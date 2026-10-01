package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scenery on every golf plot (Course Variety §3.10): 2-5 decoration trees of oak, birch or cherry,
 * each on a 3 x 3 moss planter at T - 1, round the hole but never in it.
 *
 * <p><b>Why it can't change a proof.</b> Every block of a tree — planter, trunk and canopy — stands
 * at least {@value #GAP} columns outside the hole's bounds, so outside the physics grid (the bounds
 * and one more round them: {@code GolfPlanner.plotBox}); the ball never meets it. It is drawn with
 * the plan's {@code scenery:<hole>} stream only after the hole is accepted, so it never changes
 * which attempt won. It stays inside the hole's plot, its leaves {@value #INSIDE} columns inside the
 * half (a block outside could otherwise change a leaf's distance), its top never above T + 6, and
 * clear of the hole's own scenery (Easy's pond to look at). Every leaf gets the distance vanilla
 * gives it ({@link Palette#leafDistances}), so the build's verify pass never sees it change. Pure.
 */
final class GolfScenery {

    /** Trees per plot, at least and at most (fewer when the hole leaves no room). */
    static final int LEAST = 2;
    static final int MOST = 5;
    /** Columns every tree block keeps clear of the hole's bounds. */
    static final int GAP = GolfValidatorV3.SCENERY_GAP;
    /** Columns every leaf keeps inside the half. */
    static final int INSIDE = GolfValidatorV3.CANOPY_INSIDE;
    /** Trunks at least this far apart (Chebyshev): canopies never touch. */
    static final int APART = 4;

    private GolfScenery() {
    }

    /**
     * The trees for the hole {@code hole} drawn in the plot at ({@code plotX}, {@code plotZ}) of
     * {@code half}, from {@code r}.
     */
    static List<HoleLayout.Placed> trees(GenRandom r, HoleLayout hole, int plotX, int plotZ, Box half) {
        int turf = hole.turfY();
        Box b = hole.bounds();
        Set<Long> taken = new HashSet<>();
        for (HoleLayout.Placed p : hole.scenery()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    taken.add(column(p.x() + dx, p.z() + dz));
                }
            }
        }
        List<int[]> spots = new ArrayList<>();
        for (int x = plotX + 1; x <= plotX + HoleTemplate.PLOT_X - 2; x++) {
            for (int z = plotZ + 1; z <= plotZ + HoleTemplate.PLOT_Z - 2; z++) {
                if (fits(x, z, b, half, taken)) {
                    spots.add(new int[]{x, z});
                }
            }
        }
        int want = r.nextInt(LEAST, MOST);
        List<HoleLayout.Placed> out = new ArrayList<>();
        List<int[]> trunks = new ArrayList<>();
        while (trunks.size() < want && !spots.isEmpty()) {
            int[] at = spots.remove(r.nextInt(spots.size()));
            String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
            int tall = r.nextInt(3, 4);
            tree(out, at[0], at[1], turf, wood, tall);
            trunks.add(at);
            spots.removeIf(s -> Math.max(Math.abs(s[0] - at[0]), Math.abs(s[1] - at[1])) < APART);
        }
        return out;
    }

    /**
     * Whether a tree's 3 x 3 footprint round (x, z) keeps {@value #GAP} columns clear of the
     * bounds, {@value #INSIDE} inside the half, and off the hole's own scenery.
     */
    private static boolean fits(int x, int z, Box bounds, Box half, Set<Long> taken) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                boolean near = cx >= bounds.minX() - (GAP - 1) && cx <= bounds.maxX() + (GAP - 1)
                        && cz >= bounds.minZ() - (GAP - 1) && cz <= bounds.maxZ() + (GAP - 1);
                boolean edge = cx < half.minX() + INSIDE || cx > half.maxX() - INSIDE
                        || cz < half.minZ() + INSIDE || cz > half.maxZ() - INSIDE;
                if (near || edge || taken.contains(column(cx, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * One tree: a 3 x 3 moss planter at T - 1, a trunk of {@code tall} logs from T, a ring of leaves
     * round its top log, a full 3 x 3 layer above it and a plus on top (at most T + 5).
     */
    private static void tree(List<HoleLayout.Placed> out, int x, int z, int turf, String wood, int tall) {
        List<int[]> logs = new ArrayList<>();
        List<int[]> leaves = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out.add(new HoleLayout.Placed(x + dx, turf - 1, z + dz, Palette.MOSS));
            }
        }
        int top = turf + tall - 1;
        for (int y = turf; y <= top; y++) {
            out.add(new HoleLayout.Placed(x, y, z, Palette.log(wood)));
            logs.add(new int[]{x, y, z});
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    leaves.add(new int[]{x + dx, top, z + dz});
                }
                leaves.add(new int[]{x + dx, top + 1, z + dz});
                if (dx == 0 || dz == 0) {
                    leaves.add(new int[]{x + dx, top + 2, z + dz});
                }
            }
        }
        Map<Long, Integer> d = Palette.leafDistances(logs, leaves);
        for (int[] l : leaves) {
            out.add(new HoleLayout.Placed(l[0], l[1], l[2], Palette.leaves(wood,
                    d.get(Palette.blockKey(l[0], l[1], l[2])))));
        }
    }

    private static long column(int x, int z) {
        return Palette.blockKey(x, 0, z);
    }
}
