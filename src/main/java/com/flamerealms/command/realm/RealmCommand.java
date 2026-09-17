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
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.persistence.dao.RealmRankDao;
import com.flamerealms.service.ClaimService;
import com.flamerealms.service.EconomyService;
import com.flamerealms.service.RealmService;
import com.flamerealms.service.TreasuryService;
import com.flamerealms.service.exception.ChunkAlreadyClaimedException;
import com.flamerealms.service.exception.ClaimNotContiguousException;
import com.flamerealms.service.exception.EconomyPersistenceException;
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
import com.flamerealms.service.exception.RealmServiceException;
import com.flamerealms.visualization.ClaimVisualizationService;
import com.flamerealms.visualization.TerritoryMapService;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * {@code /realm} — the M1 command surface over {@link RealmService}: create,
 * info, invite, join, leave, disband. Built on Paper's native Brigadier integration
 * ({@link Commands}/{@link CommandSourceStack}) — no third-party command
 * framework is used or needed.
 *
 * <p><b>Never blocks the main thread.</b> {@link RealmService#createRealm},
 * {@link RealmService#join} and {@link RealmService#leave} each dispatch to
 * {@code AsyncDatabaseExecutor} under the hood and return a
 * {@code CompletableFuture}. Every subcommand backed by one of those calls
 * returns from its Brigadier {@code executes(...)} callback immediately
 * after registering a {@code thenAccept}/{@code exceptionally} continuation
 * — it never calls {@code .join()}/{@code .get()}. Those continuations run
 * on the async executor's worker thread, so before either one touches the
 * Bukkit API (sending a message, reading online-player state) it hops back
 * with {@code Bukkit.getScheduler().runTask(plugin, ...)}.
 *
 * <p>{@link RealmService#getByPlayer} and {@link RealmService#getByName}
 * are plain, synchronous {@code RealmCache} reads (used by {@code info}),
 * and {@link RealmService#invite} is synchronous too (in-memory only in
 * M1) — both are safe to call directly from the command thread. {@code
 * info}'s one asynchronous leg is resolving the realm leader's display name
 * via {@code Bukkit.getOfflinePlayer(UUID)}, which is dispatched with
 * {@code runTaskAsynchronously(...)} since it can fall back to a blocking
 * usercache/playerdata disk read; see {@code sendRealmInfo}. {@code
 * invite}'s one asynchronous leg is this command's own permission check
 * (resolving the inviter's rank from {@code realm_members}/{@code
 * realm_ranks}, since {@code RealmService} deliberately performs none — see
 * {@code RealmServiceImpl}'s class Javadoc); that check is dispatched
 * through {@link AsyncDatabaseExecutor} the same way and its result is also
 * only ever applied back on the main thread.
 *
 * <p><b>Economy subcommands.</b> {@code balance}, {@code deposit} and
 * {@code withdraw} follow the exact same never-block-the-main-thread shape
 * against {@link EconomyService}/{@link TreasuryService}: every call
 * returns a {@code CompletableFuture}, this class never calls {@code
 * .join()}/{@code .get()} on one, and every continuation hops back with
 * {@code Bukkit.getScheduler().runTask(plugin, ...)} before it sends any
 * message. {@code balance} fires its personal-wallet and (if applicable)
 * realm-treasury reads concurrently and combines them with {@code
 * thenCombine} rather than waiting on one before starting the other, since
 * they touch no shared mutable state. {@code deposit}/{@code withdraw}
 * parse their {@code <amount>} argument with {@link BigDecimal} — never
 * {@code Double.parseDouble}, per this project's fixed rule that money is
 * never floating point — synchronously, before any dispatch, rejecting a
 * non-positive amount or one with more than two decimal places outright
 * rather than silently rounding it.
 *
 * <p><b>Claim subcommands.</b> {@code claim}, {@code claim confirm}, {@code
 * unclaim} and {@code map} follow the same rules against {@link
 * ClaimService}/{@link ClaimVisualizationService}/{@link TerritoryMapService}.
 * {@code claim} and {@code unclaim} both resolve the executing player's
 * current chunk via {@code player.getLocation()}'s world/chunk X/Z —
 * synchronously, on the main thread; this is ordinary Bukkit API, not a
 * database call. {@code claim} previews a purchase without making one: it
 * reads {@code realmId}'s current claim count from {@link RealmCache}
 * (synchronous, safe on this thread — same as every other {@code RealmCache}
 * read in this class) and feeds it to {@link PricingConfig#purchasePriceCents},
 * mirroring the exact "existing count + 1" lookup {@code ClaimServiceImpl}
 * itself uses inside the transaction that will actually charge for it; that
 * transaction re-checks the count for real, so a purchase confirmed after the
 * realm's claim count changed elsewhere can still be charged a different
 * price than what was previewed. The preview is stored in {@link
 * #pendingClaims}, a {@code ConcurrentHashMap<UUID, PendingClaim>} keyed by
 * player, each entry carrying its own expiry that is checked against (never
 * trusted indefinitely) rather than persisted anywhere. {@code claim
 * confirm} always removes its player's entry — successful confirm, failed
 * confirm, or expired preview alike — so a failed or stale confirm can never
 * be retried without the player running {@code claim} again for a fresh
 * preview. {@code unclaim} needs no such preview/confirm step (see {@link
 * #buildUnclaim()}). {@code map} is fully synchronous and does no database
 * I/O at all: {@link TerritoryMapService#renderMap} only reads {@code
 * player}'s live position and {@link RealmCache}.
 */
public final class RealmCommand {

    private static final BigDecimal CENTS_PER_UNIT = BigDecimal.valueOf(100);

    /**
     * How long a {@code claim} preview remains confirmable via {@code claim
     * confirm} before it must be rejected and a fresh preview required. Not
     * (yet) exposed as a server-admin config value — this is a short,
     * implementation-level UX window, not a tunable pricing/gameplay
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
    private final AsyncDatabaseExecutor asyncDatabaseExecutor;
    private final RealmMemberDao realmMemberDao;
    private final RealmRankDao realmRankDao;
    private final RealmCache realmCache;
    private final PricingConfig pricingConfig;
    private final Messages messages;

    /**
     * A given player has at most one outstanding claim preview at a time —
     * previewing a new chunk (calling {@code claim} again) simply overwrites
     * it. Never read/written from anywhere but the main thread (every
     * subcommand here only touches it from inside its {@code executes(...)}
     * callback or a {@code runSync(...)}-wrapped continuation), so a plain
     * {@link ConcurrentHashMap} is used purely for safety against any future
     * caller, not because concurrent access is actually expected.
     */
    private final ConcurrentHashMap<UUID, PendingClaim> pendingClaims = new ConcurrentHashMap<>();

    public RealmCommand(
            FlameRealmsPlugin plugin,
            RealmService realmService,
            EconomyService economyService,
            TreasuryService treasuryService,
            ClaimService claimService,
            ClaimVisualizationService claimVisualizationService,
            TerritoryMapService territoryMapService,
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
        this.asyncDatabaseExecutor = asyncDatabaseExecutor;
        this.realmMemberDao = realmMemberDao;
        this.realmRankDao = realmRankDao;
        this.realmCache = realmCache;
        this.pricingConfig = pricingConfig;
        this.messages = messages;
    }

    /** Builds the full {@code /realm} command tree. */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("realm")
                .executes(this::executeUsage)
                .then(buildCreate())
                .then(buildInfo())
                .then(buildInvite())
                .then(buildJoin())
                .then(buildLeave())
                .then(buildDisband())
                .then(buildBalance())
                .then(buildDeposit())
                .then(buildWithdraw())
                .then(buildClaim())
                .then(buildUnclaim())
                .then(buildMap())
                .then(buildBorders())
                .build();
    }

    private int executeUsage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(messages.get("usage"));
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm create <name> --------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildCreate() {
        return Commands.literal("create")
                .requires(hasPermission("flamerealms.command.create"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(this::executeCreate));
    }

    private int executeCreate(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        UUID leaderId = player.getUniqueId();

        // Non-blocking: createRealm() dispatches through AsyncDatabaseExecutor
        // and returns a CompletableFuture<Realm>. We return from this
        // executes() call right away; the continuation below runs later, on
        // the DB worker thread, and hops back to the main thread via
        // runSync(...) before it ever touches player.sendMessage(...).
        realmService.createRealm(leaderId, name)
                .thenAccept(realm -> runSync(() -> player.sendMessage(messages.get(
                        "realm-created", Placeholder.unparsed("name", realm.displayName())))))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    // -- /realm info [name] ------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildInfo() {
        return Commands.literal("info")
                .requires(hasPermission("flamerealms.command.info"))
                .executes(this::executeInfoSelf)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(this::executeInfoNamed));
    }

    private int executeInfoSelf(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        // getByPlayer() is a synchronous RealmCache read — no future involved,
        // safe to call and act on directly from this (main) thread.
        sendRealmInfo(player, realmService.getByPlayer(player.getUniqueId()), null);
        return Command.SINGLE_SUCCESS;
    }

    private int executeInfoNamed(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Audience audience = ctx.getSource().getSender();
        // getByName() is likewise a synchronous RealmCache read.
        sendRealmInfo(audience, realmService.getByName(name), name);
        return Command.SINGLE_SUCCESS;
    }

    private void sendRealmInfo(Audience audience, Optional<Realm> realmOpt, String requestedName) {
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

    // -- /realm invite <player> -------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildInvite() {
        return Commands.literal("invite")
                .requires(hasPermission("flamerealms.command.invite"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(this::executeInvite));
    }

    private int executeInvite(CommandContext<CommandSourceStack> ctx) {
        Player inviter = requirePlayer(ctx.getSource());
        if (inviter == null) {
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            inviter.sendMessage(messages.get(
                    "invite-target-offline", Placeholder.unparsed("name", targetName)));
            return 0;
        }
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            inviter.sendMessage(messages.get("invite-self"));
            return 0;
        }

        Optional<Realm> realmOpt = realmService.getByPlayer(inviter.getUniqueId());
        if (realmOpt.isEmpty()) {
            inviter.sendMessage(messages.get("not-in-realm"));
            return 0;
        }

        Realm realm = realmOpt.get();
        long realmId = realm.id();
        UUID inviterId = inviter.getUniqueId();
        UUID targetId = target.getUniqueId();

        // RealmService.invite() itself is synchronous and does NO permission
        // check on the inviter (see RealmServiceImpl's class Javadoc) — that
        // enforcement belongs here. Resolving it needs realm_members/
        // realm_ranks, which RealmCache does not hold, so this one leg is
        // async: dispatched through AsyncDatabaseExecutor, never blocked on.
        // We return from executes() immediately; the continuation applies its
        // result — and only then calls the (synchronous) invite() and sends
        // any message — back on the main thread via runSync(...).
        hasInvitePermission(realmId, inviterId)
                .thenAccept(allowed -> runSync(() -> {
                    if (!allowed) {
                        inviter.sendMessage(messages.get("invite-no-permission"));
                        return;
                    }
                    try {
                        realmService.invite(realmId, inviterId, targetId);
                    } catch (RealmServiceException e) {
                        inviter.sendMessage(describeError(e));
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

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Resolves whether {@code actor} currently holds a rank with
     * {@link RealmPermission#INVITE} inside {@code realmId}. Dispatched
     * through {@link AsyncDatabaseExecutor} since it reads {@code
     * realm_members}/{@code realm_ranks} directly — neither is cached by
     * {@code RealmCache}.
     */
    private java.util.concurrent.CompletableFuture<Boolean> hasInvitePermission(long realmId, UUID actor) {
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

    // -- /realm join <name> ------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildJoin() {
        return Commands.literal("join")
                .requires(hasPermission("flamerealms.command.join"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(this::executeJoin));
    }

    private int executeJoin(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        Optional<Realm> realmOpt = realmService.getByName(name);
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("realm-not-found-named", Placeholder.unparsed("name", name)));
            return 0;
        }
        Realm realm = realmOpt.get();

        // Non-blocking: join() returns a CompletableFuture<Void>. We return
        // from executes() right away; the continuation hops back to the main
        // thread via runSync(...) before sending any message.
        realmService.join(player.getUniqueId(), realm.id())
                .thenAccept(v -> runSync(() -> player.sendMessage(messages.get(
                        "joined-realm", Placeholder.unparsed("name", realm.displayName())))))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    // -- /realm leave -------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildLeave() {
        return Commands.literal("leave")
                .requires(hasPermission("flamerealms.command.leave"))
                .executes(this::executeLeave);
    }

    private int executeLeave(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        // Non-blocking: leave() returns a CompletableFuture<Void>. Same
        // pattern as create/join — return immediately, apply the result
        // (success or failure) back on the main thread via runSync(...).
        realmService.leave(player.getUniqueId())
                .thenAccept(v -> runSync(() -> player.sendMessage(messages.get("left-realm"))))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    // -- /realm disband -------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildDisband() {
        return Commands.literal("disband")
                .requires(hasPermission("flamerealms.command.disband"))
                .executes(this::executeDisband);
    }

    private int executeDisband(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return 0;
        }

        long realmId = realmOpt.get().id();
        String realmName = realmOpt.get().displayName();

        // Non-blocking: disbandRealm() dispatches through AsyncDatabaseExecutor
        // and returns a CompletableFuture<Void>. Same pattern as leave/join —
        // return immediately, apply the result back on the main thread.
        // RealmService itself rejects the call with NotRealmLeaderException
        // (see describeError) if the caller isn't that realm's leader.
        realmService.disbandRealm(realmId, player.getUniqueId())
                .thenAccept(v -> runSync(() -> player.sendMessage(messages.get(
                        "realm-disbanded", Placeholder.unparsed("name", realmName)))))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    // -- /realm balance ------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildBalance() {
        return Commands.literal("balance")
                .requires(hasPermission("flamerealms.command.balance"))
                .executes(this::executeBalance);
    }

    private int executeBalance(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

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
            personalFuture
                    .thenAccept(personal -> runSync(() -> sendBalance(player, personal, null)))
                    .exceptionally(ex -> {
                        runSync(() -> player.sendMessage(describeError(ex)));
                        return null;
                    });
            return Command.SINGLE_SUCCESS;
        }

        long realmId = realmOpt.get().id();
        CompletableFuture<Money> treasuryFuture = treasuryService.balanceOf(realmId);

        personalFuture.thenCombine(treasuryFuture, Balances::new)
                .thenAccept(balances -> runSync(() -> sendBalance(player, balances.personal(), balances.treasury())))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });

        return Command.SINGLE_SUCCESS;
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

    // -- /realm deposit <amount> ----------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildDeposit() {
        return Commands.literal("deposit")
                .requires(hasPermission("flamerealms.command.deposit"))
                .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(this::executeDeposit));
    }

    private int executeDeposit(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        String rawAmount = StringArgumentType.getString(ctx, "amount");
        long cents;
        try {
            cents = parseAmountToCents(rawAmount);
        } catch (InvalidAmountException e) {
            player.sendMessage(messages.get(e.messageKey()));
            return 0;
        }

        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return 0;
        }

        long realmId = realmOpt.get().id();
        UUID playerId = player.getUniqueId();
        Money amount = Money.ofCents(cents);

        // Non-blocking: deposit() dispatches through AsyncDatabaseExecutor
        // and returns a CompletableFuture<Boolean>. Return from executes()
        // immediately; apply the result back on the main thread.
        treasuryService.deposit(realmId, playerId, amount)
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
                });

        return Command.SINGLE_SUCCESS;
    }

    // -- /realm withdraw <amount> ----------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildWithdraw() {
        return Commands.literal("withdraw")
                .requires(hasPermission("flamerealms.command.withdraw"))
                .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(this::executeWithdraw));
    }

    private int executeWithdraw(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        String rawAmount = StringArgumentType.getString(ctx, "amount");
        long cents;
        try {
            cents = parseAmountToCents(rawAmount);
        } catch (InvalidAmountException e) {
            player.sendMessage(messages.get(e.messageKey()));
            return 0;
        }

        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return 0;
        }

        long realmId = realmOpt.get().id();
        UUID playerId = player.getUniqueId();
        Money amount = Money.ofCents(cents);

        // Non-blocking, same shape as deposit(): withdraw() returns a
        // CompletableFuture<Boolean>, applied back on the main thread. A
        // failed permission check fails the future (see describeError()'s
        // MissingPermissionException case); an ordinary insufficient-funds
        // outcome is a plain `false`, distinguished from that here.
        treasuryService.withdraw(realmId, playerId, amount)
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
                });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Parses a decimal dollar string (e.g. {@code "500"} or {@code "12.50"})
     * into whole cents using {@link BigDecimal} — never {@code
     * Double.parseDouble}/{@code float}, per this project's fixed rule that
     * money is never floating point. Rejects a non-positive or unparseable
     * amount, and rejects (rather than silently rounding) an amount with
     * more than two decimal places.
     *
     * @throws InvalidAmountException describing exactly what was wrong with {@code raw}
     */
    private long parseAmountToCents(String raw) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new InvalidAmountException("amount-invalid");
        }

        if (parsed.stripTrailingZeros().scale() > 2) {
            throw new InvalidAmountException("amount-too-many-decimals");
        }
        if (parsed.signum() <= 0) {
            throw new InvalidAmountException("amount-not-positive");
        }

        try {
            // scale() <= 2 was just verified, so this multiplication is
            // always an exact whole number of cents — RoundingMode.UNNECESSARY
            // documents that no actual rounding ever happens here.
            return parsed.multiply(CENTS_PER_UNIT).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidAmountException("amount-too-large");
        }
    }

    /**
     * Thrown synchronously by {@link #parseAmountToCents} for input a player
     * should fix and retry. Carries a {@code messages.yml} key rather than a
     * literal message, same as every other player-facing string here.
     */
    private static final class InvalidAmountException extends RuntimeException {
        private final String messageKey;

        InvalidAmountException(String messageKey) {
            this.messageKey = messageKey;
        }

        String messageKey() {
            return messageKey;
        }
    }

    // -- /realm claim / /realm claim confirm ---------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildClaim() {
        return Commands.literal("claim")
                .requires(hasPermission("flamerealms.command.claim"))
                .executes(this::executeClaimPreview)
                .then(Commands.literal("confirm")
                        .executes(this::executeClaimConfirm));
    }

    /**
     * {@code /realm claim} — previews claiming the player's current chunk:
     * computes what it would cost, shows a particle outline of the chunk via
     * {@link ClaimVisualizationService#previewChunk}, and stores a {@link
     * PendingClaim} for this player that {@code claim confirm} consumes.
     * Makes no database call and purchases nothing by itself.
     */
    private int executeClaimPreview(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return 0;
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

        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /realm claim confirm} — consumes this player's {@link
     * PendingClaim} (if any, and if not expired) and actually calls {@link
     * ClaimService#purchaseClaim}. The pending entry is removed up front,
     * before the future is even created, so a failed or expired confirm can
     * never be retried against a stale preview — the player must run {@code
     * claim} again for a fresh one.
     */
    private int executeClaimConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        UUID playerId = player.getUniqueId();
        PendingClaim pending = pendingClaims.remove(playerId);
        if (pending == null) {
            player.sendMessage(messages.get("claim-confirm-none"));
            return 0;
        }
        if (pending.isExpired()) {
            player.sendMessage(messages.get("claim-confirm-expired"));
            return 0;
        }

        // Non-blocking: purchaseClaim() dispatches through
        // AsyncDatabaseExecutor and returns a CompletableFuture<RealmClaim>.
        // Return from executes() immediately; apply the result back on the
        // main thread via runSync(...) before sending any message.
        claimService.purchaseClaim(pending.realmId(), playerId, pending.world(), pending.chunkX(), pending.chunkZ())
                .thenAccept(claim -> runSync(() -> player.sendMessage(messages.get(
                        "claim-confirm-success",
                        Placeholder.unparsed("price", Money.ofCents(claim.pricePaidCents()).toString())))))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    // -- /realm unclaim -------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildUnclaim() {
        return Commands.literal("unclaim")
                .requires(hasPermission("flamerealms.command.unclaim"))
                .executes(this::executeUnclaim);
    }

    /**
     * {@code /realm unclaim} — releases the player's current chunk from
     * their realm's territory, no preview/confirm step. Per this project's
     * M2 judgment, unclaiming is free (no refund) and immediate rather than
     * gated behind a confirmation the way a purchase is; see {@code
     * ClaimService#unclaimChunk}'s own contract for why no refund/connectivity
     * guard applies here.
     */
    private int executeUnclaim(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        Optional<Realm> realmOpt = realmService.getByPlayer(player.getUniqueId());
        if (realmOpt.isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return 0;
        }
        long realmId = realmOpt.get().id();
        UUID playerId = player.getUniqueId();

        // Synchronous, main-thread chunk read — same as claim's, NOT a
        // database call.
        String world = player.getWorld().getName();
        int chunkX = player.getLocation().getChunk().getX();
        int chunkZ = player.getLocation().getChunk().getZ();

        // Non-blocking: unclaimChunk() dispatches through
        // AsyncDatabaseExecutor and returns a CompletableFuture<Boolean>.
        // Same pattern as deposit/withdraw — apply the result back on the
        // main thread, distinguishing "nothing there to unclaim" (false)
        // from an exceptional failure.
        claimService.unclaimChunk(realmId, playerId, world, chunkX, chunkZ)
                .thenAccept(unclaimed -> runSync(() -> {
                    if (unclaimed) {
                        player.sendMessage(messages.get("unclaim-success"));
                    } else {
                        player.sendMessage(messages.get("unclaim-not-claimed"));
                    }
                }))
                .exceptionally(ex -> {
                    runSync(() -> player.sendMessage(describeError(ex)));
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    // -- /realm map -------------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildMap() {
        return Commands.literal("map")
                .requires(hasPermission("flamerealms.command.map"))
                .executes(this::executeMap);
    }

    /**
     * {@code /realm map} — sends {@link TerritoryMapService#renderMap}'s
     * result directly. Fully synchronous, main-thread-only, no database I/O
     * at all — no future/continuation involved, unlike every other
     * subcommand above.
     */
    private int executeMap(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        player.sendMessage(territoryMapService.renderMap(player));
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm borders -----------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildBorders() {
        return Commands.literal("borders")
                .requires(hasPermission("flamerealms.command.borders"))
                .executes(this::executeBorders);
    }

    /**
     * {@code /realm borders} — toggles a persistent particle outline of the
     * player's whole realm territory on/off. Requires the player be in a
     * realm to turn it ON; {@link ClaimVisualizationService#toggleBorders}
     * itself re-checks realm membership every pulse and auto-stops if it
     * changes, so no further state is tracked here. Fully synchronous,
     * main-thread-only, no database I/O — same shape as {@code map}.
     */
    private int executeBorders(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }

        if (realmService.getByPlayer(player.getUniqueId()).isEmpty()) {
            player.sendMessage(messages.get("not-in-realm"));
            return 0;
        }

        boolean nowOn = claimVisualizationService.toggleBorders(player);
        player.sendMessage(messages.get(nowOn ? "borders-enabled" : "borders-disabled"));
        return Command.SINGLE_SUCCESS;
    }

    // -- Shared helpers -----------------------------------------------------

    /**
     * A Brigadier {@code requires(...)} predicate gating a subcommand behind
     * a Bukkit permission node (see {@code plugin.yml}'s {@code permissions:}
     * block). A sender lacking the node never sees the subcommand accepted —
     * Brigadier reports it the same way as any other unknown command, no
     * custom message needed. Non-player senders (console, command blocks)
     * are also subject to this check like any other permissible.
     */
    private static Predicate<CommandSourceStack> hasPermission(String node) {
        return source -> source.getSender().hasPermission(node);
    }

    private Player requirePlayer(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(messages.get("only-players"));
        return null;
    }

    /** Hops back onto the main thread. Every Bukkit API touch inside a future continuation goes through this. */
    private void runSync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private Component describeError(Throwable ex) {
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
