package com.flamerealms.gui;

import org.bukkit.Material;

import java.util.List;

/**
 * One clickable (or purely decorative) item inside a {@link ChestGui}: the
 * slot it occupies, the {@link Material} rendered there, and its MiniMessage
 * name/lore templates (not yet deserialized — see {@link
 * ChestGui#buildItem(GuiItemConfig)}).
 *
 * <p>Purely a data carrier. This package knows nothing about what clicking
 * one of these should actually do; that behavior is supplied separately, as
 * a plain {@code Runnable}, by whatever concrete menu wires an action to
 * this item's slot (see {@link ConfirmMenu#create}/{@link AmountMenu#create}
 * for the pattern a later, realm-aware stage will follow for the main menu).
 *
 * @param slot     the inventory slot this item occupies, {@code 0} to
 *                 {@code size - 1} of whichever menu it belongs to
 * @param material the item's material
 * @param name     an unresolved MiniMessage template for the item's display name
 * @param lore     unresolved MiniMessage templates, one per lore line, in order
 */
public record GuiItemConfig(int slot, Material material, String name, List<String> lore) {
}
