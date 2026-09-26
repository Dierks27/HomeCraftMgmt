package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.PackRevealMenu;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.PackService;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Opens a sealed Card Pack when the player right-clicks it in hand: rolls the pack's Cards
 * (cap-aware, no charge — the pack was already paid for) into the reveal and consumes one pack
 * item. A sold-out pack is left sealed; one that comes up short refunds the missing share of what
 * the item says was paid for it.
 */
public final class PackItemListener implements Listener {

    private final HomeCraftManagement plugin;

    public PackItemListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack held = event.getItem();
        String packId = plugin.packs().packItems().packIdOf(held);
        if (packId == null) {
            return;
        }
        // If they're clicking one of our custom blocks (Printer, Store, …), let that
        // block's handler win instead of opening the pack.
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && plugin.blockService().at(event.getClickedBlock().getLocation()).isPresent()) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        Pack.PackDef def = plugin.packs().pack(packId);
        if (def == null) {
            player.sendMessage(Text.of("&cThis pack type no longer exists."));
            return;
        }
        int cards = plugin.packs().packItems().legacyCardCount(held);
        PackService.OpenResult r = plugin.packs().open(player, packId, plugin.packs().packItems().paidOf(held), cards);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            return;
        }
        held.setAmount(held.getAmount() - 1); // consume one pack
        if (r.refund() != null) {
            int total = cards > 0 ? cards : def.cardCount();
            int shortBy = total - r.cardIds().size();
            player.sendMessage(Text.of("&eThis pack could only fill " + r.cardIds().size() + " of its "
                    + total + " Cards, so you got &6" + r.refund() + " &eback for the "
                    + (shortBy == 1 ? "missing one" : shortBy + " missing ones") + "."));
        }
        new PackRevealMenu(plugin, player, def.displayName(), r.cardIds(), null).open(player);
    }
}
