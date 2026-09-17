package com.flamerealms.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Generic base class for a single chest-style GUI screen.
 *
 * <p>This class (and the rest of {@code com.flamerealms.gui}) knows nothing
 * about realms, claims or economy — only about Bukkit inventories, slots and
 * MiniMessage text. A concrete menu (e.g. {@link ConfirmMenu}, {@link
 * AmountMenu}, or a later realm-aware main menu) extends this class and
 * builds its screen through the small protected API below inside its own
 * static factory method, then hands the finished instance to {@link
 * GuiManager#open} to actually show it to a player.
 *
 * <p>Implements {@link InventoryHolder} so {@link #getInventory()}'s result
 * carries this instance back via {@code Inventory#getHolder()} — {@link
 * GuiManager} tracks player-to-open-gui directly rather than relying on
 * that, but the holder link is kept for correctness and for any future
 * caller that wants it.
 */
public abstract class ChestGui implements InventoryHolder {

    protected static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Inventory inventory;
    private final Map<Integer, Runnable> clickHandlers = new HashMap<>();

    protected ChestGui(Component title, int size) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Places {@code item} at {@code slot} and registers {@code onClick} to
     * run whenever a player clicks that slot inside this GUI's own top
     * inventory (see {@link GuiManager}'s click handling — a click anywhere
     * else while this GUI is open is cancelled outright but never
     * dispatched). {@code onClick} may be {@code null} for a purely
     * decorative item with no action.
     */
    protected void setItem(int slot, ItemStack item, Runnable onClick) {
        if (slot < 0 || slot >= inventory.getSize()) {
            // Reachable from a config-driven slot (gui.yml) or a computed one
            // (e.g. a list-style menu placing dynamic entries at 0..44 when an
            // admin has shrunk that menu's configured size below 45) —
            // Inventory#setItem throws ArrayIndexOutOfBoundsException for an
            // out-of-range index, which would otherwise crash whatever action
            // was building this menu. Skip the one item instead: a menu
            // missing one button is recoverable, a thrown exception mid-open
            // is not.
            Logger.getLogger(ChestGui.class.getName()).warning(
                    "FlameRealms GUI: skipped placing an item at out-of-range slot " + slot
                            + " in a menu of size " + inventory.getSize() + " — check gui.yml's slot values "
                            + "against that menu's configured size.");
            return;
        }
        inventory.setItem(slot, item);
        if (onClick != null) {
            clickHandlers.put(slot, onClick);
        } else {
            clickHandlers.remove(slot);
        }
    }

    /**
     * Fills every slot that's still empty (no {@link #setItem} call has
     * touched it) with an unnamed, lore-less {@code material} item that has
     * no click handler. Clicking it does nothing — the click is still
     * cancelled by {@link GuiManager}, like every other click inside this
     * inventory, but no handler is invoked.
     */
    protected void fillRemaining(Material material) {
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /**
     * Builds an {@link ItemStack} of {@code material} from already-resolved
     * {@code name}/{@code lore} {@link Component}s, forcing italics off —
     * Minecraft's default lore italics otherwise makes every custom-colored
     * line look like faded flavor text.
     */
    protected ItemStack buildItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            List<Component> resolvedLore = new ArrayList<>(lore.size());
            for (Component line : lore) {
                resolvedLore.add(line.decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(resolvedLore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Convenience overload: deserializes {@code nameTemplate}/{@code
     * loreTemplates} as MiniMessage, substituting {@code resolvers} into
     * both. Pass no resolvers for a template with no placeholders.
     */
    protected ItemStack buildItem(
            Material material, String nameTemplate, List<String> loreTemplates, TagResolver... resolvers) {
        Component name = MINI_MESSAGE.deserialize(nameTemplate, resolvers);
        List<Component> lore = new ArrayList<>(loreTemplates.size());
        for (String line : loreTemplates) {
            lore.add(MINI_MESSAGE.deserialize(line, resolvers));
        }
        return buildItem(material, name, lore);
    }

    /** Builds an item straight from a parsed {@link GuiItemConfig}, with no placeholder substitution. */
    protected ItemStack buildItem(GuiItemConfig config) {
        return buildItem(config.material(), config.name(), config.lore());
    }

    /** Deserializes a MiniMessage template (e.g. a menu's {@code title}), substituting {@code resolvers}. */
    protected static Component render(String template, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(template, resolvers);
    }

    /**
     * Package-visible: {@link GuiManager} looks up (and invokes, wrapped in
     * its own try/catch) whatever handler was registered for {@code slot}
     * via {@link #setItem}. Returns {@code null} for a slot with no
     * registered handler (a filler item, or a slot never set at all).
     */
    Runnable handlerFor(int slot) {
        return clickHandlers.get(slot);
    }
}
