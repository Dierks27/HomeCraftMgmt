package com.dierks.homecraft.muffler;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a placed Sound Muffler where it was put. A muffler is remembered by its position, so a
 * piston shoving it one block along, fire burning its wool, a wither chewing through it or water
 * washing a head-skinned one away would leave the settings hushing a spot with nothing in it —
 * and drop a plain block for the player. Each of those is simply refused. (Explosions are
 * already kept off every HomeCraft block by {@code CustomBlockListener}.)
 *
 * <p>Every check is a map lookup: no database on a piston's hot path.
 */
public final class MufflerBlockListener implements Listener {

    private final SoundMufflerService mufflers;

    public MufflerBlockListener(SoundMufflerService mufflers) {
        this.mufflers = mufflers;
    }

    /**
     * A head in a piston's path is not pushed but broken, and it is not in {@code getBlocks()} —
     * that list is only what moves. It sits one past the end of the moving line.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> touched = new ArrayList<>(event.getBlocks());
        touched.add(event.getBlock().getRelative(event.getDirection(), event.getBlocks().size() + 1));
        if (any(touched)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (any(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (mufflers.isMuffler(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (mufflers.isMuffler(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (mufflers.isMuffler(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean any(List<Block> blocks) {
        for (Block b : blocks) {
            if (mufflers.isMuffler(b)) {
                return true;
            }
        }
        return false;
    }
}
