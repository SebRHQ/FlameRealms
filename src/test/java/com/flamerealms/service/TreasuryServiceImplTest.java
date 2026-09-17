package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Money;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.domain.TransactionRecord;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.fake.FakeLedgerDao;
import com.flamerealms.service.fake.FakePlayerWalletDao;
import com.flamerealms.service.fake.FakeRealmDao;
import com.flamerealms.service.fake.FakeRealmMemberDao;
import com.flamerealms.service.fake.FakeRealmRankDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.fakeConnection;
import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.inline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TreasuryServiceImpl}.
 *
 * <p>Same fake-DAO approach as {@code RealmServiceImplTest}/{@code
 * EconomyServiceImplTest}: hand-written, in-memory {@code Fake*Dao} doubles
 * plus {@code InlineAsyncDatabaseExecutors.inline(...)} running submitted
 * work synchronously against a mocked {@link Connection}.
 *
 * <p><b>These fakes do not simulate transaction rollback.</b>
 * {@code TreasuryServiceImpl}'s {@code inTransaction} calls {@code
 * setAutoCommit}/{@code commit}/{@code rollback} on the (mocked) {@code
 * Connection}, but none of the fake DAOs observe those calls — each fake
 * mutates its in-memory state immediately, unconditionally. That is fine for
 * every scenario below except one: when {@link #withdraw} credits the
 * player first and only then discovers the realm's treasury can't cover the
 * debit, a real database would roll back that tentative player credit too;
 * these fakes cannot undo it. {@link
 * #withdrawReturnsFalseWhenTreasuryHasInsufficientFunds} therefore only
 * asserts the returned {@code false}, not the wallet/ledger side effects of
 * that specific failure path — true end-to-end atomicity under a real
 * transaction is covered by the Testcontainers integration test instead.
 * Every other scenario here fails before any DAO write happens at all (a
 * synchronous permission check, or the player-wallet debit itself reporting
 * insufficient funds before the realm side is ever touched), so those are
 * asserted fully.
 */
final class TreasuryServiceImplTest {

    private Connection connection;
    private RealmCache realmCache;
    private FakeRealmDao realmDao;
    private FakeRealmMemberDao realmMemberDao;
    private FakeRealmRankDao realmRankDao;
    private FakePlayerWalletDao walletDao;
    private FakeLedgerDao ledgerDao;
    private TreasuryServiceImpl service;

    private long realmId;

    @BeforeEach
    void setUp() throws Exception {
        connection = fakeConnection();
        realmCache = new RealmCache();
        realmDao = new FakeRealmDao();
        realmMemberDao = new FakeRealmMemberDao();
        realmRankDao = new FakeRealmRankDao();
        walletDao = new FakePlayerWalletDao();
        ledgerDao = new FakeLedgerDao(walletDao, realmDao);

        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmId = realm.id();
        realmCache.put(realm);

        AsyncDatabaseExecutor executor = inline(connection);
        service = new TreasuryServiceImpl(executor, realmCache, realmDao, realmMemberDao, realmRankDao, ledgerDao);
    }

    private UUID memberWithPermissions(long permissions) throws Exception {
        RealmRank rank = realmRankDao.insert(connection,
                new RealmRank(0L, realmId, "Rank-" + UUID.randomUUID(), 0, permissions, false));
        UUID player = UUID.randomUUID();
        realmMemberDao.insert(connection, new RealmMember(realmId, player, rank.id(), Instant.now()));
        return player;
    }

    @Test
    void depositDebitsPlayerAndCreditsRealmAtomically() throws Exception {
        UUID actor = memberWithPermissions(0L); // deposit needs no permission
        walletDao.ensureExists(connection, actor);
        walletDao.tryAdjustBalance(connection, actor, 1000L);

        Boolean result = service.deposit(realmId, actor, Money.ofCents(400)).join();

        assertThat(result).isTrue();
        assertThat(walletDao.balanceOf(actor)).isEqualTo(600L);
        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(400L);

        List<TransactionRecord> entries = ledgerDao.entries();
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> e.category() == TransactionCategory.TRANSFER);
        assertThat(entries).allMatch(e -> "REALM_DEPOSIT".equals(e.reason()));

        TransactionRecord playerSide = entries.get(0);
        assertThat(playerSide.sourceType()).isEqualTo(LedgerEntity.PLAYER);
        assertThat(playerSide.sourceId()).isEqualTo(actor.toString());
        assertThat(playerSide.targetType()).isEqualTo(LedgerEntity.REALM);

        TransactionRecord realmSide = entries.get(1);
        assertThat(realmSide.targetType()).isEqualTo(LedgerEntity.REALM);
        assertThat(realmSide.sourceType()).isEqualTo(LedgerEntity.PLAYER);
    }

    @Test
    void depositFailedPlayerDebitNeverLeavesRealmCredited() throws Exception {
        UUID actor = memberWithPermissions(0L);
        walletDao.ensureExists(connection, actor);
        walletDao.tryAdjustBalance(connection, actor, 100L); // not enough for a 400 deposit

        Boolean result = service.deposit(realmId, actor, Money.ofCents(400)).join();

        assertThat(result).isFalse();
        assertThat(walletDao.balanceOf(actor)).isEqualTo(100L); // untouched
        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(0L); // never credited
        assertThat(ledgerDao.entries()).isEmpty(); // the player-side call failed before any insert
    }

    @Test
    void withdrawCreditsPlayerAndDebitsRealmAtomically() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.WITHDRAW.bit());
        realmDao.tryAdjustBalance(connection, realmId, 1000L);

        Boolean result = service.withdraw(realmId, actor, Money.ofCents(300)).join();

        assertThat(result).isTrue();
        assertThat(walletDao.balanceOf(actor)).isEqualTo(300L);
        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(700L);

        List<TransactionRecord> entries = ledgerDao.entries();
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> e.category() == TransactionCategory.TRANSFER);
        assertThat(entries).allMatch(e -> "REALM_WITHDRAW".equals(e.reason()));
    }

    @Test
    void withdrawRejectsActorWithoutWithdrawPermissionWithoutTouchingEitherBalance() throws Exception {
        UUID actor = memberWithPermissions(0L); // no WITHDRAW bit
        realmDao.tryAdjustBalance(connection, realmId, 1000L);

        assertThatThrownBy(() -> service.withdraw(realmId, actor, Money.ofCents(300)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MissingPermissionException.class);

        assertThat(walletDao.balanceOf(actor)).isEqualTo(0L);
        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(1000L);
        assertThat(ledgerDao.entries()).isEmpty();
    }

    @Test
    void withdrawReturnsFalseWhenTreasuryHasInsufficientFunds() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.WITHDRAW.bit());
        // Realm treasury left at its default 0 balance - can't cover any withdrawal.

        Boolean result = service.withdraw(realmId, actor, Money.ofCents(50)).join();

        assertThat(result).isFalse();
        // See this class's Javadoc: the tentative player credit that happens
        // before the realm-side check is not rolled back by these
        // non-transactional fakes, so wallet/ledger state is deliberately
        // not asserted here.
    }

    @Test
    void depositAndWithdrawRejectNonPositiveAmountsSynchronouslyBeforeAnyDispatch() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.WITHDRAW.bit());

        assertThatThrownBy(() -> service.deposit(realmId, actor, Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deposit(realmId, actor, Money.ofCents(-1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withdraw(realmId, actor, Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withdraw(realmId, actor, Money.ofCents(-1L)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ledgerDao.entries()).isEmpty();
    }
}
