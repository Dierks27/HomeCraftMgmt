package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.AchievementDef;
import com.dierks.homecraft.config.PluginConfig.AchievementType;
import com.dierks.homecraft.config.PluginConfig.Quest;
import com.dierks.homecraft.config.PluginConfig.QuestPeriod;
import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.QuestDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game quests and achievements reached where no tokens are paid (the Games world), and paid back
 * home (EXTRAS E4): {@link QuestService#due}, {@link QuestDao#unclaimed}, {@link QuestDao#markClaimed}
 * and {@link AchievementService#unlocksNow}, against a real database.
 *
 * <p>Pinned here: a game quest reached in the Games world is kept, not claimed, and paid once back
 * home, even after its day or week is over (a player who logged off in the Games world at 8 PM is
 * paid the next day); a quest of any other kind, one not reached, one read from the statistics, one
 * no longer in the pool and a row of no known period are never paid by it; the poll and a world
 * change landing together pay it once; and a Games counter reached in the Games world unlocks
 * nothing there, and unlocks with its reward at the next sweep back home, once (a world change home
 * sweeps only while the Arcade is on).
 */
class HeldGameRewardsTest {

    private static final String TODAY = "d20725";
    private static final String YESTERDAY = "d20724";
    private static final String THIS_WEEK = "w20724";
    private static final String LAST_WEEK = "w20717";

    private static final Quest COURSE_DAILY = new Quest("course_daily", QuestPeriod.DAILY, QuestType.FINISH_COURSE, 1,
            5, "Finish a course or a round of golf");
    private static final Quest CABINET_DAILY = new Quest("cabinet_daily", QuestPeriod.DAILY,
            QuestType.FINISH_CABINET, 3, 4, "Play 3 arcade cabinets");
    private static final Quest STARS_WEEKLY = new Quest("stars_weekly", QuestPeriod.WEEKLY, QuestType.EARN_STARS, 6,
            20, "Earn 6 Fresh Courses stars");
    private static final Quest SELL_DAILY = new Quest("sell_daily", QuestPeriod.DAILY, QuestType.SELL_MARKET, 10, 3,
            "Sell 10 things at the market");
    private static final Quest FISH_DAILY = new Quest("fish_daily", QuestPeriod.DAILY, QuestType.CATCH_FISH, 5, 3,
            "Catch 5 fish");
    private static final List<Quest> POOL = List.of(COURSE_DAILY, CABINET_DAILY, STARS_WEEKLY, SELL_DAILY, FISH_DAILY);

    private static final BiFunction<QuestPeriod, String, Quest> BY_ID = (p, id) -> POOL.stream()
            .filter(q -> q.period() == p && q.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    /** Fishing is read from the statistics, as QuestStats reads it; the rest are pushed. */
    private static final Predicate<QuestType> PULLED = t -> t == QuestType.CATCH_FISH;

    private Connection connection;
    private QuestDao quests;
    private AchievementDao achievements;
    private final UUID alex = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = Database.open(connection, Logger.getAnonymousLogger());
        quests = new QuestDao(db);
        achievements = new AchievementDao(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private List<QuestService.Due> due(Set<String> dealtNow) throws Exception {
        return QuestService.due(quests.unclaimed(alex, POOL.stream().map(Quest::id).toList()), TODAY, THIS_WEEK,
                dealtNow, BY_ID, PULLED);
    }

    @Test
    void aGameQuestReachedInTheGamesWorldIsPaidBackHomeEvenAfterItsDayIsOver() throws Exception {
        // 8 PM yesterday, in the Games world: the course was finished, the quest reached, nothing claimed
        assertEquals(1, quests.addProgress(alex, "course_daily", YESTERDAY, 1), "reached where no tokens are paid");
        assertFalse(quests.get(alex, "course_daily", YESTERDAY).claimed(), "kept, not claimed there");
        List<QuestService.Due> due = due(Set.of("cabinet_daily"));
        assertEquals(List.of(new QuestService.Due(COURSE_DAILY, YESTERDAY)), due,
                "today, back home: yesterday's reached game quest is still owed, under yesterday's key");

        // the poll and a world change land together: one flip, one payment
        assertTrue(quests.markClaimed(alex, "course_daily", YESTERDAY), "the first settle pays it");
        assertFalse(quests.markClaimed(alex, "course_daily", YESTERDAY), "the second finds it paid: no double pay");
        assertEquals(List.of(), due(Set.of("cabinet_daily")), "and it is never owed again");
    }

    @Test
    void aWeeklyGameQuestIsOwedAcrossTheWeekBoundaryToo() throws Exception {
        quests.addProgress(alex, "stars_weekly", LAST_WEEK, 7);
        assertEquals(List.of(new QuestService.Due(STARS_WEEKLY, LAST_WEEK)), due(Set.of()),
                "6 stars reached last week in the Games world: paid this week, once home");
    }

    @Test
    void onlyReachedPushedQuestsArePaidAndOnlyGameOnesFromAnEarlierPeriod() throws Exception {
        quests.addProgress(alex, "cabinet_daily", YESTERDAY, 2);
        quests.addProgress(alex, "sell_daily", YESTERDAY, 12);
        quests.addProgress(alex, "fish_daily", TODAY, 9);
        quests.addProgress(alex, "gone_daily", YESTERDAY, 50);
        assertEquals(List.of(), due(Set.of("cabinet_daily", "sell_daily", "fish_daily")),
                "2 of 3 cabinets is not reached; yesterday's sale quest was paid where it was reached or not at all; "
                        + "fishing is the poll's; a quest no longer in the pool is left alone");

        quests.addProgress(alex, "sell_daily", TODAY, 10);
        quests.addProgress(alex, "cabinet_daily", TODAY, 3);
        assertEquals(List.of(new QuestService.Due(CABINET_DAILY, TODAY), new QuestService.Due(SELL_DAILY, TODAY)),
                due(Set.of("cabinet_daily", "sell_daily")), "today's reached pushed quests, dealt today, as before");
        assertEquals(List.of(), due(Set.of()), "today's rows of quests not dealt today (rerolled away) are not paid");

        List<QuestDao.Held> odd = List.of(new QuestDao.Held("course_daily", "x20724", 5),
                new QuestDao.Held("course_daily", null, 5));
        assertEquals(List.of(), QuestService.due(odd, TODAY, THIS_WEEK, Set.of(), BY_ID, PULLED),
                "a row of no known period is never paid");
        assertEquals(List.of(), QuestService.due(null, TODAY, THIS_WEEK, Set.of(), BY_ID, PULLED), "nothing: nothing");
    }

    @Test
    void aWorldChangeHomeSweepsTheAchievementsOnlyWhileTheArcadeIsOn() {
        assertTrue(ArcadeListener.sweeps(true), "home from the Games world: the Games counters unlock now");
        assertFalse(ArcadeListener.sweeps(false),
                "with arcade.enabled false, as the join and five-minute sweeps: a portal unlocks nothing");
    }

    @Test
    void aGamesCounterReachedInTheGamesWorldUnlocksOnlyBackHomeWithItsRewardOnce() throws Exception {
        AchievementDef first = new AchievementDef("game_first_course", "Games", AchievementType.COUNTER,
                "course_finishes", 1, true, 15, "Finish a course");
        AchievementDef all = new AchievementDef("game_all_cabinets", "Games", AchievementType.COUNTER, "cabinets", 8,
                true, 30, "Finish every arcade cabinet game");
        List<AchievementDef> defs = List.of(first, all);

        long value = achievements.addCounter(alex, "course_finishes", 1);
        assertEquals(List.of(), AchievementService.unlocksNow(defs, "course_finishes", value, false),
                "in the Games world: counted, but nothing unlocks (it would spend its only chance and pay nothing)");
        assertEquals(1, achievements.counter(alex, "course_finishes"), "the count is kept");
        assertTrue(AchievementService.sweepUnlocks(first, achievements.counter(alex, "course_finishes"),
                achievements.unlocked(alex).contains(first.id())), "the next sweep back home unlocks it");
        assertTrue(achievements.unlock(alex, first.id(), 1L), "with its reward, once");
        assertFalse(achievements.unlock(alex, first.id(), 2L), "never twice");
        assertFalse(AchievementService.sweepUnlocks(first, 5, achievements.unlocked(alex).contains(first.id())),
                "a later sweep leaves it be");
        assertEquals(List.of("game_first_course"), AchievementService.unlocksNow(defs, "course_finishes", 1, true),
                "reached where tokens are paid: at once");
        assertFalse(AchievementService.sweepUnlocks(new AchievementDef("x", "Games", AchievementType.COUNTER,
                "course_finishes", 1, false, 5, "Off"), 9, false), "a switched-off one never unlocks");

        // "every cabinet": a cabinet's first finish counts once, however often it is played
        assertEquals(1, achievements.addCounter(alex, GamesProgress.CABINET_MARK + "snake", 1),
                "Snake's first finish: firstTime is true");
        assertEquals(2, achievements.addCounter(alex, GamesProgress.CABINET_MARK + "snake", 1),
                "its second is not the first");
        assertFalse(AchievementService.sweepUnlocks(all, 7, false), "7 of 8 cabinets is not every one");
        assertTrue(AchievementService.sweepUnlocks(all, 8, false), "8 is");
    }
}
