package com.flamerealms.gui;

import com.flamerealms.domain.Money;
import com.flamerealms.util.InvalidAmountException;
import com.flamerealms.util.MoneyParsing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Consumer;

/**
 * A generic "pick an amount of money" screen built from {@code gui.yml}'s
 * {@code amount-menu} section: one button per configured preset (in cents),
 * plus a "type a custom amount in chat" button. Knows nothing about what the
 * chosen {@link Money} is actually used for (a deposit, a withdrawal, a
 * future purchase flow, ...) — that's entirely {@code onAmountChosen}'s
 * business.
 *
 * <p><b>Simplification, called out explicitly for a later stage to
 * upgrade:</b> the custom-amount chat flow's invalid-input reply is a single
 * plain, hardcoded MiniMessage error ({@code "<red>Invalid amount, please
 * try again.</red>"}) for every {@link InvalidAmountException} message key,
 * rather than routing through {@code com.flamerealms.config.Messages} (whose
 * four {@code amount-*} keys already exist and say something more specific
 * per failure). This package deliberately has no dependency on {@code
 * Messages} — adding one, or passing a {@code Messages} instance in, is left
 * to whichever later stage wires this menu into an actual command flow.
 */
public final class AmountMenu extends ChestGui {

    private AmountMenu(Component title, int size) {
        super(title, size);
    }

    /**
     * @param plugin           used only to schedule the main-thread hop {@link ChatInputService}
     *                         needs after an async chat reply
     * @param chatInputService captures the player's next chat message for the custom-amount flow
     * @param player           the player this menu is being built for — needed to close their
     *                         inventory and to prompt/read their chat input; a new {@link
     *                         AmountMenu} instance is expected per player/open, same as every
     *                         other {@link ChestGui}
     * @param onAmountChosen   invoked (back on the main thread) with the chosen amount, whether
     *                         from a preset button or a validated custom chat reply
     * @param onCancel         invoked after the inventory is closed when the cancel button is clicked
     */
    public static AmountMenu create(
            Plugin plugin,
            GuiConfig config,
            ChatInputService chatInputService,
            Player player,
            Consumer<Money> onAmountChosen,
            Runnable onCancel
    ) {
        AmountMenuConfig menuConfig = config.amountMenu();
        AmountMenu menu = new AmountMenu(render(menuConfig.title()), menuConfig.size());

        int slot = 0;
        for (long presetCents : menuConfig.presetsCents()) {
            while (slot < menuConfig.size() && (slot == menuConfig.customSlot() || slot == menuConfig.cancelSlot())) {
                slot++;
            }
            if (slot >= menuConfig.size()) {
                // Ran out of room for the remaining presets — an admin who
                // lists more presets than the menu has free slots for simply
                // doesn't get the rest rendered; not a crash.
                break;
            }

            Component presetName = render(
                    menuConfig.presetName(), Placeholder.unparsed("amount", Money.ofCents(presetCents).toString()));
            ItemStack presetItem = menu.buildItem(menuConfig.presetMaterial(), presetName, List.of());
            long chosenCents = presetCents;
            menu.setItem(slot, presetItem, () -> {
                player.closeInventory();
                onAmountChosen.accept(Money.ofCents(chosenCents));
            });
            slot++;
        }

        ItemStack customItem = menu.buildItem(menuConfig.customMaterial(), menuConfig.customName(), List.of());
        menu.setItem(menuConfig.customSlot(), customItem, () -> {
            player.closeInventory();
            player.sendMessage(render("<yellow>Type an amount in chat.</yellow>"));
            chatInputService.prompt(player, raw -> {
                long cents;
                try {
                    cents = MoneyParsing.parseAmountToCents(raw);
                } catch (InvalidAmountException e) {
                    // See class Javadoc: a simplified, non-Messages-routed error.
                    player.sendMessage(render("<red>Invalid amount, please try again.</red>"));
                    return;
                }
                onAmountChosen.accept(Money.ofCents(cents));
            });
        });

        ItemStack cancelItem = menu.buildItem(menuConfig.cancelMaterial(), menuConfig.cancelName(), List.of());
        menu.setItem(menuConfig.cancelSlot(), cancelItem, () -> {
            player.closeInventory();
            onCancel.run();
        });

        menu.fillRemaining(menuConfig.fillerMaterial());
        return menu;
    }
}
