package com.flamerealms.visualization;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.VisualizationConfig;
import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.Realm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Renders a compact text-grid map of the chunks around a player, for display
 * in chat: claimed chunks belonging to the player's own realm in one color,
 * claimed chunks belonging to any other realm in another, unclaimed chunks
 * in a neutral color, and the player's own chunk marked distinctly.
 *
 * <p>This does no DB I/O of its own — it only reads {@link RealmCache}
 * (specifically {@link RealmCache#ownerOf}), which, like every other {@code
 * RealmCache} read in this project, is safe to call directly from the main
 * thread.
 *
 * <p>Built with plain Adventure {@link Component}/{@link TextComponent}
 * calls rather than a MiniMessage template — this project uses MiniMessage
 * ({@code com.flamerealms.config.Messages}) for its static, translator-facing
 * {@code messages.yml} strings, but a grid whose size and cell colors are
 * computed at render time has no fixed template to write; building it
 * component-by-component is the natural fit.
 */
public final class TerritoryMapService {

    /** Player's own current chunk. */
    private static final String SELF_SYMBOL = "✦"; // ✦
    private static final NamedTextColor SELF_COLOR = NamedTextColor.YELLOW;

    /** A chunk claimed by the viewing player's own realm. */
    private static final String OWN_REALM_SYMBOL = "■"; // ■
    private static final NamedTextColor OWN_REALM_COLOR = NamedTextColor.GREEN;

    /** A chunk claimed by any other realm. */
    private static final String OTHER_REALM_SYMBOL = "■"; // ■
    private static final NamedTextColor OTHER_REALM_COLOR = NamedTextColor.RED;

    /** An unclaimed chunk. */
    private static final String UNCLAIMED_SYMBOL = "□"; // □
    private static final NamedTextColor UNCLAIMED_COLOR = NamedTextColor.DARK_GRAY;

    private final RealmCache realmCache;
    private final VisualizationConfig config;

    public TerritoryMapService(RealmCache realmCache, VisualizationConfig config) {
        this.realmCache = realmCache;
        this.config = config;
    }

    /**
     * Builds a {@code (2 * visualization.map-radius-chunks + 1)}-square text
     * grid (config.yml) of the chunks centered on {@code player}'s current
     * chunk, one row per line (via {@link Component#newline}), one symbol per
     * chunk.
     *
     * <p>Must be called from the main thread: it reads {@code player}'s live
     * position/world.
     */
    public Component renderMap(Player player) {
        String world = player.getWorld().getName();
        int centerChunkX = player.getChunk().getX();
        int centerChunkZ = player.getChunk().getZ();
        int radius = config.mapRadiusChunks();

        Long ownRealmId = realmCache.getByPlayer(player.getUniqueId()).map(Realm::id).orElse(null);

        TextComponent.Builder map = Component.text();
        for (int dz = -radius; dz <= radius; dz++) {
            if (dz != -radius) {
                map.appendNewline();
            }
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx != -radius) {
                    map.appendSpace();
                }
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                map.append(cell(world, chunkX, chunkZ, centerChunkX, centerChunkZ, ownRealmId));
            }
        }
        return map.build();
    }

    /** The one symbol/color representing a single chunk in the grid. */
    private Component cell(String world, int chunkX, int chunkZ, int centerChunkX, int centerChunkZ, Long ownRealmId) {
        if (chunkX == centerChunkX && chunkZ == centerChunkZ) {
            return Component.text(SELF_SYMBOL, SELF_COLOR);
        }

        Optional<Long> owner = realmCache.ownerOf(new ChunkCoordinate(world, chunkX, chunkZ));
        if (owner.isEmpty()) {
            return Component.text(UNCLAIMED_SYMBOL, UNCLAIMED_COLOR);
        }
        if (ownRealmId != null && owner.get().equals(ownRealmId)) {
            return Component.text(OWN_REALM_SYMBOL, OWN_REALM_COLOR);
        }
        return Component.text(OTHER_REALM_SYMBOL, OTHER_REALM_COLOR);
    }
}
