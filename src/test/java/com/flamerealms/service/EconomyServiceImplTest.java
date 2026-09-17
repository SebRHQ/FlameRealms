package com.flamerealms.service;

import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Money;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.domain.TransactionRecord;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.service.fake.FakeLedgerDao;
import com.flamerealms.service.fake.FakePlayerWalletDao;
import com.flamerealms.service.fake.FakeRealmDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.UUID;

import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.fakeConnection;
import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.inline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EconomyServiceImpl}.
 *
 * <p>Uses hand-written, in-memory {@code Fake*Dao} test doubles (see
 * {@code com.flamerealms.service.fake}) for the DAO layer — no Mockito mocks
 * of the DAO interfaces, no real database, no Docker. {@link
 * AsyncDatabaseExecutor} is stubbed via {@code
 * InlineAsyncDatabaseExecutors.inline(...)} to run submitted work
 * synchronously, exactly like {@code RealmServiceImplTest}.
 *
 * <p>{@link FakeLedgerDao} needs a {@code RealmDao} to compose (matching
 * {@code JdbcLedgerDao}'s own constructor), even though
 * {@link EconomyServiceImpl} never touches a realm — a fresh, unused
 * {@link FakeRealmDao} plays that role here.
 */
final class EconomyServiceImplTest {

    private FakePlayerWalletDao walletDao;
    private FakeLedgerDao ledgerDao;
    private EconomyServiceImpl service;

    @BeforeEach
    void setUp() {
        Connection connection = fakeConnection();
        walletDao = new FakePlayerWalletDao();
        ledgerDao = new FakeLedgerDao(walletDao, new FakeRealmDao());

        AsyncDatabaseExecutor executor = inline(connection);
        service = new EconomyServiceImpl(executor, walletDao, ledgerDao);
    }

    @Test
    void depositCreditsPlayerWallet() {
        UUID player = UUID.randomUUID();

        Boolean result = service.deposit(player, Money.ofCents(500), "TEST_FAUCET").join();

        assertThat(result).isTrue();
        assertThat(walletDao.balanceOf(player)).isEqualTo(500L);
        assertThat(service.balanceOf(player).join()).isEqualTo(Money.ofCents(500L));

        // Exactly one ledger row for this one balance mutation — the whole
        // point of routing every mutation through LedgerDao's shared
        // recordAndApply* choke point.
        assertThat(ledgerDao.entries()).hasSize(1);
        TransactionRecord entry = ledgerDao.entries().get(0);
        assertThat(entry.category()).isEqualTo(TransactionCategory.FAUCET);
        assertThat(entry.targetType()).isEqualTo(LedgerEntity.PLAYER);
        assertThat(entry.targetId()).isEqualTo(player.toString());
        assertThat(entry.sourceType()).isEqualTo(LedgerEntity.SERVER);
        assertThat(entry.amountCents()).isEqualTo(500L);
    }

    @Test
    void withdrawDebitsPlayerWallet() {
        UUID player = UUID.randomUUID();
        service.deposit(player, Money.ofCents(1000), "SEED").join();

        Boolean result = service.withdraw(player, Money.ofCents(400), "TEST_SINK").join();

        assertThat(result).isTrue();
        assertThat(walletDao.balanceOf(player)).isEqualTo(600L);

        // One entry for the seeding deposit, one for this withdrawal.
        assertThat(ledgerDao.entries()).hasSize(2);
        TransactionRecord withdrawEntry = ledgerDao.entries().get(1);
        assertThat(withdrawEntry.category()).isEqualTo(TransactionCategory.SINK);
        assertThat(withdrawEntry.sourceType()).isEqualTo(LedgerEntity.PLAYER);
        assertThat(withdrawEntry.sourceId()).isEqualTo(player.toString());
        assertThat(withdrawEntry.targetType()).isEqualTo(LedgerEntity.SERVER);
        assertThat(withdrawEntry.amountCents()).isEqualTo(400L);
    }

    @Test
    void withdrawReturnsFalseNotExceptionOnInsufficientFunds() {
        UUID player = UUID.randomUUID();
        service.deposit(player, Money.ofCents(100), "SEED").join();

        Boolean result = service.withdraw(player, Money.ofCents(500), "TOO_MUCH").join();

        assertThat(result).isFalse();
        // Balance and ledger are both untouched — a rejected withdrawal
        // writes nothing.
        assertThat(walletDao.balanceOf(player)).isEqualTo(100L);
        assertThat(ledgerDao.entries()).hasSize(1); // just the seeding deposit
    }

    @Test
    void depositRejectsNonPositiveAmountsSynchronouslyBeforeAnyDispatch() {
        UUID player = UUID.randomUUID();

        assertThatThrownBy(() -> service.deposit(player, Money.ZERO, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deposit(player, Money.ofCents(-1L), "x"))
                .isInstanceOf(IllegalArgumentException.class);

        // Rejected before ever reaching the executor/DAO layer.
        assertThat(walletDao.balanceOf(player)).isEqualTo(0L);
        assertThat(ledgerDao.entries()).isEmpty();
    }

    @Test
    void withdrawRejectsNonPositiveAmountsSynchronouslyBeforeAnyDispatch() {
        UUID player = UUID.randomUUID();

        assertThatThrownBy(() -> service.withdraw(player, Money.ZERO, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withdraw(player, Money.ofCents(-50L), "x"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ledgerDao.entries()).isEmpty();
    }
}
