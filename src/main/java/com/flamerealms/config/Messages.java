package com.flamerealms.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Loads every player-facing string from {@code messages.yml} so server
 * owners can restyle FlameRealms's messages without touching code. Each
 * value in that file is a complete MiniMessage template, including its own
 * color/style tags — {@link #prefix} is prepended to it, nothing else is
 * assumed or injected on top.
 */
public final class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final YamlConfiguration config;
    private final String prefix;

    private Messages(YamlConfiguration config) {
        this.config = config;
        this.prefix = config.getString("prefix", "");
    }

    /**
     * Copies the bundled default {@code messages.yml} into the plugin's data
     * folder if it isn't there yet (an existing, possibly hand-edited, copy
     * is never overwritten), then loads it. Call once from {@code onEnable()}.
     */
    public static Messages load(JavaPlugin plugin) {
        plugin.saveResource("messages.yml", false);
        File file = new File(plugin.getDataFolder(), "messages.yml");
        return new Messages(YamlConfiguration.loadConfiguration(file));
    }

    /**
     * Renders the (prefixed) template stored under {@code key}, filling in
     * {@code resolvers}. Returns a visibly obvious placeholder message
     * instead of throwing if {@code key} is missing from messages.yml, so a
     * malformed edit degrades loudly rather than crashing a command.
     */
    public Component get(String key, TagResolver... resolvers) {
        String template = config.getString(key);
        if (template == null) {
            return MINI_MESSAGE.deserialize("<red>Missing message key: " + key + "</red>");
        }
        return MINI_MESSAGE.deserialize(prefix + template, resolvers);
    }
}
