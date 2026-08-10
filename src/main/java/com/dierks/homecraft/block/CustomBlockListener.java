package com.dierks.homecraft.block;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.AmazonHolder;
import com.dierks.homecraft.crafting.WorkbenchHolder;
import com.dierks.homecraft.integration.ProtectionService;
import com.dierks.homecraft.item.CustomItems;
import com.dierks.homecraft.storage.PlacedBlock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Wires placed custom blocks into the world: places (with build-permission
 * checks + persistence), breaks (returns the correct custom item + cleans up),
 * right-click opens the matching GUI, and explosions leave our blocks intact.
 */
public final class CustomBlockListener implements Listener {

    private final HomeCraftManagement plugin;
    private final PluginConfig config;
    private final CustomBlockService blocks;
    private final CustomItems items;
    private final ProtectionService protection;

    public CustomBlockListener(HomeCraftManagement plugin,
                               PluginConfig config,
                               CustomBlockService blocks,
                               CustomItems items,
                               ProtectionService protection) {
        this.plugin = plugin;
        this.config = config;
        this.blocks = blocks;
        this.items = items;
        this.protection = protection;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        CustomBlockType type = blocks.itemType(inHand);
        if (type == null) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();

        if (type == CustomBlockType.MINI_WORKBENCH && !player.hasPermission("hcm.workbench.place")) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cYou can't place a Mini Workbench."));
            return;
        }

        if (config.respectTownPerms() && !protection.canBuild(player, block.getLocation())) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cYou can't build here."));
            return;
        }

        blocks.recordPlacement(block, type, player.getUniqueId());
        player.sendMessage(Text.of(type == CustomBlockType.PC
                ? "&aPlaced a Personal Computer."
                : "&aPlaced a Mini Workbench."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        Optional<PlacedBlock> placed = blocks.at(loc);
        if (placed.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        PlacedBlock record = placed.get();
        boolean isOwner = record.owner().equals(player.getUniqueId());

        if (config.respectTownPerms() && !isOwner && !protection.canBuild(player, loc)) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cYou can't break this here."));
            return;
        }

        blocks.removeAt(loc);
        event.setDropItems(false); // suppress the vanilla base-block drop
        loc.getWorld().dropItemNaturally(loc.toCenterLocation(), items.of(record.type()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Optional<PlacedBlock> placed = blocks.at(clicked.getLocation());
        if (placed.isEmpty()) {
            return;
        }

        // It's one of ours — never run the vanilla interaction (crafter GUI, etc.).
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);

        Player player = event.getPlayer();
        CustomBlockType type = placed.get().type();

        if (config.respectTownPerms() && !protection.canBuild(player, clicked.getLocation())) {
            player.sendMessage(Text.of("&cYou can't use this here."));
            return;
        }

        switch (type) {
            case MINI_WORKBENCH -> {
                if (!player.hasPermission("hcm.workbench.use")) {
                    player.sendMessage(Text.of("&cYou can't use this workbench."));
                    return;
                }
                WorkbenchHolder.open(player);
            }
            case PC -> {
                if (!player.hasPermission("hcm.pc.use")) {
                    player.sendMessage(Text.of("&cYou can't use this PC."));
                    return;
                }
                AmazonHolder.open(player);
            }
        }
    }

    // Keep custom blocks (and their data) safe from explosions rather than
    // leaving an orphaned DB row for a block that no longer exists.
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> blocks.at(b.getLocation()).isPresent());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> blocks.at(b.getLocation()).isPresent());
    }
}
