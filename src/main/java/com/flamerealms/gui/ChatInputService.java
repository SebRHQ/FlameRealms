package com.flamerealms.gui;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Captures a player's NEXT chat message as a one-shot text input for a GUI
 * flow — used by {@link AmountMenu}'s custom-amount button, and by any
 * future "type a name in chat" flow (e.g. realm creation).
 *
 * <p>Registered once by {@code FlameRealmsPlugin} as an ordinary Bukkit
 * {@link Listener}, the same way {@link GuiManager} is.
 */
public final class ChatInputService implements Listener {

    private final Plugin plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatInputService(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers {@code onInput} to receive {@code player}'s next chat
     * message as plain text. Only one pending prompt exists per player at a
     * time — calling this again for the same player before their previous
     * prompt was answered simply replaces it.
     */
    public void prompt(Player player, Consumer<String> onInput) {
        pending.put(player.getUniqueId(), onInput);
    }

    /**
     * Paper's modern chat event, not the deprecated Bukkit one — this
     * project depends on Paper API throughout. Fires off the main thread, so
     * the pending handler is invoked back on it via {@code
     * Bukkit.getScheduler().runTask(...)} before it ever touches Bukkit API,
     * the same never-block-the-main-thread discipline used everywhere else
     * in this project, just applied in the other direction (hopping FROM an
     * async event TO the main thread).
     */
    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Consumer<String> handler = pending.remove(event.getPlayer().getUniqueId());
        if (handler == null) {
            return;
        }

        // This was a GUI input, not a real chat message — never let the raw
        // text actually post to server chat.
        event.setCancelled(true);

        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> handler.accept(text));
    }

    /** A player who disconnects before answering must never leave a stale pending handler behind. */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
