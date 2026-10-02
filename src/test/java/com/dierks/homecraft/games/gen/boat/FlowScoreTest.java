package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.V3Fixtures;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flow score (MOUNTAIN-V2-SPEC §6) and its regression facts: the owner's v3 spiral fails it (F-B, F-R),
 * a ladder of equal bands fails it (F-P, F-V), the planner's safe layouts pass every gate, and each gate
 * trips on a one-element change of a hand-made line.
 */
class FlowScoreTest {

    private static final MountainTier ROAD_M = MountainTier.of(BoatStyle.ROAD, "medium");

    /** A hand-made line: {len, kappa} parts from s 0 (the first the pit's), lips at {s, drop}. */
    static final class Hand {
        final List<RideLine.Part> parts = new ArrayList<>();
        final List<RideLine.Lip> lips = new ArrayList<>();
        final List<FlowScore.Mark> marks = new ArrayList<>();
        double s;

        Hand straight(double len) {
            return part(len, 0, FlowScore.Mark.NONE);
        }

        Hand arc(double r, double degrees) {
            return part(r * Math.toRadians(Math.abs(degrees)), Math.signum(degrees) / r, FlowScore.Mark.NONE);
        }

        Hand hairpin(double r, int hand) {
            return part(Math.PI * r, hand / r, FlowScore.Mark.HAIRPIN);
        }

        Hand pit(double len) {
            return part(len, 0, FlowScore.Mark.PIT);
        }

        Hand part(double len, double kappa, FlowScore.Mark mark) {
            parts.add(new RideLine.Part(s, len, kappa, BoatLine.PACKED, s, s + len, parts.size(), false));
            marks.add(mark);
            s += len;
            return this;
        }

        /** A lip {@code before} blocks before the end of the line so far. */
        Hand lip(double before, int drop) {
            lips.add(new RideLine.Lip(s - before, s - before, drop));
            return this;
        }

        FlowScore score(MountainTier tier) {
            RideLine line = new RideLine(parts, lips, List.of());
            return FlowScore.of(line, BoatLine.run(line.segs()), marks.toArray(new FlowScore.Mark[0]), tier, List.of(),
                    null);
        }
    }

    // ---- the regression facts ---------------------------------------------------------------------------

    @Test
    void theOwnersSpiralFails() throws GenFailed {
        V3Fixtures.Fixture f = null;
        for (V3Fixtures.Fixture x : V3Fixtures.all()) {
            if (x.seed() == V3Fixtures.OWNER_SEED) {
                f = x;
            }
        }
        assertNotNull(f, "the owner's preview is a frozen fixture");
        PlanInput in = new PlanInput(Slots.ICE_BOAT, f.plan().half(), f.half(), f.day(), 0, f.seed(), f.tier(), 6,
                BoatPlanner.WORK_BUDGET, null);
        BoatPlanner.Made m = BoatPlanner.made(in);
        assertEquals(f.hash(), m.plan.hash(), "the algo-3 planner rebuilds the frozen spiral, so its path is the owner's");
        Hand h = new Hand();
        for (TrackPath.Seg g : m.path.segs) {
            if (g.arc) {
                h.part(g.len, g.turn / g.r, FlowScore.Mark.NONE);
            } else if (g.leg == 0 && h.parts.isEmpty()) {
                h.pit(g.len);
            } else {
                h.straight(g.len);
            }
        }
        for (TrackProfile.Lip l : m.profile.lips) {
            h.lips.add(new RideLine.Lip(l.s(), l.s(), l.drop()));
        }
        h.lips.sort((a, b) -> Double.compare(a.s(), b.s()));
        FlowScore fs = h.score(ROAD_M);
        assertTrue(fs.failed.contains("F-B"), "every corner of the spiral turns one way: balance " + fs.balance);
        assertTrue(fs.balance < 0.05, "balance about 0: " + fs.balance);
        assertTrue(fs.failed.contains("F-R"), "and they run on: " + fs.sameWay + " same-way turns");
        assertTrue(fs.sameWay >= 7, "7 turns of one hand in a row (the 8th corner is the pit's): " + fs.sameWay);
        // §6's fact 1 has it failing F-P too; but its legs shrink by about 10 blocks a quarter, so no one lag lines
        // its corners up (period 0.19 under the 0.6 gate). The equal-band ladder below is what F-P catches.
        assertTrue(fs.period < 0.6, "its shrinking legs aren't periodic: " + fs.period);
        assertTrue(fs.failed.contains("F-T"), "and 544 blocks is nowhere near a 2-minute run: " + fs.seconds + " s");
        assertFalse(fs.passes(), "so the owner's spiral fails the flow score: " + fs.summary() + " " + fs.failed);
    }

    @Test
    void aLadderOfEqualBandsFails() {
        Hand h = new Hand().pit(56);
        for (int band = 0; band < 9; band++) {
            h.straight(200).hairpin(45, band % 2 == 0 ? 1 : -1);
        }
        h.straight(200);
        FlowScore fs = h.score(ROAD_M);
        assertTrue(fs.failed.contains("F-P"), "identical bands repeat every 341 blocks: period " + fs.period);
        assertTrue(fs.failed.contains("F-V"), "two beats over and over: variety " + fs.variety + ", share " + fs.share);
        assertFalse(fs.passes(), "the ladder fails: " + fs.failed);
        // full-width bands repeat every 440 or so, past F-P's 400 lags: F-V still catches them
        Hand wide = new Hand().pit(56);
        for (int band = 0; band < 7; band++) {
            wide.straight(300).hairpin(45, band % 2 == 0 ? 1 : -1);
        }
        wide.straight(300);
        FlowScore w = wide.score(ROAD_M);
        assertTrue(w.failed.contains("F-V"), "a full-width ladder fails F-V: " + w.variety + ", share " + w.share);
    }

    @Test
    void thePlannersSafeLayoutsPassEveryGate() {
        for (BoatStyle style : BoatStyle.values()) {
            for (String id : List.of("easy", "medium", "hard")) {
                MountainTier tier = MountainTier.of(style, id);
                for (boolean west : new boolean[]{true, false}) {
                    MountainPlanner.Candidate c = MountainPlanner.safeCandidate(tier, west);
                    assertNotNull(c, tier + (west ? " west" : " east") + ": a safe layout");
                    FlowScore fs = FlowScore.of(c.sk(), c.drops());
                    assertTrue(fs.failed.isEmpty() && fs.missing.isEmpty() && fs.passes(),
                            tier + (west ? " west" : " east") + ": every gate and the tier's features: " + fs.failed
                                    + " " + fs.missing + " " + fs.summary());
                }
            }
        }
    }

    // ---- each gate on a one-element change ----------------------------------------------------------------

    @Test
    void flippingAnArcBreaksTheBalance() {
        FlowScore even = new Hand().pit(56).straight(100).arc(100, 60).straight(100).arc(100, -60).straight(100)
                .score(ROAD_M);
        assertFalse(even.failed.contains("F-B"), "a left and a right: balance " + even.balance);
        FlowScore flipped = new Hand().pit(56).straight(100).arc(100, 60).straight(100).arc(100, 60).straight(100)
                .score(ROAD_M);
        assertTrue(flipped.failed.contains("F-B"), "both right: balance " + flipped.balance);
    }

    @Test
    void aFourthTurnOfOneHandBreaksTheRun() {
        FlowScore three = new Hand().pit(56).straight(60).arc(80, 30).straight(60).arc(80, 30).straight(60)
                .arc(80, 30).straight(60).arc(80, -30).straight(60).score(ROAD_M);
        assertFalse(three.failed.contains("F-R"), "three right turns, then a left: " + three.sameWay);
        FlowScore four = new Hand().pit(56).straight(60).arc(80, 30).straight(60).arc(80, 30).straight(60)
                .arc(80, 30).straight(60).arc(80, 30).straight(60).score(ROAD_M);
        assertTrue(four.failed.contains("F-R"), "four in a row: " + four.sameWay);
    }

    @Test
    void takingAwayABrakeDropMakesAHardBrake() {
        FlowScore braked = new Hand().pit(56).straight(200).lip(30, 2).arc(40, 90).straight(60).score(ROAD_M);
        assertFalse(braked.failed.contains("F-K"), "a lip 30 before the bend: " + braked.brakesPerKm + "/km");
        FlowScore bare = new Hand().pit(56).straight(200).arc(40, 90).straight(60).score(ROAD_M);
        assertTrue(bare.failed.contains("F-K"), "no lip: a hard brake, " + bare.brakesPerKm + "/km");
    }

    @Test
    void aTightSweeperAfterALongStraightIsNotCarried() {
        FlowScore wide = new Hand().pit(56).straight(200).arc(400, 25).straight(100).score(ROAD_M);
        assertFalse(wide.failed.contains("F-C"), "R 400 is carried at 38 b/s: " + wide.carry);
        FlowScore tight = new Hand().pit(56).straight(200).arc(60, 25).straight(100).score(ROAD_M);
        assertTrue(tight.failed.contains("F-C"), "R 60 isn't: " + tight.carry);
        FlowScore hairpin = new Hand().pit(56).straight(200).arc(400, 25).straight(100).hairpin(40, -1)
                .straight(100).score(ROAD_M);
        assertFalse(hairpin.failed.contains("F-C"), "a hairpin link is a planned brake, out of the carry: "
                + hairpin.carry);
    }

    @Test
    void equalStraightsHaveNoRhythm() {
        FlowScore equal = new Hand().pit(56).straight(80).arc(100, 30).straight(80).arc(100, -30).straight(80)
                .arc(100, 30).straight(80).arc(100, -30).straight(80).score(ROAD_M);
        assertTrue(equal.failed.contains("F-L"), "all 80: CV " + equal.rhythm);
        FlowScore varied = new Hand().pit(56).straight(30).arc(100, 30).straight(150).arc(100, -30).straight(60)
                .arc(100, 30).straight(200).arc(100, -30).straight(20).score(ROAD_M);
        assertFalse(varied.failed.contains("F-L"), "30, 150, 60, 200, 20: CV " + varied.rhythm);
    }

    @Test
    void theTimeWindowIsAGate() {
        FlowScore shortRun = new Hand().pit(56).straight(400).score(ROAD_M);
        assertTrue(shortRun.failed.contains("F-T"), "456 blocks is far under 105 s: " + shortRun.seconds);
        Hand longRun = new Hand().pit(56);
        while (longRun.s < 4_400) {
            longRun.straight(150).arc(120, 40).straight(150).arc(120, -40);
        }
        FlowScore tooLong = longRun.score(ROAD_M);
        assertTrue(tooLong.failed.contains("F-T"), "4,400 blocks is over 135 s: " + tooLong.seconds);
    }

    @Test
    void aRunWithoutTheTiersFeaturesIsMissingThem() {
        FlowScore plain = new Hand().pit(56).straight(80).arc(100, 30).straight(80).arc(100, -30).straight(80)
                .score(ROAD_M);
        assertFalse(plain.missing.isEmpty(), "no S-curves, chicanes, long straights or hairpin: " + plain.missing);
        assertTrue(plain.failed.contains("F-F"), "F-F fails with them missing");
        assertTrue(String.join(" ", plain.missing).contains("S-curves"), "the S-curves are named: " + plain.missing);
    }
}
