package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Re-spawns displays the moment their chunk loads, so a text panel or hologram bound
 * in an unloaded area reappears immediately instead of waiting for the next refresh
 * tick (they are non-persistent entities re-spawned from the DB — see DisplayService).
 */
public final class DisplayListener implements Listener {

    private final HomeCraftManagement plugin;

    public DisplayListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.displayService() != null) {
            plugin.displayService().onChunkLoad(event.getChunk());
        }
    }
}
