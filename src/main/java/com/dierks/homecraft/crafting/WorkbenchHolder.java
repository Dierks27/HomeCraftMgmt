package com.dierks.homecraft.crafting;

import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The Mini Workbench crafting GUI: a 27-slot chest laid out as a 3x3 input grid
 * on the left, a result slot on the right, and locked filler elsewhere.
 *
 * <pre>
 *   . I I I . . . . .      I = input (grid)
 *   . I I I . > R . .      R = result   (slot 15)
 *   . I I I . . . . .      &gt; = arrow    (slot 14)
 * </pre>
 */
public final class WorkbenchHolder implements InventoryHolder {

    public static final int SIZE = 27;
    /** Input grid slots, row-major (index 0..8 of the 3x3 grid). */
    public static final int[] INPUT_SLOTS = {1, 2, 3, 10, 11, 12, 19, 20, 21};
    public static final int ARROW_SLOT = 14;
    public static final int RESULT_SLOT = 15;

    private final Inventory inventory;

    public WorkbenchHolder() {
        this.inventory = Bukkit.createInventory(this, SIZE, Text.of("&6Mini Workbench"));
        decorate();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static void open(Player player) {
        player.openInventory(new WorkbenchHolder().getInventory());
    }

    /** @return true if this raw slot is one of the editable input slots. */
    public static boolean isInput(int rawSlot) {
        for (int slot : INPUT_SLOTS) {
            if (slot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    private void decorate() {
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        boolean[] reserved = new boolean[SIZE];
        for (int slot : INPUT_SLOTS) {
            reserved[slot] = true;
        }
        reserved[RESULT_SLOT] = true;
        reserved[ARROW_SLOT] = true;
        for (int i = 0; i < SIZE; i++) {
            if (!reserved[i]) {
                inventory.setItem(i, filler);
            }
        }
        inventory.setItem(ARROW_SLOT, named(Material.ARROW, "&7Result »"));
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            item.setItemMeta(meta);
        }
        return item;
    }
}
