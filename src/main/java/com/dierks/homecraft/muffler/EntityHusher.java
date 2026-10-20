package com.dierks.homecraft.muffler;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import org.bukkit.World;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Silences the minecarts and bees inside every muffler set to silence them, and un-silences them
 * when they leave, the muffler changes, or it is broken (see {@link EntityHush} for why this is
 * the only way to reach those two sounds). Main thread only.
 *
 * <p><b>Only ever undoes its own work.</b> An entity is marked silent here only if it was not
 * silent already, and then carries a {@code muffler_hushed} tag; only tagged entities are ever
 * un-silenced. A minecart an admin silenced by hand, or a villager another feature keeps quiet, is
 * never touched.
 *
 * <p><b>Survives restarts and unloaded chunks.</b> The silent flag and the tag are saved with the
 * entity. A tagged entity seen again — loaded at start-up, or when its chunk loads later — is
 * taken back into the list, and the next pass decides it: still in a silencing box, it stays
 * quiet; otherwise it gets its voice back. On shutdown every loaded one is un-silenced.
 */
final class EntityHusher implements Listener {

    /** Half a second: a cart rolling into a box rumbles at most this long before it goes quiet. */
    private static final long PERIOD_TICKS = 10L;

    private final HomeCraftManagement plugin;
    private final Supplier<Collection<Muffler>> mufflers;
    private final BooleanSupplier enabled;
    /** Entities this class silenced and is watching, by UUID (the reference is refreshed each pass). */
    private final Map<UUID, Entity> hushed = new HashMap<>();
    private BukkitTask task;

    EntityHusher(HomeCraftManagement plugin, Supplier<Collection<Muffler>> mufflers, BooleanSupplier enabled) {
        this.plugin = plugin;
        this.mufflers = mufflers;
        this.enabled = enabled;
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClasses(Minecart.class, Bee.class)) {
                adopt(e);
            }
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    /** Give every loaded entity this silenced its voice back, and stop. */
    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        for (Entity e : hushed.values()) {
            if (e.isValid()) {
                release(e);
            }
        }
        hushed.clear();
    }

    /** One pass: silence what is inside a silencing box, un-silence what no longer is. */
    void tick() {
        Set<UUID> keep = new HashSet<>();
        if (enabled.getAsBoolean()) {
            for (Muffler m : mufflers.get()) {
                Set<EntityHush.Kind> kinds = EntityHush.kindsSilencedBy(m);
                if (kinds.isEmpty()) {
                    continue;
                }
                World w = plugin.getServer().getWorld(m.pos().world());
                if (w == null || !w.isChunkLoaded(m.pos().x() >> 4, m.pos().z() >> 4)) {
                    continue;
                }
                int r = m.radius();
                BoundingBox box = new BoundingBox(m.pos().x() - r, m.pos().y() - r, m.pos().z() - r,
                        m.pos().x() + r + 1, m.pos().y() + r + 1, m.pos().z() + r + 1);
                for (Entity e : w.getNearbyEntities(box, e -> kinds.contains(kindOf(e)))) {
                    if (hush(e)) {
                        keep.add(e.getUniqueId());
                    }
                }
            }
        }
        Iterator<Map.Entry<UUID, Entity>> it = hushed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entity> entry = it.next();
            if (keep.contains(entry.getKey())) {
                continue;
            }
            Entity e = entry.getValue();
            if (e.isValid()) {
                release(e); // left the box, or the muffler changed or went away
            }
            // An invalid one is dead, or in a chunk that unloaded: it keeps its tag and is taken
            // back (and decided again) when it loads.
            it.remove();
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            adopt(e);
        }
    }

    /** Silence one entity; false when it was already silent for someone else's reasons. */
    private boolean hush(Entity e) {
        UUID id = e.getUniqueId();
        if (hushed.containsKey(id)) {
            hushed.put(id, e);
            if (!e.isSilent()) {
                e.setSilent(true);
            }
            return true;
        }
        if (e.isSilent()) {
            return false; // not ours: an admin or another feature made it silent
        }
        e.setSilent(true);
        e.getPersistentDataContainer().set(Keys.MUFFLER_HUSHED, PersistentDataType.BYTE, (byte) 1);
        hushed.put(id, e);
        return true;
    }

    private void release(Entity e) {
        if (e.getPersistentDataContainer().has(Keys.MUFFLER_HUSHED, PersistentDataType.BYTE)) {
            e.getPersistentDataContainer().remove(Keys.MUFFLER_HUSHED);
            e.setSilent(false);
        }
    }

    /** Take back an entity this silenced before (a restart, a chunk loading). */
    private void adopt(Entity e) {
        if (e.getPersistentDataContainer().has(Keys.MUFFLER_HUSHED, PersistentDataType.BYTE)) {
            hushed.put(e.getUniqueId(), e);
        }
    }

    /** The kind of entity a muffler can silence, or null for anything else. */
    static EntityHush.Kind kindOf(Entity e) {
        if (e instanceof Minecart) {
            return EntityHush.Kind.MINECART;
        }
        if (e instanceof Bee) {
            return EntityHush.Kind.BEE;
        }
        return null;
    }
}
