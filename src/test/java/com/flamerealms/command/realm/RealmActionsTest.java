package com.flamerealms.command.realm;

import com.flamerealms.FlameRealmsPlugin;
import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.Messages;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.Money;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.protection.ClaimProtectionService;
import com.flamerealms.service.ClaimService;
import com.flamerealms.service.ClaimServiceImpl;
import com.flamerealms.service.EconomyService;
import com.flamerealms.service.EconomyServiceImpl;
import com.flamerealms.service.RealmServiceImpl;
import com.flamerealms.service.TreasuryService;
import com.flamerealms.service.TreasuryServiceImpl;
import com.flamerealms.service.fake.FakeLedgerDao;
import com.flamerealms.service.fake.FakePlayerWalletDao;
import com.flamerealms.service.fake.FakeRealmClaimDao;
import com.flamerealms.service.fake.FakeRealmDao;
import com.flamerealms.service.fake.FakeRealmInviteDao;
import com.flamerealms.service.fake.FakeRealmMemberDao;
import com.flamerealms.service.fake.FakeRealmRankDao;
import com.flamerealms.visualization.ClaimVisualizationService;
import com.flamerealms.visualization.TerritoryMapService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.fakeConnection;
import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.inline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Parity tests for {@link RealmActions} — confirms it is a thin, correct
 * pass-through onto {@link RealmServiceImpl}/{@link TreasuryServiceImpl}/
 * {@link ClaimServiceImpl}, not a re-test of the business logic those
 * classes' own unit tests ({@code RealmServiceImplTest}, {@code
 * TreasuryServiceImplTest}, {@code ClaimServiceImplTest}) already cover in
 * depth.
 *
 * <p>Same hand-written-fake-DAO approach as those classes: real {@code
 * *ServiceImpl} instances wired to the existing {@code Fake*Dao} test
 * doubles plus {@code InlineAsyncDatabaseExecutors.inline(...)}, so a call
 * into {@link RealmActions} drives the exact same DAO-level effects those
 * tests assert on directly.
 *
 * <p>{@link RealmActions} additionally touches Bukkit APIs no other service
 * layer test in this project needs to: every async action hops back to the
 * main thread via {@code Bukkit.getScheduler().runTask(...)} before sending
 * any message, and {@code Player} itself is a Bukkit API type. Since {@code
 * InlineAsyncDatabaseExecutors.inline(...)} completes every dispatched
 * future synchronously, that {@code runTask(...)} call happens inline,
 * within the test thread, the moment an action method is invoked — so
 * {@code Bukkit.getScheduler()} is stubbed via {@link Mockito#mockStatic}
 * to run the given {@link Runnable} immediately rather than reaching for a
 * real (nonexistent, in this test process) Bukkit server. {@link Player},
 * {@link Messages}, {@link ClaimVisualizationService}/{@link
 * TerritoryMapService} and {@link FlameRealmsPlugin} are all plain Mockito
 * mocks — none of them are DAOs, so this does not conflict with the
 * hand-written-fake convention that applies to the {@code RealmDao}/{@code
 * RealmMemberDao}/{@code RealmRankDao} family specifically (see {@code
 * InlineAsyncDatabaseExecutors}'s own Javadoc for the same distinction).
 */
final class RealmActionsTest {

    private Connection connection;
    private RealmCache realmCache;
    private FakeRealmDao realmDao;
    private FakeRealmMemberDao realmMemberDao;
    private FakeRealmRankDao realmRankDao;
    private FakePlayerWalletDao walletDao;
    private FakeLedgerDao ledgerDao;
    private FakeRealmClaimDao realmClaimDao;
    private PricingConfig pricingConfig;

    private Messages messages;
    private Player player;
    private RealmActions actions;
    private FlameRealmsPlugin plugin;
    private RealmServiceImpl realmService;

    private MockedStatic<Bukkit> bukkitStatic;

    private static final long CLAIM_PRICE_CENTS = 500L;
    private static final long CREATION_FEE_CENTS = 50000L;

    @BeforeEach
    void setUp() {
        connection = fakeConnection();
        realmCache = new RealmCache();
        realmDao = new FakeRealmDao();
        realmMemberDao = new FakeRealmMemberDao();
        realmRankDao = new FakeRealmRankDao();
        walletDao = new FakePlayerWalletDao();
        ledgerDao = new FakeLedgerDao(walletDao, realmDao);
        realmClaimDao = new FakeRealmClaimDao();

        pricingConfig = new PricingConfig(
                List.of(new PricingConfig.PriceTier(Integer.MAX_VALUE, CLAIM_PRICE_CENTS)),
                List.of(new PricingConfig.MultiplierTier(Integer.MAX_VALUE, 1.0)),
                100L, 60, 7, 10.0, 0.5, CREATION_FEE_CENTS, 3
        );

        AsyncDatabaseExecutor asyncDatabaseExecutor = inline(connection);

        FakeRealmInviteDao realmInviteDao = new FakeRealmInviteDao();
        realmService = new RealmServiceImpl(
                asyncDatabaseExecutor, realmCache, realmDao, realmRankDao, realmMemberDao,
                realmClaimDao, realmInviteDao, ledgerDao, pricingConfig);
        EconomyServiceImpl economyService = new EconomyServiceImpl(asyncDatabaseExecutor, walletDao, ledgerDao);
        TreasuryServiceImpl treasuryService = new TreasuryServiceImpl(
                asyncDatabaseExecutor, realmCache, realmDao, realmMemberDao, realmRankDao, ledgerDao);
        ClaimServiceImpl claimService = new ClaimServiceImpl(
                asyncDatabaseExecutor, realmCache, realmClaimDao, realmMemberDao, realmRankDao, ledgerDao,
                pricingConfig);

        // Not DAOs - plain Mockito mocks, same as this project's existing
        // JavaPlugin mocks in ActivityTrackingServiceTest/UpkeepServiceTest.
        plugin = mock(FlameRealmsPlugin.class);
        ClaimVisualizationService claimVisualizationService = mock(ClaimVisualizationService.class);
        TerritoryMapService territoryMapService = mock(TerritoryMapService.class);
        ClaimProtectionService claimProtectionService = mock(ClaimProtectionService.class);
        // Default-answers every Messages.get(...) call with a real (empty)
        // Component rather than Mockito's usual null, since RealmActions
        // feeds the result straight into player.sendMessage(...) - message
        // *content* is out of scope for this parity test (Messages has no
        // test of its own either), only that the right key was requested.
        messages = mock(Messages.class, invocation -> Component.empty());

        actions = new RealmActions(
                plugin, realmService, economyService, treasuryService, claimService,
                claimVisualizationService, territoryMapService, claimProtectionService, asyncDatabaseExecutor,
                realmMemberDao, realmRankDao, realmCache, pricingConfig, messages);

        player = mock(Player.class);

        // Every action funnels its result back through
        // Bukkit.getScheduler().runTask(plugin, runnable) before sending
        // any message. inline(...) completes every dispatched future
        // synchronously, so that call happens right here, inline, in this
        // test thread - never on a real Bukkit main thread. Run the
        // runnable immediately rather than reaching for a real scheduler.
        bukkitStatic = Mockito.mockStatic(Bukkit.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return mock(BukkitTask.class);
        });
        bukkitStatic.when(Bukkit::getScheduler).thenReturn(scheduler);
    }

    @AfterEach
    void tearDown() {
        bukkitStatic.close();
    }

    /**
     * Stubs {@code targetPlayer.getLocation().subtract(0, 1, 0).getBlock()}
     * to resolve to a Beacon block — {@link RealmActions#createRealm} rejects
     * the call outright, before ever touching {@code RealmService}, unless
     * this exact Bukkit read finds one directly underfoot.
     */
    private void stubStandingOnBeacon(Player targetPlayer, String world, int x, int y, int z) {
        World mockWorld = mock(World.class);
        when(mockWorld.getName()).thenReturn(world);

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.BEACON);
        when(block.getWorld()).thenReturn(mockWorld);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);

        Location location = mock(Location.class);
        when(location.subtract(0, 1, 0)).thenReturn(location);
        when(location.getBlock()).thenReturn(block);

        when(targetPlayer.getLocation()).thenReturn(location);
    }

    /** Gives {@code playerId} enough personal balance to cover the realm creation fee (and then some). */
    private void fund(UUID playerId, long cents) {
        walletDao.ensureExists(connection, playerId);
        walletDao.tryAdjustBalance(connection, playerId, cents);
    }

    private UUID memberWithPermissions(long realmId, long permissions) throws Exception {
        RealmRank rank = realmRankDao.insert(connection,
                new RealmRank(0L, realmId, "Rank-" + UUID.randomUUID(), 0, permissions, false));
        UUID playerId = UUID.randomUUID();
        realmMemberDao.insert(connection, new RealmMember(realmId, playerId, rank.id(), Instant.now()));
        return playerId;
    }

    /**
     * Gives {@code realm}'s existing {@code leaderUuid()} an actual
     * membership row with a rank holding {@link RealmPermission#ALL} — every
     * one of {@code kick}/{@code setRank}/{@code transfer}'s underlying
     * {@code RealmServiceImpl} methods requires the acting player to already
     * be a real member with the relevant permission bit(s), which {@code
     * realmDao.insert(...)} alone (unlike {@code RealmServiceImpl#createRealm})
     * does not set up by itself for these hand-constructed test realms.
     */
    private void seatLeaderAsAMember(Realm realm) throws Exception {
        // Named literally "Leader" - RealmServiceImpl#transferLeadership
        // looks the rank up by that exact name (see its own Javadoc/impl),
        // not merely by permissions.
        RealmRank leaderRank = realmRankDao.insert(connection,
                new RealmRank(0L, realm.id(), "Leader", 100, RealmPermission.ALL, false));
        realmMemberDao.insert(connection,
                new RealmMember(realm.id(), realm.leaderUuid(), leaderRank.id(), Instant.now()));
        realmCache.putMember(realm.leaderUuid(), realm.id());
    }

    // -- createRealm ---------------------------------------------------------

    @Test
    void createRealmProducesTheSameEffectRealmServiceImplTestVerifiesDirectly() {
        UUID leaderId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(leaderId);
        stubStandingOnBeacon(player, "world", 10, 64, 20);
        fund(leaderId, CREATION_FEE_CENTS * 2);

        actions.createRealm(player, "ember").join();

        // Exactly what RealmServiceImplTest#createRealmSeedsRanksAndLeaderMembership
        // asserts on RealmServiceImpl directly: RealmActions must not
        // reimplement or alter any of it, only dispatch and relay the result.
        assertThat(realmCache.getByName("ember")).isPresent();
        Realm realm = realmCache.getByName("ember").orElseThrow();
        assertThat(realm.leaderUuid()).isEqualTo(leaderId);
        assertThat(realmCache.getByPlayer(leaderId)).contains(realm);
        assertThat(realmMemberDao.findByPlayerUuid(connection, leaderId)).isPresent();

        verify(messages).get(eq("realm-created"), any(TagResolver.class));
        // Two messages now, not one: "realm-created" plus a second,
        // separately-toggleable "realm-created-nexus" line with the fee/
        // Nexus-location detail (see RealmActions#createRealm's own Javadoc
        // for why that's a second message rather than folded into the first).
        verify(player, Mockito.times(2)).sendMessage(any(Component.class));
    }

    @Test
    void createRealmRejectsADuplicateNameAndSendsTheMappedErrorInsteadOfThrowing() {
        UUID firstLeader = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(firstLeader);
        stubStandingOnBeacon(player, "world", 10, 64, 20);
        fund(firstLeader, CREATION_FEE_CENTS * 2);
        actions.createRealm(player, "ember").join();

        Mockito.clearInvocations(messages); // isolate what the second (rejected) call sends

        UUID secondLeader = UUID.randomUUID();
        Player secondPlayer = mock(Player.class);
        when(secondPlayer.getUniqueId()).thenReturn(secondLeader);
        stubStandingOnBeacon(secondPlayer, "world", 30, 64, 40);

        // Must not throw out of createRealm(...) itself - describeError(...)
        // maps the failure to a message instead, exactly like
        // RealmCommand used to before this class existed.
        actions.createRealm(secondPlayer, "ember").join();

        assertThat(realmCache.isPlayerInRealm(secondLeader)).isFalse();
        verify(secondPlayer).sendMessage(any(Component.class));
        verify(messages, Mockito.never()).get(eq("realm-created"), any(TagResolver.class));
        // The name alone isn't enough - confirm describeError(...) mapped this
        // specific failure to the specific key, not just "some other message".
        verify(messages).get(eq("error-realm-name-taken"));
    }

    // -- deposit ---------------------------------------------------------

    @Test
    void depositProducesTheSameLedgerAndBalanceEffectTreasuryServiceImplTestVerifiesDirectly() throws Exception {
        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmCache.put(realm);

        UUID actorId = memberWithPermissions(realm.id(), 0L); // deposit needs no permission
        realmCache.putMember(actorId, realm.id());
        walletDao.ensureExists(connection, actorId);
        walletDao.tryAdjustBalance(connection, actorId, 1_000L);
        when(player.getUniqueId()).thenReturn(actorId);

        actions.deposit(player, Money.ofCents(400L)).join();

        // Same assertions TreasuryServiceImplTest#depositDebitsPlayerAndCreditsRealmAtomically
        // makes on TreasuryServiceImpl directly.
        assertThat(walletDao.balanceOf(actorId)).isEqualTo(600L);
        assertThat(realmDao.findBalance(connection, realm.id())).isEqualTo(400L);
        assertThat(ledgerDao.entries()).hasSize(2);

        verify(messages).get(eq("deposit-success"), any(TagResolver.class));
    }

    @Test
    void depositInsufficientFundsIsRelayedAsTheFailureMessageNotAnException() throws Exception {
        Realm realm = realmDao.insert(connection,
                new Realm(0L, "beta", "Beta", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmCache.put(realm);

        UUID actorId = memberWithPermissions(realm.id(), 0L);
        realmCache.putMember(actorId, realm.id());
        walletDao.ensureExists(connection, actorId);
        walletDao.tryAdjustBalance(connection, actorId, 100L); // not enough for a 400 deposit
        when(player.getUniqueId()).thenReturn(actorId);

        actions.deposit(player, Money.ofCents(400L)).join();

        assertThat(walletDao.balanceOf(actorId)).isEqualTo(100L); // untouched
        assertThat(ledgerDao.entries()).isEmpty();

        verify(messages).get(eq("deposit-insufficient-funds"), any(TagResolver.class));
        verify(messages, Mockito.never()).get(eq("deposit-success"), any(TagResolver.class));
    }

    // -- previewClaim + confirmClaim ---------------------------------------------------------

    @Test
    void previewClaimThenConfirmClaimProducesTheSamePurchaseEffectClaimServiceImplTestVerifiesDirectly()
            throws Exception {
        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmCache.put(realm);
        realmDao.tryAdjustBalance(connection, realm.id(), 10_000L);

        UUID actorId = memberWithPermissions(realm.id(), RealmPermission.CLAIM.bit());
        realmCache.putMember(actorId, realm.id());
        when(player.getUniqueId()).thenReturn(actorId);

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(3);
        when(chunk.getZ()).thenReturn(4);
        Location location = mock(Location.class);
        when(location.getChunk()).thenReturn(chunk);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(location);

        // previewClaim(...) makes no database call and purchases nothing by
        // itself - only stashes the pending claim and shows the particle
        // preview (mocked away here, since ClaimVisualizationService's own
        // behavior is out of scope for this parity test).
        actions.previewClaim(player);

        assertThat(realmClaimDao.findByRealm(connection, realm.id())).isEmpty();
        assertThat(realmDao.findBalance(connection, realm.id())).isEqualTo(10_000L);
        verify(messages).get(eq("claim-preview"),
                any(TagResolver.class), any(TagResolver.class), any(TagResolver.class),
                any(TagResolver.class), any(TagResolver.class));

        actions.confirmClaim(player).join();

        // Same assertions ClaimServiceImplTest#purchaseClaimSucceedsForARealmsFirstEverClaimWithNoContiguityRequirement
        // makes on ClaimServiceImpl directly.
        assertThat(realmClaimDao.findByRealm(connection, realm.id())).hasSize(1);
        assertThat(realmDao.findBalance(connection, realm.id())).isEqualTo(10_000L - CLAIM_PRICE_CENTS);
        assertThat(realmCache.claimsOf(realm.id())).hasSize(1);
        assertThat(ledgerDao.entries()).hasSize(1);
        assertThat(ledgerDao.entries().get(0).reason()).isEqualTo("CLAIM_PURCHASE");

        verify(messages).get(eq("claim-confirm-success"), any(TagResolver.class));
    }

    @Test
    void confirmClaimWithNoPrecedingPreviewSendsTheNoPendingMessageAndPurchasesNothing() {
        UUID actorId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(actorId);

        actions.confirmClaim(player).join();

        assertThat(realmClaimDao.findAll(connection)).isEmpty();
        verify(messages).get(eq("claim-confirm-none"));
    }

    // -- kick / setRank / transfer ---------------------------------------------------------

    @Test
    void kickProducesTheSameMembershipRemovalRealmServiceImplTestVerifiesDirectly() throws Exception {
        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmCache.put(realm);
        seatLeaderAsAMember(realm);

        UUID targetId = memberWithPermissions(realm.id(), 0L);
        realmCache.putMember(targetId, realm.id());
        when(player.getUniqueId()).thenReturn(realm.leaderUuid());

        actions.kick(player, targetId, "Target").join();

        // Same assertion RealmServiceImplTest#kickRemovesAMemberWhenActorHasKickPermission
        // makes on RealmServiceImpl directly.
        assertThat(realmMemberDao.findByPlayerUuid(connection, targetId)).isEmpty();
        assertThat(realmCache.isPlayerInRealm(targetId)).isFalse();
        verify(messages).get(eq("kick-success"), any(TagResolver.class));
    }

    @Test
    void setRankProducesTheSameRankChangeRealmServiceImplTestVerifiesDirectly() throws Exception {
        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmCache.put(realm);
        seatLeaderAsAMember(realm);

        RealmRank officerRank = realmRankDao.insert(connection,
                new RealmRank(0L, realm.id(), "Officer", 50, RealmPermission.INVITE.bit(), false));
        UUID targetId = memberWithPermissions(realm.id(), 0L);
        realmCache.putMember(targetId, realm.id());
        when(player.getUniqueId()).thenReturn(realm.leaderUuid());

        actions.setRank(player, targetId, "Target", "Officer").join();

        // Same assertion RealmServiceImplTest#setRankSucceedsWhenActorHasManageRanks
        // makes on RealmServiceImpl directly.
        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, targetId).orElseThrow();
        assertThat(membership.rankId()).isEqualTo(officerRank.id());
        verify(messages).get(eq("setrank-success"), any(TagResolver.class), any(TagResolver.class));
    }

    @Test
    void transferProducesTheSameLeadershipSwapRealmServiceImplTestVerifiesDirectly() throws Exception {
        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmCache.put(realm);
        seatLeaderAsAMember(realm);

        UUID targetId = memberWithPermissions(realm.id(), 0L);
        realmCache.putMember(targetId, realm.id());
        when(player.getUniqueId()).thenReturn(realm.leaderUuid());

        actions.transfer(player, targetId, "Target").join();

        // Same assertion RealmServiceImplTest#transferLeadershipSwapsTheLeaderAndTheTwoMembersRanks
        // makes on RealmServiceImpl directly.
        assertThat(realmCache.getByName("ember").orElseThrow().leaderUuid()).isEqualTo(targetId);
        verify(messages).get(eq("transfer-success"), any(TagResolver.class));
    }

    // -- per-player action lock ---------------------------------------------------------

    /**
     * Confirms {@code busyPlayers} actually rejects a second, concurrent-
     * looking call for the same player rather than merely existing as dead
     * code.
     *
     * <p>A literal "call {@code deposit(...)} twice back-to-back" cannot
     * exercise this: with {@code InlineAsyncDatabaseExecutors.inline(...)},
     * every leg of {@code deposit(...)}'s chain — the dispatched database
     * work, {@code thenAccept}, the mocked {@code runTask(...)} hop, and the
     * final {@code whenComplete} that removes the player from {@code
     * busyPlayers} — runs synchronously, on the calling thread, before the
     * outer {@code deposit(...)} call ever returns. So by the time a test
     * could make that second call, the first has already fully released the
     * lock; there is no "still in flight" window a sequential second call
     * could ever observe, and a test written that way would pass identically
     * whether or not {@code busyPlayers} existed at all.
     *
     * <p>{@code busyPlayers.add(...)} does, however, run strictly before
     * {@code treasuryService.deposit(...)} is invoked, and {@code
     * busyPlayers.remove(...)} cannot run until well after that call
     * returns — that gap is the real "in flight" window, just defined by
     * the call stack rather than by wall-clock concurrency. This test
     * exploits exactly that: a one-off, test-local {@code TreasuryService}
     * mock makes its own {@code deposit(...)} answer by synchronously
     * re-entering {@code RealmActions.deposit(...)} for the very same
     * player, from inside that still-open window — precisely what a second
     * thread's call would see if it happened to interleave right there. If
     * {@code busyPlayers} were removed, this nested call would proceed like
     * any other and double-charge; with it in place, the nested call must be
     * rejected outright.
     */
    @Test
    void depositRejectsAReentrantCallForTheSamePlayerWhileTheFirstIsStillInFlight() throws Exception {
        Realm realm = realmDao.insert(connection,
                new Realm(0L, "ember", "Ember", UUID.randomUUID(), 1, Instant.now(), null, null, null, null, null));
        realmCache.put(realm);
        UUID actorId = memberWithPermissions(realm.id(), 0L); // deposit needs no permission
        realmCache.putMember(actorId, realm.id());
        walletDao.ensureExists(connection, actorId);
        walletDao.tryAdjustBalance(connection, actorId, 1_000L);
        when(player.getUniqueId()).thenReturn(actorId);

        TreasuryService reentrantTreasuryService = mock(TreasuryService.class);
        RealmActions reentrantActions = new RealmActions(
                plugin, realmService, mock(EconomyService.class), reentrantTreasuryService,
                mock(ClaimService.class), mock(ClaimVisualizationService.class), mock(TerritoryMapService.class),
                mock(ClaimProtectionService.class), inline(connection), realmMemberDao, realmRankDao, realmCache,
                pricingConfig, messages);

        when(reentrantTreasuryService.deposit(eq(realm.id()), eq(actorId), any(Money.class)))
                .thenAnswer(invocation -> {
                    // Still inside the outer deposit(...) call - actorId is
                    // still in busyPlayers. A real concurrent second call
                    // arriving right now must be rejected.
                    reentrantActions.deposit(player, Money.ofCents(1L)).join();
                    return CompletableFuture.completedFuture(true);
                });

        reentrantActions.deposit(player, Money.ofCents(400L)).join();

        // The nested call never reached treasuryService.deposit(...) a
        // second time, and got action-in-progress instead.
        verify(reentrantTreasuryService, Mockito.times(1))
                .deposit(eq(realm.id()), eq(actorId), any(Money.class));
        verify(messages).get(eq("action-in-progress"));
        verify(messages).get(eq("deposit-success"), any(TagResolver.class));
    }
}
