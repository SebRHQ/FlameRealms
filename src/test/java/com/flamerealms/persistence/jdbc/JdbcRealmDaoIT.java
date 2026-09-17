package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers-backed integration test for {@link JdbcRealmDao} and
 * {@link JdbcRealmMemberDao}: spins up a real MariaDB container, runs the
 * actual {@code V1__realm_core.sql} Flyway migration against it, and
 * exercises the JDBC DAOs directly to assert two database-level invariants
 * that no fake or in-memory test can meaningfully assert:
 *
 * <ul>
 *   <li>{@code realms.name} is UNIQUE — a second realm with a duplicate
 *       name is rejected by the database itself.</li>
 *   <li>{@code realm_members.player_uuid} carries {@code uq_player_one_realm}
 *       — a second membership row for a player who already has one is
 *       rejected by the database itself, even for a different realm.</li>
 * </ul>
 *
 * <p><b>Tagged {@code "integration"} and excluded from the default
 * {@code test} task</b> (see {@code build.gradle.kts}'s
 * {@code tasks.test { useJUnitPlatform { excludeTags("integration") } } }),
 * so {@code ./gradlew test} passes with no Docker daemon available.
 *
 * <p><b>To run this test where Docker (or another Testcontainers-compatible
 * runtime) IS available:</b>
 * <pre>{@code ./gradlew integrationTest}</pre>
 */
@Testcontainers
@Tag("integration")
class JdbcRealmDaoIT {

    @Container
    private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("flamerealms_it")
            .withUsername("flamerealms")
            .withPassword("flamerealms");

    private static HikariDataSource dataSource;

    private final JdbcRealmDao realmDao = new JdbcRealmDao();
    private final JdbcRealmRankDao realmRankDao = new JdbcRealmRankDao();
    private final JdbcRealmMemberDao realmMemberDao = new JdbcRealmMemberDao();

    @BeforeAll
    static void migrate() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(MARIADB.getJdbcUrl());
        hikariConfig.setUsername(MARIADB.getUsername());
        hikariConfig.setPassword(MARIADB.getPassword());
        hikariConfig.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(hikariConfig);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void duplicateRealmNameIsRejectedByTheDatabase() throws SQLException {
        String name = uniqueName("dup-name");

        try (Connection connection = dataSource.getConnection()) {
            realmDao.insert(connection, new Realm(0L, name, "First", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));

            assertThatThrownBy(() -> realmDao.insert(
                    connection, new Realm(0L, name, "Second", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null)))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void secondMembershipForAnAlreadyMemberedPlayerIsRejectedByTheDatabase() throws SQLException {
        UUID player = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            Realm realmA = realmDao.insert(connection, new Realm(
                    0L, uniqueName("realm-a"), "Realm A", player, 1, Instant.now(), null, null, null, null, null));
            RealmRank leaderRank = realmRankDao.insert(
                    connection, new RealmRank(0L, realmA.id(), "Leader", 100, RealmPermission.ALL, false));
            realmMemberDao.insert(connection, new RealmMember(realmA.id(), player, leaderRank.id(), Instant.now()));

            // A second, unrelated realm — `player` tries to join it too,
            // despite already belonging to realmA.
            Realm realmB = realmDao.insert(connection, new Realm(
                    0L, uniqueName("realm-b"), "Realm B", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
            RealmRank defaultRank = realmRankDao.insert(
                    connection, new RealmRank(0L, realmB.id(), "Member", 0, 0L, true));

            assertThatThrownBy(() -> realmMemberDao.insert(
                    connection, new RealmMember(realmB.id(), player, defaultRank.id(), Instant.now())))
                    .isInstanceOf(SQLException.class);
        }
    }

    /** A name that fits {@code VARCHAR(32)} and is unique across test runs within this container's lifetime. */
    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
