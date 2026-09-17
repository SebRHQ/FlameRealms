package com.flamerealms.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tracks each player's currently-open {@link ChestGui} and is the single
 * place a click inside (or around) one is handled. Registered once by
 * {@code FlameRealmsPlugin} as an ordinary Bukkit {@link Listener} — this
 * class itself knows nothing about realms, claims or economy, only about
 * {@link ChestGui} instances and Bukkit inventory events.
 *
 * <p><b>Anti-dupe/anti-theft rule.</b> While a player has one of our GUIs
 * open, EVERY {@link InventoryClickEvent} of theirs is cancelled
 * unconditionally — both a click inside the GUI's own top inventory and a
 * click inside their own inventory while the GUI is open. This is the
 * standard, deliberately blunt rule every chest-GUI plugin uses: players
 * simply cannot rearrange their own inventory while a FlameRealms menu is
 * open. Only when the click landed in the GUI's own top inventory is a
 * slot's click handler (if any) then looked up and invoked.
 */
public final class GuiManager implements Listener {

    private final Plugin plugin;
    private final Map<UUID, ChestGui> openGuis = new ConcurrentHashMap<>();

    public GuiManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Tracks {@code gui} as {@code player}'s currently-open menu and opens it
     * for them.
     *
     * <p><b>Order matters here.</b> {@code openInventory(...)} synchronously
     * fires an {@link InventoryCloseEvent} for whatever inventory the player
     * currently has open, if any, BEFORE the new one actually opens — this is
     * how directly switching between two of our menus (e.g. a "Back" button
     * that opens the main menu without first calling {@code
     * player.closeInventory()}) behaves. Opening first and only THEN updating
     * {@code openGuis} means that synchronous close event's handler (below)
     * still sees the OLD gui as the tracked value and removes exactly that —
     * never the new one. Doing this in the opposite order (track the new gui,
     * then open) would let that same close event delete the just-tracked new
     * entry instead, silently leaving the player looking at an untracked,
     * unprotected "menu" (no click handling, no anti-dupe cancellation).
     */
    public void open(Player player, ChestGui gui) {
        player.openInventory(gui.getInventory());
        openGuis.put(player.getUniqueId(), gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ChestGui gui = openGuis.get(player.getUniqueId());
        if (gui == null) {
            return;
        }

        // Deliberately unconditional — see class Javadoc.
        event.setCancelled(true);

        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null || clickedInventory != gui.getInventory()) {
            // Either no inventory was actually clicked (an edge-of-screen
            // click) or the click landed in the player's own inventory —
            // already cancelled above, nothing further to dispatch.
            return;
        }

        Runnable handler = gui.handlerFor(event.getSlot());
        if (handler == null) {
            return;
        }
        try {
            handler.run();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "A FlameRealms GUI click handler threw an exception", e);
        }
    }

    /**
     * Same anti-dupe/anti-theft rule as {@link #onInventoryClick} extended to
     * drags: an uncancelled {@link InventoryDragEvent} lets a player drop a
     * real item (one already on their cursor) straight into our virtual
     * inventory's slots, which isn't backed by any real container — a genuine
     * item-loss/dupe vector the click-cancelling rule alone does not close.
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (openGuis.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openGuis.remove(player.getUniqueId());
        }
    }

    /** Safety net: a GUI staying "open" across a disconnect must never leak a stale tracked entry. */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        openGuis.remove(event.getPlayer().getUniqueId());
    }
}
