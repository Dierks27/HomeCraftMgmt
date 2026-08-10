package com.dierks.homecraft.crafting;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Drives the Mini Workbench GUI: keeps the input grid editable, protects the
 * result/filler slots, recomputes the PC preview after every change, and
 * performs the craft when the player takes the result. On close, any items left
 * in the input grid are returned to the player so nothing is lost.
 */
public final class WorkbenchListener implements Listener {

    private final HomeCraftManagement plugin;
    private final RecipeManager recipes;

    public WorkbenchListener(HomeCraftManagement plugin, RecipeManager recipes) {
        this.plugin = plugin;
        this.recipes = recipes;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WorkbenchHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Simplify the interaction model: no shift/double/number moves — they can
        // shuffle items into protected slots or duplicate the preview.
        ClickType click = event.getClick();
        if (click.isShiftClick() || click == ClickType.DOUBLE_CLICK || click == ClickType.NUMBER_KEY
                || click == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        boolean topInventory = raw < event.getInventory().getSize();

        if (topInventory) {
            if (WorkbenchHolder.isInput(raw)) {
                recomputeLater(holder, player);           // allow the edit, then refresh preview
            } else if (raw == WorkbenchHolder.RESULT_SLOT) {
                event.setCancelled(true);
                tryCraft(holder, player);
            } else {
                event.setCancelled(true);                 // filler / arrow: locked
            }
        } else {
            // Clicks in the player's own inventory are fine; refresh in case an
            // item was picked up onto the cursor for placement.
            recomputeLater(holder, player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof WorkbenchHolder holder)) {
            return;
        }
        int topSize = event.getInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && !WorkbenchHolder.isInput(raw)) {
                event.setCancelled(true);                 // dragged onto a protected top slot
                return;
            }
        }
        if (event.getWhoClicked() instanceof Player player) {
            recomputeLater(holder, player);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof WorkbenchHolder)) {
            return;
        }
        HumanEntity who = event.getPlayer();
        Inventory inv = event.getInventory();
        for (int slot : WorkbenchHolder.INPUT_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                Map<Integer, ItemStack> leftover = who.getInventory().addItem(item);
                leftover.values().forEach(drop -> who.getWorld().dropItemNaturally(who.getLocation(), drop));
                inv.setItem(slot, null);
            }
        }
    }

    // ---------------------------------------------------------------------

    private void recomputeLater(WorkbenchHolder holder, Player player) {
        // The click/drag hasn't been applied to the inventory yet; read next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> recompute(holder, player));
    }

    private void recompute(WorkbenchHolder holder, Player player) {
        Inventory inv = holder.getInventory();
        RecipeManager.CraftAttempt attempt = recipes.matchPc(readGrid(inv));
        boolean canCraft = attempt != null && player.hasPermission("hcm.pc.craft");
        inv.setItem(WorkbenchHolder.RESULT_SLOT, canCraft ? attempt.result() : null);
    }

    private void tryCraft(WorkbenchHolder holder, Player player) {
        Inventory inv = holder.getInventory();
        RecipeManager.CraftAttempt attempt = recipes.matchPc(readGrid(inv));
        if (attempt == null) {
            return;
        }
        if (!player.hasPermission("hcm.pc.craft")) {
            player.sendMessage(Text.of("&cYou don't have permission to craft that.")
                    .color(NamedTextColor.RED));
            return;
        }

        // Consume ingredients.
        for (int i = 0; i < WorkbenchHolder.INPUT_SLOTS.length; i++) {
            int take = attempt.consume()[i];
            if (take <= 0) {
                continue;
            }
            int slot = WorkbenchHolder.INPUT_SLOTS[i];
            ItemStack cell = inv.getItem(slot);
            if (cell == null) {
                continue;
            }
            int left = cell.getAmount() - take;
            if (left <= 0) {
                inv.setItem(slot, null);
            } else {
                cell.setAmount(left);
                inv.setItem(slot, cell);
            }
        }

        // Give the result.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(attempt.result());
        leftover.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
        player.sendMessage(Text.of("&aCrafted a Personal Computer."));

        recompute(holder, player);
    }

    private ItemStack[] readGrid(Inventory inv) {
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < WorkbenchHolder.INPUT_SLOTS.length; i++) {
            grid[i] = inv.getItem(WorkbenchHolder.INPUT_SLOTS[i]);
        }
        return grid;
    }
}
