package com.flamerealms.config;

import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Immutable binding of config.yml's {@code visualization:} block, consumed
 * by {@code com.flamerealms.visualization.ClaimVisualizationService} and
 * {@code com.flamerealms.visualization.TerritoryMapService}.
 *
 * <p>Like {@link DatabaseConfig}, this lives in {@code config.yml} itself
 * (loaded via {@code plugin.getConfig()} after {@code saveDefaultConfig()}),
 * not a separate file the way {@code pricing.yml} is — visualization has no
 * reason for its own file, it's a handful of scalars.
 *
 * @param particle                the particle traced along a previewed chunk's
 *                                 boundary (config: {@code visualization.particle})
 * @param previewDurationSeconds  how long a chunk-boundary preview keeps
 *                                 pulsing before it self-cancels (config:
 *                                 {@code visualization.preview-duration-seconds})
 * @param mapRadiusChunks         how many chunks out from the player's own chunk
 *                                 {@code TerritoryMapService#renderMap} covers, in
 *                                 every direction (config: {@code
 *                                 visualization.map-radius-chunks})
 */
public record VisualizationConfig(
        Particle particle,
        int previewDurationSeconds,
        int mapRadiusChunks
) {

    private static final Particle DEFAULT_PARTICLE = Particle.FLAME;
    private static final int DEFAULT_PREVIEW_DURATION_SECONDS = 5;
    private static final int DEFAULT_MAP_RADIUS_CHUNKS = 8;

    /**
     * Reads the {@code visualization:} block from the given configuration
     * (typically {@code plugin.getConfig()} after {@code saveDefaultConfig()}
     * has been called).
     *
     * @param logger used to warn about an unrecognized particle name or a
     *               non-positive duration/radius, falling back to the
     *               default in either case rather than throwing — a
     *               visualization misconfiguration should degrade loudly in
     *               the log, not crash the plugin the way an unusable
     *               database pool size does
     */
    public static VisualizationConfig fromConfig(FileConfiguration config, Logger logger) {
        Particle particle = parseParticle(
                config.getString("visualization.particle", DEFAULT_PARTICLE.name()), logger);
        int previewDurationSeconds = positiveOrDefault(
                "visualization.preview-duration-seconds",
                config.getInt("visualization.preview-duration-seconds", DEFAULT_PREVIEW_DURATION_SECONDS),
                DEFAULT_PREVIEW_DURATION_SECONDS,
                logger);
        int mapRadiusChunks = positiveOrDefault(
                "visualization.map-radius-chunks",
                config.getInt("visualization.map-radius-chunks", DEFAULT_MAP_RADIUS_CHUNKS),
                DEFAULT_MAP_RADIUS_CHUNKS,
                logger);

        return new VisualizationConfig(particle, previewDurationSeconds, mapRadiusChunks);
    }

    private static Particle parseParticle(String name, Logger logger) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("config.yml: 'visualization.particle' is set to '" + name
                    + "', which is not a recognized particle — falling back to " + DEFAULT_PARTICLE + ".");
            return DEFAULT_PARTICLE;
        }
    }

    private static int positiveOrDefault(String path, int value, int def, Logger logger) {
        if (value <= 0) {
            logger.warning("config.yml: '" + path + "' must be positive, but was " + value
                    + " — falling back to the default of " + def + ".");
            return def;
        }
        return value;
    }
}
