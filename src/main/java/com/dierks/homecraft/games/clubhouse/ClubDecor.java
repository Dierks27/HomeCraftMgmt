package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.world.WorldEntities;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The generated room's decoration that isn't blocks (0.42.0; {@link ClubhousePlanner#decor}): the Feast Pot on its
 * table, the bar's bottles and glasses, the jukebox's records, the putting green's flag, ball and putter, and the
 * Arcade Room's prizes. Each is one display, a game entity ({@link WorldEntities#tag}): never saved, swept at every
 * start, made once and left alone while it stands (only a chunk unload or a moved room makes one again), so nothing
 * flickers. Steam rises from the Feast Pot while anyone is in. Nothing here can be taken, broken or tapped.
 *
 * <p>An owner-built room has none (its decoration is the owner's). A saved room (edit mode) keeps them where the
 * generated room has them.
 */
final class ClubDecor {

    private final Clubhouse club;
    private final Map<String, Shown> shown = new HashMap<>();
    private int beat;

    private record Shown(ClubhousePlanner.Prop prop, UUID id) {
    }

    ClubDecor(Clubhouse club) {
        this.club = club;
    }

    /** Once a second while the room is open. */
    void second(ClubhouseRoom room, boolean anyoneIn) {
        List<ClubhousePlanner.Prop> props = room == null ? List.of() : room.decor();
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (w == null || props.isEmpty()) {
            remove();
            return;
        }
        Set<String> keep = new HashSet<>();
        for (ClubhousePlanner.Prop p : props) {
            keep.add(p.key());
            Shown s = shown.get(p.key());
            Entity e = s == null ? null : Bukkit.getEntity(s.id());
            if (s != null && s.prop().equals(p) && e != null && e.isValid()) {
                continue; // as it is
            }
            if (!w.isChunkLoaded((int) Math.floor(p.x()) >> 4, (int) Math.floor(p.z()) >> 4)) {
                continue;
            }
            if (e != null) {
                e.remove();
            }
            Entity made = spawn(w, p);
            if (made != null) {
                shown.put(p.key(), new Shown(p, made.getUniqueId()));
            }
        }
        for (String key : new HashSet<>(shown.keySet())) {
            if (!keep.contains(key)) {
                Entity e = Bukkit.getEntity(shown.remove(key).id());
                if (e != null) {
                    e.remove();
                }
            }
        }
        // a little steam off the Feast Pot while anyone is in, every other second
        Shown pot = shown.get("feast.pot");
        potTap(w, pot == null ? null : pot.prop());
        if (anyoneIn && pot != null && (beat++ & 1) == 0) {
            w.spawnParticle(Particle.WHITE_SMOKE, pot.prop().x(), pot.prop().y() + 0.4, pot.prop().z(), 2, 0.08, 0.05,
                    0.08, 0.005);
        }
    }

    private Entity spawn(World w, ClubhousePlanner.Prop p) {
        Material m = Material.matchMaterial(p.what());
        if (m == null) {
            return null;
        }
        if (p.block()) {
            return w.spawn(new Location(w, p.x(), p.y(), p.z()), BlockDisplay.class, e -> {
                WorldEntities.tag(e, club, null);
                e.setBlock(m.createBlockData());
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf()));
            });
        }
        return w.spawn(new Location(w, p.x(), p.y(), p.z(), p.yaw(), 0f), ItemDisplay.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setItemStack(new ItemStack(m));
            Quaternionf left = new Quaternionf();
            switch (p.look()) {
                case "face" -> e.setBillboard(Display.Billboard.VERTICAL);
                case "flat" -> left.rotateX((float) (-Math.PI / 2)); // lying on the table
                case "lean" -> left.rotateZ((float) Math.toRadians(20)); // leaning on the green's edge
                default -> {
                }
            }
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setRotation(p.yaw(), 0f);
            e.setTransformation(new Transformation(new Vector3f(), left, new Vector3f(p.sx(), p.sy(), p.sz()),
                    new Quaternionf()));
        });
    }

    /**
     * The Feast Pot's tap target (0.42.0: the real pot, fed from the saved home inventory): an interaction box over
     * the pot, made with it and taken down with it.
     */
    private void potTap(World w, ClubhousePlanner.Prop pot) {
        Entity e = potTap == null ? null : Bukkit.getEntity(potTap);
        if (pot == null) {
            if (e != null) {
                e.remove();
            }
            potTap = null;
            potAt = null;
            return;
        }
        Location at = new Location(w, pot.x(), pot.y() - pot.sy() / 2 - 0.05, pot.z());
        if (e != null && e.isValid() && at.equals(potAt)) {
            return;
        }
        if (e != null) {
            e.remove();
        }
        potAt = at;
        potTap = w.spawn(at, org.bukkit.entity.Interaction.class, i -> {
            WorldEntities.tag(i, club, null);
            i.setInteractionWidth(Math.max(0.9f, pot.sx() + 0.2f));
            i.setInteractionHeight(Math.max(0.9f, pot.sy() + 0.3f));
            i.setResponsive(true);
        }).getUniqueId();
    }

    private UUID potTap;
    private Location potAt;

    /** Whether {@code e} is the Feast Pot's tap target. */
    boolean isPot(Entity e) {
        return e != null && potTap != null && potTap.equals(e.getUniqueId());
    }

    /** Everything comes down (the room closed, moved or stopped). */
    void remove() {
        Entity tap = potTap == null ? null : Bukkit.getEntity(potTap);
        if (tap != null) {
            tap.remove();
        }
        potTap = null;
        potAt = null;
        for (Shown s : shown.values()) {
            Entity e = Bukkit.getEntity(s.id());
            if (e != null) {
                e.remove();
            }
        }
        shown.clear();
    }

    int count() {
        return shown.size();
    }
}
