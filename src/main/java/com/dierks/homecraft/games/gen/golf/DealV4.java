package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Golf v4's deal (GOLF-V4-SPEC §3.3, §3.7): each hole's length class, then each hole's recipe, so a
 * course has a real mix of lengths and the v4 quota of features. Pure: nothing but the seed's
 * stream and the mix goes in, so a stored layout is re-derived to the same deal.
 *
 * <p><b>The length deal.</b> The config's mix keeps its meaning (each hole's difficulty); each tier
 * deals its holes' classes from its own fork, {@code length:<tier>}, in a seeded order: Easy deals
 * S, S, M (and again), Medium M, M, L, L, Hard L, X or, one time in three, X, X. Golf of the Week's
 * EEEMMMMHH is therefore S2 M3 L3 X1 (par about 30) or S2 M3 L2 X2 (about 31), and any admin mix of
 * 1-9 letters works the same way. Tiny Golf (the child's course, {@code tiny}) deals only S and M:
 * Easy S, M and S or M (S, S, M or S, M, M), Medium and Hard M.
 *
 * <p><b>The recipe deal</b> is Adventure Golf's ({@link Quota}): deal k ({@value #DEALS} of them)
 * shuffles every (tier, class) group's recipes ({@link HoleRecipe#list}) with its own fork,
 * {@code order:<tier>:<class>:<k>}; the group's j-th hole takes the j-th recipe. The holes'
 * first-attempt recipes are counted against the targets ({@link #table}, capped at what the groups
 * can hold), and the first deal that meets every target is used, else the one meeting the most; off
 * Tiny Golf the first that also starts every hole with a different recipe (the owner found Golf v4
 * "pretty repetitive"), else the one meeting the most with the fewest twice.
 *
 * <p><b>The v4 quota</b> for 7 holes or more: water 2, sand 1, height 2, trees 1, big drop 1, plus a
 * Swing layup, a Chip layup (red-team F00: every club has a job), on about half the courses a guarded
 * par 3, a hole of three legs and three of two. The layups and the guarded par 3 are pinned to holes
 * before the shuffles ({@link #pins}), so a course has each of them once. Tiny Golf: sand 1, height
 * 1, and a tree hole or a pond to look at (as Adventure Golf).
 */
final class DealV4 {

    /**
     * How many recipe deals are tried. Golf v4 counts ten targets, not Adventure Golf's five
     * ({@link Quota#DEALS} is 64): with the layups pinned, 256 deals left about one course in thirty
     * short of its sand and 1,024 one in five hundred; 4,096 met all ten on 20,000 seeds of 20,000.
     * Dealing is only shuffling (a course stops at its first deal that meets them all): those 20,000
     * courses were dealt in 8 seconds.
     */
    static final int DEALS = 4096;
    /** The features that have targets, in the order the summary says them. */
    static final List<Quota.Feature> COUNTED = List.of(Quota.Feature.WATER, Quota.Feature.SAND,
            Quota.Feature.HEIGHT, Quota.Feature.TREES, Quota.Feature.BIG_DROP, Quota.Feature.LAYUP,
            Quota.Feature.CHIP_LAYUP, Quota.Feature.GUARDED, Quota.Feature.THREE_LEGS, Quota.Feature.TWO_LEGS);
    /** A course of this many holes or more has the full quota ({@link #table}). */
    static final int TABLE_FULL = 7;
    /** The features every course of 7 holes or more is sure of, a hole pinned to each ({@link #pins}). */
    static final List<Quota.Feature> PINNED = List.of(Quota.Feature.GUARDED, Quota.Feature.LAYUP,
            Quota.Feature.CHIP_LAYUP);
    /**
     * One course in this many has a guarded par 3 (GOLF-R3 skeptics: three corner-pond holes every week
     * made the same water corners every week); the Swing and Chip layups, which give those clubs their
     * jobs, are on every course.
     */
    static final int GUARDED_IN = 2;
    /** One time in this many, Hard deals two X holes rather than an L and an X. */
    static final int TWO_X_IN = 3;

    private DealV4() {
    }

    /**
     * The deal a course is planned with.
     *
     * @param k       which recipe deal (0 to {@value #DEALS} - 1)
     * @param classes each hole's length class
     * @param order   each (tier, class) group's recipes, in the order its holes take them
     * @param counts  what the holes' first-attempt recipes have
     * @param met     how many targets they meet
     */
    record Deal(int k, List<LengthClass> classes, Map<Integer, HoleRecipe> pinned,
                Map<String, List<HoleRecipe>> order, Map<Quota.Feature, Integer> counts, int met, boolean dry,
                Map<Quota.Feature, Integer> targets) {

        /**
         * Hole {@code i}'s recipe: its pinned one ({@link #pins}, on every attempt), else the j-th of
         * its group's list for the group's j-th hole that isn't pinned. With {@code next} (a hole's last
         * attempts) the one after its group's own recipes, so no two holes of a group share one while
         * the list is long enough (on Tiny Golf, as it always was, the one after its own).
         */
        HoleRecipe recipe(String mix, int i, boolean next) {
            HoleRecipe pin = pinned.get(i);
            if (pin != null) {
                return pin;
            }
            String g = group(mix.charAt(i), classes.get(i));
            int j = 0;
            int m = 0;
            for (int h = 0; h < mix.length(); h++) {
                if (!pinned.containsKey(h) && group(mix.charAt(h), classes.get(h)).equals(g)) {
                    j += h < i ? 1 : 0;
                    m++;
                }
            }
            List<HoleRecipe> list = order.get(g);
            if (dry) {
                return list.get((j + (next ? 1 : 0)) % list.size());
            }
            return list.get((j + (next ? m : 0)) % list.size());
        }
    }

    /**
     * The holes that take a recipe of {@link #PINNED}'s features (red-team F00: a Swing layup and a
     * Chip layup on every course of 7 holes or more, a guarded par 3 on one in {@value #GUARDED_IN}, by
     * the fork {@code pin:GUARDED:week}), from the course's stream
     * {@code root}. For each feature in turn, the most constrained first (the guarded par 3, an M hole;
     * the Chip layup, an L hole; the Swing layup, an L or an X hole), one hole that isn't pinned yet and
     * whose class and tier have such a recipe, picked from fork {@code pin:<feature>}. The Swing layup
     * takes an L hole where another stays free, else an X hole (so both par 4s aren't corner-pond
     * layups and an L S-bend has room, and a course's one par 5 is left to the S-bends, doglegs and
     * straights), and on an L hole it is the three-leg one
     * ({@link HoleRecipe#L_LAYUP_BEND}) where no other hole could have three legs, else either, from
     * the same fork. Dealt before the shuffles, so the quota's deal is left only Adventure Golf's own
     * targets and the legs to meet, and no other hole of the course is dealt a pinned feature's recipe.
     */
    static Map<Integer, HoleRecipe> pins(GenRandom root, String mix, List<LengthClass> classes, boolean dry) {
        Map<Integer, HoleRecipe> out = new HashMap<>();
        Map<Quota.Feature, Integer> table = table(mix.length());
        for (Quota.Feature f : PIN_ORDER) {
            if (table.get(f) == 0
                    || f == Quota.Feature.GUARDED && root.fork("pin:GUARDED:week").nextInt(GUARDED_IN) != 0) {
                continue;
            }
            List<Integer> can = new ArrayList<>();
            int freeL = 0;
            boolean x = false;
            for (int i = 0; i < mix.length(); i++) {
                if (out.containsKey(i)) {
                    continue;
                }
                freeL += classes.get(i) == LengthClass.L ? 1 : 0;
                if (!with(mix.charAt(i), classes.get(i), dry, f).isEmpty()) {
                    can.add(i);
                    x |= classes.get(i) == LengthClass.X;
                }
            }
            if (f == Quota.Feature.LAYUP && x && freeL < 2) {
                can.removeIf(i -> classes.get(i) == LengthClass.L);
            } else if (f == Quota.Feature.LAYUP && freeL >= 2) {
                can.removeIf(i -> classes.get(i) == LengthClass.X); // the par 5 left to the S-bends and straights
            }
            if (can.isEmpty()) {
                continue;
            }
            GenRandom r = root.fork("pin:" + f.name());
            int hole = can.get(r.nextInt(can.size()));
            List<HoleRecipe> with = with(mix.charAt(hole), classes.get(hole), dry, f);
            HoleRecipe pick = with.get(0);
            if (with.size() > 1) {
                out.put(hole, pick);
                boolean legs = false;
                for (int i = 0; i < mix.length() && !legs; i++) {
                    char tier = mix.charAt(i);
                    legs = !out.containsKey(i) && free(tier, classes.get(i), dry).stream()
                            .anyMatch(h -> h.features(tier, dry).contains(Quota.Feature.THREE_LEGS));
                }
                char tier = mix.charAt(hole);
                List<HoleRecipe> bent = with.stream().filter(h -> h.features(tier, dry)
                        .contains(Quota.Feature.THREE_LEGS)).toList();
                pick = !legs && !bent.isEmpty() ? bent.get(0) : with.get(r.nextInt(with.size()));
            }
            out.put(hole, pick);
        }
        return Map.copyOf(out);
    }

    /** The pinned features in the order {@link #pins} deals them: the most constrained first. */
    static final List<Quota.Feature> PIN_ORDER = List.of(Quota.Feature.GUARDED, Quota.Feature.CHIP_LAYUP,
            Quota.Feature.LAYUP);

    /** A (tier, class) group's recipes that have {@code f}, in their fixed order. */
    private static List<HoleRecipe> with(char tier, LengthClass cls, boolean dry, Quota.Feature f) {
        return HoleRecipe.list(tier, cls, dry).stream().filter(h -> h.features(tier, dry).contains(f)).toList();
    }

    /**
     * A (tier, class) group's recipes for a hole that isn't pinned on a course with pins: none of
     * {@link #PINNED}'s features, so the course has each once (all of them if none is without).
     */
    static List<HoleRecipe> free(char tier, LengthClass cls, boolean dry) {
        List<HoleRecipe> all = HoleRecipe.list(tier, cls, dry);
        List<HoleRecipe> out = new ArrayList<>();
        for (HoleRecipe h : all) {
            if (PINNED.stream().noneMatch(h.features(tier, dry)::contains)) {
                out.add(h);
            }
        }
        return out.isEmpty() ? all : out;
    }

    /** A (tier, class) group's key: "ML". */
    static String group(char tier, LengthClass cls) {
        return "" + Character.toUpperCase(tier) + cls.letter();
    }

    /**
     * Each hole's length class (the length deal), from the course's stream {@code root}: per tier,
     * its pattern cycled over its holes and shuffled with the fork {@code length:<tier>}.
     */
    static List<LengthClass> classes(GenRandom root, String mix, boolean tiny) {
        LengthClass[] out = new LengthClass[mix.length()];
        for (char tier : new char[]{'E', 'M', 'H'}) {
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i < mix.length(); i++) {
                if (Character.toUpperCase(mix.charAt(i)) == tier) {
                    at.add(i);
                }
            }
            if (at.isEmpty()) {
                continue;
            }
            GenRandom r = root.fork("length:" + tier);
            List<LengthClass> pattern = pattern(tier, tiny, r);
            List<LengthClass> bag = new ArrayList<>();
            for (int j = 0; j < at.size(); j++) {
                bag.add(pattern.get(j % pattern.size()));
            }
            for (int j = bag.size() - 1; j > 0; j--) {
                int s = r.nextInt(j + 1);
                LengthClass tmp = bag.get(j);
                bag.set(j, bag.get(s));
                bag.set(s, tmp);
            }
            for (int j = 0; j < at.size(); j++) {
                out[at.get(j)] = bag.get(j);
            }
        }
        return List.of(out);
    }

    /** A tier's pattern of classes (cycled over its holes). */
    private static List<LengthClass> pattern(char tier, boolean tiny, GenRandom r) {
        if (tiny) {
            return tier == 'E' ? List.of(LengthClass.S, LengthClass.M, r.nextBoolean() ? LengthClass.S : LengthClass.M)
                    : List.of(LengthClass.M);
        }
        return switch (tier) {
            case 'E' -> List.of(LengthClass.S, LengthClass.S, LengthClass.M);
            case 'M' -> List.of(LengthClass.M, LengthClass.M, LengthClass.L, LengthClass.L);
            default -> r.nextInt(TWO_X_IN) == 0 ? List.of(LengthClass.X, LengthClass.X)
                    : List.of(LengthClass.L, LengthClass.X);
        };
    }

    /**
     * The targets for a course of {@code holes} holes, before they are capped to what its groups can
     * hold: 7 or more {water 2, sand 1, height 2, trees 1, big drop 1, layup 1, chip layup 1, guarded
     * par 3 1, three legs 1, two legs 3}; 4-6 {1, 1, 2, 1, 0, 0, 0, 0, 0, 1}; 3 {0, 1, 1, 1, 0, ...},
     * the tree hole a tree hole or a pond to look at; 2 or fewer, none. (Adventure Golf's sand 2 and
     * height 3 left the six holes the pins leave over to the few recipes with two or three features
     * each: the same par 5s every week, GOLF-R3 skeptics.) A pinned feature's target holds only when
     * the deal pins it ({@link #deal}).
     */
    static Map<Quota.Feature, Integer> table(int holes) {
        int[] t;
        if (holes >= TABLE_FULL) {
            t = new int[]{2, 1, 2, 1, 1, 1, 1, 1, 1, 3};
        } else if (holes >= 4) {
            t = new int[]{1, 1, 2, 1, 0, 0, 0, 0, 0, 1};
        } else if (holes == 3) {
            t = new int[]{0, 1, 1, 1, 0, 0, 0, 0, 0, 0};
        } else {
            t = new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        }
        Map<Quota.Feature, Integer> out = new EnumMap<>(Quota.Feature.class);
        for (int i = 0; i < COUNTED.size(); i++) {
            out.put(COUNTED.get(i), t[i]);
        }
        return out;
    }

    /**
     * The targets for {@code mix} with {@code classes}: {@link #table} capped at what its groups hold,
     * each group the fewer of its holes and its recipes with the feature.
     */
    static Map<Quota.Feature, Integer> targets(String mix, List<LengthClass> classes, boolean dry) {
        Map<Quota.Feature, Integer> out = table(mix.length());
        Map<String, Integer> holes = new LinkedHashMap<>();
        for (int i = 0; i < mix.length(); i++) {
            holes.merge(group(mix.charAt(i), classes.get(i)), 1, Integer::sum);
        }
        for (Quota.Feature f : COUNTED) {
            int room = 0;
            for (Map.Entry<String, Integer> e : holes.entrySet()) {
                char tier = e.getKey().charAt(0);
                LengthClass cls = LengthClass.valueOf(e.getKey().substring(1));
                int with = 0;
                for (HoleRecipe h : HoleRecipe.list(tier, cls, dry)) {
                    if (counts(f, h.features(tier, dry), mix.length())) {
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
    static boolean counts(Quota.Feature f, Set<Quota.Feature> has, int holes) {
        return has.contains(f) || f == Quota.Feature.TREES && holes <= 3 && has.contains(Quota.Feature.VIEW);
    }

    /** The deal for a course of {@code mix} from its seed's stream {@code root}. */
    static Deal deal(GenRandom root, String mix, boolean dry) {
        List<LengthClass> classes = classes(root, mix, dry);
        Map<Integer, HoleRecipe> pinned = pins(root, mix, classes, dry);
        Map<Quota.Feature, Integer> targets = targets(mix, classes, dry);
        for (Quota.Feature f : PINNED) {
            if (pinned.entrySet().stream().noneMatch(e -> e.getValue().features(mix.charAt(e.getKey()), dry)
                    .contains(f))) {
                targets.put(f, 0); // not pinned this week (a guarded par 3 on about half the courses): no target
            }
        }
        targets = Collections.unmodifiableMap(new EnumMap<>(targets));
        Deal best = null;
        int bestTwice = 0;
        for (int k = 0; k < DEALS; k++) {
            Map<String, List<HoleRecipe>> order = new HashMap<>();
            for (int i = 0; i < mix.length(); i++) {
                char tier = Character.toUpperCase(mix.charAt(i));
                LengthClass cls = classes.get(i);
                String g = group(tier, cls);
                if (order.containsKey(g)) {
                    continue;
                }
                List<HoleRecipe> list = pinned.isEmpty() ? HoleRecipe.list(tier, cls, dry) : free(tier, cls, dry);
                if (list.isEmpty()) {
                    throw new IllegalStateException("no recipe for a " + cls + " hole of tier " + tier);
                }
                GenRandom r = root.fork("order:" + tier + ":" + cls.letter() + ":" + k);
                for (int j = list.size() - 1; j > 0; j--) {
                    int s = r.nextInt(j + 1);
                    HoleRecipe tmp = list.get(j);
                    list.set(j, list.get(s));
                    list.set(s, tmp);
                }
                order.put(g, List.copyOf(list));
            }
            Deal d = new Deal(k, classes, pinned, Map.copyOf(order), Map.of(), 0, dry, targets);
            List<Set<Quota.Feature>> first = new ArrayList<>();
            for (int i = 0; i < mix.length(); i++) {
                first.add(d.recipe(mix, i, false).features(mix.charAt(i), dry));
            }
            Map<Quota.Feature, Integer> counts = count(first, mix.length());
            int met = met(counts, targets);
            int twice = dry ? 0 : twice(d, mix);
            if (best == null || met > best.met() || met == best.met() && twice < bestTwice) {
                best = new Deal(k, classes, pinned, Map.copyOf(order), counts, met, dry, targets);
                bestTwice = twice;
            }
            if (met == COUNTED.size() && twice == 0) {
                break;
            }
        }
        return best;
    }

    /** How many of a deal's holes start with a recipe an earlier hole of the course starts with. */
    private static int twice(Deal d, String mix) {
        Set<HoleRecipe> seen = new HashSet<>();
        int twice = 0;
        for (int i = 0; i < mix.length(); i++) {
            twice += seen.add(d.recipe(mix, i, false)) ? 0 : 1;
        }
        return twice;
    }

    /** What holes (each a feature set) have, counted per feature. */
    static Map<Quota.Feature, Integer> count(List<Set<Quota.Feature>> holes, int size) {
        Map<Quota.Feature, Integer> out = new EnumMap<>(Quota.Feature.class);
        for (Quota.Feature f : COUNTED) {
            out.put(f, 0);
        }
        for (Set<Quota.Feature> has : holes) {
            for (Quota.Feature f : COUNTED) {
                if (counts(f, has, size)) {
                    out.merge(f, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** What drawn holes have, counted per feature. */
    static Map<Quota.Feature, Integer> countLayouts(List<HoleLayout> holes) {
        List<Set<Quota.Feature>> has = new ArrayList<>();
        for (HoleLayout l : holes) {
            has.add(l.features());
        }
        return count(has, holes.size());
    }

    /** How many targets {@code counts} meets. */
    static int met(Map<Quota.Feature, Integer> counts, Map<Quota.Feature, Integer> targets) {
        int met = 0;
        for (Quota.Feature f : COUNTED) {
            if (counts.getOrDefault(f, 0) >= targets.getOrDefault(f, 0)) {
                met++;
            }
        }
        return met;
    }

    /** The summary line: "quota: water 2/2, sand 3/2, ... (deal 3)". */
    static String line(Map<Quota.Feature, Integer> has, Map<Quota.Feature, Integer> targets, int deal) {
        StringBuilder out = new StringBuilder("quota:");
        for (int i = 0; i < COUNTED.size(); i++) {
            Quota.Feature f = COUNTED.get(i);
            out.append(i == 0 ? " " : ", ").append(f.words()).append(' ').append(has.getOrDefault(f, 0)).append('/')
                    .append(targets.getOrDefault(f, 0));
        }
        return out.append(" (deal ").append(deal).append(')').toString();
    }
}
