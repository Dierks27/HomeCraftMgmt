package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.V4Fixtures;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wide road's raster (MOUNTAIN-V21-SPEC §5.3-§5.4, boat planner algo 5), on real plans: the lane keeps its
 * width everywhere and every leg its bound, the wall arrows point the way on every face a rider sees and wear a
 * wood cap, the forest's trees and the split's island tree are round, and the slow ground is snow with signs that
 * say so. Algo 4 keeps 0.37.0's look (its necks, sandstone and sand signs), and its frozen fixtures stay block
 * for block (V4FixturesTest).
 */
class RasterV4Test {

    private static final String[] FACES = {"N", "S", "E", "W"};

    static MountainPlanner.Made made(long seed, String style, String tier) {
        MountainPlanner.Made m = MountainPlannerTest.made(MountainPlannerTest.HALF_A, seed, tier);
        assertEquals(style, m.tier.style.id(), "seed " + seed + " is a " + style + " seed");
        assertTrue(m.tier.wide(), "today's planner makes the wide road");
        return m;
    }

    /** The plans the arrow tests read: road easy, medium and hard seeds 2 and 4, and slalom medium seed 1. */
    static List<MountainPlanner.Made> arrowPlans() {
        List<MountainPlanner.Made> out = new ArrayList<>();
        for (String tier : List.of("easy", "medium", "hard")) {
            for (long seed : new long[]{2, 4}) {
                out.add(made(seed, "road", tier));
            }
        }
        out.add(made(1, "slalom", "medium"));
        return out;
    }

    static String name(MountainPlanner.Made m) {
        return m.tier.style.id() + " " + m.tier.id + " seed " + m.in.seed();
    }

    /** Every arrow block of {@code m}'s raster: {x, y, z} half-local, and its block text. */
    static List<Object[]> arrows(MountainPlanner.Made m) {
        RasterV4 r = m.raster;
        List<Object[]> out = new ArrayList<>();
        for (int x = 0; x < r.sx; x++) {
            for (int z = 0; z < r.sz; z++) {
                if (!r.vox.any(x, z)) {
                    continue;
                }
                for (int[] b : r.vox.column(x, z)) {
                    String id = r.vox.palette.get(b[1]);
                    if (id.startsWith(Palette.ARROW + "[")) {
                        out.add(new Object[]{new int[]{x, b[0], z}, id});
                    }
                }
            }
        }
        return out;
    }

    // ---- the width and the legs ---------------------------------------------------------------------------

    @Test
    void theLaneKeepsItsWidthFromThePitToTheFinish() throws GenFailed {
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                for (long seed : style == BoatStyle.ROAD ? MountainPlannerTest.ROADS : MountainPlannerTest.SLALOMS) {
                    MountainPlanner.Made m = made(seed, style.id(), tier);
                    assertEquals(List.of(), m.raster.narrowAt(), name(m) + ": the lane is " + m.tier.width
                            + " wide at every block and every Big Drop's lip is clear");
                }
            }
        }
        // the check sees a neck: algo 4's hard slalom (11 wide) put its Big Drops in 7-wide necks
        MountainPlanner.Made old = MountainPlanner.made(MountainPlannerTest.input(MountainPlannerTest.HALF_A, 1,
                "hard"), 4);
        assertFalse(old.tier.wide(), "fixture: algo 4's tables");
        List<Double> narrow = old.raster.narrowAt();
        assertFalse(narrow.isEmpty(), "algo 4's necks are narrower than the lane");
        for (DropPlan.Drop d : old.cand.drops().drops) {
            if (d.drop() >= 2) {
                assertTrue(narrow.contains(d.s()), "a necked Big Drop's lip at " + d.s() + " is seen");
            }
        }
    }

    /**
     * Every leg of {@code m}'s course from the start to the finish, through its checkpoints' middles: {from s,
     * to s, across, the most it may be (66 holding a 2-block drop, else 60)}. MountainSoakTest reads it too.
     */
    static List<double[]> legs(MountainPlanner.Made m) {
        RasterV4 r = m.raster;
        List<double[]> marks = new ArrayList<>();
        double[] st = r.sk.line.at(r.sk.start);
        marks.add(new double[]{r.sk.start, st[0], st[1]});
        for (RasterV4.Spot c : m.checkpoints) {
            marks.add(new double[]{c.s(), c.x(), c.z()});
        }
        double[] fp = r.sk.line.at(r.sk.finish);
        marks.add(new double[]{r.sk.finish, fp[0], fp[1]});
        List<double[]> out = new ArrayList<>();
        for (int k = 1; k < marks.size(); k++) {
            double[] a = marks.get(k - 1);
            double[] b = marks.get(k);
            double most = r.dp.bigBetween(a[0], b[0]) ? MountainValidator.MAX_LEG_BIG_DROP : DownhillValidator.MAX_LEG;
            out.add(new double[]{a[0], b[0], Math.hypot(b[1] - a[1], b[2] - a[2]), most});
        }
        return out;
    }

    @Test
    void everyLegKeepsSixtyOrSixtySixAcrossABigDrop() {
        int over = 0;
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                for (long seed : style == BoatStyle.ROAD ? MountainPlannerTest.ROADS : MountainPlannerTest.SLALOMS) {
                    MountainPlanner.Made m = made(seed, style.id(), tier);
                    for (double[] leg : legs(m)) {
                        assertTrue(leg[2] <= leg[3], name(m) + ": the leg from " + Math.round(leg[0]) + " is "
                                + leg[2] + " across; at most " + leg[3]);
                        over += leg[3] > DownhillValidator.MAX_LEG && leg[2] > DownhillValidator.MAX_LEG ? 1 : 0;
                    }
                }
            }
        }
        assertTrue(over > 0, "fixture: legs across Big Drops over 60 were laid: " + over);
    }

    // ---- the arrows ---------------------------------------------------------------------------------------

    /**
     * Where {@code m}'s wall arrows break §5.4's rule, none when it holds: every face of an arrow whose neighbour
     * is a drive column with air at the arrow's height shows it flat, pointing within cos 0.7 of the way there;
     * every arrow faces the lane; a road plan has at least 100. MountainSoakTest reads it too.
     */
    static List<String> arrowProblems(MountainPlanner.Made m) {
        RasterV4 r = m.raster;
        List<String> out = new ArrayList<>();
        List<Object[]> arrows = arrows(m);
        for (Object[] a : arrows) {
            int[] p = (int[]) a[0];
            String facing = WallArrowTest.facing((String) a[1]);
            int seen = 0;
            for (String face : FACES) {
                int[] d = WallArrowTest.dir(face);
                int nx = p[0] + d[0];
                int nz = p[2] + d[2];
                if (!r.drive(nx, nz) || !r.vox.empty(nx, p[1], nz)) {
                    continue;
                }
                seen++;
                String way = WallArrowTest.shown(facing, face);
                int[] w = WallArrowTest.dir(way);
                String where = name(m) + ": the arrow at " + p[0] + " " + p[1] + " " + p[2] + " (facing " + facing
                        + ") on its " + face + " face";
                double[] t = r.sk.line.tangent(r.sAt[r.idx(nx, nz)]);
                double cos = w[0] * t[0] + w[2] * t[1];
                if (w[1] != 0) {
                    out.add(where + " points " + way + ", not flat");
                } else if (!(cos > 0.7)) {
                    out.add(where + " points " + way + ", not along the way (cos " + cos + ")");
                }
            }
            if (seen < 1) {
                out.add(name(m) + ": the arrow at " + p[0] + " " + p[1] + " " + p[2] + " doesn't face the lane");
            }
        }
        if (m.tier.road() && arrows.size() < 100) {
            out.add(name(m) + ": only " + arrows.size() + " arrows");
        }
        return out;
    }

    @Test
    void everyArrowFaceOntoTheLanePointsTheWay() {
        for (MountainPlanner.Made m : arrowPlans()) {
            assertEquals(List.of(), arrowProblems(m), name(m) + ": every arrow face a rider sees points the way ("
                    + arrows(m).size() + " arrows)");
        }
    }

    @Test
    void everyWallArrowHasAWoodCap() {
        for (MountainPlanner.Made m : arrowPlans()) {
            RasterV4 r = m.raster;
            for (Object[] a : arrows(m)) {
                int[] p = (int[]) a[0];
                String where = name(m) + ": the arrow at " + p[0] + " " + p[1] + " " + p[2];
                assertEquals(Palette.TRACK_WALL, r.vox.at(p[0], p[1] + 1, p[2]), where + " has wood over it");
                assertFalse(r.standSide(p[0], p[2]), where + " isn't in the stand's glass wall");
            }
        }
    }

    // ---- the trees ----------------------------------------------------------------------------------------

    /** The first forest of the medium (then hard) road goldens. */
    static Object[] firstForest() {
        for (String tier : List.of("medium", "hard")) {
            for (long seed : MountainPlannerTest.ROADS) {
                MountainPlanner.Made m = made(seed, "road", tier);
                for (PiecesV4.Piece p : m.pieces.list) {
                    if (p.kind == PiecesV4.Kind.FOREST) {
                        return new Object[]{m, p};
                    }
                }
            }
        }
        return null;
    }

    static boolean leaf(String block) {
        return block != null && block.contains("_leaves");
    }

    static boolean log(String block) {
        return block != null && block.endsWith("_log[axis=y]");
    }

    @Test
    void theForestsCrownsAreRound() {
        Object[] found = firstForest();
        assertNotNull(found, "fixture: a medium or hard road golden has a forest");
        MountainPlanner.Made m = (MountainPlanner.Made) found[0];
        PiecesV4.Piece p = (PiecesV4.Piece) found[1];
        RasterV4 r = m.raster;
        Voxels v = r.vox;
        String name = name(m) + ", the forest at " + Math.round(p.s1);
        int ice = r.level((p.s1 + p.s2) / 2);
        // the crowns: the trunks' and the wall trees'
        List<double[]> crowns = new ArrayList<>();
        for (double[] t : p.trunks) {
            double[] c = r.trunkCentre(t);
            crowns.add(new double[]{c[0], c[1], RasterV4.TRUNK_CROWN_FROM, 0});
            // the centre is the trunk square's middle: the mean of its columns (the normal's sign as vAt reads it)
            double sx = 0;
            double sz = 0;
            int n = 0;
            for (int x = 0; x < r.sx; x++) {
                for (int z = 0; z < r.sz; z++) {
                    int i = r.idx(x, z);
                    if (r.near[i] && r.obstacle[i] && r.fence[i] == 0 && r.sAt[i] >= t[0] && r.sAt[i] < t[0] + 2
                            && r.vAt[i] >= t[1] && r.vAt[i] < t[1] + 2) {
                        sx += x + 0.5;
                        sz += z + 0.5;
                        n++;
                    }
                }
            }
            assertTrue(n >= 2, name + ": the trunk at " + t[0] + " stands in the lane (" + n + " columns)");
            assertTrue(Math.hypot(sx / n - c[0], sz / n - c[1]) <= 1.0,
                    name + ": the crown is centred on the trunk at " + t[0]);
            // its logs reach hi + 8, inside the crown
            for (int x = (int) Math.floor(c[0]) - 1; x <= (int) Math.floor(c[0]) + 1; x++) {
                for (int z = (int) Math.floor(c[1]) - 1; z <= (int) Math.floor(c[1]) + 1; z++) {
                    int i = r.idx(x, z);
                    if (r.obstacle[i] && r.fence[i] == 0 && p == r.pieces.at(r.sAt[i])) {
                        assertTrue(log(v.at(x, ice + RasterV4.TRUNK_TOP, z)),
                                name + ": the trunk's log at ice + " + RasterV4.TRUNK_TOP + " at " + x + " " + z);
                    }
                }
            }
        }
        List<int[]> wall = r.wallTreesOf(p);
        boolean left = false;
        boolean right = false;
        for (int[] w : wall) {
            int i = r.idx(w[0], w[1]);
            left |= r.vAt[i] < 0;
            right |= r.vAt[i] > 0;
            for (int y = ice + 1; y <= ice + RasterV4.WALL_TRUNK_TOP; y++) {
                assertTrue(log(v.at(w[0], y, w[1])), name + ": a wall tree's log at " + w[0] + " " + y + " " + w[1]);
            }
            crowns.add(new double[]{w[0] + 0.5, w[1] + 0.5, RasterV4.WALL_CROWN_FROM, 1});
        }
        assertTrue(left && right, name + ": wall trees in both walls (" + wall.size() + ")");
        // the leaves of the crowns, by crown and height
        int leaves = 0;
        int top = Integer.MIN_VALUE;
        int[][] layer = new int[p.trunks.size()][8];
        for (int x = 0; x < r.sx; x++) {
            for (int z = 0; z < r.sz; z++) {
                if (!v.any(x, z)) {
                    continue;
                }
                for (int[] b : v.column(x, z)) {
                    if (!leaf(v.palette.get(b[1]))) {
                        continue;
                    }
                    boolean ours = false;
                    for (int k = 0; k < crowns.size(); k++) {
                        double[] c = crowns.get(k);
                        double[] radii = c[3] == 0 ? RasterV4.TRUNK_CROWN : RasterV4.WALL_CROWN;
                        int at = b[0] - ice - (int) c[2];
                        if (at < 0 || at >= radii.length
                                || Math.hypot(x + 0.5 - c[0], z + 0.5 - c[1]) > radii[at] + 1e-9) {
                            continue;
                        }
                        ours = true;
                        if (c[3] == 0 && b[0] - ice >= 5 && b[0] - ice <= 12) {
                            layer[k][b[0] - ice - 5]++;
                        }
                    }
                    if (ours) {
                        leaves++;
                        top = Math.max(top, b[0]);
                    }
                }
            }
        }
        assertEquals(ice + 11, top, name + ": the crowns reach the forest's ice + 11");
        assertTrue(leaves >= 2_000, name + ": " + leaves + " leaves (0.37.0's slab had at most 900)");
        boolean round = false;
        for (int[] l : layer) {
            round |= l[3] >= 60 && l[0] < l[2];
        }
        assertTrue(round, name + ": a trunk's crown is widest in its middle (+8 and +5 against +7)");
        // no leaf in any lane column's headroom
        for (int x = 0; x < r.sx; x++) {
            for (int z = 0; z < r.sz; z++) {
                if (r.drive(x, z)) {
                    int h = r.h[r.idx(x, z)];
                    for (int y = h + 1; y <= h + 4; y++) {
                        assertFalse(leaf(v.at(x, y, z)),
                                name(m) + ": a leaf over the track at " + x + " " + y + " " + z);
                    }
                }
            }
        }
    }

    @Test
    void theSplitsIslandTreeIsABall() {
        int islands = 0;
        for (String tier : List.of("easy", "medium", "hard")) {
            for (long seed : MountainPlannerTest.ROADS) {
                MountainPlanner.Made m = made(seed, "road", tier);
                RasterV4 r = m.raster;
                Voxels v = r.vox;
                for (PiecesV4.Piece p : m.pieces.list) {
                    if (p.kind != PiecesV4.Kind.SPLIT) {
                        continue;
                    }
                    int ice = r.level((p.s1 + p.s2) / 2);
                    int[] trunk = null;
                    for (int x = 0; x < r.sx && trunk == null; x++) {
                        for (int z = 0; z < r.sz; z++) {
                            int i = r.idx(x, z);
                            if (r.near[i] && r.obstacle[i] && r.sAt[i] >= p.s1 && r.sAt[i] <= p.s2
                                    && log(v.at(x, ice + 3, z))) {
                                trunk = new int[]{x, z};
                                break;
                            }
                        }
                    }
                    String name = name(m) + ", the split at " + Math.round(p.s1);
                    assertNotNull(trunk, name + ": the island has its tree");
                    for (int y = ice + 3; y <= ice + RasterV4.ISLAND_TRUNK_TOP; y++) {
                        assertTrue(log(v.at(trunk[0], y, trunk[1])), name + ": its log at ice + " + (y - ice));
                    }
                    int leaves = 0;
                    for (int x = trunk[0] - 3; x <= trunk[0] + 3; x++) {
                        for (int z = trunk[1] - 3; z <= trunk[1] + 3; z++) {
                            for (int y = ice + 5; y <= ice + 9; y++) {
                                leaves += leaf(v.at(x, y, z)) ? 1 : 0;
                            }
                        }
                    }
                    assertTrue(leaves >= 40, name + ": " + leaves + " leaves round its trunk (0.37.0's about 21)");
                    assertTrue(leaf(v.at(trunk[0], ice + 9, trunk[1])), name + ": a leaf on top at ice + 9");
                    assertEquals(ice + 9, v.top(trunk[0], trunk[1]), name + ": and nothing over it");
                    islands++;
                }
            }
        }
        assertTrue(islands >= 2, "fixture: the road goldens have splits: " + islands);
    }

    // ---- snow ---------------------------------------------------------------------------------------------

    @Test
    void theSlowGroundIsSnow() {
        for (String tier : List.of("easy", "medium", "hard")) {
            MountainPlanner.Made m = made(2, "road", tier);
            RasterV4 r = m.raster;
            int slow = 0;
            for (int x = 0; x < r.sx; x++) {
                for (int z = 0; z < r.sz; z++) {
                    int i = r.idx(x, z);
                    if (r.h[i] == RasterV4.NONE) {
                        continue;
                    }
                    String block = r.vox.at(x, r.h[i], z);
                    if (r.mat[i] == RasterV4.SAND) {
                        slow++;
                        assertEquals(Palette.SNOW, block, name(m) + ": the slow cell at " + x + " " + z + " is snow");
                    } else {
                        assertNotEquals(Palette.SNOW, block, name(m) + ": the ice at " + x + " " + z + " isn't");
                    }
                }
            }
            assertTrue(slow > 0, name(m) + ": run-offs, drifts and the paddock: " + slow + " cells");
            assertFalse(m.finished().palette().contains(Palette.SAND), name(m) + ": no smooth sandstone anywhere");
            for (RasterV4.Spot c : m.checkpoints) {
                int span = (int) Math.ceil(DownhillValidator.RESET_ROOM) + 1;
                for (int x = (int) Math.floor(c.x()) - span; x <= (int) Math.floor(c.x()) + span; x++) {
                    for (int z = (int) Math.floor(c.z()) - span; z <= (int) Math.floor(c.z()) + span; z++) {
                        double d = Math.hypot(x + 0.5 - c.x(), z + 0.5 - c.z());
                        if (r.drive(x, z) && d <= DownhillValidator.RESET_ROOM) {
                            assertNotEquals(RasterV4.SAND, r.mat[r.idx(x, z)], name(m)
                                    + ": no snow within the reset room of the checkpoint at " + Math.round(c.s()));
                        }
                    }
                }
            }
        }
        // algo 4 keeps its sandstone
        Plan old = V4Fixtures.boat("road", "medium").plan();
        assertTrue(old.palette().contains(Palette.SAND), "algo 4's frozen road keeps its smooth sandstone");
    }

    @Test
    void theSignsSaySnow() {
        int bends = 0;
        int drifts = 0;
        for (String tier : List.of("easy", "medium", "hard")) {
            for (long seed : MountainPlannerTest.ROADS) {
                MountainPlanner.Made m = made(seed, "road", tier);
                for (SignText t : m.finished().signs()) {
                    assertNotEquals(GenCopy.boatSandyBend(), t.lines(), name(m) + ": no SANDY BEND");
                    assertNotEquals(GenCopy.boatSandPit(), t.lines(), name(m) + ": no SAND PIT");
                    bends += t.lines().equals(GenCopy.boatSnowyBend()) ? 1 : 0;
                    drifts += t.lines().equals(GenCopy.boatSnowDrift()) ? 1 : 0;
                }
            }
        }
        assertTrue(bends > 0, "SNOWY BEND before the run-off bends: " + bends);
        assertTrue(drifts > 0, "SNOW DRIFT! before the drifts: " + drifts);
        // algo 4's frozen road keeps its sand signs
        List<List<String>> old = new ArrayList<>();
        for (SignText t : V4Fixtures.boat("road", "medium").plan().signs()) {
            old.add(t.lines());
        }
        assertTrue(old.contains(GenCopy.boatSandyBend()) || old.contains(GenCopy.boatSandPit()),
                "algo 4's road says sand");
        assertFalse(old.contains(GenCopy.boatSnowyBend()) || old.contains(GenCopy.boatSnowDrift()),
                "and never snow");
    }
}
