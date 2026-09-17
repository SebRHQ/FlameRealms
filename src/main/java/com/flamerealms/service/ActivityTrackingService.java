package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.domain.Realm;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.dao.RealmMemberActivityDao;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Presence-only tracking of {@code realm_member_activity.online_minutes},
 * following the write-behind / eventually-consistent pattern this project's
 * async model reserves for cheap, non-financial bookkeeping (contrast with
 * {@code LedgerDao}, where every balance mutation is synchronous-with-its-
 * commit and never batched or delayed).
 *
 * <p><b>The shape of it.</b> A once-a-simulated-minute main-thread tick
 * (every {@value #MINUTE_TICK_PERIOD_TICKS} ticks) increments an in-memory
 * {@code Map<UUID, Integer>} for every online player who currently belongs
 * to a realm — cheap, O(online player count), no database touch. A much
 * less frequent main-thread flush (every {@value #FLUSH_PERIOD_MINUTES} real
 * minutes) snapshots that map, dispatches the accumulated minutes to {@link
 * RealmMemberActivityDao#recordPresence} through {@link
 * AsyncDatabaseExecutor}, and only then zeroes the flushed counters — never
 * before the snapshot is taken, so nothing is double-counted and nothing
 * ticked between the snapshot and the reset is lost.
 *
 * <p><b>Bounded loss window, by design.</b> The periodic flush is
 * fire-and-forget: {@link #flushInternal()} resets the in-memory counters as
 * soon as it has copied them, not once the database write has actually
 * landed, because the whole point of write-behind is that the main thread
 * never waits on it. If that async write then fails (a transient DB error,
 * not a crash), the flushed minutes for that one cycle are lost — never
 * retried — exactly the "accepted bounded-loss window" this pattern trades
 * for never blocking the main thread on non-financial state. A JVM crash
 * loses at most one flush interval's worth of minutes for the same reason.
 * The one place that is NOT true is graceful shutdown: {@link #flushNow()}
 * exists specifically so a normal {@code onDisable()} does not throw away
 * the partial interval sitting in memory when the server stops cleanly.
 *
 * <p><b>Realm membership at flush time, not tick time.</b> The in-memory map
 * only tracks accumulated minutes per player UUID, not which realm they were
 * in while ticking. {@link #flushInternal()} resolves each player's realm
 * via {@link RealmCache#getByPlayer} at flush time; a player who left their
 * realm between the last tick and the flush simply has that cycle's minutes
 * dropped (there is no realm left to attribute them to). This is a
 * deliberate simplification consistent with "presence is cheap, approximate
 * bookkeeping" — it is not exact online-time-per-realm accounting.
 *
 * <p><b>Date rollover.</b> {@link #flushInternal()} always writes against
 * the date the currently-accumulated minutes actually belong to (tracked in
 * {@link #accumulationDate}), not {@code LocalDate.now()} at flush time —
 * if no flush has happened since midnight, the accumulator is still
 * yesterday's minutes and gets flushed against yesterday's {@code
 * activity_date}, then {@link #accumulationDate} rolls forward to today for
 * whatever accumulates next. Ticks that land in the few minutes between
 * midnight and the next flush are still credited to the pre-rollover date
 * bucket (at most one flush interval's worth of skew) — an accepted
 * approximation, the same class of trade-off as the bounded-loss window
 * above, not worth a mid-cycle split for presence data.
 *
 * <p><b>Single-threaded by construction, despite the {@link
 * ConcurrentHashMap}.</b> {@link #tickOnlineMinutes()} and {@link
 * #flushInternal()} are both registered via {@code
 * Bukkit.getScheduler().runTaskTimer(...)} (see {@link #start}), and {@link
 * #flushNow()} is documented to be called only from {@code
 * FlameRealmsPlugin#onDisable()} — Bukkit guarantees all three run on the
 * main thread, one at a time, never interleaved with each other. The map is
 * still a {@link ConcurrentHashMap} as cheap insurance, matching {@code
 * RealmCache}'s own default, but no extra locking is needed here and none is
 * used.
 *
 * <p><b>Intended wiring (not done here).</b> {@code FlameRealmsPlugin} is
 * expected to construct one instance in {@code onEnable()} and call {@link
 * #start()} right after, then in {@code onDisable()} call {@link #stop()}
 * followed by {@link #flushNow()} — in that order, and strictly BEFORE
 * {@code AsyncDatabaseExecutor.shutdown(...)} closes the pool this class's
 * flush depends on. {@link #flushNow()} blocking the main thread is only
 * acceptable at that exact call site: the plugin is already shutting down
 * and nothing else needs the main thread free. Calling it anywhere else
 * would violate {@code AsyncDatabaseExecutor}'s "never block the main
 * thread on a database call" rule.
 */
public final class ActivityTrackingService {

    /** One simulated minute, in ticks (20 ticks/sec x 60 sec). */
    static final int MINUTE_TICK_PERIOD_TICKS = 1200;

    /** How often the in-memory accumulator is flushed to the database. */
    static final int FLUSH_PERIOD_MINUTES = 5;
    private static final long FLUSH_PERIOD_TICKS = 20L * 60 * FLUSH_PERIOD_MINUTES;

    private final JavaPlugin plugin;
    private final RealmCache realmCache;
    private final RealmMemberActivityDao realmMemberActivityDao;
    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final Logger logger;

    // player UUID -> minutes accumulated for `accumulationDate` so far this
    // interval. Only ever holds entries for players currently (or very
    // recently) online and in a realm; a flush removes exactly the keys it
    // flushed, so an entry's absence is equivalent to "0 minutes accumulated".
    private final Map<UUID, Integer> onlineMinutesToday = new ConcurrentHashMap<>();

    // The activity_date the counters in onlineMinutesToday belong to. Only
    // read/written from the main thread (see class Javadoc).
    private LocalDate accumulationDate = LocalDate.now();

    private BukkitTask minuteTask;
    private BukkitTask flushTask;

    public ActivityTrackingService(
            JavaPlugin plugin,
            RealmCache realmCache,
            RealmMemberActivityDao realmMemberActivityDao,
            AsyncDatabaseExecutor asyncDatabaseExecutor
    ) {
        this.plugin = plugin;
        this.realmCache = realmCache;
        this.realmMemberActivityDao = realmMemberActivityDao;
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.logger = plugin.getLogger();
    }

    /**
     * Registers the minute-tick and periodic-flush repeating tasks. Call
     * once, from {@code onEnable()} — see class Javadoc for the intended
     * full wiring.
     */
    public void start() {
        minuteTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tickOnlineMinutes, MINUTE_TICK_PERIOD_TICKS, MINUTE_TICK_PERIOD_TICKS);
        flushTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::flushInternal, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
    }

    /**
     * Cancels both repeating tasks. Call from {@code onDisable()}, before
     * {@link #flushNow()} — see class Javadoc for the intended full wiring.
     */
    public void stop() {
        if (minuteTask != null) {
            minuteTask.cancel();
            minuteTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
    }

    /**
     * Main-thread only, no database touch: increments every currently
     * online, currently realm-member player's accumulated-minutes counter by
     * one. Package-visible (rather than private) purely so a test can invoke
     * it directly without going through Bukkit's scheduler. Resolves which
     * online players are realm members, then delegates the actual counter
     * bump to {@link #incrementMinutes}, which has no Bukkit dependency at
     * all — that split is what lets a test exercise the accumulate/flush
     * logic below without a running Bukkit server.
     */
    void tickOnlineMinutes() {
        Set<UUID> onlineRealmMembers = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerUuid = player.getUniqueId();
            if (realmCache.isPlayerInRealm(playerUuid)) {
                onlineRealmMembers.add(playerUuid);
            }
        }
        incrementMinutes(onlineRealmMembers);
    }

    /**
     * Adds one minute to each given player's accumulated counter. No Bukkit
     * dependency — see {@link #tickOnlineMinutes}'s Javadoc for why this is
     * split out.
     */
    void incrementMinutes(Collection<UUID> playerUuids) {
        for (UUID playerUuid : playerUuids) {
            onlineMinutesToday.merge(playerUuid, 1, Integer::sum);
        }
    }

    /**
     * Blocks the calling thread until the current accumulator snapshot has
     * been written to the database. <b>Only ever call this from {@code
     * FlameRealmsPlugin#onDisable()}.</b> Blocking on a database future is
     * forbidden everywhere else in this project (see {@code
     * AsyncDatabaseExecutor}'s class Javadoc) — it is acceptable here, and
     * only here, because the plugin is already shutting down and the main
     * thread has nothing else left to do.
     */
    public void flushNow() {
        flushInternal().join();
    }

    /**
     * Snapshots and resets the in-memory accumulator, then dispatches its
     * contents to the database as one batched transaction (see class
     * Javadoc's rationale in the {@code RealmMemberActivityDao} note below).
     * Non-blocking; failures are logged, never thrown back at the scheduler.
     *
     * <p>Batched into a single {@code AsyncDatabaseExecutor.submit(...)} /
     * one transaction per flush, rather than one submit per player: the
     * flushed set is normally small (only online, realm-member players), so
     * the win is fewer connection checkouts and one commit instead of many,
     * with no meaningful downside — {@code recordPresence} is a cheap
     * single-row upsert and nothing here needs per-player isolation (an
     * individual row failing this batch is not expected to be an ordinary
     * outcome the way, say, one realm's upkeep failing is in {@code
     * UpkeepService}).
     */
    CompletableFuture<Void> flushInternal() {
        Map<UUID, Integer> snapshot = snapshotAndReset();
        LocalDate dateForThisFlush = advanceAccumulationDate();

        if (snapshot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                return inTransaction(connection, conn -> {
                    for (Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
                        UUID playerUuid = entry.getKey();
                        int minutes = entry.getValue();

                        Optional<Realm> realm = realmCache.getByPlayer(playerUuid);
                        if (realm.isEmpty()) {
                            // Left their realm between the last tick and this
                            // flush — see class Javadoc, these minutes are
                            // dropped, not attributed to a stale realm id.
                            continue;
                        }

                        realmMemberActivityDao.recordPresence(
                                conn, realm.get().id(), playerUuid, dateForThisFlush, minutes);
                    }
                    return null;
                });
            } catch (SQLException e) {
                throw new RuntimeException("Failed to flush realm member activity", e);
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.warning("Failed to flush realm member activity ("
                        + snapshot.size() + " players, date " + dateForThisFlush + "): " + error.getMessage());
            }
        });
    }

    /**
     * Copies out every currently-accumulated entry and removes exactly those
     * keys from the live map (equivalent to zeroing them — see the field's
     * own comment for why "absent" and "zero" mean the same thing here).
     */
    private Map<UUID, Integer> snapshotAndReset() {
        Map<UUID, Integer> snapshot = Map.copyOf(onlineMinutesToday);
        onlineMinutesToday.keySet().removeAll(snapshot.keySet());
        return snapshot;
    }

    /**
     * Returns the date the just-snapshotted counters belong to, then rolls
     * {@link #accumulationDate} forward to today for whatever accumulates
     * next. Always rolls forward, even on an empty snapshot, so a flush that
     * happens to land with nothing accumulated still clears a stale date.
     */
    private LocalDate advanceAccumulationDate() {
        LocalDate dateForThisFlush = accumulationDate;
        accumulationDate = LocalDate.now();
        return dateForThisFlush;
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
