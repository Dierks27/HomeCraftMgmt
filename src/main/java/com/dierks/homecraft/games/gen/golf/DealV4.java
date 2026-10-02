package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenRandom;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
 * can hold), and the first deal that meets every target is used, else the one meeting the most.
 *
 * <p><b>The v4 quota</b> for 7 holes or more: water 2, sand 2, height 3, trees 1, big drop 1 (as
 * Adventure Golf), plus a Swing layup, a Chip layup, a guarded par 3 (red-team F00: every club has a
 * job), a hole of three legs and three of two. The layups and the guarded par 3 are pinned to holes
 * before the shuffles ({@link #pins}), so every course has them. Tiny Golf: sand 1, height 1, and a
 * tree hole or a pond to look at (as Adventure Golf).
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
                Map<String, List<HoleRecipe>> order, Map<Quota.Feature, Integer> counts, int met) {

        /**
         * Hole {@code i}'s recipe: its pinned one ({@link #pins}, on every attempt), else the j-th of
         * its group's list for the group's j-th hole that isn't pinned, or with {@code next} the one
         * after it (a hole's last attempts).
         */
        HoleRecipe recipe(String mix, int i, boolean next) {
            HoleRecipe pin = pinned.get(i);
            if (pin != null) {
                return pin;
            }
            String g = group(mix.charAt(i), classes.get(i));
            int j = 0;
            for (int h = 0; h < i; h++) {
                if (!pinned.containsKey(h) && group(mix.charAt(h), classes.get(h)).equals(g)) {
                    j++;
                }
            }
            List<HoleRecipe> list = order.get(g);
            return list.get((j + (next ? 1 : 0)) % list.size());
        }
    }

    /**
     * The holes that take a recipe of {@link #PINNED}'s features (red-team F00: a Swing layup, a Chip
     * layup and a guarded par 3 on every course of 7 holes or more), from the course's stream
     * {@code root}: for each feature in turn whose target is more than 0, one hole of the shortest class
     * and the tiers that have such a recipe and isn't pinned yet (a Swing layup goes to an L hole where
     * the course has one free: an X layup measures a par 6 more often), picked from fork
     * {@code pin:<feature>}; it takes the first such recipe of its group's list. Dealt before the
     * shuffles, so the quota's deal is left only Adventure Golf's own targets and the legs to meet.
     */
    static Map<Integer, HoleRecipe> pins(GenRandom root, String mix, List<LengthClass> classes, boolean dry) {
        Map<Integer, HoleRecipe> out = new HashMap<>();
        Map<Quota.Feature, Integer> table = table(mix.length());
        for (Quota.Feature f : PINNED) {
            if (table.get(f) == 0) {
                continue;
            }
            List<Integer> can = new ArrayList<>();
            List<HoleRecipe> with = new ArrayList<>();
            for (int i = 0; i < mix.length(); i++) {
                if (out.containsKey(i)) {
                    continue;
                }
                for (HoleRecipe h : HoleRecipe.list(mix.charAt(i), classes.get(i), dry)) {
                    if (h.features(mix.charAt(i), dry).contains(f)) {
                        if (!with.isEmpty() && h.cls.compareTo(with.get(0).cls) < 0) {
                            can.clear(); // the shortest class with one: a Swing layup is an L hole where it can be
                            with.clear();
                        }
                        if (with.isEmpty() || h.cls == with.get(0).cls) {
                            can.add(i);
                            with.add(h);
                        }
                        break;
                    }
                }
            }
            if (!can.isEmpty()) {
                int pick = root.fork("pin:" + f.name()).nextInt(can.size());
                out.put(can.get(pick), with.get(pick));
            }
        }
        return Map.copyOf(out);
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
     * hold: 7 or more {water 2, sand 2, height 3, trees 1, big drop 1, layup 1, chip layup 1, guarded
     * par 3 1, three legs 1, two legs 3}; 4-6 {1, 1, 2, 1, 0, 0, 0, 0, 0, 1}; 3 {0, 1, 1, 1, 0, ...},
     * the tree hole a tree hole or a pond to look at; 2 or fewer, none.
     */
    static Map<Quota.Feature, Integer> table(int holes) {
        int[] t;
        if (holes >= TABLE_FULL) {
            t = new int[]{2, 2, 3, 1, 1, 1, 1, 1, 1, 3};
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
        Map<Quota.Feature, Integer> targets = targets(mix, classes, dry);
        Map<Integer, HoleRecipe> pinned = pins(root, mix, classes, dry);
        Deal best = null;
        for (int k = 0; k < DEALS; k++) {
            Map<String, List<HoleRecipe>> order = new HashMap<>();
            for (int i = 0; i < mix.length(); i++) {
                char tier = Character.toUpperCase(mix.charAt(i));
                LengthClass cls = classes.get(i);
                String g = group(tier, cls);
                if (order.containsKey(g)) {
                    continue;
                }
                List<HoleRecipe> list = HoleRecipe.list(tier, cls, dry);
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
            Deal d = new Deal(k, classes, pinned, Map.copyOf(order), Map.of(), 0);
            List<Set<Quota.Feature>> first = new ArrayList<>();
            for (int i = 0; i < mix.length(); i++) {
                first.add(d.recipe(mix, i, false).features(mix.charAt(i), dry));
            }
            Map<Quota.Feature, Integer> counts = count(first, mix.length());
            int met = met(counts, targets);
            if (best == null || met > best.met()) {
                best = new Deal(k, classes, pinned, Map.copyOf(order), counts, met);
            }
            if (met == COUNTED.size()) {
                break;
            }
        }
        return best;
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
