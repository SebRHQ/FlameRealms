package com.flamerealms.gui;

import com.flamerealms.domain.RealmRank;
import com.flamerealms.service.RealmService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A generic-chrome (see {@link ListMenuConfig}) list of a single realm's
 * ranks, used by {@link MainMenu}'s "setrank" flow after a {@link
 * MemberSelectorMenu} entry has been picked. Knows nothing about what
 * clicking a rank actually does — {@code onSelect} decides.
 *
 * <p>Ranks already come back from {@link RealmService#getRanks} ordered
 * highest-priority-first (see that method's own contract, a pass-through to
 * {@code RealmRankDao.findAllByRealm}), so this menu places them in that same
 * order with no re-sorting of its own.
 *
 * <p><b>Simplification, called out explicitly:</b> pagination is out of scope
 * for this pass, same as {@link PlayerSelectorMenu}/{@link RealmSelectorMenu}/
 * {@link MemberSelectorMenu} — entries are capped at {@link #MAX_ENTRIES} (45,
 * leaving room for the back button). In practice a realm's rank count is tiny
 * (three by default, per {@code RealmServiceImpl#createRealm}'s seeding), so
 * this cap is never expected to bind.
 *
 * <p>Unlike {@link MemberSelectorMenu}, resolving {@code realmId}'s ranks
 * touches no Bukkit API at all (no player-name lookups), so only one hop is
 * needed: {@link RealmService#getRanks}'s future completes off the main
 * thread (dispatched through {@code AsyncDatabaseExecutor}), and {@link #open}
 * hops back once via {@code Bukkit.getScheduler().runTask(...)} before
 * building/opening the menu.
 */
public final class RankSelectorMenu extends ChestGui {

    private static final int MAX_ENTRIES = 45;

    private RankSelectorMenu(Component title, int size) {
        super(title, size);
    }

    /**
     * @param plugin      needed to hop back onto the main thread once the ranks future completes
     * @param config      supplies the shared list-menu chrome ({@code list-menu} in {@code gui.yml})
     * @param guiManager  the resulting menu is opened through this, once built
     * @param realmService source of {@code realmId}'s current rank list
     * @param viewer      the player this menu is being built for
     * @param realmId     the realm whose ranks are being listed
     * @param title       plain text substituted for the {@code <list-title>} placeholder
     * @param onSelect    invoked (after the viewer's inventory is already closed) with the clicked rank
     * @param onBack      invoked when the back button is clicked
     */
    public static void open(
            Plugin plugin, GuiConfig config, GuiManager guiManager, RealmService realmService,
            Player viewer, long realmId, String title, Consumer<RealmRank> onSelect, Runnable onBack) {
        realmService.getRanks(realmId).whenComplete((ranks, error) -> {
            List<RealmRank> safeRanks;
            if (error != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load ranks for realm " + realmId + " to build a selector menu", error);
                safeRanks = List.of();
            } else {
                safeRanks = ranks;
            }

            Bukkit.getScheduler().runTask(plugin, () ->
                    guiManager.open(viewer, build(config, viewer, title, safeRanks, onSelect, onBack)));
        });
    }

    private static RankSelectorMenu build(
            GuiConfig config, Player viewer, String title, List<RealmRank> ranks,
            Consumer<RealmRank> onSelect, Runnable onBack) {
        ListMenuConfig menuConfig = config.listMenu();
        Component renderedTitle = render(menuConfig.title(), Placeholder.unparsed("list-title", title));
        RankSelectorMenu menu = new RankSelectorMenu(renderedTitle, menuConfig.size());

        if (ranks.isEmpty()) {
            ItemStack info = menu.buildItem(Material.BARRIER, render("<gray>No ranks found</gray>"), List.of());
            menu.setItem(emptyStateSlot(menuConfig), info, null);
        } else {
            int slot = 0;
            int placed = 0;
            for (RealmRank rank : ranks) {
                if (placed >= MAX_ENTRIES) {
                    break;
                }
                while (slot < menuConfig.size() && slot == menuConfig.backSlot()) {
                    slot++;
                }
                if (slot >= menuConfig.size()) {
                    break;
                }
                menu.setItem(slot, buildRankItem(menu, rank), () -> {
                    viewer.closeInventory();
                    onSelect.accept(rank);
                });
                slot++;
                placed++;
            }
        }

        ItemStack backItem = menu.buildItem(menuConfig.backMaterial(), menuConfig.backName(), List.of());
        menu.setItem(menuConfig.backSlot(), backItem, onBack);

        menu.fillRemaining(menuConfig.fillerMaterial());
        return menu;
    }

    private static ItemStack buildRankItem(RankSelectorMenu menu, RealmRank rank) {
        Component name = render("<gold><name></gold>", Placeholder.unparsed("name", rank.name()));
        List<Component> lore = List.of(
                render("<gray>Priority: <priority></gray>", Placeholder.unparsed("priority", String.valueOf(rank.priority()))));
        return menu.buildItem(Material.NAME_TAG, name, lore);
    }

    private static int emptyStateSlot(ListMenuConfig menuConfig) {
        int slot = menuConfig.size() / 2;
        return slot == menuConfig.backSlot() ? 0 : slot;
    }
}
