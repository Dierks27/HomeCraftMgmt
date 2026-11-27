package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The Kids' Party Room's live parts (0.59.0, plan 15; the owner's daughter wanted "a trampoline in there, and a place
 * you can turn mini and run around in a maze or mini world"). Nothing here needs reading: a bounce, a size, a chime.
 *
 * <ul>
 *   <li><b>The trampoline.</b> Slime only bounces a fall, so walking onto it does nothing in the game. Here a visitor
 *   whose feet are on the trampoline's slime (and who isn't crouching: a crouch is how to stop) is sent up
 *   {@value #BOUNCE} a tick each time they land, about three blocks, keeping the way they were going: boing, boing,
 *   with a controller and no button. A bounce never goes higher than {@value #CAP} over the slime (anything still
 *   going up there is stopped), well under the lanterns and the roof, and the trampoline is inside the room's walls,
 *   so nobody is ever bounced out. Nobody in a Clubhouse session takes fall damage (the games' guard cancels all of
 *   it), so a tumble off the side is only a tumble.</li>
 *   <li><b>The Mini World.</b> A visitor whose feet are in it ({@link ClubhouseRoom#miniWorld}: through its little
 *   doorway) is made {@value #MINI} of their size (the {@code SCALE} attribute, a transient modifier: never saved with
 *   the player, so a quit, a crash or a restart can't keep anyone small), and their own size again the moment their
 *   feet are anywhere else: out of a doorway, sent home, in another world, a spectator. Leaving the Clubhouse by any
 *   way at all ({@link #gone}), the Clubhouse stopping ({@link #stop}) and joining ({@link #joined}) take it off too.
 *   Jumping and stepping are not scaled by the game (26.2 reads jump strength and step height as they are), so a small
 *   visitor still steps up a slab and hops a block, as everyone does; nothing in the Mini World is under two blocks
 *   high, so growing back anywhere in it never sticks anyone in a block. Bedrock players (Geyser) are sized the same
 *   way; if a client ever ignores it, everything there is still walked at full size.</li>
 *   <li><b>The maze's prize.</b> Reaching the gold middle of the maze plays a chime and a burst of sparkles there (once
 *   in {@value #GOAL_MS} ms for each visitor), and the doorways sparkle a little while anyone is near them.</li>
 * </ul>
 * Only Clubhouse visitors are bounced, sized and cheered. Everything runs on the main thread from the Clubhouse's tick.
 */
final class PartyRoom {

    /** How small the Mini World makes a visitor (their scale there). */
    static final double MINI = 0.3;
    /** A bounce: the speed up (blocks a tick) a visitor landing on the trampoline is given (about three blocks high). */
    static final double BOUNCE = 0.7;
    /** The highest a bounce goes: feet at most this far over the slime's top. */
    static final double CAP = 3.4;
    /** Ticks between two bounces of one visitor (a landing is seen once). */
    static final int BOUNCE_TICKS = 5;
    /** A visitor whose feet are this near the slime's top is on it. */
    static final double ON = 0.05;
    /** The maze's prize cheers the same visitor at most this often. */
    static final long GOAL_MS = 15_000;
    /** The doorways sparkle while anyone is this near (blocks). */
    static final double DOOR_NEAR = 6;

    private final Clubhouse club;
    private final NamespacedKey key;
    /** Who is small now (each has the modifier on). */
    private final Set<UUID> small = new HashSet<>();
    private final Map<UUID, Integer> bouncedAt = new HashMap<>();
    private final Map<UUID, Boolean> capped = new HashMap<>();
    private final Map<UUID, double[]> lastAt = new HashMap<>();
    private final Map<UUID, Long> goalAt = new HashMap<>();
    private int ticks;
    /** Whether the Mini World's doorway stands (a saved room may have no Mini World), read once a second. */
    private boolean miniThere;
    private int miniReadAt = Integer.MIN_VALUE / 2;

    PartyRoom(Clubhouse club) {
        this.club = club;
        this.key = club.plugin() == null ? null : new NamespacedKey(club.plugin(), "clubhouse_mini");
    }

    // ---- pure -------------------------------------------------------------------------------------------------

    /** Whether a visitor with feet at (x, y, z) is small: a visitor, not a spectator, in the Mini World (pure). */
    static boolean smallAt(Box mini, boolean visitor, boolean spectator, double x, double y, double z) {
        return mini != null && visitor && !spectator && mini.contains(x, y, z);
    }

    /**
     * What the trampoline does to a visitor this tick (pure): {@code BOUNCE} to send them up (on the slime, not
     * crouching, not flying, and not bounced in the last {@value #BOUNCE_TICKS} ticks), {@code STOP} to stop them going
     * any higher (over the cap), else {@code NONE}.
     *
     * @param up        how far their feet are over the slime's top
     * @param sinceLast ticks since their last bounce ({@link Integer#MAX_VALUE}: none yet)
     */
    static Jump jump(double up, boolean onSlime, boolean sneaking, boolean flying, int sinceLast) {
        if (!onSlime || flying) {
            return Jump.NONE;
        }
        if (up > CAP) {
            return Jump.STOP;
        }
        return up >= -ON && up <= ON && !sneaking && sinceLast >= BOUNCE_TICKS ? Jump.BOUNCE : Jump.NONE;
    }

    /** What the trampoline does. */
    enum Jump {
        NONE, BOUNCE, STOP
    }

    /** How high a bounce of {@code speed} goes (blocks), with the game's gravity and drag (pure, for the tests). */
    static double height(double speed) {
        double y = 0;
        double v = speed;
        double top = 0;
        for (int t = 0; t < 200 && (v > 0 || y > 0); t++) {
            y += v;
            v = (v - 0.08) * 0.98;
            top = Math.max(top, y);
            if (v < 0) {
                break;
            }
        }
        return top;
    }

    /** Whether feet at (x, y, z) are on the maze's gold middle (its block, standing on its floor) (pure). */
    static boolean atGoal(ClubhousePlanner.Place3 goal, double x, double y, double z) {
        return goal != null && Math.floor(x) == Math.floor(goal.x()) && Math.floor(z) == Math.floor(goal.z())
                && y >= goal.y() - 0.1 && y < goal.y() + 1.0;
    }

    // ---- every tick -------------------------------------------------------------------------------------------

    /** Every tick while the Clubhouse runs: sizes, bounces, the maze's prize; {@code visitor} says who is in. */
    void tick(ClubhouseRoom room, Predicate<UUID> visitor, long now) {
        ticks++;
        World w = room == null ? null : Bukkit.getWorld(room.world());
        Box mini = w == null ? null : room.miniWorld();
        if (mini != null && ticks - miniReadAt >= 20) {
            miniReadAt = ticks;
            int[] l = room.miniLintel();
            miniThere = l != null && w.isChunkLoaded(l[0] >> 4, l[2] >> 4)
                    ? w.getBlockAt(l[0], l[1], l[2]).getType() == Material.PURPLE_CONCRETE : miniThere;
        }
        Box sizeBox = mini != null && miniThere ? mini : null;
        for (UUID id : new ArrayList<>(small)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                small.remove(id); // gone: Clubhouse.gone took it off, and it was never saved
                continue;
            }
            if (!(w != null && p.getWorld().equals(w) && smallAt(sizeBox, visitor.test(id),
                    p.getGameMode() == GameMode.SPECTATOR, p.getX(), p.getY(), p.getZ()))) {
                grow(p, true);
            }
        }
        if (w == null) {
            return;
        }
        Box tramp = room.trampoline();
        ClubhousePlanner.Place3 goal = room.mazeGoal();
        boolean sparkle = ticks % 10 == 0;
        for (Player p : w.getPlayers()) {
            UUID id = p.getUniqueId();
            if (!visitor.test(id)) {
                continue;
            }
            double x = p.getX();
            double y = p.getY();
            double z = p.getZ();
            boolean spectator = p.getGameMode() == GameMode.SPECTATOR;
            if (!small.contains(id) && smallAt(sizeBox, true, spectator, x, y, z)) {
                shrink(p);
            }
            if (tramp != null && !spectator && tramp.contains(x, y, z)) {
                bounce(p, w, tramp);
            } else {
                lastAt.remove(id);
            }
            if (goal != null && atGoal(goal, x, y, z)) {
                Long last = goalAt.get(id);
                if (last == null || now - last >= GOAL_MS) {
                    goalAt.put(id, now);
                    cheer(w, goal);
                }
            }
            if (sparkle && sizeBox != null) {
                for (ClubhousePlanner.Place3 d : room.miniDoors()) {
                    if (Math.abs(d.x() - x) <= DOOR_NEAR && Math.abs(d.z() - z) <= DOOR_NEAR) {
                        p.spawnParticle(Particle.END_ROD, d.x(), d.y() + 1.0, d.z(), 2, 0.25, 0.6, 0.25, 0.01);
                    }
                }
            }
        }
    }

    /** The trampoline, for one visitor over it: send them up as they land, stop them at the cap. */
    private void bounce(Player p, World w, Box tramp) {
        UUID id = p.getUniqueId();
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        double[] was = lastAt.put(id, new double[]{x, y, z});
        boolean onSlime = w.getBlockAt((int) Math.floor(x), tramp.minY(), (int) Math.floor(z)).getType()
                == Material.SLIME_BLOCK; // a saved room without the slime has no bounce
        Integer last = bouncedAt.get(id);
        Jump j = jump(y - (tramp.minY() + 1), onSlime, p.isSneaking(), p.isFlying() || p.isGliding(),
                last == null ? Integer.MAX_VALUE : ticks - last);
        if (j == Jump.NONE) {
            if (y - (tramp.minY() + 1) <= CAP) {
                capped.remove(id);
            }
            return;
        }
        // keep the way they were going: their last tick's step along the floor
        double dx = was == null ? 0 : Math.max(-0.4, Math.min(0.4, x - was[0]));
        double dz = was == null ? 0 : Math.max(-0.4, Math.min(0.4, z - was[2]));
        if (j == Jump.STOP) {
            if (capped.put(id, true) == null) {
                p.setVelocity(new Vector(dx, -0.05, dz));
            }
            return;
        }
        bouncedAt.put(id, ticks);
        p.setVelocity(new Vector(dx, BOUNCE, dz));
        Location at = p.getLocation();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        w.playSound(at, Sound.ENTITY_SLIME_JUMP, 0.45f, 1.2f + r.nextFloat() * 0.5f);
        w.spawnParticle(Particle.ITEM_SLIME, at.getX(), at.getY() + 0.1, at.getZ(), 4, 0.25, 0.05, 0.25, 0.0);
    }

    /** The maze's prize: a chime and a burst of sparkles over the cake, for everyone near. */
    private static void cheer(World w, ClubhousePlanner.Place3 goal) {
        Location at = new Location(w, goal.x(), goal.y() + 1.2, goal.z());
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
        w.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 30, 0.3, 0.5, 0.3, 0.25);
        w.spawnParticle(Particle.END_ROD, at, 12, 0.3, 0.6, 0.3, 0.02);
    }

    // ---- sizes ------------------------------------------------------------------------------------------------

    /** Small: the modifier on (transient: never saved), a little whoosh down and sparkles. */
    private void shrink(Player p) {
        AttributeInstance a = key == null ? null : p.getAttribute(Attribute.SCALE);
        if (a == null) {
            return;
        }
        a.removeModifier(key);
        a.addTransientModifier(new AttributeModifier(key, MINI - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
        small.add(p.getUniqueId());
        Location at = p.getLocation().add(0, 0.5, 0);
        p.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.8f);
        p.getWorld().spawnParticle(Particle.WAX_ON, at, 14, 0.3, 0.5, 0.3, 0.0);
    }

    /** Their own size again (with a whoosh up and sparkles when {@code fuss}). Safe to call on anyone. */
    private void grow(Player p, boolean fuss) {
        small.remove(p.getUniqueId());
        AttributeInstance a = key == null ? null : p.getAttribute(Attribute.SCALE);
        if (a == null || a.getModifier(key) == null) {
            return;
        }
        a.removeModifier(key);
        if (fuss) {
            Location at = p.getLocation().add(0, 1, 0);
            p.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 0.9f);
            p.getWorld().spawnParticle(Particle.WAX_OFF, at, 14, 0.3, 0.6, 0.3, 0.0);
        }
    }

    /** Whether {@code id} is small now (the status line, the tests). */
    boolean small(UUID id) {
        return small.contains(id);
    }

    /** {@code id} left the Clubhouse (any way, a quit too): their own size, and forgotten. */
    void gone(UUID id, Player p) {
        if (p != null) {
            grow(p, false);
        }
        small.remove(id);
        bouncedAt.remove(id);
        capped.remove(id);
        lastAt.remove(id);
        goalAt.remove(id);
    }

    /** Someone joined: never small (the modifier is never saved; this is only to be sure). */
    void joined(Player p) {
        if (p != null) {
            grow(p, false);
        }
    }

    /** The Clubhouse stops: everyone their own size. */
    void stop() {
        for (UUID id : List.copyOf(small)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                grow(p, false);
            }
        }
        small.clear();
        bouncedAt.clear();
        capped.clear();
        lastAt.clear();
        goalAt.clear();
    }
}
