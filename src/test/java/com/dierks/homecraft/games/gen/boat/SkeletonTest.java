package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.GenRandom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mountain Run v2's route (MOUNTAIN-V2-SPEC §4, §5.3) and its drops (§7.2), over many seeds of every style
 * and tier: inside the mask and clear of itself, links in range and alternating, both hands of turn,
 * straights of 40 or more on an axis (red-team F08), the finish where the stand looks, both mirrors, and the
 * drop plan's own rules.
 */
class SkeletonTest {

    private static final int SEEDS = 24;

    /** Up to {@link #SEEDS} routes of {@code tier}, each from its own seed. */
    static List<Skeleton> routes(MountainTier tier) {
        List<Skeleton> out = new ArrayList<>();
        for (int i = 0; i < SEEDS; i++) {
            GenRandom root = new GenRandom(0xA11CEL + 7919L * i);
            Frame f = Frame.draw(root.fork("frame:0"), tier);
            Skeleton sk = f == null ? null : Skeleton.draw(root.fork("route:0"), f);
            if (sk != null) {
                out.add(sk);
            }
        }
        return out;
    }

    static List<MountainTier> tiers() {
        return tiers(MountainPlanner.ALGO);
    }

    /** Every style and tier as boat planner {@code algo} makes them. */
    static List<MountainTier> tiers(int algo) {
        List<MountainTier> out = new ArrayList<>();
        for (BoatStyle style : BoatStyle.values()) {
            for (String id : List.of("easy", "medium", "hard")) {
                out.add(MountainTier.of(style, id, algo));
            }
        }
        return out;
    }

    @Test
    void mostSeedsMakeARoute() {
        for (MountainTier tier : tiers()) {
            int n = routes(tier).size();
            assertTrue(n >= SEEDS * 3 / 4, tier + ": " + n + " of " + SEEDS + " seeds make a route");
        }
    }

    @Test
    void theRouteStaysInsideTheMask() {
        for (MountainTier tier : tiers()) {
            for (Skeleton sk : routes(tier)) {
                double half = tier.width / 2.0 + 1 + PiecesV4.RUNOFF;
                for (double s = 0; s <= sk.end; s += 2) {
                    double[] p = sk.line.at(s);
                    assertTrue(p[0] >= half + 2 && p[0] <= MountainPlanner.SIZE_X - half - 2,
                            tier + ": x " + p[0] + " at s " + s + " keeps the lane off the rim");
                    assertTrue(p[1] >= half + 2 && p[1] <= MountainPlanner.SIZE_Z - half - 2,
                            tier + ": z " + p[1] + " at s " + s + " keeps the lane off the rim");
                    double dx = Math.max(0, Math.abs(p[0] + 0.5 - (MountainPlanner.SIZE_X / 2.0 + 0.5)) - 3.5);
                    double dz = Math.max(0, Math.abs(p[1] - (MountainPlanner.SIZE_Z - MountainValidator.STAND_BACK + 0.5)) - 3.5);
                    assertTrue(Math.hypot(dx, dz) >= half, tier + ": the lane at s " + s + " keeps off the stand");
                }
            }
        }
    }

    @Test
    void longStraightsLieOnAnAxis() {
        for (MountainTier tier : tiers()) {
            for (Skeleton sk : routes(tier)) {
                for (Centreline.Element e : sk.line.elements()) {
                    if (e.arc() || e.length < Skeleton.AXIS_LONG) {
                        continue;
                    }
                    double h = Math.toDegrees(e.h0);
                    double off = Math.abs(h - 90 * Math.round(h / 90));
                    assertTrue(off < 1e-6, tier + ": a " + Math.round(e.length) + "-block straight at s "
                            + Math.round(e.s0) + " heads " + h + " degrees, not along an axis (its walls would step)");
                }
            }
        }
    }

    @Test
    void theRouteTurnsBothWaysAndTraversesKeepTheirHeading() {
        for (MountainTier tier : tiers()) {
            for (Skeleton sk : routes(tier)) {
                double left = 0;
                double right = 0;
                for (Centreline.Element e : sk.line.elements()) {
                    Skeleton.Tag tag = sk.tag(e);
                    if (!e.arc()) {
                        continue;
                    }
                    if (e.turn > 0) {
                        right += e.angle();
                    } else {
                        left -= e.angle();
                    }
                    if (!tag.role().link() && tag.role() != Skeleton.Role.FINISH) {
                        double band = Math.toDegrees(sk.frame.dir(tag.band()) > 0 ? 0 : Math.PI);
                        double h = Math.toDegrees(e.heading(e.length));
                        double off = Math.abs(((h - band) % 360 + 540) % 360 - 180);
                        assertTrue(off <= Skeleton.MAX_OFF_AXIS + 1e-6, tier + ": a traverse arc ends "
                                + off + " degrees off its band's axis");
                    }
                }
                assertTrue(left > Math.PI && right > Math.PI, tier + ": turns both ways, left " + Math.toDegrees(left)
                        + " right " + Math.toDegrees(right) + " degrees");
            }
        }
    }

    @Test
    void linksAlternateAndKeepTheirRadii() {
        for (MountainTier tier : tiers()) {
            for (Skeleton sk : routes(tier)) {
                for (int k = 0; k < sk.frame.bands; k++) {
                    assertEquals(-sk.frame.dir(k), sk.frame.dir(k + 1), tier + ": band " + (k + 1)
                            + " runs back the other way");
                }
                double[] net = new double[sk.frame.bands];
                for (Centreline.Element e : sk.line.elements()) {
                    Skeleton.Tag tag = sk.tag(e);
                    if (tag.role().link() && tag.band() < net.length) {
                        net[tag.band()] += e.angle();
                    }
                }
                for (int k = 0; k < net.length; k++) {
                    assertEquals(Math.PI, Math.abs(net[k]), 1e-6, tier + ": link " + k + " turns the route round");
                    assertEquals(sk.frame.dir(k), (int) Math.signum(net[k]), tier + ": link " + k
                            + " turns toward the next band, against the link before");
                }
                for (Centreline.Element e : sk.line.elements()) {
                    Skeleton.Tag tag = sk.tag(e);
                    if (!e.arc()) {
                        continue;
                    }
                    if (tag.role() == Skeleton.Role.HAIRPIN) {
                        assertTrue(e.radius >= 30 - 1e-6 && e.radius <= Frame.PITCH_MAX / 2 + 1e-6,
                                tier + ": a hairpin of R " + e.radius);
                    }
                    if (tag.role() == Skeleton.Role.ELBOW) {
                        assertTrue(e.radius >= Frame.elbowRMin(tier) - 1e-6, tier + ": an elbow of R " + e.radius);
                    }
                    if (tag.role() == Skeleton.Role.HAIRPIN || tag.role() == Skeleton.Role.ELBOW) {
                        assertEquals(sk.frame.dir(tag.band()), e.turn, tier + ": link " + tag.band()
                                + " turns toward the next band (east-running bands turn right)");
                    }
                }
            }
        }
    }

    @Test
    void theFinishIsWhereTheStandLooks() {
        for (MountainTier tier : tiers()) {
            for (Skeleton sk : routes(tier)) {
                double[] f = sk.line.at(sk.finish);
                assertEquals(Frame.FINISH_X, f[0], 1.5, tier + ": the finish is in front of the stand, x " + f[0]);
                assertEquals(sk.frame.zFinish, f[1], 1e-6, tier + ": on the finish band, z " + f[1]);
                assertEquals(sk.finish + Frame.RUN_OUT + Frame.PADDOCK, sk.end, 1e-6,
                        tier + ": the run-out and paddock follow it");
                assertEquals(Frame.START, sk.start, 1e-9, tier + ": the start is 30 into the pit");
                double[] p0 = sk.line.at(0);
                assertEquals(sk.frame.pitX(), p0[0], 1e-6, tier + ": the pit's back wall is the frame's");
            }
        }
    }

    @Test
    void aRouteHasNoMoreChicanesThanItsTierAllows() {
        // audit MTN01: the grammar stops drawing chicanes at the tier's most (§5.3: road medium 1-2, hard 1-3,
        // none on easy or a slalom), counted over the whole run, whose bands share the count
        for (MountainTier tier : tiers()) {
            int routes = 0;
            int most = 0;
            for (int i = 0; i < CHICANE_SEEDS; i++) {
                GenRandom root = new GenRandom(0xC41CA9EL + 7919L * i);
                Frame f = Frame.draw(root.fork("frame:0"), tier);
                Skeleton sk = f == null ? null : Skeleton.draw(root.fork("route:0"), f);
                if (sk == null) {
                    continue;
                }
                routes++;
                int chicanes = 0;
                int beat = -1;
                for (Centreline.Element e : sk.line.elements()) {
                    Skeleton.Tag tag = sk.tag(e);
                    if (tag.role() == Skeleton.Role.CHICANE && tag.beat() != beat) {
                        chicanes++;
                        beat = tag.beat();
                    }
                }
                assertTrue(chicanes <= tier.chicMax, tier + " stream " + i + ": " + chicanes + " chicanes, at most "
                        + tier.chicMax);
                most = Math.max(most, chicanes);
            }
            assertTrue(routes >= CHICANE_SEEDS / 2, tier + ": fixture: " + routes + " routes");
            assertTrue(most >= Math.min(1, tier.chicMax), tier + ": the chicanes still come: at most " + most);
        }
    }

    /** Streams per tier the chicane cap is held over. */
    private static final int CHICANE_SEEDS = 200;

    @Test
    void bothMirrorsComeUp() {
        for (MountainTier tier : tiers()) {
            int west = 0;
            List<Skeleton> all = routes(tier);
            for (Skeleton sk : all) {
                west += sk.frame.west ? 1 : 0;
                double[] p0 = sk.line.at(0);
                assertTrue(sk.frame.west ? p0[0] < 240 : p0[0] > 240, tier + ": the pit is at its mirror's end");
                assertEquals(sk.frame.west ? 1 : -1, sk.frame.dir(0), tier + ": and the first band runs away from it");
            }
            assertTrue(west > 0 && west < all.size(), tier + ": both mirrors, " + west + " west of " + all.size());
        }
    }

    @Test
    void theDropPlansKeepTheirRules() {
        List<MountainTier> both = new ArrayList<>(tiers(MountainValidator.FIRST_ALGO));
        both.addAll(tiers(MountainValidator.WIDE_ALGO));
        for (MountainTier tier : both) {
            int plans = 0;
            for (Skeleton sk : routes(tier)) {
                DropPlan dp = DropPlan.draw(new GenRandom(sk.frame.bands * 31L + Math.round(sk.end)), sk);
                if (dp == null) {
                    continue;
                }
                plans++;
                DropPlan.Planner runs = new DropPlan.Planner(new GenRandom(0), sk);
                DropPlan.Drop last = dp.last();
                assertEquals(DropPlan.Kind.FINAL, last.kind(), tier + ": the last lip is the Final Drop");
                double toFinish = sk.finish - last.s();
                assertTrue(toFinish >= BoatEnvelope.zone(last.drop()) + 3 && toFinish <= 70,
                        tier + ": the Final Drop is " + toFinish + " before the finish");
                assertTrue(dp.descent() >= tier.descentMin && dp.descent() <= tier.descentCap,
                        tier + ": descent " + dp.descent());
                assertTrue(dp.drops.size() >= tier.dropsMin - DropPlan.SHORT_BY && dp.drops.size() <= tier.dropsMax,
                        tier + ": " + dp.drops.size() + " drops");
                assertTrue(dp.bigs() <= tier.bigFor(dp.drops.size()), tier + ": " + dp.bigs() + " 2-block drops");
                DropPlan.Drop prev = null;
                for (DropPlan.Drop d : dp.drops) {
                    assertTrue(d.drop() == 1 || d.drop() == 2, tier + ": drops are 1 or 2 blocks");
                    DropPlan.Run run = runs.runAt(d.s());
                    assertTrue(run != null, tier + ": the lip at " + d.s() + " is on a straight");
                    double w = dp.width(d.s() - 0.5);
                    double c = runs.before(d.s(), d.drop());
                    if (tier.wide()) {
                        // the road's tight run-up, 2 past the checkpoint before the lip; the Slalom's as in 0.37.0
                        assertTrue(d.s() - run.s0() >= tier.runUp(c) - 1e-6, tier + " v" + tier.algo + ": its run-up");
                        // its width is the raster invariant's job (MountainPlannerTest): there are no necks
                        assertTrue(d.drop() < 2 || (runs.axis(d.s()) && runs.axis(d.s() - c)), tier + " v" + tier.algo
                                + ": a 2-block lip and the checkpoint before it stand on an axis-aligned straight");
                        assertEquals(tier.width, dp.width(d.s()), 1e-9, tier + " v" + tier.algo
                                + ": the lane keeps its width at every lip");
                    } else {
                        assertTrue(d.s() - run.s0() >= MountainTier.clearBefore(w) - 1e-6,
                                tier + " v" + tier.algo + ": its run-up");
                        assertTrue(d.drop() < 2 || w <= MountainTier.NECK + 1e-6, tier + " v" + tier.algo
                                + ": a 2-block lip stands in a lane at most " + MountainTier.NECK + " wide");
                    }
                    assertTrue(run.s1() - d.s() >= MountainTier.landing(d.drop(), false) - 1e-6,
                            tier + ": its landing strip is straight");
                    if (prev != null) {
                        assertTrue(d.s() - prev.s() >= BoatEnvelope.zone(prev.drop()) + DropPlan.LIP_GAP - 1e-6,
                                tier + ": lips " + prev.s() + " and " + d.s() + " are Z + 12 apart");
                    }
                    for (Skeleton.GateSet g : sk.gates) {
                        assertTrue(d.s() + BoatEnvelope.zone(d.drop()) < g.from() - 3 || d.s() - 3 > g.to(),
                                tier + ": no gate within a lip's flight zone");
                    }
                    prev = d;
                }
            }
            assertTrue(plans > 0, tier + " v" + tier.algo + ": some routes make a drop plan");
        }
    }

    /**
     * The deck fix (0.37.1): the wide hard road's approach floor is the Slalom's {@link Skeleton#APPROACH}, so fill
     * lays more of its lips in the rows and its typical approach is well under 104 (47702e1: median 174); the wide
     * easy and medium roads and algo 4's roads keep 104 / 140.
     */
    @Test
    void theWideHardRoadsApproachIsTheSlaloms() {
        for (int algo : new int[]{MountainPlanner.ALGO, MountainValidator.FIRST_ALGO}) {
            for (String id : List.of("easy", "medium", "hard")) {
                MountainTier tier = MountainTier.of(BoatStyle.ROAD, id, algo);
                List<Double> lens = new ArrayList<>();
                for (Skeleton sk : routes(tier)) {
                    for (Centreline.Element e : sk.line.elements()) {
                        if (e.turn == 0 && sk.tag(e).role() == Skeleton.Role.APPROACH) {
                            lens.add(e.length);
                        }
                    }
                }
                lens.sort(null);
                assertTrue(lens.size() > 10, tier + " v" + algo + ": fixture: approaches " + lens.size());
                double median = lens.get(lens.size() / 2);
                assertTrue(lens.get(0) >= Skeleton.APPROACH - 1e-9, tier + " v" + algo
                        + ": every approach at least 46, the shortest " + lens.get(0));
                if (tier.wide() && tier.hard()) {
                    assertTrue(median < Skeleton.APPROACH_ROAD, tier + ": the median approach under 104: " + median);
                } else {
                    assertTrue(median >= Skeleton.APPROACH_ROAD, tier + " v" + algo
                            + ": the median approach 104 or more: " + median);
                }
            }
        }
    }
}
