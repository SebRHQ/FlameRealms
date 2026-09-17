package com.flamerealms.gui;

import com.flamerealms.service.RealmService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A generic-chrome (see {@link ListMenuConfig}) list of a single realm's
 * current members, used by {@link MainMenu}'s "kick"/"setrank"/"transfer"
 * buttons. Knows nothing about what clicking an entry actually does — {@code
 * onSelect} decides — and, unlike {@link PlayerSelectorMenu} (which lists
 * every ONLINE player), this menu lists a realm's members regardless of
 * whether they're currently online, since a realm member need not be online
 * to be kicked/re-ranked/handed leadership.
 *
 * <p><b>Simplification, called out explicitly:</b> pagination is out of scope
 * for this pass, same as {@link PlayerSelectorMenu}/{@link RealmSelectorMenu}
 * — entries are capped at {@link #MAX_ENTRIES} (45, leaving room for the back
 * button).
 *
 * <p><b>Two off-thread hops before this menu can be built.</b> {@link #open}
 * first calls {@link RealmService#getMemberUuids}, which dispatches through
 * {@code AsyncDatabaseExecutor} — never on the main thread. Its completion
 * runs on a database worker thread, from which every member's display name
 * still needs resolving via {@link Bukkit#getOfflinePlayer(UUID)}, itself
 * unsafe to call from the main thread whenever a member isn't already
 * resident in the server's in-memory profile cache (same null-fallback
 * pattern {@code RealmActions.showInfo} already uses). So that resolution
 * step is explicitly re-dispatched via {@code
 * Bukkit.getScheduler().runTaskAsynchronously(...)} rather than assumed safe
 * just because it's already off the main thread, and only once every name is
 * resolved does the menu get built and opened back on the main thread via
 * {@code Bukkit.getScheduler().runTask(...)}.
 */
public final class MemberSelectorMenu extends ChestGui {

    private static final int MAX_ENTRIES = 45;

    private MemberSelectorMenu(Component title, int size) {
        super(title, size);
    }

    /** One resolved (UUID, display name) pairing handed to {@code onSelect}. */
    public record MemberEntry(UUID uuid, String displayName) {
    }

    /**
     * @param plugin      needed to hop between threads while resolving member display names
     * @param config      supplies the shared list-menu chrome ({@code list-menu} in {@code gui.yml})
     * @param guiManager  the resulting menu is opened through this, once built, per this
     *                    package's own "every open goes through GuiManager" contract
     * @param realmService source of {@code realmId}'s current member list
     * @param viewer      the player this menu is being built for
     * @param realmId     the realm whose members are being listed
     * @param title       plain text substituted for the {@code <list-title>} placeholder
     * @param excludeUuids member UUIDs left out of the listing entirely — e.g. the viewer
     *                     themselves (kick/transfer) and/or the realm's leader (kick only,
     *                     since a leader can never be kicked; see
     *                     {@code RealmService.kick}'s own guard)
     * @param onSelect    invoked (after the viewer's inventory is already closed) with the clicked member
     * @param onBack      invoked when the back button is clicked
     */
    public static void open(
            Plugin plugin, GuiConfig config, GuiManager guiManager, RealmService realmService,
            Player viewer, long realmId, String title, Set<UUID> excludeUuids,
            Consumer<MemberEntry> onSelect, Runnable onBack) {
        realmService.getMemberUuids(realmId).whenComplete((uuids, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load members for realm " + realmId + " to build a selector menu", error);
                return;
            }

            // Resolving Bukkit.getOfflinePlayer(UUID) can still fall back to a
            // synchronous usercache/playerdata disk read for a genuine cache
            // miss, so this stays off the main thread even though we're
            // already on a database worker thread here, not the main one.
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<MemberEntry> entries = new ArrayList<>();
                for (UUID uuid : uuids) {
                    if (excludeUuids.contains(uuid)) {
                        continue;
                    }
                    String name = Bukkit.getOfflinePlayer(uuid).getName();
                    if (name == null) {
                        name = uuid.toString();
                    }
                    entries.add(new MemberEntry(uuid, name));
                }

                List<MemberEntry> resolved = entries;
                Bukkit.getScheduler().runTask(plugin, () ->
                        guiManager.open(viewer, build(config, viewer, title, resolved, onSelect, onBack)));
            });
        });
    }

    private static MemberSelectorMenu build(
            GuiConfig config, Player viewer, String title, List<MemberEntry> members,
            Consumer<MemberEntry> onSelect, Runnable onBack) {
        ListMenuConfig menuConfig = config.listMenu();
        Component renderedTitle = render(menuConfig.title(), Placeholder.unparsed("list-title", title));
        MemberSelectorMenu menu = new MemberSelectorMenu(renderedTitle, menuConfig.size());

        if (members.isEmpty()) {
            ItemStack info = menu.buildItem(Material.BARRIER, render("<gray>No eligible members</gray>"), List.of());
            menu.setItem(emptyStateSlot(menuConfig), info, null);
        } else {
            int slot = 0;
            int placed = 0;
            for (MemberEntry member : members) {
                if (placed >= MAX_ENTRIES) {
                    break;
                }
                while (slot < menuConfig.size() && slot == menuConfig.backSlot()) {
                    slot++;
                }
                if (slot >= menuConfig.size()) {
                    break;
                }
                menu.setItem(slot, buildHeadItem(menu, member), () -> {
                    viewer.closeInventory();
                    onSelect.accept(member);
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

    private static ItemStack buildHeadItem(MemberSelectorMenu menu, MemberEntry member) {
        Component name = render("<yellow><name></yellow>", Placeholder.unparsed("name", member.displayName()));
        ItemStack item = menu.buildItem(Material.PLAYER_HEAD, name, List.of());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(member.uuid()));
            item.setItemMeta(skullMeta);
        }
        return item;
    }

    private static int emptyStateSlot(ListMenuConfig menuConfig) {
        int slot = menuConfig.size() / 2;
        return slot == menuConfig.backSlot() ? 0 : slot;
    }
}
