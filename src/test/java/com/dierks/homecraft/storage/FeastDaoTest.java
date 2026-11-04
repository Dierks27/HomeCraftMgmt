package com.dierks.homecraft.storage;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Family Feast's markers against a real SQLite (0.38.6): only keys under {@code feast.}, a round trip, the order
 * list kept once each, and the reset of one edition (its day boards too, never another edition's), refused for a
 * year's edition once anyone was paid, its table filled or a Turkey Mini was given.
 */
class FeastDaoTest {

    private Connection conn;
    private Database db;
    private FeastDao dao;
    private GamesDao games;
    private AchievementDao counters;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        dao = new FeastDao(db);
        games = new GamesDao(db);
        counters = new AchievementDao(db);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    @Test
    void aKeyOutsideFeastIsRefusedBeforeTheDatabase() {
        assertThrows(IllegalArgumentException.class, () -> dao.get("schema_version"));
        assertThrows(IllegalArgumentException.class, () -> dao.set("games.secret", "x"));
        assertThrows(IllegalArgumentException.class, () -> dao.set("feast.", "x"), "a prefix alone is no key");
        assertThrows(IllegalArgumentException.class, () -> dao.reset("Bad Edition"));
        assertTrue(FeastDao.allowed(FeastRules.POT_KEY) && FeastDao.allowed(FeastRules.doneKey("2026")));
    }

    @Test
    void aRoundTrip() throws Exception {
        assertNull(dao.get(FeastRules.POT_KEY));
        dao.set(FeastRules.POT_KEY, "world,1,64,2");
        assertEquals("world,1,64,2", dao.get(FeastRules.POT_KEY));
        dao.set(FeastRules.POT_KEY, null);
        assertNull(dao.get(FeastRules.POT_KEY), "null removes it");
    }

    @Test
    void theOrderListAppendsEachHelperOnce() throws Exception {
        assertTrue(dao.appendOrder("2026", b));
        assertTrue(dao.appendOrder("2026", a));
        assertFalse(dao.appendOrder("2026", b), "already there");
        assertEquals(List.of(b, a), dao.order("2026"), "first gift first");
        assertEquals(List.of(), dao.order("test"), "another edition's own list");
    }

    @Test
    void resetDeletesTheEditionsDayBoardsTooAndNothingElse() throws Exception {
        games.addPointsCapped(a, FeastRules.GAME, FeastRules.board("test"), FeastRules.dayBoard("test", 5), 10, 40, 1L);
        games.addPoints(b, FeastRules.GAME, FeastRules.board("test"), 4, 1L);
        games.addPoints(a, FeastRules.GAME, FeastRules.board("test2"), 7, 1L);
        games.addPoints(a, FeastRules.GAME, FeastRules.board("2026"), 3, 1L);
        dao.appendOrder("test", a);
        dao.set(FeastRules.doneKey("test"), "1");
        dao.set(FeastRules.POT_KEY, "world,1,64,2");
        counters.addCounter(a, FeastRules.seenKey("test"), 1);
        counters.addCounter(a, FeastRules.seenKey("2026"), 1);
        games.payReward(a, FeastRules.GAME, TokenService.Source.EVENT_FEAST, 5, RewardKind.EVENT_PRIZE,
                SkillRewards.eventRef(FeastRules.eventId("test")), 20, -1, -1, true, "Family Feast", 1L);

        FeastDao.Reset r = dao.reset("test");
        assertFalse(r.refused());
        assertEquals(3, r.scores(), "the board twice and the day board once");
        assertEquals(2, r.markers(), "done and order");
        assertEquals(1, r.counters());
        assertEquals(1, r.rewards(), "a test's reward row goes too");
        assertNull(games.best(a, FeastRules.GAME, FeastRules.dayBoard("test", 5)), "the day board too");
        assertNull(games.best(a, FeastRules.GAME, FeastRules.board("test")));
        assertEquals(7L, games.best(a, FeastRules.GAME, FeastRules.board("test2")), "never another edition's");
        assertEquals(3L, games.best(a, FeastRules.GAME, FeastRules.board("2026")));
        assertEquals("world,1,64,2", dao.get(FeastRules.POT_KEY), "the pot stays");
        assertEquals(1, counters.counter(a, FeastRules.seenKey("2026")), "the real one's marks stay");
        assertEquals(0, counters.counter(a, FeastRules.seenKey("test")));
        assertEquals(0, dao.rewardRows("test"));
    }

    @Test
    void resetIsRefusedForAYearsEditionAfterAPayoutAndAllowedForATest() throws Exception {
        games.addPoints(a, FeastRules.GAME, FeastRules.board("2026"), 30, 1L);
        assertFalse(dao.reset("2026").refused(), "nobody paid yet: allowed");
        games.addPoints(a, FeastRules.GAME, FeastRules.board("2026"), 30, 1L);
        games.payReward(a, FeastRules.GAME, TokenService.Source.EVENT_FEAST, 5, RewardKind.EVENT_PRIZE,
                SkillRewards.eventRef(FeastRules.eventId("2026")), 20, -1, -1, true, "Family Feast", 1L);
        assertEquals(1, dao.rewardRows("2026"));
        FeastDao.Reset r = dao.reset("2026");
        assertTrue(r.refused(), "the real Feast can never pay twice");
        assertEquals(30L, games.best(a, FeastRules.GAME, FeastRules.board("2026")), "and nothing was wiped");
        assertEquals(1, dao.rewardRows("2026"));

        games.payReward(b, FeastRules.GAME, TokenService.Source.EVENT_FEAST, 5, RewardKind.EVENT_PRIZE,
                SkillRewards.eventRef(FeastRules.eventId("test")), 20, -1, -1, true, "Family Feast", 1L);
        assertFalse(dao.reset("test").refused(), "a test, always");
    }

    @Test
    void resetIsRefusedForAYearsFilledEditionThatPaidNoTokens() throws Exception {
        games.addPoints(a, FeastRules.GAME, FeastRules.board("2026"), 30, 1L);
        counters.addCounter(a, FeastRules.miniKey("2026"), 1);
        assertEquals(0, dao.rewardRows("2026"), "tokens: 0, so no reward row");
        assertTrue(dao.reset("2026").refused(), "a Turkey Mini was given: a reset would give a second");
        assertEquals(1, counters.counter(a, FeastRules.miniKey("2026")), "and its mark stays");

        counters.addCounter(a, FeastRules.miniKey("2026"), -1);
        dao.set(FeastRules.doneKey("2026"), "1");
        assertTrue(dao.reset("2026").refused(), "the table filled: refused even before any Mini");
        assertEquals("1", dao.get(FeastRules.doneKey("2026")));

        dao.set(FeastRules.doneKey("test"), "1");
        counters.addCounter(b, FeastRules.miniKey("test"), 1);
        assertFalse(dao.reset("test").refused(), "a test edition is always reset");
    }
}
