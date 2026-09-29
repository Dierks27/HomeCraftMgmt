package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code gen:} block of a course row's YAML (GEN-SPEC §5.1), for both course codecs:
 *
 * <pre>
 * gen:
 *   slot: fresh_parkour_easy
 *   generator: parkour
 *   algo: 1
 *   day: 20724                       # the edition's first day
 *   date: '2026-09-28'
 *   cadence: 7                       # its length in days: the edition is 7:38
 *   reroll: 0
 *   seed: 3f2a91c07d1e55b0
 *   half: B
 *   plan: 3c9e51aa07b2
 *   ref_ms: 38000
 *   gold_ms: 76000
 *   silver_ms: 114000
 *   built_at: 1790661730000
 *   attempts: [0, 2, 0]              # golf only
 *   witness: [[[12.5, 5]], ...]      # golf only: per hole, [yaw, power] per putt
 *   recall_slot: fresh_classic_parkour   # only for an archived course recalled into a Classics slot:
 *   recall_from: 1790661730000           #   which slot, since when (epoch ms)
 *   recall_day: 20731                    #   and that moment's local day
 * </pre>
 *
 * Plain maps and lists in and out, so it needs no server and each codec keeps its own YAML
 * handling. {@code date} is for people reading the row; {@code day} is what counts. A block
 * written before cadences (no {@code cadence}) reads as a daily edition. A yaw is written as the
 * shortest decimal of its float and read back to exactly that float.
 *
 * <p>Reading never guesses: a block that is there but can't be read throws
 * {@link IllegalArgumentException} naming the part, so a damaged row is reported instead of
 * turning quietly into a hand-built course.
 */
public final class GenTagCodec {

    /** The key the block sits under. */
    public static final String KEY = "gen";

    private GenTagCodec() {
    }

    /** The block for {@code tag}, keys in the order above. */
    public static Map<String, Object> write(GenTag tag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", tag.slot());
        m.put("generator", tag.generator());
        m.put("algo", tag.algo());
        m.put("day", tag.day());
        m.put("date", tag.date().toString());
        m.put("cadence", tag.cadence());
        m.put("reroll", tag.reroll());
        m.put("seed", GenSeed.hex(tag.seed()));
        m.put("half", String.valueOf(tag.half()));
        m.put("plan", tag.planHash());
        m.put("ref_ms", tag.refMs());
        m.put("gold_ms", tag.goldMs());
        m.put("silver_ms", tag.silverMs());
        m.put("built_at", tag.builtAt());
        if (!tag.attempts().isEmpty()) {
            m.put("attempts", new ArrayList<>(tag.attempts()));
        }
        if (!tag.witness().isEmpty()) {
            List<List<List<Object>>> holes = new ArrayList<>();
            for (List<Putt> line : tag.witness()) {
                List<List<Object>> putts = new ArrayList<>();
                for (Putt p : line) {
                    putts.add(List.of(Double.parseDouble(Float.toString(p.yaw())), p.power()));
                }
                holes.add(putts);
            }
            m.put("witness", holes);
        }
        if (tag.recall() != null) {
            m.put("recall_slot", tag.recall().slot());
            m.put("recall_from", tag.recall().from());
            m.put("recall_day", tag.recall().day());
        }
        return m;
    }

    /** The tag back from {@link #write}'s block; throws {@link IllegalArgumentException} naming what can't be read. */
    public static GenTag read(Map<?, ?> m) {
        if (m == null) {
            throw new IllegalArgumentException("gen is not a map");
        }
        String slot = text(m, "slot");
        String generator = text(m, "generator");
        int algo = (int) whole(m.get("algo"), "algo");
        long day = whole(m.get("day"), "day");
        int cadence = m.get("cadence") == null ? Edition.DAILY : (int) whole(m.get("cadence"), "cadence");
        if (cadence < Edition.DAILY || cadence > Edition.MAX_CADENCE) {
            throw new IllegalArgumentException("gen cadence is not 1-" + Edition.MAX_CADENCE);
        }
        int reroll = m.get("reroll") == null ? 0 : (int) whole(m.get("reroll"), "reroll");
        Long seed = m.get("seed") instanceof String s ? GenSeed.parse(s) : null;
        if (seed == null) {
            throw new IllegalArgumentException("gen seed is not hex");
        }
        String half = text(m, "half");
        if (!half.equalsIgnoreCase("A") && !half.equalsIgnoreCase("B")) {
            throw new IllegalArgumentException("gen half is not A or B");
        }
        Object rawPlan = m.get("plan");
        if (rawPlan != null && !(rawPlan instanceof String)) {
            throw new IllegalArgumentException("gen plan is not text");
        }
        String plan = rawPlan == null ? "" : ((String) rawPlan).trim();
        List<Integer> attempts = new ArrayList<>();
        if (m.get("attempts") != null) {
            for (Object o : list(m.get("attempts"), "attempts")) {
                attempts.add((int) whole(o, "an attempt"));
            }
        }
        List<List<Putt>> witness = new ArrayList<>();
        if (m.get("witness") != null) {
            int hole = 0;
            for (Object h : list(m.get("witness"), "witness")) {
                hole++;
                List<Putt> line = new ArrayList<>();
                for (Object p : list(h, "witness hole " + hole)) {
                    List<?> pair = list(p, "a putt of hole " + hole);
                    if (pair.size() != 2) {
                        throw new IllegalArgumentException("a putt of hole " + hole + " is not [yaw, power]");
                    }
                    float yaw = Float.parseFloat(Double.toString(number(pair.get(0), "a putt's yaw")));
                    int power = (int) whole(pair.get(1), "a putt's power");
                    if (power < 1 || power > 5) {
                        throw new IllegalArgumentException("a putt's power is not 1-5");
                    }
                    line.add(new Putt(yaw, power));
                }
                witness.add(line);
            }
        }
        GenTag.Recall recall = null;
        if (m.get("recall_slot") != null) {
            recall = new GenTag.Recall(text(m, "recall_slot"), whole(m.get("recall_from"), "recall_from"),
                    whole(m.get("recall_day"), "recall_day"));
        }
        return new GenTag(slot, generator, algo, day, reroll, seed, half.charAt(0), plan,
                optionalWhole(m, "ref_ms"), optionalWhole(m, "gold_ms"), optionalWhole(m, "silver_ms"), attempts,
                witness, optionalWhole(m, "built_at"), cadence, recall);
    }

    private static String text(Map<?, ?> m, String key) {
        if (m.get(key) instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        throw new IllegalArgumentException("gen " + key + " is missing");
    }

    private static long optionalWhole(Map<?, ?> m, String key) {
        return m.get(key) == null ? 0 : whole(m.get(key), key);
    }

    private static List<?> list(Object raw, String what) {
        if (raw instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException("gen " + what + " is not a list");
    }

    private static long whole(Object raw, String what) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
            return ((Number) raw).longValue();
        }
        throw new IllegalArgumentException("gen " + what + " is not a whole number");
    }

    private static double number(Object raw, String what) {
        if (raw instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("gen " + what + " is not a number");
    }
}
