package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Arcade Room's ball pit's balls move (0.59.0; the owner: "The ball pit in the arcade would be amazing if the balls
 * moved!! ... that was the thing she said when she jumped in! A BALL PIT!!"). The balls are {@link ClubDecor}'s displays
 * ({@code arcade.ball.*}): here each is only moved, never made or taken down.
 *
 * <ul>
 *   <li><b>Someone in the pit</b> (a player, or an NPC regular: anything alive whose feet are in it) pushes the balls
 *   round them away and up, harder for a jump in or a run than for standing still, and each ball then rolls back to
 *   its place over the next half second or so.</li>
 *   <li><b>A gentle jiggle</b>: while anyone is near, a few balls at a time wobble a little and settle.</li>
 * </ul>
 * A ball is moved by teleporting its display with a teleport duration (the client slides it there smoothly; a ball is
 * drawn turned to whoever looks, so it moves in the world, not on the screen), and never past the pit's padded wall: every
 * place it goes is kept inside the pit, its own half width in from the wall, and between the pit's floor and a little
 * over its rim.
 *
 * <p>Cheap: nothing is looked at unless a player is within {@value #NEAR} blocks of the pit, at most {@value #MOVES}
 * balls move in a tick, and a pit nobody is near is left as it is (every ball back at its place).
 */
final class BallPit {

    /** Nothing happens unless a player is this near the pit (blocks, along the floor). */
    static final double NEAR = 8;
    /** The most balls moved in one tick. */
    static final int MOVES = 24;
    /** A ball this near (along the floor) a body in the pit is pushed away. */
    static final double REACH = 1.3;
    /** The furthest a push moves a ball along the floor, and up. */
    static final double PUSH = 0.45;
    static final double POP = 0.55;
    /** Ticks a push takes, and the slower roll back to its place. */
    static final int PUSH_TICKS = 4;
    static final int BACK_TICKS = 12;
    /** Ticks a pushed ball waits before it rolls back. */
    static final int REST_TICKS = 6;
    /** A jiggle: how far, how often (ticks), how many balls at once. */
    static final double JIGGLE = 0.05;
    static final int JIGGLE_EVERY = 5;
    static final int JIGGLE_BALLS = 3;

    private final ClubDecor decor;
    /** Each moved ball: when it goes back to its place (in ticks). */
    private final Map<UUID, Integer> backAt = new HashMap<>();
    /** Each body's feet at the last tick (how fast it moved, and whether it fell in). */
    private final Map<UUID, double[]> lastFeet = new HashMap<>();
    private int ticks;

    BallPit(ClubDecor decor) {
        this.decor = decor;
    }

    /** A ball's place and its entity. */
    record Ball(UUID id, double x, double y, double z, double r) {
    }

    // ---- pure -------------------------------------------------------------------------------------------------

    /**
     * Where a ball at its place (bx, by, bz) is pushed by a body at (fx, fz) moving {@code speed} blocks a tick (pure):
     * away from the body along the floor, harder the nearer it is and the faster the body, and up; {@code null} when the
     * body is too far. {@code kick} (0 to 1) is a little randomness to the push. The place is kept in the pit.
     */
    static double[] pushed(Box pit, double r, double bx, double by, double bz, double fx, double fz, double speed,
                           double kick) {
        double dx = bx - fx;
        double dz = bz - fz;
        double d = Math.hypot(dx, dz);
        double reach = REACH + Math.min(0.6, speed);
        if (d > reach) {
            return null;
        }
        double near = 1 - d / reach;
        double strength = Math.min(1.0, 0.35 + near * 0.6 + speed * 1.5) * (0.75 + 0.25 * kick);
        double ux = d < 1e-6 ? Math.cos(kick * Math.PI * 2) : dx / d;
        double uz = d < 1e-6 ? Math.sin(kick * Math.PI * 2) : dz / d;
        return keep(pit, r, bx + ux * PUSH * strength, by + POP * strength * (0.6 + 0.4 * kick),
                bz + uz * PUSH * strength);
    }

    /**
     * A ball's place kept inside the pit (pure): its middle at least its own half width {@code r} in from the walls, from
     * resting on the floor up to a little over the rim.
     */
    static double[] keep(Box pit, double r, double x, double y, double z) {
        double minX = pit.minX() + r;
        double maxX = pit.maxX() + 1 - r;
        double minZ = pit.minZ() + r;
        double maxZ = pit.maxZ() + 1 - r;
        double minY = pit.minY() + RegularSpots.CARPET + r * 0.7;
        double maxY = pit.maxY() + 0.6;
        return new double[]{Math.max(minX, Math.min(maxX, x)), Math.max(minY, Math.min(maxY, y)),
                Math.max(minZ, Math.min(maxZ, z))};
    }

    // ---- every tick -------------------------------------------------------------------------------------------

    /** Every tick while the Clubhouse runs. */
    void tick(ClubhouseRoom room) {
        ticks++;
        Box pit = room == null ? null : room.ballPit();
        World w = pit == null ? null : Bukkit.getWorld(room.world());
        if (w == null) {
            backAt.clear();
            lastFeet.clear();
            return;
        }
        double cx = (pit.minX() + pit.maxX() + 1) / 2.0;
        double cz = (pit.minZ() + pit.maxZ() + 1) / 2.0;
        boolean near = false;
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR && Math.abs(p.getX() - cx) <= NEAR && Math.abs(p.getZ() - cz) <= NEAR
                    && Math.abs(p.getY() - pit.minY()) <= 4) {
                near = true;
                break;
            }
        }
        if (!near && backAt.isEmpty()) {
            lastFeet.clear();
            return; // nobody about and every ball at its place
        }
        List<ClubDecor.Placed> placed = decor.placed("arcade.ball.");
        if (placed.isEmpty()) {
            return;
        }
        Map<UUID, Ball> balls = new HashMap<>();
        for (ClubDecor.Placed b : placed) {
            ClubhousePlanner.Prop p = b.prop();
            balls.put(b.id(), new Ball(b.id(), p.x(), p.y(), p.z(), p.sx() / 2.0));
        }
        backAt.keySet().retainAll(balls.keySet());
        int moves = 0;
        // 1) the balls whose time is up roll back to their places
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(backAt.entrySet())) {
            if (moves >= MOVES) {
                break;
            }
            if (e.getValue() <= ticks) {
                Ball b = balls.get(e.getKey());
                if (move(w, b, b.x(), b.y(), b.z(), BACK_TICKS)) {
                    moves++;
                }
                backAt.remove(e.getKey());
            }
        }
        if (!near) {
            return;
        }
        // 2) everyone in the pit pushes the balls round them
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BoundingBox inPit = new BoundingBox(pit.minX(), pit.minY() - 0.2, pit.minZ(), pit.maxX() + 1, pit.maxY() + 1.5,
                pit.maxZ() + 1);
        Set<UUID> seen = new HashSet<>();
        for (Entity e : w.getNearbyEntities(inPit, BallPit::body)) {
            seen.add(e.getUniqueId());
            double fx = e.getX();
            double fy = e.getY();
            double fz = e.getZ();
            double[] was = lastFeet.put(e.getUniqueId(), new double[]{fx, fy, fz});
            double speed = was == null ? 0.3 : Math.hypot(fx - was[0], fz - was[2]) + Math.max(0, was[1] - fy) * 1.5;
            boolean still = speed < 0.02;
            if (still && ticks % 8 != 0) {
                continue; // standing still: a nudge now and then, no more
            }
            for (Ball b : balls.values()) {
                if (moves >= MOVES) {
                    break;
                }
                if (backAt.containsKey(b.id()) && !still) {
                    continue; // already on its way
                }
                double[] to = pushed(pit, b.r(), b.x(), b.y(), b.z(), fx, fz, speed, r.nextDouble());
                if (to != null && move(w, b, to[0], to[1], to[2], PUSH_TICKS)) {
                    backAt.put(b.id(), ticks + PUSH_TICKS + REST_TICKS + r.nextInt(6));
                    moves++;
                }
            }
        }
        lastFeet.keySet().retainAll(seen);
        // 3) a gentle jiggle while anyone is near
        if (ticks % JIGGLE_EVERY == 0) {
            List<Ball> all = new ArrayList<>(balls.values());
            for (int n = 0; n < JIGGLE_BALLS && moves < MOVES && !all.isEmpty(); n++) {
                Ball b = all.get(r.nextInt(all.size()));
                if (backAt.containsKey(b.id())) {
                    continue;
                }
                double[] to = keep(pit, b.r(), b.x() + (r.nextDouble() - 0.5) * 2 * JIGGLE,
                        b.y() + r.nextDouble() * JIGGLE, b.z() + (r.nextDouble() - 0.5) * 2 * JIGGLE);
                if (move(w, b, to[0], to[1], to[2], BACK_TICKS)) {
                    backAt.put(b.id(), ticks + BACK_TICKS);
                    moves++;
                }
            }
        }
    }

    /** What moves the balls: anything alive in the pit (a player who isn't a spectator, an NPC regular), never a stand. */
    private static boolean body(Entity e) {
        return e instanceof LivingEntity && !(e instanceof ArmorStand)
                && !(e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR);
    }

    /** Slide ball {@code b}'s display to (x, y, z) over {@code duration} ticks. False when it isn't there. */
    private static boolean move(World w, Ball b, double x, double y, double z, int duration) {
        if (b == null || !(Bukkit.getEntity(b.id()) instanceof Display d) || !d.isValid()) {
            return false;
        }
        d.setTeleportDuration(duration);
        Location to = new Location(w, x, y, z, d.getYaw(), d.getPitch());
        d.teleport(to);
        return true;
    }

    /** Everything back where it was (the room closing): only forgotten here; the displays come down with ClubDecor's. */
    void clear() {
        backAt.clear();
        lastFeet.clear();
    }
}
