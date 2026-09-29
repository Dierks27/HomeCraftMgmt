package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hole templates and their rasteriser (GEN-SPEC §4.3), before any solving: 200 seeds of every
 * template in every tier it serves, each drawn in a plot and read back from its blocks. The tee
 * and cup are inside the hole's bounds, the lane flood-filled from the tee never leaks (no drop
 * into nothing, no wall a ball can roll over), the cup is a fine sunken cup, every wall stands
 * one block above the hole's highest lane, nothing hangs over the lane but the flag, slime is
 * never underfoot, and every block is an allowed golf block inside the plot. And, on the real
 * physics, no putt from any spot hard against a wall ever leaves the hole.
 */
class HoleTemplateTest {

    private static final int SEEDS = 200;

    @Test
    void everyTemplateDrawsASoundHoleFor200Seeds() {
        Box plot = Box.sized(GolfKit.PLOT_X, GolfKit.DAILY_A.minY(), GolfKit.PLOT_Z, HoleTemplate.PLOT_X, 16,
                HoleTemplate.PLOT_Z);
        for (HoleTemplate t : HoleTemplate.values()) {
            String tiers = t == HoleTemplate.SAFE_STRAIGHT ? "S" : "EMH";
            for (char tier : tiers.toCharArray()) {
                if (t != HoleTemplate.SAFE_STRAIGHT && !t.fits(tier)) {
                    continue;
                }
                Set<Boolean> mirrors = new HashSet<>();
                for (long seed = 0; seed < SEEDS; seed++) {
                    HoleLayout l = GolfKit.draw(t, tier, seed);
                    String what = t + " " + tier + " seed " + seed + " (" + l.describe() + ")";
                    mirrors.add(l.mirrored());
                    GolfCourse.Hole h = GolfKit.hole(l);
                    GolfCourse one = new GolfCourse("t", "t", "w", true, 1, List.of(h));
                    assertEquals(List.of(), one.problems(null), what + ": tee and cup inside its bounds");
                    Set<Long> spots = new HashSet<>();
                    for (HoleLayout.Placed p : l.blocks()) {
                        assertTrue(plot.contains(p.x(), p.y(), p.z()), what + ": block inside its plot at "
                                + p.x() + " " + p.y() + " " + p.z());
                        assertTrue(Palette.allowed(p.blockData()), what + ": " + p.blockData() + " is allowed");
                        assertTrue(spots.add(((long) p.x() << 40) ^ ((long) p.y() << 20) ^ p.z()),
                                what + ": one block per spot");
                    }
                    PlanBlocks g = GolfKit.grid(l);
                    assertEquals(BallPhysics.CupShape.FINE, BallPhysics.cupShape(g, h.cup().x(), h.cup().y(),
                            h.cup().z()), what + ": the cup is a fine cup");
                    assertEquals(List.of(), GolfValidator.holeProblems(g, h, 1),
                            what + ": no leak, walls one high, nothing over the lane, no hollow but the cup");
                    assertTrue(plot.contains(l.signX(), l.signY(), l.signZ()) && !g.solid(l.signX(), l.signY(),
                            l.signZ()) && g.solid(l.signX(), l.signY() - 1, l.signZ()),
                            what + ": the tee sign stands on the wall behind the tee");
                    assertEquals(l.cupTop() + 3, flagY(g, l), what + ": the flag floats three over the cup");
                    LaneMap lane = LaneMap.of(g, h);
                    for (int x = lane.minX; x < lane.minX + lane.sizeX; x++) {
                        for (int z = lane.minZ; z < lane.minZ + lane.sizeZ; z++) {
                            if (lane.isLane(x, z)) {
                                int under = (int) Math.ceil(lane.surface(x, z)) - 1;
                                assertTrue(g.surface(x, under, z) != BallPhysics.Surface.SLIME,
                                        what + ": slime only in walls, never in the floor");
                            }
                        }
                    }
                }
                if (t != HoleTemplate.SAFE_STRAIGHT) {
                    assertEquals(Set.of(true, false), mirrors, t + " is drawn both ways round");
                }
            }
        }
    }

    @Test
    void noPuttFromAnySpotAlongTheWallsLeavesTheHole() {
        int spots = 0;
        for (HoleTemplate t : HoleTemplate.values()) {
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

    @Test
    void everyTierHasItsTemplates() {
        assertEquals(List.of(HoleTemplate.STRAIGHT, HoleTemplate.BUMPERS, HoleTemplate.RAMP),
                HoleTemplate.forTier('E'), "Easy: straight, bumpers, ramp");
        assertEquals(List.of(HoleTemplate.RAMP, HoleTemplate.DOGLEG, HoleTemplate.ICE_RUN, HoleTemplate.ISLAND),
                HoleTemplate.forTier('m'), "Medium: ramp, dogleg, ice run, island (any case)");
        assertEquals(List.of(HoleTemplate.ISLAND, HoleTemplate.S_BEND, HoleTemplate.NARROW_ICE),
                HoleTemplate.forTier('H'), "Hard: island, S-bend, narrow ice");
        Set<HoleTemplate> used = EnumSet.noneOf(HoleTemplate.class);
        for (char tier : "EMH".toCharArray()) {
            used.addAll(HoleTemplate.forTier(tier));
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(HoleTemplate.SAFE_STRAIGHT)), used,
                "every template but the fallback serves a tier; the fallback serves none");
    }

    @Test
    void theSameSeedDrawsTheSameHole() {
        for (HoleTemplate t : HoleTemplate.values()) {
            char tier = t == HoleTemplate.SAFE_STRAIGHT ? 'S' : HoleTemplate.forTier('E').contains(t) ? 'E'
                    : HoleTemplate.forTier('M').contains(t) ? 'M' : 'H';
            assertEquals(GolfKit.draw(t, tier, 7), GolfKit.draw(t, tier, 7), t + " is the same for the same seed");
        }
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
