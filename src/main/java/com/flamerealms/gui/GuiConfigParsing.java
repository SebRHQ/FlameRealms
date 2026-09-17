package com.flamerealms.gui;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Shared warn-and-fall-back-to-a-sane-default parsing helpers for every
 * {@code gui.yml} section, matching {@code
 * com.flamerealms.config.VisualizationConfig}'s philosophy: a bad or missing
 * cosmetic value degrades loudly to a default rather than throwing — a
 * broken GUI config is not fatal the way a broken database pool size is (see
 * {@code com.flamerealms.config.DatabaseConfig}, which intentionally throws).
 */
final class GuiConfigParsing {

    private GuiConfigParsing() {
    }

    /**
     * Reads an {@code int} at {@code path}, falling back to {@code def} (with
     * a warning) unless it's a positive multiple of 9 between 9 and 54
     * inclusive — Bukkit's own chest inventory size constraints.
     */
    static int parseSize(FileConfiguration config, String path, int def, Logger logger) {
        int size = config.getInt(path, def);
        if (size < 9 || size > 54 || size % 9 != 0) {
            logger.warning("gui.yml: '" + path + "' must be a multiple of 9 between 9 and 54, but was " + size
                    + " — falling back to the default of " + def + ".");
            return def;
        }
        return size;
    }

    /**
     * Validates {@code rawValue} (already read from config, or already
     * defaulted by the caller) as a slot within a menu of {@code size} slots
     * ({@code 0} to {@code size - 1}). Falls back to {@code def} with a
     * warning if it doesn't fit; if even {@code def} doesn't fit (an admin
     * shrank {@code size} without moving every slot that used to fit the
     * bundled default), clamps to the last valid slot instead of handing
     * back an index that would blow up {@code Inventory#setItem} — this
     * never throws, no matter how the two values disagree.
     */
    static int parseSlot(String path, int rawValue, int size, int def, Logger logger) {
        if (rawValue >= 0 && rawValue < size) {
            return rawValue;
        }
        logger.warning("gui.yml: '" + path + "' must be between 0 and " + (size - 1)
                + " (inclusive) for a menu of size " + size + ", but was " + rawValue
                + " — falling back to the default of " + def + ".");

        if (def >= 0 && def < size) {
            return def;
        }
        int clamped = Math.max(0, size - 1);
        logger.warning("gui.yml: the default slot " + def + " for '" + path
                + "' doesn't fit a menu of size " + size + " either — clamping to " + clamped + ".");
        return clamped;
    }

    /**
     * Reads a material name at {@code path}, falling back to {@code def}
     * (with a warning) if it doesn't parse via {@link Material#valueOf} —
     * same pattern as {@code VisualizationConfig}'s particle parsing.
     */
    static Material parseMaterial(FileConfiguration config, String path, Material def, Logger logger) {
        String name = config.getString(path, def.name());
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("gui.yml: '" + path + "' is set to '" + name
                    + "', which is not a recognized material — falling back to " + def + ".");
            return def;
        }
    }
}
