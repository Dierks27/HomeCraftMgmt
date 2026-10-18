package com.dierks.homecraft.games.gen.boat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner's tables per boat planner version (MOUNTAIN-V21-SPEC §2.1, D1, D3, D5, D8): the wide road's
 * (algo 5) are the spec's, they differ from algo 4's (0.37.0, frozen) only where the spec says, and both aim
 * inside their own algo's proof ({@code MountainValidator.Rules.of(tier, slalom, algo)}).
 */
class MountainTierTest {

    /** The fields 0.37.1 changes (§2.1: "only these fields change from 961a5bc"), and the version itself. */
    private static final Set<String> CHANGED = Set.of("algo", "width", "pitWidth", "tMin", "tMax", "dropsMin",
            "dropsMax", "bigMost", "bigShare", "descentLo", "descentHi", "descentCap", "descentMin", "boostMin",
            "boostMax", "blueMin", "blueMax");

    private static final String[] TIERS = {"easy", "medium", "hard"};

    @Test
    void todaysTablesAreTheWideRoadsAndAlgoFourKeepsItsOwn() {
        assertEquals(5, MountainPlanner.ALGO, "D12: the wide road is boat planner v5");
        for (BoatStyle style : BoatStyle.values()) {
            for (String id : TIERS) {
                MountainTier now = MountainTier.of(style, id);
                assertSame(MountainTier.of(style, id, MountainPlanner.ALGO), now, style + " " + id
                        + ": of(style, id) is today's planner's");
                assertEquals(MountainValidator.WIDE_ALGO, now.algo, style + " " + id + ": algo 5");
                assertTrue(now.wide(), style + " " + id + ": the wide road's rules");
                MountainTier v4 = MountainTier.of(style, id, MountainValidator.FIRST_ALGO);
                assertEquals(MountainValidator.FIRST_ALGO, v4.algo, style + " " + id + ": algo 4's tables are kept");
                assertFalse(v4.wide(), style + " " + id + ": with algo 4's rules");
                assertSame(now, MountainTier.of(style, id.toUpperCase(), MountainValidator.WIDE_ALGO + 1),
                        style + " " + id + ": a later version reads the wide road's (any case)");
            }
        }
        assertNull(MountainTier.of(BoatStyle.ROAD, "EEE", MountainValidator.FIRST_ALGO), "a golf mix isn't a tier");
        assertNull(MountainTier.of(null, "easy"), "no style, no tier");
    }

    @Test
    void theWideRoadsTablesAreTheSpecs() {
        // style, tier: width, pit, tMin, tMax, dropsMin, dropsMax, bigMost, descentLo, descentHi, cap, floor,
        // boostMin, boostMax, blueMin, blueMax (§2.1; bigShare 1 on every one, D3)
        Object[][] want = {
                {BoatStyle.ROAD, "easy", 18, 18, 100, 130, 18, 28, 28, 32, 44, 48, 30, 0, 0, 0, 0},
                {BoatStyle.ROAD, "medium", 15, 15, 120, 150, 24, 38, 38, 48, 64, 70, 46, 0, 1, 0, 0},
                {BoatStyle.ROAD, "hard", 14, 14, 120, 150, 28, 40, 40, 52, 68, 74, 48, 0, 0, 1, 2},
                {BoatStyle.SLALOM, "easy", 15, 15, 90, 120, 8, 14, 14, 18, 26, 28, 18, 0, 0, 0, 0},
                {BoatStyle.SLALOM, "medium", 13, 13, 105, 135, 12, 20, 20, 26, 38, 40, 26, 0, 0, 0, 0},
                {BoatStyle.SLALOM, "hard", 11, 11, 105, 135, 14, 22, 22, 30, 42, 44, 30, 0, 0, 0, 0}};
        for (Object[] w : want) {
            MountainTier t = MountainTier.of((BoatStyle) w[0], (String) w[1], MountainValidator.WIDE_ALGO);
            int[] got = {t.width, t.pitWidth, (int) t.tMin, (int) t.tMax, t.dropsMin, t.dropsMax, t.bigMost,
                    t.descentLo, t.descentHi, t.descentCap, t.descentMin, t.boostMin, t.boostMax, t.blueMin, t.blueMax};
            int[] spec = new int[got.length];
            for (int i = 0; i < spec.length; i++) {
                spec[i] = (Integer) w[i + 2];
            }
            assertEquals(Arrays.toString(spec), Arrays.toString(got), t + ": §2.1's numbers");
            assertEquals(1.0, t.bigShare, 1e-12, t + ": every drop may be a 2-block drop (D3)");
            assertEquals(t.dropsMax, t.bigFor(t.dropsMax), t + ": so the Final Drop may be big too");
        }
    }

    @Test
    void theWideRoadDiffersFromAlgoFourOnlyWhereTheSpecSays() throws IllegalAccessException {
        for (BoatStyle style : BoatStyle.values()) {
            for (String id : TIERS) {
                MountainTier v4 = MountainTier.of(style, id, MountainValidator.FIRST_ALGO);
                MountainTier v5 = MountainTier.of(style, id, MountainValidator.WIDE_ALGO);
                Set<String> differ = new TreeSet<>();
                for (Field f : MountainTier.class.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Object a = f.get(v4);
                    Object b = f.get(v5);
                    boolean same = a instanceof int[] x && b instanceof int[] y ? Arrays.equals(x, y)
                            : Objects.equals(a, b);
                    if (!same) {
                        differ.add(f.getName());
                    }
                }
                List<String> extra = new ArrayList<>(differ);
                extra.removeAll(CHANGED);
                assertEquals(List.of(), extra, style + " " + id + ": nothing else moves (§2.1)");
                assertTrue(differ.contains("algo"), style + " " + id + ": the version does");
            }
        }
    }

    @Test
    void algoFoursTablesAreFrozen() {
        // the 0.37.0 numbers its layouts were made from (961a5bc): the road 9 / 7 / 7, the old windows and drops
        Object[][] want = {
                {BoatStyle.ROAD, "easy", 9, 90, 120, 16, 22, 4, 18, 26, 28, 16, 0, 0, 0, 0},
                {BoatStyle.ROAD, "medium", 7, 105, 135, 22, 30, 30, 36, 50, 52, 32, 1, 3, 0, 0},
                {BoatStyle.ROAD, "hard", 7, 105, 135, 26, 34, 34, 46, 61, 64, 44, 0, 0, 2, 4},
                {BoatStyle.SLALOM, "easy", 15, 90, 120, 8, 12, 0, 10, 14, 16, 8, 0, 0, 0, 0},
                {BoatStyle.SLALOM, "medium", 13, 105, 135, 12, 18, 12, 18, 30, 34, 16, 0, 0, 0, 0},
                {BoatStyle.SLALOM, "hard", 11, 105, 135, 14, 20, 16, 22, 36, 40, 20, 0, 0, 0, 0}};
        double[] share = {1, 2.0 / 3, 4.0 / 5, 0, 2.0 / 3, 4.0 / 5};
        for (int k = 0; k < want.length; k++) {
            Object[] w = want[k];
            MountainTier t = MountainTier.of((BoatStyle) w[0], (String) w[1], MountainValidator.FIRST_ALGO);
            int[] got = {t.width, (int) t.tMin, (int) t.tMax, t.dropsMin, t.dropsMax, t.bigMost, t.descentLo,
                    t.descentHi, t.descentCap, t.descentMin, t.boostMin, t.boostMax, t.blueMin, t.blueMax};
            int[] old = new int[got.length];
            for (int i = 0; i < old.length; i++) {
                old[i] = (Integer) w[i + 2];
            }
            assertEquals(Arrays.toString(old), Arrays.toString(got), t + " v4: 0.37.0's numbers");
            assertEquals(share[k], t.bigShare, 1e-12, t + " v4: its 2-block share");
        }
    }

    @Test
    void eachVersionAimsInsideItsOwnProof() {
        for (int algo : new int[]{MountainValidator.FIRST_ALGO, MountainValidator.WIDE_ALGO}) {
            for (BoatStyle style : BoatStyle.values()) {
                for (String id : TIERS) {
                    MountainTier t = MountainTier.of(style, id, algo);
                    MountainValidator.Rules r = MountainValidator.Rules.of(id, t.slalom(), algo);
                    String at = t + " v" + algo;
                    assertEquals(r.narrowest(), t.proofP, at + ": P is the proof's");
                    if (t.road()) { // a slalom has no slow ground in its lane, so its B is moot (7 here, the proof 5)
                        assertEquals(r.iceLine(), t.proofB, at + ": B is the proof's");
                    }
                    assertEquals(r.descentMin(), t.descentMin, at + ": the planner's floor is the proof's");
                    assertEquals(r.descentCap(), t.descentCap, at + ": and its cap");
                    assertTrue(t.descentLo >= t.descentMin && t.descentHi <= t.descentCap,
                            at + ": the aimed descent lies inside them");
                    for (int n = t.dropsMin - DropPlan.SHORT_BY; n <= t.dropsMax; n++) {
                        assertTrue(t.bigFor(n) <= r.bigDrops(n), at + ": " + t.bigFor(n) + " of " + n
                                + " drops big, within the proof's " + r.bigDrops(n));
                    }
                    assertTrue(t.boostMax + t.blueMax == 0 || r.blueIce(),
                            at + ": blue only where the proof allows it");
                }
            }
        }
    }

    @Test
    void thePlungeHoldsABigDropOnTheAxis() {
        // §3.1: tier.runUp(setBack(W, true)) + landing(2) + 2: 40 / 39 / 38 on the roads (18 / 15 / 14)
        int[][] road = {{18, 40}, {15, 39}, {14, 38}};
        String[] ids = {"easy", "medium", "hard"};
        for (int i = 0; i < 3; i++) {
            MountainTier t = MountainTier.of(BoatStyle.ROAD, ids[i]);
            assertEquals(road[i][0], t.width, t + ": its width");
            assertEquals(road[i][1], t.lipPlunge(), t + ": the elbows' plunge for a lip");
            assertEquals(t.runUp(MountainTier.setBack(t.width, true)) + MountainTier.landing(2, false) + 2,
                    t.lipPlunge(), t + ": its run-up, landing strip and 2");
        }
        for (String id : ids) {
            MountainTier t = MountainTier.of(BoatStyle.SLALOM, id);
            assertEquals(MountainTier.clearBefore(t.width) + 28, t.lipPlunge(), t + ": the Slalom's run-up as 0.37.0");
            MountainTier v4 = MountainTier.of(BoatStyle.ROAD, id, MountainValidator.FIRST_ALGO);
            assertEquals(Math.max(8, v4.width + 3) + 28, v4.lipPlunge(), v4 + " v4: 0.37.0's runUp(W) + 26 + 2");
        }
    }
}
