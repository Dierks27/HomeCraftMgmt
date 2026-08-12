package com.dierks.homecraft.block;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.StoreMenu;
import com.dierks.homecraft.gui.mini.AuctionMenu;
import com.dierks.homecraft.gui.mini.DisplayCaseMenu;
import com.dierks.homecraft.gui.mini.VendingMenu;
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
        player.sendMessage(Text.of("&aPlaced a " + friendly(type) + "."));
        if (type == CustomBlockType.PC && plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_pc");
        }
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

        // Return any Mini loaded into a Vending Machine / Display Case to the breaker.
        if (record.type() == CustomBlockType.MINI_VENDING_MACHINE
                || record.type() == CustomBlockType.DISPLAY_CASE) {
            plugin.vending().onBlockBroken(loc, player);
        }
        // Return all stock from a broken Pallet.
        if (record.type() == CustomBlockType.PALLET) {
            plugin.pallets().onBlockBroken(loc, player);
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
                new StoreMenu(plugin, player).open(player);
            }
            case MINI_VENDING_MACHINE -> {
                boolean owner = placed.get().owner().equals(player.getUniqueId()) || player.hasPermission("hcm.admin");
                new VendingMenu(plugin, player, clicked.getLocation(), owner).open(player);
            }
            case DISPLAY_CASE -> {
                boolean owner = placed.get().owner().equals(player.getUniqueId()) || player.hasPermission("hcm.admin");
                new DisplayCaseMenu(plugin, player, clicked.getLocation(), owner).open(player);
            }
            case AUCTION_HOUSE -> new AuctionMenu(plugin, player, null).open(player);
            case MAILBOX -> new com.dierks.homecraft.gui.MailboxMenu(plugin, player, null).open(player);
            case ARCADE -> new com.dierks.homecraft.gui.arcade.ArcadeMenu(plugin, player).open(player);
            case CRATE_MACHINE -> com.dierks.homecraft.gui.arcade.CratePickMenu.open(plugin, player);
            case SCRATCH_BOOTH -> new com.dierks.homecraft.gui.arcade.ScratchMenu(plugin, player).open(player);
            case PITY_KIOSK -> new com.dierks.homecraft.gui.arcade.PityMenu(plugin, player).open(player);
            case TOKEN_COUNTER -> new com.dierks.homecraft.gui.arcade.TokenCounterMenu(plugin, player).open(player);
            case PALLET -> {
                boolean owner = placed.get().owner().equals(player.getUniqueId()) || player.hasPermission("hcm.admin");
                new com.dierks.homecraft.gui.marketplace.PalletMenu(
                        plugin, player, clicked.getLocation(), owner).open(player);
            }
        }
    }

    /** Friendly display label for a placed-block type (for the placement message). */
    private String friendly(CustomBlockType type) {
        return switch (type) {
            case PC -> "Personal Computer";
            case MINI_WORKBENCH -> "Mini Workbench";
            case MINI_VENDING_MACHINE -> "Mini Vending Machine";
            case DISPLAY_CASE -> "Mini Display Case";
            case AUCTION_HOUSE -> "Mini Auction House";
            case MAILBOX -> "Mailbox";
            case PALLET -> "Pallet";
            case ARCADE -> "Arcade Machine";
            case CRATE_MACHINE -> "Crate Machine";
            case SCRATCH_BOOTH -> "Scratch-Ticket Booth";
            case PITY_KIOSK -> "Pity Exchange";
            case TOKEN_COUNTER -> "Token Counter";
        };
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
