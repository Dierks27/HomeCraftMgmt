package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.games.world.WorldEntities;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The bodies of the NPC players in a world game (stage 2): one mannequin each, made, moved and taken down here, the
 * same way the Clubhouse's regulars are.
 *
 * <ul>
 *   <li><b>Furniture, never a player.</b> Each is a game entity ({@link WorldEntities#tag}, the NPC players' own):
 *       never saved with its chunk, swept when the NPC players start and whenever a chunk loads with one in it;
 *       invulnerable, immovable, not collidable and without gravity, so it can never push, block or carry a real
 *       player, and the games' guard cancels every touch. A seated one (a boat racer) rides an invisible marker
 *       stand, which has no box at all.</li>
 *   <li><b>Moved by teleport</b>, a small step a tick along its path, so it needs no physics and never walks
 *       anywhere the path doesn't go. A body whose next spot is in a chunk nobody has loaded is taken down and made
 *       again when it comes back into view; one whose spot is inside a solid block is hidden for that moment
 *       ({@link #place}'s {@code hideInWalls}), so it never shows halfway through a wall.</li>
 *   <li><b>Who sees them.</b> Hidden from everyone by default and shown to each player in its world who has NPCs on
 *       ({@link #views}); never to one who has them off.</li>
 *   <li><b>Its name</b> is "Pip ⚙", with "⚙ NPC ★" under it: the stars of the level it plays at in this game.</li>
 * </ul>
 * Main thread only.
 */
public final class NpcBodies {

    /** One body. */
    private static final class Body {
        UUID mannequin;
        UUID seat;
        String looks;
        int looksVersion = -1;
        Pose pose = Pose.STANDING;
        boolean hidden;
        boolean wings;
        /** This server would not seat it: it is shown standing (and isn't asked to sit again). */
        boolean standsOnly;
    }

    private final CpuPlayers npcs;
    private final Map<String, Body> bodies = new LinkedHashMap<>();
    /** Each player's bodies shown to them now. */
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();

    public NpcBodies(CpuPlayers npcs) {
        this.npcs = npcs;
    }

    /** How many bodies are up. */
    public int size() {
        return bodies.size();
    }

    /** Whether body {@code key} is up. */
    public boolean has(String key) {
        return bodies.containsKey(key);
    }

    /**
     * Put body {@code key} (NPC {@code npc}, playing at {@code level}) at {@code at}: made there in a loaded chunk, or
     * stepped there.
     *
     * @param pose        standing, or {@link Pose#FALL_FLYING} for a glider (where a mannequin takes it)
     * @param seated      seated on an invisible marker (a boat racer)
     * @param hideInWalls hidden while {@code at} is inside a solid block (a path between checkpoints)
     * @return the mannequin, or {@code null} when nothing is there now (an unloaded chunk)
     */
    public Mannequin place(String key, CpuPlayer npc, CpuLevel level, Location at, Pose pose, boolean seated,
                           boolean hideInWalls) {
        World w = at.getWorld();
        if (w == null) {
            return null;
        }
        Body b = bodies.computeIfAbsent(key, k -> new Body());
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            down(b); // nobody near: it comes back when the chunk does
            return null;
        }
        Entity e = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
        if (e != null && (!e.isValid() || !e.getWorld().equals(w))) {
            down(b);
            e = null;
        }
        if (e == null) {
            Mannequin m = w.spawn(at, Mannequin.class, made -> {
                WorldEntities.tag(made, npcs, null);
                made.setVisibleByDefault(false); // shown to each player it is for ({@link #views})
                made.setInvulnerable(true);
                made.setImmovable(true);
                made.setGravity(false);
                made.setSilent(true);
                made.setCollidable(false);
                made.setCanPickupItems(false);
                made.setRemoveWhenFarAway(false);
                made.setCustomNameVisible(true);
            });
            b.mannequin = m.getUniqueId();
            b.looksVersion = -1;
            b.pose = Pose.STANDING;
            b.wings = false;
            b.hidden = false;
            forgetShown(b.mannequin);
            e = m;
        }
        Mannequin m = (Mannequin) e;
        Location was = m.getLocation();
        boolean still = was.getWorld() != null && was.getWorld().equals(w) && was.distanceSquared(at) < 1e-6
                && Math.abs(was.getYaw() - at.getYaw()) < 0.01f && (!seated || b.seat != null || b.standsOnly);
        if (still) {
            // where it was: nothing to move (a body waiting on its spot is never sent a teleport every tick)
        } else if (seated && !b.standsOnly) {
            Entity st = b.seat == null ? null : Bukkit.getEntity(b.seat);
            if (st == null || !st.isValid()) {
                ArmorStand seat = w.spawn(at, ArmorStand.class, s -> {
                    WorldEntities.tag(s, npcs, null);
                    s.setMarker(true);
                    s.setInvisible(true);
                    s.setSmall(true);
                    s.setGravity(false);
                    s.setInvulnerable(true);
                    s.setSilent(true);
                    s.setPersistent(false);
                });
                b.seat = seat.getUniqueId();
                if (!seat.addPassenger(m)) {
                    seat.remove(); // it is shown standing instead: never anything with a box
                    b.seat = null;
                    b.standsOnly = true;
                    m.teleport(at);
                }
            } else {
                st.teleport(at); // a vehicle keeps its passengers through a teleport on this server
                if (!st.getPassengers().contains(m)) {
                    m.teleport(at); // a server that dropped it: seated again where the marker is
                    st.addPassenger(m);
                }
            }
        } else {
            unseat(b);
            m.teleport(at);
        }
        m.setRotation(at.getYaw(), at.getPitch());
        posed(m, b, pose);
        boolean inWall = hideInWalls && solid(at);
        if (inWall != b.hidden) {
            m.setInvisible(inWall);
            m.setCustomNameVisible(!inWall);
            b.hidden = inWall;
        }
        dress(m, b, npc, level);
        return m;
    }

    /** Whether feet or head at {@code at} would be inside a solid block. */
    private static boolean solid(Location at) {
        Block feet = at.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        return feet.getType().isOccluding() || head.getType().isOccluding();
    }

    private void posed(Mannequin m, Body b, Pose pose) {
        Pose want = pose == null ? Pose.STANDING : pose;
        boolean wings = want == Pose.FALL_FLYING;
        if (wings != b.wings) {
            m.getEquipment().setChestplate(wings ? new ItemStack(Material.ELYTRA) : null);
            m.getEquipment().setChestplateDropChance(0f);
            b.wings = wings;
        }
        if (want == b.pose) {
            return;
        }
        try {
            if (Mannequin.validPoses().contains(want)) {
                m.setPose(want, true);
            } else {
                m.setPose(Pose.STANDING, false);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // this server won't pose it: it stays standing
        }
        b.pose = want;
    }

    /** Its name, the gear, "NPC" and its stars, and its skin, whenever they change. */
    private void dress(Mannequin m, Body b, CpuPlayer npc, CpuLevel level) {
        String looks = npc.key() + ":" + level;
        if (looks.equals(b.looks) && b.looksVersion == npcs.looksVersion()) {
            return;
        }
        m.customName(Text.of("&f" + npcs.name(npc) + " &7" + CpuRoster.MARKER));
        m.setDescription(Text.of("&7" + CpuRoster.MARKER + " " + CpuRoster.WORD + " &e" + level.starText()));
        m.setProfile(npcs.profile(npc));
        b.looks = looks;
        b.looksVersion = npcs.looksVersion();
    }

    /** The mannequin of body {@code key}, or {@code null}. */
    public Mannequin body(String key) {
        Body b = bodies.get(key);
        Entity e = b == null || b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
        return e instanceof Mannequin m && m.isValid() ? m : null;
    }

    /** A swing of the arm (a wave, a cheer, a putt). */
    public void swing(String key) {
        Mannequin m = body(key);
        if (m != null) {
            m.swingMainHand();
        }
    }

    /** Hold {@code item} (or nothing) in the main hand. */
    public void hold(String key, Material item) {
        Mannequin m = body(key);
        if (m != null) {
            m.getEquipment().setItemInMainHand(item == null ? null : new ItemStack(item));
            m.getEquipment().setItemInMainHandDropChance(0f);
        }
    }

    /**
     * Each player in {@code w} sees the bodies there if they have NPCs on, and never if not; a body in another world
     * is left alone.
     */
    public void views(World w) {
        if (w == null || bodies.isEmpty()) {
            return;
        }
        Plugin plugin = npcs.games().plugin();
        for (Player p : w.getPlayers()) {
            Set<UUID> mine = shown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            boolean wants = npcs.wants(p.getUniqueId());
            for (Body b : bodies.values()) {
                Entity e = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
                if (e == null || !e.getWorld().equals(w)) {
                    continue;
                }
                if (wants && mine.add(b.mannequin)) {
                    p.showEntity(plugin, e);
                } else if (!wants && mine.remove(b.mannequin)) {
                    p.hideEntity(plugin, e);
                }
            }
        }
    }

    /** Body {@code key} comes down. */
    public void remove(String key) {
        Body b = bodies.remove(key);
        if (b != null) {
            down(b);
        }
    }

    /** Every body comes down. */
    public void clear() {
        for (Body b : bodies.values()) {
            down(b);
        }
        bodies.clear();
        shown.clear();
    }

    /** A player left the server: their view is set again when they come back. */
    public void forget(UUID player) {
        shown.remove(player);
    }

    private void down(Body b) {
        unseat(b);
        Entity e = b.mannequin == null ? null : Bukkit.getEntity(b.mannequin);
        if (e != null) {
            e.remove();
        }
        if (b.mannequin != null) {
            forgetShown(b.mannequin);
        }
        b.mannequin = null;
    }

    private static void unseat(Body b) {
        Entity st = b.seat == null ? null : Bukkit.getEntity(b.seat);
        if (st != null) {
            st.eject();
            st.remove();
        }
        b.seat = null;
    }

    private void forgetShown(UUID mannequin) {
        for (Set<UUID> s : shown.values()) {
            s.remove(mannequin);
        }
    }
}
