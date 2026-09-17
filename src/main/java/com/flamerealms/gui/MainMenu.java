package com.flamerealms.gui;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.command.realm.RealmActions;
import com.flamerealms.domain.Realm;
import com.flamerealms.service.RealmService;

import net.kyori.adventure.text.Component;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The realm-aware chest-GUI front door: one button per key present in
 * {@link GuiConfig#mainMenu()}'s {@link MainMenuConfig#items()}, each wired
 * to the same {@link RealmActions} method its {@code /realm} command
 * counterpart calls. A key an admin deleted from {@code gui.yml}'s
 * {@code main-menu.items} is simply absent from that map and therefore never
 * rendered here — never synthesized back in, matching {@link GuiConfig}'s
 * own contract.
 *
 * <p>Unlike {@link ConfirmMenu}/{@link AmountMenu} (generic, reusable,
 * knowing nothing about realms), this class is the one place in {@code
 * com.flamerealms.gui} that IS realm-aware — it is what turns the generic
 * framework into the actual FlameRealms menu.
 *
 * <p>Every submenu this class opens (a {@link PlayerSelectorMenu}, a
 * {@link RealmSelectorMenu}, a {@link ConfirmMenu}, an {@link AmountMenu}) is
 * opened through {@link GuiManager#open}, never {@code
 * Player#openInventory} directly, so {@link GuiManager}'s open-GUI tracking
 * (and therefore its anti-dupe click cancellation) never falls out of sync
 * with what's actually on the player's screen.
 *
 * <p>A fresh {@link MainMenu} instance (and inventory) is built on every
 * {@link #open} call, the same one-instance-per-open convention every other
 * {@link ChestGui} in this package follows — nothing here is reused across
 * players or across repeat opens by the same player.
 */
public final class MainMenu extends ChestGui {

    private MainMenu(Component title, int size) {
        super(title, size);
    }

    /**
     * The entry point a command (e.g. a future {@code /realm menu}) calls to
     * show this menu to {@code player}. Builds a fresh {@link MainMenu} and
     * routes its display through {@code guiManager} rather than opening it
     * directly.
     *
     * @param player           who the menu is being opened for
     * @param config           the loaded {@code gui.yml} binding
     * @param actions          the business logic every button ultimately delegates to
     * @param realmService     read directly (rather than through {@code actions}) by the
     *                         kick/setrank/transfer buttons, which need a realm id up front to
     *                         open a {@link MemberSelectorMenu}/{@link RankSelectorMenu} — see
     *                         {@link RealmActions#requireRealm} for why that one check is exposed
     *                         standalone instead of duplicated here
     * @param realmCache       read by the "join" button to list realms the viewer isn't already in
     * @param chatInputService used by the "create" button (realm name) and, transitively, by
     *                         {@link AmountMenu}'s custom-amount button for "deposit"/"withdraw"
     * @param plugin           passed through to {@link AmountMenu#create}, which needs it to
     *                         schedule the main-thread hop after an async chat reply, and to
     *                         {@link MemberSelectorMenu#open}/{@link RankSelectorMenu#open}, which
     *                         need it for their own off-thread resolution steps
     * @param guiManager       every open (this menu and any submenu it opens) is routed through
     *                         this instance, per this class's own contract above
     */
    public static void open(
            Player player,
            GuiConfig config,
            RealmActions actions,
            RealmService realmService,
            RealmCache realmCache,
            ChatInputService chatInputService,
            Plugin plugin,
            GuiManager guiManager
    ) {
        guiManager.open(player, build(player, config, actions, realmService, realmCache, chatInputService, plugin, guiManager));
    }

    private static MainMenu build(
            Player player,
            GuiConfig config,
            RealmActions actions,
            RealmService realmService,
            RealmCache realmCache,
            ChatInputService chatInputService,
            Plugin plugin,
            GuiManager guiManager
    ) {
        MainMenuConfig menuConfig = config.mainMenu();
        MainMenu menu = new MainMenu(render(menuConfig.title()), menuConfig.size());

        for (Map.Entry<String, GuiItemConfig> entry : menuConfig.items().entrySet()) {
            GuiItemConfig itemConfig = entry.getValue();
            ItemStack item = menu.buildItem(itemConfig);
            Runnable handler = handlerFor(entry.getKey(), player, config, actions, realmService, realmCache,
                    chatInputService, plugin, guiManager);
            menu.setItem(itemConfig.slot(), item, handler);
        }

        menu.fillRemaining(menuConfig.fillerMaterial());
        return menu;
    }

    /**
     * Maps one {@code main-menu.items} key to the {@link Runnable} its click
     * handler runs. An unrecognized key (one an admin invented in {@code
     * gui.yml} that this class doesn't know about) falls back to {@code
     * null} — a purely decorative item, per {@link ChestGui#setItem}'s own
     * contract for a {@code null} handler — rather than throwing or silently
     * assuming an action.
     */
    private static Runnable handlerFor(
            String key,
            Player player,
            GuiConfig config,
            RealmActions actions,
            RealmService realmService,
            RealmCache realmCache,
            ChatInputService chatInputService,
            Plugin plugin,
            GuiManager guiManager
    ) {
        Runnable backToMainMenu = () -> guiManager.open(player,
                build(player, config, actions, realmService, realmCache, chatInputService, plugin, guiManager));

        return switch (key) {
            case "info" -> () -> {
                player.closeInventory();
                actions.showInfoSelf(player);
            };

            case "create" -> () -> {
                player.closeInventory();
                player.sendMessage(render("<yellow>Type your new realm's name in chat.</yellow>"));
                chatInputService.prompt(player, name -> actions.createRealm(player, name));
            };

            case "invite" -> () -> {
                player.closeInventory();
                guiManager.open(player, PlayerSelectorMenu.create(
                        config, player, "Invite a Player",
                        target -> actions.invite(player, target),
                        backToMainMenu));
            };

            case "join" -> () -> {
                player.closeInventory();
                guiManager.open(player, RealmSelectorMenu.create(
                        config, realmCache, player, "Join a Realm",
                        realm -> actions.join(player, realm),
                        backToMainMenu));
            };

            case "leave" -> () -> {
                player.closeInventory();
                guiManager.open(player, ConfirmMenu.create(
                        config,
                        () -> {
                            player.closeInventory();
                            actions.leave(player);
                        },
                        player::closeInventory));
            };

            case "disband" -> () -> {
                player.closeInventory();
                guiManager.open(player, ConfirmMenu.create(
                        config,
                        () -> {
                            player.closeInventory();
                            actions.disband(player);
                        },
                        player::closeInventory));
            };

            case "balance" -> () -> {
                player.closeInventory();
                actions.showBalance(player);
            };

            case "deposit" -> () -> {
                player.closeInventory();
                guiManager.open(player, AmountMenu.create(
                        plugin, config, chatInputService, player,
                        amount -> actions.deposit(player, amount),
                        player::closeInventory));
            };

            case "withdraw" -> () -> {
                player.closeInventory();
                guiManager.open(player, AmountMenu.create(
                        plugin, config, chatInputService, player,
                        amount -> actions.withdraw(player, amount),
                        player::closeInventory));
            };

            case "claim" -> () -> {
                player.closeInventory();
                actions.previewClaim(player);
                guiManager.open(player, ConfirmMenu.create(
                        config,
                        List.of("<gray>Alternative to typing /realm claim confirm</gray>"),
                        () -> {
                            player.closeInventory();
                            actions.confirmClaim(player);
                        },
                        player::closeInventory));
            };

            case "unclaim" -> () -> {
                player.closeInventory();
                actions.unclaim(player);
            };

            case "map" -> () -> {
                player.closeInventory();
                actions.showMap(player);
            };

            case "borders" -> () -> {
                player.closeInventory();
                actions.toggleBorders(player);
            };

            case "kick" -> () -> {
                player.closeInventory();
                Optional<Realm> realmOpt = actions.requireRealm(player);
                if (realmOpt.isEmpty()) {
                    return;
                }
                Realm realm = realmOpt.get();

                // A leader can never be kicked (RealmService.kick's own
                // guard) — filtered out here purely for a better UX than
                // letting the click fail with an error afterward; the
                // service layer is still the actual authority regardless.
                Set<UUID> excluded = new HashSet<>();
                excluded.add(player.getUniqueId());
                excluded.add(realm.leaderUuid());

                MemberSelectorMenu.open(plugin, config, guiManager, realmService, player, realm.id(),
                        "Kick a Member", excluded,
                        member -> actions.kick(player, member.uuid(), member.displayName()),
                        backToMainMenu);
            };

            case "setrank" -> () -> {
                player.closeInventory();
                Optional<Realm> realmOpt = actions.requireRealm(player);
                if (realmOpt.isEmpty()) {
                    return;
                }
                long realmId = realmOpt.get().id();

                MemberSelectorMenu.open(plugin, config, guiManager, realmService, player, realmId,
                        "Set a Member's Rank", Set.of(player.getUniqueId()),
                        member -> RankSelectorMenu.open(plugin, config, guiManager, realmService, player, realmId,
                                "Choose a Rank",
                                rank -> actions.setRank(player, member.uuid(), member.displayName(), rank.name()),
                                backToMainMenu),
                        backToMainMenu);
            };

            case "transfer" -> () -> {
                player.closeInventory();
                Optional<Realm> realmOpt = actions.requireRealm(player);
                if (realmOpt.isEmpty()) {
                    return;
                }
                long realmId = realmOpt.get().id();

                MemberSelectorMenu.open(plugin, config, guiManager, realmService, player, realmId,
                        "Transfer Leadership", Set.of(player.getUniqueId()),
                        member -> guiManager.open(player, ConfirmMenu.create(
                                config,
                                List.of("<gray>Transfer leadership to <yellow>" + member.displayName()
                                        + "</yellow>?</gray>",
                                        "<gray>This cannot be undone by you alone.</gray>"),
                                () -> {
                                    player.closeInventory();
                                    actions.transfer(player, member.uuid(), member.displayName());
                                },
                                player::closeInventory)),
                        backToMainMenu);
            };

            case "close" -> player::closeInventory;

            default -> null;
        };
    }
}
