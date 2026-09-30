package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * The mountain round the Mountain Run (Course Variety §2.5 "Scenery", §2.2): what turns a spiral of
 * ice into a stepped mountain seen from the stand.
 *
 * <ul>
 *   <li><b>Terraces.</b> Between two neighbouring rings a single moss layer at the higher (outer)
 *       ring's ice level fills the grass strip, and the lower ring's outer wall is carried up to it
 *       as a stripped-spruce cliff ({@link TrackRaster#walls}).</li>
 *   <li><b>The summit cone.</b> Stepped moss under the stand's platform, one block in per layer, a
 *       shell from the lowest ring's level up to the scenery cap.</li>
 *   <li><b>Trees</b> (oak, birch, cherry, real persistent leaves at vanilla's own distance) on the
 *       terraces, the cone and the half's corners.</li>
 * </ul>
 * Every block of it keeps the proof's scenery rules: only moss, logs and leaves, never within 2
 * columns of the track (nothing beside or over it but the walls), at least 2 columns inside the half,
 * and never above the cap (the stand's floor less one), so only the stand stands at the stand's
 * heights and the view from it is clear. Seeded ({@code fork("scenery:" + t)}): which trees, where,
 * of which wood. Pure: no Bukkit.
 */
final class BoatScenery {

    /** A tree: logs from its ground + 1 to + {@value #TRUNK}, leaves to + {@value #HEIGHT}. */
    static final int TRUNK = 4;
    static final int HEIGHT = 5;
    /** Its canopy reaches this many columns round the trunk. */
    static final int SPREAD = 2;
    /** Trunks keep this far apart (across the ground, the larger of x and z). */
    static final int APART = 6;
    /** The most trees a run has. */
    static final int MAX_TREES = 28;
    /** A corner tree's moss ground, over the half's floor. */
    static final int CORNER_GROUND = 6;

    private BoatScenery() {
    }

    /**
     * Each column's terrace level: for a column that isn't track or beside it, the ice level of the
     * nearest ring it lies inside (within the pitch and a bit), or NONE (outside the outermost ring,
     * or too far in).
     */
    static int[][] terraces(TrackRaster t) {
        int[][] out = new int[t.sx][t.sz];
        double reach = 2.0 * t.path.pitch;
        for (int x = 0; x < t.sx; x++) {
            for (int z = 0; z < t.sz; z++) {
                out[x][z] = TrackRaster.NONE;
                if (!far(t, x, z)) {
                    continue;
                }
                TrackPath.Near n = t.path.nearestInside(x, z, reach, t.profile.end);
                if (n != null) {
                    out[x][z] = Math.min(t.top, t.profile.level(n.s));
                }
            }
        }
        return out;
    }

    /** Whether column (x, z) is far from the track: not track, not beside it, not an island, inside the inset. */
    static boolean far(TrackRaster t, int x, int z) {
        return t.inside(x, z) && !t.drive(x, z) && !t.beside(x, z) && !t.obstacle[x][z] && !t.atEdge(x, z);
    }

    /** The terraces' moss, the cone, and the trees; how many trees. */
    static int draw(GenRandom r, TrackRaster t) {
        int[][] ground = new int[t.sx][t.sz];
        for (int x = 0; x < t.sx; x++) {
            for (int z = 0; z < t.sz; z++) {
                ground[x][z] = TrackRaster.NONE;
                if (!far(t, x, z)) {
                    continue;
                }
                int y = t.terrace[x][z];
                // the cone: one block in per layer from the platform's edge, down to the lowest ring
                int c = Math.max(Math.abs(x - t.standX), Math.abs(z - t.standZ));
                int cone = t.top - Math.max(0, c - com.dierks.homecraft.games.trial.RaceStand.SIZE / 2);
                if (cone >= t.profile.bottom() && cone > y) {
                    y = cone;
                }
                if (y != TrackRaster.NONE && t.empty(x, y, z)) {
                    t.put(x, y, z, Palette.MOSS);
                    ground[x][z] = y;
                }
            }
        }
        int trees = 0;
        List<int[]> spots = new ArrayList<>();
        for (int x = 0; x < t.sx; x++) {
            for (int z = 0; z < t.sz; z++) {
                if (ground[x][z] != TrackRaster.NONE && ground[x][z] + HEIGHT <= t.top) {
                    spots.add(new int[]{x, z});
                }
            }
        }
        for (int i = spots.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int[] tmp = spots.get(i);
            spots.set(i, spots.get(j));
            spots.set(j, tmp);
        }
        List<int[]> trunks = new ArrayList<>();
        for (int[] s : spots) {
            if (trunks.size() >= MAX_TREES) {
                break;
            }
            if (tree(r, t, trunks, s[0], s[1], ground[s[0]][s[1]], false)) {
                trees++;
            }
        }
        // a tree in each corner of the half, on a moss ground of its own
        int in = DownhillValidator.SCENERY_INSET + SPREAD + 2;
        int[][] corners = {{in, in}, {t.sx - 1 - in, in}, {in, t.sz - 1 - in}, {t.sx - 1 - in, t.sz - 1 - in}};
        for (int[] c : corners) {
            int g = t.h0 + CORNER_GROUND;
            if (g + HEIGHT > t.top || !t.empty(c[0], g, c[1])) {
                continue;
            }
            if (tree(r, t, trunks, c[0], c[1], g, true)) {
                trees++;
            }
        }
        return trees;
    }

    /** A tree at (x, z) on ground {@code g} if it fits there, far from the track and the other trees. */
    private static boolean tree(GenRandom r, TrackRaster t, List<int[]> trunks, int x, int z, int g, boolean placeGround) {
        for (int[] o : trunks) {
            if (Math.max(Math.abs(o[0] - x), Math.abs(o[1] - z)) < APART) {
                return false;
            }
        }
        for (int dx = -SPREAD; dx <= SPREAD; dx++) {
            for (int dz = -SPREAD; dz <= SPREAD; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (!far(t, nx, nz)) {
                    return false;
                }
                for (int y = g + 1; y <= g + HEIGHT; y++) {
                    if (!t.empty(nx, y, nz)) {
                        return false;
                    }
                }
            }
        }
        if (placeGround) {
            if (!t.empty(x, g, z)) {
                return false;
            }
            t.put(x, g, z, Palette.MOSS);
        }
        String wood = Palette.WOODS.get(r.nextInt(Palette.WOODS.size()));
        for (int y = g + 1; y <= g + TRUNK; y++) {
            t.put(x, y, z, Palette.log(wood));
        }
        for (int y = g + TRUNK - 1; y <= g + TRUNK; y++) {
            for (int dx = -SPREAD; dx <= SPREAD; dx++) {
                for (int dz = -SPREAD; dz <= SPREAD; dz++) {
                    if ((dx == 0 && dz == 0) || (Math.abs(dx) == SPREAD && Math.abs(dz) == SPREAD)) {
                        continue;
                    }
                    t.leaf(x + dx, y, z + dz, wood);
                }
            }
        }
        int y = g + HEIGHT;
        t.leaf(x, y, z, wood);
        for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            t.leaf(x + s[0], y, z + s[1], wood);
        }
        trunks.add(new int[]{x, z});
        return true;
    }
}
