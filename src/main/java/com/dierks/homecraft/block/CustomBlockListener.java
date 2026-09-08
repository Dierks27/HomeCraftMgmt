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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

        // A Vending Machine is two blocks tall: the head above is placed automatically,
        // so the space must be free (and buildable) or the placement is refused.
        if (type == CustomBlockType.MINI_VENDING_MACHINE) {
            Block above = block.getRelative(BlockFace.UP);
            if (above.getY() >= block.getWorld().getMaxHeight() || !above.getType().isAir()) {
                event.setCancelled(true);
                player.sendMessage(Text.of("&cA Vending Machine is two blocks tall — the block above must be empty."));
                return;
            }
            if (config.respectTownPerms() && !protection.canBuild(player, above.getLocation())) {
                event.setCancelled(true);
                player.sendMessage(Text.of("&cYou can't build here (the space above is protected)."));
                return;
            }
        }

        blocks.recordPlacement(block, type, player.getUniqueId());
        if (type == CustomBlockType.MINI_VENDING_MACHINE) {
            blocks.placeVendingUpper(block);
        }
        if (type == CustomBlockType.MAILBOX) {
            MailboxVariant variant = items.mailboxVariant(inHand);
            blocks.tagMailboxVariant(block, variant == null ? MailboxVariant.WOOD : variant);
        }
        player.sendMessage(Text.of("&aPlaced a " + friendly(type) + "."));
        if (type == CustomBlockType.PC && plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_pc");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        // Breaking the upper head of a Vending Machine breaks the whole machine.
        Optional<Block> lowerOfUpper = blocks.vendingLowerOf(broken);
        Block base = lowerOfUpper.orElse(broken);
        Location loc = base.getLocation();
        Optional<PlacedBlock> placed = blocks.at(loc);
        if (placed.isEmpty()) {
            if (lowerOfUpper.isEmpty() && blocks.isVendingUpper(broken)) {
                // Orphaned companion head (its machine is gone) — never drop a head item.
                event.setDropItems(false);
            }
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

        // The item to drop is resolved BEFORE the tile goes away (the Mailbox variant lives on it).
        ItemStack drop = record.type() == CustomBlockType.MAILBOX
                ? items.mailbox(blocks.mailboxVariantAt(base))
                : items.of(record.type());

        blocks.removeAt(loc);
        event.setDropItems(false); // suppress the vanilla base-block drop
        if (record.type() == CustomBlockType.MINI_VENDING_MACHINE) {
            // Remove the other half too — exactly one vending item drops either way.
            blocks.removeVendingUpper(base);
            if (lowerOfUpper.isPresent()) {
                base.setType(Material.AIR, false);
            }
        }
        loc.getWorld().dropItemNaturally(loc.toCenterLocation(), drop);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clickedRaw = event.getClickedBlock();
        if (clickedRaw == null) {
            return;
        }
        // Right-clicking the upper head of a Vending Machine acts on the machine below.
        Block clicked = blocks.vendingLowerOf(clickedRaw).orElse(clickedRaw);
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
                // The Mini Workbench is retired (Phase 9) — Minis now come from Cards + a
                // Printer. Existing benches stay usable as a light craft station (the PC
                // recipe) so placements migrate gracefully rather than becoming dead blocks.
                if (!player.hasPermission("hcm.workbench.use")) {
                    player.sendMessage(Text.of("&cYou can't use this workbench."));
                    return;
                }
                player.sendMessage(Text.of("&7This Workbench is retired — craft a PC here, "
                        + "and print Minis from Cards at a &bPrinter&7."));
                WorkbenchHolder.open(player);
            }
            case PRINTER -> {
                if (!player.hasPermission("hcm.printer.use")) {
                    player.sendMessage(Text.of("&cYou can't use this Printer."));
                    return;
                }
                new com.dierks.homecraft.gui.mini.PrinterMenu(plugin, player, clicked.getLocation()).open(player);
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
            case PRINTER -> "Mini Printer";
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
        event.blockList().removeIf(this::isProtectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedBlock);
    }

    private boolean isProtectedBlock(Block b) {
        return blocks.at(b.getLocation()).isPresent() || blocks.vendingLowerOf(b).isPresent();
    }
}
