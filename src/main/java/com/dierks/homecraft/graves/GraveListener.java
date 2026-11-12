package com.dierks.homecraft.graves;

import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
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
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;

/**
 * Everything the world does to or with a grave (HCM-GRAVES §1, §3, §4).
 *
 * <p><b>The death, at MONITOR</b> ({@link #onDeath}). Where it sits among HCM's own death listeners:
 * <ul>
 *   <li>LOWEST: {@code KitGuardListener.onDeath} cancels a death inside a game session and revives the player;
 *       {@code TradeListener} ends any trade. A cancelled death makes no grave ({@code ignoreCancelled}). Also
 *       {@link #onDeathKeep}: a player with {@code hcm.graves.keep} keeps everything, so everything after sees a
 *       keep-inventory death with nothing dropped (never for a game session's death).</li>
 *   <li>LOW: {@code PrizeSafetyListener.onDeath} moves prizes to the items to keep.</li>
 *   <li>NORMAL: {@code CourierListener.onDeath} keeps courier packages when {@code courier.package.keep_on_death}.</li>
 *   <li>HIGHEST: {@code PrizeSafetyListener.onDeathGuard} takes out of the drops any prize that is kept as well.</li>
 *   <li>MONITOR: this, and {@code KitGuardListener.onDeathDone}, which only acts on a death inside a game session:
 *       this makes no grave for one, so the two never touch the same death and their order doesn't matter.</li>
 * </ul>
 * MONITOR is the one place guaranteed to run after every HIGHEST, HCM's and every other plugin's, which is why grave
 * plugins (GravesX among them) build there. Changing the drops at MONITOR is otherwise frowned on; here it is the
 * point, and nothing after it reads them but the server dropping what is left.
 *
 * <p><b>Protection.</b> A grave's head is never broken, blown up (creepers, TNT, beds, withers; removed from the
 * explosion's list at LOWEST, so Towny's explosion regen never records it either), pushed or pulled by a piston,
 * washed out by water or lava, changed by a mob or a falling block, or grown over by a tree. Hoppers have nothing to
 * take: a head has no inventory and every stack is in the database. An admin moves or removes one with
 * {@code /hcm graves}, not a pickaxe.
 */
public final class GraveListener implements Listener {

    private final GraveService graves;

    public GraveListener(GraveService graves) {
        this.graves = graves;
    }

    // ---- the death ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        graves.onDeath(event);
    }

    /**
     * {@code hcm.graves.keep} (a child who should simply keep everything): at LOWEST the death becomes a keep-inventory
     * death with nothing dropped, before any other HCM death listener reads it, so prizes and courier packages just stay
     * in the bag and no grave is made. A death in a game session is left to the Games.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDeathKeep(PlayerDeathEvent event) {
        graves.keepIfAllowed(event);
    }

    /** The compass to the grave the death made, once the player is back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(com.destroystokyo.paper.event.player.PlayerPostRespawnEvent event) {
        graves.respawned(event.getPlayer());
    }

    /** A grave compass whose grave is closed crumbles in any container a player opens. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        graves.crumble(event.getInventory());
    }

    /** A grave compass is only a pointer: nothing is crafted from it (a map, a recovery compass). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        for (org.bukkit.inventory.ItemStack in : event.getInventory().getMatrix()) {
            if (GraveCompass.is(in)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ---- using it -------------------------------------------------------------------------------------

    /**
     * A right-click on the head: open it. LOWEST and regardless of a cancel, so a claim or region where the player
     * can't build never keeps them from their own things; then cancelled, so nothing else acts on the click.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Grave g = graves.at(event.getClickedBlock());
        if (g == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // the client repeats the click for the off hand: only the main hand counts
        }
        graves.use(event.getPlayer(), g, event.getPlayer().isSneaking());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        graves.tellNotices(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        graves.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        graves.chunkLoaded(event.getChunk());
    }

    // ---- protection ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        Grave g = graves.at(event.getBlock());
        if (g == null) {
            return;
        }
        event.setCancelled(true);
        boolean admin = event.getPlayer().hasPermission(GraveService.ADMIN);
        event.getPlayer().sendMessage(Text.of("&7This is &f" + g.ownerName() + "&7's grave: right-click it to open it."
                + (admin ? " &8(/hcm graves remove " + g.id() + " takes it away)" : "")));
        Sounds.refused(event.getPlayer());
    }

    /** Nothing placed into a grave's block (a bucket's water, a block against the head's cell). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (graves.isGrave(event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (graves.isGrave(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(graves::isGrave);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(graves::isGrave);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (touches(event.getBlocks(), event.getBlock(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (touches(event.getBlocks(), event.getBlock(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /** A piston that would move or destroy a grave: any block it moves, or the cell its arm goes into. */
    private boolean touches(List<Block> moved, Block piston, BlockFace direction) {
        if (graves.isGrave(piston.getRelative(direction))) {
            return true;
        }
        for (Block b : moved) {
            if (graves.isGrave(b) || graves.isGrave(b.getRelative(direction))) {
                return true;
            }
        }
        return false;
    }

    /** Water and lava never wash it out. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFlow(BlockFromToEvent event) {
        if (graves.isGrave(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    /** Withers, endermen, ravagers, falling blocks landing on it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (graves.isGrave(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** A tree or a mushroom growing into it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onGrow(StructureGrowEvent event) {
        List<BlockState> blocks = event.getBlocks();
        blocks.removeIf(s -> graves.isGrave(s.getBlock()));
    }
}
