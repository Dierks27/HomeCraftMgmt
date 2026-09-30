package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenRandom;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weekly variety quota (Course Variety §3.3): its table of targets by the mix's size, capped at
 * what the mix's tiers can hold; a deal that meets every target when one does (the first), the one
 * meeting the most otherwise (the lowest on a tie), the same for the same seed; Tiny Golf's lists
 * never deal water in play; and the summary line.
 */
class QuotaTest {

    private static Map<Quota.Feature, Integer> of(int water, int sand, int height, int trees, int drop) {
        Map<Quota.Feature, Integer> out = new EnumMap<>(Quota.Feature.class);
        out.put(Quota.Feature.WATER, water);
        out.put(Quota.Feature.SAND, sand);
        out.put(Quota.Feature.HEIGHT, height);
        out.put(Quota.Feature.TREES, trees);
        out.put(Quota.Feature.BIG_DROP, drop);
        return out;
    }

    @Test
    void theTableIsTheSpecs() {
        assertEquals(of(2, 2, 3, 1, 1), Quota.table(9), "Golf of the Week (7 or more holes)");
        assertEquals(of(2, 2, 3, 1, 1), Quota.table(7), "7 holes");
        assertEquals(of(1, 1, 2, 1, 0), Quota.table(6), "4-6 holes");
        assertEquals(of(1, 1, 2, 1, 0), Quota.table(4), "4 holes");
        assertEquals(of(0, 1, 1, 1, 0), Quota.table(3), "Tiny Golf: no water, a sand, a height, a tree or a view");
        assertEquals(of(0, 0, 0, 0, 0), Quota.table(2), "2 or fewer: none");
        assertEquals(of(2, 2, 3, 1, 1), Quota.targets("EEEMMMMHH", false), "the shipped mix can hold all of it");
        assertEquals(of(0, 1, 1, 1, 0), Quota.targets("EEE", true), "and Tiny Golf's");
    }

    @Test
    void aTargetCountsOnlyWhatTheMixsTiersCanHold() {
        assertEquals(of(0, 1, 2, 1, 0), Quota.targets("EEEEEEEEE", false),
                "nine Easy holes: no water in play and no big drop (Easy has neither), and one sand trap to deal");
        assertEquals(of(1, 1, 2, 1, 0), Quota.targets("EEEMEE", false),
                "one Medium hole is room for one water hole");
        assertEquals(of(0, 1, 2, 1, 0), Quota.targets("MMMMMM", true),
                "a dry course's Medium list has no water in play");
    }

    @Test
    void tinyGolfsListsNeverPutWaterInPlay() {
        for (char tier : "EMH".toCharArray()) {
            for (HoleTemplate t : Quota.list(tier, true)) {
                assertFalse(t.features(tier).contains(Quota.Feature.WATER), t + " " + tier + " is dry");
            }
            assertEquals(HoleTemplate.forTier(tier), Quota.list(tier, false), tier + ": a wet course deals the whole tier");
        }
        assertEquals(HoleTemplate.forTier('E'), Quota.list('E', true), "Easy is dry anyway: all seven");
        assertEquals(List.of(HoleTemplate.RAMP, HoleTemplate.DOGLEG, HoleTemplate.ICE_RUN, HoleTemplate.ISLAND,
                        HoleTemplate.SAND_TRAP, HoleTemplate.HILL, HoleTemplate.TERRACES, HoleTemplate.DOGLEG_DOWN,
                        HoleTemplate.TREE_GARDEN), Quota.list('M', true),
                "Medium without its pond side, two-way and creek");
        assertEquals(List.of(HoleTemplate.ISLAND, HoleTemplate.S_BEND, HoleTemplate.NARROW_ICE, HoleTemplate.VOLCANO,
                        HoleTemplate.DOGLEG_DOWN, HoleTemplate.TREE_GARDEN), Quota.list('H', true),
                "Hard without its terraces (a pond on Hard), island pond and two-way");
    }

    @Test
    void theFirstDealThatMeetsTheQuotaIsUsedAndTheSameSeedDealsTheSame() {
        int met = 0;
        int seeds = 400;
        for (long seed = 0; seed < seeds; seed++) {
            GenRandom root = new GenRandom(seed);
            Quota.Deal d = Quota.deal(root, "EEEMMMMHH", false);
            assertEquals(d, Quota.deal(new GenRandom(seed), "EEEMMMMHH", false), "seed " + seed + ": the same deal");
            if (d.met() == Quota.COUNTED.size()) {
                met++;
                for (int k = 0; k < d.k(); k++) {
                    assertTrue(firstAttempts(root, "EEEMMMMHH", k) < Quota.COUNTED.size(),
                            "seed " + seed + ": no earlier deal (" + k + ") met it");
                }
            }
            for (char tier : "EMH".toCharArray()) {
                assertEquals(Set.copyOf(HoleTemplate.forTier(tier)), Set.copyOf(d.order().get(tier)),
                        "seed " + seed + ": each tier deals every one of its templates");
            }
        }
        assertTrue(met * 100 >= seeds * 99, "Golf of the Week's quota is met by the deal " + met + " times in "
                + seeds);
        int tiny = 0;
        for (long seed = 0; seed < seeds; seed++) {
            tiny += Quota.deal(new GenRandom(seed), "EEE", true).met() == Quota.COUNTED.size() ? 1 : 0;
        }
        assertTrue(tiny * 100 >= seeds * 99, "and Tiny Golf's " + tiny + " times in " + seeds);
    }

    /** How many targets deal {@code k} of {@code root} meets by its first-attempt templates. */
    private static int firstAttempts(GenRandom root, String mix, int k) {
        Map<Character, List<HoleTemplate>> order = new java.util.HashMap<>();
        for (char tier : "EMH".toCharArray()) {
            List<HoleTemplate> list = new java.util.ArrayList<>(Quota.list(tier, false));
            GenRandom r = root.fork("order:" + tier + ":" + k);
            for (int i = list.size() - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                HoleTemplate tmp = list.get(i);
                list.set(i, list.get(j));
                list.set(j, tmp);
            }
            order.put(tier, list);
        }
        List<Map.Entry<HoleTemplate, Character>> first = new java.util.ArrayList<>();
        for (int i = 0; i < mix.length(); i++) {
            first.add(Map.entry(Quota.template(order, mix, i, false), mix.charAt(i)));
        }
        return Quota.met(Quota.count(first, mix.length()), Quota.targets(mix, false));
    }

    @Test
    void whenNoDealMeetsItTheOneMeetingMostIsUsedTheLowestOnATie() {
        assertEquals(0, Quota.deal(new GenRandom(5), "EE", false).k(), "two holes: no targets, so the first deal");
        // three Easy holes and a Medium one must deal a sand trap, a ramp and a hump to the Easy holes and a
        // water hole with trees (the two-way) to the Medium one: most seeds find no such deal in 64
        int short_ = 0;
        for (long seed = 0; seed < 60; seed++) {
            GenRandom root = new GenRandom(seed);
            Quota.Deal d = Quota.deal(root, "EEEM", false);
            int best = -1;
            int first = -1;
            for (int k = 0; k < Quota.DEALS; k++) {
                int met = firstAttempts(root, "EEEM", k);
                if (met > best) {
                    best = met;
                    first = k;
                }
            }
            assertEquals(best, d.met(), "seed " + seed + ": the deal meeting the most targets");
            assertEquals(first, d.k(), "seed " + seed + ": the lowest such deal");
            short_ += best < Quota.COUNTED.size() ? 1 : 0;
        }
        assertTrue(short_ > 0, "some seeds had no deal meeting every target: " + short_);
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(Quota.COUNTED.size(), Quota.deal(new GenRandom(seed), "HHHHHHHHH", false).met(),
                    "nine Hard holes deal all nine Hard templates, which have everything the quota asks");
        }
    }

    @Test
    void holesAreDealtInTurnAndTheSecondTemplateIsTheNextOne() {
        Map<Character, List<HoleTemplate>> order = Map.of('E', List.of(HoleTemplate.STRAIGHT, HoleTemplate.HUMP),
                'M', List.of(HoleTemplate.HILL), 'H', List.of(HoleTemplate.VOLCANO, HoleTemplate.TERRACES));
        assertEquals(HoleTemplate.STRAIGHT, Quota.template(order, "EME", 0, false), "the first Easy hole: the first");
        assertEquals(HoleTemplate.HUMP, Quota.template(order, "EME", 2, false), "the second Easy hole: the second");
        assertEquals(HoleTemplate.STRAIGHT, Quota.template(order, "EME", 2, true), "its second template wraps round");
        assertEquals(HoleTemplate.HILL, Quota.template(order, "EME", 1, true), "a one-template list repeats it");
    }

    @Test
    void theSummaryLineSaysWhatTheCourseHasAgainstItsTargets() {
        assertEquals("quota: water 2/2, sand 3/2, height 4/3, trees 1/1, big drop 1/1 (deal 3)",
                Quota.line(of(2, 3, 4, 1, 1), of(2, 2, 3, 1, 1), 3), "as the spec's example");
        assertEquals(5, Quota.met(of(2, 3, 4, 1, 1), of(2, 2, 3, 1, 1)), "all met");
        assertEquals(4, Quota.met(of(1, 3, 4, 1, 1), of(2, 2, 3, 1, 1)), "one water short");
    }
}
