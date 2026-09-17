package com.flamerealms.persistence.jdbc;

import com.flamerealms.domain.LedgerEntity;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmClaim;
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
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
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
 * Testcontainers-backed integration test proving the concrete concurrency
 * guarantee {@code ClaimServiceImpl}'s "one transaction for withdraw +
 * claim-insert" design exists to provide: spins up a real MariaDB container,
 * runs the actual {@code V1__realm_core.sql}/{@code V2__economy.sql}/{@code
 * V3__claims.sql} Flyway migrations against it, then races many threads to
 * {@code purchaseClaim} the exact same chunk for the same realm at the same
 * time.
 *
 * <p><b>What this proves that no fake/in-memory test can.</b> {@code
 * realm_claims}'s {@code uq_chunk} unique constraint
 * ({@code UNIQUE KEY uq_chunk (world, chunk_x, chunk_z)}, see {@code
 * V3__claims.sql}) is only meaningful against a real database - {@code
 * FakeRealmClaimDao} (used by {@code ClaimServiceImplTest}) never simulates
 * that constraint at all. Here, every racing thread's claim attempt runs the
 * database work {@code ClaimServiceImpl#purchaseClaim} performs (permission
 * check, treasury debit, claim insert, all on one explicit transaction)
 * against the same live connection pool: exactly one thread's {@code
 * uq_chunk} insert should win, every loser's insert should fail with a
 * {@link SQLIntegrityConstraintViolationException} that rolls its whole
 * transaction back - including the treasury debit it had already tentatively
 * applied - so the realm ends up debited exactly once, not once per losing
 * attempt.
 *
 * <p><b>Why this drives the DAOs directly instead of {@code
 * ClaimServiceImpl}.</b> {@code ClaimServiceImpl} only adds {@code
 * AsyncDatabaseExecutor} dispatch on top of the exact database work
 * reproduced here directly (the private {@link #attemptPurchase} helper
 * mirrors {@code ClaimServiceImpl#purchaseClaim}'s transaction body
 * verbatim). {@code AsyncDatabaseExecutor} itself requires a {@code
 * DatabaseManager}, whose {@code HikariConfig.setDriverClassName(...)}
 * hardcodes the shaded driver class name that only exists inside the built
 * shadow jar - the same constraint {@link JdbcRealmDaoIT}/{@link
 * EconomyLedgerIT} already work around by building their own {@link
 * HikariDataSource} by hand. This test follows that same established
 * precedent.
 *
 * <p>Tagged {@code "integration"} and excluded from the default {@code test}
 * task, exactly like {@link JdbcRealmDaoIT}/{@link EconomyLedgerIT} - see
 * {@link JdbcRealmDaoIT}'s Javadoc for how to run it.
 */
@Testcontainers
@Tag("integration")
class ClaimServiceConcurrencyIT {

    @Container
    private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("flamerealms_it")
            .withUsername("flamerealms")
            .withPassword("flamerealms");

    private static HikariDataSource dataSource;

    private final JdbcRealmDao realmDao = new JdbcRealmDao();
    private final JdbcRealmRankDao realmRankDao = new JdbcRealmRankDao();
    private final JdbcRealmMemberDao realmMemberDao = new JdbcRealmMemberDao();
    private final JdbcRealmClaimDao realmClaimDao = new JdbcRealmClaimDao();
    private final JdbcPlayerWalletDao walletDao = new JdbcPlayerWalletDao();
    private final JdbcLedgerDao ledgerDao = new JdbcLedgerDao(walletDao, realmDao);

    private static final long CLAIM_PRICE_CENTS = 500L;
    private static final long STARTING_TREASURY_CENTS = 100_000L; // plenty for every racing thread to attempt

    @BeforeAll
    static void migrate() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(MARIADB.getJdbcUrl());
        hikariConfig.setUsername(MARIADB.getUsername());
        hikariConfig.setPassword(MARIADB.getPassword());
        // High enough that every racing thread below gets its own
        // connection instead of queueing behind the pool.
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

    @Test
    void exactlyOneOfManyConcurrentPurchasesOfTheIdenticalChunkSucceedsAndTheRealmIsDebitedOnlyOnce()
            throws Exception {
        UUID actor = UUID.randomUUID();
        long realmId;
        try (Connection setup = dataSource.getConnection()) {
            Realm realm = realmDao.insert(setup, new Realm(
                    0L, uniqueName("claim-race"), "Claim Race", UUID.randomUUID(), 1, Instant.now(), null));
            realmId = realm.id();

            RealmRank rank = realmRankDao.insert(setup, new RealmRank(
                    0L, realmId, "Owner", 100, RealmPermission.CLAIM.bit(), false));
            realmMemberDao.insert(setup, new RealmMember(realmId, actor, rank.id(), Instant.now()));

            realmDao.tryAdjustBalance(setup, realmId, STARTING_TREASURY_CENTS);
        }

        String world = "world";
        int chunkX = 7;
        int chunkZ = -3;
        int threadCount = 20;

        List<Boolean> results = runConcurrently(threadCount,
                () -> attemptPurchase(realmId, actor, world, chunkX, chunkZ));

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(1L);

        try (Connection connection = dataSource.getConnection()) {
            List<RealmClaim> claims = realmClaimDao.findByRealm(connection, realmId);
            assertThat(claims).hasSize(1);
            assertThat(claims.get(0).world()).isEqualTo(world);
            assertThat(claims.get(0).chunkX()).isEqualTo(chunkX);
            assertThat(claims.get(0).chunkZ()).isEqualTo(chunkZ);

            // Debited exactly once - every losing thread's tentative debit
            // was rolled back along with its failed insert, not left behind.
            assertThat(realmDao.findBalance(connection, realmId))
                    .isEqualTo(STARTING_TREASURY_CENTS - CLAIM_PRICE_CENTS);
        }
    }

    /**
     * Reproduces {@code ClaimServiceImpl#purchaseClaim}'s transaction body
     * verbatim, against a fresh connection from the pool: permission check,
     * treasury debit, then the claim insert. A fixed {@link
     * #CLAIM_PRICE_CENTS} stands in for {@code PricingConfig}'s tiered
     * lookup - this test is only exercising the database-level race, not the
     * pricing curve (already covered by {@code ClaimServiceImplTest}).
     *
     * @return {@code true} if this attempt won the race and its claim (and
     *         debit) committed; {@code false} if it lost {@code uq_chunk}
     *         and its whole transaction (including the tentative debit) was
     *         rolled back
     */
    private boolean attemptPurchase(long realmId, UUID actor, String world, int chunkX, int chunkZ)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                RealmMember membership = realmMemberDao.findByPlayerUuid(connection, actor).orElseThrow();
                RealmRank rank = realmRankDao.findById(connection, membership.rankId()).orElseThrow();
                if (!RealmPermission.has(rank.permissions(), RealmPermission.CLAIM)) {
                    throw new IllegalStateException("actor lacks CLAIM permission");
                }

                boolean debited = ledgerDao.recordAndApplyToRealm(
                        connection, realmId, -CLAIM_PRICE_CENTS, TransactionCategory.SINK,
                        "CLAIM_PURCHASE", LedgerEntity.SERVER, null);
                if (!debited) {
                    throw new IllegalStateException("treasury could not cover the claim price");
                }

                realmClaimDao.insert(connection,
                        new RealmClaim(0L, realmId, world, chunkX, chunkZ, Instant.now(), CLAIM_PRICE_CENTS));

                connection.commit();
                return true;
            } catch (SQLIntegrityConstraintViolationException lostRace) {
                // uq_chunk - another thread's insert committed first.
                connection.rollback();
                return false;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                return false;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    /** Runs {@code work} on {@code threadCount} threads, released together, and collects every result. */
    private static List<Boolean> runConcurrently(int threadCount, ThrowingCallable work) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> executor.<Boolean>submit(() -> {
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

    @FunctionalInterface
    private interface ThrowingCallable extends Callable<Boolean> {
    }

    /** A name that fits {@code VARCHAR(32)} and is unique across test runs within this container's lifetime. */
    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
