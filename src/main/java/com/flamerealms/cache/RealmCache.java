package com.flamerealms.cache;

import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.Realm;
import com.flamerealms.util.UuidCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, write-through cache of active realms — authoritative for every
 * read {@code RealmService} exposes.
 *
 * <p>Backed by five {@link ConcurrentHashMap}s: realm id -&gt; {@link Realm},
 * lowercased realm name -&gt; realm id, player UUID -&gt; realm id, realm id
 * -&gt; that realm's claimed {@link ChunkCoordinate}s, and the reverse index
 * {@link ChunkCoordinate} -&gt; owning realm id. Name lookups are
 * case-insensitive because realm names are, per the rest of the plugin's
 * convention of treating them as case-insensitive unique handles.
 *
 * <p><b>Write-through, never optimistic.</b> Every mutator on this class
 * (everything below the read methods) is meant to be called by
 * {@code RealmServiceImpl} only from inside the success continuation of an
 * {@code AsyncDatabaseExecutor.submit(...)} call — i.e. strictly after the
 * corresponding database transaction has already committed. Nothing here
 * ever gets written speculatively before a commit, and nothing here talks to
 * the database on the calling (read) path — {@link #loadAll(Connection)} and
 * {@link #loadClaims(Connection)} are the two exceptions, and they exist
 * solely to warm the cache once at startup.
 */
public final class RealmCache {

    private final Map<Long, Realm> realmsById = new ConcurrentHashMap<>();
    private final Map<String, Long> realmIdsByLowercaseName = new ConcurrentHashMap<>();
    private final Map<UUID, Long> realmIdsByPlayer = new ConcurrentHashMap<>();

    // realm id -> that realm's claimed chunks, plus the reverse index for
    // O(1) "is this chunk claimed, and by whom" lookups (also used by later
    // milestones' PvP territory gate, not just claim management). Populated
    // by loadClaims(...) at startup and kept current write-through by
    // addClaim/removeClaim/removeAllClaimsOf, same contract as every other
    // mutator on this class.
    private final Map<Long, Set<ChunkCoordinate>> claimsByRealm = new ConcurrentHashMap<>();
    private final Map<ChunkCoordinate, Long> realmIdByClaim = new ConcurrentHashMap<>();

    // Only active realms matter to the cache — a disbanded realm has no
    // pending reads to serve. RealmDao/RealmMemberDao expose no bulk "find
    // all" query (by design, their contract is single-row lookups only), so
    // this warm-up reads directly via JDBC rather than growing that
    // contract for a one-time startup path.
    private static final String LOAD_REALMS =
            "SELECT id, name, display_name, leader_uuid, level, created_at, disbanded_at "
                    + "FROM realms WHERE disbanded_at IS NULL";

    private static final String LOAD_MEMBERS =
            "SELECT realm_id, player_uuid FROM realm_members";

    // Mirrors LOAD_REALMS/LOAD_MEMBERS above: RealmClaimDao.findAll(...) is
    // the sanctioned DAO-level equivalent of this query (for callers that
    // already hold a RealmClaimDao), but loadClaims(...) does its own raw
    // JDBC here rather than taking RealmClaimDao as a new constructor
    // dependency of this class, for the same reason loadAll() above reads
    // realms/members directly instead of going through RealmDao/RealmMemberDao.
    private static final String LOAD_CLAIMS =
            "SELECT realm_id, world, chunk_x, chunk_z FROM realm_claims";

    /**
     * Warm-populates this cache from the database, replacing whatever it
     * currently holds. Intended to be called exactly once, during
     * {@code onEnable()}, dispatched through
     * {@code AsyncDatabaseExecutor.submit(...)} — the plugin must not be
     * considered ready to serve realm reads/writes until the future that
     * call returns has completed successfully.
     */
    public void loadAll(Connection connection) throws SQLException {
        realmsById.clear();
        realmIdsByLowercaseName.clear();
        realmIdsByPlayer.clear();

        try (PreparedStatement statement = connection.prepareStatement(LOAD_REALMS);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                Realm realm = mapRealmRow(resultSet);
                realmsById.put(realm.id(), realm);
                realmIdsByLowercaseName.put(lowercase(realm.name()), realm.id());
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(LOAD_MEMBERS);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long realmId = resultSet.getLong("realm_id");
                // A membership row belonging to a realm that didn't load
                // above (i.e. it was disbanded) is skipped. The FK cascade
                // on realm_members.realm_id should already make this
                // impossible in practice, but the cache has no reason to
                // represent it even if it somehow occurred.
                if (!realmsById.containsKey(realmId)) {
                    continue;
                }
                UUID playerUuid = UuidCodec.fromBytes(resultSet.getBytes("player_uuid"));
                realmIdsByPlayer.put(playerUuid, realmId);
            }
        }
    }

    /**
     * Warm-populates this cache's claim tracking from {@code realm_claims},
     * replacing whatever it currently holds. A sibling to {@link #loadAll},
     * not merged into it: {@link #loadAll} stays scoped to realms/members
     * exactly as before, and this is a separate method the plugin bootstrap
     * calls on its own, since {@code RealmClaimDao} is a new dependency
     * {@link #loadAll} has no reason to take on. Intended to be called
     * exactly once, during {@code onEnable()}, dispatched through {@code
     * AsyncDatabaseExecutor.submit(...)} alongside {@link #loadAll} — the
     * plugin must not be considered ready to serve claim reads/writes until
     * the future that call returns has completed successfully.
     */
    public void loadClaims(Connection connection) throws SQLException {
        claimsByRealm.clear();
        realmIdByClaim.clear();

        try (PreparedStatement statement = connection.prepareStatement(LOAD_CLAIMS);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long realmId = resultSet.getLong("realm_id");
                ChunkCoordinate coordinate = new ChunkCoordinate(
                        resultSet.getString("world"), resultSet.getInt("chunk_x"), resultSet.getInt("chunk_z"));
                claimsByRealm.computeIfAbsent(realmId, id -> ConcurrentHashMap.newKeySet()).add(coordinate);
                realmIdByClaim.put(coordinate, realmId);
            }
        }
    }

    // -- Reads -----------------------------------------------------------

    /** Looks up a cached realm by its id. */
    public Optional<Realm> get(long realmId) {
        return Optional.ofNullable(realmsById.get(realmId));
    }

    /** Looks up a cached realm by name, case-insensitively. */
    public Optional<Realm> getByName(String name) {
        Long realmId = realmIdsByLowercaseName.get(lowercase(name));
        return realmId == null ? Optional.empty() : get(realmId);
    }

    /** Looks up the realm a player currently belongs to. */
    public Optional<Realm> getByPlayer(UUID playerUuid) {
        Long realmId = realmIdsByPlayer.get(playerUuid);
        return realmId == null ? Optional.empty() : get(realmId);
    }

    /** Whether {@code name} is already taken by a cached (active) realm. */
    public boolean isNameTaken(String name) {
        return realmIdsByLowercaseName.containsKey(lowercase(name));
    }

    /** Whether {@code playerUuid} already belongs to a cached realm. */
    public boolean isPlayerInRealm(UUID playerUuid) {
        return realmIdsByPlayer.containsKey(playerUuid);
    }

    /** Every chunk a realm currently has claimed. Empty (never {@code null}) if it has none. */
    public Set<ChunkCoordinate> claimsOf(long realmId) {
        Set<ChunkCoordinate> claims = claimsByRealm.get(realmId);
        return claims == null ? Collections.emptySet() : Collections.unmodifiableSet(claims);
    }

    /** Looks up which realm (if any) owns a claimed chunk. */
    public Optional<Long> ownerOf(ChunkCoordinate coordinate) {
        return Optional.ofNullable(realmIdByClaim.get(coordinate));
    }

    /**
     * Every currently-cached (i.e. active) realm. An unmodifiable snapshot
     * view backed by {@code realmsById}'s values — added for {@code
     * UpkeepService}, which needs to iterate every active realm once per
     * daily cycle and has no other synchronous hook for that; no earlier
     * caller needed a bulk read, only the single-realm lookups above.
     */
    public Collection<Realm> values() {
        return Collections.unmodifiableCollection(realmsById.values());
    }

    // -- Write-through mutators (post-commit only) ------------------------

    /**
     * Inserts or replaces a realm's entry, keyed by both id and name.
     * Called after a transaction that creates (or otherwise persists) a
     * realm row has committed.
     */
    public void put(Realm realm) {
        realmsById.put(realm.id(), realm);
        realmIdsByLowercaseName.put(lowercase(realm.name()), realm.id());
    }

    /** Records that {@code playerUuid} belongs to {@code realmId}. */
    public void putMember(UUID playerUuid, long realmId) {
        realmIdsByPlayer.put(playerUuid, realmId);
    }

    /** Forgets {@code playerUuid}'s membership, whatever realm it pointed at. */
    public void removeMember(UUID playerUuid) {
        realmIdsByPlayer.remove(playerUuid);
    }

    /**
     * Removes a realm and every cached member mapping that pointed at it.
     * Called after a disband transaction has committed. A no-op if the
     * realm was not (or no longer) cached.
     */
    public void removeRealm(long realmId) {
        Realm removed = realmsById.remove(realmId);
        if (removed != null) {
            realmIdsByLowercaseName.remove(lowercase(removed.name()));
        }
        realmIdsByPlayer.values().removeIf(cachedRealmId -> cachedRealmId == realmId);
    }

    /**
     * Records a newly-claimed chunk as belonging to {@code realmId}. Called
     * after a transaction that inserts a {@code realm_claims} row has
     * committed.
     */
    public void addClaim(long realmId, ChunkCoordinate coordinate) {
        claimsByRealm.computeIfAbsent(realmId, id -> ConcurrentHashMap.newKeySet()).add(coordinate);
        realmIdByClaim.put(coordinate, realmId);
    }

    /**
     * Forgets a single claimed chunk, whichever realm it belonged to. Called
     * after a transaction that deletes that {@code realm_claims} row has
     * committed.
     */
    public void removeClaim(ChunkCoordinate coordinate) {
        Long realmId = realmIdByClaim.remove(coordinate);
        if (realmId != null) {
            Set<ChunkCoordinate> claims = claimsByRealm.get(realmId);
            if (claims != null) {
                claims.remove(coordinate);
            }
        }
    }

    /**
     * Removes every claim belonging to {@code realmId}. Called when a realm
     * is disbanded: {@code realm_claims.realm_id} cascades via {@code
     * ON DELETE CASCADE} (see {@code V3__claims.sql}), so once the disband
     * transaction has committed every one of the realm's claim rows is
     * already gone from the database and the cache needs the matching
     * cleanup — the same reasoning {@link #removeRealm} already follows for
     * member mappings. A no-op if the realm had no cached claims.
     */
    public void removeAllClaimsOf(long realmId) {
        Set<ChunkCoordinate> removed = claimsByRealm.remove(realmId);
        if (removed != null) {
            for (ChunkCoordinate coordinate : removed) {
                realmIdByClaim.remove(coordinate);
            }
        }
    }

    private static String lowercase(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static Realm mapRealmRow(ResultSet resultSet) throws SQLException {
        Timestamp disbandedAt = resultSet.getTimestamp("disbanded_at");
        return new Realm(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("display_name"),
                UuidCodec.fromBytes(resultSet.getBytes("leader_uuid")),
                resultSet.getInt("level"),
                resultSet.getTimestamp("created_at").toInstant(),
                disbandedAt == null ? null : disbandedAt.toInstant()
        );
    }
}
