package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Height;
import com.dierks.homecraft.arcade.MegaShow.Look;
import com.dierks.homecraft.arcade.MegaShow.Shot;
import com.dierks.homecraft.arcade.MegaShow.Style;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mega Firework Show's minute (0.38.1; the owner: "a full blown show with 3 firing locations", then "maybe we do
 * less at a time and spread out the show to like a minute long"): 81 rockets, about 1.35 a second, never more than
 * three in a tick before the finale; an opening walk, chases, pairs, volleys, a crescendo and a triple finale ending in
 * gold; a row of 45 to 90 seconds stretches the body and keeps the finale; one or two launchers fire the same show;
 * each launcher has its own colours; and the plan is the same every time.
 */
class MegaShowPlanTest {

    private static final int L = 0;
    private static final int M = 1;
    private static final int R = 2;

    /** Rockets at each tick of a plan, in tick order. */
    private static Map<Integer, Integer> perTick(List<Shot> shots) {
        Map<Integer, Integer> out = new TreeMap<>();
        for (Shot s : shots) {
            out.merge(s.tick(), 1, Integer::sum);
        }
        return out;
    }

    /** The shots of one style, in order. */
    private static List<Shot> of(List<Shot> shots, Style style) {
        return shots.stream().filter(s -> s.style() == style).toList();
    }

    /** Each tick of {@code shots} with the lanes that fire on it, in order. */
    private static Map<Integer, List<Integer>> lanesByTick(List<Shot> shots) {
        Map<Integer, List<Integer>> out = new TreeMap<>();
        for (Shot s : shots) {
            out.computeIfAbsent(s.tick(), t -> new ArrayList<>()).add(s.lane());
        }
        return out;
    }

    /** The first tick of the finale for a show of {@code seconds}. */
    private static int finaleStart(int seconds) {
        return seconds * 20 - MegaShow.FINALE_TICKS;
    }

    @Test
    void aMinuteLongShowFires81RocketsAbout1Point35ASecond() {
        List<Shot> show = MegaShow.plan(60);
        assertEquals(81, show.size(), "81 rockets (the plan aimed at 60-100)");
        assertEquals(1.35, show.size() / 60.0, 1e-9, "about 1.35 a second: less at a time, spread out");
        assertEquals(1196, MegaShow.endTick(show), "the last one bursts at 59.8 s: a minute, and then it's over");
        assertEquals(60, MegaShow.DEFAULT_SECONDS);
        for (int lane = L; lane <= R; lane++) {
            int l = lane;
            assertTrue(show.stream().filter(s -> s.lane() == l).count() >= 25, "every lane gets its share: " + lane);
        }
        for (int i = 1; i < show.size(); i++) {
            assertTrue(show.get(i - 1).tick() <= show.get(i).tick(), "in tick order");
        }
    }

    @Test
    void neverMoreThanThreeInOneTickBeforeTheFinaleAndSixInIt() {
        List<Shot> show = MegaShow.plan(60);
        int finale = finaleStart(60);
        int bodyPeak = 0;
        int finalePeak = 0;
        for (Map.Entry<Integer, Integer> e : perTick(show).entrySet()) {
            if (e.getKey() < finale) {
                bodyPeak = Math.max(bodyPeak, e.getValue());
            } else {
                finalePeak = Math.max(finalePeak, e.getValue());
            }
        }
        assertEquals(3, bodyPeak, "at most three at once before the finale (the volleys)");
        assertEquals(6, finalePeak, "six in the finale's low-and-high volley");
        for (Shot s : show) {
            assertEquals(s.tick() >= finale, s.style() == Style.FINALE || s.style() == Style.GOLD,
                    "only the finale is in the last five seconds: " + s);
        }
    }

    @Test
    void itOpensWithSingleShotsWalkingLeftToRight() {
        List<Shot> sparks = of(MegaShow.plan(60), Style.SPARK);
        assertEquals(7, sparks.size());
        assertEquals(Map.of(0, List.of(L), 20, List.of(M), 40, List.of(R), 80, List.of(L), 100, List.of(M),
                120, List.of(R), 140, List.of(M)), lanesByTick(sparks), "left, middle, right, twice, then the middle");
        for (int i = 0; i < 6; i++) {
            assertEquals(Height.LOW, sparks.get(i).height(), "low to begin with");
        }
        assertEquals(Height.HIGH, sparks.get(6).height(), "and one high in the middle to end the walk");
    }

    @Test
    void theChasesGoLeftToRightAndBack() {
        List<Shot> chases = of(MegaShow.plan(60), Style.CHASE);
        assertEquals(12, chases.size(), "four chases of three");
        List<List<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 12; i += 3) {
            runs.add(List.of(chases.get(i).lane(), chases.get(i + 1).lane(), chases.get(i + 2).lane()));
            assertEquals(List.of(Height.LOW, Height.LOW, Height.MID), List.of(chases.get(i).height(),
                    chases.get(i + 1).height(), chases.get(i + 2).height()), "each ends a little higher");
            assertEquals(20, chases.get(i + 1).tick() - chases.get(i).tick(), "a second apart");
            assertEquals(20, chases.get(i + 2).tick() - chases.get(i + 1).tick());
        }
        assertEquals(List.of(List.of(L, M, R), List.of(R, M, L), List.of(L, M, R), List.of(R, M, L)), runs,
                "across and back, twice");
    }

    @Test
    void thenPairsThenAFewAllThreeVolleys() {
        List<Shot> show = MegaShow.plan(60);
        Map<Integer, List<Integer>> pairs = lanesByTick(of(show, Style.PAIR));
        assertEquals(7, pairs.size(), "seven pairs");
        for (List<Integer> pair : pairs.values()) {
            assertEquals(2, pair.size(), "two at once");
            assertEquals(2, new HashSet<>(pair).size(), "from two launchers");
        }
        assertEquals(List.of(L, R), new ArrayList<>(pairs.values()).get(6), "the last pair is the outside two");
        Map<Integer, List<Integer>> volleys = lanesByTick(of(show, Style.VOLLEY));
        assertEquals(4, volleys.size(), "a few volleys");
        for (List<Integer> v : volleys.values()) {
            assertEquals(List.of(L, M, R), v, "all three at once");
        }
        List<Integer> at = new ArrayList<>(volleys.keySet());
        for (int i = 1; i < at.size(); i++) {
            assertEquals(50, at.get(i) - at.get(i - 1), "two and a half seconds apart");
        }
        assertTrue(of(show, Style.PAIR).get(0).tick() > of(show, Style.CHASE).get(11).tick(), "after the chases");
        assertTrue(at.get(0) > of(show, Style.PAIR).get(13).tick(), "and the volleys after the pairs");
    }

    @Test
    void theCrescendosGapsShrink() {
        List<Shot> rise = of(MegaShow.plan(60), Style.RISE);
        assertEquals(18, rise.size());
        List<Integer> ticks = new ArrayList<>(lanesByTick(rise).keySet());
        int last = Integer.MAX_VALUE;
        for (int i = 1; i < ticks.size(); i++) {
            int gap = ticks.get(i) - ticks.get(i - 1);
            assertTrue(gap <= last, "never a longer gap than the one before: " + ticks);
            last = gap;
        }
        assertEquals(30, ticks.get(1) - ticks.get(0), "a second and a half apart to begin with");
        assertEquals(10, last, "half a second apart at the end");
        Height before = Height.LOW;
        for (Shot s : rise) {
            assertTrue(s.height().ordinal() >= before.ordinal(), "and it only climbs: " + s);
            before = s.height();
        }
        assertEquals(List.of(L, M, R), lanesByTick(rise).get(ticks.get(ticks.size() - 1)), "ending with all three");
    }

    @Test
    void theTripleFinaleStepsUpThenTheDoubleThenTheGold() {
        List<Shot> show = MegaShow.plan(60);
        int f = finaleStart(60);
        List<Shot> finale = show.stream().filter(s -> s.tick() >= f).toList();
        assertEquals(18, finale.size());
        Map<Integer, List<Shot>> byTick = finale.stream().collect(Collectors.groupingBy(Shot::tick, TreeMap::new,
                Collectors.toList()));
        assertEquals(List.of(f, f + 10, f + 20, f + 40, f + 60), new ArrayList<>(byTick.keySet()));
        assertEquals(List.of(Height.LOW, Height.MID, Height.HIGH), List.of(byTick.get(f).get(0).height(),
                byTick.get(f + 10).get(0).height(), byTick.get(f + 20).get(0).height()), "the triple steps up");
        for (int t : List.of(f, f + 10, f + 20)) {
            assertEquals(List.of(L, M, R), byTick.get(t).stream().map(Shot::lane).toList(), "all three each time");
        }
        assertEquals(6, byTick.get(f + 40).size(), "then low and high together");
        assertEquals(EnumSet.of(Height.LOW, Height.HIGH),
                byTick.get(f + 40).stream().map(Shot::height).collect(Collectors.toCollection(
                        () -> EnumSet.noneOf(Height.class))));
        List<Shot> gold = byTick.get(f + 60);
        assertEquals(List.of(L, M, R), gold.stream().map(Shot::lane).toList(), "then the gold, from every launcher");
        assertTrue(gold.stream().allMatch(s -> s.style() == Style.GOLD && s.height() == Height.HIGH));
        assertEquals(gold, of(show, Style.GOLD), "the gold is the last volley and only that");
        int breath = f - of(show, Style.RISE).get(17).tick();
        assertTrue(breath >= 30, "a breath before the finale: " + breath + " ticks");
    }

    @Test
    void everyBurstIsInsideTheShowsSeconds() {
        for (int seconds = MegaShow.MIN_SECONDS; seconds <= MegaShow.MAX_SECONDS; seconds++) {
            List<Shot> show = MegaShow.plan(seconds);
            assertEquals(0, show.get(0).tick(), seconds + ": the first goes up at once");
            assertEquals(seconds * 20 - 4, MegaShow.endTick(show), seconds + ": the last burst, 0.2 s before the end");
            for (Shot s : show) {
                assertTrue(s.tick() + s.height().ticks() < seconds * 20, seconds + ": " + s);
            }
        }
    }

    @Test
    void aLongerOrShorterRowStretchesTheBodyAndKeepsTheFinale() {
        List<Shot> minute = MegaShow.plan(60);
        for (int seconds : new int[]{45, 60, 90}) {
            List<Shot> show = MegaShow.plan(seconds);
            assertEquals(81, show.size(), seconds + ": always 81 rockets");
            int f = finaleStart(seconds);
            Map<Integer, Integer> perTick = perTick(show);
            for (Map.Entry<Integer, Integer> e : perTick.entrySet()) {
                if (e.getKey() < f) {
                    assertTrue(e.getValue() <= 3, seconds + ": at most three in a tick before the finale");
                }
            }
            assertEquals(perTick(minute).size(), perTick.size(), seconds + ": every beat is still its own tick");
            for (int i = 0; i < show.size(); i++) {
                Shot a = minute.get(i);
                Shot b = show.get(i);
                assertEquals(List.of(a.lane(), a.style(), a.height()), List.of(b.lane(), b.style(), b.height()),
                        seconds + ": the same rocket, only its tick moves");
                if (a.tick() >= finaleStart(60)) {
                    assertEquals(a.tick() - finaleStart(60), b.tick() - f, seconds + ": the finale is never stretched");
                }
            }
        }
        double perSecond45 = 81 / 45.0;
        double perSecond90 = 81 / 90.0;
        assertEquals(1.8, perSecond45, 1e-9, "45 s: 1.8 a second");
        assertEquals(0.9, perSecond90, 1e-9, "90 s: 0.9 a second");
        assertEquals(MegaShow.plan(45), MegaShow.plan(10), "a length below 45 plays 45");
        assertEquals(MegaShow.plan(90), MegaShow.plan(600), "and above 90 plays 90");
    }

    @Test
    void theLauncherRowsSecondsAre45To90() {
        assertEquals(45, MegaShow.MIN_SECONDS);
        assertEquals(90, MegaShow.MAX_SECONDS);
        assertEquals(45, MegaShow.seconds(3), "the Firework Show's 3 is no Mega length");
        assertEquals(45, MegaShow.seconds(15), "nor the Grand show's 15");
        assertEquals(60, MegaShow.seconds(60));
        assertEquals(90, MegaShow.seconds(1000));
        assertEquals(3, MegaShow.MAX_LAUNCHERS);
        assertEquals(30, FireworkShow.MAX_SECONDS, "every other firework row keeps 0.38.1's 3-30");
    }

    @Test
    void oneOrTwoLaunchersFireTheSameShowFromThose() {
        List<Shot> three = MegaShow.plan(60);
        assertEquals(three, MegaShow.forLaunchers(three, 3), "three launchers: each lane is its launcher");
        assertEquals(three, MegaShow.forLaunchers(three, 7), "never more than three");
        for (Shot s : three) {
            assertEquals(s.lane(), s.launcher());
        }
        for (int n : new int[]{1, 2}) {
            List<Shot> folded = MegaShow.forLaunchers(three, n);
            assertEquals(81, folded.size(), n + ": the same 81 rockets");
            for (int i = 0; i < three.size(); i++) {
                Shot a = three.get(i);
                Shot b = folded.get(i);
                assertEquals(List.of(a.tick(), a.lane(), a.style(), a.nth()), List.of(b.tick(), b.lane(), b.style(),
                        b.nth()), n + ": same tick, lane, style and colours: " + a);
                assertTrue(b.launcher() >= 0 && b.launcher() < n, n + ": only from the launchers there are");
            }
            assertEquals(MegaShow.endTick(three), MegaShow.endTick(folded), n + ": a minute all the same");
        }
        List<Shot> two = MegaShow.forLaunchers(three, 2);
        List<Integer> middle = new ArrayList<>();
        for (Shot s : two) {
            if (s.lane() == L) {
                assertEquals(0, s.launcher(), "the left lane on the first");
            } else if (s.lane() == R) {
                assertEquals(1, s.launcher(), "the right lane on the second");
            } else {
                middle.add(s.launcher());
            }
        }
        for (int i = 0; i < middle.size(); i++) {
            assertEquals(i % 2, middle.get(i), "the middle's shots take turns");
        }
        assertTrue(MegaShow.forLaunchers(three, 1).stream().allMatch(s -> s.launcher() == 0), "one: all from it");
        assertTrue(MegaShow.forLaunchers(three, 0).stream().allMatch(s -> s.launcher() == 0), "never fewer than one");
    }

    @Test
    void twoShotsOnOneLauncherInOneTickBurstAtDifferentHeights() {
        for (int n : new int[]{1, 2, 3}) {
            Map<String, List<Height>> stacked = new TreeMap<>();
            for (Shot s : MegaShow.forLaunchers(MegaShow.plan(60), n)) {
                stacked.computeIfAbsent(s.tick() + "@" + s.launcher(), k -> new ArrayList<>()).add(s.height());
            }
            for (Map.Entry<String, List<Height>> e : stacked.entrySet()) {
                List<Height> h = e.getValue();
                if (h.size() <= Height.values().length) {
                    assertEquals(h.size(), new HashSet<>(h).size(), n + " launchers, " + e.getKey() + ": " + h);
                } else {
                    assertEquals(EnumSet.allOf(Height.class), EnumSet.copyOf(h), n + ": every height, " + h);
                }
            }
        }
        assertEquals(Height.HIGH, MegaShow.free(Height.MID, EnumSet.of(Height.MID)), "the next one up");
        assertEquals(Height.MID, MegaShow.free(Height.HIGH, EnumSet.of(Height.HIGH)), "or down, from the top");
        assertEquals(Height.LOW, MegaShow.free(Height.LOW, EnumSet.of(Height.MID)), "its own when free");
        assertEquals(Height.MID, MegaShow.free(Height.MID, EnumSet.allOf(Height.class)), "all taken: its own");
        List<Shot> one = MegaShow.forLaunchers(MegaShow.plan(60), 1);
        Shot first = one.stream().filter(s -> s.style() == Style.VOLLEY).findFirst().orElseThrow();
        List<Height> volley = one.stream().filter(s -> s.tick() == first.tick()).map(Shot::height).toList();
        assertEquals(List.of(Height.MID, Height.HIGH, Height.LOW), volley, "one launcher's volley: three heights");
    }

    @Test
    void eachLaneHasItsOwnColoursAndTheGoldIsEveryones() {
        List<Set<Color>> lanes = new ArrayList<>();
        for (int lane = L; lane <= R; lane++) {
            lanes.add(new HashSet<>());
        }
        for (Shot s : MegaShow.plan(60)) {
            Look look = MegaShow.look(s);
            assertEquals(2, look.colours().size(), "two colours a rocket: " + s);
            assertEquals(Color.WHITE, look.fade(), "fading to white");
            assertNotEquals(FireworkEffect.Type.CREEPER, look.type(), "never a creeper face");
            if (s.style() == Style.GOLD) {
                assertEquals(List.of(Color.YELLOW, Color.ORANGE), look.colours(), "the gold");
                assertTrue(look.flicker() && look.trail());
                continue;
            }
            assertTrue(MegaShow.LANE_COLOURS.get(s.lane()).containsAll(look.colours()), "its lane's own: " + s);
            assertNotEquals(look.colours().get(0), look.colours().get(1));
            lanes.get(s.lane()).addAll(look.colours());
        }
        for (int a = L; a <= R; a++) {
            assertEquals(new HashSet<>(MegaShow.LANE_COLOURS.get(a)), lanes.get(a), "every colour of lane " + a);
            for (int b = a + 1; b <= R; b++) {
                Set<Color> both = new HashSet<>(lanes.get(a));
                both.retainAll(lanes.get(b));
                assertEquals(Set.of(), both, "lanes " + a + " and " + b + " share no colour");
            }
        }
        for (int n : new int[]{1, 2}) {
            for (Shot s : MegaShow.forLaunchers(MegaShow.plan(60), n)) {
                if (s.style() != Style.GOLD) {
                    assertTrue(MegaShow.LANE_COLOURS.get(s.lane()).containsAll(MegaShow.look(s).colours()),
                            n + " launchers: the show keeps all three palettes");
                }
            }
        }
        Map<Style, FireworkEffect.Type> shapes = new TreeMap<>();
        for (Shot s : MegaShow.plan(60)) {
            shapes.put(s.style(), MegaShow.look(s).type());
        }
        assertEquals(Map.of(Style.SPARK, FireworkEffect.Type.BALL, Style.CHASE, FireworkEffect.Type.BALL,
                Style.PAIR, FireworkEffect.Type.STAR, Style.VOLLEY, FireworkEffect.Type.BALL_LARGE,
                Style.RISE, FireworkEffect.Type.BURST, Style.FINALE, FireworkEffect.Type.BALL_LARGE,
                Style.GOLD, FireworkEffect.Type.BALL_LARGE), shapes, "each part of the show its own shape");
    }

    @Test
    void theSamePlanEveryTime() {
        for (int seconds : new int[]{45, 60, 75, 90}) {
            assertEquals(MegaShow.plan(seconds), MegaShow.plan(seconds), seconds + ": no randomness anywhere");
            for (int n = 1; n <= 3; n++) {
                assertEquals(MegaShow.forLaunchers(MegaShow.plan(seconds), n),
                        MegaShow.forLaunchers(MegaShow.plan(seconds), n), seconds + ", " + n);
            }
        }
        assertFalse(MegaShow.plan(60).isEmpty());
        assertEquals(MegaShow.FINALE_BEAT * MegaShow.BEAT, finaleStart(60), "a minute is the template as it is");
        assertEquals(10, MegaShow.BEAT, "0.38.1's burst spacing");
    }
}
