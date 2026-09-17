package com.flamerealms.persistence.dao;

import com.flamerealms.domain.Realm;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code realms} table.
 *
 * <p>Every method takes a live {@link Connection} as its first parameter and
 * is meant to be called only from inside a single
 * {@code AsyncDatabaseExecutor.submit(...)} unit of work — never on the
 * caller's own thread and never by opening a connection itself. Composing
 * several DAO calls (e.g. this DAO plus {@code RealmRankDao} plus
 * {@code RealmMemberDao}) inside one {@code submit} call is how the service
 * layer keeps a multi-step operation such as "create realm + seed ranks +
 * insert leader membership" inside a single transaction.
 */
public interface RealmDao {

    /**
     * Inserts {@code realm} and returns it with the generated {@code id}
     * populated. {@code realm.id()} is ignored on the way in.
     */
    Realm insert(Connection connection, Realm realm) throws SQLException;

    /** Looks up a realm by its surrogate id. */
    Optional<Realm> findById(Connection connection, long realmId) throws SQLException;

    /** Looks up a realm by its unique, machine-facing name. */
    Optional<Realm> findByName(Connection connection, String name) throws SQLException;

    /**
     * Looks up the realm a player currently belongs to, joining through
     * {@code realm_members}. Empty if the player is not a member of any
     * realm.
     */
    Optional<Realm> findByPlayerUuid(Connection connection, UUID playerUuid) throws SQLException;

    /** Marks a realm as disbanded at the given instant. */
    void markDisbanded(Connection connection, long realmId, Instant disbandedAt) throws SQLException;

    /** Deletes a realm outright (cascades to its ranks and memberships). */
    void delete(Connection connection, long realmId) throws SQLException;

    /** Reads a realm's current treasury balance ({@code realms.balance_cents}). */
    long findBalance(Connection connection, long realmId) throws SQLException;

    /**
     * Conditionally applies {@code deltaCents} to the realm's treasury
     * balance in a single {@code UPDATE ... WHERE balance_cents + ? >= 0},
     * never by reading the balance and writing it back. Returns {@code true}
     * only if a row was actually affected, i.e. the realm exists and the
     * resulting balance would not go negative; returns {@code false}
     * otherwise, in which case the balance was left completely untouched.
     */
    boolean tryAdjustBalance(Connection connection, long realmId, long deltaCents) throws SQLException;

    /** Reads a realm's current accrued upkeep debt ({@code realms.upkeep_debt_cents}). */
    long findUpkeepDebt(Connection connection, long realmId) throws SQLException;

    /**
     * Zeroes a realm's accrued upkeep debt. Called after a daily upkeep
     * charge that covered the existing debt plus the new cycle's cost has
     * been successfully applied to the treasury (see {@code UpkeepService}).
     */
    void resetUpkeepDebt(Connection connection, long realmId) throws SQLException;

    /**
     * Adds {@code deltaCents} onto a realm's accrued upkeep debt in a single
     * {@code UPDATE ... SET upkeep_debt_cents = upkeep_debt_cents + ?}, never
     * by reading the debt and writing it back. Called when a daily upkeep
     * charge could not be applied to the treasury (insufficient funds) —
     * {@code deltaCents} is that cycle's computed cost only, not the
     * already-accounted-for existing debt (see {@code UpkeepService}).
     */
    void incrementUpkeepDebt(Connection connection, long realmId, long deltaCents) throws SQLException;
}
