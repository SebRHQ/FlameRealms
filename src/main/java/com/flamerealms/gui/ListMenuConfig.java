package com.flamerealms.gui;

import org.bukkit.Material;

/**
 * Parsed binding of {@code gui.yml}'s {@code list-menu:} block — a generic,
 * scrollable-by-a-later-stage list screen with a back button. This stage
 * only builds the config schema for it; the menu class that actually
 * paginates a list of items (realm browsing, etc.) is left to a later,
 * realm-aware stage.
 *
 * @param title          unresolved MiniMessage template for the inventory title, containing
 *                       the {@code <list-title>} placeholder a later stage substitutes with
 *                       whatever this particular list is titled (e.g. "Browse Realms")
 * @param size           the inventory size, a multiple of 9 between 9 and 54
 * @param fillerMaterial material used to pad every slot not otherwise occupied
 * @param backSlot       slot of the back button, {@code 0} to {@code size - 1}
 * @param backMaterial   material of the back button
 * @param backName       unresolved MiniMessage template for the back button's name
 */
public record ListMenuConfig(
        String title,
        int size,
        Material fillerMaterial,
        int backSlot,
        Material backMaterial,
        String backName
) {
}
