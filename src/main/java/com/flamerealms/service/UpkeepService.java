package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmClaim;
import com.flamerealms.domain.TransactionCategory;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.RealmClaimDao;
import com.flamerealms.persistence.dao.RealmDao;
import com.flamerealms.persistence.dao.RealmMemberActivityDao;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;

/**
 * The daily territory-upkeep charge:
 *
 * <pre>
 * Daily Upkeep = territoryCost(claims) x territoryMultiplier(claims)
 *                x activePopulationMultiplier(activePopulation)
 * </pre>
 *
 * <p>See {@link #territoryCost}, {@link #territoryMultiplier} and {@link
 * #activePopulationMultiplier} for each factor; {@link #computeDailyUpkeepCents}
 * composes all three. This milestone's {@code activePopulation} signal is
 * presence-only ({@link RealmMemberActivityDao#countActiveMembers}) — there
 * is no {@code contribution_events} table yet (see that DAO's own Javadoc),
 * so the "presence UNION contribution" signal {@code pricing.yml} documents
 * degrades to presence alone until a later milestone adds the other half.
 *
 * <p><b>Debt accounting.</b> Each cycle, per realm: read the realm's existing
 * {@code upkeep_debt_cents}, add this cycle's computed cost to get {@code
 * totalCharge}, and attempt to charge the treasury for the FULL {@code
 * totalCharge} in one shot via {@link LedgerDao#recordAndApplyToRealm} —
 * there is no partial-payment primitive anywhere in this codebase, and this
 * milestone does not add one. Success zeroes the debt (the whole thing, old
 * debt plus this cycle, was just paid off together); failure (insufficient
 * treasury funds) leaves the ledger and balance completely untouched and
 * instead adds exactly this cycle's cost — not the already-accounted-for old
 * debt — onto {@code upkeep_debt_cents}, so debt grows by one cycle's worth
 * per unpaid day, never double-counted.
 *
 * <p><b>Upkeep-debt chunk release.</b> A realm's consecutive-failed-upkeep-
 * cycle count ({@code realms.upkeep_unpaid_cycles}, read/written via {@link
 * RealmDao#incrementUnpaidUpkeepCycles}/{@link RealmDao#resetUnpaidUpkeepCycles}/
 * {@link RealmDao#findUnpaidUpkeepCycles}) is reset to zero alongside the debt
 * itself whenever a cycle's charge is fully paid, and incremented by one
 * alongside the debt whenever it isn't. If, after incrementing, that count
 * exceeds {@link PricingConfig#debtReleaseThresholdCycles()}, exactly ONE of
 * the realm's claims — the most recently claimed one ({@link
 * RealmClaimDao#findMostRecentByRealm}) — is released in the SAME transaction
 * as that cycle's charge, via {@link RealmClaimDao#delete}. A realm with zero
 * claims left simply keeps accruing an ever-climbing unpaid-cycle count with
 * nothing further this mechanism can do about it — that is not treated as an
 * error. See {@link #chargeUpkeep} for exactly where this happens.
 *
 * <p><b>The {@code onClaimReleased} callback.</b> Actually applying a released
 * claim's real-world consequences — {@link RealmCache#removeClaim} and, more
 * importantly, un-protecting the chunk's WorldGuard region — cannot happen
 * inside {@link #chargeUpkeep}'s transaction (it runs off the main thread,
 * inside {@link AsyncDatabaseExecutor#submit}) nor inside this class at all:
 * {@code com.flamerealms.service} classes must stay Bukkit/WorldGuard-free
 * (see {@code ClaimServiceImpl}'s own class Javadoc for why), so this class
 * cannot take a {@code ClaimProtectionService} reference directly. Instead,
 * the constructor takes a small functional callback, {@code onClaimReleased},
 * of type {@code BiConsumer<String, ChunkCoordinate>} (the realm id, as a
 * string, and the released chunk). {@link #chargeUpkeep} applies {@link
 * RealmCache#removeClaim} itself (that much is layering-clean — {@link
 * RealmCache} is already a dependency of this class) once its transaction has
 * committed, then invokes {@code onClaimReleased}, hopped onto the main
 * thread via {@code Bukkit.getScheduler().runTask(plugin, ...)} (this class
 * already holds a {@link JavaPlugin} reference for its own scheduled task
 * registration in {@link #start()}, reused here), so the actual caller —
 * {@code FlameRealmsPlugin}, wiring this to {@code
 * ClaimProtectionService#unprotectClaim} — can safely touch Bukkit/WorldGuard
 * APIs in response.
 *
 * <p><b>No auto-unclaim before this milestone.</b> Earlier milestones left
 * accumulating unpaid upkeep debt with no consequence beyond the debt figure
 * itself growing (see {@code TODO.md}, which listed it as pending); the
 * mechanism above is what closes that gap.
 *
 * <p><b>Per-realm isolation.</b> Every realm's charge runs as its own {@code
 * AsyncDatabaseExecutor.submit(...)} call — its own connection, its own
 * transaction, independent of every other realm's. One realm failing to pay
 * (or hitting a persistence error) can never affect another realm's charge;
 * nothing here aggregates outcomes across realms.
 *
 * <p><b>Iterating "every active realm."</b> {@link RealmCache} previously
 * exposed only single-realm lookups (by id, by name, by player); {@link
 * RealmCache#values()} was added specifically to back this class's daily
 * cycle, following that cache's existing read-method conventions (a plain,
 * unmodifiable snapshot view, no new write path).
 */
public final class UpkeepService {

    /** 24 hours, in ticks (20 ticks/sec). Exact wall-clock/timezone alignment is not the point of this milestone. */
    static final long UPKEEP_PERIOD_TICKS = 20L * 60 * 60 * 24;

    private final JavaPlugin plugin;
    private final RealmCache realmCache;
    private final RealmClaimDao realmClaimDao;
    private final RealmMemberActivityDao realmMemberActivityDao;
    private final RealmDao realmDao;
    private final LedgerDao ledgerDao;
    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final PricingConfig pricingConfig;
    private final BiConsumer<String, ChunkCoordinate> onClaimReleased;
    private final Logger logger;

    private BukkitTask upkeepTask;

    public UpkeepService(
            JavaPlugin plugin,
            RealmCache realmCache,
            RealmClaimDao realmClaimDao,
            RealmMemberActivityDao realmMemberActivityDao,
            RealmDao realmDao,
            LedgerDao ledgerDao,
            AsyncDatabaseExecutor asyncDatabaseExecutor,
            PricingConfig pricingConfig,
            BiConsumer<String, ChunkCoordinate> onClaimReleased
    ) {
        this.plugin = plugin;
        this.realmCache = realmCache;
        this.realmClaimDao = realmClaimDao;
        this.realmMemberActivityDao = realmMemberActivityDao;
        this.realmDao = realmDao;
        this.ledgerDao = ledgerDao;
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.pricingConfig = pricingConfig;
        this.onClaimReleased = onClaimReleased;
        this.logger = plugin.getLogger();
    }

    /**
     * Registers the daily repeating upkeep task. Call once, from {@code
     * onEnable()} — {@code FlameRealmsPlugin} is expected to construct this
     * class and call this method; that wiring is not done here (see this
     * package's other new service, {@code ActivityTrackingService}, for the
     * same note).
     */
    public void start() {
        upkeepTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::runUpkeepCycle, UPKEEP_PERIOD_TICKS, UPKEEP_PERIOD_TICKS);
    }

    /** Cancels the daily repeating task. Call from {@code onDisable()}. */
    public void stop() {
        if (upkeepTask != null) {
            upkeepTask.cancel();
            upkeepTask = null;
        }
    }

    /**
     * Runs one upkeep cycle: for every currently-cached active realm,
     * dispatches its own independent charge transaction. Returns a future
     * that completes once every realm's attempt has finished (success or
     * failure alike) purely so tests can await the whole cycle — the
     * scheduler-driven {@link #start()} path ignores it, matching every
     * other fire-and-forget background task in this project. Package-visible
     * so a test can invoke it directly without going through Bukkit's
     * scheduler.
     */
    CompletableFuture<Void> runUpkeepCycle() {
        List<Realm> realms = List.copyOf(realmCache.values());
        LocalDate activeSince = LocalDate.now().minusDays(pricingConfig.rollingWindowDays());

        CompletableFuture<?>[] futures = realms.stream()
                .map(realm -> chargeUpkeep(realm.id(), activeSince))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    /**
     * One realm's independent charge transaction: see class Javadoc's "Debt
     * accounting" and "Upkeep-debt chunk release" sections. Never throws;
     * failures are logged and swallowed into a completed future so one
     * realm's problem never propagates into {@link #runUpkeepCycle}'s
     * aggregate future.
     *
     * <p>If this cycle's payment fails and pushes the realm's unpaid-cycle
     * count past {@link PricingConfig#debtReleaseThresholdCycles()}, the
     * realm's most-recently-claimed chunk is released inside the same
     * transaction. {@link RealmCache#removeClaim} and {@code onClaimReleased}
     * are then applied strictly after that transaction has committed — see
     * class Javadoc's "The {@code onClaimReleased} callback" section for why.
     */
    private CompletableFuture<Void> chargeUpkeep(long realmId, LocalDate activeSince) {
        return asyncDatabaseExecutor.<Optional<ChunkCoordinate>>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    int claimCount = realmClaimDao.countByRealm(conn, realmId);
                    int activePopulation = realmMemberActivityDao.countActiveMembers(
                            conn, realmId, activeSince, pricingConfig.presenceThresholdMinutes());
                    long cycleUpkeepCents = computeDailyUpkeepCents(claimCount, activePopulation);

                    long existingDebtCents = realmDao.findUpkeepDebt(conn, realmId);
                    long totalChargeCents = existingDebtCents + cycleUpkeepCents;

                    if (totalChargeCents <= 0) {
                        // Nothing owed (no claims, no lingering debt) — skip
                        // the ledger entirely rather than record a
                        // meaningless zero-amount SINK transaction for every
                        // claim-less realm every day.
                        return Optional.<ChunkCoordinate>empty();
                    }

                    boolean paid = ledgerDao.recordAndApplyToRealm(
                            conn, realmId, -totalChargeCents, TransactionCategory.SINK,
                            "TERRITORY_UPKEEP", LedgerEntity.SERVER, null);

                    if (paid) {
                        realmDao.resetUpkeepDebt(conn, realmId);
                        realmDao.resetUnpaidUpkeepCycles(conn, realmId);
                        return Optional.<ChunkCoordinate>empty();
                    }

                    realmDao.incrementUpkeepDebt(conn, realmId, cycleUpkeepCents);
                    // Read the pre-increment count once, then track the
                    // post-increment count locally — avoids a second read
                    // back through RealmDao for the same value.
                    int previousUnpaidCycles = realmDao.findUnpaidUpkeepCycles(conn, realmId);
                    realmDao.incrementUnpaidUpkeepCycles(conn, realmId);
                    int unpaidCyclesNow = previousUnpaidCycles + 1;

                    if (unpaidCyclesNow <= pricingConfig.debtReleaseThresholdCycles()) {
                        return Optional.<ChunkCoordinate>empty();
                    }

                    Optional<RealmClaim> mostRecentClaim = realmClaimDao.findMostRecentByRealm(conn, realmId);
                    if (mostRecentClaim.isEmpty()) {
                        // Nothing left to lose — the unpaid-cycles count keeps
                        // climbing but there is nothing further this
                        // mechanism can do about it. Not an error.
                        return Optional.<ChunkCoordinate>empty();
                    }

                    RealmClaim released = mostRecentClaim.get();
                    realmClaimDao.delete(conn, realmId, released.world(), released.chunkX(), released.chunkZ());
                    return Optional.of(released.coordinate());
                });
            } catch (SQLException e) {
                throw new RuntimeException("Failed to charge upkeep for realm " + realmId, e);
            }
        }).<Void>thenApply(releasedClaim -> {
            releasedClaim.ifPresent(coordinate -> {
                realmCache.removeClaim(coordinate);
                if (onClaimReleased != null) {
                    Bukkit.getScheduler().runTask(plugin,
                            () -> onClaimReleased.accept(String.valueOf(realmId), coordinate));
                }
            });
            return null;
        }).exceptionally(error -> {
            logger.warning("Failed to charge upkeep for realm " + realmId + ": " + error.getMessage());
            return null;
        });
    }

    /**
     * {@code territoryCost(claims) x territoryMultiplier(claims) x
     * activePopulationMultiplier(activePopulation)}, rounded to the nearest
     * cent. Package-visible for direct unit testing of the formula, with no
     * database or Bukkit involved.
     */
    long computeDailyUpkeepCents(int claimCount, int activePopulation) {
        long territoryCostCents = territoryCost(claimCount);
        double territoryMultiplier = territoryMultiplier(claimCount);
        double activePopulationMultiplier = activePopulationMultiplier(activePopulation);
        return Math.round(territoryCostCents * territoryMultiplier * activePopulationMultiplier);
    }

    /** {@code claimCount x PricingConfig.costPerChunkCents()}. */
    private long territoryCost(int claimCount) {
        return claimCount * pricingConfig.costPerChunkCents();
    }

    /** {@code PricingConfig}'s territory-multiplier-tiers lookup, the same tiered-lookup shape as the claim purchase price tiers. */
    private double territoryMultiplier(int claimCount) {
        return pricingConfig.territoryMultiplier(claimCount);
    }

    /**
     * {@code 1.0 + m * (1 - exp(-activePopulation / k))}, where {@code m} is
     * {@code PricingConfig.maxMultiplierBonus()} and {@code k} is {@code
     * PricingConfig.saturationConstant()}.
     *
     * <p>This shape is deliberately moderate and saturating rather than
     * linear/runaway:
     * <ul>
     *   <li><b>Bounded.</b> As {@code activePopulation -> infinity}, the
     *       exponential term -&gt; 0, so the multiplier approaches {@code
     *       1.0 + m} asymptotically and never exceeds it — an ordinary
     *       realm's upkeep can never blow up just because it has a lot of
     *       active members.</li>
     *   <li><b>Diminishing marginal cost.</b> The derivative with respect to
     *       {@code activePopulation} is {@code (m / k) * exp(-activePopulation / k)}
     *       — strictly positive but strictly decreasing. Each additional
     *       active member adds less to the multiplier than the one before
     *       it, which is exactly "small marginal cost per additional active
     *       member" rather than a flat (linear) or growing per-member cost.</li>
     *   <li><b>Gentle for a small, ordinary group.</b> Near {@code
     *       activePopulation = 0} the curve is close to linear with slope
     *       {@code m / k} (a first-order Taylor approximation of {@code 1 -
     *       exp(-x/k)} is {@code x/k}), so a small realm's bonus multiplier
     *       grows roughly proportionally at first — no cliff, no early
     *       penalty — before curving over as the realm's active population
     *       approaches and passes {@code k}.</li>
     * </ul>
     */
    private double activePopulationMultiplier(int activePopulation) {
        return 1.0 + pricingConfig.maxMultiplierBonus()
                * (1 - Math.exp(-activePopulation / pricingConfig.saturationConstant()));
    }

    /**
     * Runs {@code work} inside an explicit transaction on {@code
     * connection}. Same shape as every other service's private helper of the
     * same name (see {@code RealmServiceImpl}'s Javadoc for why it is not
     * shared lower in the persistence layer yet).
     */
    private static <T> T inTransaction(Connection connection, SqlWork<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
