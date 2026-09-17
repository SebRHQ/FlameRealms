package com.flamerealms.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AsyncDatabaseExecutor}'s {@code unavailable()} mode
 * — the degraded-mode factory used by {@code FlameRealmsPlugin} when
 * {@code DatabaseManager} could not be constructed at startup (see {@code
 * DatabaseUnavailableException}'s Javadoc).
 *
 * <p>No real {@link javax.sql.DataSource} or thread pool is involved here at
 * all: {@code unavailable()} builds an instance whose {@code dataSource}/
 * {@code executor} fields are both {@code null}, so the whole point of these
 * tests is confirming {@link AsyncDatabaseExecutor#submit(java.util.function.Function)}
 * and {@link AsyncDatabaseExecutor#shutdown(Duration)} check the {@code
 * available} flag before ever touching either field.
 */
final class AsyncDatabaseExecutorTest {

    @Test
    void submitOnAnUnavailableExecutorFailsImmediatelyWithDatabaseUnavailableException() {
        AsyncDatabaseExecutor executor = AsyncDatabaseExecutor.unavailable();

        AtomicBoolean workRan = new AtomicBoolean(false);
        CompletableFuture<String> future = executor.submit((Connection connection) -> {
            // Must never run: submit() should fail synchronously, before
            // ever dereferencing the (null) dataSource to obtain a
            // connection to hand to this work function.
            workRan.set(true);
            return "unreachable";
        });

        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThat(workRan).isFalse();

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DatabaseUnavailableException.class);

        future.exceptionally(ex -> {
            assertThat(ex).isInstanceOf(DatabaseUnavailableException.class);
            return null;
        }).join();
    }

    @Test
    void shutdownOnAnUnavailableExecutorIsASafeNoOp() {
        AsyncDatabaseExecutor executor = AsyncDatabaseExecutor.unavailable();

        assertThatCode(() -> executor.shutdown(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();

        // Safe to call more than once too - still just a no-op, nothing to
        // shut down twice.
        assertThatCode(() -> executor.shutdown(Duration.ZERO))
                .doesNotThrowAnyException();
    }
}
