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
        if (beat % SHELF_EVERY == 0) {
            stockBar(room, w);
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

    /** The bar's shelves are looked at this often (seconds). */
    static final int SHELF_EVERY = 10;

    /**
     * The back bar's shelves (0.44.2, the owner: "We should place those into the shelves not have them just
     * floating"): each shelf block holds three bottles of its own (honey, potions of a few colours, a bottle o'
     * enchanting, an empty glass), put back whenever they differ (a rebuild empties a shelf). A shelf block that isn't
     * there (an edited room) is left alone. Nobody can take them: {@link Clubhouse} refuses a use of any shelf in the
     * room.
     */
    void stockBar(ClubhouseRoom room, World w) {
        List<int[]> shelves = room.barShelves();
        for (int n = 0; n < shelves.size(); n++) {
            int[] at = shelves.get(n);
            if (!w.isChunkLoaded(at[0] >> 4, at[2] >> 4)) {
                continue;
            }
            org.bukkit.block.Block b = w.getBlockAt(at[0], at[1], at[2]);
            if (!(b.getState() instanceof org.bukkit.block.Shelf shelf)) {
                continue;
            }
            org.bukkit.inventory.Inventory inv = shelf.getInventory();
            for (int slot = 0; slot < Math.min(3, inv.getSize()); slot++) {
                ItemStack want = bottle(n * 3 + slot);
                ItemStack have = inv.getItem(slot);
                if (have == null || !have.isSimilar(want)) {
                    inv.setItem(slot, want);
                }
            }
        }
    }

    /** The bottle for shelf slot {@code i}: a fixed mix, the same every time. */
    static ItemStack bottle(int i) {
        int pick = Math.floorMod(i * 7 + 3, 8);
        Material m = switch (pick) {
            case 0 -> Material.HONEY_BOTTLE;
            case 1 -> Material.EXPERIENCE_BOTTLE;
            case 2 -> Material.GLASS_BOTTLE;
            default -> Material.POTION;
        };
        ItemStack item = new ItemStack(m);
        if (m == Material.POTION && item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta) {
            org.bukkit.potion.PotionType[] colours = {org.bukkit.potion.PotionType.HEALING,
                    org.bukkit.potion.PotionType.SWIFTNESS, org.bukkit.potion.PotionType.FIRE_RESISTANCE,
                    org.bukkit.potion.PotionType.LEAPING, org.bukkit.potion.PotionType.NIGHT_VISION};
            meta.setBasePotionType(colours[Math.floorMod(i, colours.length)]);
            item.setItemMeta(meta);
        }
        return item;
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
