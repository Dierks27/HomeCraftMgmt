package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mountain Run v2's soak (MOUNTAIN-V2-SPEC §15; {@code gradle test -Pslow}): {@value #SEEDS} seeds of each
 * style at each tier, every plan proven, in its window and under its caps, the safe layout rare, the
 * planner's time bounded; the distributions printed for tuning.
 */
class MountainSoakTest {

    static final int SEEDS = 300;
    static final Box HALF = new Box(6080, 96, 2880, 6559, 271, 3519);

    /** What one style and tier did over its seeds. */
    record Tally(String name, List<String> problems, int safe, int plans, List<Double> seconds, List<Double> ms,
                 List<Double> ops, List<Double> cps, List<Double> drops, List<Double> descent, List<Double> length,
                 List<Double> flow, List<Double> carry, List<Double> period, List<Double> variety) {

        String print() {
            return String.format(Locale.ROOT, "%s: %d plans, safe %d, problems %d%n  T_m %s%n  planner ms %s%n"
                            + "  ops %s%n  checkpoints %s%n  drops %s%n  descent %s%n  length %s%n  flow %s%n"
                            + "  carry %s%n  period %s%n  variety %s", name, plans, safe, problems.size(), d(seconds),
                    d(ms), d(ops), d(cps), d(drops), d(descent), d(length), d(flow), d(carry), d(period), d(variety));
        }
    }

    static String d(List<Double> xs) {
        if (xs.isEmpty()) {
            return "-";
        }
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return String.format(Locale.ROOT, "min %.2f p10 %.2f med %.2f p90 %.2f p99 %.2f max %.2f", s.get(0),
                s.get(s.size() / 10), s.get(s.size() / 2), s.get(s.size() * 9 / 10), s.get(s.size() * 99 / 100),
                s.get(s.size() - 1));
    }

    /** {@code n} seeds of {@code style} at {@code tier}, from {@code first} on. */
    static Tally soak(BoatStyle style, String tier, int n, long first) {
        Tally t = new Tally(style + " " + tier, new ArrayList<>(), 0, 0, new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        int safe = 0;
        int plans = 0;
        for (long seed = first; plans < n; seed++) {
            if (BoatStyle.of(seed) != style) {
                continue;
            }
            plans++;
            long t0 = System.nanoTime();
            MountainPlanner.Made m;
            try {
                m = MountainPlanner.made(new PlanInput(Slots.ICE_BOAT, HALF, 'A', 20725, 0, seed, tier, 6, 0, null));
            } catch (GenFailed e) {
                t.problems().add(seed + ": " + e.getMessage());
                continue;
            }
            t.ms().add((System.nanoTime() - t0) / 1e6);
            Plan p = m.finished();
            for (String problem : MountainValidator.problems(p, tier)) {
                t.problems().add(seed + ": " + problem);
            }
            safe += m.how.startsWith("the safe") ? 1 : 0;
            t.seconds().add(m.seconds);
            t.ops().add((double) p.ops().size());
            t.cps().add((double) ((PlannedTrial) p.course()).course().checkpoints().size());
            t.drops().add((double) m.cand.drops().drops.size());
            t.descent().add((double) m.cand.drops().descent());
            t.length().add(m.cand.sk().finish - m.cand.sk().start);
            t.flow().add(m.cand.flow().total);
            t.carry().add(m.cand.flow().carry);
            t.period().add(m.cand.flow().period);
            t.variety().add(m.cand.flow().variety);
        }
        return new Tally(t.name(), t.problems(), safe, plans, t.seconds(), t.ms(), t.ops(), t.cps(), t.drops(),
                t.descent(), t.length(), t.flow(), t.carry(), t.period(), t.variety());
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void threeHundredSeedsOfEachStyleAndTierAreProvenAndInTheirWindows() {
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                Tally t = soak(style, tier, SEEDS, 1);
                System.out.println(t.print());
                MountainTier mt = MountainTier.of(style, tier);
                assertEquals(List.of(), t.problems(), t.name() + ": every plan made and proven");
                assertTrue(t.safe() <= Math.ceil(0.02 * SEEDS), t.name() + ": the safe layout at most 2%: " + t.safe());
                for (double s : t.seconds()) {
                    assertTrue(s >= mt.tMin && s <= mt.tMax, t.name() + ": T_m " + s + " in the window");
                }
                for (double o : t.ops()) {
                    assertTrue(o <= MountainValidator.MAX_OPS, t.name() + ": " + o + " blocks");
                }
                List<Double> ops = new ArrayList<>(t.ops());
                Collections.sort(ops);
                assertTrue(ops.get(ops.size() * 99 / 100) <= 380_000, t.name() + ": p99 blocks under 380k");
                List<Double> ms = new ArrayList<>(t.ms());
                Collections.sort(ms);
                assertTrue(ms.get(ms.size() * 99 / 100) <= 30_000, t.name() + ": p99 planning under 30 s");
                for (double c : t.cps()) {
                    assertTrue(c <= MountainValidator.MAX_CHECKPOINTS, t.name() + ": " + c + " checkpoints");
                }
            }
        }
    }
}
