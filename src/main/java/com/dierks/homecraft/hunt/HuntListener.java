package com.dierks.homecraft.hunt;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's side of the hunt.
 *
 * <p>Catching a wild Mini is a player touching or breaking its head ({@code MiniHeadListener}
 * routes both to {@link HuntService#claim}). Everything ELSE that takes the head away is an
 * escape: an explosion, a piston, water or lava. Those used to drop a plain textured head and
 * leave the spawn to retire its pre-minted copy on the next sweep; now the head is removed, the
 * escape is counted, and nothing is minted.
 *
 * <p>Also replays the hints so far to anybody who logs in mid-hunt.
 */
public final class HuntListener implements Listener {

    private final HomeCraftManagement plugin;

    public HuntListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        HuntService hunt = plugin.hunt();
        if (hunt == null || hunt.live().isEmpty()) {
            return;
        }
        // A beat after the join spam (streak token, mail), so the hints are not buried.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                hunt.replayHints(event.getPlayer());
            }
        }, 60L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        escapeAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        escapeAll(event.blockList());
    }

    /**
     * A head in a piston's path is not pushed but broken (its push reaction is DESTROY), and the
     * broken block is not in {@code getBlocks()} — that list is only what moves. It sits one past
     * the end of the moving line, or directly in front of the piston if nothing moves.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        HuntService hunt = plugin.hunt();
        if (hunt == null) {
            return;
        }
        BlockFace dir = event.getDirection();
        List<Block> candidates = new ArrayList<>(event.getBlocks());
        candidates.add(event.getBlock().getRelative(dir, event.getBlocks().size() + 1));
        for (Block b : candidates) {
            HuntService.Hunt h = hunt.isWild(b) ? hunt.at(b) : null;
            if (h != null) {
                hunt.escape(h, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        HuntService hunt = plugin.hunt();
        Block to = event.getToBlock();
        if (hunt == null || !hunt.isWild(to)) {
            return;
        }
        HuntService.Hunt h = hunt.at(to);
        if (h != null) {
            hunt.escape(h, true);
        }
    }

    /** Take wild heads out of an explosion (so no plain head drops) and let them escape. */
    private void escapeAll(List<Block> blocks) {
        HuntService hunt = plugin.hunt();
        if (hunt == null || hunt.live().isEmpty()) {
            return;
        }
        List<Block> wild = new ArrayList<>();
        for (Block b : blocks) {
            if (hunt.isWild(b)) {
                wild.add(b);
            }
        }
        for (Block b : wild) {
            blocks.remove(b);
            HuntService.Hunt h = hunt.at(b);
            if (h != null) {
                hunt.escape(h, true);
            } else {
                b.setType(org.bukkit.Material.AIR, false);
            }
        }
    }
}
