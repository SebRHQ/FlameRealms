package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmClaim;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.domain.TransactionRecord;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.service.exception.ClaimNotContiguousException;
import com.flamerealms.service.exception.InsufficientTreasuryFundsException;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.fake.FakeLedgerDao;
import com.flamerealms.service.fake.FakePlayerWalletDao;
import com.flamerealms.service.fake.FakeRealmClaimDao;
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
 * Unit tests for {@link ClaimServiceImpl}.
 *
 * <p>Same fake-DAO approach as {@code TreasuryServiceImplTest}/{@code
 * RealmServiceImplTest}: hand-written, in-memory {@code Fake*Dao} doubles
 * plus {@code InlineAsyncDatabaseExecutors.inline(...)} running submitted
 * work synchronously against a mocked {@link Connection}. See {@code
 * TreasuryServiceImplTest}'s class Javadoc for why these fakes do not
 * simulate transaction rollback — the same caveat applies here (not that it
 * matters for any scenario below: {@link #purchaseClaimRejectsWhenTreasuryCannotAffordPrice}
 * fails before any DAO write happens at all, since the ledger debit is the
 * very thing that reports "can't cover it").
 */
final class ClaimServiceImplTest {

    private Connection connection;
    private RealmCache realmCache;
    private FakeRealmClaimDao realmClaimDao;
    private FakeRealmMemberDao realmMemberDao;
    private FakeRealmRankDao realmRankDao;
    private FakeRealmDao realmDao;
    private FakeLedgerDao ledgerDao;
    private PricingConfig pricingConfig;
    private ClaimServiceImpl service;

    private long realmId;

    /** claim #1 -> 100c, claims #2-3 -> 200c, claim #4+ -> 300c. */
    private static final long TIER_1_PRICE = 100L;
    private static final long TIER_2_PRICE = 200L;
    private static final long TIER_3_PRICE = 300L;

    @BeforeEach
    void setUp() throws Exception {
        connection = fakeConnection();
        realmCache = new RealmCache();
        realmClaimDao = new FakeRealmClaimDao();
        realmMemberDao = new FakeRealmMemberDao();
        realmRankDao = new FakeRealmRankDao();
        realmDao = new FakeRealmDao();
        ledgerDao = new FakeLedgerDao(new FakePlayerWalletDao(), realmDao);

        pricingConfig = new PricingConfig(
                List.of(
                        new PricingConfig.PriceTier(1, TIER_1_PRICE),
                        new PricingConfig.PriceTier(3, TIER_2_PRICE),
                        new PricingConfig.PriceTier(Integer.MAX_VALUE, TIER_3_PRICE)),
                List.of(new PricingConfig.MultiplierTier(Integer.MAX_VALUE, 1.0)),
                100L, 60, 7, 10.0, 0.5, 50000L, 3
        );

        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmId = realm.id();
        realmCache.put(realm);

        AsyncDatabaseExecutor executor = inline(connection);
        service = new ClaimServiceImpl(
                executor, realmCache, realmClaimDao, realmMemberDao, realmRankDao, ledgerDao, pricingConfig);
    }

    private UUID memberWithPermissions(long permissions) throws Exception {
        RealmRank rank = realmRankDao.insert(connection,
                new RealmRank(0L, realmId, "Rank-" + UUID.randomUUID(), 0, permissions, false));
        UUID player = UUID.randomUUID();
        realmMemberDao.insert(connection, new RealmMember(realmId, player, rank.id(), Instant.now()));
        return player;
    }

    @Test
    void purchaseClaimSucceedsForARealmsFirstEverClaimWithNoContiguityRequirement() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.CLAIM.bit());
        realmDao.tryAdjustBalance(connection, realmId, 10_000L);

        RealmClaim claim = service.purchaseClaim(realmId, actor, "world", 42, -17).join();

        assertThat(claim.realmId()).isEqualTo(realmId);
        assertThat(claim.world()).isEqualTo("world");
        assertThat(claim.chunkX()).isEqualTo(42);
        assertThat(claim.chunkZ()).isEqualTo(-17);
        assertThat(claim.pricePaidCents()).isEqualTo(TIER_1_PRICE);

        assertThat(realmClaimDao.findByRealm(connection, realmId)).hasSize(1);
        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(10_000L - TIER_1_PRICE);
        assertThat(realmCache.claimsOf(realmId)).containsExactly(claim.coordinate());

        List<TransactionRecord> entries = ledgerDao.entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).reason()).isEqualTo("CLAIM_PURCHASE");
    }

    @Test
    void purchaseClaimRejectsASecondClaimNotAdjacentToAnyExistingClaim() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.CLAIM.bit());
        realmDao.tryAdjustBalance(connection, realmId, 10_000L);

        service.purchaseClaim(realmId, actor, "world", 0, 0).join();
        long balanceAfterFirstClaim = realmDao.findBalance(connection, realmId);

        // (5, 5) is nowhere near (0, 0) - not orthogonally adjacent.
        assertThatThrownBy(() -> service.purchaseClaim(realmId, actor, "world", 5, 5).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClaimNotContiguousException.class);

        assertThat(realmClaimDao.findByRealm(connection, realmId)).hasSize(1);
        // The rejected attempt never reached the ledger debit - contiguity
        // is checked before pricing/payment.
        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(balanceAfterFirstClaim);
    }

    @Test
    void purchaseClaimAcceptsASecondClaimAdjacentToAnExistingClaim() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.CLAIM.bit());
        realmDao.tryAdjustBalance(connection, realmId, 10_000L);

        service.purchaseClaim(realmId, actor, "world", 0, 0).join();
        RealmClaim second = service.purchaseClaim(realmId, actor, "world", 1, 0).join();

        assertThat(second.chunkX()).isEqualTo(1);
        assertThat(second.chunkZ()).isEqualTo(0);
        assertThat(realmClaimDao.findByRealm(connection, realmId)).hasSize(2);
    }

    @Test
    void purchaseClaimRejectsAnActorLackingTheClaimPermissionWithoutTouchingTreasuryOrInsertingAClaim()
            throws Exception {
        UUID actor = memberWithPermissions(0L); // no CLAIM bit
        realmDao.tryAdjustBalance(connection, realmId, 10_000L);

        assertThatThrownBy(() -> service.purchaseClaim(realmId, actor, "world", 0, 0).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MissingPermissionException.class);

        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(10_000L);
        assertThat(realmClaimDao.findByRealm(connection, realmId)).isEmpty();
        assertThat(ledgerDao.entries()).isEmpty();
    }

    @Test
    void purchaseClaimRejectsWhenTreasuryCannotAffordPrice() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.CLAIM.bit());
        // Treasury left at its default 0 balance - can't cover even tier 1's price.

        assertThatThrownBy(() -> service.purchaseClaim(realmId, actor, "world", 0, 0).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(InsufficientTreasuryFundsException.class)
                .cause()
                .satisfies(cause -> {
                    InsufficientTreasuryFundsException e = (InsufficientTreasuryFundsException) cause;
                    assertThat(e.realmId()).isEqualTo(realmId);
                    assertThat(e.priceCents()).isEqualTo(TIER_1_PRICE);
                });

        // Balance explicitly asserted unchanged, not just "the claim failed".
        assertThat(realmDao.findBalance(connection, realmId)).isZero();
        assertThat(realmClaimDao.findByRealm(connection, realmId)).isEmpty();
        assertThat(ledgerDao.entries()).isEmpty();
    }

    @Test
    void purchaseClaimPriceReflectsTheCorrectPricingTierForEachClaimCount() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.CLAIM.bit());
        realmDao.tryAdjustBalance(connection, realmId, 10_000L);

        RealmClaim first = service.purchaseClaim(realmId, actor, "world", 0, 0).join();   // 1st claim
        RealmClaim second = service.purchaseClaim(realmId, actor, "world", 1, 0).join();  // 2nd claim
        RealmClaim third = service.purchaseClaim(realmId, actor, "world", 2, 0).join();   // 3rd claim
        RealmClaim fourth = service.purchaseClaim(realmId, actor, "world", 3, 0).join();  // 4th claim

        assertThat(first.pricePaidCents()).isEqualTo(TIER_1_PRICE);
        assertThat(second.pricePaidCents()).isEqualTo(TIER_2_PRICE);
        assertThat(third.pricePaidCents()).isEqualTo(TIER_2_PRICE);
        assertThat(fourth.pricePaidCents()).isEqualTo(TIER_3_PRICE);

        long totalSpent = TIER_1_PRICE + TIER_2_PRICE + TIER_2_PRICE + TIER_3_PRICE;
        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(10_000L - totalSpent);
    }

    @Test
    void unclaimChunkRemovesTheClaimAndCreatesNoLedgerEntry() throws Exception {
        realmClaimDao.insert(connection, new RealmClaim(0L, realmId, "world", 0, 0, Instant.now(), TIER_1_PRICE));
        UUID actor = memberWithPermissions(RealmPermission.UNCLAIM.bit());

        Boolean removed = service.unclaimChunk(realmId, actor, "world", 0, 0).join();

        assertThat(removed).isTrue();
        assertThat(realmClaimDao.findByRealm(connection, realmId)).isEmpty();
        // 0%-refund decision: no ledger entry at all for an unclaim.
        assertThat(ledgerDao.entries()).isEmpty();
    }

    @Test
    void unclaimChunkRejectsAnActorLackingTheUnclaimPermission() throws Exception {
        realmClaimDao.insert(connection, new RealmClaim(0L, realmId, "world", 0, 0, Instant.now(), TIER_1_PRICE));
        UUID actor = memberWithPermissions(RealmPermission.CLAIM.bit()); // has CLAIM, not UNCLAIM

        assertThatThrownBy(() -> service.unclaimChunk(realmId, actor, "world", 0, 0).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MissingPermissionException.class);

        assertThat(realmClaimDao.findByRealm(connection, realmId)).hasSize(1);
    }

    @Test
    void unclaimChunkReturnsFalseRatherThanThrowingForAChunkTheRealmHasNotClaimed() throws Exception {
        UUID actor = memberWithPermissions(RealmPermission.UNCLAIM.bit());

        Boolean removed = service.unclaimChunk(realmId, actor, "world", 99, 99).join();

        assertThat(removed).isFalse();
        assertThat(ledgerDao.entries()).isEmpty();
    }
}
