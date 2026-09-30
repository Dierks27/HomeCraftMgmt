package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hole templates and their rasteriser (GEN-SPEC §4.3, Course Variety §3.2), before any solving:
 * 200 seeds of every template in every tier it serves, each drawn in a plot and read back from its
 * blocks. The shapes GEN-SPEC first had keep their frozen rules (uniform walls a block above the
 * highest lane) and pass Adventure Golf's too; the eleven new ones pass Adventure Golf's per-hole
 * rules ({@link GolfValidatorV3}): no leak, walls where they stand, the flight rule, ponds sealed and
 * 3 across, trees clear of the tee and the flag. Every block is an allowed golf block inside the
 * plot, each leaf at vanilla's distance, the tee sign on its wall, the flag three over the cup; Easy
 * never has water in play, and scenery never enters the physics grid. On the real physics no putt
 * from any spot a ball can rest leaves a hole (the rail a wall's lower block makes is closed where a
 * ball can reach it); each template's features are where it says; and each passes its first attempt
 * often enough (a quarter of the time; 2,000 seeds under {@code -Phcm.slow}).
 */
class HoleTemplateTest {

    private static final int SEEDS = 200;
    private static final int T = GolfKit.TURF;

    /** The shapes GEN-SPEC first had (and the fallback): judged by the frozen rules too. */
    private static boolean old(HoleTemplate t) {
        return t.ordinal() <= HoleTemplate.SAFE_STRAIGHT.ordinal();
    }

    /** Every (template, tier) a course can draw, the fallback as 'S'. */
    private static List<Map.Entry<HoleTemplate, Character>> drawn() {
        List<Map.Entry<HoleTemplate, Character>> out = new ArrayList<>();
        for (HoleTemplate t : HoleTemplate.values()) {
            String tiers = t == HoleTemplate.SAFE_STRAIGHT ? "S" : "EMH";
            for (char tier : tiers.toCharArray()) {
                if (t == HoleTemplate.SAFE_STRAIGHT || t.fits(tier)) {
                    out.add(Map.entry(t, tier));
                }
            }
        }
        return out;
    }

    @Test
    void everyTemplateDrawsASoundHoleFor200Seeds() {
        Box plot = Box.sized(GolfKit.PLOT_X, GolfKit.DAILY_A.minY(), GolfKit.PLOT_Z, HoleTemplate.PLOT_X, 16,
                HoleTemplate.PLOT_Z);
        List<String> bad = drawn().parallelStream().flatMap(e -> {
            HoleTemplate t = e.getKey();
            char tier = e.getValue();
            List<String> out = new ArrayList<>();
            Set<Boolean> mirrors = new HashSet<>();
            for (long seed = 0; seed < SEEDS; seed++) {
                HoleLayout l = GolfKit.draw(t, tier, seed);
                String what = t + " " + tier + " seed " + seed + " (" + l.describe() + ")";
                mirrors.add(l.mirrored());
                out.addAll(soundness(l, plot, what));
            }
            if (t != HoleTemplate.SAFE_STRAIGHT && !mirrors.equals(Set.of(true, false))) {
                out.add(t + " " + tier + " isn't drawn both ways round");
            }
            return out.stream();
        }).toList();
        assertEquals(List.of(), bad, "every template draws a sound hole, every seed");
    }

    /** Everything a drawn hole must be, before it is solved; empty when it is. */
    private static List<String> soundness(HoleLayout l, Box plot, String what) {
        List<String> out = new ArrayList<>();
        GolfCourse.Hole h = GolfKit.hole(l);
        GolfCourse one = new GolfCourse("t", "t", "w", true, 1, List.of(h));
        if (!one.problems(null).isEmpty()) {
            out.add(what + ": tee or cup outside its bounds " + one.problems(null));
        }
        Set<Long> spots = new HashSet<>();
        List<String> palette = new ArrayList<>();
        List<BlockOp> ops = new ArrayList<>();
        for (HoleLayout.Placed p : l.blocks()) {
            if (!plot.contains(p.x(), p.y(), p.z())) {
                out.add(what + ": a block outside its plot at " + p);
            }
            if (!Palette.allowed(p.blockData()) && !Palette.poolWater(p.blockData())) {
                out.add(what + ": " + p.blockData() + " isn't allowed");
            }
            if (!spots.add(Palette.blockKey(p.x(), p.y(), p.z()))) {
                out.add(what + ": two blocks at " + p);
            }
            if (!palette.contains(p.blockData())) {
                palette.add(p.blockData());
            }
            ops.add(new BlockOp(p.x(), p.y(), p.z(), (short) palette.indexOf(p.blockData())));
        }
        out.addAll(Palette.stateProblems(palette).stream().map(p -> what + ": " + p).toList());
        out.addAll(Palette.leafProblems(palette, ops).stream().map(p -> what + ": " + p).toList());
        PlanBlocks g = GolfKit.grid(l);
        if (BallPhysics.cupShape(g, h.cup().x(), h.cup().y(), h.cup().z()) != BallPhysics.CupShape.FINE) {
            out.add(what + ": the cup isn't a fine cup");
        }
        if (old(l.template())) {
            out.addAll(GolfValidator.holeProblems(g, h, 1).stream().map(p -> what + " (frozen rules): " + p).toList());
        }
        out.addAll(GolfValidator.holeProblems(g, h, 1, GolfPlanner.ALGO).stream().map(p -> what + ": " + p).toList());
        boolean onWall = plot.contains(l.signX(), l.signY(), l.signZ()) && !g.solid(l.signX(), l.signY(), l.signZ())
                && g.solid(l.signX(), l.signY() - 1, l.signZ()) && l.bounds().contains(l.signX(), l.signY(), l.signZ());
        if (!onWall) {
            out.add(what + ": the tee sign doesn't stand on the wall behind the tee, inside the bounds");
        }
        if (flagY(g, l) != l.cupTop() + 3 || l.cupTop() + 3 > l.boundsTop()) {
            out.add(what + ": the flag doesn't float three over the cup, inside the bounds");
        }
        LaneMap lane = LaneMap.of(g, h, GolfPlanner.ALGO);
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (lane.isLane(x, z)) {
                    int under = (int) Math.ceil(lane.surface(x, z)) - 1;
                    if (g.surface(x, under, z) == BallPhysics.Surface.SLIME) {
                        out.add(what + ": slime in the floor at " + x + "," + z);
                    }
                }
            }
        }
        Box grid = GolfPlanner.plotBox(l);
        for (HoleLayout.Placed p : l.scenery()) {
            if (grid.contains(p.x(), grid.minY(), p.z()) || !plot.contains(p.x(), p.y(), p.z())) {
                out.add(what + ": scenery at " + p + " isn't outside the ball's grid and inside the plot");
            }
        }
        return out;
    }

    @Test
    void easyNeverHasWaterInPlay() {
        for (HoleTemplate t : HoleTemplate.forTier('E')) {
            for (long seed = 0; seed < SEEDS; seed++) {
                HoleLayout l = GolfKit.draw(t, 'E', seed);
                String what = t + " seed " + seed;
                assertTrue(l.blocks().stream().noneMatch(p -> Palette.poolWater(p.blockData())),
                        what + ": no water among the blocks the ball plays (for Tiny Golf and the four-year-old)");
                assertEquals(0, LaneMap.of(GolfKit.grid(l), GolfKit.hole(l), GolfPlanner.ALGO).hazards(),
                        what + ": and no pond in its bounds");
                assertFalse(l.features().contains(Quota.Feature.WATER), what + ": it says so");
            }
        }
        HoleLayout view = GolfKit.draw(HoleTemplate.POND_SIDE, 'E', 1);
        assertTrue(view.scenery().stream().anyMatch(p -> Palette.poolWater(p.blockData())),
                "Easy's pond side has its pond, to look at");
        Box b = view.bounds();
        assertTrue(view.scenery().stream().allMatch(p -> p.x() < b.minX() - 1 || p.x() > b.maxX() + 1
                        || p.z() < b.minZ() - 1 || p.z() > b.maxZ() + 1),
                "two columns clear of the bounds: the ball never reaches it");
    }

    @Test
    void noPuttFromAnySpotAlongTheWallsLeavesTheHole() {
        int spots = 0;
        for (HoleTemplate t : HoleTemplate.values()) {
            if (!old(t)) {
                continue; // the new templates: every spot a ball can rest, below
            }
            char tier = t == HoleTemplate.SAFE_STRAIGHT ? 'S' : t.fits('H') ? 'H' : t.fits('M') ? 'M' : 'E';
            for (long seed = 0; seed < 4; seed++) {
                HoleLayout l = GolfKit.draw(t, tier, seed);
                PlanBlocks g = GolfKit.grid(l);
                GolfCourse.Hole h = GolfKit.hole(l);
                LaneMap lane = LaneMap.of(g, h);
                BallPhysics.Hole area = GolfShot.area(g, h);
                for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
                    for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                        if (!lane.isLane(x, z) || x == h.cup().x() && z == h.cup().z()) {
                            continue;
                        }
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            if (lane.isLane(x + d[0], z + d[1])) {
                                continue;
                            }
                            // hard against the wall: the ball's edge over the wall column
                            double bx = x + 0.5 + d[0] * 0.45;
                            double bz = z + 0.5 + d[1] * 0.45;
                            spots++;
                            for (int yaw = 0; yaw < 360; yaw += 15) {
                                for (int power : new int[]{1, 3, 5}) {
                                    BallPhysics.Ball ball = new BallPhysics.Ball(bx, lane.surface(x, z), bz);
                                    GolfShot.Result r = GolfShot.play(g, area, ball, new Putt(yaw, power));
                                    assertFalse(r.penalty(), l.describe() + ": a putt from " + bx + " " + bz
                                            + " at " + yaw + ", power " + power + " left the hole (" + r + ")");
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(spots > 1000, "enough wall-side spots were tried: " + spots);
    }

    /**
     * Every spot of an Adventure hole a ball can come to rest at and be putted from: each lane
     * cell's middle; touching each wall round it (rolling sideways, a ball's edge never gets over a
     * wall); and with its edge out over each open side (lane no more than a step up, a pond), where
     * it can roll on past the corner of a wall that starts there and ride the wall's lower block.
     */
    private static List<double[]> restingSpots(LaneMap lane, GolfCourse.Hole h) {
        List<double[]> out = new ArrayList<>();
        for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
            for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                if (!lane.isLane(x, z) || x == h.cup().x() && z == h.cup().z()) {
                    continue;
                }
                double s = lane.surface(x, z);
                out.add(new double[]{x + 0.5, s, z + 0.5});
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    boolean open = lane.isHazard(nx, nz) || lane.isLane(nx, nz) && lane.surface(nx, nz) <= s + 0.5;
                    double off = open ? 0.45 : 0.5 - BallPhysics.RADIUS - 0.005;
                    out.add(new double[]{x + 0.5 + d[0] * off, s, z + 0.5 + d[1] * off});
                }
            }
        }
        return out;
    }

    @Test
    void noPuttFromAnySpotABallCanRestLeavesAnAdventureHole() {
        List<String> out = drawn().parallelStream().filter(e -> !old(e.getKey())).flatMap(e -> {
            List<String> bad = new ArrayList<>();
            for (long seed = 0; seed < 2 && bad.isEmpty(); seed++) {
                HoleLayout l = GolfKit.draw(e.getKey(), e.getValue(), seed);
                PlanBlocks g = GolfKit.grid(l);
                GolfCourse.Hole h = GolfKit.hole(l);
                BallPhysics.Hole area = GolfShot.area(g, h);
                for (double[] at : restingSpots(LaneMap.of(g, h, GolfPlanner.ALGO), h)) {
                    for (int yaw = 0; yaw < 360 && bad.isEmpty(); yaw += 15) {
                        for (int power : new int[]{1, 3, 5}) {
                            GolfShot.Result r = GolfShot.play(g, area, new BallPhysics.Ball(at[0], at[1], at[2]),
                                    new Putt(yaw, power));
                            if (r.outcome() == BallPhysics.Outcome.OUT) {
                                bad.add(l.describe() + ": a putt from " + at[0] + " " + at[2] + " at " + yaw
                                        + ", power " + power + " left the hole");
                                break;
                            }
                        }
                    }
                }
            }
            return bad.stream();
        }).toList();
        assertEquals(List.of(), out, "a pond may take a ball (a stroke, and it comes back), but nothing leaves the"
                + " hole, from anywhere a ball can rest, at any angle");
    }

    @Test
    void theVolcanosRailIsClosed() {
        boolean shown = false;
        for (long seed = 0; seed < 6; seed++) {
            HoleLayout l = GolfKit.draw(HoleTemplate.VOLCANO, 'H', seed);
            PlanBlocks g = GolfKit.grid(l);
            GolfCourse.Hole h = GolfKit.hole(l);
            LaneMap lane = LaneMap.of(g, h, GolfPlanner.ALGO);
            String what = l.describe();
            // every ring wall stands at least two above the turf, so a ball riding a wall's lower block at
            // the plateau's height (it can roll onto one where the approach's wall begins) meets a wall at
            // the end of it, never one it rolls over
            int walls = 0;
            for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
                for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                    if (!lane.isLane(x, z) && g.solid(x, T - 1, z)) {
                        walls++;
                        assertTrue(g.solid(x, T + 1, z), what + ": the wall at " + x + "," + z
                                + " stands two above the turf");
                    }
                }
            }
            assertTrue(walls > 40, what + " has its walls: " + walls);
            int edge = l.teeX() - 2; // the approach is 5 wide round the tee
            int wallX = edge - 1;
            int plateau = -1;
            for (int z = lane.minZ + 1; z < lane.minZ + lane.sizeZ; z++) {
                if (Math.abs(lane.surface(edge, z) - (T + 1)) < 1e-6 && Math.abs(lane.surface(edge, z - 1) - T) < 1e-6) {
                    plateau = z;
                }
            }
            assertTrue(plateau > 0 && g.solid(wallX, T, plateau - 1) && lane.isLane(wallX, plateau),
                    what + ": the approach's wall ends where the plateau begins");
            BallPhysics.Hole area = GolfShot.area(g, h);
            double bx = edge + 0.05; // on the plateau, its edge over the column where the approach's wall starts
            for (int power = 1; power <= BallPhysics.clubs(); power++) {
                GolfShot.Result r = GolfShot.play(g, area, new BallPhysics.Ball(bx, T + 1, plateau + 0.5),
                        new Putt(180, power));
                assertFalse(r.penalty(), what + ": back toward the tee along the wall, power " + power
                        + ", it stays in (" + r + ")");
            }
            // with the far walls a block above the turf (all the wall and flight rules ask there), the same
            // ball rides the rail out over the wall behind the tee
            PlanBlocks low = GolfKit.grid(l);
            for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
                for (int z = lane.minZ; z <= plateau - 9; z++) {
                    if (!lane.isLane(x, z)) {
                        low.set(x, T + 1, z, PlanBlocks.AIR);
                    }
                }
            }
            for (int power = 1; power <= BallPhysics.clubs() && !shown; power++) {
                shown = GolfShot.play(low, area, new BallPhysics.Ball(bx, T + 1, plateau + 0.5), new Putt(180, power))
                        .outcome() == BallPhysics.Outcome.OUT;
            }
        }
        assertTrue(shown, "a real way out otherwise: why the rail rule raises them");
    }

    @Test
    void everyTierHasItsTemplates() {
        assertEquals(List.of(HoleTemplate.STRAIGHT, HoleTemplate.BUMPERS, HoleTemplate.RAMP, HoleTemplate.SAND_TRAP,
                HoleTemplate.HUMP, HoleTemplate.TREE_GARDEN, HoleTemplate.POND_SIDE), HoleTemplate.forTier('E'),
                "Easy's seven (§3.2)");
        assertEquals(List.of(HoleTemplate.RAMP, HoleTemplate.DOGLEG, HoleTemplate.ICE_RUN, HoleTemplate.ISLAND,
                        HoleTemplate.SAND_TRAP, HoleTemplate.HILL, HoleTemplate.TERRACES, HoleTemplate.DOGLEG_DOWN,
                        HoleTemplate.TREE_GARDEN, HoleTemplate.POND_SIDE, HoleTemplate.TWO_WAY, HoleTemplate.CREEK),
                HoleTemplate.forTier('m'), "Medium's twelve (any case)");
        assertEquals(List.of(HoleTemplate.ISLAND, HoleTemplate.S_BEND, HoleTemplate.NARROW_ICE, HoleTemplate.VOLCANO,
                        HoleTemplate.TERRACES, HoleTemplate.DOGLEG_DOWN, HoleTemplate.TREE_GARDEN,
                        HoleTemplate.ISLAND_POND, HoleTemplate.TWO_WAY), HoleTemplate.forTier('H'),
                "Hard's nine");
        Set<HoleTemplate> used = EnumSet.noneOf(HoleTemplate.class);
        for (char tier : "EMH".toCharArray()) {
            used.addAll(HoleTemplate.forTier(tier));
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(HoleTemplate.SAFE_STRAIGHT)), used,
                "every template but the fallback serves a tier; the fallback serves none");
        assertEquals(HoleTemplate.SAFE_STRAIGHT.ordinal() + 11, HoleTemplate.CREEK.ordinal(),
                "the eleven new ones come after the fallback, so the old ones keep their places");
    }

    @Test
    void theSameSeedDrawsTheSameHole() {
        for (Map.Entry<HoleTemplate, Character> e : drawn()) {
            assertEquals(GolfKit.draw(e.getKey(), e.getValue(), 7), GolfKit.draw(e.getKey(), e.getValue(), 7),
                    e + " is the same for the same seed");
        }
    }

    @Test
    void eachHoleHasTheFeaturesItsTemplateSays() {
        for (Map.Entry<HoleTemplate, Character> e : drawn()) {
            for (long seed = 0; seed < 20; seed++) {
                HoleLayout l = GolfKit.draw(e.getKey(), e.getValue(), seed);
                String what = e + " seed " + seed;
                assertEquals(e.getKey().features(e.getValue()), l.features(), what + ": the quota counts what it has");
                assertEquals(e.getKey().teeFeature(e.getValue()), l.teeFeature(), what + ": its sign says it");
                Set<Quota.Feature> has = l.features();
                boolean water = l.blocks().stream().anyMatch(p -> Palette.poolWater(p.blockData()));
                assertEquals(has.contains(Quota.Feature.WATER), water, what + ": water in play exactly when it says");
                boolean sand = l.blocks().stream().anyMatch(p -> Palette.id(p.blockData()).startsWith(
                        "minecraft:smooth_sandstone"));
                assertEquals(has.contains(Quota.Feature.SAND), sand, what + ": sand exactly when it says");
                boolean trees = l.blocks().stream().anyMatch(p -> Palette.isLeaves(p.blockData()));
                assertEquals(has.contains(Quota.Feature.TREES), trees, what + ": trees in play exactly when it says");
                boolean up = l.blocks().stream().anyMatch(p -> p.y() >= T && raisedLane(p.blockData()));
                assertEquals(has.contains(Quota.Feature.HEIGHT), up, what + ": height exactly when it says");
            }
        }
    }

    /** Whether a block is of a raised lane column: turf, a slab, glass, the ring or the tee (not a wall). */
    private static boolean raisedLane(String b) {
        return b.equals(Palette.RAMP) || b.equals(Palette.TURF_DARK) || b.equals(Palette.TURF_LIGHT)
                || b.equals(Palette.BLUE_GLASS) || b.equals(Palette.CUP_RING) || b.equals(Palette.TEE);
    }

    @Test
    void theFeaturesAreWhereTheTemplatesSay() {
        HoleLayout ice = GolfKit.draw(HoleTemplate.ICE_RUN, 'M', 3);
        assertTrue(ice.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.GOLF_ICE))
                && ice.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.BRAKE)),
                "an ice run has packed ice and a soul-soil brake");
        HoleLayout bumpers = GolfKit.draw(HoleTemplate.BUMPERS, 'E', 3);
        assertTrue(bumpers.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.BUMPER)
                && p.y() == GolfKit.TURF), "bumpers are slime at ball height");
        HoleLayout island = GolfKit.draw(HoleTemplate.ISLAND, 'H', 3);
        assertEquals(GolfKit.TURF + 1, island.cupTop(), "an island's cup is on its raised green");
        assertTrue(island.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.RAMP)),
                "and the green has a ramp");
        HoleLayout straight = GolfKit.draw(HoleTemplate.STRAIGHT, 'E', 3);
        assertTrue(straight.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.TEE)
                && p.x() == straight.teeX() && p.z() == straight.teeZ() && p.y() == GolfKit.TURF - 1),
                "a white tee block, inlaid");
        assertTrue(straight.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.CUP)
                && p.x() == straight.cupX() && p.z() == straight.cupZ() && p.y() == GolfKit.TURF - 2),
                "black concrete one below the turf: a sunken cup");
        long ring = straight.blocks().stream().filter(p -> p.blockData().equals(Palette.CUP_RING)
                && Math.abs(p.x() - straight.cupX()) <= 1 && Math.abs(p.z() - straight.cupZ()) <= 1).count();
        assertEquals(8, ring, "a white 3 x 3 ring round the cup");

        // Adventure Golf
        HoleLayout flush = GolfKit.draw(HoleTemplate.SAND_TRAP, 'E', 3);
        assertEquals(9, flush.blocks().stream().filter(p -> p.blockData().equals(Palette.SAND) && p.y() == T - 1)
                .count(), "Easy's sand trap: a flush 3 x 3 of smooth sandstone");
        HoleLayout sunken = GolfKit.draw(HoleTemplate.SAND_TRAP, 'M', 3);
        long slabs = sunken.blocks().stream().filter(p -> p.blockData().equals(Palette.SAND_SLAB) && p.y() == T - 1)
                .count();
        assertTrue(slabs == 9 || slabs == 12, "Medium's: a sunken bunker 3 wide, 3 or 4 long: " + slabs);
        assertBunkerEscape(sunken);
        HoleLayout volcano = GolfKit.draw(HoleTemplate.VOLCANO, 'H', 3);
        assertEquals(T + 2, volcano.cupTop(), "the volcano's cup is on its summit, two up");
        assertEquals(T + 5, volcano.boundsTop(), "its flag floats three above, and its bounds reach up to it");
        assertEquals(T, volcano.teeY(), "teed off from the turf");
        HoleLayout terraces = GolfKit.draw(HoleTemplate.TERRACES, 'M', 3);
        assertEquals(T + 2, terraces.teeY(), "the terraces' tee is on the top terrace, two up");
        assertTrue(terraces.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.TEE) && p.y() == T + 1),
                "its tee block is the top terrace's top");
        assertEquals(10, terraces.blocks().stream().filter(p -> p.blockData().equals(Palette.BLUE_GLASS)).count(),
                "two edges of glass, 5 wide: the glass waterfall");
        assertTrue(GolfKit.draw(HoleTemplate.TERRACES, 'H', 3).blocks().stream().filter(p ->
                Palette.poolWater(p.blockData())).count() >= 9, "on Hard, a pond beside the bottom terrace");
        HoleLayout down = GolfKit.draw(HoleTemplate.DOGLEG_DOWN, 'M', 3);
        assertEquals(T + 1, down.teeY(), "the dogleg's first leg is a block up");
        assertTrue(down.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.BUMPER) && p.y() == T + 1),
                "Medium keeps the slime bank, raised to two above the lower leg (the flight rule)");
        assertEquals(9, GolfKit.draw(HoleTemplate.DOGLEG_DOWN, 'H', 3).blocks().stream().filter(p ->
                p.blockData().equals(Palette.SAND_SLAB)).count(), "Hard has a sunken 3 x 3 bunker there instead");
        for (char tier : "EMH".toCharArray()) {
            int least = tier == 'H' ? 2 : 1;
            int most = tier == 'E' ? 1 : tier == 'M' ? 2 : 3;
            for (long seed = 0; seed < 20; seed++) {
                HoleLayout garden = GolfKit.draw(HoleTemplate.TREE_GARDEN, tier, seed);
                long trunks = garden.blocks().stream().filter(p -> p.blockData().endsWith("_log[axis=y]")
                        && p.y() == T - 1).count();
                assertTrue(trunks >= least && trunks <= most, "a tree garden on " + tier + " has " + least + "-"
                        + most + " trees in play: " + trunks);
                assertTrue(garden.blocks().stream().filter(p -> Palette.isLeaves(p.blockData()))
                        .allMatch(p -> p.y() >= T + 3), "and every leaf is 3 above the lane, clear of the ball");
            }
        }
        HoleLayout pond = GolfKit.draw(HoleTemplate.POND_SIDE, 'M', 3);
        assertTrue(pond.blocks().stream().filter(p -> Palette.poolWater(p.blockData())).count() >= 18,
                "Medium's pond side: a pond 3-4 wide and 6-10 long, in play");
        HoleLayout isle = GolfKit.draw(HoleTemplate.ISLAND_POND, 'H', 3);
        LaneMap il = LaneMap.of(GolfKit.grid(isle), GolfKit.hole(isle), GolfPlanner.ALGO);
        assertTrue(il.isHazard(isle.cupX() - 4, isle.cupZ()) && il.isHazard(isle.cupX() + 4, isle.cupZ())
                && il.isHazard(isle.cupX(), isle.cupZ() + 4), "the island green has water on three sides");
        HoleLayout two = GolfKit.draw(HoleTemplate.TWO_WAY, 'M', 3);
        assertEquals(2, two.blocks().stream().filter(p -> p.blockData().endsWith("_log[axis=y]") && p.y() == T - 1)
                .count(), "the two-way's island has two trunks");
        assertTrue(two.blocks().stream().anyMatch(p -> p.blockData().equals(Palette.MOSS)), "on moss");
        assertTrue(GolfKit.draw(HoleTemplate.TWO_WAY, 'H', 3).blocks().stream().anyMatch(p ->
                p.blockData().equals(Palette.SAND)), "on Hard, sand before the pond on the short way");
        HoleLayout creek = GolfKit.draw(HoleTemplate.CREEK, 'M', 3);
        LaneMap cl = LaneMap.of(GolfKit.grid(creek), GolfKit.hole(creek), GolfPlanner.ALGO);
        int across = 0;
        for (int z = cl.minZ; z < cl.minZ + cl.sizeZ; z++) {
            int wet = 0;
            int dry = 0;
            for (int x = cl.minX; x < cl.minX + cl.sizeX; x++) {
                wet += cl.isHazard(x, z) ? 1 : 0;
                dry += cl.isLane(x, z) ? 1 : 0;
            }
            if (wet > 0) {
                assertEquals(3, dry, "a creek row is water right across but for the 3-wide bridge");
                across++;
            }
        }
        assertTrue(across >= 3 && across <= 5, "the creek is 3-5 across: " + across);
    }

    /** A sunken bunker: power 1 never climbs out of it, power 2 does (§3.5: v² > 0.04 at the step). */
    private static void assertBunkerEscape(HoleLayout l) {
        PlanBlocks g = GolfKit.grid(l);
        GolfCourse.Hole h = GolfKit.hole(l);
        BallPhysics.Hole area = GolfShot.area(g, h);
        HoleLayout.Placed sand = l.blocks().stream().filter(p -> p.blockData().equals(Palette.SAND_SLAB))
                .max((a, b) -> Integer.compare(a.z(), b.z())).orElseThrow(); // its row nearest the cup
        GolfShot.Result one = GolfShot.play(g, area, new BallPhysics.Ball(sand.x() + 0.5, T - 0.5, sand.z() + 0.5),
                new Putt(0, 1));
        assertEquals(T - 0.5, one.y(), 1e-6, "power 1 stays in the sand");
        GolfShot.Result two = GolfShot.play(g, area, new BallPhysics.Ball(sand.x() + 0.5, T - 0.5, sand.z() + 0.5),
                new Putt(0, 2));
        assertEquals(T, two.y(), 1e-6, "power 2 gets out onto the turf");
    }

    @Test
    void everyTemplatePassesItsFirstAttemptOftenEnough() {
        assertPassRates(60);
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void everyTemplatePassesItsFirstAttemptOftenEnoughOver2000Seeds() {
        assertPassRates(2000);
    }

    /**
     * Each template, at each tier, passes its first attempt (a hole the planner keeps: sound, par in
     * its tier's range, the kid within par + 1) at least a quarter of the time over {@code seeds}
     * seeds (§3.11), in a plot of Golf of the Week's middle row.
     */
    private static void assertPassRates(int seeds) {
        int[] p = GolfPlanner.plot(GolfKit.DAILY_A, 4);
        Map<String, Double> rates = new TreeMap<>();
        for (Map.Entry<HoleTemplate, Character> e : drawn()) {
            if (e.getKey() == HoleTemplate.SAFE_STRAIGHT) {
                continue;
            }
            long passed = LongStream.range(0, seeds).parallel().filter(seed -> {
                HoleLayout l = e.getKey().draw(new GenRandom(seed).fork("rate"), e.getValue(), p[0], p[1], T);
                try {
                    return GolfPlanner.solve(l, e.getValue(), 0, new Work(GolfPlanner.ATTEMPT_BUDGET, null)) != null;
                } catch (GenFailed never) {
                    throw new IllegalStateException(never);
                }
            }).count();
            rates.put(e.getKey() + " " + e.getValue(), passed / (double) seeds);
        }
        System.out.println("first-attempt pass rates over " + seeds + " seeds: " + rates);
        rates.forEach((what, rate) -> assertTrue(rate >= 0.25, what + " passes its first attempt " + rate
                + " of the time, less than a quarter"));
    }

    @Test
    void theLeavesAreAtVanillasDistanceAndTheCanopiesClearTheLane() {
        IntStream.range(0, 30).forEach(seed -> {
            for (HoleLayout l : List.of(GolfKit.draw(HoleTemplate.TREE_GARDEN, 'H', seed),
                    GolfKit.draw(HoleTemplate.TWO_WAY, 'M', seed))) {
                List<int[]> logs = new ArrayList<>();
                List<int[]> leaves = new ArrayList<>();
                for (HoleLayout.Placed q : l.blocks()) {
                    if (Palette.holdsLeaves(q.blockData())) {
                        logs.add(new int[]{q.x(), q.y(), q.z()});
                    } else if (Palette.isLeaves(q.blockData())) {
                        leaves.add(new int[]{q.x(), q.y(), q.z()});
                    }
                }
                Map<Long, Integer> d = Palette.leafDistances(logs, leaves);
                for (HoleLayout.Placed q : l.blocks()) {
                    if (Palette.isLeaves(q.blockData())) {
                        String wood = Palette.id(q.blockData()).replace("minecraft:", "").replace("_leaves", "");
                        assertEquals(Palette.leaves(wood, d.get(Palette.blockKey(q.x(), q.y(), q.z()))),
                                q.blockData(), l.describe() + ": the leaf at " + q + " is at vanilla's distance");
                        assertTrue(q.y() >= T + 3, l.describe() + ": and 3 above the turf lane");
                    }
                }
                assertNotNull(GenCopy.golfTeeFeature(GenCopy.golfTee(1, 3, l.teeFeature()), 1, 3),
                        "its tee sign reads back as a tee sign");
            }
        });
    }

    private static int flagY(PlanBlocks g, HoleLayout l) {
        for (int y = l.turfY() + 8; y > l.cupTop(); y--) {
            if (g.solid(l.cupX(), y, l.cupZ())) {
                return y;
            }
        }
        return -1;
    }
}
