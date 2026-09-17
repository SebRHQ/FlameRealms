package com.flamerealms.gui;

import org.bukkit.Material;

import java.util.Map;

/**
 * Parsed binding of {@code gui.yml}'s {@code main-menu:} block.
 *
 * @param title          unresolved MiniMessage template for the inventory title
 * @param size           the inventory size, a multiple of 9 between 9 and 54
 * @param fillerMaterial material used to pad every slot {@link #items()} doesn't occupy
 * @param items          keyed by the exact {@code main-menu.items} key in {@code gui.yml}
 *                       (e.g. {@code "info"}, {@code "create"}, ... {@code "close"}); a key an
 *                       admin has deleted from a customized {@code gui.yml} is simply ABSENT
 *                       here, never synthesized back in — a later stage rendering this menu is
 *                       expected to just skip any action with no entry in this map
 */
public record MainMenuConfig(String title, int size, Material fillerMaterial, Map<String, GuiItemConfig> items) {
}
