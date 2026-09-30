package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The weekly variety quota (Course Variety §3.3): which order each tier's templates are dealt in,
 * so a Golf of the Week has some water, some sand, some height, a tree hole and a big drop, not the
 * same few shapes in a new order.
 *
 * <p><b>Why.</b> Before Adventure Golf each tier's list was shuffled once and hole k of a tier got
 * the k-th template; with as many templates as holes, every course had the same seven shapes.
 * Longer lists fix the sameness, and the quota makes sure a course draws a good mix from them.
 *
 * <p><b>The deal.</b> Deal k ({@value #DEALS} of them, k = 0 first) shuffles every tier's list with
 * its own fork, {@code order:<tier>:<k>}; the holes' first-attempt templates are then counted
 * ({@link HoleTemplate#features}) against the targets for the mix's size. The first deal that meets
 * every target is used; when none does, the one meeting the most (the lowest k on a tie). Nothing
 * but the seed and the mix goes in, so a stored layout is re-derived to the same deal. A target
 * counts only what the mix's tiers can hold: water in play needs Medium or Hard holes, and a tier
 * deals each of its templates once before it repeats one. A fallback (a hole's second template or
 * the safe straight) may break the quota after the deal; the planner's summary says what the course
 * ended up with.
 *
 * <p><b>Dry courses.</b> Tiny Golf is the four-year-old's course: its lists never hold a template
 * that puts water in play, whatever mix an admin gives it (Easy never has any anyway). Pure.
 */
public final class Quota {

    /** What a hole has, as the quota counts it. */
    public enum Feature {
        /** A pond, a creek or an island in play. */
        WATER("water"),
        /** A bunker: flush or sunken sand. */
        SAND("sand"),
        /** Height: a ramp, a raised green, a hump, a hill, a volcano, terraces or a dogleg that drops. */
        HEIGHT("height"),
        /** Trees in play. */
        TREES("trees"),
        /** A drop of a whole block the ball flies off (a hill, a volcano, terraces, a dogleg that drops). */
        BIG_DROP("big drop"),
        /** A pond to look at beyond the wall (Easy's pond side): Tiny Golf counts it as its tree hole. */
        VIEW("pond view");

        private final String words;

        Feature(String words) {
            this.words = words;
        }

        /** Admin words for it. */
        public String words() {
            return words;
        }
    }

    /**
     * How many deals are tried. The spec's first figure was 16, but a random deal meets every
     * target only about one time in ten (Tiny Golf's three Easy holes need a sand trap, a height and
     * a tree or pond view among the first three of seven: 11%), so 16 left one course in six short;
     * 64 leave about one in a thousand. Dealing is only shuffling, so it costs nothing.
     */
    public static final int DEALS = 64;
    /** The features that have targets, in the order the summary says them. */
    static final List<Feature> COUNTED = List.of(Feature.WATER, Feature.SAND, Feature.HEIGHT, Feature.TREES,
            Feature.BIG_DROP);

    private Quota() {
    }

    /**
     * The targets for a mix's size, before they are capped to what its tiers can hold: 7 holes or
     * more {water 2, sand 2, height 3, trees 1, big drop 1}; 4-6 {1, 1, 2, 1, 0}; 3 (Tiny Golf)
     * {0, 1, 1, 1, 0}, its tree hole a tree garden or a pond view; 2 or fewer, none.
     */
    static Map<Feature, Integer> table(int holes) {
        int[] t;
        if (holes >= 7) {
            t = new int[]{2, 2, 3, 1, 1};
        } else if (holes >= 4) {
            t = new int[]{1, 1, 2, 1, 0};
        } else if (holes == 3) {
            t = new int[]{0, 1, 1, 1, 0};
        } else {
            t = new int[]{0, 0, 0, 0, 0};
        }
        Map<Feature, Integer> out = new EnumMap<>(Feature.class);
        for (int i = 0; i < COUNTED.size(); i++) {
            out.put(COUNTED.get(i), t[i]);
        }
        return out;
    }

    /**
     * The targets for {@code mix}: {@link #table} capped at what its tiers can hold, which for each
     * tier is the fewer of its holes and its templates with the feature (a tier deals each template
     * once before it repeats one).
     */
    public static Map<Feature, Integer> targets(String mix, boolean dry) {
        Map<Feature, Integer> out = table(mix.length());
        Map<Character, Integer> holes = new HashMap<>();
        for (char c : mix.toCharArray()) {
            holes.merge(c, 1, Integer::sum);
        }
        for (Feature f : COUNTED) {
            int room = 0;
            for (Map.Entry<Character, Integer> e : holes.entrySet()) {
                int with = 0;
                for (HoleTemplate t : list(e.getKey(), dry)) {
                    if (counts(f, t.features(e.getKey()), mix.length())) {
                        with++;
                    }
                }
                room += Math.min(e.getValue(), with);
            }
            out.put(f, Math.min(out.get(f), room));
        }
        return out;
    }

    /** Whether a hole with {@code has} counts toward {@code f} in a course of {@code holes} holes. */
    private static boolean counts(Feature f, Set<Feature> has, int holes) {
        return has.contains(f) || f == Feature.TREES && holes == 3 && has.contains(Feature.VIEW);
    }

    /**
     * The templates a tier deals from, in their fixed order ({@link HoleTemplate#forTier}); on a dry
     * course without the ones that put water in play at that tier.
     */
    public static List<HoleTemplate> list(char tier, boolean dry) {
        List<HoleTemplate> out = new ArrayList<>();
        for (HoleTemplate t : HoleTemplate.forTier(tier)) {
            if (!dry || !t.features(tier).contains(Feature.WATER)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The deal a course is planned with.
     *
     * @param k      which deal (0 to {@value #DEALS} - 1)
     * @param order  each tier's templates, in the order its holes take them
     * @param counts what the holes' first-attempt templates have
     * @param met    how many targets they meet
     */
    public record Deal(int k, Map<Character, List<HoleTemplate>> order, Map<Feature, Integer> counts, int met) {
    }

    /** The deal for a course of {@code mix} from its seed's stream {@code root}. */
    public static Deal deal(GenRandom root, String mix, boolean dry) {
        Map<Feature, Integer> targets = targets(mix, dry);
        Deal best = null;
        for (int k = 0; k < DEALS; k++) {
            Map<Character, List<HoleTemplate>> order = new HashMap<>();
            for (char tier : new char[]{'E', 'M', 'H'}) {
                List<HoleTemplate> list = list(tier, dry);
                GenRandom r = root.fork("order:" + tier + ":" + k);
                for (int i = list.size() - 1; i > 0; i--) {
                    int j = r.nextInt(i + 1);
                    HoleTemplate tmp = list.get(i);
                    list.set(i, list.get(j));
                    list.set(j, tmp);
                }
                order.put(tier, List.copyOf(list));
            }
            List<Map.Entry<HoleTemplate, Character>> first = new ArrayList<>();
            for (int i = 0; i < mix.length(); i++) {
                first.add(Map.entry(template(order, mix, i, false), mix.charAt(i)));
            }
            Map<Feature, Integer> counts = count(first, mix.length());
            int met = met(counts, targets);
            if (best == null || met > best.met()) {
                best = new Deal(k, Map.copyOf(order), counts, met);
            }
            if (met == COUNTED.size()) {
                break;
            }
        }
        return best;
    }

    /**
     * Hole {@code i}'s template in a deal: the k-th of its tier's list for the tier's k-th hole, or
     * with {@code next} the one after it (a hole's attempts after the first six).
     */
    static HoleTemplate template(Map<Character, List<HoleTemplate>> order, String mix, int i, boolean next) {
        char tier = mix.charAt(i);
        int k = 0;
        for (int j = 0; j < i; j++) {
            if (mix.charAt(j) == tier) {
                k++;
            }
        }
        List<HoleTemplate> list = order.get(tier);
        return list.get((k + (next ? 1 : 0)) % list.size());
    }

    /** What holes (each a template and the tier it was drawn for) have, counted per feature. */
    static Map<Feature, Integer> count(Collection<Map.Entry<HoleTemplate, Character>> holes, int size) {
        Map<Feature, Integer> out = new EnumMap<>(Feature.class);
        for (Feature f : COUNTED) {
            out.put(f, 0);
        }
        for (Map.Entry<HoleTemplate, Character> h : holes) {
            Set<Feature> has = h.getKey().features(h.getValue());
            for (Feature f : COUNTED) {
                if (counts(f, has, size)) {
                    out.merge(f, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** What drawn holes have, counted per feature. */
    static Map<Feature, Integer> countLayouts(List<HoleLayout> holes) {
        Map<Feature, Integer> out = new EnumMap<>(Feature.class);
        for (Feature f : COUNTED) {
            out.put(f, 0);
        }
        for (HoleLayout l : holes) {
            for (Feature f : COUNTED) {
                if (counts(f, l.features(), holes.size())) {
                    out.merge(f, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** How many targets {@code counts} meets. */
    static int met(Map<Feature, Integer> counts, Map<Feature, Integer> targets) {
        int met = 0;
        for (Feature f : COUNTED) {
            if (counts.getOrDefault(f, 0) >= targets.getOrDefault(f, 0)) {
                met++;
            }
        }
        return met;
    }

    /** The summary line: "quota: water 2/2, sand 3/2, ... (deal 3)", what the course has over its targets. */
    static String line(Map<Feature, Integer> has, Map<Feature, Integer> targets, int deal) {
        StringBuilder out = new StringBuilder("quota:");
        for (int i = 0; i < COUNTED.size(); i++) {
            Feature f = COUNTED.get(i);
            out.append(i == 0 ? " " : ", ").append(f.words()).append(' ').append(has.getOrDefault(f, 0)).append('/')
                    .append(targets.getOrDefault(f, 0));
        }
        return out.append(" (deal ").append(deal).append(')').toString();
    }
}
