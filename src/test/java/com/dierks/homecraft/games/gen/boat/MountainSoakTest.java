package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Mountain Run v2's soak (MOUNTAIN-V2-SPEC §15, MOUNTAIN-V21-SPEC §6; {@code gradle test -Pslow}): {@value #SEEDS}
 * seeds of each style at each tier, every plan proven, in its window and under its caps, the lane its tier's
 * width and every leg its bound, the wall arrows pointing the way, the trees fed, the safe layout rare, the
 * planner's time bounded, a road's deck of pieces placed (three in four or more); the distributions printed for
 * tuning, with where the drops stand and §6.2's targets (printed, not asserted).
 *
 * <p>The knobs ({@code -P} on gradle's command line, which build.gradle passes on): {@code hcm.soak.only} one
 * style x tier or several ({@code road:medium,slalom:hard}; none = all six), {@code hcm.soak.seeds} the plans
 * of each (default {@value #SEEDS}), {@code hcm.soak.from} the first seed (default 1).
 */
class MountainSoakTest {

    static final int SEEDS = 300;
    static final Box HALF = new Box(6080, 96, 2880, 6559, 271, 3519);
    /** §5.4's arrow rule is checked on each style and tier's first this many plans (§6.2). */
    static final int ARROW_PLANS = 10;
    /** Lips each at most this far after the one before are one staircase run (§6.1). */
    static final double STAIR_RUN = 70;
    /** §6.2's p99 line for a plan's blocks (MOUNTAIN-V21-SPEC D11; 0.37.0's was 380,000). */
    static final int P99_OPS = 390_000;
    /** §6.2's floor for the trees a plan grows, at p10. */
    static final int P10_TREES = 280;

    private static final Pattern KEPT = Pattern.compile("\\((\\d+) kept\\)|after (\\d+) candidates");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    /** What one style and tier did over its seeds. */
    static final class Tally {
        final String name;
        final MountainTier tier;
        /** Plans not made, or not proven: "seed: why". */
        final List<String> problems = new ArrayList<>();
        /** Where a plan's lane is narrower than its tier's width ({@link RasterV4#narrowAt}). */
        final List<String> narrow = new ArrayList<>();
        /** A leg over 60 across, or over 66 holding a 2-block drop. */
        final List<String> legs = new ArrayList<>();
        /** A wall arrow off §5.4's rule, on the first {@value #ARROW_PLANS} plans. */
        final List<String> arrows = new ArrayList<>();
        /** The distributions, printed in this order. */
        final Map<String, List<Double>> dist = new LinkedHashMap<>();
        /** The fall in each band from the top (the finish's band left out): band k's falls over the plans. */
        final List<List<Double>> bands = new ArrayList<>();
        /** Stage B's builds that fell through, by their first line with its numbers blanked. */
        final Map<String, Integer> refused = new TreeMap<>();
        final int[] placed = new int[PiecesV4.Kind.values().length];
        final int[] dealt = new int[PiecesV4.Kind.values().length];
        int plans;
        int safe;
        /** Stage B builds, refused or not. */
        int builds;
        /** Refusals naming a side pocket (V5), of {@link #builds}. */
        int pockets;
        /** Plans built with every piece (no fewer-pieces build, not the safe layout); those dealt and with a forest. */
        int full;
        int fullForestsDealt;
        int fullForests;

        Tally(BoatStyle style, String tier) {
            this.name = style + " " + tier;
            this.tier = MountainTier.of(style, tier);
        }

        void add(String what, double v) {
            dist.computeIfAbsent(what, k -> new ArrayList<>()).add(v);
        }

        List<Double> get(String what) {
            return dist.getOrDefault(what, List.of());
        }

        String print() {
            StringBuilder out = new StringBuilder();
            out.append(String.format(Locale.ROOT, "%s: %d plans, safe %d, problems %d, narrow %d, legs %d, arrows %d",
                    name, plans, safe, problems.size(), narrow.size(), legs.size(), arrows.size()));
            for (Map.Entry<String, List<Double>> e : dist.entrySet()) {
                out.append(String.format(Locale.ROOT, "%n  %s %s", e.getKey(), d(e.getValue())));
            }
            StringBuilder deck = new StringBuilder();
            for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
                if (dealt[k.ordinal()] > 0) {
                    deck.append(' ').append(k.word).append(' ').append(placed[k.ordinal()]).append('/')
                            .append(dealt[k.ordinal()]);
                }
            }
            out.append(String.format(Locale.ROOT, "%n  pieces placed of dealt%s", deck));
            out.append(String.format(Locale.ROOT, "%n  forests on FULL plans: %d of the %d dealt one (%d FULL plans)",
                    fullForests, fullForestsDealt, full));
            StringBuilder fall = new StringBuilder();
            for (int k = 0; k < bands.size(); k++) {
                fall.append(String.format(Locale.ROOT, " %d: med %.0f (%d plans)", k, median(bands.get(k)),
                        bands.get(k).size()));
            }
            out.append(String.format(Locale.ROOT, "%n  fall by band from the top (the finish's left out):%s", fall));
            int no = refused.values().stream().mapToInt(Integer::intValue).sum();
            out.append(String.format(Locale.ROOT, "%n  Stage B: %d builds, %d fell through%s", builds, no,
                    no == 0 ? "" : ", by first line:"));
            List<Map.Entry<String, Integer>> lines = new ArrayList<>(refused.entrySet());
            lines.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // stable: ties keep the key order
            for (Map.Entry<String, Integer> e : lines) {
                out.append(String.format(Locale.ROOT, "%n    %d x %s", e.getValue(), e.getKey()));
            }
            for (String t : targets()) {
                out.append(String.format(Locale.ROOT, "%n  target %s", t));
            }
            return out.toString();
        }

        /** §6.2's targets, each with how it went: printed and reported, not asserted. */
        List<String> targets() {
            List<String> out = new ArrayList<>();
            boolean road = tier.road();
            int t = switch (tier.id) {
                case "easy" -> 0;
                case "medium" -> 1;
                default -> 2;
            };
            int descent = (road ? new int[]{33, 47, 49} : new int[]{20, 28, 32})[t];
            out.add(target("descent median", median(get("descent")), ">= " + descent,
                    median(get("descent")) >= descent));
            double big = median(get("2-block share"));
            out.add(target("2-block share median", big, "0.5-0.75", big >= 0.5 && big <= 0.75));
            out.add(String.format(Locale.ROOT, "T_m median %.1f s%s", median(get("T_m")),
                    road ? " (about 115 / 137 / 137 on the roads)" : ""));
            out.add(String.format(Locale.ROOT, "raster blocks p50 %.0f, p99 %.0f (D11's estimate 78-92k)",
                    pct(get("raster blocks"), 50), pct(get("raster blocks"), 99)));
            if (!road) {
                return out;
            }
            int pit = PiecesV4.Kind.SAND_PIT.ordinal();
            out.add(target("snow drifts placed of dealt", dealt[pit] == 0 ? 1 : placed[pit] / (double) dealt[pit],
                    ">= 0.75", placed[pit] * 4 >= dealt[pit] * 3));
            if (fullForestsDealt > 0) {
                double share = fullForests / (double) fullForestsDealt;
                out.add(target("forests on FULL plans dealt one", share, ">= 0.6", share >= 0.6));
            }
            if (t == 0) {
                double share = builds == 0 ? 0 : pockets / (double) builds;
                out.add(target("V5 side-pocket refusals of Stage B builds", share, "<= 0.1", share <= 0.1));
            } else {
                double least = median(get("least band fall"));
                out.add(target("spread: least band fall, median plan", least, ">= 5", least >= 5));
                double gap = pct(get("longest lip-free"), 90);
                out.add(target("spread: longest lip-free stretch p90", gap, "<= 250", gap <= 250));
                double approach = median(get("APPROACH share"));
                out.add(target("spread: APPROACH lips median", approach, "<= 0.4", approach <= 0.4));
            }
            if (t == 1) {
                double run = pct(get("longest staircase run"), 90);
                out.add(target("staircase runs: the longest p90", run, "<= 4", run <= 4));
            }
            return out;
        }

        /** §6.2's hard criteria that failed, a line each; none when this style and tier passes. */
        List<String> failed() {
            List<String> out = new ArrayList<>();
            problems.forEach(x -> out.add(name + ": not made or not proven: " + x));
            narrow.forEach(x -> out.add(name + ": the lane narrower than " + tier.width + ": " + x));
            legs.forEach(x -> out.add(name + ": a leg over its bound: " + x));
            arrows.forEach(x -> out.add(name + ": an arrow off the rule: " + x));
            if (safe > Math.ceil(0.02 * plans)) {
                out.add(name + ": the safe layout on " + safe + " of " + plans + " plans, more than 2%");
            }
            for (double s : get("T_m")) {
                if (s < tier.tMin || s > tier.tMax) {
                    out.add(name + ": T_m " + s + " outside " + tier.tMin + "-" + tier.tMax);
                }
            }
            for (double o : get("blocks")) {
                if (o > MountainValidator.MAX_OPS) {
                    out.add(name + ": " + o + " blocks, over " + MountainValidator.MAX_OPS);
                }
            }
            if (pct(get("blocks"), 99) > P99_OPS) {
                out.add(name + ": p99 blocks " + pct(get("blocks"), 99) + ", over " + P99_OPS);
            }
            if (pct(get("planner ms"), 99) > 30_000) {
                out.add(name + ": p99 planning " + pct(get("planner ms"), 99) + " ms, over 30 s");
            }
            for (double c : get("checkpoints")) {
                if (c > MountainValidator.MAX_CHECKPOINTS) {
                    out.add(name + ": " + c + " checkpoints, over " + MountainValidator.MAX_CHECKPOINTS);
                }
            }
            if (pct(get("trees"), 10) < P10_TREES) {
                out.add(name + ": p10 trees " + pct(get("trees"), 10) + ", under " + P10_TREES);
            }
            if (tier.road()) {
                // audit MTN-R3-00: the §5.3 deck is placed, not mostly dropped
                int in = 0;
                int of = 0;
                for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
                    in += placed[k.ordinal()];
                    of += dealt[k.ordinal()];
                }
                if (in * 4 < of * 3) {
                    out.add(name + ": " + in + " pieces of " + of + " dealt, under three in four");
                }
            }
            return out;
        }
    }

    static String target(String what, double got, String want, boolean ok) {
        return String.format(Locale.ROOT, "%s %.2f (%s): %s", what, got, want, ok ? "ok" : "MISSED");
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

    /** The {@code p}th percentile of {@code xs} as {@link #d} reads it; NaN for none. */
    static double pct(List<Double> xs, int p) {
        if (xs.isEmpty()) {
            return Double.NaN;
        }
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(s.size() * p / 100);
    }

    static double median(List<Double> xs) {
        return pct(xs, 50);
    }

    /** The style x tier pairs {@code hcm.soak.only} asks for ({@code road:medium}, comma-separated), or all six. */
    static List<Object[]> asked() {
        String only = System.getProperty("hcm.soak.only", "").trim().toLowerCase(Locale.ROOT);
        List<String> want = new ArrayList<>();
        for (String w : only.split(",")) {
            if (!w.isBlank()) {
                want.add(w.trim());
            }
        }
        boolean all = want.isEmpty();
        List<Object[]> out = new ArrayList<>();
        for (BoatStyle style : BoatStyle.values()) {
            for (String tier : List.of("easy", "medium", "hard")) {
                if (all || want.remove(style.id() + ":" + tier)) {
                    out.add(new Object[]{style, tier});
                }
            }
        }
        assertEquals(List.of(), want, "hcm.soak.only names style:tier pairs (road:medium and the like)");
        return out;
    }

    /** The whole number system property {@code key} is set to, or {@code otherwise}. */
    static long knob(String key, long otherwise) {
        String v = System.getProperty(key, "").trim();
        return v.isEmpty() ? otherwise : Long.parseLong(v);
    }

    /** {@code n} seeds of {@code style} at {@code tier}, from {@code first} on. */
    static Tally soak(BoatStyle style, String tier, int n, long first) {
        Tally t = new Tally(style, tier);
        for (long seed = first; t.plans < n; seed++) {
            if (BoatStyle.of(seed) != style) {
                continue;
            }
            t.plans++;
            long t0 = System.nanoTime();
            MountainPlanner.Made m;
            try {
                m = MountainPlanner.made(new PlanInput(Slots.ICE_BOAT, HALF, 'A', 20725, 0, seed, tier, 6, 0, null));
            } catch (GenFailed e) {
                t.problems.add(seed + ": " + e.getMessage());
                continue;
            }
            tally(t, seed, m, (System.nanoTime() - t0) / 1e6);
        }
        return t;
    }

    /** Plan {@code m} of seed {@code seed}, made in {@code ms}, into {@code t} (its {@link Tally#plans}th). */
    static void tally(Tally t, long seed, MountainPlanner.Made m, double ms) {
        Plan p = m.finished();
        for (String problem : MountainValidator.problems(p, t.tier.id)) {
            t.problems.add(seed + ": " + problem);
        }
        for (double s : m.raster.narrowAt()) {
            t.narrow.add(seed + " at " + s);
        }
        for (double[] leg : RasterV4Test.legs(m)) {
            if (leg[2] > leg[3]) {
                t.legs.add(String.format(Locale.ROOT, "%d: from %.1f, %.2f across, at most %.0f", seed, leg[0],
                        leg[2], leg[3]));
            }
        }
        if (t.plans <= ARROW_PLANS) {
            for (String a : RasterV4Test.arrowProblems(m)) {
                t.arrows.add(seed + ": " + a);
            }
        }
        boolean safe = m.how.startsWith("the safe");
        t.safe += safe ? 1 : 0;
        t.add("T_m", m.seconds);
        t.add("planner ms", ms);
        t.add("blocks", p.ops().size());
        t.add("raster blocks", m.rasterBlocks);
        t.add("trees", m.trees);
        t.add("checkpoints", ((PlannedTrial) p.course()).course().checkpoints().size());
        Matcher kept = KEPT.matcher(m.how);
        if (kept.find()) {
            t.add("Stage A kept", Double.parseDouble(kept.group(1) != null ? kept.group(1) : kept.group(2)));
        }
        t.builds += m.refused.size() + 1;
        for (String r : m.refused) {
            t.refused.merge(NUMBER.matcher(r).replaceAll("#"), 1, Integer::sum);
            t.pockets += r.contains("side pocket") ? 1 : 0;
        }
        // the drops: how many, how far down, how big, and where they stand (B's Where, §6.1)
        Skeleton sk = m.cand.sk();
        DropPlan dp = m.cand.drops();
        int lips = dp.drops.size();
        int bigs = 0;
        int approach = 0;
        int[] perBand = new int[sk.frame.bands + 1];
        for (DropPlan.Drop d : dp.drops) {
            bigs += d.drop() >= 2 ? 1 : 0;
            Skeleton.Tag tag = sk.tag(sk.line.elementAt(d.s()));
            approach += tag.role() == Skeleton.Role.APPROACH ? 1 : 0;
            perBand[Math.min(tag.band(), sk.frame.bands)] += d.drop();
        }
        t.add("drops", lips);
        t.add("descent", dp.descent());
        t.add("2-block share", lips == 0 ? 0 : bigs / (double) lips);
        t.add("APPROACH share", lips == 0 ? 0 : approach / (double) lips);
        int least = Integer.MAX_VALUE;
        for (int k = 0; k < sk.frame.bands; k++) {
            while (t.bands.size() <= k) {
                t.bands.add(new ArrayList<>());
            }
            t.bands.get(k).add((double) perBand[k]);
            least = Math.min(least, perBand[k]);
        }
        t.add("least band fall", least);
        // the longest stretch without a lip, links not counted (SPREAD's measure), and the staircase runs
        DropPlan.Planner along = new DropPlan.Planner(new GenRandom(0), sk);
        double prev = sk.start;
        double gap = 0;
        int run = 0;
        int longest = 0;
        int runs = 0;
        for (int i = 0; i <= lips; i++) {
            double s = i < lips ? dp.drops.get(i).s() : sk.finish;
            gap = Math.max(gap, along.unlinked(prev, s));
            if (i < lips) {
                run = i > 0 && s - prev <= STAIR_RUN ? run + 1 : 1;
                longest = Math.max(longest, run);
                boolean ends = i + 1 >= lips || dp.drops.get(i + 1).s() - s > STAIR_RUN;
                runs += ends && run >= 3 ? 1 : 0;
            }
            prev = s;
        }
        t.add("longest lip-free", gap);
        t.add("longest staircase run", longest);
        t.add("staircase runs of 3+", runs);
        t.add("length", sk.finish - sk.start);
        t.add("flow", m.cand.flow().total);
        t.add("carry", m.cand.flow().carry);
        t.add("period", m.cand.flow().period);
        t.add("variety", m.cand.flow().variety);
        // the pieces: the deck placed, and the forests on plans built with every piece
        int here = 0;
        int deck = 0;
        for (PiecesV4.Kind k : PiecesV4.Kind.values()) {
            t.placed[k.ordinal()] += m.pieces.count(k);
            t.dealt[k.ordinal()] += m.pieces.dealt(k);
            here += m.pieces.count(k);
            deck += m.pieces.dealt(k);
        }
        if (deck > 0) {
            t.add("pieces", here / (double) deck);
        }
        if (!safe && !m.how.contains("(fewer pieces)") && !m.how.contains("(no pieces)")) {
            t.full++;
            if (m.pieces.dealt(PiecesV4.Kind.FOREST) > 0) {
                t.fullForestsDealt++;
                t.fullForests += m.pieces.count(PiecesV4.Kind.FOREST) > 0 ? 1 : 0;
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "hcm.slow", matches = "true")
    void threeHundredSeedsOfEachStyleAndTierAreProvenAndInTheirWindows() {
        int seeds = (int) knob("hcm.soak.seeds", SEEDS);
        long first = knob("hcm.soak.from", 1);
        List<String> failed = new ArrayList<>();
        for (Object[] pair : asked()) {
            Tally t = soak((BoatStyle) pair[0], (String) pair[1], seeds, first);
            System.out.println(t.print());
            failed.addAll(t.failed());
        }
        assertEquals(List.of(), failed, "§6.2's hard criteria on every style and tier soaked");
    }

    @Test
    void theTallyReadsAPlan() {
        // the soak's own arithmetic on one pinned plan, so a slow run never dies of a tally bug
        MountainPlanner.Made m = MountainPlannerTest.made(MountainPlannerTest.HALF_A, 2, "medium");
        Tally t = new Tally(BoatStyle.ROAD, "medium");
        t.plans = 1;
        tally(t, 2, m, 1);
        assertEquals(List.of(), t.problems, "proven");
        assertEquals(List.of(), t.narrow, "its lane its tier's width");
        assertEquals(List.of(), t.legs, "every leg within its bound");
        assertEquals(List.of(), t.arrows, "every arrow pointing the way");
        assertEquals(List.of(), t.failed().stream().filter(x -> !x.contains("dealt, under three in four")).toList(),
                "and every other criterion one plan can meet (a deck is the tier's, over its seeds)");
        for (String key : List.of("Stage A kept", "descent", "2-block share", "least band fall", "longest lip-free",
                "APPROACH share", "longest staircase run", "raster blocks", "trees")) {
            assertEquals(1, t.get(key).size(), key + " measured");
        }
        assertEquals(m.cand.drops().descent(), t.get("descent").get(0), 1e-9, "the descent is the plan's");
        assertEquals(m.refused.size() + 1, t.builds, "its Stage B builds: " + m.how + ", " + m.refused);
        assertFalse(t.print().isBlank(), "and it prints");
    }
}
