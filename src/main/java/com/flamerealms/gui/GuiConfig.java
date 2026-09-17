package com.flamerealms.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable binding of the whole of {@code gui.yml}.
 *
 * <p>This class — and every other class in {@code com.flamerealms.gui} — is
 * deliberately generic: it knows about menus, slots, materials and
 * MiniMessage text templates, and nothing whatsoever about realms, claims or
 * economy. A later, realm-aware stage looks up {@link MainMenuConfig#items()}
 * by key (e.g. {@code "claim"}, {@code "deposit"}) to decide what a click
 * actually does; this package never does.
 *
 * <p>Like {@code com.flamerealms.config.PricingConfig}, {@code gui.yml} is
 * its own file: {@link #load(JavaPlugin)} copies the bundled default into
 * the plugin's data folder on first run via {@code saveResource(...)} (an
 * existing, possibly hand-edited, copy is never overwritten — same
 * convention as {@code messages.yml}/{@code pricing.yml}), then loads and
 * parses it from there, never from the jar.
 *
 * <p>Unlike {@code com.flamerealms.config.DatabaseConfig}, a bad value here
 * is purely cosmetic — a broken GUI/particle config is not fatal the way a
 * broken thread pool is — so every field follows {@code
 * VisualizationConfig}'s philosophy: an out-of-range slot, an invalid chest
 * size, or an unrecognized {@link Material} name never throws, it just logs
 * a warning and substitutes the shipped default for that one value. An item
 * an admin deleted entirely from {@code main-menu.items} is simply left out
 * of {@link MainMenuConfig#items()} — never synthesized back in.
 */
public record GuiConfig(
        MainMenuConfig mainMenu,
        ConfirmMenuConfig confirmMenu,
        AmountMenuConfig amountMenu,
        ListMenuConfig listMenu
) {

    // -- Defaults, mirroring the bundled gui.yml exactly ---------------------

    private static final Material DEFAULT_FILLER_MATERIAL = Material.GRAY_STAINED_GLASS_PANE;

    private static final String DEFAULT_MAIN_MENU_TITLE = "<dark_gray>FlameRealms</dark_gray>";
    private static final int DEFAULT_MAIN_MENU_SIZE = 27;
    private static final Map<String, GuiItemConfig> DEFAULT_MAIN_MENU_ITEMS = buildDefaultMainMenuItems();

    private static Map<String, GuiItemConfig> buildDefaultMainMenuItems() {
        Map<String, GuiItemConfig> defaults = new LinkedHashMap<>();
        defaults.put("info", new GuiItemConfig(10, Material.BOOK, "<gold>Realm Info</gold>",
                List.of("<gray>View your realm's info</gray>")));
        defaults.put("create", new GuiItemConfig(11, Material.NETHER_STAR, "<gold>Create Realm</gold>",
                List.of("<gray>Found a new realm</gray>")));
        defaults.put("invite", new GuiItemConfig(12, Material.PLAYER_HEAD, "<gold>Invite Player</gold>",
                List.of("<gray>Invite an online player</gray>")));
        defaults.put("join", new GuiItemConfig(13, Material.WRITABLE_BOOK, "<gold>Join Realm</gold>",
                List.of("<gray>Browse and join a realm</gray>")));
        defaults.put("leave", new GuiItemConfig(14, Material.IRON_DOOR, "<gold>Leave Realm</gold>", List.of()));
        defaults.put("disband", new GuiItemConfig(15, Material.TNT, "<red>Disband Realm</red>",
                List.of("<gray>This cannot be undone</gray>")));
        defaults.put("balance", new GuiItemConfig(16, Material.GOLD_INGOT, "<gold>Balance</gold>", List.of()));
        defaults.put("kick", new GuiItemConfig(17, Material.LEATHER_BOOTS, "<red>Kick Member</red>",
                List.of("<gray>Remove a member from your realm</gray>")));
        defaults.put("setrank", new GuiItemConfig(18, Material.NAME_TAG, "<gold>Set Rank</gold>",
                List.of("<gray>Change a member's rank</gray>")));
        defaults.put("deposit", new GuiItemConfig(19, Material.HOPPER, "<gold>Deposit</gold>", List.of()));
        defaults.put("withdraw", new GuiItemConfig(20, Material.CHEST, "<gold>Withdraw</gold>", List.of()));
        defaults.put("claim", new GuiItemConfig(21, Material.GRASS_BLOCK, "<gold>Claim Chunk</gold>",
                List.of("<gray>Claim the chunk you're standing in</gray>")));
        defaults.put("unclaim", new GuiItemConfig(22, Material.BARRIER, "<gold>Unclaim Chunk</gold>",
                List.of("<gray>Release the chunk you're standing in</gray>")));
        defaults.put("map", new GuiItemConfig(23, Material.FILLED_MAP, "<gold>Territory Map</gold>", List.of()));
        defaults.put("borders", new GuiItemConfig(24, Material.GLOWSTONE_DUST, "<gold>Toggle Borders</gold>",
                List.of("<gray>Toggle a persistent outline of your territory</gray>")));
        defaults.put("transfer", new GuiItemConfig(25, Material.TOTEM_OF_UNDYING, "<gold>Transfer Leadership</gold>",
                List.of("<gray>Hand over the realm's leadership</gray>", "<gray>without disbanding it</gray>")));
        defaults.put("close", new GuiItemConfig(26, Material.BARRIER, "<red>Close</red>", List.of()));
        return Map.copyOf(defaults);
    }

    private static final String DEFAULT_CONFIRM_MENU_TITLE = "<dark_gray>Please Confirm</dark_gray>";
    private static final int DEFAULT_CONFIRM_MENU_SIZE = 27;
    private static final int DEFAULT_CONFIRM_SLOT = 11;
    private static final Material DEFAULT_CONFIRM_MATERIAL = Material.LIME_WOOL;
    private static final String DEFAULT_CONFIRM_NAME = "<green>Confirm</green>";
    private static final int DEFAULT_CONFIRM_MENU_CANCEL_SLOT = 15;
    private static final Material DEFAULT_CANCEL_MATERIAL = Material.RED_WOOL;
    private static final String DEFAULT_CANCEL_NAME = "<red>Cancel</red>";

    private static final String DEFAULT_AMOUNT_MENU_TITLE = "<dark_gray>Choose an Amount</dark_gray>";
    private static final int DEFAULT_AMOUNT_MENU_SIZE = 27;
    private static final Material DEFAULT_PRESET_MATERIAL = Material.GOLD_NUGGET;
    private static final String DEFAULT_PRESET_NAME = "<gold><amount></gold>";
    private static final List<Long> DEFAULT_PRESETS_CENTS = List.of(10000L, 50000L, 100000L, 500000L);
    private static final int DEFAULT_CUSTOM_SLOT = 22;
    private static final Material DEFAULT_CUSTOM_MATERIAL = Material.PAPER;
    private static final String DEFAULT_CUSTOM_NAME = "<yellow>Custom Amount (type in chat)</yellow>";
    private static final int DEFAULT_AMOUNT_MENU_CANCEL_SLOT = 26;

    private static final String DEFAULT_LIST_MENU_TITLE = "<dark_gray><list-title></dark_gray>";
    private static final int DEFAULT_LIST_MENU_SIZE = 54;
    private static final int DEFAULT_BACK_SLOT = 49;
    private static final Material DEFAULT_BACK_MATERIAL = Material.ARROW;
    private static final String DEFAULT_BACK_NAME = "<gray>Back</gray>";

    /**
     * Copies the bundled default {@code gui.yml} into the plugin's data
     * folder if it isn't there yet (an existing, possibly hand-edited, copy
     * is never overwritten), then reads and parses it. Call once from
     * {@code onEnable()}.
     */
    public static GuiConfig load(JavaPlugin plugin) {
        plugin.saveResource("gui.yml", false);
        File file = new File(plugin.getDataFolder(), "gui.yml");
        return fromConfig(YamlConfiguration.loadConfiguration(file), plugin.getLogger());
    }

    /**
     * Parses an already-loaded {@code gui.yml}. Split out from {@link
     * #load(JavaPlugin)} the same way {@code PricingConfig#fromConfig} is,
     * so tests can feed in a {@link FileConfiguration} directly.
     */
    public static GuiConfig fromConfig(FileConfiguration config, Logger logger) {
        return new GuiConfig(
                parseMainMenu(config, logger),
                parseConfirmMenu(config, logger),
                parseAmountMenu(config, logger),
                parseListMenu(config, logger));
    }

    private static MainMenuConfig parseMainMenu(FileConfiguration config, Logger logger) {
        String title = config.getString("main-menu.title", DEFAULT_MAIN_MENU_TITLE);
        int size = GuiConfigParsing.parseSize(config, "main-menu.size", DEFAULT_MAIN_MENU_SIZE, logger);
        Material filler = GuiConfigParsing.parseMaterial(
                config, "main-menu.filler-material", DEFAULT_FILLER_MATERIAL, logger);
        Map<String, GuiItemConfig> items = parseItems(config, size, logger);
        return new MainMenuConfig(title, size, filler, items);
    }

    /**
     * Parses {@code main-menu.items}. A key entirely missing from a
     * customized {@code gui.yml} is simply absent from the returned map —
     * never synthesized back in. A key that IS present but has a bad
     * slot/material falls back to that same key's shipped default when one
     * exists (e.g. {@code "claim"}), or to a generic placeholder for a
     * custom key an admin invented that has no shipped default to fall back
     * to.
     */
    private static Map<String, GuiItemConfig> parseItems(FileConfiguration config, int size, Logger logger) {
        ConfigurationSection section = config.getConfigurationSection("main-menu.items");
        if (section == null) {
            logger.warning("gui.yml: 'main-menu.items' is missing entirely — falling back to the default item set.");
            return DEFAULT_MAIN_MENU_ITEMS;
        }

        Map<String, GuiItemConfig> items = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            GuiItemConfig fallback = DEFAULT_MAIN_MENU_ITEMS.getOrDefault(
                    key, new GuiItemConfig(0, Material.STONE, "<gray>" + key + "</gray>", List.of()));

            String base = "main-menu.items." + key;
            int slot = GuiConfigParsing.parseSlot(
                    base + ".slot", config.getInt(base + ".slot", fallback.slot()), size, fallback.slot(), logger);
            Material material = GuiConfigParsing.parseMaterial(
                    config, base + ".material", fallback.material(), logger);
            String name = config.getString(base + ".name", fallback.name());
            List<String> lore = config.getStringList(base + ".lore");

            items.put(key, new GuiItemConfig(slot, material, name, lore));
        }
        return resolveSlotCollisions(items, size, logger);
    }

    /**
     * Two DIFFERENT item keys independently validating their own slot against
     * the menu size (as {@link #parseItems} does above) can still both land
     * on the SAME slot — e.g. two keys both explicitly configured to the same
     * slot, or several keys' fallback slots all clamping to {@code size-1}
     * after an admin shrinks {@code main-menu.size}. Left unresolved, one
     * button would silently overwrite another in the built {@link
     * org.bukkit.inventory.Inventory} with no warning at all — exactly the
     * "silently wrong" failure mode this class's warn-and-default philosophy
     * is meant to rule out. Walk the items in a stable order (insertion/YAML
     * order) and bump every slot collision after the first claimant to the
     * next free slot in the menu, warning each time.
     */
    private static Map<String, GuiItemConfig> resolveSlotCollisions(
            Map<String, GuiItemConfig> items, int size, Logger logger) {
        Map<String, GuiItemConfig> resolved = new LinkedHashMap<>();
        Set<Integer> usedSlots = new HashSet<>();

        for (Map.Entry<String, GuiItemConfig> entry : items.entrySet()) {
            String key = entry.getKey();
            GuiItemConfig item = entry.getValue();
            int slot = item.slot();

            if (usedSlots.contains(slot)) {
                int freeSlot = firstFreeSlot(usedSlots, size);
                if (freeSlot < 0) {
                    logger.warning("gui.yml: 'main-menu.items." + key + "' is configured for slot " + slot
                            + ", which is already used by another item, and no free slot remains in a menu of "
                            + "size " + size + " — this item will not be shown.");
                    continue;
                }
                logger.warning("gui.yml: 'main-menu.items." + key + "' is configured for slot " + slot
                        + ", which is already used by another item — moved to slot " + freeSlot + " instead.");
                slot = freeSlot;
                item = new GuiItemConfig(slot, item.material(), item.name(), item.lore());
            }

            usedSlots.add(slot);
            resolved.put(key, item);
        }
        return Map.copyOf(resolved);
    }

    private static int firstFreeSlot(Set<Integer> usedSlots, int size) {
        for (int slot = 0; slot < size; slot++) {
            if (!usedSlots.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * For a menu with exactly two special-purpose slots (confirm/cancel,
     * custom/cancel, etc.) configured independently: if {@code slot} was
     * configured to the same value as {@code otherSlot}, move it to {@code
     * fallbackSlot} (or, if that's ALSO {@code otherSlot}, the first free
     * slot in the menu) and warn. Returns {@code slot} unchanged if there is
     * no collision.
     */
    private static int resolveIfColliding(
            int slot, int otherSlot, int fallbackSlot, int size, String path, Logger logger) {
        if (slot != otherSlot) {
            return slot;
        }
        int newSlot = fallbackSlot != otherSlot
                ? fallbackSlot
                : firstFreeSlot(Set.of(otherSlot), size);
        logger.warning("gui.yml: '" + path + "' is configured for slot " + slot
                + ", which collides with another slot in the same menu — moved to slot " + newSlot + " instead.");
        return newSlot;
    }

    private static ConfirmMenuConfig parseConfirmMenu(FileConfiguration config, Logger logger) {
        int size = GuiConfigParsing.parseSize(config, "confirm-menu.size", DEFAULT_CONFIRM_MENU_SIZE, logger);
        String title = config.getString("confirm-menu.title", DEFAULT_CONFIRM_MENU_TITLE);
        Material filler = GuiConfigParsing.parseMaterial(
                config, "confirm-menu.filler-material", DEFAULT_FILLER_MATERIAL, logger);

        int confirmSlot = GuiConfigParsing.parseSlot(
                "confirm-menu.confirm-slot", config.getInt("confirm-menu.confirm-slot", DEFAULT_CONFIRM_SLOT),
                size, DEFAULT_CONFIRM_SLOT, logger);
        Material confirmMaterial = GuiConfigParsing.parseMaterial(
                config, "confirm-menu.confirm-material", DEFAULT_CONFIRM_MATERIAL, logger);
        String confirmName = config.getString("confirm-menu.confirm-name", DEFAULT_CONFIRM_NAME);

        int cancelSlot = GuiConfigParsing.parseSlot(
                "confirm-menu.cancel-slot",
                config.getInt("confirm-menu.cancel-slot", DEFAULT_CONFIRM_MENU_CANCEL_SLOT),
                size, DEFAULT_CONFIRM_MENU_CANCEL_SLOT, logger);
        cancelSlot = resolveIfColliding(
                cancelSlot, confirmSlot, DEFAULT_CONFIRM_MENU_CANCEL_SLOT, size, "confirm-menu.cancel-slot", logger);
        Material cancelMaterial = GuiConfigParsing.parseMaterial(
                config, "confirm-menu.cancel-material", DEFAULT_CANCEL_MATERIAL, logger);
        String cancelName = config.getString("confirm-menu.cancel-name", DEFAULT_CANCEL_NAME);

        return new ConfirmMenuConfig(
                title, size, filler, confirmSlot, confirmMaterial, confirmName, cancelSlot, cancelMaterial, cancelName);
    }

    private static AmountMenuConfig parseAmountMenu(FileConfiguration config, Logger logger) {
        int size = GuiConfigParsing.parseSize(config, "amount-menu.size", DEFAULT_AMOUNT_MENU_SIZE, logger);
        String title = config.getString("amount-menu.title", DEFAULT_AMOUNT_MENU_TITLE);
        Material filler = GuiConfigParsing.parseMaterial(
                config, "amount-menu.filler-material", DEFAULT_FILLER_MATERIAL, logger);
        Material presetMaterial = GuiConfigParsing.parseMaterial(
                config, "amount-menu.preset-material", DEFAULT_PRESET_MATERIAL, logger);
        String presetName = config.getString("amount-menu.preset-name", DEFAULT_PRESET_NAME);

        // An explicitly-empty "presets-cents: []" (an admin who wants ONLY the
        // custom-amount button, no presets) is a deliberate choice and must be
        // respected, not silently overridden — only a genuinely ABSENT key
        // falls back to the shipped default list. config.contains(...) is the
        // only way to tell "absent" and "present but empty" apart, since
        // getLongList(...) returns an empty list for both.
        List<Long> presetsCents;
        if (!config.contains("amount-menu.presets-cents")) {
            presetsCents = DEFAULT_PRESETS_CENTS;
        } else {
            presetsCents = List.copyOf(config.getLongList("amount-menu.presets-cents"));
        }

        int customSlot = GuiConfigParsing.parseSlot(
                "amount-menu.custom-slot", config.getInt("amount-menu.custom-slot", DEFAULT_CUSTOM_SLOT),
                size, DEFAULT_CUSTOM_SLOT, logger);
        Material customMaterial = GuiConfigParsing.parseMaterial(
                config, "amount-menu.custom-material", DEFAULT_CUSTOM_MATERIAL, logger);
        String customName = config.getString("amount-menu.custom-name", DEFAULT_CUSTOM_NAME);

        int cancelSlot = GuiConfigParsing.parseSlot(
                "amount-menu.cancel-slot",
                config.getInt("amount-menu.cancel-slot", DEFAULT_AMOUNT_MENU_CANCEL_SLOT),
                size, DEFAULT_AMOUNT_MENU_CANCEL_SLOT, logger);
        cancelSlot = resolveIfColliding(
                cancelSlot, customSlot, DEFAULT_AMOUNT_MENU_CANCEL_SLOT, size, "amount-menu.cancel-slot", logger);
        Material cancelMaterial = GuiConfigParsing.parseMaterial(
                config, "amount-menu.cancel-material", DEFAULT_CANCEL_MATERIAL, logger);
        String cancelName = config.getString("amount-menu.cancel-name", DEFAULT_CANCEL_NAME);

        return new AmountMenuConfig(
                title, size, filler, presetMaterial, presetName, presetsCents,
                customSlot, customMaterial, customName, cancelSlot, cancelMaterial, cancelName);
    }

    private static ListMenuConfig parseListMenu(FileConfiguration config, Logger logger) {
        int size = GuiConfigParsing.parseSize(config, "list-menu.size", DEFAULT_LIST_MENU_SIZE, logger);
        String title = config.getString("list-menu.title", DEFAULT_LIST_MENU_TITLE);
        Material filler = GuiConfigParsing.parseMaterial(
                config, "list-menu.filler-material", DEFAULT_FILLER_MATERIAL, logger);
        int backSlot = GuiConfigParsing.parseSlot(
                "list-menu.back-slot", config.getInt("list-menu.back-slot", DEFAULT_BACK_SLOT),
                size, DEFAULT_BACK_SLOT, logger);
        Material backMaterial = GuiConfigParsing.parseMaterial(
                config, "list-menu.back-material", DEFAULT_BACK_MATERIAL, logger);
        String backName = config.getString("list-menu.back-name", DEFAULT_BACK_NAME);

        return new ListMenuConfig(title, size, filler, backSlot, backMaterial, backName);
    }
}
