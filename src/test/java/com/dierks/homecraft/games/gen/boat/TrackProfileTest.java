package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.RaceStand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mountain Run's heights (Course Variety §2.4-2.6): drops only on straights with their run-up and
 * landing strip, spaced Z(d) + 12 and never in another's zone, their checkpoints able to cross them,
 * the tier's sizes and fall, the Final Drop Z(d) + 3 from a finish that is 12-30 from the stand.
 */
class TrackProfileTest {

    private static final Box HALF = LegacyBoxes.v036(Slots.ICE_BOAT, 'A');
    private static final int SX = RaceStand.centreX(HALF) - HALF.minX();
    private static final int SZ = RaceStand.centreZ(HALF) - HALF.minZ();
    private static final int TOP = HALF.minY() + BoatPlanner.TOP_ABOVE;

    /** The first profile that comes together for {@code day}, with its path. */
    private static Object[] drawn(BoatPlanner.Level level, int day) {
        long seed = GenSeed.seed(0x5EC12E7L, 20_000 + day, Slots.ICE_BOAT.id(), 0);
        GenRandom root = new GenRandom(seed);
        for (int t = 0; t < BoatPlanner.TRIES; t++) {
            TrackPath path = TrackPath.draw(root.fork("track:" + t), level, SX + 0.5, SZ + 0.5);
            TrackProfile p = TrackProfile.draw(root.fork("drops:" + t), path, level, TOP, SX, SZ);
            if (p != null) {
                return new Object[]{path, p};
            }
        }
        return null;
    }

    @Test
    void dropsKeepTheirSpacingStraightsAndTheTiersSizes() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int made = 0;
            for (int day = 0; day < 150; day++) {
                Object[] d = drawn(level, day);
                if (d == null) {
                    continue;
                }
                made++;
                TrackPath path = (TrackPath) d[0];
                TrackProfile p = (TrackProfile) d[1];
                String at = level + " day " + day;
                assertTrue(p.lips.size() >= level.minDrops() && p.lips.size() <= level.maxDrops(),
                        at + ": the tier's number of drops: " + p.lips.size());
                int bigs = 0;
                TrackProfile.Lip prev = null;
                for (TrackProfile.Lip l : p.lips) {
                    TrackPath.Seg st = path.straight(l.leg());
                    assertTrue(l.s() - st.s0 >= level.runUp() - 1e-9, at + ": a flat straight run-up before every drop");
                    assertTrue(st.s1() - l.s() >= level.landing(l.drop()) - 1e-9,
                            at + ": and a straight landing strip after it (" + level.landing(l.drop()) + ")");
                    double edge = l.s() - TrackProfile.spotStart(st);
                    assertEquals(0.5, edge - Math.floor(edge), 1e-9, at + ": the edge falls between two columns");
                    if (prev == null) {
                        assertTrue(l.s() >= TrackProfile.START + level.firstLip(), at + ": the first drop is at least "
                                + level.firstLip() + " after the start");
                    } else {
                        assertTrue(l.s() >= prev.s() + BoatEnvelope.zone(prev.drop()) + BoatPlanner.LIP_GAP,
                                at + ": drops are Z(d) + 12 apart along the track");
                    }
                    assertFalse(Double.isNaN(TrackProfile.post(path, level, l, p.finish)),
                            at + ": the checkpoints can cross every drop");
                    bigs += l.drop() == 2 ? 1 : 0;
                    prev = l;
                }
                assertTrue(TrackProfile.apart(path, level, p.lips.subList(0, p.lips.size() - 1), p.last()),
                        at + ": the Final Drop is outside every zone before it");
                assertTrue(bigs <= level.proof().bigDrops(), at + ": at most the tier's 2s");
                assertTrue(p.descent() <= level.proof().descent(), at + ": at most the tier's fall");
                assertEquals(TOP - p.descent(), p.bottom(), "the bottom is the top less the whole fall");
                assertTrue(TrackProfile.finalFits(path, level, p.last(), p.finish),
                        at + ": the Final Drop is Z(d) + 3 from the finish");
                double[] f = path.at(p.finish);
                double toStand = TrackProfile.standDistance(f[0], f[1], SX, SZ);
                assertTrue(toStand >= 12 && toStand <= 30, at + ": the finish is 12-30 from the platform: " + toStand);
                assertEquals(p.finish + TrackProfile.RUN_OUT + TrackProfile.PADDOCK + 0.5, p.end, 1e-9,
                        "14 blocks of run-out and 6 of paddock after the finish");
            }
            assertTrue(made >= 145, level + ": nearly every seed's drops come together within its tries: " + made);
        }
    }

    @Test
    void theFinalDropIsInFrontOfTheStandWhereverTheLandingStripsLetOneBe() {
        // Review B1 (§2.5: the Final Drop 43-70 before the finish, in front of the stand). The landing
        // strips are fixed (22 / 26 packed, 33 / 41 blue; the schedule valve keeps them), and they
        // decide where a drop can be: on medium the sixth leg often holds one, so the Final Drop goes
        // there first; easy's fifth leg (25-30) and hard's inner legs (19-38) never hold a run-up and a
        // landing strip, so theirs is the last drop that fits, pinned here so it never creeps earlier.
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            int inFront = 0;
            int made = 0;
            for (int day = 0; day < 150; day++) {
                Object[] d = drawn(level, day);
                if (d == null) {
                    continue;
                }
                made++;
                TrackProfile p = (TrackProfile) d[1];
                TrackProfile.Lip last = p.last();
                double after = p.finish - last.s();
                String at = level + " day " + day + ": the Final Drop is " + Math.round(after) + " before the finish";
                assertTrue(after >= BoatEnvelope.zone(last.drop()) + 3, at + ", at least Z(d) + 3");
                if (after <= 70) {
                    inFront++;
                }
                switch (level) {
                    case EASY -> assertTrue(after <= 135, at + ": on the fourth leg, two before the finish");
                    case MEDIUM -> assertTrue(after <= 125, at + ": on the fifth leg or the sixth");
                    case HARD -> assertTrue(after <= 300, at + ": on the fourth leg or the fifth");
                }
            }
            if (level == BoatPlanner.Level.MEDIUM) {
                assertTrue(inFront >= made / 4, "medium: the Final Drop is 43-70 before the finish on at least a quarter"
                        + " of days (where the sixth leg holds it and the tier's drops fit before it): " + inFront + " of "
                        + made);
            }
        }
    }

    @Test
    void theLevelStepsDownAtEachEdgeAndNowhereElse() {
        Object[] d = drawn(BoatPlanner.Level.HARD, 4);
        assertNotNull(d, "a hard profile");
        TrackProfile p = (TrackProfile) d[1];
        int y = TOP;
        double from = 0;
        for (TrackProfile.Lip l : p.lips) {
            assertEquals(y, p.level(from + 0.25), "flat before the edge");
            assertEquals(y, p.level(l.s() - 0.25), "up to the edge");
            y -= l.drop();
            assertEquals(y, p.level(l.s() + 0.25), "one step down after it");
            from = l.s();
        }
        assertEquals(y, p.level(p.end), "flat to the end");
        assertEquals(p.lips.size(), p.lipsBetween(0, p.end), "every drop is between the start and the end");
        assertEquals(0, p.lipsBetween(p.lips.get(0).s(), p.lips.get(0).s()), "none between a point and itself");
    }

    @Test
    void aDropInsideAnothersZoneIsNotApart() {
        Object[] d = drawn(BoatPlanner.Level.MEDIUM, 2);
        TrackPath path = (TrackPath) d[0];
        TrackProfile p = (TrackProfile) d[1];
        TrackProfile.Lip first = p.lips.get(0);
        TrackProfile.Lip tooNear = new TrackProfile.Lip(first.leg(), first.s() + 20, 1);
        assertFalse(TrackProfile.apart(path, BoatPlanner.Level.MEDIUM, List.of(first), tooNear),
                "20 blocks on, one flight could clear both");
        assertTrue(TrackProfile.apart(path, BoatPlanner.Level.MEDIUM, List.of(first), p.lips.get(1)),
                "the next drop the profile placed is outside it");
        assertTrue(TrackProfile.exit(path, BoatPlanner.Level.MEDIUM, first, p.end) > first.s() + 40,
                "the zone reaches at least Z(1) along the track");
    }

    @Test
    void theFinishIsInTheMiddleOfAColumnOnTheLastLegTwelveToThirtyFromTheStand() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            for (int day = 0; day < 30; day++) {
                long seed = GenSeed.seed(0x5EC12E7L, 20_000 + day, Slots.ICE_BOAT.id(), 0);
                TrackPath path = TrackPath.draw(new GenRandom(seed).fork("track:0"), level, SX + 0.5, SZ + 0.5);
                double f = TrackProfile.finish(new GenRandom(seed), path, level, SX, SZ);
                if (Double.isNaN(f)) {
                    continue;
                }
                TrackPath.Seg last = path.straight(path.legs() - 1);
                assertTrue(f >= last.s0 && f + TrackProfile.RUN_OUT + TrackProfile.PADDOCK + 1 <= last.s1() + 1e-9,
                        level + ": the finish, its run-out and paddock on the last straight");
                assertEquals(0, f - last.s0 - Math.floor(f - last.s0), 1e-9, "in the middle of a column");
                double[] at = path.at(f);
                double d = TrackProfile.standDistance(at[0], at[1], SX, SZ);
                assertTrue(d >= 12 && d <= 30, level + ": 12-30 from the platform's edge: " + d);
                double safe = TrackProfile.finish(null, path, level, SX, SZ);
                assertEquals(safe, TrackProfile.finish(null, path, level, SX, SZ), "the safe spiral's is fixed");
            }
        }
    }

    @Test
    void theSafeSpiralsDropsAreTheTiersFewestAtFixedPlaces() {
        for (BoatPlanner.Level level : BoatPlanner.Level.values()) {
            TrackPath path = TrackPath.safe(0, 1, level, SX + 0.5, SZ + 0.5);
            double finish = TrackProfile.finish(null, path, level, SX, SZ);
            int[] drops = switch (level) {
                case EASY -> new int[]{1, 1, 1, 1};
                case MEDIUM -> new int[]{1, 1, 1, 1, 1};
                case HARD -> new int[]{1, 1, 2, 1};
            };
            List<TrackProfile.Lip> a = TrackProfile.fixed(path, level, drops, finish);
            List<TrackProfile.Lip> b = TrackProfile.fixed(path, level, drops, finish);
            assertNotNull(a, level + ": they fit");
            assertEquals(a, b, level + ": the same every time");
            List<Integer> legs = new ArrayList<>();
            for (TrackProfile.Lip l : a) {
                legs.add(l.leg());
            }
            assertEquals(legs.stream().distinct().count(), legs.size(), level + ": one drop a leg: " + legs);
            assertNull(TrackProfile.fixed(path, level, new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1}, finish),
                    level + ": nine drops don't fit");
        }
    }
}
