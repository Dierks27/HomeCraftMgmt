package com.dierks.homecraft.arcade.feast;

import com.dierks.homecraft.config.FeastConfig;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.FeastDao;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.TokenDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Family Feast's bookkeeping against a real SQLite with a fake server (0.38.6): a failed write takes nothing, a
 * cap of 0 adds through {@code addPoints}, the items taken come from what was added, the fill pays every helper once
 * (an offline one too) and gives each one at home their Turkey Mini first and then their moment, a second fill pays
 * nothing, a late helper is settled at once, a join pays nothing twice, the total never counts the day boards, an
 * offline helper's moment and Mini come once when they are home, a full bag keeps the Mini owed (unmarked) until a
 * slot is free, and a test edition never mints a Mini.
 */
class FeastCoreTest {

    /** A fake server: who is online and home, and everything the core asked for, in order. */
    static final class Host implements FeastHost {
        FeastConfig.Feast settings = FeastConfig.Feast.DEFAULTS;
        boolean open = true;
        long now = 1_000L;
        long day = LocalDate.of(2026, 11, 23).toEpochDay();
        final Set<UUID> online = new HashSet<>();
        final Set<UUID> away = new HashSet<>();
        final Set<UUID> full = new HashSet<>();
        final Map<UUID, String> names = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean mintOk = true;

        @Override public long now() { return now; }
        @Override public long epochDay() { return day; }
        @Override public FeastConfig.Feast settings() { return settings; }
        @Override public boolean open() { return open; }
        @Override public Collection<UUID> online() { return new ArrayList<>(online); }
        @Override public boolean home(UUID p) { return online.contains(p) && !away.contains(p); }
        @Override public String name(UUID p) { return names.get(p); }
        @Override public boolean hasRoom(UUID p) { return online.contains(p) && !full.contains(p); }

        @Override
        public boolean mintMini(UUID p, String miniId) {
            calls.add("mini " + names.get(p) + " " + miniId);
            return mintOk;
        }

        @Override
        public void moment(UUID p, boolean withLine, String line) {
            calls.add("moment " + names.get(p) + (withLine ? " " + line : ""));
        }

        @Override public void paid(UUID p, int tokens) { calls.add("paid " + names.get(p) + " " + tokens); }
        @Override public void broadcast(String line) { calls.add("all " + line); }
        @Override public void homeShow() { calls.add("show"); }
        @Override public void info(String line) { }
        @Override public void warn(String line) { calls.add("warn"); }

        long count(String prefix) {
            return calls.stream().filter(c -> c.startsWith(prefix)).count();
        }
    }

    private Connection conn;
    private Database db;
    private Host host;
    private GamesDao games;
    private AchievementDao counters;
    private FeastDao meta;
    private TokenDao tokens;
    private FeastCore core;
    private final UUID dad = UUID.randomUUID();
    private final UUID lily = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        host = new Host();
        host.names.put(dad, "Dad");
        host.names.put(lily, "Lily");
        host.names.put(sam, "Sam");
        games = new GamesDao(db);
        counters = new AchievementDao(db);
        meta = new FeastDao(db);
        tokens = new TokenDao(db);
        core = new FeastCore(host, games, counters, meta);
        core.refresh();
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private FeastConfig.Feast with(int goal, int cap, String edition) {
        FeastConfig.Feast d = FeastConfig.Feast.DEFAULTS;
        return new FeastConfig.Feast(true, d.from(), d.to(), edition, goal, cap, d.tokens(), d.creativeHelpersPaid());
    }

    private int balance(UUID p) throws Exception {
        return tokens.change(p, 0, "TEST", "", 1L);
    }

    @Test
    void aFailedWriteTakesNothing() throws Exception {
        conn.close(); // every write now fails
        FeastCore.Gift g = core.give(dad, 3, 1, false, false);
        assertEquals(FeastCore.Outcome.FAILED, g.outcome());
        assertEquals(0, g.taken(), "nothing leaves the hand");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:"); // for close()
    }

    @Test
    void closedTakesNothing() {
        host.open = false;
        assertEquals(new FeastCore.Gift(FeastCore.Outcome.CLOSED, 0, 0), core.give(dad, 1, 5, true, false));
    }

    @Test
    void oneATapTheStackOnASneakAndFullForTheDay() throws Exception {
        assertEquals(new FeastCore.Gift(FeastCore.Outcome.TAKEN, 1, 3), core.give(lily, 3, 16, false, false),
                "one pie a tap");
        assertEquals(1, counters.counter(lily, FeastRules.survivalKey("2026")), "a gift outside Creative, counted");
        assertEquals(3, core.total());
        games.addPointsCapped(dad, FeastRules.GAME, FeastRules.board("2026"), FeastRules.dayBoard("2026", host.day),
                30, 40, 1L);
        core.refresh();
        assertEquals(new FeastCore.Gift(FeastCore.Outcome.TAKEN, 3, 9), core.give(dad, 3, 16, true, false),
                "30 used of 40: a sneak puts in 3 pies (9 points)");
        assertEquals(new FeastCore.Gift(FeastCore.Outcome.FULL, 0, 0), core.give(dad, 3, 13, true, false),
                "1 left and a pie is 3: full for today, nothing taken");
        assertEquals(new FeastCore.Gift(FeastCore.Outcome.TAKEN, 1, 1), core.give(dad, 1, 5, true, false),
                "but a loaf still fits");
        host.day++;
        assertEquals(FeastCore.Outcome.TAKEN, core.give(dad, 3, 1, false, false).outcome(), "a new day");
    }

    @Test
    void aCapOfZeroAddsThroughAddPoints() throws Exception {
        host.settings = with(500, 0, "2026");
        FeastCore.Gift g = core.give(dad, 6, 64, true, false);
        assertEquals(new FeastCore.Gift(FeastCore.Outcome.TAKEN, 64, 384), g, "no limit: the whole stack of cake");
        assertEquals(384L, games.best(dad, FeastRules.GAME, FeastRules.board("2026")));
        assertNull(games.best(dad, FeastRules.GAME, FeastRules.dayBoard("2026", host.day)),
                "no day board without a cap");
    }

    @Test
    void theItemsTakenComeFromWhatWasAdded() throws Exception {
        // 31 of 40 used today: 9 left, so a sneak with five pies puts in three (the add's own count, rounded up:
        // FeastRulesTest pins the rounding for an add the cap cut short)
        games.addPointsCapped(dad, FeastRules.GAME, FeastRules.board("2026"), FeastRules.dayBoard("2026", host.day),
                31, 40, 1L);
        core.refresh();
        FeastCore.Gift g = core.give(dad, 3, 5, true, false);
        assertEquals(9, g.added());
        assertEquals(FeastRules.taken(g.added(), 3), g.taken());
        assertEquals(3, g.taken());
        assertEquals(40L, games.best(dad, FeastRules.GAME, FeastRules.dayBoard("2026", host.day)), "the day is full");
    }

    @Test
    void theTotalIgnoresTheDayBoards() throws Exception {
        core.give(dad, 2, 5, true, false);
        core.give(lily, 1, 3, true, false);
        assertEquals(13, core.total(), "10 + 3, the day boards never counted");
        assertEquals(List.of(dad, lily), core.helpers(), "first gift first");
        assertEquals(2, core.view().helpers());
    }

    @Test
    void theFillPaysEachHelperOnceOfflineTooAndGivesTheMiniBeforeTheMoment() throws Exception {
        host.settings = with(20, 40, "2026");
        host.online.addAll(List.of(dad, lily));
        core.give(sam, 2, 4, true, false); // Sam gave, then went offline
        core.give(lily, 1, 1, false, true); // Lily's one loaf, in Creative
        FeastCore.Gift last = core.give(dad, 3, 16, true, false);
        assertEquals(FeastCore.Outcome.TAKEN, last.outcome());
        core.afterGift(dad);
        assertTrue(core.done());
        assertEquals(20, balance(sam), "offline, paid");
        assertEquals(20, balance(lily), "her one loaf counts the same");
        assertEquals(20, balance(dad));
        assertEquals(List.of("paid Sam 20", "paid Lily 20", "paid Dad 20",
                "mini Lily turkey_mini_2026", "moment Lily", "mini Dad turkey_mini_2026", "moment Dad",
                "all &6Thankful for: &fSam, Lily and Dad&6!", "show"), host.calls,
                "pay everyone, then each one home: the Mini first, then the moment; the line for all; the show");

        host.calls.clear();
        core.fill();
        core.second();
        core.settle(dad);
        assertEquals(20, balance(dad), "a second fill pays nothing");
        assertEquals(List.of(), host.calls, "and gives nothing again");
    }

    @Test
    void anOfflineHelpersMomentAndMiniComeOnceWhenTheyAreHome() throws Exception {
        host.settings = with(10, 40, "2026");
        host.online.add(dad);
        core.give(sam, 2, 1, false, false);
        core.give(dad, 3, 3, true, false);
        core.afterGift(dad);
        host.calls.clear();

        host.online.add(sam);
        host.away.add(sam); // back, but in a game
        core.settle(sam);
        assertEquals(List.of(), host.calls, "paid already; not home yet: nothing");
        host.away.remove(sam);
        core.settle(sam);
        assertEquals(List.of("mini Sam turkey_mini_2026", "moment Sam &6Thankful for: &fSam and Dad&6!"),
                host.calls, "home: their Mini, then their own moment with the line");
        host.calls.clear();
        core.second();
        core.settle(sam);
        FeastCore again = new FeastCore(host, games, counters, meta); // a restart
        again.refresh();
        again.second();
        assertEquals(List.of(), host.calls, "once, across a restart too");
    }

    @Test
    void aLateHelperIsSettledAtOnce() throws Exception {
        host.settings = with(10, 40, "2026");
        host.online.addAll(List.of(dad, lily));
        core.give(dad, 6, 2, true, false);
        core.afterGift(dad);
        assertTrue(core.done());
        host.calls.clear();
        core.give(lily, 1, 1, false, false);
        core.afterGift(lily);
        assertEquals(List.of("paid Lily 20", "mini Lily turkey_mini_2026",
                "moment Lily &6Thankful for: &fDad and Lily&6!"), host.calls);
    }

    @Test
    void aJoinPaysNothingTwice() throws Exception {
        host.settings = with(10, 40, "2026");
        core.give(dad, 6, 2, true, false);
        core.afterGift(dad);
        assertEquals(20, balance(dad), "paid at the fill, offline");
        host.online.add(dad);
        FeastCore restarted = new FeastCore(host, games, counters, meta);
        restarted.refresh();
        restarted.settle(dad);
        restarted.settle(dad);
        assertEquals(20, balance(dad), "the reward row is once");
    }

    @Test
    void creativeOnlyHelpersAreNotPaidWhenTheKnobIsOff() throws Exception {
        FeastConfig.Feast d = FeastConfig.Feast.DEFAULTS;
        host.settings = new FeastConfig.Feast(true, d.from(), d.to(), "2026", 10, 40, 20, false);
        host.online.addAll(List.of(dad, lily));
        core.give(lily, 1, 1, false, true);
        core.give(dad, 6, 2, true, false);
        core.afterGift(dad);
        assertEquals(0, balance(lily), "Creative only: the moment, not the tokens");
        assertEquals(20, balance(dad));
        assertEquals(1, host.count("moment Lily"), "the visible moment is everyone's");
        core.give(lily, 1, 1, false, false); // later, in Survival
        core.afterGift(lily);
        assertEquals(20, balance(lily), "one gift outside Creative and she is paid");
    }

    @Test
    void aTestEditionNeverMintsAMiniAndOutsideTheWindowNothingHappens() throws Exception {
        host.settings = with(10, 40, "test");
        core.refresh();
        host.online.add(dad);
        core.give(dad, 6, 2, true, false);
        core.afterGift(dad);
        assertEquals(0, host.count("mini"), "a test edition has no Mini");
        assertEquals(1, host.count("moment Dad"));

        host.settings = with(10, 40, "2026");
        core.refresh();
        host.online.add(lily);
        core.give(lily, 6, 2, true, false);
        host.open = false; // the window closed before she was settled
        core.afterGift(lily);
        core.second();
        core.settle(lily);
        assertEquals(0, host.count("mini Lily"), "outside the window: nothing");
        assertEquals(0, host.count("paid Lily"));
    }

    @Test
    void theMiniIsMarkedFirstAndAMintThatFailsIsNotRetried() throws Exception {
        host.settings = with(10, 40, "2026");
        host.online.add(dad);
        host.mintOk = false; // at its cap
        core.give(dad, 6, 2, true, false);
        core.afterGift(dad);
        assertEquals(1, host.count("mini Dad"));
        assertEquals(1, counters.counter(dad, FeastRules.miniKey("2026")), "the mark was written first");
        host.mintOk = true;
        FeastCore again = new FeastCore(host, games, counters, meta);
        again.refresh();
        again.settle(dad);
        assertEquals(1, host.count("mini Dad"), "one try a helper, never a second Mini");
    }

    @Test
    void aFullBagWaitsForAFreeSlotAndTheMiniIsNeverMarkedBeforeIt() throws Exception {
        host.settings = with(10, 40, "2026");
        host.online.add(lily);
        host.full.add(lily); // her bag is full when the table fills
        core.give(lily, 6, 2, true, false);
        core.afterGift(lily);
        assertTrue(core.done());
        assertEquals(0, host.count("mini Lily"), "not minted: it would drop at her feet");
        assertEquals(0, counters.counter(lily, FeastRules.miniKey("2026")), "and not marked, so it stays owed");
        core.second();
        core.second();
        assertEquals(0, host.count("mini Lily"), "still full: still waiting");
        host.full.remove(lily);
        core.second();
        assertEquals(1, host.count("mini Lily"), "a free slot: it goes straight into the bag");
        assertEquals(1, counters.counter(lily, FeastRules.miniKey("2026")));
        core.second();
        assertEquals(1, host.count("mini Lily"), "once");
    }

    @Test
    void theRoomCeremonyIsOncePerHelper() throws Exception {
        host.settings = with(10, 40, "2026");
        core.give(dad, 6, 2, true, false);
        assertFalse(core.unseenHelper(dad), "not full yet");
        core.afterGift(dad);
        assertTrue(core.unseenHelper(dad));
        assertFalse(core.unseenHelper(lily), "not a helper");
        core.markRoomSeen(dad);
        assertFalse(core.unseenHelper(dad));
        FeastCore again = new FeastCore(host, games, counters, meta);
        again.refresh();
        assertFalse(again.unseenHelper(dad), "kept across a restart");
        FeastRules.FeastView v = again.view();
        assertTrue(v.done() && v.plates() == FeastRules.CELLS && v.total() == 12 && v.goal() == 10);
    }
}
