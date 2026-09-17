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

    /**
     * The on-disk file this instance was loaded from, kept around purely so
     * {@link #reload()} knows where to re-read from. Never anything but the
     * server's own {@code plugins/FlameRealms/messages.yml}.
     */
    private final File file;

    /**
     * Neither field below is {@code final} any more: {@link #reload()}
     * replaces both in place, so every existing holder of this SAME {@code
     * Messages} instance (every class that was handed it at construction —
     * {@code RealmCommand}, {@code RealmActions}, ...) sees the reloaded
     * content on their very next {@link #get} call, with no re-wiring needed.
     */
    private YamlConfiguration config;
    private String prefix;

    private Messages(File file, YamlConfiguration config) {
        this.file = file;
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
        return new Messages(file, YamlConfiguration.loadConfiguration(file));
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

    /**
     * Re-reads {@code messages.yml} from disk in place (a local file read —
     * no database/Bukkit-blocking concern, safe to call synchronously from
     * the main thread). Backs {@code /realm reload}. Deliberately does NOT
     * touch {@code pricing.yml}/{@code gui.yml}/{@code config.yml} — those
     * are each baked into their own immutable config record/object at
     * construction and held directly (not through a shared mutable
     * indirection like this class), so reloading them would need every
     * downstream holder restructured first. Out of scope for this pass.
     */
    public void reload() {
        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(file);
        this.config = reloaded;
        this.prefix = reloaded.getString("prefix", "");
    }
}
