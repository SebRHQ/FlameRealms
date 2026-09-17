package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.PlayerWallet;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.domain.TransactionCategory;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed integration tests for the economy DAOs
 * ({@link JdbcPlayerWalletDao}, {@link JdbcRealmDao}, {@link JdbcLedgerDao})
 * introduced for M2: spins up a real MariaDB container, runs the actual
 * {@code V1__realm_core.sql}/{@code V2__economy.sql} Flyway migrations
 * against it, and asserts two invariants that no fake or in-memory unit test
 * can meaningfully assert:
 *
 * <ul>
 *   <li>{@code tryAdjustBalance}'s single conditional {@code UPDATE ...
 *       WHERE balance + delta >= 0} actually serializes concurrent
 *       withdrawals against the database's own row locking — a balance
 *       never goes negative even when many threads race to overdraw it,
 *       and exactly the number of withdrawals the balance can cover
 *       succeed, for both {@link JdbcPlayerWalletDao} and
 *       {@link JdbcRealmDao}.</li>
 *   <li>The ledger invariant: after a mix of successful and rejected
 *       player/realm transfers, {@code transactions} holds exactly one row
 *       per successful (never per attempted) balance mutation, and a
 *       transfer that fails partway through — realm-side insufficient
 *       funds discovered only after the player side was tentatively
 *       credited — is rolled back in full by a real transaction, leaving
 *       no orphaned balance change or ledger row behind.</li>
 * </ul>
 *
 * <p><b>Why this drives the DAOs directly instead of {@code
 * EconomyServiceImpl}/{@code TreasuryServiceImpl}.</b> Both service classes
 * only add {@code AsyncDatabaseExecutor} dispatch on top of the exact same
 * DAO calls, transaction demarcation and lock ordering exercised here
 * directly (private {@link #treasuryDeposit}/{@link #treasuryWithdraw}
 * helpers below reproduce {@code TreasuryServiceImpl}'s method bodies
 * verbatim). {@code AsyncDatabaseExecutor} itself requires a {@code
 * DatabaseManager}, whose {@code HikariConfig.setDriverClassName(...)}
 * hardcodes the shaded driver class name ({@code
 * com.flamerealms.libs.mariadb.Driver}) that only exists inside the built
 * shadow jar, not on the plain test classpath — the same constraint that
 * already leads {@link JdbcRealmDaoIT} to build its own {@link
 * HikariDataSource} by hand rather than going through {@code
 * DatabaseManager}. This test follows that same established precedent.
 *
 * <p>Tagged {@code "integration"} and excluded from the default {@code test}
 * task, exactly like {@link JdbcRealmDaoIT} — see that class's Javadoc for
 * how to run it.
 */
@Testcontainers
@Tag("integration")
class EconomyLedgerIT {

    @Container
    private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("flamerealms_it")
            .withUsername("flamerealms")
            .withPassword("flamerealms");

    private static HikariDataSource dataSource;

    private final JdbcRealmDao realmDao = new JdbcRealmDao();
    private final JdbcRealmRankDao realmRankDao = new JdbcRealmRankDao();
    private final JdbcRealmMemberDao realmMemberDao = new JdbcRealmMemberDao();
    private final JdbcPlayerWalletDao walletDao = new JdbcPlayerWalletDao();
    private final JdbcLedgerDao ledgerDao = new JdbcLedgerDao(walletDao, realmDao);

    @BeforeAll
    static void migrate() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(MARIADB.getJdbcUrl());
        hikariConfig.setUsername(MARIADB.getUsername());
        hikariConfig.setPassword(MARIADB.getPassword());
        // High enough that every thread in the concurrent-load tests below
        // gets its own connection instead of queueing behind the pool.
        hikariConfig.setMaximumPoolSize(32);
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

    // -- Concurrent tryAdjustBalance ---------------------------------------

    @Test
    void concurrentPlayerWithdrawalsNeverDriveTheWalletNegative() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection setup = dataSource.getConnection()) {
            walletDao.ensureExists(setup, player);
            walletDao.tryAdjustBalance(setup, player, 1_000L);
        }

        int threadCount = 20;
        long withdrawalCents = 100L; // exactly 10 of these can be covered by 1000

        List<Boolean> results = runConcurrently(threadCount, () -> {
            try (Connection connection = dataSource.getConnection()) {
                return walletDao.tryAdjustBalance(connection, player, -withdrawalCents);
            }
        });

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(10L);

        try (Connection connection = dataSource.getConnection()) {
            PlayerWallet finalWallet = walletDao.findByPlayer(connection, player).orElseThrow();
            assertThat(finalWallet.balanceCents()).isEqualTo(0L);
            assertThat(finalWallet.balanceCents()).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    void concurrentRealmWithdrawalsNeverDriveTheTreasuryNegative() throws Exception {
        Realm realm = insertRealm();
        try (Connection setup = dataSource.getConnection()) {
            realmDao.tryAdjustBalance(setup, realm.id(), 500L);
        }

        int threadCount = 20;
        long withdrawalCents = 100L; // exactly 5 of these can be covered by 500

        List<Boolean> results = runConcurrently(threadCount, () -> {
            try (Connection connection = dataSource.getConnection()) {
                return realmDao.tryAdjustBalance(connection, realm.id(), -withdrawalCents);
            }
        });

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(5L);

        try (Connection connection = dataSource.getConnection()) {
            long finalBalance = realmDao.findBalance(connection, realm.id());
            assertThat(finalBalance).isEqualTo(0L);
            assertThat(finalBalance).isGreaterThanOrEqualTo(0L);
        }
    }

    /** Runs {@code work} on {@code threadCount} threads, released together, and collects every result. */
    private static List<Boolean> runConcurrently(int threadCount, Callable<Boolean> work) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> executor.submit(() -> {
                        startLine.await();
                        return work.call();
                    }))
                    .collect(Collectors.toList());

            startLine.countDown();

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    // -- Ledger invariant end-to-end ----------------------------------------

    @Test
    void ledgerRowCountAndMoneyConservationHoldAfterAMixOfTransfers() throws Exception {
        Realm realm = insertRealm();
        UUID actor;
        try (Connection connection = dataSource.getConnection()) {
            RealmRank rank = realmRankDao.insert(connection, new RealmRank(
                    0L, realm.id(), "Owner", 100, RealmPermission.WITHDRAW.bit(), false));
            actor = UUID.randomUUID();
            realmMemberDao.insert(connection, new RealmMember(realm.id(), actor, rank.id(), Instant.now()));

            // Seeded directly (not through the ledger), so `transactions`
            // starts genuinely empty for this realm/player pair.
            walletDao.ensureExists(connection, actor);
            walletDao.tryAdjustBalance(connection, actor, 10_000L);
        }

        int successfulTransfers = 0;

        try (Connection connection = dataSource.getConnection()) {
            assertThat(treasuryDeposit(connection, realm.id(), actor, 500L)).isTrue();
            successfulTransfers++;
            assertThat(treasuryDeposit(connection, realm.id(), actor, 500L)).isTrue();
            successfulTransfers++;
            assertThat(treasuryWithdraw(connection, realm.id(), actor, 300L)).isTrue();
            successfulTransfers++;

            // Player can no longer cover this — rejected before ever
            // touching the realm side.
            assertThat(treasuryDeposit(connection, realm.id(), actor, 50_000L)).isFalse();

            // Realm can't cover this either. Per the fixed lock ordering
            // (see TreasuryServiceImpl's class Javadoc) the player is
            // tentatively credited FIRST, then the realm debit is attempted
            // and fails — this whole transaction rolls back for real here
            // (a real Connection, unlike the non-transactional fakes used
            // by TreasuryServiceImplTest), so the tentative credit and its
            // ledger row must both vanish.
            assertThat(treasuryWithdraw(connection, realm.id(), actor, 50_000L)).isFalse();
        }

        long expectedPlayerBalance = 10_000L - 500L - 500L + 300L; // 9_300
        long expectedRealmBalance = 500L + 500L - 300L; // 700

        try (Connection connection = dataSource.getConnection()) {
            PlayerWallet wallet = walletDao.findByPlayer(connection, actor).orElseThrow();
            assertThat(wallet.balanceCents()).isEqualTo(expectedPlayerBalance);
            assertThat(realmDao.findBalance(connection, realm.id())).isEqualTo(expectedRealmBalance);

            // Money conservation: TRANSFER-only operations must not create
            // or destroy money system-wide for this player+realm pair.
            long totalBefore = 10_000L; // player seed + realm's starting 0
            long totalAfter = wallet.balanceCents() + realmDao.findBalance(connection, realm.id());
            assertThat(totalAfter).isEqualTo(totalBefore);

            // Row count: exactly one ledger row per successful (never per
            // attempted) balance mutation, and each successful transfer
            // mutates two balances (player + realm).
            long transferRowCount = countTransferRows(connection, actor);
            assertThat(transferRowCount).isEqualTo(2L * successfulTransfers);

            // Netting both sides of every TRANSFER row to zero: the signed
            // effect on the player side and the signed effect on the realm
            // side of the same set of rows must cancel out exactly, since a
            // TRANSFER only ever moves money between a player and a realm,
            // never creating or losing any of it.
            long playerNet = signedNet(connection, actor.toString(), LedgerEntity.PLAYER);
            long realmNet = signedNet(connection, String.valueOf(realm.id()), LedgerEntity.REALM);
            assertThat(playerNet + realmNet).isEqualTo(0L);
            assertThat(playerNet).isEqualTo(2L * (wallet.balanceCents() - 10_000L));
            assertThat(realmNet).isEqualTo(2L * realmDao.findBalance(connection, realm.id()));
        }
    }

    private Realm insertRealm() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return realmDao.insert(connection, new Realm(
                    0L, uniqueName("ledger-it"), "Ledger IT", UUID.randomUUID(), 1, Instant.now(), null));
        }
    }

    /**
     * Reproduces {@code TreasuryServiceImpl#deposit}'s body exactly: debit
     * the player, then credit the realm, both inside one explicit
     * transaction. See this class's Javadoc for why the DAOs are driven
     * directly instead of going through the service class itself.
     */
    private boolean treasuryDeposit(Connection connection, long realmId, UUID actor, long amountCents)
            throws SQLException {
        String realmIdString = String.valueOf(realmId);
        try {
            return inTransaction(connection, conn -> {
                if (!ledgerDao.recordAndApplyToPlayer(
                        conn, actor, -amountCents, TransactionCategory.TRANSFER,
                        "REALM_DEPOSIT", LedgerEntity.REALM, realmIdString)) {
                    throw InsufficientFundsSignal.INSTANCE;
                }
                if (!ledgerDao.recordAndApplyToRealm(
                        conn, realmId, amountCents, TransactionCategory.TRANSFER,
                        "REALM_DEPOSIT", LedgerEntity.PLAYER, actor.toString())) {
                    throw new IllegalStateException("Realm vanished mid-transaction");
                }
                return true;
            });
        } catch (InsufficientFundsSignal signal) {
            return false;
        }
    }

    /** Reproduces {@code TreasuryServiceImpl#withdraw}'s body exactly (player credited first, realm debited second). */
    private boolean treasuryWithdraw(Connection connection, long realmId, UUID actor, long amountCents)
            throws SQLException {
        String realmIdString = String.valueOf(realmId);
        try {
            return inTransaction(connection, conn -> {
                if (!ledgerDao.recordAndApplyToPlayer(
                        conn, actor, amountCents, TransactionCategory.TRANSFER,
                        "REALM_WITHDRAW", LedgerEntity.REALM, realmIdString)) {
                    throw new IllegalStateException("Crediting the player cannot fail");
                }
                if (!ledgerDao.recordAndApplyToRealm(
                        conn, realmId, -amountCents, TransactionCategory.TRANSFER,
                        "REALM_WITHDRAW", LedgerEntity.PLAYER, actor.toString())) {
                    throw InsufficientFundsSignal.INSTANCE;
                }
                return true;
            });
        } catch (InsufficientFundsSignal signal) {
            return false;
        }
    }

    /**
     * Counts TRANSFER rows with {@code actor} on either side. Every TRANSFER
     * row this test produces always has this specific player as one side
     * (this test's realm/player pair is unique to it), so scoping by the
     * player alone is already exact — no need to also filter by realm id.
     */
    private static long countTransferRows(Connection connection, UUID actor) throws SQLException {
        String playerScopedSql = "SELECT COUNT(*) FROM transactions WHERE category = 'TRANSFER' "
                + "AND ((source_type = 'PLAYER' AND source_id = ?) OR (target_type = 'PLAYER' AND target_id = ?))";
        try (PreparedStatement statement = connection.prepareStatement(playerScopedSql)) {
            statement.setString(1, actor.toString());
            statement.setString(2, actor.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    /**
     * Signed net effect of every TRANSFER row on {@code entityType}'s side
     * identified by {@code entityId}: {@code +amount_cents} when this entity
     * is the row's target, {@code -amount_cents} when it is the source.
     */
    private static long signedNet(Connection connection, String entityId, LedgerEntity entityType)
            throws SQLException {
        String sql = "SELECT COALESCE(SUM(CASE "
                + "WHEN target_type = ? AND target_id = ? THEN amount_cents "
                + "WHEN source_type = ? AND source_id = ? THEN -amount_cents "
                + "ELSE 0 END), 0) FROM transactions WHERE category = 'TRANSFER'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType.name());
            statement.setString(2, entityId);
            statement.setString(3, entityType.name());
            statement.setString(4, entityId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    /** A name that fits {@code VARCHAR(32)} and is unique across test runs within this container's lifetime. */
    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

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

    /** Internal control-flow signal only, mirroring {@code TreasuryServiceImpl}'s own private one. */
    private static final class InsufficientFundsSignal extends RuntimeException {
        private static final InsufficientFundsSignal INSTANCE = new InsufficientFundsSignal();

        private InsufficientFundsSignal() {
            super(null, null, false, false);
        }
    }
}
