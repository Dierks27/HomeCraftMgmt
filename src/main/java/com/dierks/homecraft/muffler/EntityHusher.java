package com.dierks.homecraft.muffler;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.util.Keys;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.World;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityEnterBlockEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
 * <p><b>Survives restarts, unloaded chunks and beehives.</b> The silent flag and the tag are saved
 * with the entity. A tagged entity that turns up again (loaded at start-up, or its chunk loading)
 * is decided the moment it is added to the world, in the same tick, before any player's game can
 * start its sound loop on a stale flag: still in a silencing box, it is taken back into the list;
 * otherwise it gets its voice back there and then. A bee is given its voice back as it enters a
 * hive, so it is stored, and later comes out, with its buzz; if it comes out inside a silencing box
 * the next pass quiets it again. A tagged entity a pass finds is always ours, whatever list it is
 * or isn't on. On shutdown every loaded one is un-silenced.
 *
 * <p><b>Never TNT minecarts.</b> A silent TNT minecart primes without its fuse hiss — the warning
 * that it is about to explode — so those are never silenced.
 *
 * <p><b>Inside means its position.</b> An entity counts when its position is in the box — the
 * same rule as every other sound ({@link Muffler#contains}) — not when its hitbox merely touches
 * the edge.
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
                if (w == null) {
                    continue;
                }
                // Only loaded entities come back, and nothing is loaded by asking — so a box that
                // spills into chunks around an unloaded muffler still covers the loaded part.
                int r = m.radius();
                BoundingBox box = new BoundingBox(m.pos().x() - r, m.pos().y() - r, m.pos().z() - r,
                        m.pos().x() + r + 1, m.pos().y() + r + 1, m.pos().z() + r + 1);
                for (Entity e : w.getNearbyEntities(box,
                        e -> kinds.contains(kindOf(e)) && m.contains(e.getX(), e.getY(), e.getZ()))) {
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
            // An invalid one is dead, in a chunk that unloaded, or a bee gone into its hive: it
            // keeps its tag and is taken back (and decided again) when it is added to the world.
            it.remove();
        }
    }

    /**
     * Every add to the world (chunk loads, a bee leaving a hive): a tagged entity is decided now,
     * not at the next pass, so its voice is back before any player's game adds it silent and loses
     * the loop until the next time it reloads.
     */
    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        Entity e = event.getEntity();
        EntityHush.Kind kind = kindOf(e);
        if (kind == null || !isTagged(e)) {
            return;
        }
        if (enabled.getAsBoolean() && EntityHush.shouldSilence(mufflersIn(e.getWorld().getName()), kind,
                e.getX(), e.getY(), e.getZ())) {
            hushed.put(e.getUniqueId(), e);
        } else {
            release(e);
            hushed.remove(e.getUniqueId());
        }
    }

    /**
     * A bee appearing inside a silencing box — out of its hive, bred, spawned — is silenced before
     * it is added to the world, so no player's game starts its buzz for the half second until the
     * next pass. The event fires before the add, with the bee already at its position.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        Entity e = event.getEntity();
        EntityHush.Kind kind = kindOf(e);
        if (kind == null || !enabled.getAsBoolean()) {
            return;
        }
        if (EntityHush.shouldSilence(mufflersIn(e.getWorld().getName()), kind, e.getX(), e.getY(), e.getZ())) {
            hush(e);
        }
    }

    /** A bee going into its hive is stored with its voice, so it comes back out buzzing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnterBlock(EntityEnterBlockEvent event) {
        Entity e = event.getEntity();
        if (isTagged(e)) {
            release(e);
            hushed.remove(e.getUniqueId());
        }
    }

    private List<Muffler> mufflersIn(String world) {
        List<Muffler> out = new ArrayList<>();
        for (Muffler m : mufflers.get()) {
            if (m.pos().world().equals(world)) {
                out.add(m);
            }
        }
        return out;
    }

    /** Silence one entity; false when it was already silent for someone else's reasons. */
    private boolean hush(Entity e) {
        UUID id = e.getUniqueId();
        switch (claim(hushed.containsKey(id), isTagged(e), e.isSilent())) {
            case OURS -> {
                hushed.put(id, e); // the tag says ours, even if this exact entity was never on the list
                if (!e.isSilent()) {
                    e.setSilent(true);
                }
                return true;
            }
            case NEW -> {
                e.setSilent(true);
                e.getPersistentDataContainer().set(Keys.MUFFLER_HUSHED, PersistentDataType.BYTE, (byte) 1);
                hushed.put(id, e);
                return true;
            }
            default -> {
                return false; // not ours: an admin or another feature made it silent
            }
        }
    }

    /** Whose silence an entity a pass finds in a silencing box is. */
    enum Claim { OURS, NEW, NOT_OURS }

    /**
     * On the list or carrying our tag: ours (a bee back out of its hive is a new entity with our
     * tag on it). Otherwise silent already: someone else's, never touched. Otherwise: ours to silence.
     */
    static Claim claim(boolean tracked, boolean tagged, boolean alreadySilent) {
        if (tracked || tagged) {
            return Claim.OURS;
        }
        return alreadySilent ? Claim.NOT_OURS : Claim.NEW;
    }

    private void release(Entity e) {
        if (isTagged(e)) {
            e.getPersistentDataContainer().remove(Keys.MUFFLER_HUSHED);
            e.setSilent(false);
        }
    }

    /** Take back an entity this silenced before, already loaded when the plugin starts. */
    private void adopt(Entity e) {
        if (kindOf(e) != null && isTagged(e)) {
            hushed.put(e.getUniqueId(), e);
        }
    }

    private static boolean isTagged(Entity e) {
        return e.getPersistentDataContainer().has(Keys.MUFFLER_HUSHED, PersistentDataType.BYTE);
    }

    /** The kind of entity a muffler can silence, or null for anything else (TNT minecarts included). */
    static EntityHush.Kind kindOf(Entity e) {
        if (e instanceof ExplosiveMinecart) {
            return null; // its fuse hiss is a warning, never hushed
        }
        if (e instanceof Minecart) {
            return EntityHush.Kind.MINECART;
        }
        if (e instanceof Bee) {
            return EntityHush.Kind.BEE;
        }
        return null;
    }
}
