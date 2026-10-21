package com.dierks.homecraft.block;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.PlacedPrizes;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
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
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wires placed custom blocks into the world: places (with build-permission
 * checks + persistence), breaks (returns the correct custom item + cleans up),
 * right-click opens the matching GUI, and explosions, pistons and flowing water
 * or lava leave our blocks (and placed prize heads) intact.
 *
 * <p>A Vending Machine, Display Case or Pallet is broken only by whoever placed it
 * or an admin, and what it holds goes back to whoever stocked it, into their
 * Mailbox, once the break is final (0.37.3, {@link CustomBlockGuard}).
 */
public final class CustomBlockListener implements Listener {

    private final HomeCraftManagement plugin;
    private final PluginConfig config;
    private final CustomBlockService blocks;
    private final CustomItems items;
    private final ProtectionService protection;
    /** The day each player's tap that arrived cancelled was last logged ({@link #cancelledTap}); main thread. */
    private final Map<UUID, LocalDate> cancelledTapLogged = new HashMap<>();

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

        if (type == CustomBlockType.SOUND_MUFFLER && !player.hasPermission("hcm.muffler.use")) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cYou can't place a Sound Muffler."));
            return;
        }

        // The Sound Muffler isn't part of the economy — quiet is welcome in every world — so it
        // alone skips the economy-world check. The Arcade Machine also stands wherever the Games
        // can be played (games.play_worlds — the hub), because that is where it's wanted.
        if (type != CustomBlockType.SOUND_MUFFLER && !arcadeInGamesWorld(type, player)
                && !plugin.sandbox().check(player, "place " + type.name().toLowerCase(java.util.Locale.ROOT))) {
            event.setCancelled(true);
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
        if (type == CustomBlockType.MINI_VENDING_MACHINE && plugin.shops() != null) {
            plugin.shops().register(block.getLocation()); // the upper half is a floating display
        }
        if (type == CustomBlockType.MAILBOX) {
            MailboxVariant variant = items.mailboxVariant(inHand);
            blocks.tagMailboxVariant(block, variant == null ? MailboxVariant.WOOD : variant);
        }
        if (type == CustomBlockType.DISPLAY_CASE) {
            DisplayCaseVariant variant = items.displayCaseVariant(inHand);
            blocks.tagDisplayVariant(block, variant == null ? DisplayCaseVariant.PLAIN : variant);
        }
        player.sendMessage(Text.of("&aPlaced a " + friendly(type) + "."));
        if (type == CustomBlockType.SOUND_MUFFLER && plugin.soundMufflers() != null) {
            // Placing one opens its menu straight away (on the next tick, once the block is
            // really there): the first thing anyone wants is to pick what to hush.
            plugin.soundMufflers().placed(block, player.getUniqueId(), inHand);
            Location at = block.getLocation();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // Another plugin may have cancelled the placement after us; the 30-second sweep
                // forgets that muffler, and there is no block to show a menu for.
                if (player.isOnline() && plugin.soundMufflers() != null && plugin.soundMufflers().at(at) != null
                        && !at.getBlock().getType().isAir()) {
                    new com.dierks.homecraft.gui.muffler.MufflerMenu(plugin, player,
                            com.dierks.homecraft.muffler.SoundMufflerService.posOf(at)).open(player);
                }
            });
        }
        if (type == CustomBlockType.PC && plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_pc");
        }
    }

    /**
     * A break only decides here (HIGH): a Vending Machine, Display Case or Pallet is refused to anyone but whoever
     * placed it or an admin ({@link CustomBlockGuard#breakRefusal}), then the town check as before. Nothing changes
     * yet: everything a break does happens in {@link #onBreakFinal}, once no other plugin can cancel it (0.37.3,
     * spec §4.15 (1)). Acting here left a cancelled break standing with its record gone and its item already
     * dropped, so breaking it again dropped a second one.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        // Breaking the upper head of a Vending Machine breaks the whole machine.
        Block base = blocks.vendingLowerOf(broken).orElse(broken);
        Location loc = base.getLocation();
        Optional<PlacedBlock> placed = blocks.at(loc);
        if (placed.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        PlacedBlock record = placed.get();
        boolean isOwner = record.owner().equals(player.getUniqueId());

        if (CustomBlockGuard.breakRefusal(record.type(), isOwner, player.hasPermission("hcm.admin"))) {
            event.setCancelled(true);
            player.sendMessage(Text.of(CustomBlockGuard.refusalLine(ownerName(record.owner()), record.type())));
            return;
        }

        if (config.respectTownPerms() && !isOwner && !protection.canBuild(player, loc)) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cYou can't break this here."));
        }
    }

    /**
     * The break is final (MONITOR, {@code ignoreCancelled}: a break any plugin cancelled never gets here). The stock
     * goes back to whoever stocked it, always into their Mailbox ({@code returnStock}); then the record, a muffler,
     * the shop display and the vanilla drop go, and the block's own item drops, exactly one. If the stock can't be
     * written the break is called off after all, the one change made here, so the block stays with everything in
     * it and nothing is lost or dropped.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakFinal(BlockBreakEvent event) {
        Block broken = event.getBlock();
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

        // The item to drop is resolved BEFORE the tile goes away (variants live on it).
        ItemStack drop = switch (record.type()) {
            case MAILBOX -> items.mailbox(blocks.mailboxVariantAt(base));
            case DISPLAY_CASE -> items.displayCase(blocks.displayVariantAt(base));
            // A muffler that was set up drops an item that remembers its settings.
            case SOUND_MUFFLER -> plugin.soundMufflers() != null
                    ? plugin.soundMufflers().itemFor(plugin.soundMufflers().at(loc))
                    : items.of(record.type());
            default -> items.of(record.type());
        };

        boolean done = CustomBlockGuard.finish(record.type(), new CustomBlockGuard.Finish() {
            @Override
            public boolean returnStock() {
                return CustomBlockListener.this.returnStock(record, loc, player);
            }

            @Override
            public void forget() {
                blocks.removeAt(loc);
                if (record.type() == CustomBlockType.SOUND_MUFFLER && plugin.soundMufflers() != null) {
                    plugin.soundMufflers().removed(loc);
                }
                event.setDropItems(false); // suppress the vanilla base-block drop
                if (record.type() == CustomBlockType.MINI_VENDING_MACHINE) {
                    // Remove the other half too — exactly one vending item drops either way.
                    blocks.removeVendingUpper(base); // legacy upper head block, if one is still there
                    if (plugin.shops() != null) {
                        plugin.shops().unregister(loc); // the floating display + glow + hologram
                    }
                    if (lowerOfUpper.isPresent()) {
                        base.setType(Material.AIR, false);
                    }
                }
            }

            @Override
            public void drop() {
                loc.getWorld().dropItemNaturally(loc.toCenterLocation(), drop);
            }
        });
        if (!done) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cCould not save - try again."));
        }
    }

    /**
     * Everything in a Vending Machine, Display Case or Pallet back to whoever stocked it, into their Mailbox, in one
     * transaction (the services tell each owner). An admin who broke someone else's is told where it went.
     *
     * @return false when it couldn't be written (nothing changed)
     */
    private boolean returnStock(PlacedBlock record, Location loc, Player breaker) {
        boolean someoneElses = !record.owner().equals(breaker.getUniqueId());
        try {
            if (record.type() == CustomBlockType.PALLET) {
                int back = plugin.pallets().returnStock(loc);
                if (someoneElses && back > 0) {
                    breaker.sendMessage(Text.of("&7Returned the Pallet's stock to its owner's Mailbox."));
                }
            } else {
                int back = plugin.vending().returnStock(loc, CustomBlockGuard.name(record.type())).size();
                if (someoneElses && back > 0) {
                    breaker.sendMessage(Text.of("&7Returned " + back + (back == 1 ? " Mini" : " Minis")
                            + " to their owners' Mailboxes."));
                }
            }
            return true;
        } catch (java.sql.SQLException e) {
            plugin.getLogger().severe("Couldn't return the stock of the " + CustomBlockGuard.name(record.type())
                    + " at " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + " in "
                    + loc.getWorld().getName() + ", so it stays standing: " + e.getMessage());
            return false;
        }
    }

    /** The name of a block's owner for a refusal line ("someone else" when the server doesn't know it). */
    private String ownerName(UUID owner) {
        String name = plugin.getServer().getOfflinePlayer(owner).getName();
        return name == null || name.isBlank() ? "someone else" : name;
    }

    /**
     * Right-click opens the block's screen. It listens without {@code ignoreCancelled}, as join signs do: a tap
     * on the Arcade Machine (or one of its retired machines) can arrive already cancelled, by vanilla spawn
     * protection or a protection plugin's "use" rule, and both spare ops, so a player who isn't op tapped and
     * nothing happened. Those open anyway ({@link #opensWhenCancelled}), still behind the economy sandbox and
     * the build-rights check below; a cancelled tap on any other block, or by a player in a world game (the
     * session's own guard says no to every block, and its screens), is left alone exactly as before.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        // what isCancelled() and ignoreCancelled read for this event, before we deny it ourselves
        boolean arrivedCancelled = event.useInteractedBlock() == Event.Result.DENY;
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
        CustomBlockType type = placed.get().type();
        if (arrivedCancelled && !opensWhenCancelled(type, inGameSession(event.getPlayer()))) {
            return; // as with ignoreCancelled: someone else said no, and this block doesn't open anyway
        }

        // It's one of ours — never run the vanilla interaction (crafter GUI, etc.).
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);

        Player player = event.getPlayer();

        if (type != CustomBlockType.SOUND_MUFFLER && !arcadeInGamesWorld(type, player)
                && !plugin.sandbox().check(player, "use " + type.name().toLowerCase(java.util.Locale.ROOT))) {
            return; // "the economy is disabled in this world" — the block stays, its GUI doesn't open
        }

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
                // The PC is a browser, and its root screen is the Site launcher (§2.2) —
                // not the store. The store is one Site on it.
                com.dierks.homecraft.gui.SiteLauncherMenu.open(plugin, player);
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
            // The retired machine blocks still work where they stand: each opens the hub screen it
            // used to be (a crate, the hub for the Scratch Ticket, the Minis tab for the Rare Card,
            // the Wallet), with Back going to the hub.
            case CRATE_MACHINE -> com.dierks.homecraft.gui.arcade.ArcadeScreens.crate(plugin, player);
            case SCRATCH_BOOTH -> new com.dierks.homecraft.gui.arcade.ArcadeMenu(plugin, player).open(player);
            case PITY_KIOSK -> com.dierks.homecraft.gui.arcade.ArcadeScreens.minisTab(plugin, player);
            case TOKEN_COUNTER -> com.dierks.homecraft.gui.arcade.ArcadeScreens.wallet(plugin, player);
            case PALLET -> {
                boolean owner = placed.get().owner().equals(player.getUniqueId()) || player.hasPermission("hcm.admin");
                new com.dierks.homecraft.gui.marketplace.PalletMenu(
                        plugin, player, clicked.getLocation(), owner).open(player);
            }
            // Anyone may look at what a muffler hushes; only its owner (or an admin) may change it.
            case SOUND_MUFFLER -> {
                if (plugin.soundMufflers() == null) {
                    return;
                }
                if (plugin.soundMufflers().at(clicked.getLocation()) == null) {
                    // A placed_blocks row with no muffler behind it (the service failed to load):
                    // re-register it so the block isn't dead.
                    plugin.soundMufflers().placed(clicked, placed.get().owner(), null);
                }
                new com.dierks.homecraft.gui.muffler.MufflerMenu(plugin, player,
                        com.dierks.homecraft.muffler.SoundMufflerService.posOf(clicked.getLocation())).open(player);
            }
        }
        if (arrivedCancelled) {
            cancelledTap(player, type, clicked); // after the switch: only a screen that really opened is logged
        }
    }

    /**
     * Whether a tap on {@code type} that arrived already cancelled still opens it: the Arcade family (the Arcade
     * Machine and its retired machines: the crate, the Scratch-Ticket booth, the Pity Exchange, the Token
     * Counter). They only open a screen, and the screen asks for whatever it spends. Every other block keeps
     * the old rule: a cancelled tap does nothing.
     */
    static boolean opensWhenCancelled(CustomBlockType type) {
        return switch (type) {
            case ARCADE, CRATE_MACHINE, SCRATCH_BOOTH, PITY_KIOSK, TOKEN_COUNTER -> true;
            default -> false;
        };
    }

    /**
     * {@link #opensWhenCancelled(CustomBlockType)}, and never for a player in a world game ({@code inGameSession}):
     * the session's guard denies every tap on a block and every screen that isn't the game's own, so the tap is
     * dropped silently, as it always was, rather than meeting the economy check's refusal or a screen that can't
     * open.
     */
    static boolean opensWhenCancelled(CustomBlockType type, boolean inGameSession) {
        return !inGameSession && opensWhenCancelled(type);
    }

    /** Whether {@code player} is in a world game (a session on its way in or out too). A lookup that fails is no. */
    private boolean inGameSession(Player player) {
        try {
            com.dierks.homecraft.games.GamesService games = plugin.games();
            return games != null && games.sessions().session(player) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * A tap that arrived cancelled, once the switch has run: when one of our screens really opened, one console
     * line a player a day says so. Nothing opened (something else closed or refused the screen): no line, and the
     * day's one line is kept for a tap that does open.
     */
    private void cancelledTap(Player player, CustomBlockType type, Block at) {
        try {
            if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu)) {
                return;
            }
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (today.equals(cancelledTapLogged.put(player.getUniqueId(), today))) {
            return;
        }
        plugin.getLogger().info(cancelledTapLine(player.getName(), type, at.getX(), at.getY(), at.getZ(),
                at.getWorld().getName()));
    }

    /**
     * The console line for a tap that arrived cancelled and opened anyway. It names no cause: spawn protection,
     * a protection plugin, a spectator or an item cooling down all arrive the same.
     */
    static String cancelledTapLine(String player, CustomBlockType type, int x, int y, int z, String world) {
        return "Arcade: " + player + "'s tap on " + type.name().toLowerCase(Locale.ROOT) + " at " + x + " " + y + " "
                + z + " in " + world + " arrived cancelled (by the server or another plugin); opened it";
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
            case SOUND_MUFFLER -> "Sound Muffler";
        };
    }

    // Keep custom blocks (and their data) safe from explosions rather than
    // leaving an orphaned DB row for a block that no longer exists.
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        CustomBlockGuard.spare(event.blockList(), this::isProtectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        CustomBlockGuard.spare(event.blockList(), this::isProtectedBlock);
    }

    /**
     * No piston moves or breaks one of ours (0.37.3): pushed, its record would point at the old spot; a head in the
     * way (a Display Case, a Vending Machine's top, a placed trophy) would pop off as a plain head. Refused at LOW,
     * before anything acts on a move that won't happen.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Block inFront = event.getBlock().getRelative(event.getDirection(), event.getBlocks().size() + 1);
        if (CustomBlockGuard.pistonRefused(CustomBlockGuard.pistonTouches(event.getBlocks(), inFront),
                this::isProtectedBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (CustomBlockGuard.pistonRefused(CustomBlockGuard.pistonTouches(event.getBlocks(), null),
                this::isProtectedBlock)) {
            event.setCancelled(true);
        }
    }

    /**
     * Water or lava never washes one of ours away (0.37.3). A flow into air or more fluid, nearly every flow there
     * is, looks nothing up.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (to.getType().isAir() || to.isLiquid()) {
            return;
        }
        if (CustomBlockGuard.flowRefused(to, this::isProtectedBlock)) {
            event.setCancelled(true);
        }
    }

    /**
     * One of ours: a placed prize head (a hat or a trophy, read from the skull, material first), a placed HomeCraft
     * block, or the upper head of a Vending Machine.
     */
    private boolean isProtectedBlock(Block b) {
        return PlacedPrizes.isPlaced(b) || blocks.at(b.getLocation()).isPresent()
                || blocks.vendingLowerOf(b).isPresent();
    }

    /**
     * An Arcade Machine in a world the Games may be played in ({@code games.play_worlds} or
     * {@code games.worlds}) while the Games are on: it places and opens there even if the economy
     * is off, so the hub can have one. The hub's crates and Scratch Ticket still ask the economy
     * sandbox themselves, so nothing that moves money opens outside an economy world.
     */
    private boolean arcadeInGamesWorld(CustomBlockType type, Player player) {
        if (type != CustomBlockType.ARCADE) {
            return false;
        }
        com.dierks.homecraft.games.GamesService games = plugin.games();
        return games != null && games.config().enabled() && games.gate().worldAllowed(player.getWorld());
    }
}
