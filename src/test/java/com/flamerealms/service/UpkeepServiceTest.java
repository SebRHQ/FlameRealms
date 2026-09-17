package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.domain.TransactionRecord;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.service.fake.FakeLedgerDao;
import com.flamerealms.service.fake.FakePlayerWalletDao;
import com.flamerealms.service.fake.FakeRealmClaimDao;
import com.flamerealms.service.fake.FakeRealmDao;
import com.flamerealms.service.fake.FakeRealmMemberActivityDao;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.fakeConnection;
import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.inline;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UpkeepService}.
 *
 * <p>Exercises the daily upkeep charge (see class Javadoc's "Debt
 * accounting" section) through {@code runUpkeepCycle()} directly, bypassing
 * Bukkit's scheduler entirely — {@code start()}/{@code stop()} are the only
 * methods here that touch {@code Bukkit.getScheduler()}, and this suite
 * never calls them. Same fake-DAO + {@code InlineAsyncDatabaseExecutors}
 * approach as {@code TreasuryServiceImplTest}/{@code RealmServiceImplTest}.
 */
final class UpkeepServiceTest {

    private Connection connection;
    private RealmCache realmCache;
    private FakeRealmClaimDao realmClaimDao;
    private FakeRealmMemberActivityDao realmMemberActivityDao;
    private FakeRealmDao realmDao;
    private FakeLedgerDao ledgerDao;
    private PricingConfig pricingConfig;
    private UpkeepService service;

    private static final long COST_PER_CHUNK_CENTS = 100L;

    @BeforeEach
    void setUp() {
        connection = fakeConnection();
        realmCache = new RealmCache();
        realmClaimDao = new FakeRealmClaimDao();
        realmMemberActivityDao = new FakeRealmMemberActivityDao();
        realmDao = new FakeRealmDao();
        ledgerDao = new FakeLedgerDao(new FakePlayerWalletDao(), realmDao);

        pricingConfig = new PricingConfig(
                List.of(new PricingConfig.PriceTier(Integer.MAX_VALUE, 1000L)),
                List.of(
                        new PricingConfig.MultiplierTier(10, 1.0),
                        new PricingConfig.MultiplierTier(Integer.MAX_VALUE, 1.5)),
                COST_PER_CHUNK_CENTS,
                60,
                7,
                10.0,
                0.5
        );

        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("UpkeepServiceTest"));

        AsyncDatabaseExecutor asyncDatabaseExecutor = inline(connection);
        service = new UpkeepService(
                plugin, realmCache, realmClaimDao, realmMemberActivityDao, realmDao,
                ledgerDao, asyncDatabaseExecutor, pricingConfig);
    }

    private long createRealm(String name) {
        try {
            Realm inserted = realmDao.insert(connection,
                    new Realm(0L, name, name, UUID.randomUUID(), 1, Instant.now(), null));
            realmCache.put(inserted);
            return inserted.id();
        } catch (java.sql.SQLException e) {
            // FakeRealmDao.insert never actually throws this — the checked
            // signature is only there to satisfy RealmDao's interface.
            throw new AssertionError(e);
        }
    }

    @Test
    void computeDailyUpkeepCentsMatchesTheDocumentedFormula() {
        int claimCount = 5;
        int activePopulation = 10;

        long territoryCost = claimCount * COST_PER_CHUNK_CENTS;
        double territoryMultiplier = 1.0; // claimCount=5 <= tier max 10
        double activePopulationMultiplier = 1.0 + 0.5 * (1 - Math.exp(-activePopulation / 10.0));
        long expected = Math.round(territoryCost * territoryMultiplier * activePopulationMultiplier);

        assertThat(service.computeDailyUpkeepCents(claimCount, activePopulation)).isEqualTo(expected);
    }

    @Test
    void computeDailyUpkeepCentsIsZeroWithNoClaims() {
        assertThat(service.computeDailyUpkeepCents(0, 42)).isZero();
    }

    @Test
    void activePopulationMultiplierSaturatesRatherThanGrowingWithoutBound() {
        long atModeratePopulation = service.computeDailyUpkeepCents(5, 10);
        long atHugePopulation = service.computeDailyUpkeepCents(5, 100_000);
        long theoreticalUncappedCeiling = Math.round(5 * COST_PER_CHUNK_CENTS * 1.0 * 1.5); // 1.0 + m, m=0.5

        // A vastly larger population still can't push the charge past the
        // 1.0 + m asymptote — the whole point of the saturating shape.
        assertThat(atHugePopulation).isLessThanOrEqualTo(theoreticalUncappedCeiling);
        assertThat(atHugePopulation).isGreaterThan(atModeratePopulation);
    }

    @Test
    void activePopulationMultiplierHasStrictlyShrinkingMarginalIncreaseAsPopulationGrows() {
        int claimCount = 20; // triggers the second territory-multiplier tier (1.5), same for every sample below
        int step = 10; // matches this test's saturationConstant (k=10.0)

        // Five equally-spaced sample points -> four successive deltas. Per
        // computeDailyUpkeepCents's Javadoc, the curve's derivative is
        // strictly decreasing, so each later delta must be strictly smaller
        // than the one before it - well beyond the +/-1-cent noise
        // Math.round(...) could introduce, given this test's cost/tier
        // constants.
        long charge0 = service.computeDailyUpkeepCents(claimCount, 0);
        long charge1 = service.computeDailyUpkeepCents(claimCount, step);
        long charge2 = service.computeDailyUpkeepCents(claimCount, 2 * step);
        long charge3 = service.computeDailyUpkeepCents(claimCount, 3 * step);
        long charge4 = service.computeDailyUpkeepCents(claimCount, 4 * step);

        long delta1 = charge1 - charge0;
        long delta2 = charge2 - charge1;
        long delta3 = charge3 - charge2;
        long delta4 = charge4 - charge3;

        assertThat(delta1).isGreaterThan(delta2);
        assertThat(delta2).isGreaterThan(delta3);
        assertThat(delta3).isGreaterThan(delta4);
        // Every marginal step is still a genuine (if shrinking) increase, not zero/negative.
        assertThat(delta4).isPositive();
    }

    @Test
    void chargesFullTotalAndZeroesDebtWhenTreasuryCanCoverIt() {
        long realmId = createRealm("alpha");
        realmClaimDao.seedClaims(realmId, 5);
        realmDao.incrementUpkeepDebt(connection, realmId, 200L); // pre-existing debt
        realmDao.tryAdjustBalance(connection, realmId, 100_000L); // plenty of treasury

        long cycleUpkeep = service.computeDailyUpkeepCents(5, 0);
        long expectedTotalCharge = 200L + cycleUpkeep;

        service.runUpkeepCycle().join();

        assertThat(realmDao.findBalance(connection, realmId)).isEqualTo(100_000L - expectedTotalCharge);
        assertThat(realmDao.findUpkeepDebt(connection, realmId)).isZero();

        List<TransactionRecord> entries = ledgerDao.entries();
        assertThat(entries).hasSize(1);
        TransactionRecord entry = entries.get(0);
        assertThat(entry.category()).isEqualTo(TransactionCategory.SINK);
        assertThat(entry.reason()).isEqualTo("TERRITORY_UPKEEP");
        assertThat(entry.amountCents()).isEqualTo(expectedTotalCharge);
        assertThat(entry.sourceType()).isEqualTo(LedgerEntity.REALM);
        assertThat(entry.sourceId()).isEqualTo(String.valueOf(realmId));
        assertThat(entry.targetType()).isEqualTo(LedgerEntity.SERVER);
    }

    @Test
    void addsOnlyThisCyclesCostToDebtWhenTreasuryCannotCoverIt() {
        long realmId = createRealm("beta");
        realmClaimDao.seedClaims(realmId, 5);
        realmDao.incrementUpkeepDebt(connection, realmId, 200L); // pre-existing debt
        // No balance deposited: treasury is 0, can't cover any positive charge.

        long cycleUpkeep = service.computeDailyUpkeepCents(5, 0);

        service.runUpkeepCycle().join();

        // Debt grows by exactly this cycle's cost, NOT the total charge
        // (200 existing + cycleUpkeep) — the old debt is already accounted
        // for and must not be double-counted.
        assertThat(realmDao.findUpkeepDebt(connection, realmId)).isEqualTo(200L + cycleUpkeep);
        assertThat(realmDao.findBalance(connection, realmId)).isZero();
        assertThat(ledgerDao.entries()).isEmpty();
    }

    @Test
    void skipsTheLedgerEntirelyWhenNothingIsOwed() {
        long realmId = createRealm("gamma");
        // No claims, no debt — totalCharge is 0.

        service.runUpkeepCycle().join();

        assertThat(ledgerDao.entries()).isEmpty();
        assertThat(realmDao.findUpkeepDebt(connection, realmId)).isZero();
    }

    @Test
    void oneRealmsUnpaidUpkeepDoesNotAffectAnotherRealm() {
        long richRealmId = createRealm("rich");
        realmClaimDao.seedClaims(richRealmId, 5);
        realmDao.tryAdjustBalance(connection, richRealmId, 100_000L);

        long poorRealmId = createRealm("poor");
        realmClaimDao.seedClaims(poorRealmId, 5);
        // No balance for the poor realm.

        service.runUpkeepCycle().join();

        assertThat(realmDao.findUpkeepDebt(connection, richRealmId)).isZero();
        assertThat(realmDao.findUpkeepDebt(connection, poorRealmId))
                .isEqualTo(service.computeDailyUpkeepCents(5, 0));
        assertThat(ledgerDao.entries()).hasSize(1); // only the rich realm's charge landed
    }
}
