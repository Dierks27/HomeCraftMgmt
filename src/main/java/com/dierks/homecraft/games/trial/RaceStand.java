package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.List;

/**
 * Fresh Ice Boat's viewing stand (EVENTS-DROPPER-SPEC §A.4.2), where finishers wait while the
 * others race: pure, shared by the planner that builds it and the races that park racers on it.
 *
 * <p>A {@value #SIZE} × {@value #SIZE} platform at the half's centre, its top {@value #ABOVE} above
 * the start (so {@value #ABOVE} above the race line, well clear of the ice and its headroom), with a
 * two-high glass rail round the edge so players stand on the inner 5 × 5. The loop's radius is at
 * least 25.6 and the track at most 9 wide, so the stand is more than 12 blocks from any ice, and a
 * boat can't reach it. Only layouts from boat planner algo {@value #FIRST_ALGO} on have one: an
 * older layout's stored plan is kept as it was.
 *
 * <p><b>Mountain Run v2</b> (boat planner algo {@value #FIRST_V4_ALGO} on, MOUNTAIN-V2-SPEC §4.1, §7.4) puts
 * the stand at the bottom, by the finish: its centre column is the half's middle x, {@value #STAND_BACK} in
 * from the half's south (high-z) edge, and players stand {@value #ABOVE} above the FINISH ({@link #spotV4}).
 * Racers park where they finish and look north ({@link #FACING_V4}) over the finish straight to the lower
 * face of the mountain; the summit is 500+ blocks away, beyond any view distance, so nothing promises it.
 * {@link #spotV4} is the proof's own spot ({@code MountainValidator.standSpot}), pinned equal by a test.
 * Algo 2 and 3 keep the stand at the half's centre, 5 above the start, exactly as before.
 */
public final class RaceStand {

    /** The platform is this many blocks square. */
    public static final int SIZE = 7;
    /** Its top (where players stand) is this far above the course's start. */
    public static final int ABOVE = 5;
    /** The rail is this many blocks high. */
    public static final int RAIL = 2;
    /** The first boat planner version that builds it. */
    public static final int FIRST_ALGO = 2;
    /** The stand is at least this far (block to block, across the ground) from any ice. */
    public static final double LANE_CLEARANCE = 12;
    /** Its sign, on the platform facing the middle: "RACE NIGHT / Watch from / here!". */
    public static final List<String> SIGN = List.of("RACE NIGHT", "Watch from", "here!");
    /** The first boat planner version whose stand is at the bottom, by the finish (Mountain Run v2). */
    public static final int FIRST_V4_ALGO = 4;
    /** Mountain Run v2: the stand's centre row is this far in from the half's south (high-z) edge (§4.1). */
    public static final int STAND_BACK = 40;
    /** Mountain Run v2: the way a player on the stand faces, north, up the mountain (a Minecraft yaw). */
    public static final float FACING_V4 = 180f;
    /** The platform's block (white concrete). */
    public static final String FLOOR = "minecraft:white_concrete";
    /** The rail's block (glass), two high round the platform's edge. */
    public static final String RAIL_BLOCK = "minecraft:glass";

    private RaceStand() {
    }

    /** The block column at the middle of the half: the stand's centre column. */
    public static int centreX(Box half) {
        return (int) Math.floor(half.minX() + half.sizeX() / 2.0);
    }

    /** The block row at the middle of the half. */
    public static int centreZ(Box half) {
        return (int) Math.floor(half.minZ() + half.sizeZ() / 2.0);
    }

    /** The platform's block height for a course starting at {@code startY}: one under where players stand. */
    public static int floorY(double startY) {
        return (int) Math.floor(startY) + ABOVE - 1;
    }

    /** Whether column (x, z) is on the platform of the stand centred on ({@code cx}, {@code cz}). */
    public static boolean onPlatform(int x, int z, int cx, int cz) {
        return Math.abs(x - cx) <= SIZE / 2 && Math.abs(z - cz) <= SIZE / 2;
    }

    /** Whether column (x, z) is on the platform's edge, where the rail stands. */
    public static boolean onRail(int x, int z, int cx, int cz) {
        return onPlatform(x, z, cx, cz) && (Math.abs(x - cx) == SIZE / 2 || Math.abs(z - cz) == SIZE / 2);
    }

    /** Where a player stands on the stand of a half whose course starts at height {@code startY}. */
    public static Point spot(Box half, double startY) {
        return new Point(centreX(half) + 0.5, startY + ABOVE, centreZ(half) + 0.5);
    }

    /** Mountain Run v2: the stand's centre row, {@value #STAND_BACK} in from the half's south edge. */
    public static int centreZV4(Box half) {
        return half.minZ() + half.sizeZ() - STAND_BACK;
    }

    /**
     * Mountain Run v2: where a player stands on the stand of a half whose course finishes at height
     * {@code finishY} (§4.1): the middle of the half across x, {@value #STAND_BACK} in from its south edge,
     * {@value #ABOVE} above the finish. The same point as {@code MountainValidator.standSpot}, which proves it.
     */
    public static Point spotV4(Box half, double finishY) {
        return new Point(centreX(half) + 0.5, finishY + ABOVE, centreZV4(half) + 0.5);
    }

    /**
     * The stand of course {@code c} in {@code half}: for a Fresh boat layout of algo {@value #FIRST_V4_ALGO}
     * or later the one at the bottom ({@link #spotV4}, by the finish), for algo {@value #FIRST_ALGO} or 3 the
     * one at the half's centre over the start ({@link #spot}), else {@code null} (no stand: finishers go home
     * at the line).
     */
    public static Point of(Course c, Box half) {
        if (c == null || half == null || c.start() == null || c.kind() != TrialKind.BOAT || !has(c.gen())) {
            return null;
        }
        if (c.gen().algo() >= FIRST_V4_ALGO) {
            return c.finish() == null ? null : spotV4(half, c.finish().y());
        }
        return spot(half, c.start().y());
    }

    /**
     * The way a racer parked on the stand of {@code c} faces: north up the mountain ({@link #FACING_V4}) on a
     * Mountain Run v2, else {@code otherwise} (the way they were facing, as before).
     */
    public static float facing(Course c, float otherwise) {
        return c != null && c.kind() == TrialKind.BOAT && c.gen() != null && has(c.gen())
                && c.gen().algo() >= FIRST_V4_ALGO ? FACING_V4 : otherwise;
    }

    /**
     * Whether players can stand at {@code spot} as the world is now (a solid floor under it and two
     * blocks of air): a stand that isn't there (not built yet, or cleared) is never used, and
     * finishers go home at the line instead.
     */
    public static boolean standable(RaceGrid.Surface surface, Point spot) {
        if (surface == null || spot == null) {
            return false;
        }
        int x = (int) Math.floor(spot.x());
        int y = (int) Math.floor(spot.y());
        int z = (int) Math.floor(spot.z());
        return surface.at(x, y - 1, z) == RaceGrid.Cell.SOLID && surface.at(x, y, z) == RaceGrid.Cell.AIR
                && surface.at(x, y + 1, z) == RaceGrid.Cell.AIR;
    }

    /** Whether a layout's tag says it was built with a stand. */
    public static boolean has(GenTag tag) {
        return tag != null && Slots.BOAT.equals(tag.generator()) && tag.algo() >= FIRST_ALGO;
    }
}
