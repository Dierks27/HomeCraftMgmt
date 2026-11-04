package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyRules.Offset;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.CatchStep;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.Ground;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.Kind;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.Leash;
import com.dierks.homecraft.arcade.turkey.TurkeyRules.Tap;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.storage.TokenDao;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catch the Turkey's rules: the six runs a day and the restart hold, the ids and keys that stop a second turkey
 * or a second prize, who gets a turkey (the Overworld only), where it appears, which taps catch, and the tokens
 * and the Turkey Mini's chance.
 */
class TurkeyRulesTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final UUID OWNER = UUID.fromString("0a1b2c3d-4e5f-6789-abcd-ef0123456789");

    private static long due(String hhmm) {
        return CandyRules.dueSlot(LocalDateTime.of(DAY, LocalTime.parse(hhmm)), 240, 50, 0, false);
    }

    // ---- when -------------------------------------------------------------------------------

    @Test
    void sixRunsADayAt240MinutesFrom50() {
        List<Long> slots = TurkeyRules.slotsOn(DAY, 240, 50);
        List<Long> expected = new ArrayList<>();
        for (int m : new int[]{50, 290, 530, 770, 1010, 1250}) {
            expected.add(CandyRules.slotId(DAY, m));
        }
        assertEquals(expected, slots, "00:50, 04:50, 08:50, 12:50, 16:50, 20:50");
        assertEquals(CandyRules.slotId(DAY, 50), due("00:50"));
        assertEquals(CandyRules.slotId(DAY, 50), due("00:51"), "a run is due in its first two minutes");
        assertEquals(CandyRules.NO_SLOT, due("00:52"));
        assertEquals(CandyRules.slotId(DAY, 16 * 60 + 50), due("16:50"));
        assertEquals(CandyRules.NO_SLOT, due("01:50"), "not every hour");
        assertEquals(List.of(), TurkeyRules.slotsOn(null, 240, 50));
        assertEquals(List.of(), TurkeyRules.slotsOn(DAY, 0, 50));
    }

    @Test
    void aRunJustBeforeARestartIsSkipped() {
        RestartHold shipped = new RestartHold(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), CHICAGO, 5);
        for (long slot : TurkeyRules.slotsOn(DAY, 240, 50)) {
            assertFalse(CandyRules.heldByRestart(slot, CHICAGO, shipped), "the shipped runs miss 04:00 and 16:00");
        }
        RestartHold close = new RestartHold(List.of(LocalTime.of(4, 52)), CHICAGO, 5);
        assertTrue(CandyRules.heldByRestart(due("04:50"), CHICAGO, close), "04:50 with a restart at 04:52");
        assertFalse(CandyRules.heldByRestart(due("08:50"), CHICAGO, close));
    }

    // ---- ids and keys -----------------------------------------------------------------------

    @Test
    void runIdsAndKeys() {
        long slot = CandyRules.slotId(DAY, 50);
        assertEquals("s" + slot, TurkeyRules.runId(slot));
        String welcome = TurkeyRules.welcomeRunId(DAY.toEpochDay(), OWNER);
        assertEquals("w" + DAY.toEpochDay() + "-0a1b2c3d", welcome, "the day and the owner's first 8 hex digits");
        assertTrue(TurkeyRules.welcomeRun(welcome));
        assertFalse(TurkeyRules.welcomeRun(TurkeyRules.runId(slot)));
        assertEquals("t1234", TurkeyRules.testRunId(1234));
        assertEquals("turkey:2026:s" + slot, TurkeyRules.claimKey(2026, TurkeyRules.runId(slot)));
        assertEquals("turkey:2026:" + welcome, TurkeyRules.claimKey(2026, welcome));
        assertEquals("turkey:2026:welcome:" + DAY.toEpochDay(), TurkeyRules.welcomeKey(2026, DAY.toEpochDay()));
        assertEquals("turkey:2026:mini", TurkeyRules.miniKey(2026));
        assertEquals("turkey_mini_2026", TurkeyRules.miniId(2026), "the Holiday Collection's row id");
        assertEquals("s5|" + OWNER, TurkeyRules.tag("s5", OWNER));
        assertEquals("s5", TurkeyRules.tagRun(TurkeyRules.tag("s5", OWNER)));
        assertNull(TurkeyRules.tagRun("nonsense"));
        assertNull(TurkeyRules.tagRun(null));
    }

    // ---- who --------------------------------------------------------------------------------

    private static CandyRules.State state(GameMode mode, boolean economy, boolean games, boolean inGame, long idleMs,
                                          boolean water, int liveOwn) {
        return new CandyRules.State(false, false, mode, economy, games, inGame, true, idleMs, false, false, 64, 63,
                false, water, false, liveOwn, liveOwn);
    }

    @Test
    void theCandySkipsThenTheOverworldOnly() {
        assertNull(TurkeyRules.skip(state(GameMode.SURVIVAL, true, false, false, 0, false, 0), true));
        assertNull(TurkeyRules.skip(state(GameMode.CREATIVE, true, false, false, 0, false, 0), true),
                "Creative gets a turkey (and no tokens)");
        assertEquals(TurkeyRules.NOT_OVERWORLD,
                TurkeyRules.skip(state(GameMode.SURVIVAL, true, false, false, 0, false, 0), false),
                "never in the Nether or the End");
        assertEquals("not an economy world", TurkeyRules.skip(state(GameMode.SURVIVAL, false, false, false, 0, false, 0), true));
        assertEquals("in the Games world", TurkeyRules.skip(state(GameMode.SURVIVAL, true, true, false, 0, false, 0), true));
        assertEquals("in a game", TurkeyRules.skip(state(GameMode.SURVIVAL, true, false, true, 0, false, 0), true));
        assertEquals("idle 5 min", TurkeyRules.skip(state(GameMode.SURVIVAL, true, false, false, 300_000, false, 0), true));
        assertEquals("in water", TurkeyRules.skip(state(GameMode.SURVIVAL, true, false, false, 0, true, 0), true));
        assertNull(TurkeyRules.skip(state(GameMode.SURVIVAL, true, false, false, 0, false, 500), true),
                "the shower's live-candy caps don't stop a turkey");
    }

    @Test
    void noTurkeyInARunAlreadyCaughtAndNoSecondWelcome() {
        assertTrue(TurkeyRules.spawnFor(false, false, Kind.SCHEDULED));
        assertFalse(TurkeyRules.spawnFor(true, false, Kind.SCHEDULED), "claimed (a restart): none");
        assertTrue(TurkeyRules.spawnFor(false, true, Kind.SCHEDULED), "a welcome had today doesn't stop a run");
        assertTrue(TurkeyRules.spawnFor(false, false, Kind.WELCOME));
        assertFalse(TurkeyRules.spawnFor(false, true, Kind.WELCOME), "one welcome turkey a day");
        assertFalse(TurkeyRules.spawnFor(true, false, Kind.WELCOME));
        assertEquals(6, TurkeyRules.MAX_TURKEYS_PER_RUN);
        assertEquals(90_000L, TurkeyRules.WELCOME_DELAY_MS);
        assertEquals(60_000L, TurkeyRules.JOIN_MIN_LEFT_MS);
    }

    // ---- where ------------------------------------------------------------------------------

    @Test
    void theSpotsAreInFrontFourToSixBlocksOut() {
        List<Offset> south = TurkeyRules.spotOffsets(0f); // yaw 0 faces +Z
        assertFalse(south.isEmpty());
        assertEquals(new Offset(0, 5), south.get(0), "straight ahead at the middle distance first");
        for (Offset o : south) {
            assertTrue(o.distance() >= 4 && o.distance() <= 6, o + " is 4-6 blocks out");
            assertTrue(CandyRules.inFront(o, 0f, 60), o + " is within 60 degrees of the look");
            assertTrue(o.bz() > 0, o + " is in front, never behind");
        }
        assertEquals(new Offset(-5, 0), TurkeyRules.spotOffsets(90f).get(0), "yaw 90 faces -X");
        assertEquals(south, TurkeyRules.spotOffsets(0f), "the same order every time: no randomness");
    }

    private static Ground ground(boolean loaded, String floor, int clear, boolean wet, boolean lava, boolean sight) {
        return new Ground(loaded, !"WATER".equals(floor), floor, clear, wet, lava, sight);
    }

    @Test
    void aSpotNeedsDryGroundRoomAboveAndSight() {
        assertEquals(3, TurkeyRules.headroom(2.0), "1.4 blocks tall at 2.0: two, and one more");
        assertEquals(4, TurkeyRules.headroom(3.0));
        assertEquals(2, TurkeyRules.headroom(1.0));
        assertTrue(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 3, false, false, true), 2.0));
        assertFalse(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 2, false, false, true), 2.0), "low headroom at 2.0");
        assertTrue(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 3, false, false, true), 1.0));
        assertFalse(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 3, false, false, true), 3.0), "low headroom at 3.0");
        assertTrue(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 4, false, false, true), 3.0));
        assertFalse(TurkeyRules.spotOk(ground(true, "WATER", 3, true, false, true), 2.0), "water");
        assertFalse(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 3, true, false, true), 2.0), "water or fire above");
        assertFalse(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 3, false, true, true), 2.0), "lava beside");
        assertFalse(TurkeyRules.spotOk(ground(true, "GRASS_BLOCK", 3, false, false, false), 2.0), "out of sight");
        assertFalse(TurkeyRules.spotOk(ground(false, "GRASS_BLOCK", 3, false, false, true), 2.0), "unloaded chunk");
        assertFalse(TurkeyRules.spotOk(ground(true, "MAGMA_BLOCK", 3, false, false, true), 2.0), "a hazard");
        assertFalse(TurkeyRules.spotOk(ground(true, "CACTUS", 3, false, false, true), 2.0));
        assertFalse(TurkeyRules.spotOk(null, 2.0));
    }

    @Test
    void theLeash() {
        assertEquals(Leash.STAY, TurkeyRules.leash(true, false, false, 0));
        assertEquals(Leash.STAY, TurkeyRules.leash(true, false, false, 6));
        assertEquals(Leash.BRING_BACK, TurkeyRules.leash(true, false, false, 6.5), "past 6: walked back");
        assertEquals(Leash.BRING_BACK, TurkeyRules.leash(true, false, false, 24));
        assertEquals(Leash.LOST, TurkeyRules.leash(true, false, false, 24.5), "past 24: it ends");
        assertEquals(Leash.LOST, TurkeyRules.leash(false, false, false, 1), "gone (its chunk unloaded)");
        assertEquals(Leash.LOST, TurkeyRules.leash(true, true, false, 1), "in water");
        assertEquals(Leash.LOST, TurkeyRules.leash(true, false, true, 1), "in lava");
        assertEquals(Leash.LOST, TurkeyRules.leash(true, false, false, -1), "another world");
    }

    // ---- a catch ----------------------------------------------------------------------------

    private static Tap tap(boolean mainHand, Long last, long now, boolean economy, boolean games, boolean home,
                           boolean session, boolean welcome, boolean owner) {
        return new Tap(mainHand, last, now, economy, games, home, session, welcome, owner);
    }

    @Test
    void whichTapsCatch() {
        assertEquals(CatchStep.CATCH, TurkeyRules.catchStep(tap(true, null, 1000, true, false, true, false, false, false)),
                "anyone may catch a scheduled turkey");
        assertEquals(CatchStep.IGNORE, TurkeyRules.catchStep(tap(false, null, 1000, true, false, true, false, false, true)),
                "the off hand");
        assertEquals(CatchStep.IGNORE, TurkeyRules.catchStep(tap(true, 1000L, 1599, true, false, true, false, false, true)),
                "599 ms after the last tap on it: a held tap");
        assertEquals(CatchStep.CATCH, TurkeyRules.catchStep(tap(true, 1000L, 1600, true, false, true, false, false, true)),
                "600 ms: counts");
        assertEquals(CatchStep.IGNORE, TurkeyRules.catchStep(tap(true, null, 1000, true, true, true, false, false, true)),
                "the Games world");
        assertEquals(CatchStep.IGNORE, TurkeyRules.catchStep(tap(true, null, 1000, true, false, true, true, false, true)),
                "in a session");
        assertEquals(CatchStep.IGNORE, TurkeyRules.catchStep(tap(true, null, 1000, true, false, false, false, false, true)),
                "on the way back from a game");
        assertEquals(CatchStep.IGNORE, TurkeyRules.catchStep(tap(true, null, 1000, false, false, true, false, false, true)),
                "not an economy world");
        assertEquals(CatchStep.NOT_YOURS, TurkeyRules.catchStep(tap(true, null, 1000, true, false, true, false, true, false)),
                "someone else's welcome turkey: a cluck");
        assertEquals(CatchStep.CATCH, TurkeyRules.catchStep(tap(true, null, 1000, true, false, true, false, true, true)),
                "your own welcome turkey");
    }

    @Test
    void tokensUnderTheirOwnCap() {
        UUID p = UUID.randomUUID();
        List<TokenDao.SourceTotal> totals = List.of(new TokenDao.SourceTotal(p, "EVENT_TURKEY", 3, 0),
                new TokenDao.SourceTotal(p, "EVENT_CANDY", 8, 0), new TokenDao.SourceTotal(p, "EVENT_HARVEST", 5, 0),
                new TokenDao.SourceTotal(p, "QUEST", 20, 0));
        assertEquals(3, TurkeyRules.paidToday(totals), "only the turkey's own lines");
        assertEquals(0, TurkeyRules.paidToday(null));
        assertEquals(3, TurkeyRules.tokensToPay(0, 3, 6, true, true, false), "first catch: 3");
        assertEquals(3, TurkeyRules.tokensToPay(3, 3, 6, true, true, false), "second: 3");
        assertEquals(0, TurkeyRules.tokensToPay(6, 3, 6, true, true, false), "third: the cap of 6");
        assertEquals(1, TurkeyRules.tokensToPay(5, 3, 6, true, true, false), "cut to what is left");
        assertEquals(0, TurkeyRules.tokensToPay(0, 3, 6, true, CandyRules.paysTokens(GameMode.CREATIVE), false),
                "Creative: none");
        assertEquals(0, TurkeyRules.tokensToPay(0, 3, 6, false, true, false), "out of the window: none");
        assertEquals(0, TurkeyRules.tokensToPay(0, 3, 6, true, true, true), "a test run: none");
    }

    @Test
    void theExtraTurkeyMini() {
        assertTrue(TurkeyRules.extraMini(0.0, 5, false, false, true));
        assertTrue(TurkeyRules.extraMini(0.0499, 5, false, false, true));
        assertFalse(TurkeyRules.extraMini(0.05, 5, false, false, true), "5 in 100");
        assertFalse(TurkeyRules.extraMini(0.0, 5, true, false, true), "one extra a season");
        assertFalse(TurkeyRules.extraMini(0.0, 5, false, true, true), "never in a test run");
        assertFalse(TurkeyRules.extraMini(0.0, 0, false, false, true), "0 = off");
        assertTrue(TurkeyRules.extraMini(0.99, 100, false, false, true));
        assertFalse(TurkeyRules.extraMini(0.0, 100, false, false, false), "a full bag: no roll, the chance kept");
    }

    @Test
    void aWelcomeInsideTheRestartHoldOrCutShortByTheRestartWaits() {
        ZoneId z = ZoneId.of("America/Chicago");
        RestartHold hold = new RestartHold(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), z, 5);
        long run = 3 * 60_000L;
        long at1558 = LocalDateTime.of(2026, 11, 24, 15, 58).atZone(z).toInstant().toEpochMilli();
        long at1553 = LocalDateTime.of(2026, 11, 24, 15, 53).atZone(z).toInstant().toEpochMilli();
        long at1556 = LocalDateTime.of(2026, 11, 24, 15, 56).atZone(z).toInstant().toEpochMilli();
        long at1601 = LocalDateTime.of(2026, 11, 24, 16, 1).atZone(z).toInstant().toEpochMilli();
        assertTrue(TurkeyRules.welcomeHeld(at1558, run, hold), "inside the hold: she gets it after the restart");
        RestartHold short1 = new RestartHold(List.of(LocalTime.of(16, 0)), z, 1);
        assertTrue(TurkeyRules.welcomeHeld(at1558, run, short1), "a 1-minute hold, but the run would end after 16:00");
        assertFalse(TurkeyRules.welcomeHeld(at1556, run, short1), "15:56 + 3 minutes ends at 15:59: given");
        assertFalse(TurkeyRules.welcomeHeld(at1553, run, hold), "15:53: it ends at 15:56, before the hold");
        assertFalse(TurkeyRules.welcomeHeld(at1601, run, hold), "after the restart's minute: given");
        assertTrue(TurkeyRules.welcomeHeld(at1556, run, hold));
        assertFalse(TurkeyRules.welcomeHeld(at1558, run, null), "no restart times: never held");
        assertFalse(TurkeyRules.welcomeHeld(at1558, run, new RestartHold(List.of(), z, 5)));
    }
}
