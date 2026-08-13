package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/**
 * Re-spawns holograms the moment their chunk loads, so a text-display bound in an
 * unloaded area reappears immediately instead of waiting for the next refresh tick
 * (holograms are non-persistent entities re-spawned from the DB — see DisplayService),
 * and turns a right-click on a map-TV into a browser link to the live dashboard chart.
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

    /**
     * Right-clicking a map-TV frame normally rotates the map — intercept that (only for
     * map-TV frames) and open the live dashboard chart in the player's browser instead.
     */
    @EventHandler(ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof ItemFrame frame)
                || plugin.displayService() == null) {
            return;
        }
        Optional<String> commodity = plugin.displayService().mapTvCommodityFor(frame);
        if (commodity.isEmpty()) {
            return; // a normal item frame — leave vanilla rotation alone
        }
        event.setCancelled(true); // it's a map-TV: suppress the rotate, open the chart
        plugin.displayService().openMapTvChart(event.getPlayer(), commodity.get());
    }
}
