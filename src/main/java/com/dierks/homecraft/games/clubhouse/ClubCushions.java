package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.material.Colorable;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The Clubhouse's cushions (0.40.0; the owner: "place cushions on the chairs now that we have them in 26.3"): one on
 * every chair, stool and armchair of the generated room ({@link ClubhousePlanner#seats}), green in the Great Hall,
 * red at the bar and the card table, brown ("leather") on the armchairs.
 *
 * <p><b>26.3 only.</b> A cushion is an entity there ({@code minecraft:cushion}); the type is looked up by its key, so
 * the jar still runs on 26.2, where there is none and the chairs are bare (one INFO line at start says so).
 *
 * <p><b>Furniture, not a game's thing.</b> Each carries {@link Keys#CLUB_SEAT} (never {@code GAME_ENTITY}: the games'
 * guard would keep a sitter seated and let nobody touch it), so anyone may sit on one and get up again. Nobody can
 * break one (invulnerable, and its damage is cancelled) or get it as an item (its drops are cancelled). Never saved
 * with its chunk; swept when the Clubhouse starts, so a crash leaves none. Kept once a second while the room is open:
 * one that went (a chunk unloaded) comes back; all come down when the room closes, moves or stops, and in an
 * owner-built room there are none.
 */
final class ClubCushions {

    private final Logger log;
    private final Map<Integer, UUID> shown = new HashMap<>();
    private List<ClubhousePlanner.Seat> seats = List.of();
    private String world;
    private boolean warned;

    ClubCushions(Logger log) {
        this.log = log;
    }

    /** The cushion's entity type on this server, or {@code null} (26.2: no cushions). */
    static EntityType type() {
        try {
            EntityType t = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("cushion"));
            return t != null && t.isSpawnable() && t.getEntityClass() != null ? t : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether {@code e} is one of the Clubhouse's cushions. */
    static boolean ours(Entity e) {
        return WorldEntities.seat(e);
    }

    /** The dye of a seat's colour name, white for one this server doesn't know. */
    static DyeColor dye(String name) {
        try {
            return DyeColor.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return DyeColor.WHITE;
        }
    }

    /** Once a second: a cushion on every seat of {@code room} while it is open; none when it isn't. */
    void second(ClubhouseRoom room) {
        EntityType type = type();
        if (room == null || !room.open() || type == null) {
            remove();
            if (type == null && !warned && room != null && room.open()) {
                warned = true;
                log.info("Clubhouse: this server has no cushions (Paper 26.3 adds them); the chairs stay bare.");
            }
            return;
        }
        List<ClubhousePlanner.Seat> now = room.seats();
        if (!now.equals(seats) || !room.world().equals(world)) {
            remove(); // the room moved, was rebuilt, or became the owner's: the old cushions go
            seats = now;
            world = room.world();
        }
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return;
        }
        for (int i = 0; i < seats.size(); i++) {
            UUID id = shown.get(i);
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null && e.isValid()) {
                continue;
            }
            ClubhousePlanner.Seat s = seats.get(i);
            if (!w.isChunkLoaded((int) Math.floor(s.x()) >> 4, (int) Math.floor(s.z()) >> 4)) {
                shown.remove(i);
                continue;
            }
            try {
                int n = i;
                Entity made = spawn(w, new Location(w, s.x(), s.y(), s.z(), s.yaw(), 0f), type, c -> {
                    c.setPersistent(false);
                    c.setInvulnerable(true);
                    c.getPersistentDataContainer().set(Keys.CLUB_SEAT, PersistentDataType.INTEGER, n);
                    if (c instanceof Colorable col) {
                        col.setColor(dye(s.color()));
                    }
                });
                shown.put(i, made.getUniqueId());
            } catch (RuntimeException | LinkageError ex) {
                if (!warned) {
                    warned = true;
                    log.warning("Clubhouse: could not put a cushion on a chair: " + ex);
                }
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Entity> Entity spawn(World w, Location at, EntityType type, Consumer<Entity> setUp) {
        Class<T> cls = (Class<T>) type.getEntityClass();
        return w.spawn(at, cls, setUp::accept);
    }

    /** Every cushion comes down (the room closed or moved, or the Clubhouse stopped). */
    void remove() {
        for (UUID id : shown.values()) {
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) {
                e.eject();
                e.remove();
            }
        }
        shown.clear();
        seats = List.of();
        world = null;
    }

    /** Every cushion in every loaded world, ours or left by a crash (the Clubhouse's start). */
    static int sweep() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (ours(e)) {
                    e.eject();
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    /** How many cushions stand now (for the admin's status). */
    int count() {
        return shown.size();
    }
}
