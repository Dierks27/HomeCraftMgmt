package com.dierks.homecraft.games.lava;

/**
 * 0.57.0: where someone in the playroom ends up when the furniture moves (a new layout every round of Group and
 * Challenge, a piece sinking into the lava). Pure: the room asks it for every player, and the NPCs' walk asks it for
 * every NPC, so both agree.
 *
 * <ul>
 *   <li><b>On a piece that moved</b>: they ride along. The same spot of the same piece, wherever the piece is now
 *       (turned with it), so nobody standing on the sofa is dropped into the lava when it slides across the room.</li>
 *   <li><b>On a piece that sank</b>: down to whatever is under them now (the lava, most likely: that was the
 *       wobble's warning).</li>
 *   <li><b>Where a piece arrived</b>: lifted onto it (the highest place to stand in their column, else the nearest
 *       dry place), never left inside a block.</li>
 *   <li>Anyone else stays where they are.</li>
 * </ul>
 */
public final class LavaShift {

    private LavaShift() {
    }

    /**
     * Where feet at (x, y, z) in {@code from} go when the room becomes {@code to}, with the lava at {@code level};
     * {@code null} when they stay where they are.
     */
    public static LavaSite.Spot carry(LavaSite from, LavaSite to, double x, double y, double z, int level) {
        LavaSite.Stand on = from.standUnder(x, y, z);
        int slot = on == null ? -1 : from.pieceOf(on);
        if (slot >= 0) {
            LavaLayout.Placed was = slot < from.layout().size() ? from.layout().get(slot) : null;
            LavaLayout.Placed now = slot < to.layout().size() ? to.layout().get(slot) : null;
            if (was != null && !was.equals(now)) {
                if (now == null) { // it sank: down to whatever is under them now
                    LavaSite.Stand below = highest(to, on.x(), on.z(), on.y());
                    return below == null ? null : spot(to, below, x - on.x(), z - on.z(), was, was);
                }
                int bx = to.box().minX();
                int bz = to.box().minZ();
                int[] local = was.local(on.x() - bx, on.z() - bz);
                if (local != null) {
                    int[] at = now.at(local[0], local[1]);
                    LavaSite.Stand land = nearestIn(to, bx + at[0], bz + at[1], on.y());
                    if (land != null) {
                        return spot(to, land, x - on.x(), z - on.z(), was, now);
                    }
                }
                LavaSite.Spot dry = to.nearestSafe(level, x, y, z);
                return dry;
            }
        }
        if (to.collides(x, y, z)) {
            LavaSite.Stand top = highest(to, (int) Math.floor(x), (int) Math.floor(z), Double.MAX_VALUE);
            if (top != null && !to.collides(top.x() + 0.5, top.y(), top.z() + 0.5)) {
                return to.spot(top);
            }
            return to.nearestSafe(level, x, y, z);
        }
        return null;
    }

    /** The highest stand of a column at most {@code atMost} high, or {@code null}. */
    private static LavaSite.Stand highest(LavaSite site, int x, int z, double atMost) {
        LavaSite.Stand best = null;
        for (LavaSite.Stand s : site.column(x, z)) {
            if (s.y() <= atMost + 1e-9 && (best == null || s.y() > best.y())) {
                best = s;
            }
        }
        return best;
    }

    /** The stand of a column nearest {@code y}, or {@code null}. */
    private static LavaSite.Stand nearestIn(LavaSite site, int x, int z, double y) {
        LavaSite.Stand best = null;
        for (LavaSite.Stand s : site.column(x, z)) {
            if (best == null || Math.abs(s.y() - y) < Math.abs(best.y() - y)) {
                best = s;
            }
        }
        return best;
    }

    /** Feet on {@code s}, where they were on their block (turned with the piece), facing the way they did. */
    private static LavaSite.Spot spot(LavaSite site, LavaSite.Stand s, double fx, double fz, LavaLayout.Placed was,
                                      LavaLayout.Placed now) {
        int turn = Math.floorMod(now.turns() - was.turns(), 4);
        double ox = Math.max(0.3, Math.min(0.7, fx));
        double oz = Math.max(0.3, Math.min(0.7, fz));
        double rx = ox;
        double rz = oz;
        switch (turn) {
            case 1 -> {
                rx = 1 - oz;
                rz = ox;
            }
            case 2 -> {
                rx = 1 - ox;
                rz = 1 - oz;
            }
            case 3 -> {
                rx = oz;
                rz = 1 - ox;
            }
            default -> {
                // not turned
            }
        }
        return new LavaSite.Spot(s.x() + rx, s.y(), s.z() + rz, site.facing(s.x() + rx, s.z() + rz));
    }
}
