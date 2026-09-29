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

    /**
     * The stand of course {@code c} in {@code half}: its spot for a Fresh boat layout of algo
     * {@value #FIRST_ALGO} or later, else {@code null} (no stand: finishers go home at the line).
     */
    public static Point of(Course c, Box half) {
        if (c == null || half == null || c.start() == null || c.kind() != TrialKind.BOAT || !has(c.gen())) {
            return null;
        }
        return spot(half, c.start().y());
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
