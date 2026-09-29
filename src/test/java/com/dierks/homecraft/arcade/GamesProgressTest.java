package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GameProgress;
import com.dierks.homecraft.games.GameSpec;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skill games' finishes as quests and achievement counters (EXTRAS E4): each real finish is one
 * quest step and its counters exactly once; practice counts for FINISH_CABINET but earns no medal;
 * every cabinet game counts toward "every cabinet" once; nothing counts where the games pay no
 * tokens; and a game of chance is never counted, even if something called for one.
 */
class GamesProgressTest {

    /** A sink that writes down what it was told. */
    private static final class Recorder implements GamesProgress.Sink {
        final List<String> events = new ArrayList<>();
        final Set<String> firsts = new HashSet<>();
        boolean here = true;

        @Override
        public boolean countsHere(Player player) {
            return here;
        }

        @Override
        public boolean cabinet(String gameId) {
            return GamesProgress.isCabinet(gameId);
        }

        @Override
        public void quest(Player player, QuestType type, long amount) {
            events.add("quest " + type + " " + amount);
        }

        @Override
        public void count(Player player, String counter, long by) {
            events.add("count " + counter + " " + by);
        }

        @Override
        public boolean firstTime(Player player, String marker) {
            return firsts.add(marker);
        }
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> 7;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Alex";
                    default -> null;
                });
    }

    private final Recorder sink = new Recorder();
    private final GamesProgress progress = new GamesProgress(sink);
    private final Player alex = player();

    @Test
    void aFinishedCabinetRunIsOneQuestStepAndEachCabinetCountsOnceTowardEveryCabinet() {
        progress.cabinetFinished(alex, "snake", false, false);
        assertEquals(List.of("quest FINISH_CABINET 1", "count cabinet_finishes 1", "count cabinets 1"), sink.events,
                "a first Snake run: one quest step, one finish, and Snake is a new cabinet");
        sink.events.clear();
        progress.cabinetFinished(alex, "snake", false, false);
        assertEquals(List.of("quest FINISH_CABINET 1", "count cabinet_finishes 1"), sink.events,
                "a second Snake run is another step and finish, but not another different cabinet");
        sink.events.clear();
        progress.cabinetFinished(alex, "Ore_Merge ", false, false);
        assertTrue(sink.events.contains("count cabinets 1"), "a different cabinet (any case, trimmed) counts: " + sink.events);
    }

    @Test
    void practiceCountsForTheQuestButNeverForAMedal() {
        progress.cabinetFinished(alex, "snake", true, true);
        assertTrue(sink.events.contains("quest FINISH_CABINET 1"), "practice is a finished run: " + sink.events);
        assertFalse(sink.events.stream().anyMatch(e -> e.contains(GamesProgress.CABINET_GOLDS)),
                "practice records nothing, so it has no medal to count: " + sink.events);
        sink.events.clear();
        progress.cabinetFinished(alex, "snake", false, true);
        assertTrue(sink.events.contains("count cabinet_golds 1"), "a gold on a scored run counts: " + sink.events);
    }

    @Test
    void aGameOfChanceIsNeverCountedEvenIfSomethingCalledForOne() {
        int chance = 0;
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            if (spec.kind() == GameKind.CHANCE) {
                chance++;
                progress.cabinetFinished(alex, spec.id(), false, true);
                assertFalse(GamesProgress.isCabinet(spec.id()), spec.id() + " is a game of chance, not a cabinet");
            }
        }
        assertTrue(chance >= 5, "the catalog's games of chance were all tried: " + chance);
        progress.cabinetFinished(alex, "trials", false, false);
        progress.cabinetFinished(alex, "scratch_ticket", false, false);
        progress.cabinetFinished(alex, "", false, false);
        progress.cabinetFinished(alex, null, false, false);
        assertEquals(List.of(), sink.events, "only a catalog cabinet is a cabinet finish");
    }

    @Test
    void nothingCountsWhereTheGamesPayNoTokens() {
        sink.here = false;
        progress.cabinetFinished(alex, "snake", false, true);
        progress.courseFinished(alex, "hill", false, true);
        progress.golfFinished(alex, "green", 20, 27, 2, false);
        progress.starsEarned(alex, 3);
        progress.freshSetFinished(alex, "7:38");
        progress.starChartTopGoal(alex, 20720);
        assertEquals(List.of(), sink.events, "creative, or a world without games: nothing is counted");
        assertTrue(sink.firsts.isEmpty(), "and no cabinet is marked as done");
    }

    @Test
    void aCourseFinishIsOneStepAndARecordIsCounted() {
        progress.courseFinished(alex, "hill", false, false);
        assertEquals(List.of("quest FINISH_COURSE 1", "count course_finishes 1"), sink.events, "a counted run");
        sink.events.clear();
        progress.courseFinished(alex, "fresh_parkour", true, true);
        assertEquals(List.of("quest FINISH_COURSE 1", "count course_finishes 1", "count course_records 1"),
                sink.events, "a Fresh course with a record");
    }

    @Test
    void aGolfRoundCountsAsACourseWithHolesInOneAndUnderPar() {
        progress.golfFinished(alex, "green", 25, 27, 2, false);
        assertEquals(List.of("quest FINISH_COURSE 1", "count course_finishes 1", "count holes_in_one 2",
                "count golf_under_par 1"), sink.events, "two holes-in-one, two under par");
        sink.events.clear();
        progress.golfFinished(alex, "green", 27, 27, 0, true);
        assertEquals(List.of("quest FINISH_COURSE 1", "count course_finishes 1"), sink.events,
                "level par is not under par, and no hole-in-one is none");
        sink.events.clear();
        progress.golfFinished(alex, "green", 27, 27, 0, false, true);
        assertEquals(List.of("quest FINISH_COURSE 1", "count course_finishes 1", "count course_records 1"),
                sink.events, "a round that sets the course's record is a course record ('Set a course record')");
        sink.events.clear();
        GameProgress quiet = new GameProgress() {
            @Override
            public void golfFinished(Player player, String courseId, int strokes, int par, int holesInOne,
                                     boolean fresh) {
                sink.events.add("six " + strokes);
            }
        };
        quiet.golfFinished(alex, "green", 30, 27, 0, false, true);
        assertEquals(List.of("six 30"), sink.events, "a listener that doesn't hear records still hears the round");
        assertFalse(GamesProgress.underPar(10, 0), "a round with no par is never under it");
        assertFalse(GamesProgress.underPar(0, 27), "a round with no strokes isn't a round");
        assertTrue(GamesProgress.underPar(26, 27), "one under is under");
    }

    @Test
    void starsAreOneQuestStepEachAndTheSetAndTheTopGoalCountTheirCounters() {
        progress.starsEarned(alex, 3);
        progress.starsEarned(alex, 0);
        progress.starsEarned(alex, -1);
        progress.freshSetFinished(alex, "7:38");
        progress.starChartTopGoal(alex, 20720);
        assertEquals(List.of("quest EARN_STARS 3", "count fresh_sets 1", "count star_chart_tops 1"), sink.events,
                "3 stars are 3 steps; no stars are nothing");
    }

    @Test
    void aRaceNightRacedCountsAndAWinCountsAgainButNoQuestStep() {
        progress.raceNightFinished(alex, false);
        assertEquals(List.of("count race_nights 1"), sink.events, "raced a Race Night: one count");
        sink.events.clear();
        progress.raceNightFinished(alex, true);
        assertEquals(List.of("count race_nights 1", "count race_night_wins 1"), sink.events,
                "won one: the win counts too (its races already stepped FINISH_COURSE where they counted)");
        sink.events.clear();
        progress.raceNightFinished(null, true);
        assertEquals(List.of(), sink.events, "nobody is nothing");
    }

    @Test
    void nobodyIsNothing() {
        progress.cabinetFinished(null, "snake", false, false);
        progress.courseFinished(null, "hill", false, false);
        progress.golfFinished(null, "green", 1, 3, 1, false);
        progress.starsEarned(null, 2);
        progress.freshSetFinished(null, "7:38");
        progress.starChartTopGoal(null, 1);
        assertEquals(List.of(), sink.events, "a finish with no player counts for nobody");
    }

    @Test
    void aWholeMinuteOnFallingFloorsCountsOnceAndOnlyWhereTheGamesPay() {
        progress.floorsLastedMinute(alex);
        assertEquals(List.of("count " + GamesProgress.FLOORS_MINUTES + " 1"), sink.events,
                "one minute-long round: one count toward game_floors_minute, and no quest step");
        sink.events.clear();
        sink.here = false;
        progress.floorsLastedMinute(alex);
        progress.floorsLastedMinute(null);
        assertEquals(List.of(), sink.events, "nothing where the games pay no tokens, and nothing for nobody");
        assertTrue(GameProgress.NONE != null, "(the default listener ignores it)");
        GameProgress.NONE.floorsLastedMinute(alex);
    }

    @Test
    void theCountersAreTheOnesTheAchievementsRead() throws Exception {
        Set<String> used = new HashSet<>();
        for (var row : GameAchievementsTest.gamesRows()) {
            used.add(String.valueOf(row.get("counter")));
        }
        assertEquals(new HashSet<>(GamesProgress.COUNTERS), used,
                "every counter the progress keeps unlocks a Games achievement, and every one they read is kept");
    }

    @Test
    void theGameQuestTypesArePushedNeverPulled() {
        for (QuestType t : QuestType.values()) {
            boolean game = t == QuestType.FINISH_CABINET || t == QuestType.FINISH_COURSE || t == QuestType.EARN_STARS;
            assertEquals(game, t.game(), t + ": only the three new types are the games'");
            if (game) {
                assertFalse(QuestStats.isPulled(t), t + " is pushed by the games, never read from statistics");
                assertEquals(0, QuestStats.read(null, t), t + " has no statistic");
            }
        }
        assertTrue(GamesProgress.dealable(null, QuestType.CATCH_FISH), "an ordinary quest is always dealable");
        for (QuestType t : List.of(QuestType.FINISH_CABINET, QuestType.FINISH_COURSE, QuestType.EARN_STARS)) {
            assertFalse(GamesProgress.dealable(null, t), t + " isn't dealt while the games aren't there");
        }
        assertFalse(GamesProgress.countsHere(null, null), "no games, nothing counts");
    }
}
