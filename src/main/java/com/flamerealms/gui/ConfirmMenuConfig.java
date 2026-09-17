package com.flamerealms.gui;

import org.bukkit.Material;

/**
 * Parsed binding of {@code gui.yml}'s {@code confirm-menu:} block — a
 * generic "are you sure?" screen with one confirm button and one cancel
 * button, consumed by {@link ConfirmMenu}.
 *
 * @param title           unresolved MiniMessage template for the inventory title
 * @param size            the inventory size, a multiple of 9 between 9 and 54
 * @param fillerMaterial  material used to pad every slot besides the confirm/cancel buttons
 * @param confirmSlot     slot of the confirm button, {@code 0} to {@code size - 1}
 * @param confirmMaterial material of the confirm button
 * @param confirmName     unresolved MiniMessage template for the confirm button's name
 * @param cancelSlot      slot of the cancel button, {@code 0} to {@code size - 1}
 * @param cancelMaterial  material of the cancel button
 * @param cancelName      unresolved MiniMessage template for the cancel button's name
 */
public record ConfirmMenuConfig(
        String title,
        int size,
        Material fillerMaterial,
        int confirmSlot,
        Material confirmMaterial,
        String confirmName,
        int cancelSlot,
        Material cancelMaterial,
        String cancelName
) {
}
