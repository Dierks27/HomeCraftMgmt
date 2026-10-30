package com.dierks.homecraft.games.gen.rings;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.RingsV3Boxes;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number-range rule for Sky Rings, relaxed with proof (the golf probe's precedent): the live
 * course stands in x, z 4096..8191, the binade of a double the elytra sim was always run in, while
 * Classic Sky Rings stands west of it at x 1760..3359, z 2880..3391. The sim works in absolute
 * {@link StrictMath} doubles, so a whole-block shift across a binade can change the low bits of a
 * flight; a recalled plan is therefore proven again where it stands ({@code PlanCheck} runs
 * {@link RingsValidator} on the moved plan), and these tests measure how often that proof holds.
 * Fast: 10 easy plans moved onto the Classic's box pass the validator there. Slow ({@code -Phcm.slow}):
 * 120 plans, at least 99% of them.
 */
class RingsBinadeProbeTest {

    private static Plan live(int n) {
        try {
            return new RingsPlanner().plan(new PlanInput(Slots.SKY_RINGS, RingsV3Boxes.half(Slots.SKY_RINGS, 'A'), 'A',
                    20725 + n, 0, GenSeed.seed(0x5EC12E7L, 20725 + n, Slots.SKY_RINGS.id(), 0), "easy", 6, 0, null));
        } catch (GenFailed e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    /** How many of {@code n} live plans still pass the validator moved onto the Classic's box, and the first that don't. */
    private static int[] probe(int n, List<String> problems) {
        Box classic = RingsV3Boxes.half(Slots.CLASSIC_RINGS, 'A');
        List<String> bad = IntStream.range(0, n).parallel().boxed().flatMap(k -> {
            Plan p = live(k);
            List<String> out = new ArrayList<>();
            for (String problem : RingsValidator.problems(p, "easy")) {
                out.add("seed " + k + " where it was made: " + problem);
            }
            Plan moved = PlanShift.to(p, classic);
            if (moved.half().minX() >= 4096 || p.half().minX() < 4096) {
                out.add("seed " + k + " didn't cross x 4096");
            }
            List<String> there = RingsValidator.problems(moved, "easy");
            if (!there.isEmpty()) {
                out.add("seed " + k + " moved: " + there.get(0));
            }
            return out.stream();
        }).toList();
        problems.addAll(bad);
        return new int[]{n - (int) bad.stream().filter(b -> b.contains(" moved: ")).count()};
    }

    @Test
    void movedOntoTheClassicPlansStillPassTheirCheckWhereTheyStand() {
        List<String> problems = new ArrayList<>();
        int ok = probe(10, problems)[0];
        System.out.println("RingsBinadeProbe: " + ok + " of 10 easy plans pass the validator moved onto Classic Sky Rings");
        assertEquals(List.of(), problems.stream().filter(p -> !p.contains(" moved: ")).toList(),
                "every plan passes where it was made and the move crosses x 4096");
        assertTrue(ok >= 9, "at least 9 of 10 moved plans are proven again where they stand: " + problems);
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void ninetyNinePercentOfMovedPlansPassTheirCheck() {
        List<String> problems = new ArrayList<>();
        int ok = probe(120, problems)[0];
        System.out.println("RingsBinadeProbe (slow): " + ok + " of 120 easy plans pass moved onto Classic Sky Rings");
        assertTrue(ok >= 119, "at least 99% of moved plans are proven again where they stand: " + problems);
    }
}
