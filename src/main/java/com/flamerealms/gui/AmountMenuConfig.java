package com.flamerealms.gui;

import org.bukkit.Material;

import java.util.List;

/**
 * Parsed binding of {@code gui.yml}'s {@code amount-menu:} block — a generic
 * "pick an amount of money" screen with one button per preset plus a
 * "type a custom amount in chat" button, consumed by {@link AmountMenu}.
 *
 * @param title           unresolved MiniMessage template for the inventory title
 * @param size            the inventory size, a multiple of 9 between 9 and 54
 * @param fillerMaterial  material used to pad every slot not otherwise occupied
 * @param presetMaterial  material shared by every preset button
 * @param presetName      unresolved MiniMessage template for a preset button's name,
 *                        containing the {@code <amount>} placeholder substituted at render
 *                        time with that preset's formatted {@code Money} value
 * @param presetsCents    one preset amount per button, in cents, in display order
 * @param customSlot      slot of the "type a custom amount" button, {@code 0} to {@code size - 1}
 * @param customMaterial  material of the custom-amount button
 * @param customName      unresolved MiniMessage template for the custom-amount button's name
 * @param cancelSlot      slot of the cancel button, {@code 0} to {@code size - 1}
 * @param cancelMaterial  material of the cancel button
 * @param cancelName      unresolved MiniMessage template for the cancel button's name
 */
public record AmountMenuConfig(
        String title,
        int size,
        Material fillerMaterial,
        Material presetMaterial,
        String presetName,
        List<Long> presetsCents,
        int customSlot,
        Material customMaterial,
        String customName,
        int cancelSlot,
        Material cancelMaterial,
        String cancelName
) {
}
