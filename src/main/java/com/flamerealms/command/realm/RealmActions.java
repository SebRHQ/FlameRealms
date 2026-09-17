package com.flamerealms.command.realm;

import com.flamerealms.FlameRealmsPlugin;
import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.Messages;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.Money;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmClaim;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.DatabaseUnavailableException;
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.persistence.dao.RealmRankDao;
import com.flamerealms.protection.ClaimProtectionService;
import com.flamerealms.service.ClaimService;
import com.flamerealms.service.EconomyService;
import com.flamerealms.service.RealmService;
import com.flamerealms.service.TreasuryService;
import com.flamerealms.service.exception.CannotKickLeaderException;
import com.flamerealms.service.exception.ChunkAlreadyClaimedException;
import com.flamerealms.service.exception.ClaimNotContiguousException;
import com.flamerealms.service.exception.EconomyPersistenceException;
import com.flamerealms.service.exception.InsufficientFundsForRealmCreationException;
import com.flamerealms.service.exception.InsufficientTreasuryFundsException;
import com.flamerealms.service.exception.LeaderCannotLeaveException;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.exception.NotInvitedException;
import com.flamerealms.service.exception.NotRealmLeaderException;
import com.flamerealms.service.exception.PlayerAlreadyInRealmException;
import com.flamerealms.service.exception.PlayerNotInRealmException;
import com.flamerealms.service.exception.RealmNameTakenException;
import com.flamerealms.service.exception.RealmNotFoundException;
import com.flamerealms.service.exception.RealmPersistenceException;
import com.flamerealms.visualization.ClaimVisualizationService;
import com.flamerealms.visualization.TerritoryMapService;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The UI-framework-agnostic business logic behind every {@code /realm}
 * action — permission checks, calling {@link RealmService}/{@link
 * EconomyService}/{@link TreasuryService}/{@link ClaimService}, building and
 * sending every player-facing message, the async future-continuation/{@code
 * runSync} pattern, {@link #describeError(Throwable)}'s exception-to-message
 * mapping, and the {@link #pendingClaims} bookkeeping backing {@code claim}/
 * {@code claim confirm}.
 *
 * <p>This class was extracted verbatim (behavior-preserving) out of {@code
 * RealmCommand}, which used to mix this logic together with Brigadier
 * command-tree wiring and argument parsing. {@code RealmCommand} is now a
 * thin layer: it parses Brigadier arguments, resolves anything that needs a
 * Bukkit lookup purely to validate that an argument refers to something real
 * (an online player, an existing realm), and then delegates straight into
 * one of the public methods here. Nothing in this class references Brigadier
 * — every method takes only already-resolved values ({@link Player}/{@link
 * Audience}/{@link UUID}/{@link Money}/domain types) — so a future chest-GUI
 * front end can call the exact same methods and get identical behavior,
 * including every message sent.
 *
 * <p>Every method here does its own end-to-end work, including sending every
 * success/failure message itself. The {@code CompletableFuture<Void>}
 * returned by the methods that dispatch asynchronously is purely for a
 * caller's own bookkeeping (e.g. a GUI wanting to know when to refresh) — it
 * is not meant to be composed or chained further, and every message this
 * class sends is already sent by the time that future completes.
 *
 * <p>See the (former) {@code RealmCommand} class Javadoc for the full
 * rationale behind the never-block-the-main-thread shape every async method
 * here follows: dispatch, return immediately, apply the result back on the
 * main thread via {@link #runSync(Runnable)} before touching the Bukkit API.
 */
public final class RealmActions {

    /**
     * How long a {@code claim} preview remains confirmable via {@link
     * #confirmClaim(Player)} before it must be rejected and a fresh preview
     * required. Not (yet) exposed as a server-admin config value — this is a
     * short, implementation-level UX window, not a tunable pricing/gameplay
     * constant the way {@code pricing.yml}'s bands are.
     */
    private static final long PENDING_CLAIM_EXPIRY_SECONDS = 30L;

    private final FlameRealmsPlugin plugin;
    private final RealmService realmService;
    private final EconomyService economyService;
    private final TreasuryService treasuryService;
    private final ClaimService claimService;
    private final ClaimVisualizationService claimVisualizationService;
    private final TerritoryMapService territoryMapService;
    private final ClaimProtectionService claimProtectionService;
    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final RealmMemberDao realmMemberDao;
    private final RealmRankDao realmRankDao;
    private final RealmCache realmCache;
    private final PricingConfig pricingConfig;
    private final Messages messages;

    /**
     * A given player has at most one outstanding claim preview at a time —
     * previewing a new chunk (calling {@link #previewClaim(Player)} again)
     * simply overwrites it. Never read/written from anywhere but the main
     * thread (every action here only touches it directly or from inside a
     * {@link #runSync(Runnable)}-wrapped continuation), so a plain {@link
     * ConcurrentHashMap} is used purely for safety against any future
     * caller, not because concurrent access is actually expected.
     */
    private final ConcurrentHashMap<UUID, PendingClaim> pendingClaims = new ConcurrentHashMap<>();

    /**
     * Per-player in-flight-action lock for every money-moving command (M2
     * onward, per this project's own TODO.md): {@link #createRealm},
     * {@link #deposit}, {@link #withdraw}, {@link #confirmClaim} and
     * {@link #unclaim} each add the acting player's UUID here immediately
     * before dispatching, and remove it (via {@code whenComplete}, so it
     * runs no matter how the future ends — success, an expected failure, or
     * an exceptional one) once that dispatch fully resolves. A player who
     * already has one of those five in flight gets {@code
     * action-in-progress} instead of a second dispatch, closing the same
     * double-spend/double-submit window a rapid double-click or macro could
     * otherwise exploit. Deliberately NOT used by {@code kick}/{@code
     * setRank}/{@code transfer}/{@code join}/{@code leave} — none of those
     * move money.
     */
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();

    public RealmActions(
            FlameRealmsPlugin plugin,
            RealmService realmService,
            EconomyService economyService,
            TreasuryService treasuryService,
            ClaimService claimService,
            ClaimVisualizationService claimVisualizationService,
            TerritoryMapService territoryMapService,
            ClaimProtectionService claimProtectionService,
            AsyncDatabaseExecutor asyncDatabaseExecutor,
            RealmMemberDao realmMemberDao,
            RealmRankDao realmRankDao,
            RealmCache realmCache,
            PricingConfig pricingConfig,
            Messages messages
    ) {
        this.plugin = plugin;
        this.realmService = realmService;
        this.economyService = economyService;
        this.treasuryService = treasuryService;
        this.claimService = claimService;
        this.claimVisualizationService = claimVisualizationService;
        this.territoryMapService = territoryMapService;
        this.claimProtectionService = claimProtectionService;
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.realmMemberDao = realmMemberDao;
        this.realmRankDao = realmRankDao;
        this.realmCache = realmCache;
        this.pricingConfig = pricingConfig;
        this.messages = messages;
    }

    // -- shared: realm-membership precondition -------------------------------

    /**
     * Returns {@code player}'s current realm, sending {@code not-in-realm}
     * and returning {@link Optional#empty()} if they aren't in one. Every
     * mutating method in this class ({@link #kick}, {@link #setRank}, {@link
     * #transfer}, etc.) already performs this exact check-and-message
     * pattern on its own before dispatching, but a GUI caller (see {@code
     * com.flamerealms.gui.MainMenu}'s kick/setrank/transfer buttons) needs
     * the {@link Realm}'s id up front, before it can even open a
     * member-selector menu — well before any of those methods would
     * otherwise be called. Exposed standalone so that caller doesn't have to
     * duplicate the check-and-message logic itself.
     */
    public Optional<Realm> requireRealm(Player player) {
        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
        }
        return realmOpt;
    }

    // -- create ---------------------------------------------------------

    /**
     * Ported from {@code RealmCommand#executeCreate}, updated for {@code
     * RealmService#createRealm}'s now-required Nexus location: a realm may
     * only be founded standing on a placed Beacon block, whose own (block,
     * not fractional player) coordinates become the realm's Nexus. The
     * Beacon check is a synchronous, main-thread-only Bukkit read — no
     * database call — so a missing Beacon returns immediately without ever
     * calling {@link RealmService#createRealm} (and therefore without ever
     * attempting to charge the creation fee).
     */
    public CompletableFuture<Void> createRealm(Player player, String name) {
        UUID leaderId = player.getUniqueId();

        // Synchronous, main-thread Bukkit read — NOT a database call. The
        // block directly beneath the player's feet, not the player's own
        // fractional location, is what becomes the realm's Nexus.
        Block standFoot = player.getLocation().subtract(0, 1, 0).getBlock();
        if (standFoot.getType() != Material.BEACON) {
            player.sendMessage(messages.get("realm-create-requires-beacon"));
            return CompletableFuture.completedFuture(null);
        }

        if (!busyPlayers.add(leaderId)) {
            player.sendMessage(messages.get("action-in-progress"));
            return CompletableFuture.completedFuture(null);
        }

        String nexusWorld = standFoot.getWorld().getName();
        int nexusX = standFoot.getX();
        int nexusY = standFoot.getY();
        int nexusZ = standFoot.getZ();

        // Non-blocking: createRealm() dispatches through AsyncDatabaseExecutor
        // and returns a CompletableFuture<Realm>. We return immediately; the
        // continuation below runs later, on the DB worker thread, and hops
        // back to the main thread via runSync(...) before it ever touches
        // player.sendMessage(...).
        return realmService.createRealm(leaderId, name, nexusWorld, nexusX, nexusY, nexusZ)
                .thenAccept(realm -> runSync(() -> {
                    player.sendMessage(messages.get(
                            "realm-created", Placeholder.unparsed("name", realm.displayName())));
                    // Sent as a second message rather than folded into
                    // "realm-created" itself, so that existing message's
                    // "name" placeholder and wording stay exactly as they
                    // were before this stage — a server owner who already
                    // restyled "realm-created" keeps that customization
                    // untouched, and gets this new fee/Nexus detail as a
                    // separately toggleable line underneath it.
                    player.sendMessage(messages.get(
                            "realm-created-nexus",
                            Placeholder.unparsed("fee", Money.ofCents(
                                    pricingConfig.realmCreationFeeCents()).toString()),
                            Placeholder.unparsed("world", nexusWorld),
                            Placeholder.unparsed("x", String.valueOf(nexusX)),
                            Placeholder.unparsed("y", String.valueOf(nexusY)),
                            Placeholder.unparsed("z", String.valueOf(nexusZ))));
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                })
                .whenComplete((ignored, ignoredError) -> busyPlayers.remove(leaderId));
    }

    // -- info -------------------------------------------------------------

    /**
     * Convenience wrapper for a caller (the GUI) that doesn't want to
     * duplicate the {@link RealmService#getByPlayer(UUID)} lookup itself.
     */
    public void showInfoSelf(Player player) {
        showInfo(player, realmService.getByPlayer(player.getUniqueId()), null);
    }

    /** Ported verbatim from {@code RealmCommand#sendRealmInfo}. */
    public void showInfo(Audience audience, Optional<Realm> realmOpt, String requestedName) {
        if (realmOpt.isEmpty()) {
            if (requestedName != null) {
                audience.sendMessage(messages.get(
                        "realm-not-found-named", Placeholder.unparsed("name", requestedName)));
            } else {
                audience.sendMessage(messages.get("not-in-realm"));
            }
            return;
        }

        Realm realm = realmOpt.get();

        // Bukkit.getOfflinePlayer(UUID) is not a safe main-thread call: when
        // the leader isn't already resident in the server's in-memory
        // profile cache (e.g. hasn't been online this session), it falls
        // back to a synchronous usercache/playerdata disk read. Resolve it
        // off-thread and hop back via runSync(...) before sending anything,
        // same as every other Bukkit API touch that follows a dispatch in
        // this class.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String leaderName = Bukkit.getOfflinePlayer(realm.leaderUuid()).getName();
            if (leaderName == null) {
                leaderName = realm.leaderUuid().toString();
            }
            String resolvedLeaderName = leaderName;
            runSync(() -> audience.sendMessage(messages.get(
                    "realm-info",
                    Placeholder.unparsed("name", realm.displayName()),
                    Placeholder.unparsed("leader", resolvedLeaderName),
                    Placeholder.unparsed("level", String.valueOf(realm.level())))));
        });
    }

    // -- invite -------------------------------------------------------------

    /**
     * Ported from {@code RealmCommand#executeInvite}, given {@code target}
     * already resolved to an online {@link Player} that isn't {@code
     * inviter} — the "is target null/offline" and "inviting yourself" checks
     * only make sense when parsing a raw command argument (a name that might
     * not resolve to anyone, or might resolve to the sender), so they are
     * left in {@code RealmCommand} rather than duplicated here: a GUI
     * player-selector menu only ever lists online players other than the
     * viewer, so those checks can never fire for a GUI caller anyway.
     */
    public CompletableFuture<Void> invite(Player inviter, Player target) {
        Optional<Realm> realmOpt = realmService.getByPlayer(inviter.getUniqueId());
        if (realmOpt.isEmpty()) {
            inviter.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }

        Realm realm = realmOpt.get();
        long realmId = realm.id();
        UUID inviterId = inviter.getUniqueId();
        UUID targetId = target.getUniqueId();

        // RealmService.invite() does NO permission check on the inviter (see
        // RealmServiceImpl's class Javadoc) — that enforcement belongs here.
        // Resolving it needs realm_members/realm_ranks, which RealmCache does
        // not hold, so this leg is async: dispatched through
        // AsyncDatabaseExecutor, never blocked on. RealmService.invite()
        // itself is now ALSO async (it persists the invite to realm_invites),
        // so the permission check and the invite call are chained together
        // with thenCompose(...) rather than the permission check alone being
        // the only async leg — the pairing below shuttles "was this call even
        // attempted" through to the single thenAccept/exceptionally pair that
        // hops back to the main thread via runSync(...) before sending
        // anything.
        return hasInvitePermission(realmId, inviterId)
                .thenCompose(allowed -> allowed
                        ? realmService.invite(realmId, inviterId, targetId).thenApply(v -> true)
                        : CompletableFuture.completedFuture(false))
                .thenAccept(permitted -> runSync(() -> {
                    if (!permitted) {
                        inviter.sendMessage(messages.get("invite-no-permission"));
                        return;
                    }
                    inviter.sendMessage(messages.get(
                            "invite-sent", Placeholder.unparsed("name", target.getName())));
                    target.sendMessage(messages.get(
                            "invite-received", Placeholder.unparsed("name", realm.name())));
                }))
                .exceptionally(ex -> {
                    runSync(() -> inviter.sendMessage(describeError(ex)));
                    return null;
                });
    }

    /**
     * Resolves whether {@code actor} currently holds a rank with
     * {@link RealmPermission#INVITE} inside {@code realmId}. Dispatched
     * through {@link AsyncDatabaseExecutor} since it reads {@code
     * realm_members}/{@code realm_ranks} directly — neither is cached by
     * {@code RealmCache}.
     */
    private CompletableFuture<Boolean> hasInvitePermission(long realmId, UUID actor) {
        return asyncDatabaseExecutor.submit(connection -> {
            try {
                RealmMember member = realmMemberDao.findByPlayerUuid(connection, actor).orElse(null);
                if (member == null || member.realmId() != realmId) {
                    return false;
                }
                RealmRank rank = realmRankDao.findById(connection, member.rankId()).orElse(null);
                if (rank == null) {
                    return false;
                }
                return RealmPermission.has(rank.permissions(), RealmPermission.INVITE);
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to resolve invite permission for player " + actor + " in realm " + realmId, e);
            }
        });
    }

    // -- join -----------------------------------------------------------

    /**
     * Ported verbatim from {@code RealmCommand#executeJoin}, given an
     * already-resolved {@link Realm}, plus one addition: once the join
     * commits, every claim the realm already owns must have its WorldGuard
     * region membership resynced to include the newly-joined player. {@code
     * realmCache.claimsOf(realmId)} is synchronous; the fresh member list
     * (now including the joiner) needs {@link RealmService#getMemberUuids}, so
     * that fetch is chained onto the join future before hopping back to the
     * main thread, exactly like {@link #confirmClaim(Player)} does for its
     * own claim+members pairing. A realm with zero claims yet is a no-op.
     */
    public CompletableFuture<Void> join(Player player, Realm realm) {
        long realmId = realm.id();

        // Non-blocking: join() returns a CompletableFuture<Void>. We return
        // immediately; the continuation hops back to the main thread via
        // runSync(...) before sending any message.
        return realmService.join(player.getUniqueId(), realm.id())
                .thenCompose(v -> fetchClaimResync(realmId))
                .thenAccept(resync -> runSync(() -> {
                    player.sendMessage(messages.get(
                            "joined-realm", Placeholder.unparsed("name", realm.displayName())));
                    applyClaimResync(realmId, resync);
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });
    }

    // -- leave ------------------------------------------------------------

    /**
     * Ported verbatim from {@code RealmCommand#executeLeave}, plus one
     * addition: once the leave commits, the player who just left must be
     * removed from every WorldGuard region belonging to their former realm.
     * The realm id and its claim set are captured up front, before {@code
     * leave()} is even dispatched — {@code realmCache.getByPlayer(player)}
     * would no longer resolve it once the player has actually left — then
     * the updated (post-leave) member list is fetched once the leave
     * commits, same shape as {@link #join(Player, Realm)}'s own resync.
     */
    public CompletableFuture<Void> leave(Player player) {
        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        long realmId = realmOpt.map(Realm::id).orElse(-1L);
        Set<ChunkCoordinate> claimsBeforeLeaving = realmOpt.isPresent()
                ? Set.copyOf(realmCache.claimsOf(realmId))
                : Set.of();

        // Non-blocking: leave() returns a CompletableFuture<Void>. Same
        // pattern as create/join — return immediately, apply the result
        // (success or failure) back on the main thread via runSync(...).
        return realmService.leave(player.getUniqueId())
                .thenCompose(v -> fetchClaimResync(realmId, claimsBeforeLeaving))
                .thenAccept(resync -> runSync(() -> {
                    player.sendMessage(messages.get("left-realm"));
                    applyClaimResync(realmId, resync);
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });
    }

    // -- disband ----------------------------------------------------------

    /**
     * Ported verbatim from {@code RealmCommand#executeDisband}, plus one
     * addition: once the disband commits, every claim the realm owned must
     * be entirely unprotected — no realm exists anymore for anyone to be a
     * member of. The claim set is captured up front, as an immutable
     * snapshot, since {@code RealmServiceImpl#disbandRealm}'s own cache
     * cleanup ({@code realmCache.removeAllClaimsOf(realmId)}) only runs
     * after the future returned below completes.
     */
    public CompletableFuture<Void> disband(Player player) {
        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }

        long realmId = realmOpt.get().id();
        String realmName = realmOpt.get().displayName();
        Set<ChunkCoordinate> claimsBeforeDisband = Set.copyOf(realmCache.claimsOf(realmId));

        // Non-blocking: disbandRealm() dispatches through AsyncDatabaseExecutor
        // and returns a CompletableFuture<Void>. Same pattern as leave/join —
        // return immediately, apply the result back on the main thread.
        // RealmService itself rejects the call with NotRealmLeaderException
        // (see describeError) if the caller isn't that realm's leader.
        return realmService.disbandRealm(realmId, player.getUniqueId())
                .thenAccept(v -> runSync(() -> {
                    player.sendMessage(messages.get(
                            "realm-disbanded", Placeholder.unparsed("name", realmName)));
                    for (ChunkCoordinate claim : claimsBeforeDisband) {
                        claimProtectionService.unprotectClaim(claim.world(), claim.chunkX(), claim.chunkZ());
                    }
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });
    }

    // -- balance ------------------------------------------------------------

    /** Ported verbatim from {@code RealmCommand#executeBalance}/{@code sendBalance}. */
    public CompletableFuture<Void> showBalance(Player player) {
        UUID playerId = player.getUniqueId();
        // getByPlayer() is a synchronous RealmCache read, safe to call
        // directly from this (main) thread — see class Javadoc.
        Optional<Realm> realmOpt = realmService.getByPlayer(playerId);

        // Personal wallet and realm treasury (if any) are independent reads
        // touching no shared mutable state, so they are fired concurrently
        // and combined rather than sequenced. Neither future is ever
        // blocked on; the combined continuation hops back to the main
        // thread via runSync(...) before sending anything.
        CompletableFuture<Money> personalFuture = economyService.balanceOf(playerId);

        if (realmOpt.isEmpty()) {
            return personalFuture
                    .thenAccept(personal -> runSync(() -> sendBalance(player, personal, null)))
                    .exceptionally(ex -> {
                        runSync(() -> player.sendMessage(describeError(ex)));
                        return null;
                    });
        }

        long realmId = realmOpt.get().id();
        CompletableFuture<Money> treasuryFuture = treasuryService.balanceOf(realmId);

        return personalFuture.thenCombine(treasuryFuture, Balances::new)
                .thenAccept(balances -> runSync(() -> sendBalance(player, balances.personal(), balances.treasury())))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });
    }

    private void sendBalance(Audience audience, Money personal, Money treasury) {
        if (treasury == null) {
            audience.sendMessage(messages.get(
                    "balance-personal-only", Placeholder.unparsed("personal", personal.toString())));
            return;
        }
        audience.sendMessage(messages.get(
                "balance-personal-and-treasury",
                Placeholder.unparsed("personal", personal.toString()),
                Placeholder.unparsed("treasury", treasury.toString())));
    }

    /** Pairing of concurrently-fetched balances, used only to feed {@code thenCombine}. */
    private record Balances(Money personal, Money treasury) {
    }

    // -- deposit ----------------------------------------------------------

    /**
     * Ported from the post-parsing half of {@code RealmCommand#executeDeposit}
     * — {@code amount} is already resolved to a {@link Money} by the caller
     * (via {@code MoneyParsing.parseAmountToCents} for the Brigadier path).
     */
    public CompletableFuture<Void> deposit(Player player, Money amount) {
        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }

        long realmId = realmOpt.get().id();
        UUID playerId = player.getUniqueId();

        if (!busyPlayers.add(playerId)) {
            player.sendMessage(messages.get("action-in-progress"));
            return CompletableFuture.completedFuture(null);
        }

        // Non-blocking: deposit() dispatches through AsyncDatabaseExecutor
        // and returns a CompletableFuture<Boolean>. Return immediately;
        // apply the result back on the main thread.
        return treasuryService.deposit(realmId, playerId, amount)
                .thenAccept(ok -> runSync(() -> {
                    if (ok) {
                        player.sendMessage(messages.get(
                                "deposit-success", Placeholder.unparsed("amount", amount.toString())));
                    } else {
                        player.sendMessage(messages.get(
                                "deposit-insufficient-funds", Placeholder.unparsed("amount", amount.toString())));
                    }
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                })
                .whenComplete((ignored, ignoredError) -> busyPlayers.remove(playerId));
    }

    // -- withdraw ---------------------------------------------------------

    /**
     * Ported from the post-parsing half of {@code RealmCommand#executeWithdraw}
     * — {@code amount} is already resolved to a {@link Money} by the caller.
     */
    public CompletableFuture<Void> withdraw(Player player, Money amount) {
        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }

        long realmId = realmOpt.get().id();
        UUID playerId = player.getUniqueId();

        if (!busyPlayers.add(playerId)) {
            player.sendMessage(messages.get("action-in-progress"));
            return CompletableFuture.completedFuture(null);
        }

        // Non-blocking, same shape as deposit(): withdraw() returns a
        // CompletableFuture<Boolean>, applied back on the main thread. A
        // failed permission check fails the future (see describeError()'s
        // MissingPermissionException case); an ordinary insufficient-funds
        // outcome is a plain `false`, distinguished from that here.
        return treasuryService.withdraw(realmId, playerId, amount)
                .thenAccept(ok -> runSync(() -> {
                    if (ok) {
                        player.sendMessage(messages.get(
                                "withdraw-success", Placeholder.unparsed("amount", amount.toString())));
                    } else {
                        player.sendMessage(messages.get(
                                "withdraw-insufficient-funds", Placeholder.unparsed("amount", amount.toString())));
                    }
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                })
                .whenComplete((ignored, ignoredError) -> busyPlayers.remove(playerId));
    }

    // -- claim / claim confirm ---------------------------------------------

    /**
     * {@code /realm claim} — previews claiming the player's current chunk:
     * computes what it would cost, shows a particle outline of the chunk via
     * {@link ClaimVisualizationService#previewChunk}, and stores a {@link
     * PendingClaim} for this player that {@link #confirmClaim(Player)}
     * consumes. Makes no database call and purchases nothing by itself.
     * Ported verbatim from {@code RealmCommand#executeClaimPreview}.
     */
    public void previewClaim(Player player) {
        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return;
        }
        long realmId = realmOpt.get().id();

        // Synchronous, main-thread chunk read — this is ordinary Bukkit API,
        // NOT a database call.
        String world = player.getWorld().getName();
        int chunkX = player.getLocation().getChunk().getX();
        int chunkZ = player.getLocation().getChunk().getZ();

        // Preview-only price: RealmCache.claimsOf(...) is the same
        // synchronous cache read used elsewhere in this class, and
        // purchasePriceCents(existingCount + 1) is the exact lookup
        // ClaimServiceImpl performs for real inside purchaseClaim's
        // transaction. Nothing is charged or persisted here — the
        // transaction re-checks this count itself, so a confirm after the
        // realm's claim count has since changed can still be charged a
        // different price than this preview shows.
        int existingClaimCount = realmCache.claimsOf(realmId).size();
        long priceCents = pricingConfig.purchasePriceCents(existingClaimCount + 1);
        Money price = Money.ofCents(priceCents);

        Instant expiresAt = Instant.now().plusSeconds(PENDING_CLAIM_EXPIRY_SECONDS);
        pendingClaims.put(player.getUniqueId(), new PendingClaim(realmId, world, chunkX, chunkZ, expiresAt));

        claimVisualizationService.previewChunk(player, world, chunkX, chunkZ);

        player.sendMessage(messages.get(
                "claim-preview",
                Placeholder.unparsed("price", price.toString()),
                Placeholder.unparsed("world", world),
                Placeholder.unparsed("chunk-x", String.valueOf(chunkX)),
                Placeholder.unparsed("chunk-z", String.valueOf(chunkZ)),
                Placeholder.unparsed("seconds", String.valueOf(PENDING_CLAIM_EXPIRY_SECONDS))));
    }

    /**
     * {@code /realm claim confirm} — consumes this player's {@link
     * PendingClaim} (if any, and if not expired) and actually calls {@link
     * ClaimService#purchaseClaim}. The pending entry is removed up front,
     * before the future is even created, so a failed or expired confirm can
     * never be retried against a stale preview — the player must call
     * {@link #previewClaim(Player)} again for a fresh one. Ported verbatim
     * from {@code RealmCommand#executeClaimConfirm}, plus one addition: once
     * the purchase commits, the newly-claimed chunk must be protected —
     * a WorldGuard region denying {@code BUILD} to everyone except the
     * realm's current members. That needs the realm's member list, fetched
     * via {@link RealmService#getMemberUuids} and chained onto the purchase
     * future so both the purchased {@link RealmClaim} and the member list
     * are in hand by the time this hops back to the main thread. If the
     * member-list fetch itself fails, that failure is logged and swallowed
     * rather than suppressing the claim-success message — the claim
     * purchase already succeeded regardless of whether protection could be
     * set up (see {@code ClaimProtectionService}'s own "best-effort" Javadoc).
     */
    public CompletableFuture<Void> confirmClaim(Player player) {
        UUID playerId = player.getUniqueId();
        PendingClaim pending = pendingClaims.remove(playerId);
        if (pending == null) {
            player.sendMessage(messages.get("claim-confirm-none"));
            return CompletableFuture.completedFuture(null);
        }
        if (pending.isExpired()) {
            player.sendMessage(messages.get("claim-confirm-expired"));
            return CompletableFuture.completedFuture(null);
        }

        if (!busyPlayers.add(playerId)) {
            player.sendMessage(messages.get("action-in-progress"));
            return CompletableFuture.completedFuture(null);
        }

        // Non-blocking: purchaseClaim() dispatches through
        // AsyncDatabaseExecutor and returns a CompletableFuture<RealmClaim>.
        // Return immediately; apply the result back on the main thread via
        // runSync(...) before sending any message.
        return claimService.purchaseClaim(pending.realmId(), playerId, pending.world(), pending.chunkX(), pending.chunkZ())
                .thenCompose(claim -> realmService.getMemberUuids(pending.realmId())
                        .handle((members, error) -> new ClaimPurchaseResync(claim, members, error)))
                .thenAccept(resync -> runSync(() -> {
                    player.sendMessage(messages.get(
                            "claim-confirm-success",
                            Placeholder.unparsed("price", Money.ofCents(resync.claim().pricePaidCents()).toString())));
                    if (resync.memberError() != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to fetch member list to protect claim " + resync.claim().coordinate()
                                        + " for realm " + pending.realmId(), resync.memberError());
                        return;
                    }
                    claimProtectionService.protectClaim(
                            resync.claim().world(), resync.claim().chunkX(), resync.claim().chunkZ(),
                            Set.copyOf(resync.members()));
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                })
                .whenComplete((ignored, ignoredError) -> busyPlayers.remove(playerId));
    }

    // -- unclaim ------------------------------------------------------------

    /**
     * {@code /realm unclaim} — releases the player's current chunk from
     * their realm's territory, no preview/confirm step. Per this project's
     * M2 judgment, unclaiming is free (no refund) and immediate rather than
     * gated behind a confirmation the way a purchase is; see {@code
     * ClaimService#unclaimChunk}'s own contract for why no refund/connectivity
     * guard applies here. Ported verbatim from {@code
     * RealmCommand#executeUnclaim}, plus one addition: once {@code
     * unclaimChunk} actually removes a claim, its WorldGuard region is torn
     * down too.
     */
    public CompletableFuture<Void> unclaim(Player player) {
        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }
        long realmId = realmOpt.get().id();
        UUID playerId = player.getUniqueId();

        // Synchronous, main-thread chunk read — same as claim's, NOT a
        // database call.
        String world = player.getWorld().getName();
        int chunkX = player.getLocation().getChunk().getX();
        int chunkZ = player.getLocation().getChunk().getZ();

        if (!busyPlayers.add(playerId)) {
            player.sendMessage(messages.get("action-in-progress"));
            return CompletableFuture.completedFuture(null);
        }

        // Non-blocking: unclaimChunk() dispatches through
        // AsyncDatabaseExecutor and returns a CompletableFuture<Boolean>.
        // Same pattern as deposit/withdraw — apply the result back on the
        // main thread, distinguishing "nothing there to unclaim" (false)
        // from an exceptional failure.
        return claimService.unclaimChunk(realmId, playerId, world, chunkX, chunkZ)
                .thenAccept(unclaimed -> runSync(() -> {
                    if (unclaimed) {
                        player.sendMessage(messages.get("unclaim-success"));
                        claimProtectionService.unprotectClaim(world, chunkX, chunkZ);
                    } else {
                        player.sendMessage(messages.get("unclaim-not-claimed"));
                    }
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                })
                .whenComplete((ignored, ignoredError) -> busyPlayers.remove(playerId));
    }

    // -- map -------------------------------------------------------------------

    /**
     * {@code /realm map} — sends {@link TerritoryMapService#renderMap}'s
     * result directly. Fully synchronous, main-thread-only, no database I/O
     * at all — no future/continuation involved. Ported verbatim from
     * {@code RealmCommand#executeMap}.
     */
    public void showMap(Player player) {
        player.sendMessage(territoryMapService.renderMap(player));
    }

    // -- borders -----------------------------------------------------------

    /**
     * {@code /realm borders} — toggles a persistent particle outline of the
     * player's whole realm territory on/off. Requires the player be in a
     * realm to turn it ON; {@link ClaimVisualizationService#toggleBorders}
     * itself re-checks realm membership every pulse and auto-stops if it
     * changes, so no further state is tracked here. Fully synchronous,
     * main-thread-only, no database I/O — same shape as {@link
     * #showMap(Player)}. Ported verbatim from {@code RealmCommand#executeBorders}.
     */
    public void toggleBorders(Player player) {
        if (realmService.getByPlayer(player.getUniqueId()).isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return;
        }

        boolean nowOn = claimVisualizationService.toggleBorders(player);
        player.sendMessage(messages.get(nowOn ? "borders-enabled" : "borders-disabled"));
    }

    // -- kick -----------------------------------------------------------------

    /**
     * {@code /realm kick <player>} — removes {@code targetUuid} from {@code
     * actor}'s realm against their will. {@code targetUuid}/{@code
     * targetDisplayName} arrive already resolved by the caller (see {@code
     * RealmCommand}'s offline-tolerant lookup, mirroring {@link
     * #showInfo(Audience, Optional, String)}'s own reason for resolving
     * off-thread) — a kicked player need not be online. Not one of the five
     * money-moving actions {@link #busyPlayers} guards. Once the kick
     * commits, every claim the realm owns has its WorldGuard region
     * membership resynced against the now-smaller member list — same {@link
     * #fetchClaimResync(long)}/{@link #applyClaimResync(long, ClaimResync)}
     * pattern {@link #join(Player, Realm)}/{@link #leave(Player)} already
     * use; the kicked player is simply absent from that fresh list, no
     * special-casing needed.
     */
    public CompletableFuture<Void> kick(Player actor, UUID targetUuid, String targetDisplayName) {
        Optional<Realm> realmOpt = realmService.getByPlayer(actor.getUniqueId());
        if (realmOpt.isEmpty()) {
            actor.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }
        long realmId = realmOpt.get().id();

        return realmService.kick(realmId, actor.getUniqueId(), targetUuid)
                .thenCompose(v -> fetchClaimResync(realmId))
                .thenAccept(resync -> runSync(() -> {
                    actor.sendMessage(messages.get(
                            "kick-success", Placeholder.unparsed("name", targetDisplayName)));
                    applyClaimResync(realmId, resync);
                }))
                .exceptionally(ex -> {
                    runSync(() -> actor.sendMessage(describeError(ex)));
                    return null;
                });
    }

    // -- setRank ----------------------------------------------------------------

    /**
     * {@code /realm setrank <player> <rank>} — changes {@code targetUuid}'s
     * rank within {@code actor}'s realm to the rank named {@code rankName}.
     * Resolving a rank name to its id needs a {@code realm_ranks} read, which
     * {@link RealmCache} does not hold, so — same shape as {@link
     * #hasInvitePermission(long, UUID)} — that lookup is dispatched through
     * {@link AsyncDatabaseExecutor} and chained via {@code thenCompose(...)}
     * into {@link RealmService#setRank}. If no rank by that name exists in
     * this realm, {@link RealmService#setRank} is never called at all.
     */
    public CompletableFuture<Void> setRank(
            Player actor, UUID targetUuid, String targetDisplayName, String rankName) {
        Optional<Realm> realmOpt = realmService.getByPlayer(actor.getUniqueId());
        if (realmOpt.isEmpty()) {
            actor.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }
        long realmId = realmOpt.get().id();

        return resolveRankIdByName(realmId, rankName)
                .thenCompose(rankId -> rankId == null
                        ? CompletableFuture.completedFuture(null)
                        : realmService.setRank(realmId, actor.getUniqueId(), targetUuid, rankId))
                .thenAccept(v -> runSync(() -> actor.sendMessage(messages.get(
                        "setrank-success",
                        Placeholder.unparsed("name", targetDisplayName),
                        Placeholder.unparsed("rank", rankName)))))
                .exceptionally(ex -> {
                    runSync(() -> actor.sendMessage(describeError(ex)));
                    return null;
                });
    }

    /**
     * Resolves {@code rankName} to its id within {@code realmId} via {@link
     * RealmRankDao#findByRealmAndName}, dispatched through {@link
     * AsyncDatabaseExecutor} exactly like {@link #hasInvitePermission(long,
     * UUID)} — a {@code realm_ranks} read has no synchronous cache path.
     * Completes with {@code null} (never exceptionally for a merely-missing
     * rank) if no such rank exists, so {@link #setRank(Player, UUID, String,
     * String)}'s caller can special-case that into {@code error-rank-not-found}
     * without ever reaching {@link RealmService#setRank}.
     */
    private CompletableFuture<Long> resolveRankIdByName(long realmId, String rankName) {
        return asyncDatabaseExecutor.submit(connection -> {
            try {
                return realmRankDao.findByRealmAndName(connection, realmId, rankName)
                        .map(RealmRank::id)
                        .orElse(null);
            } catch (SQLException e) {
                throw new RealmPersistenceException(
                        "Failed to resolve rank '" + rankName + "' for realm " + realmId, e);
            }
        });
    }

    // -- transfer -----------------------------------------------------------------

    /**
     * {@code /realm transfer <player>} — hands {@code currentLeader}'s realm
     * leadership to {@code newLeaderUuid}. {@link NotRealmLeaderException}/
     * {@link PlayerNotInRealmException} (already handled by {@link
     * #describeError}) cover every failure mode {@link
     * RealmService#transferLeadership} can produce.
     */
    public CompletableFuture<Void> transfer(Player currentLeader, UUID newLeaderUuid, String newLeaderDisplayName) {
        Optional<Realm> realmOpt = realmService.getByPlayer(currentLeader.getUniqueId());
        if (realmOpt.isEmpty()) {
            currentLeader.sendMessage(messages.get("not-in-realm"));
            return CompletableFuture.completedFuture(null);
        }
        long realmId = realmOpt.get().id();

        return realmService.transferLeadership(realmId, currentLeader.getUniqueId(), newLeaderUuid)
                .thenAccept(v -> runSync(() -> currentLeader.sendMessage(messages.get(
                        "transfer-success", Placeholder.unparsed("name", newLeaderDisplayName)))))
                .exceptionally(ex -> {
                    runSync(() -> currentLeader.sendMessage(describeError(ex)));
                    return null;
                });
    }

    // -- shared helpers -----------------------------------------------------

    /** Hops back onto the main thread. Every Bukkit API touch inside a future continuation goes through this. */
    private void runSync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    /**
     * Pairing of a realm's claim set with the (possibly failed) fetch of its
     * current member list, used only to shuttle both values through {@link
     * #join(Player, Realm)}'s and {@link #leave(Player)}'s {@code
     * thenCompose}/{@code thenAccept} chain into {@link
     * #applyClaimResync(long, ClaimResync)}. {@code memberError} is {@code
     * null} on a successful fetch (or when {@code claims} was empty and the
     * fetch was skipped entirely).
     */
    private record ClaimResync(Set<ChunkCoordinate> claims, List<UUID> members, Throwable memberError) {
    }

    /**
     * Fetches {@code realmId}'s current claim set (synchronous, cached) and,
     * only if it's non-empty, its current member list (async) — a realm with
     * no claims yet has nothing to resync, so the member fetch is skipped
     * entirely rather than wastefully dispatched. Never completes
     * exceptionally: a failed member fetch is captured in the returned
     * {@link ClaimResync#memberError()} instead, for {@link
     * #applyClaimResync(long, ClaimResync)} to log.
     */
    private CompletableFuture<ClaimResync> fetchClaimResync(long realmId) {
        return fetchClaimResync(realmId, realmCache.claimsOf(realmId));
    }

    /** Same as {@link #fetchClaimResync(long)}, but for an already-captured claim set (see {@link #leave(Player)}). */
    private CompletableFuture<ClaimResync> fetchClaimResync(long realmId, Set<ChunkCoordinate> claims) {
        if (claims.isEmpty()) {
            return CompletableFuture.completedFuture(new ClaimResync(claims, List.of(), null));
        }
        return realmService.getMemberUuids(realmId)
                .handle((members, error) -> new ClaimResync(claims, members, error));
    }

    /**
     * Re-protects every claim in {@code resync.claims()} against {@code
     * resync.members()} — one {@link ClaimProtectionService#protectClaim}
     * call per claim, resyncing that region's membership to the realm's
     * current roster. Must run on the main thread (called only from inside
     * an existing {@link #runSync(Runnable)} block). A no-op if there were no
     * claims to resync; logs and otherwise does nothing if the member fetch
     * that produced {@code resync} failed — never lets a resync failure
     * suppress the join/leave success message already sent alongside it.
     */
    private void applyClaimResync(long realmId, ClaimResync resync) {
        if (resync.claims().isEmpty()) {
            return;
        }
        if (resync.memberError() != null) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to resync claim protection for realm " + realmId, resync.memberError());
            return;
        }
        Set<UUID> memberSet = Set.copyOf(resync.members());
        for (ChunkCoordinate claim : resync.claims()) {
            claimProtectionService.protectClaim(claim.world(), claim.chunkX(), claim.chunkZ(), memberSet);
        }
    }

    /**
     * Pairing of a freshly-purchased claim with the (possibly failed) fetch
     * of its realm's member list, used only to shuttle both values through
     * {@link #confirmClaim(Player)}'s {@code thenCompose}/{@code thenAccept}
     * chain.
     */
    private record ClaimPurchaseResync(RealmClaim claim, List<UUID> members, Throwable memberError) {
    }

    /**
     * Moved verbatim from {@code RealmCommand}, plus one new case added by
     * this stage: {@link DatabaseUnavailableException} (thrown when {@code
     * AsyncDatabaseExecutor} is running in its {@code unavailable()} mode
     * because the database was never reachable at startup) maps to the
     * dedicated {@code error-database-unavailable} message rather than
     * falling through to the generic persistence-error one.
     */
    public Component describeError(Throwable ex) {
        Throwable cause = unwrapCompletion(ex);
        return switch (cause) {
            case RealmNameTakenException e -> messages.get("error-realm-name-taken");
            case PlayerAlreadyInRealmException e -> messages.get("error-already-in-realm");
            case RealmNotFoundException e -> messages.get("error-realm-not-found");
            case PlayerNotInRealmException e -> messages.get("error-player-not-in-realm");
            case NotRealmLeaderException e -> messages.get("error-not-leader");
            case MissingPermissionException e -> messages.get("error-missing-permission");
            case NotInvitedException e -> messages.get("error-not-invited");
            case LeaderCannotLeaveException e -> messages.get("error-leader-cannot-leave");
            case ChunkAlreadyClaimedException e -> messages.get("error-chunk-already-claimed");
            case ClaimNotContiguousException e -> messages.get("error-claim-not-contiguous");
            case InsufficientTreasuryFundsException e -> messages.get("error-insufficient-treasury-funds");
            case InsufficientFundsForRealmCreationException e -> messages.get(
                    "error-insufficient-funds-for-realm-creation",
                    Placeholder.unparsed("fee", Money.ofCents(e.feeCents()).toString()));
            case CannotKickLeaderException e -> messages.get("error-cannot-kick-leader");
            case DatabaseUnavailableException e -> messages.get("error-database-unavailable");
            case RealmPersistenceException e -> messages.get("error-persistence");
            case EconomyPersistenceException e -> messages.get("error-persistence");
            default -> messages.get("error-unexpected");
        };
    }

    private static Throwable unwrapCompletion(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }
}
