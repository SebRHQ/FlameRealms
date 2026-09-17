package com.flamerealms.gui;

import net.kyori.adventure.text.Component;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A generic "are you sure?" screen built entirely from {@code gui.yml}'s
 * {@code confirm-menu} section: one confirm button, one cancel button, the
 * rest filled. Knows nothing about what confirming or cancelling actually
 * does — that's supplied by the caller as plain {@link Runnable}s.
 *
 * <p>Neither button closes the inventory on its own — the {@code onConfirm}/
 * {@code onCancel} callback decides that. Most callers will simply call
 * {@code player.closeInventory()} as the first line of their callback, but
 * that's the caller's job, not this class's; the underlying action's own
 * follow-up message is often enough on its own without an extra close.
 */
public final class ConfirmMenu extends ChestGui {

    private ConfirmMenu(Component title, int size) {
        super(title, size);
    }

    /** Same as {@link #create(GuiConfig, List, Runnable, Runnable)} with no extra confirm-item lore. */
    public static ConfirmMenu create(GuiConfig config, Runnable onConfirm, Runnable onCancel) {
        return create(config, List.of(), onConfirm, onCancel);
    }

    /**
     * @param extraConfirmLore extra MiniMessage lore lines appended under the confirm
     *                         button's configured name — e.g. a later, realm-aware caller
     *                         might pass {@code List.of("<gray>Disband MyRealm?</gray>")} to
     *                         make the confirm screen self-explanatory; pass an empty list for
     *                         just the plain configured button
     */
    public static ConfirmMenu create(
            GuiConfig config, List<String> extraConfirmLore, Runnable onConfirm, Runnable onCancel) {
        ConfirmMenuConfig menuConfig = config.confirmMenu();
        ConfirmMenu menu = new ConfirmMenu(render(menuConfig.title()), menuConfig.size());

        ItemStack confirmItem = menu.buildItem(menuConfig.confirmMaterial(), menuConfig.confirmName(), extraConfirmLore);
        menu.setItem(menuConfig.confirmSlot(), confirmItem, onConfirm);

        ItemStack cancelItem = menu.buildItem(menuConfig.cancelMaterial(), menuConfig.cancelName(), List.of());
        menu.setItem(menuConfig.cancelSlot(), cancelItem, onCancel);

        menu.fillRemaining(menuConfig.fillerMaterial());
        return menu;
    }
}
