package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Height;
import com.dierks.homecraft.arcade.MegaShow.Shot;
import com.dierks.homecraft.arcade.MegaShow.Style;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Color;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mega show from 1 to 10 lanes (0.38.2; the owner: "Let's make it so we can chain together up to 5 launchers!",
 * then "Or really as many launchers as you feel like you could do!"): the Super (4), Ultra (5) and Ultimate (10, 90 s)
 * shows. Pinned here: the three-lane plan is 0.38.1's to the tick (a frozen copy of its 81 cues) at every length and
 * folded; every lane fires 27 rockets a minute; the Ultimate's 90 seconds keep the minute's density with the encore,
 * which only four lanes or more over 75 seconds get; never more than 10 rockets a tick before the finale and 20 in it,
 * folded or not; the zones are symmetric; the walks and chases sweep every lane in order; the finale; a wide plan folds
 * by position and spreads a stack 2 ticks apart; ten colour sets, three of them 0.38.1's; and the same plan every time.
 */
class MegaShowLanesTest {

    /**
     * 0.38.1's 81 cues, frozen (its template, in its own order): "tick lane style height" with the tick in a minute's
     * show (beat x 10), style S/C/P/V/R/F/G (spark, chase, pair, volley, rise, finale, gold) and height L/M/H.
     */
    private static final String[] CUES_0381 = {
            "0 0 S L", "20 1 S L", "40 2 S L", "80 0 S L", "100 1 S L", "120 2 S L", "140 1 S H", "160 0 C L",
            "180 1 C L", "200 2 C M", "230 2 C L", "250 1 C L", "270 0 C M", "300 0 C L", "320 1 C L",
            "340 2 C M", "370 2 C L", "390 1 C L", "410 0 C M", "440 0 P M", "440 2 P M", "470 0 P M",
            "470 1 P M", "500 1 P M", "500 2 P M", "530 0 P M", "530 2 P M", "560 0 P M", "560 1 P M",
            "590 1 P M", "590 2 P M", "620 0 P H", "620 2 P H", "660 0 V M", "660 1 V M", "660 2 V M",
            "710 0 V M", "710 1 V M", "710 2 V M", "760 0 V M", "760 1 V M", "760 2 V M", "810 0 V H",
            "810 1 V H", "810 2 V H", "840 0 R L", "870 2 R L", "900 1 R L", "920 0 R L", "940 2 R L",
            "960 1 R L", "980 0 R M", "980 2 R M", "1000 1 R M", "1010 0 R M", "1020 2 R M", "1030 1 R M",
            "1040 0 R H", "1040 2 R H", "1050 1 R H", "1060 0 R H", "1060 1 R H", "1060 2 R H", "1100 0 F L",
            "1100 1 F L", "1100 2 F L", "1110 0 F M", "1110 1 F M", "1110 2 F M", "1120 0 F H", "1120 1 F H",
            "1120 2 F H", "1140 0 F L", "1140 1 F L", "1140 2 F L", "1140 0 F H", "1140 1 F H", "1140 2 F H",
            "1160 0 G H", "1160 1 G H", "1160 2 G H"};

    /** 0.38.1's {@code plan(seconds)}, from the frozen cues and its own formula. */
    private static List<Shot> plan0381(int seconds) {
        int s = Math.max(45, Math.min(90, seconds));
        int finale = s * 20 - 100;
        double k = finale / 1100.0;
        int[] nth = new int[3];
        List<Shot> out = new ArrayList<>();
        for (String cue : CUES_0381) {
            String[] f = cue.split(" ");
            int t = Integer.parseInt(f[0]);
            int lane = Integer.parseInt(f[1]);
            Style style = switch (f[2]) {
                case "S" -> Style.SPARK;
                case "C" -> Style.CHASE;
                case "P" -> Style.PAIR;
                case "V" -> Style.VOLLEY;
                case "R" -> Style.RISE;
                case "F" -> Style.FINALE;
                default -> Style.GOLD;
            };
            Height h = f[3].equals("L") ? Height.LOW : f[3].equals("M") ? Height.MID : Height.HIGH;
            int tick = t < 1100 ? (int) Math.round(t * k) : finale + (t - 1100);
            out.add(new Shot(tick, lane, lane, style, h, nth[lane]++));
        }
        out.sort(Comparator.comparingInt(Shot::tick));
        return out;
    }

    /** 0.38.1's {@code forLaunchers(plan, 1 or 2)}: left on the first, right on the second, the middle in turns. */
    private static List<Shot> fold0381(List<Shot> plan, int n) {
        List<Shot> out = new ArrayList<>();
        Map<Long, Set<Height>> used = new HashMap<>();
        int middle = 0;
        for (Shot s : plan) {
            int to = n == 1 ? 0 : s.lane() == 0 ? 0 : s.lane() == 2 ? 1 : middle++ % 2;
            Set<Height> here = used.computeIfAbsent(s.tick() * 4L + to, k -> EnumSet.noneOf(Height.class));
            Height h = MegaShow.free(s.height(), here);
            here.add(h);
            out.add(new Shot(s.tick(), s.lane(), to, s.style(), h, s.nth()));
        }
        return out;
    }

    private static Map<Integer, Integer> perTick(List<Shot> shots) {
        Map<Integer, Integer> out = new TreeMap<>();
        for (Shot s : shots) {
            out.merge(s.tick(), 1, Integer::sum);
        }
        return out;
    }

    private static Prize row(int launchers, int seconds) {
        return new Prize("x", PrizeTab.COSMETICS, "&dShow", List.of(), 75, new Icon("", Material.FIREWORK_ROCKET),
                PrizeType.FIREWORK, true, null, 1, null, null, null, null, 0, 0, null, 0, "", List.of(), List.of(),
                null, null, List.of(), null, new PrizeExtra(null, 0, null, OnlyIf.ANY, seconds, null, launchers));
    }

    private static long count(List<Shot> shots, int lane) {
        return shots.stream().filter(s -> s.lane() == lane).count();
    }

    @Test
    void theThreeLanePlanIs0381sToTheTick() {
        assertEquals(81, CUES_0381.length);
        for (int seconds : new int[]{45, 60, 75, 90}) {
            List<Shot> old = plan0381(seconds);
            assertEquals(old, MegaShow.plan(3, seconds), seconds + ": every shot, tick, lane, style, height and order");
            assertEquals(old, MegaShow.plan(seconds), seconds + ": and plan(seconds) is it");
            for (int n : new int[]{1, 2}) {
                assertEquals(fold0381(old, n), MegaShow.forLaunchers(MegaShow.plan(seconds), n),
                        seconds + " onto " + n + ": 0.38.1's fold");
                assertEquals(fold0381(old, n), MegaShow.forLaunchers(MegaShow.plan(3, seconds), 3, n),
                        seconds + " onto " + n + ": the three-lane fold");
            }
        }
        assertEquals(MegaShow.plan(3, 60), MegaShow.plan(1, 60), "fewer lanes asked: the three-lane plan");
        assertEquals(MegaShow.plan(3, 60), MegaShow.plan(0, 60));
    }

    @Test
    void everyLaneFires27RocketsAMinute() {
        for (int lanes = 3; lanes <= 10; lanes++) {
            for (int seconds : new int[]{45, 60, 75}) {
                List<Shot> show = MegaShow.plan(lanes, seconds);
                assertEquals(27 * lanes, show.size(), lanes + " lanes, " + seconds + " s");
                for (int lane = 0; lane < lanes; lane++) {
                    assertEquals(27, count(show, lane), lanes + " lanes: lane " + lane + "'s share");
                }
            }
        }
        assertEquals(81, MegaShow.plan(3, 60).size(), "the Mega show");
        assertEquals(108, MegaShow.plan(4, 60).size(), "the Super show");
        assertEquals(135, MegaShow.plan(5, 60).size(), "the Ultra show");
        assertEquals(270, MegaShow.plan(10, 60).size(), "ten lanes for a minute");
        assertEquals(MegaShow.plan(10, 60), MegaShow.plan(11, 60), "never more than ten");
    }

    @Test
    void theUltimatesNinetySecondsKeepTheDensity() {
        List<Shot> ultimate = MegaShow.plan(10, 90);
        assertEquals(396, ultimate.size(), "the Ultimate: 396 rockets");
        assertEquals(4.4, ultimate.size() / 90.0, 1e-9, "4.4 a second, as the minute's 270 / 60 = 4.5");
        for (int lane = 0; lane < 10; lane++) {
            long n = count(ultimate, lane);
            boolean middle = lane >= 3 && lane <= 6;
            assertEquals(middle ? 39 : 40, n, "lane " + lane + ": 40 on the outside zones, 39 in the middle");
        }
        assertEquals(1796, MegaShow.endTick(ultimate), "the last burst 0.2 s before the end");
        assertEquals(158, MegaShow.plan(4, 90).size(), "a Super row of 90 s (an owner's edit) gets it too");
        assertEquals(199, MegaShow.plan(5, 90).size());
    }

    @Test
    void onlyFourOrMoreLanesOverSeventyFiveSecondsGetTheEncore() {
        for (int lanes = 3; lanes <= 10; lanes++) {
            for (int seconds : new int[]{45, 60, 75, 76, 90}) {
                boolean waves = MegaShow.plan(lanes, seconds).stream().anyMatch(s -> s.style() == Style.WAVE);
                assertEquals(lanes >= 4 && seconds > 75, waves, lanes + " lanes, " + seconds + " s");
            }
        }
        assertEquals(81, MegaShow.plan(3, 90).size(), "the Mega show at 90 s: 0.38.1's 81, stretched");
    }

    @Test
    void atMostTenATickBeforeTheFinaleAndTwentyInItEvenFolded() {
        for (int lanes = 3; lanes <= 10; lanes++) {
            for (int seconds : new int[]{45, 60, 75, 76, 90}) {
                List<Shot> plan = MegaShow.plan(lanes, seconds);
                for (int n = 1; n <= lanes; n++) {
                    List<Shot> folded = MegaShow.forLaunchers(plan, lanes, n);
                    int finale = MegaShow.finaleTick(folded);
                    assertEquals(plan.size(), folded.size(), "the same rockets");
                    for (Map.Entry<Integer, Integer> e : perTick(folded).entrySet()) {
                        int cap = e.getKey() < finale ? MegaShow.BODY_CAP : MegaShow.FINALE_CAP;
                        assertTrue(e.getValue() <= cap, lanes + " lanes onto " + n + ", " + seconds + " s: "
                                + e.getValue() + " at tick " + e.getKey());
                    }
                    for (int i = 1; i < folded.size(); i++) {
                        assertTrue(folded.get(i - 1).tick() <= folded.get(i).tick(), "in tick order");
                    }
                    for (Shot s : folded) {
                        assertTrue(s.launcher() >= 0 && s.launcher() < n, "only the launchers there are: " + s);
                    }
                }
            }
        }
        assertEquals(10, MegaShow.BODY_CAP);
        assertEquals(20, MegaShow.FINALE_CAP);
        Map<Integer, Integer> ultimate = perTick(MegaShow.plan(10, 90));
        int f = 90 * 20 - 100;
        assertEquals(10, ultimate.entrySet().stream().filter(e -> e.getKey() < f).mapToInt(Map.Entry::getValue).max()
                .orElse(0), "the Ultimate's body peak is the lane count");
        assertEquals(20, ultimate.entrySet().stream().filter(e -> e.getKey() >= f).mapToInt(Map.Entry::getValue).max()
                .orElse(0), "and twice that in the finale");
        List<Shot> crowded = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            crowded.add(new Shot(5, i % 10, i % 10, Style.VOLLEY, Height.MID, i));
        }
        List<Shot> capped = MegaShow.capped(crowded, 100);
        assertEquals(Map.of(5, 10, 6, 10, 7, 5), perTick(capped), "the excess moves on a tick at a time, in order");
        assertEquals(crowded.subList(0, 10), capped.subList(0, 10), "the first ten keep their tick");
    }

    @Test
    void theZonesAreSymmetricAndNeverEmpty() {
        int[][] widths = {{3, 1, 1, 1}, {4, 1, 2, 1}, {5, 2, 1, 2}, {6, 2, 2, 2}, {7, 2, 3, 2}, {8, 3, 2, 3},
                {9, 3, 3, 3}, {10, 3, 4, 3}};
        for (int[] w : widths) {
            MegaShow.Zones z = MegaShow.Zones.of(w[0]);
            assertEquals(List.of(w[1], w[2], w[3]), List.of(z.left().size(), z.middle().size(), z.right().size()),
                    w[0] + " lanes");
            List<Integer> all = new ArrayList<>(z.left());
            all.addAll(z.middle());
            all.addAll(z.right());
            for (int lane = 0; lane < w[0]; lane++) {
                assertEquals(lane, all.get(lane), w[0] + " lanes: left to right, each lane in one zone");
            }
        }
    }

    @Test
    void theWalksAndChasesSweepEveryLaneInOrder() {
        for (int lanes = 3; lanes <= 10; lanes++) {
            List<Shot> show = MegaShow.plan(lanes, 60);
            List<Shot> walk = show.stream().filter(s -> s.style() == Style.SPARK && s.height() == Height.LOW).toList();
            assertEquals(2 * lanes, walk.size(), lanes + ": two walks across every lane");
            for (int i = 0; i < walk.size(); i++) {
                assertEquals(i % lanes, walk.get(i).lane(), lanes + ": left to right");
            }
            List<Shot> chase = show.stream().filter(s -> s.style() == Style.CHASE).toList();
            assertEquals(4 * lanes, chase.size());
            for (int c = 0; c < 4; c++) {
                List<Shot> run = chase.subList(c * lanes, (c + 1) * lanes);
                Set<Integer> ticks = new HashSet<>();
                for (int j = 0; j < lanes; j++) {
                    int lane = c % 2 == 0 ? j : lanes - 1 - j;
                    assertEquals(lane, run.get(j).lane(), lanes + ": chase " + c + " across, then back");
                    assertEquals(j == lanes - 1 ? Height.MID : Height.LOW, run.get(j).height(), "ending higher");
                    assertTrue(ticks.add(run.get(j).tick()), lanes + ": never two of a sweep in one tick");
                }
                assertEquals(40, run.get(lanes - 1).tick() - run.get(0).tick(), "two seconds across, any width");
            }
        }
    }

    @Test
    void theFinaleIsEveryLaneLowMidHighThenBothThenGold() {
        for (int lanes : new int[]{3, 4, 5, 10}) {
            for (int seconds : new int[]{60, 90}) {
                List<Shot> show = MegaShow.plan(lanes, seconds);
                int f = seconds * 20 - 100;
                assertEquals(f, MegaShow.finaleTick(show), lanes + "/" + seconds + ": the last five seconds");
                List<Shot> finale = show.stream().filter(s -> s.tick() >= f).toList();
                assertEquals(6 * lanes, finale.size());
                Map<Integer, List<Shot>> by = new TreeMap<>();
                for (Shot s : finale) {
                    by.computeIfAbsent(s.tick(), t -> new ArrayList<>()).add(s);
                }
                assertEquals(List.of(f, f + 10, f + 20, f + 40, f + 60), new ArrayList<>(by.keySet()));
                assertTrue(by.get(f).stream().allMatch(s -> s.height() == Height.LOW));
                assertTrue(by.get(f + 10).stream().allMatch(s -> s.height() == Height.MID));
                assertTrue(by.get(f + 20).stream().allMatch(s -> s.height() == Height.HIGH));
                assertEquals(2 * lanes, by.get(f + 40).size(), "low and high together");
                assertEquals(lanes, by.get(f + 60).size());
                assertTrue(by.get(f + 60).stream().allMatch(s -> s.style() == Style.GOLD && s.height() == Height.HIGH),
                        "then the gold from every launcher");
                assertTrue(show.stream().filter(s -> s.tick() < f).noneMatch(s -> s.style() == Style.FINALE
                        || s.style() == Style.GOLD), "only the finale is in it");
                assertEquals(seconds * 20 - 4, MegaShow.endTick(show), "the finale is never stretched");
            }
        }
    }

    @Test
    void aWidePlanFoldsByPositionAndSpreadsAStackTwoTicksApart() {
        int[] turns = new int[10];
        assertEquals(List.of(0, 0, 0, 1, 1, 1, 1, 2, 2, 2), lanesOnto(10, 3), "ten lanes onto three: by position");
        assertEquals(0, MegaShow.launcherOf(9, 10, 1, turns), "onto one: all on it");
        assertEquals(List.of(0, 1, 1, 2, 3), lanesOnto(5, 4), "five onto four");
        for (int seconds : new int[]{60, 90}) {
            List<Shot> plan = MegaShow.plan(10, seconds);
            for (int n : new int[]{1, 2, 3, 9}) {
                List<Shot> folded = MegaShow.forLaunchers(plan, 10, n);
                Set<String> seen = new HashSet<>();
                for (Shot s : folded) {
                    assertTrue(seen.add(s.tick() + "@" + s.launcher() + ":" + s.height()),
                            seconds + " s onto " + n + ": no height twice on one launcher in a tick: " + s);
                }
                assertTrue(MegaShow.endTick(folded) <= seconds * 20 + 2, seconds + " s onto " + n + ": ends on time,"
                        + " give or take 2 ticks: " + MegaShow.endTick(folded));
                assertEquals(plan.size(), folded.size(), "the same rockets");
                assertEquals(perLane(plan), perLane(folded), "each keeping its lane (and so its colours)");
            }
        }
        assertEquals(1202, MegaShow.endTick(MegaShow.forLaunchers(MegaShow.plan(10, 60), 10, 1)),
                "the Ultimate's minute on one launcher: its last stack spread 2 ticks");
        assertEquals(MegaShow.plan(5, 60), MegaShow.forLaunchers(MegaShow.plan(5, 60), 5, 5), "five onto five: as is");
        assertEquals(MegaShow.plan(5, 60), MegaShow.forLaunchers(MegaShow.plan(5, 60), 5, 7), "never more");
        assertEquals(MegaShow.plan(5, 60), MegaShow.forLaunchers(MegaShow.plan(5, 60), 7),
                "the two-argument fold counts a plan's lanes itself");
    }

    private static Map<Integer, Long> perLane(List<Shot> shots) {
        Map<Integer, Long> out = new TreeMap<>();
        for (Shot s : shots) {
            out.merge(s.lane(), 1L, Long::sum);
        }
        return out;
    }

    /** Which launcher (of {@code n}) each lane of {@code lanes} goes to, for lanes not exactly between two. */
    private static List<Integer> lanesOnto(int lanes, int n) {
        int[] turns = new int[lanes];
        List<Integer> out = new ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            out.add(MegaShow.launcherOf(lane, lanes, n, turns));
        }
        return out;
    }

    @Test
    void tenLanesHaveTenColourSetsAndThreeKeep0381s() {
        assertEquals(10, MegaShow.COLOUR_SETS.size());
        Set<Color> all = new HashSet<>();
        for (List<Color> set : MegaShow.COLOUR_SETS) {
            assertEquals(3, set.size());
            for (Color c : set) {
                assertTrue(all.add(c), "no colour in two sets: " + c);
            }
        }
        for (int lane = 0; lane < 3; lane++) {
            assertEquals(MegaShow.LANE_COLOURS.get(lane), MegaShow.laneColours(lane, 3), "0.38.1's three: " + lane);
        }
        assertEquals(List.of(Color.RED, Color.ORANGE, Color.YELLOW), MegaShow.LANE_COLOURS.get(0));
        assertEquals(List.of(Color.LIME, Color.AQUA, Color.TEAL), MegaShow.LANE_COLOURS.get(1));
        assertEquals(List.of(Color.FUCHSIA, Color.PURPLE, Color.BLUE), MegaShow.LANE_COLOURS.get(2));
        for (int lanes = 3; lanes <= 10; lanes++) {
            Set<List<Color>> used = new HashSet<>();
            for (int lane = 0; lane < lanes; lane++) {
                assertTrue(used.add(MegaShow.laneColours(lane, lanes)), lanes + ": each lane its own set");
            }
            for (Shot s : MegaShow.plan(lanes, 60)) {
                MegaShow.Look look = MegaShow.look(s, lanes, null);
                if (s.style() == Style.GOLD) {
                    assertEquals(MegaShow.GOLD_COLOURS, look.colours(), "the gold finale from every launcher");
                } else {
                    assertTrue(MegaShow.laneColours(s.lane(), lanes).containsAll(look.colours()), "its lane's: " + s);
                }
                assertEquals(Color.WHITE, look.fade());
            }
        }
        Shot wave = MegaShow.plan(10, 90).stream().filter(s -> s.style() == Style.WAVE).findFirst().orElseThrow();
        assertEquals(org.bukkit.FireworkEffect.Type.STAR, MegaShow.look(wave, 10, null).type(), "a wave: a star");
        assertTrue(MegaShow.look(wave, 10, null).trail(), "with a trail");
    }

    @Test
    void aRowsLanesAreAtLeastThree() {
        assertEquals(3, MegaShow.lanes(row(1, 60)), "one launcher: 0.38.1's three-lane plan, folded");
        assertEquals(3, MegaShow.lanes(row(2, 60)));
        assertEquals(3, MegaShow.lanes(row(3, 60)), "the Mega show");
        assertEquals(4, MegaShow.lanes(row(4, 60)), "the Super show");
        assertEquals(5, MegaShow.lanes(row(5, 60)), "the Ultra show");
        assertEquals(10, MegaShow.lanes(row(10, 90)), "the Ultimate show");
        assertEquals(10, MegaShow.lanes(row(12, 90)), "never more than ten");
        assertEquals(3, MegaShow.lanes(null));
    }

    @Test
    void theSamePlanEveryTime() {
        for (int lanes = 3; lanes <= 10; lanes++) {
            for (int seconds : new int[]{45, 60, 90}) {
                assertEquals(MegaShow.plan(lanes, seconds), MegaShow.plan(lanes, seconds), lanes + "/" + seconds);
                for (int n = 1; n <= lanes; n += 3) {
                    assertEquals(MegaShow.forLaunchers(MegaShow.plan(lanes, seconds), lanes, n),
                            MegaShow.forLaunchers(MegaShow.plan(lanes, seconds), lanes, n), lanes + " onto " + n);
                }
            }
        }
        assertFalse(MegaShow.plan(10, 90).isEmpty());
    }
}
