package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;

/**
 * Retires minted Minis when a dropped copy is destroyed (Phase 9, §3.4): thrown in
 * lava/fire, burned on the ground, dropped into the void, caught in an explosion,
 * or despawned on the ground. Retiring decrements circulation but keeps the mint
 * number forever (the cap slot is never freed). Best-effort: paths without a clean
 * event (unloaded chunks, ender chests, {@code /clear}) can't always be caught, and
 * retiring is idempotent so double-fires never double-count.
 */
public final class MiniDestructionListener implements Listener {

    private final HomeCraftManagement plugin;

    public MiniDestructionListener(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** A dropped item that despawned on the ground — the copy is gone. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        plugin.miniService().retire(event.getEntity().getItemStack());
    }

    /** A dropped item destroyed by the environment (lava/fire/void/explosion). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (!isDestructive(event.getCause())) {
            return;
        }
        // These causes destroy a dropped item outright; retire its contents.
        plugin.miniService().retire(item.getItemStack());
    }

    private boolean isDestructive(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case FIRE, FIRE_TICK, LAVA, VOID, HOT_FLOOR, BLOCK_EXPLOSION, ENTITY_EXPLOSION -> true;
            default -> false;
        };
    }
}
