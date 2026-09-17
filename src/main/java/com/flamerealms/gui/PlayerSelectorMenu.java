package com.flamerealms.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A generic-chrome (see {@link ListMenuConfig}) list of every online player
 * except the viewer, used by {@link MainMenu}'s "invite" button. Knows
 * nothing about realms/invites itself — {@code onSelect} decides what
 * clicking an entry actually does.
 *
 * <p><b>Simplifications, called out explicitly rather than left silent:</b>
 * <ul>
 *   <li>Pagination is out of scope for this pass — entries are capped at
 *       {@link #MAX_ENTRIES} (45, leaving room for the back button in a
 *       54-slot menu); any online player beyond that cap simply isn't
 *       listed.</li>
 *   <li>Each head uses {@link SkullMeta#setOwningPlayer(org.bukkit.OfflinePlayer)}
 *       for a real player-face texture. This turned out straightforward (a
 *       {@code PLAYER_HEAD} {@link ItemStack}'s {@link ItemMeta} is always a
 *       {@link SkullMeta} on Paper), so the plain-head fallback mentioned as
 *       a fallback option was not needed.</li>
 * </ul>
 */
public final class PlayerSelectorMenu extends ChestGui {

    private static final int MAX_ENTRIES = 45;

    private PlayerSelectorMenu(Component title, int size) {
        super(title, size);
    }

    /**
     * @param config   supplies the shared list-menu chrome ({@code list-menu} in {@code gui.yml})
     * @param viewer   the player this menu is being built for, excluded from the listing
     * @param title    plain text substituted for the {@code <list-title>} placeholder
     * @param onSelect invoked (after the viewer's inventory is already closed) with the clicked player
     * @param onBack   invoked when the back button is clicked; the inventory is NOT pre-closed for
     *                 this one, matching {@link ConfirmMenu}'s "caller decides" convention
     */
    public static PlayerSelectorMenu create(
            GuiConfig config, Player viewer, String title, Consumer<Player> onSelect, Runnable onBack) {
        ListMenuConfig menuConfig = config.listMenu();
        Component renderedTitle = render(menuConfig.title(), Placeholder.unparsed("list-title", title));
        PlayerSelectorMenu menu = new PlayerSelectorMenu(renderedTitle, menuConfig.size());

        List<Player> others = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(viewer.getUniqueId())) {
                others.add(online);
            }
        }

        if (others.isEmpty()) {
            ItemStack info = menu.buildItem(Material.BARRIER, render("<gray>No other players online</gray>"), List.of());
            menu.setItem(emptyStateSlot(menuConfig), info, null);
        } else {
            int slot = 0;
            int placed = 0;
            for (Player target : others) {
                if (placed >= MAX_ENTRIES) {
                    break;
                }
                while (slot < menuConfig.size() && slot == menuConfig.backSlot()) {
                    slot++;
                }
                if (slot >= menuConfig.size()) {
                    break;
                }
                menu.setItem(slot, buildHeadItem(menu, target), () -> {
                    viewer.closeInventory();
                    onSelect.accept(target);
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

    private static ItemStack buildHeadItem(PlayerSelectorMenu menu, Player target) {
        Component name = render("<yellow><name></yellow>", Placeholder.unparsed("name", target.getName()));
        ItemStack item = menu.buildItem(Material.PLAYER_HEAD, name, List.of());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(target);
            item.setItemMeta(skullMeta);
        }
        return item;
    }

    private static int emptyStateSlot(ListMenuConfig menuConfig) {
        int slot = menuConfig.size() / 2;
        return slot == menuConfig.backSlot() ? 0 : slot;
    }
}
