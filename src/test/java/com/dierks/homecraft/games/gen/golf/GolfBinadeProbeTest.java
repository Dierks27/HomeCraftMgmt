package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.V4Boxes;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number-range rule for golf, relaxed with proof (GOLF-V4-SPEC §4.4): Golf v4's halves stand in x
 * 8768..9599, the binade [8192, 16384) of a double, while every older golf plan was made in [4096,
 * 8192). A whole-block shift inside one binade is exact; across one the low bits of a putt differ, and
 * these tests pin that no putt plays differently. Fast: 10 algo-3 plans moved up across x 8192 and 10
 * Golf v4 plans moved down across it pass their validators where they stand, witnesses replayed.
 * Slow ({@code -Phcm.slow}): 120 plans, chains of putts from every tee compared putt for putt — the same
 * outcome, the same ticks, every rest spot within a millionth of a block — over at least 500,000
 * putts, with 0 behavioural divergences.
 */
class GolfBinadeProbeTest {

    /** Up from Col E (x 7488 or the 0.35 4864) across 8192: what a recall into the v4 Classic does. */
    private static final int UP = 3904;
    /** Down from Col G (x 8768) across 8192. */
    private static final int DOWN = -1280;

    private static Plan v3(Slots.Def slot, int n) {
        try {
            return GolfPlanner.v3().plan(new PlanInput(slot, LegacyBoxes.half(slot, 'A'), 'A', 20725 + n, 0,
                    GenSeed.seed(0x5EC12E7L, 20725 + n, slot.id(), 0), slot.tierOrMix(), 8, 0, null));
        } catch (GenFailed e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    private static Plan v4(Slots.Def slot, int n) {
        try {
            return new GolfPlanner().plan(new PlanInput(slot, V4Boxes.half(slot, 'A'), 'A', 20725 + n, 0,
                    GenSeed.seed(0x5EC12E7L, 20725 + n, slot.id(), 0), slot.tierOrMix(), 8, 0, null));
        } catch (GenFailed e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    @Test
    void movedAcrossX8192PlansStillPassTheirChecksWhereTheyStand() {
        List<String> bad = IntStream.range(0, 20).parallel().boxed().flatMap(k -> {
            Plan p = k < 10 ? v3(k % 2 == 0 ? Slots.DAILY_GOLF : Slots.TINY_GOLF, k)
                    : v4(k % 2 == 0 ? Slots.DAILY_GOLF : Slots.TINY_GOLF, k);
            int dx = across(p);
            Plan moved = PlanShift.by(p, dx, 0, 0);
            boolean crossed = (p.half().minX() < 8192) != (moved.half().minX() < 8192);
            List<String> out = new ArrayList<>();
            if (!crossed) {
                out.add(p.slot() + " seed " + k + " didn't cross x 8192");
            }
            for (String problem : GolfValidator.quickProblems(moved)) {
                out.add(p.slot() + " algo " + p.algo() + " seed " + k + " moved " + dx + ": " + problem);
            }
            return out.stream();
        }).toList();
        assertEquals(List.of(), bad, "a moved plan is proven again where it stands, and passes");
    }

    @Test
    void aShortProbeFindsNoBehaviouralDivergence() {
        long[] counted = probe(4, 40);
        assertEquals(0, counted[1], "no putt plays differently across x 8192 (of " + counted[0] + ")");
        assertTrue(counted[0] > 10_000, "a real sample: " + counted[0] + " putts");
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void slow120PlansAndHalfAMillionPuttsShowNoDivergence() {
        long[] counted = probe(30, 160);
        System.out.println("GolfBinadeProbe: " + counted[0] + " putts compared, " + counted[1] + " behavioural"
                + " divergences, " + counted[2] + " differing only in the last bits");
        assertTrue(counted[0] >= 500_000, "at least half a million putts: " + counted[0]);
        assertEquals(0, counted[1], "0 behavioural divergences");
    }

    /** The shift that takes {@code p}'s half across x 8192: up from below it, down from above. */
    private static int across(Plan p) {
        return p.half().minX() < 8192 ? UP : DOWN;
    }

    /**
     * {@code plans} plans of each kind (v3 and v4, Golf of the Week and Tiny Golf), {@code chains}
     * chains of up to six random putts from every tee, each compared with the same putts on the plan
     * moved across x 8192: {putts compared, behavioural divergences, last-bit differences}.
     */
    private static long[] probe(int plans, int chains) {
        AtomicLong compared = new AtomicLong();
        AtomicLong diverged = new AtomicLong();
        AtomicLong bits = new AtomicLong();
        IntStream.range(0, plans * 4).parallel().forEach(k -> {
            Slots.Def slot = k % 2 == 0 ? Slots.DAILY_GOLF : Slots.TINY_GOLF;
            boolean v4 = k % 4 >= 2;
            Plan p = v4 ? v4(slot, 300 + k) : v3(slot, 300 + k);
            int dx = across(p);
            Plan moved = PlanShift.by(p, dx, 0, 0);
            PlanBlocks a = PlanBlocks.of(p.half(), p.palette(), p.ops());
            PlanBlocks b = PlanBlocks.of(moved.half(), moved.palette(), moved.ops());
            List<GolfCourse.Hole> ha = ((PlannedGolf) p.course()).course().holes();
            List<GolfCourse.Hole> hb = ((PlannedGolf) moved.course()).course().holes();
            GenRandom r = new GenRandom(k).fork("binade");
            for (int i = 0; i < ha.size(); i++) {
                BallPhysics.Hole areaA = GolfShot.area(a, ha.get(i));
                BallPhysics.Hole areaB = GolfShot.area(b, hb.get(i));
                for (int c = 0; c < chains; c++) {
                    BallPhysics.Ball ballA = GolfShot.tee(a, ha.get(i));
                    BallPhysics.Ball ballB = GolfShot.tee(b, hb.get(i));
                    for (int s = 0; s < 6; s++) {
                        Putt putt = new Putt((float) r.nextDouble(-180, 180), r.nextInt(1, 5));
                        GolfShot.Result ra = GolfShot.play(a, areaA, ballA, putt);
                        GolfShot.Result rb = GolfShot.play(b, areaB, ballB, putt);
                        compared.incrementAndGet();
                        boolean same = ra.outcome() == rb.outcome() && ra.ticks() == rb.ticks()
                                && Math.abs(ra.x() + dx - rb.x()) < 1e-6 && Math.abs(ra.y() - rb.y()) < 1e-6
                                && Math.abs(ra.z() - rb.z()) < 1e-6;
                        if (!same) {
                            diverged.incrementAndGet();
                            break;
                        }
                        if (ra.x() + dx != rb.x()) {
                            bits.incrementAndGet();
                        }
                        if (ra.inCup()) {
                            break;
                        }
                    }
                }
            }
        });
        return new long[]{compared.get(), diverged.get(), bits.get()};
    }
}
