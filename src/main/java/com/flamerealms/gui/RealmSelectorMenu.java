package com.flamerealms.gui;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.domain.Realm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A generic-chrome (see {@link ListMenuConfig}) list of every realm in
 * {@link RealmCache#values()} except the one the viewer already belongs to
 * (if any), used by {@link MainMenu}'s "join" button. Knows nothing about
 * what clicking an entry actually does — {@code onSelect} decides.
 *
 * <p><b>Simplification, called out explicitly:</b> pagination is out of
 * scope for this pass, same as {@link PlayerSelectorMenu} — entries are
 * capped at {@link #MAX_ENTRIES} (45, leaving room for the back button).
 *
 * <p>Leader-name resolution uses {@code Bukkit.getOfflinePlayer(UUID)}
 * directly on the calling (main) thread, the same null-fallback pattern
 * {@code RealmActions.showInfo} uses — but unlike that method, this one does
 * NOT hop off-thread first. {@code RealmActions.showInfo} only ever resolves
 * one leader per call; this menu may resolve one per realm in the whole
 * server, so on a very large realm count with many offline leaders this
 * could incur a synchronous usercache/playerdata disk read per entry. Left
 * as-is per this stage's instructions (which asked for exactly this lookup
 * pattern); an async pre-resolution pass is a reasonable follow-up if this
 * ever shows up as real lag.
 */
public final class RealmSelectorMenu extends ChestGui {

    private static final int MAX_ENTRIES = 45;

    private RealmSelectorMenu(Component title, int size) {
        super(title, size);
    }

    /**
     * @param config     supplies the shared list-menu chrome ({@code list-menu} in {@code gui.yml})
     * @param realmCache read via {@link RealmCache#values()}/{@link RealmCache#getByPlayer(java.util.UUID)}
     * @param viewer     the player this menu is being built for
     * @param title      plain text substituted for the {@code <list-title>} placeholder
     * @param onSelect   invoked (after the viewer's inventory is already closed) with the clicked realm
     * @param onBack     invoked when the back button is clicked
     */
    public static RealmSelectorMenu create(
            GuiConfig config, RealmCache realmCache, Player viewer, String title,
            Consumer<Realm> onSelect, Runnable onBack) {
        ListMenuConfig menuConfig = config.listMenu();
        Component renderedTitle = render(menuConfig.title(), Placeholder.unparsed("list-title", title));
        RealmSelectorMenu menu = new RealmSelectorMenu(renderedTitle, menuConfig.size());

        Optional<Realm> ownRealm = realmCache.getByPlayer(viewer.getUniqueId());
        List<Realm> candidates = new ArrayList<>();
        for (Realm realm : realmCache.values()) {
            if (ownRealm.isEmpty() || realm.id() != ownRealm.get().id()) {
                candidates.add(realm);
            }
        }

        if (candidates.isEmpty()) {
            ItemStack info = menu.buildItem(Material.BARRIER, render("<gray>No other realms to join</gray>"), List.of());
            menu.setItem(emptyStateSlot(menuConfig), info, null);
        } else {
            int slot = 0;
            int placed = 0;
            for (Realm realm : candidates) {
                if (placed >= MAX_ENTRIES) {
                    break;
                }
                while (slot < menuConfig.size() && slot == menuConfig.backSlot()) {
                    slot++;
                }
                if (slot >= menuConfig.size()) {
                    break;
                }
                menu.setItem(slot, buildRealmItem(menu, realm), () -> {
                    viewer.closeInventory();
                    onSelect.accept(realm);
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

    private static ItemStack buildRealmItem(RealmSelectorMenu menu, Realm realm) {
        String leaderName = Bukkit.getOfflinePlayer(realm.leaderUuid()).getName();
        if (leaderName == null) {
            leaderName = realm.leaderUuid().toString();
        }

        Component name = render("<gold><name></gold>", Placeholder.unparsed("name", realm.displayName()));
        List<Component> lore = List.of(
                render("<gray>Leader: <leader></gray>", Placeholder.unparsed("leader", leaderName)),
                render("<gray>Level: <level></gray>", Placeholder.unparsed("level", String.valueOf(realm.level()))));
        return menu.buildItem(Material.WRITABLE_BOOK, name, lore);
    }

    private static int emptyStateSlot(ListMenuConfig menuConfig) {
        int slot = menuConfig.size() / 2;
        return slot == menuConfig.backSlot() ? 0 : slot;
    }
}
