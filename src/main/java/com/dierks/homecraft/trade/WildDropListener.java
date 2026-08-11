package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.storage.PlacedNaturalDao;
import com.dierks.homecraft.util.Keys;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Fires Wild-Drop rolls from the world, with the anti-farm guards: block-break
 * drops only from naturally-generated (non-player-placed) blocks, and mob-kill
 * drops only from non-artificial mobs (spawner/bred/egg-spawned mobs are tagged
 * and excluded).
 */
public final class WildDropListener implements Listener {

    /** Spawn reasons treated as "artificial" — their mobs never Wild-Drop. */
    private static final Set<CreatureSpawnEvent.SpawnReason> ARTIFICIAL = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_WITHER,
            CreatureSpawnEvent.SpawnReason.MOUNT,
            CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.TRAP,
            CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.INFECTION,
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.COMMAND);

    private final HomeCraftManagement plugin;
    private final WildDropService drops;
    private final PlacedNaturalDao placed;

    public WildDropListener(HomeCraftManagement plugin, WildDropService drops, PlacedNaturalDao placed) {
        this.plugin = plugin;
        this.drops = drops;
        this.placed = placed;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!drops.isTrackedBlock(event.getBlock().getType())) {
            return;
        }
        try {
            placed.mark(event.getBlock().getLocation());
        } catch (SQLException e) {
            plugin.getLogger().warning("anti-farm mark failed: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        var type = event.getBlock().getType();
        if (!drops.isTrackedBlock(type)) {
            return;
        }
        try {
            if (placed.consume(event.getBlock().getLocation())) {
                return; // player-placed (or silk-and-replaced) — no drop
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("anti-farm check failed: " + e.getMessage());
            return;
        }
        drops.roll(player, Loot.Trigger.BLOCK_BREAK, type.name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(CreatureSpawnEvent event) {
        if (ARTIFICIAL.contains(event.getSpawnReason())) {
            event.getEntity().getPersistentDataContainer()
                    .set(Keys.MOB_ARTIFICIAL, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null) {
            return;
        }
        if (entity.getPersistentDataContainer().has(Keys.MOB_ARTIFICIAL, PersistentDataType.BYTE)) {
            return;
        }
        drops.roll(killer, Loot.Trigger.MOB_KILL, entity.getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            drops.roll(event.getPlayer(), Loot.Trigger.FISHING, "FISH");
        }
    }
}
