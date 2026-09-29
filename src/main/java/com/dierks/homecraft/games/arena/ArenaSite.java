package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Cell;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.List;

/**
 * One week's Falling Floors arena (EVENTS-DROPPER-SPEC §B.3.2): the plan the reset converges the box
 * to, the floors the round rules play on, where players start, and the gallery round the edge.
 *
 * <p>Why one record holds both the {@link Plan} and the {@link FloorLayout}: the cells a round may
 * turn red must be exactly the cells the reset puts back. The layout is made from the plan
 * ({@link FloorLayout#fromPlan}), so the two can't drift apart, and the reset always converges the
 * box to this plan whatever a round did to it.
 *
 * <p>Pure and immutable. {@link ArenaPlanner} makes one from (the box, the seed, the week), and
 * {@link ArenaValidator} checks it without trusting the planner.
 *
 * @param plan         every block of the box: the three floors and the gallery (air everywhere else)
 * @param layout       the floors as the round rules see them
 * @param week         the week key the shape was made for (the boards turn with it)
 * @param shapes       each floor's shape, top first ({@code ring}, {@code disc}, ...)
 * @param floorBlocks  each floor's own block, top first (yellow, pink, light blue stained glass)
 * @param galleryY     the gallery walk's block height; players stand at {@code galleryY + 1}
 * @param gallerySpots where players are put in the gallery, spread round it, each facing the middle
 */
public record ArenaSite(Plan plan, FloorLayout layout, long week, List<String> shapes, List<String> floorBlocks,
                        int galleryY, List<Spot> gallerySpots) {

    /**
     * A place to put a player: the feet and which way they face.
     *
     * @param yaw Minecraft yaw (0 = south, 90 = west)
     */
    public record Spot(double x, double y, double z, float yaw) {
    }

    public ArenaSite {
        if (plan == null || layout == null) {
            throw new IllegalArgumentException("a site needs its plan and its floors");
        }
        shapes = List.copyOf(shapes == null ? List.of() : shapes);
        floorBlocks = List.copyOf(floorBlocks == null ? List.of() : floorBlocks);
        gallerySpots = List.copyOf(gallerySpots == null ? List.of() : gallerySpots);
    }

    /** The arena's box (the plan's half). */
    public Box box() {
        return plan.half();
    }

    /** This week's shape, as the website shows it: the top floor's. */
    public String shape() {
        return shapes.isEmpty() ? null : shapes.get(0);
    }

    /** Where player {@code i}'s round starts: the middle of top-floor spawn {@code i}, facing the middle. */
    public Spot spawn(int i) {
        return spawn(layout, i);
    }

    /**
     * Spawn {@code i} of {@code floors} (the round's own floors, which are this site's unless a new
     * week's came in while it was going), facing the middle of this box.
     */
    public Spot spawn(FloorLayout floors, int i) {
        Cell c = floors.spawns().get(Math.floorMod(i, floors.spawns().size()));
        double x = c.centerX();
        double z = c.centerZ();
        return new Spot(x, floors.topY(0), z, facing(x, z));
    }

    /** Gallery spot {@code n}, round and round. */
    public Spot gallery(int n) {
        return gallerySpots.get(Math.floorMod(n, gallerySpots.size()));
    }

    /**
     * Whether feet at (x, y, z) are in the gallery: over its 4-wide ring and at its height (standing
     * on the walk, or jumping on it). Everyone else inside the box is on or over the floors.
     */
    public boolean inGallery(double x, double y, double z) {
        Box b = box();
        if (!b.contains(x, y, z) || y < galleryY + 1 - 0.5 || y > galleryY + 1 + ArenaPlanner.RAIL_HEIGHT + 1) {
            return false;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        return ArenaPlanner.ringDepth(b, bx, bz) < ArenaPlanner.GALLERY_WIDTH;
    }

    /** The yaw that looks from (x, z) at the middle of the box. */
    public float facing(double x, double z) {
        Box b = box();
        double cx = (b.minX() + b.maxX() + 1) / 2.0;
        double cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        return yaw(cx - x, cz - z);
    }

    /** Minecraft's yaw for a direction (dx, dz): 0 looks south (+z), 90 west (-x). */
    static float yaw(double dx, double dz) {
        if (dx == 0 && dz == 0) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
