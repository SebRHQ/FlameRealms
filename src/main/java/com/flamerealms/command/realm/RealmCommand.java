package com.flamerealms.command.realm;

import com.flamerealms.FlameRealmsPlugin;
import com.flamerealms.config.Messages;
import com.flamerealms.domain.Money;
import com.flamerealms.domain.Realm;
import com.flamerealms.service.RealmService;
import com.flamerealms.util.InvalidAmountException;
import com.flamerealms.util.MoneyParsing;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@code /realm} — the Brigadier command-tree wiring and argument parsing
 * over {@link RealmActions}, which holds all the actual business logic
 * (permission checks, calling the domain services, building/sending every
 * message, the async future-continuation/{@code runSync} pattern,
 * exception-to-message mapping, and the claim preview/confirm bookkeeping).
 * Built on Paper's native Brigadier integration ({@link Commands}/{@link
 * CommandSourceStack}) — no third-party command framework is used or needed.
 *
 * <p>This class's own job is narrow: extract Brigadier arguments, resolve
 * anything that needs a Bukkit lookup purely to validate that an argument
 * refers to something real (an online player for {@code invite}, an
 * existing realm for {@code join}/{@code info}), and then delegate straight
 * into the corresponding {@link RealmActions} method — which does the rest,
 * including sending every success/failure message itself. {@link
 * RealmActions} has no Brigadier types anywhere in it, so a future chest-GUI
 * front end can call the exact same methods (given an already-resolved
 * {@code Player}/{@code Realm}/{@code Money}) and get identical behavior.
 *
 * <p>{@code deposit}/{@code withdraw} parse their {@code <amount>} argument
 * with {@link MoneyParsing#parseAmountToCents}, which uses {@link
 * java.math.BigDecimal} — never {@code Double.parseDouble} — per this
 * project's fixed rule that money is never floating point; that's genuinely
 * argument validation (turning a raw string into a {@link Money}), so it
 * stays here rather than moving into {@link RealmActions}, which takes an
 * already-parsed {@link Money}.
 */
public final class RealmCommand {

    private final FlameRealmsPlugin plugin;
    private final RealmService realmService;
    private final Messages messages;
    private final RealmActions actions;
    private final Consumer<Player> openMainMenu;

    public RealmCommand(
            FlameRealmsPlugin plugin,
            RealmService realmService,
            Messages messages,
            RealmActions actions,
            Consumer<Player> openMainMenu
    ) {
        this.plugin = plugin;
        this.realmService = realmService;
        this.messages = messages;
        this.actions = actions;
        this.openMainMenu = openMainMenu;
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
                .then(buildKick())
                .then(buildSetRank())
                .then(buildTransfer())
                .then(buildMenu())
                .then(buildReload())
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
        actions.createRealm(player, name);
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
        actions.showInfoSelf(player);
        return Command.SINGLE_SUCCESS;
    }

    private int executeInfoNamed(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Audience audience = ctx.getSource().getSender();
        // getByName() is a synchronous RealmCache read (via RealmService) —
        // no future involved, safe to call and act on directly from this
        // (main) thread.
        actions.showInfo(audience, realmService.getByName(name), name);
        return Command.SINGLE_SUCCESS;
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

        // Resolving the invited player by name, rejecting an offline target
        // and rejecting a self-invite are argument-validation concerns that
        // only make sense for a raw command argument — a GUI player-selector
        // menu only ever lists online players other than the viewer, so
        // RealmActions.invite(...) does not need to repeat these checks.
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

        actions.invite(inviter, target);
        return Command.SINGLE_SUCCESS;
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

        actions.join(player, realmOpt.get());
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
        actions.leave(player);
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
        actions.disband(player);
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
        actions.showBalance(player);
        return Command.SINGLE_SUCCESS;
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
            cents = MoneyParsing.parseAmountToCents(rawAmount);
        } catch (InvalidAmountException e) {
            player.sendMessage(messages.get(e.getMessageKey()));
            return 0;
        }

        actions.deposit(player, Money.ofCents(cents));
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
            cents = MoneyParsing.parseAmountToCents(rawAmount);
        } catch (InvalidAmountException e) {
            player.sendMessage(messages.get(e.getMessageKey()));
            return 0;
        }

        actions.withdraw(player, Money.ofCents(cents));
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm claim / /realm claim confirm ---------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildClaim() {
        return Commands.literal("claim")
                .requires(hasPermission("flamerealms.command.claim"))
                .executes(this::executeClaimPreview)
                .then(Commands.literal("confirm")
                        .executes(this::executeClaimConfirm));
    }

    private int executeClaimPreview(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        actions.previewClaim(player);
        return Command.SINGLE_SUCCESS;
    }

    private int executeClaimConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        actions.confirmClaim(player);
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm unclaim -------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildUnclaim() {
        return Commands.literal("unclaim")
                .requires(hasPermission("flamerealms.command.unclaim"))
                .executes(this::executeUnclaim);
    }

    private int executeUnclaim(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        actions.unclaim(player);
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm map -------------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildMap() {
        return Commands.literal("map")
                .requires(hasPermission("flamerealms.command.map"))
                .executes(this::executeMap);
    }

    private int executeMap(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        actions.showMap(player);
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm borders -----------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildBorders() {
        return Commands.literal("borders")
                .requires(hasPermission("flamerealms.command.borders"))
                .executes(this::executeBorders);
    }

    private int executeBorders(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        actions.toggleBorders(player);
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm kick <player> -----------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildKick() {
        return Commands.literal("kick")
                .requires(hasPermission("flamerealms.command.kick"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(this::executeKick));
    }

    private int executeKick(CommandContext<CommandSourceStack> ctx) {
        Player actor = requirePlayer(ctx.getSource());
        if (actor == null) {
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        resolveOfflineTarget(actor, targetName, (targetUuid, targetDisplayName) ->
                actions.kick(actor, targetUuid, targetDisplayName));
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm setrank <player> <rank> --------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildSetRank() {
        return Commands.literal("setrank")
                .requires(hasPermission("flamerealms.command.setrank"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("rank", StringArgumentType.word())
                                .executes(this::executeSetRank)));
    }

    private int executeSetRank(CommandContext<CommandSourceStack> ctx) {
        Player actor = requirePlayer(ctx.getSource());
        if (actor == null) {
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        String rankName = StringArgumentType.getString(ctx, "rank");
        resolveOfflineTarget(actor, targetName, (targetUuid, targetDisplayName) ->
                actions.setRank(actor, targetUuid, targetDisplayName, rankName));
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm transfer <player> --------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildTransfer() {
        return Commands.literal("transfer")
                .requires(hasPermission("flamerealms.command.transfer"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(this::executeTransfer));
    }

    private int executeTransfer(CommandContext<CommandSourceStack> ctx) {
        Player currentLeader = requirePlayer(ctx.getSource());
        if (currentLeader == null) {
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        resolveOfflineTarget(currentLeader, targetName, (targetUuid, targetDisplayName) ->
                actions.transfer(currentLeader, targetUuid, targetDisplayName));
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm menu -------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildMenu() {
        return Commands.literal("menu")
                .requires(hasPermission("flamerealms.command.menu"))
                .executes(this::executeMenu);
    }

    private int executeMenu(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        openMainMenu.accept(player);
        return Command.SINGLE_SUCCESS;
    }

    // -- /realm reload --------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> buildReload() {
        // The reserved admin-only node, not a new one — see plugin.yml.
        return Commands.literal("reload")
                .requires(hasPermission("flamerealms.admin"))
                .executes(this::executeReload);
    }

    /**
     * Reloads {@code messages.yml} only. This is a local file read with no
     * database or Bukkit-API touch, unlike every other command in this
     * class, so it runs synchronously right here rather than through {@link
     * RealmActions}. Deliberately does NOT reload {@code pricing.yml}/
     * {@code gui.yml}/{@code config.yml} in this pass — see {@link
     * Messages#reload()}'s own Javadoc for why that is a documented scope-
     * down rather than an oversight. The confirmation is a locally-built
     * {@link Component}, not routed through {@link Messages#get}, since if
     * messages.yml itself is what's broken, the confirmation shouldn't
     * depend on it having reloaded successfully.
     */
    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        messages.reload();
        sender.sendMessage(Component.text(
                "FlameRealms messages.yml reloaded.", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    // -- Shared helpers -----------------------------------------------------

    /**
     * Resolves {@code targetName} to a {@link UUID} plus display name the
     * offline-tolerant way — {@code kick}/{@code setrank}/{@code transfer}
     * all act on a realm member who might currently be offline, unlike
     * {@code invite}'s online-only target. {@link Bukkit#getOfflinePlayer(String)}
     * falls back to a synchronous usercache/playerdata disk read whenever the
     * name isn't already resident in the server's in-memory profile cache, so
     * — same fix already applied to {@code /realm info}'s leader-name lookup
     * in {@link RealmActions#showInfo} — it is dispatched off-thread via
     * {@link Bukkit#getScheduler()}{@code .runTaskAsynchronously(...)} and
     * hopped back to the main thread via {@link #runSync(Runnable)} before
     * {@code onResolved} (which does the actual, main-thread-only {@code
     * actions.xxx(...)} call) ever runs. If this server has never seen a
     * player under that name, {@code onResolved} is never called at all —
     * {@code actor} gets a "player not found" message instead.
     */
    private void resolveOfflineTarget(Player actor, String targetName, TargetResolvedAction onResolved) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            boolean everSeen = offline.hasPlayedBefore() || offline.isOnline();
            UUID targetUuid = everSeen ? offline.getUniqueId() : null;
            String targetDisplayName = offline.getName();

            runSync(() -> {
                if (targetUuid == null || targetDisplayName == null) {
                    actor.sendMessage(messages.get(
                            "player-not-found", Placeholder.unparsed("name", targetName)));
                    return;
                }
                onResolved.accept(targetUuid, targetDisplayName);
            });
        });
    }

    /** Callback for {@link #resolveOfflineTarget(Player, String, TargetResolvedAction)}. */
    @FunctionalInterface
    private interface TargetResolvedAction {
        void accept(UUID targetUuid, String targetDisplayName);
    }


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

    /**
     * Hops back onto the main thread. Brigadier/{@code CommandSourceStack}-
     * specific commands themselves never need this any more (every future
     * continuation now lives inside {@link RealmActions}, which has its own
     * copy of this same helper) — kept here only in case a future
     * Brigadier-side addition to this class ever needs it directly.
     */
    private void runSync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
