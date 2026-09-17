package com.flamerealms.service.support;

import com.flamerealms.persistence.AsyncDatabaseExecutor;
import org.mockito.Answers;

import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.mockito.Mockito.mock;

/**
 * Builds a test double for {@code AsyncDatabaseExecutor} that runs
 * submitted work synchronously (on the calling thread, immediately) instead
 * of dispatching to a real thread pool backed by a real JDBC connection.
 *
 * <p>{@code AsyncDatabaseExecutor} is a concrete {@code final} class tied to
 * a real {@code DatabaseManager}/HikariCP pool, so it cannot be faked with a
 * hand-written subclass. It is not a DAO interface either — the task's
 * "hand-written fakes, not Mockito mocks" instruction is about the
 * {@code RealmDao}/{@code RealmRankDao}/{@code RealmMemberDao} test doubles.
 * For this one unavoidable concrete collaborator, Mockito's default
 * (inline) mock maker mocks the final class directly, and a custom
 * {@link org.mockito.stubbing.Answer} makes {@code submit(...)} behave like
 * the real thing minus the threading and the real connection pool: it calls
 * the given {@code Function<Connection, T>} immediately against a mocked
 * {@link Connection}, wraps a normal return in a completed future exactly
 * like the real {@code submit} does, and — matching
 * {@code AsyncDatabaseExecutor.submit}'s own {@code catch (RuntimeException e)}
 * — completes the future exceptionally if the work throws one, so
 * {@code RealmServiceException} subtypes (all unchecked) surface through
 * {@code CompletableFuture} exactly as they would in production.
 */
public final class InlineAsyncDatabaseExecutors {

    private InlineAsyncDatabaseExecutors() {
    }

    /** A mocked {@link Connection} suitable for passing through the fake DAOs, which never dereference it. */
    public static Connection fakeConnection() {
        return mock(Connection.class);
    }

    /**
     * An {@code AsyncDatabaseExecutor} whose {@code submit(...)} runs the
     * given work immediately against {@code connection}, synchronously,
     * on the calling thread.
     */
    @SuppressWarnings("unchecked")
    public static AsyncDatabaseExecutor inline(Connection connection) {
        return mock(AsyncDatabaseExecutor.class, invocation -> {
            if (!"submit".equals(invocation.getMethod().getName())) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }

            Function<Connection, Object> work = (Function<Connection, Object>) invocation.getArgument(0);
            try {
                return CompletableFuture.completedFuture(work.apply(connection));
            } catch (RuntimeException e) {
                CompletableFuture<Object> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        });
    }
}
