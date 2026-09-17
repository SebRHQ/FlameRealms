package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.domain.Realm;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.service.fake.FakeRealmMemberActivityDao;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.fakeConnection;
import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.inline;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ActivityTrackingService}.
 *
 * <p>Drives the accumulate/flush logic through {@code incrementMinutes(...)}
 * and {@code flushInternal()}/{@code flushNow()} directly, never through
 * {@code tickOnlineMinutes()} or {@code start()}/{@code stop()} — those are
 * the only members that touch {@code Bukkit.getOnlinePlayers()} /
 * {@code Bukkit.getScheduler()}, which need a running Bukkit server this
 * project's test suite has no double for. See {@code
 * ActivityTrackingService#tickOnlineMinutes}'s Javadoc for why the
 * Bukkit-free {@code incrementMinutes} exists as a separate seam.
 */
final class ActivityTrackingServiceTest {

    private Connection connection;
    private RealmCache realmCache;
    private FakeRealmMemberActivityDao realmMemberActivityDao;
    private ActivityTrackingService service;

    private long realmId;
    private UUID playerA;
    private UUID playerB;

    @BeforeEach
    void setUp() {
        connection = fakeConnection();
        realmCache = new RealmCache();
        realmMemberActivityDao = new FakeRealmMemberActivityDao();

        Realm realm = new Realm(1L, "alpha", "Alpha", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null);
        realmCache.put(realm);
        realmId = realm.id();

        playerA = UUID.randomUUID();
        playerB = UUID.randomUUID();
        realmCache.putMember(playerA, realmId);
        realmCache.putMember(playerB, realmId);

        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("ActivityTrackingServiceTest"));

        AsyncDatabaseExecutor asyncDatabaseExecutor = inline(connection);
        service = new ActivityTrackingService(plugin, realmCache, realmMemberActivityDao, asyncDatabaseExecutor);
    }

    @Test
    void flushWritesAccumulatedMinutesAndResetsTheCounter() {
        service.incrementMinutes(List.of(playerA, playerB));
        service.incrementMinutes(List.of(playerA)); // playerA online for two ticks, playerB for one

        LocalDate today = LocalDate.now();
        service.flushNow();

        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, today)).isEqualTo(2);
        assertThat(realmMemberActivityDao.minutesFor(realmId, playerB, today)).isEqualTo(1);

        // A second flush with nothing newly accumulated must not add anything more.
        service.flushNow();
        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, today)).isEqualTo(2);
        assertThat(realmMemberActivityDao.minutesFor(realmId, playerB, today)).isEqualTo(1);
    }

    @Test
    void secondFlushOnlyAddsTheDeltaSinceTheFirst() {
        service.incrementMinutes(List.of(playerA));
        service.flushNow();

        service.incrementMinutes(List.of(playerA));
        service.incrementMinutes(List.of(playerA));
        service.flushNow();

        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, LocalDate.now())).isEqualTo(3);
    }

    @Test
    void aFlushWithNothingAccumulatedIsANoOp() {
        service.flushNow();
        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, LocalDate.now())).isZero();
    }

    @Test
    void minutesForAPlayerNoLongerInARealmAtFlushTimeAreDropped() {
        service.incrementMinutes(List.of(playerA));
        realmCache.removeMember(playerA); // left the realm before the flush ran

        service.flushNow();

        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, LocalDate.now())).isZero();
    }

    @Test
    void flushAgainstAPastDateUsesThatDateNotToday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        service.incrementMinutes(List.of(playerA));
        // Simulate the accumulator still holding yesterday's minutes when a
        // flush finally happens (e.g. the plugin was idle across midnight).
        setAccumulationDate(yesterday);

        service.flushNow();

        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, yesterday)).isEqualTo(1);
        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, LocalDate.now())).isZero();

        // Whatever accumulates next is attributed to today, not yesterday again.
        service.incrementMinutes(List.of(playerA));
        service.flushNow();
        assertThat(realmMemberActivityDao.minutesFor(realmId, playerA, LocalDate.now())).isEqualTo(1);
    }

    /** Test-only reflective hook: there is no public setter, by design — see the field's own Javadoc. */
    private void setAccumulationDate(LocalDate date) {
        try {
            var field = ActivityTrackingService.class.getDeclaredField("accumulationDate");
            field.setAccessible(true);
            field.set(service, date);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
