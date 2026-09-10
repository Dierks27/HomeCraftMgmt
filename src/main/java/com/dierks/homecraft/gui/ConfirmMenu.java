package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable "are you sure?" confirmation. Shows the item being purchased in the
 * centre with a green ✓ (confirm) and a red ✗ (cancel) on either side, so no
 * purchase — and no Vault charge — ever happens on a single stray click. The
 * confirm/cancel lore can carry the price and the buyer's balance so the decision
 * is fully informed.
 */
public final class ConfirmMenu extends Menu {

    private final String title;
    private final ItemStack display;
    private final List<String> confirmLore;
    private final String confirmHeadline;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmMenu(HomeCraftManagement plugin, String title, ItemStack display,
                       List<String> confirmLore, Runnable onConfirm, Runnable onCancel) {
        this(plugin, title, display, confirmLore, "&7Click to confirm this purchase.",
                onConfirm, onCancel);
    }

    /**
     * As above, but with the confirm button's leading line supplied by the caller — a
     * removal screen is not a purchase and must not claim to be one.
     */
    public ConfirmMenu(HomeCraftManagement plugin, String title, ItemStack display,
                       List<String> confirmLore, String confirmHeadline,
                       Runnable onConfirm, Runnable onCancel) {
        super(plugin);
        this.title = title;
        this.display = display;
        this.confirmLore = confirmLore == null ? List.of() : confirmLore;
        this.confirmHeadline = confirmHeadline == null ? "&7Click to confirm." : confirmHeadline;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        init(27, Text.of(title));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }

        // Button order is fixed across every confirm screen: Cancel (✗) on the
        // LEFT, Confirm (✓) on the RIGHT.
        set(11, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c&l✗ Cancel",
                "&7No charge — go back."), e -> {
            if (onCancel != null) {
                onCancel.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });

        set(13, display, null);

        List<String> confirm = new ArrayList<>();
        confirm.add(confirmHeadline);
        confirm.addAll(confirmLore);
        set(15, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Confirm",
                confirm.toArray(new String[0])), e -> {
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
    }
}
