package com.dierks.homecraft.games.gen.rings;

import java.util.ArrayList;
import java.util.List;

/**
 * A Sky Rings ring as blocks (GEN-SPEC §4.2): a one-block-thick voxel circle round a centre block,
 * every block whose offset (u, v) in the ring's plane has {@code R − 0.5 ≤ √(u² + v²) < R + 0.5}.
 *
 * <p><b>Blocks, not particles.</b> Bedrock can't rely on particles and Geyser doesn't draw display
 * entities, so a ring is concrete everyone sees. <b>Upright and square to x or z</b>: a ring stands
 * in the plane whose normal (x or z) is nearer the way the flight goes, because a pixel circle on
 * a diagonal looks broken; the layouts keep every approach and departure within
 * {@value #MAX_OFF_NORMAL}° of that normal, so nobody flies through a ring edge-on.
 */
public final class RingShape {

    /** The most a flight through a ring may be off its normal, in degrees. */
    public static final double MAX_OFF_NORMAL = 35;

    /** The axis a ring faces along. */
    public enum Normal {
        /** The ring stands in a y-z plane (flights go east or west through it). */
        X,
        /** The ring stands in an x-y plane (flights go north or south). */
        Z
    }

    private RingShape() {
    }

    /** The blocks of a ring of radius {@code r} round block (cx, cy, cz), facing {@code normal}. */
    public static List<int[]> voxels(int cx, int cy, int cz, int r, Normal normal) {
        List<int[]> out = new ArrayList<>();
        int reach = r + 1;
        for (int u = -reach; u <= reach; u++) {
            for (int v = -reach; v <= reach; v++) {
                if (inFrame(u, v, r)) {
                    out.add(normal == Normal.Z ? new int[]{cx + u, cy + v, cz} : new int[]{cx, cy + v, cz + u});
                }
            }
        }
        return out;
    }

    /** Whether in-plane offset (u, v) from the centre block is part of a ring of radius {@code r}. */
    public static boolean inFrame(int u, int v, int r) {
        double d = Math.sqrt((double) u * u + (double) v * v);
        return d >= r - 0.5 && d < r + 0.5;
    }

    /** The normal nearer a flight heading along (hx, hz): x when it runs more east-west, else z. */
    public static Normal normalFor(double hx, double hz) {
        return Math.abs(hx) > Math.abs(hz) ? Normal.X : Normal.Z;
    }

    /** How far a heading along (hx, hz) is off a ring's normal, in degrees (0 to 90). */
    public static double offNormal(double hx, double hz, Normal normal) {
        double along = normal == Normal.X ? Math.abs(hx) : Math.abs(hz);
        double across = normal == Normal.X ? Math.abs(hz) : Math.abs(hx);
        return StrictMath.toDegrees(StrictMath.atan2(across, along));
    }
}
