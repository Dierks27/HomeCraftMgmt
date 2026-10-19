package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.candy.CandyRules.Column;
import com.dierks.homecraft.arcade.candy.CandyRules.Floor;
import com.dierks.homecraft.arcade.candy.CandyRules.Offset;
import com.dierks.homecraft.arcade.candy.CandyRules.State;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.storage.TokenDao;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Candy Apple shower's rules (HALLOWEEN-SPEC D10-D18, §4.2, and §4.3's E1, E3-E5, E20): the window and
 * its slots, the last day and the restart hold; every skip, in order, with its text; the ring that never puts
 * a candy in reach of a player who doesn't move, its front arc, the columns and the pops; the requeue; the
 * golden rule; the tokens under their cap; and what counts toward the bag.
 */
class CandyRulesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    // ---- the window and the slots ----------------------------------------------------------

    @Test
    void theWindowIsOctoberBothEndsIncluded() {
        assertFalse(CandyRules.open(LocalDate.of(2026, 9, 30), FROM, TO));
        assertTrue(CandyRules.open(FROM, FROM, TO));
        assertTrue(CandyRules.open(DAY, FROM, TO));
        assertTrue(CandyRules.open(TO, FROM, TO), "the whole of 31 October");
        assertFalse(CandyRules.open(LocalDate.of(2026, 11, 1), FROM, TO), "off on 1 November");
        assertFalse(CandyRules.open(LocalDate.of(2027, 10, 15), FROM, TO), "next October needs new dates");
        assertFalse(CandyRules.open(null, FROM, TO));
        assertFalse(CandyRules.open(DAY, null, TO));
        assertFalse(CandyRules.open(DAY, FROM, null));
    }

    private static long due(String time) {
        return CandyRules.dueSlot(LocalDateTime.of(DAY, LocalTime.parse(time)), 20, 10, 10, false);
    }

    private static long dueLastDay(String time, int lastDayEvery) {
        return CandyRules.dueSlot(LocalDateTime.of(TO, LocalTime.parse(time)), 20, 10, lastDayEvery, true);
    }

    @Test
    void aSlotEveryTwentyMinutesFromTen() {
        assertEquals(CandyRules.NO_SLOT, due("00:00"));
        assertEquals(CandyRules.NO_SLOT, due("00:09"));
        long ten = due("00:10");
        assertEquals(CandyRules.slotId(DAY, 10), ten, "00:10 fires");
        assertEquals(DAY.toEpochDay() * 1440 + 10, ten, "the id is the local epoch day x 1440 + the minute");
        assertEquals(ten, due("00:11"), "00:11 is the same slot: the caller fires it once");
        assertEquals(ten, CandyRules.dueSlot(LocalDateTime.of(DAY, LocalTime.of(0, 11, 59)), 20, 10, 10, false));
        assertEquals(CandyRules.NO_SLOT, due("00:12"), "2 minutes late: wait for the next slot");
        assertEquals(CandyRules.NO_SLOT, due("00:20"));
        assertEquals(CandyRules.slotId(DAY, 30), due("00:30"));
        assertEquals(CandyRules.slotId(DAY, 50), due("00:50"));
        assertEquals(CandyRules.slotId(DAY, 19 * 60 + 30), due("19:30"));
        assertEquals(CandyRules.slotId(DAY, 23 * 60 + 50), due("23:50"), "the day's last slot");
        assertEquals(CandyRules.NO_SLOT, due("23:59"));
        assertNotEquals(due("00:10"), due("00:30"), "each slot its own id");
        assertEquals(CandyRules.slotId(DAY.plusDays(1), 10),
                CandyRules.dueSlot(LocalDateTime.of(DAY.plusDays(1), LocalTime.of(0, 10)), 20, 10, 10, false),
                "a new day, new ids");
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(19, 30)), CandyRules.slotStart(due("19:31")));
        assertEquals(CandyRules.NO_SLOT, CandyRules.dueSlot(null, 20, 10, 10, false));
    }

    @Test
    void theLastDayHasASlotEveryTenMinutesFromMidnight() {
        assertEquals(0, CandyRules.offsetOn(10, CandyRules.everyOn(20, 10, true)), "10 mod 10 = 0");
        assertEquals(CandyRules.slotId(TO, 0), dueLastDay("00:00", 10));
        assertEquals(CandyRules.slotId(TO, 16 * 60), dueLastDay("16:00", 10));
        assertEquals(CandyRules.slotId(TO, 16 * 60), dueLastDay("16:01", 10));
        assertEquals(CandyRules.NO_SLOT, dueLastDay("16:05", 10));
        assertEquals(CandyRules.slotId(TO, 16 * 60 + 10), dueLastDay("16:10", 10));
        assertEquals(CandyRules.slotId(TO, 16 * 60 + 20), dueLastDay("16:20", 10), "every 10, not every 20");
        assertEquals(CandyRules.slotId(TO, 23 * 60 + 50), dueLastDay("23:50", 10));
        // 0 = as every_minutes
        assertEquals(CandyRules.NO_SLOT, dueLastDay("16:00", 0));
        assertEquals(CandyRules.slotId(TO, 16 * 60 + 10), dueLastDay("16:10", 0));
        // and an ordinary day ignores the last day's interval
        assertEquals(CandyRules.NO_SLOT, due("16:00"));
        assertEquals(20, CandyRules.everyOn(20, 10, false));
        assertEquals(10, CandyRules.everyOn(20, 10, true));
        assertEquals(20, CandyRules.everyOn(20, 0, true));
    }

    @Test
    void aRestartHoldSkipsOnlyTheSlotJustBeforeTheRestart() {
        RestartHold hold = new RestartHold(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), CHICAGO, 5);
        long sixteen = dueLastDay("16:00", 10);
        assertTrue(CandyRules.heldByRestart(sixteen, CHICAGO, hold), "16:00 on the last day is the restart: skipped");
        assertFalse(CandyRules.heldByRestart(dueLastDay("16:10", 10), CHICAGO, hold), "16:10 fires");
        assertFalse(CandyRules.heldByRestart(dueLastDay("15:50", 10), CHICAGO, hold), "15:50 is before the hold");
        assertTrue(CandyRules.heldByRestart(dueLastDay("04:00", 10), CHICAGO, hold), "and 04:00");
        // the shipped :10/:30/:50 slots are never held
        for (int m = 10; m < 1440; m += 20) {
            assertFalse(CandyRules.heldByRestart(CandyRules.slotId(DAY, m), CHICAGO, hold), "minute " + m);
        }
        assertFalse(CandyRules.heldByRestart(sixteen, CHICAGO, null), "no hold");
        assertFalse(CandyRules.heldByRestart(sixteen, CHICAGO,
                new RestartHold(List.of(), CHICAGO, 5)), "no restart times");
    }

    @Test
    void theNextShower() {
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(19, 30)),
                CandyRules.nextSlot(LocalDateTime.of(DAY, LocalTime.of(19, 15)), FROM, TO, 20, 10, 10));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(19, 50)),
                CandyRules.nextSlot(LocalDateTime.of(DAY, LocalTime.of(19, 30)), FROM, TO, 20, 10, 10),
                "the slot that just started is not the next");
        assertEquals(LocalDateTime.of(DAY.plusDays(1), LocalTime.of(0, 10)),
                CandyRules.nextSlot(LocalDateTime.of(DAY, LocalTime.of(23, 55)), FROM, TO, 20, 10, 10));
        assertEquals(LocalDateTime.of(TO, LocalTime.of(0, 0)),
                CandyRules.nextSlot(LocalDateTime.of(TO.minusDays(1), LocalTime.of(23, 55)), FROM, TO, 20, 10, 10),
                "the last day starts at midnight");
        assertEquals(LocalDateTime.of(TO, LocalTime.of(16, 10)),
                CandyRules.nextSlot(LocalDateTime.of(TO, LocalTime.of(16, 1)), FROM, TO, 20, 10, 10));
        assertNull(CandyRules.nextSlot(LocalDateTime.of(TO, LocalTime.of(23, 55)), FROM, TO, 20, 10, 10),
                "after the last day's last slot: over");
        assertEquals(LocalDateTime.of(FROM, LocalTime.of(0, 10)),
                CandyRules.nextSlot(LocalDateTime.of(2026, 9, 15, 12, 0), FROM, TO, 20, 10, 10), "before the window");
        assertNull(CandyRules.nextSlot(LocalDateTime.of(DAY, LocalTime.NOON), TO, FROM, 20, 10, 10), "from > to");
    }

    // ---- the skips -------------------------------------------------------------------------

    /** A player who gets a shower (by default): alive, Survival, in the economy world, home, active, on the ground. */
    private static final class Who {
        boolean dead;
        boolean sleeping;
        GameMode mode = GameMode.SURVIVAL;
        boolean economyWorld = true;
        boolean gamesWorld;
        boolean inGame;
        boolean home = true;
        long idleMillis;
        boolean gliding;
        boolean flying;
        double feetY = 65.0;
        boolean inLava;
        boolean inWater;
        boolean inVehicle;
        int liveOwn;
        int liveServer;

        State state() {
            return new State(dead, sleeping, mode, economyWorld, gamesWorld, inGame, home, idleMillis, gliding,
                    flying, feetY, 64, inLava, inWater, inVehicle, liveOwn, liveServer);
        }

        String skip() {
            return CandyRules.skip(state());
        }
    }

    /** Everything wrong at once, so each step of {@link #theFirstFailureIsTheReason} clears one more. */
    private static Who everythingWrong() {
        Who w = new Who();
        w.dead = true;
        w.mode = GameMode.SPECTATOR;
        w.economyWorld = false;
        w.gamesWorld = true;
        w.inGame = true;
        w.home = false;
        w.idleMillis = 600_000;
        w.gliding = true;
        w.flying = true;
        w.feetY = 90;
        w.inLava = true;
        w.inWater = true;
        w.inVehicle = true;
        w.liveOwn = 12;
        w.liveServer = 120;
        return w;
    }

    @Test
    void everySkipWithItsText() {
        assertNull(new Who().skip());
        Who w = new Who();
        w.dead = true;
        assertEquals("not here", w.skip());
        w = new Who();
        w.sleeping = true;
        assertEquals("not here", w.skip());
        w = new Who();
        w.mode = GameMode.SPECTATOR;
        assertEquals("spectator", w.skip());
        w.mode = GameMode.CREATIVE;
        assertNull(w.skip(), "Creative gets the shower (never tokens)");
        w.mode = GameMode.ADVENTURE;
        assertNull(w.skip());
        w = new Who();
        w.economyWorld = false;
        assertEquals("not an economy world", w.skip());
        // an empty economy_enabled list makes every world an economy world, the Games world included
        w = new Who();
        w.gamesWorld = true;
        assertEquals("in the Games world", w.skip());
        w = new Who();
        w.inGame = true;
        assertEquals("in a game", w.skip());
        w = new Who();
        w.home = false;
        assertEquals("in a game", w.skip(), "on the way back from one");
        w = new Who();
        w.idleMillis = 5 * 60_000L;
        assertEquals("idle 5 min", w.skip());
        w.idleMillis = 4 * 60_000L + 59_000L;
        assertNull(w.skip(), "4:59 passes");
        w.idleMillis = 12 * 60_000L + 30_000L;
        assertEquals("idle 12 min", w.skip());
        w = new Who();
        w.gliding = true;
        assertEquals("flying", w.skip(), "gliding");
        w = new Who();
        w.mode = GameMode.CREATIVE;
        w.flying = true;
        w.feetY = 68.0;
        assertEquals("flying", w.skip(), "3 above the ground");
        w.feetY = 66.0;
        assertNull(w.skip(), "1 above: a Creative child hopping about");
        w.feetY = 67.0;
        assertNull(w.skip(), "2 above is not more than 2");
        w.flying = false;
        w.feetY = 80.0;
        assertNull(w.skip(), "falling, not flying");
        w = new Who();
        w.inLava = true;
        assertEquals("in lava", w.skip());
        w = new Who();
        w.inWater = true;
        assertEquals("in water", w.skip(), "a water-stream machine too");
        w = new Who();
        w.inVehicle = true;
        assertEquals("in a boat or minecart", w.skip());
        w = new Who();
        w.liveOwn = 12;
        assertEquals("12 candies already out", w.skip());
        w.liveOwn = 11;
        w.liveServer = 119;
        assertNull(w.skip());
        w.liveOwn = 0;
        w.liveServer = 120;
        assertEquals("120 candies already out on the server", w.skip());
    }

    @Test
    void theFirstFailureIsTheReason() {
        Who w = everythingWrong();
        assertEquals("not here", w.skip());
        w.dead = false;
        assertEquals("spectator", w.skip());
        w.mode = GameMode.SURVIVAL;
        assertEquals("not an economy world", w.skip());
        w.economyWorld = true;
        assertEquals("in the Games world", w.skip());
        w.gamesWorld = false;
        assertEquals("in a game", w.skip());
        w.inGame = false;
        assertEquals("in a game", w.skip(), "still on the way back");
        w.home = true;
        assertEquals("idle 10 min", w.skip());
        w.idleMillis = 0;
        assertEquals("flying", w.skip());
        w.gliding = false;
        assertEquals("flying", w.skip(), "flying high");
        w.flying = false;
        assertEquals("in lava", w.skip());
        w.inLava = false;
        assertEquals("in water", w.skip());
        w.inWater = false;
        assertEquals("in a boat or minecart", w.skip());
        w.inVehicle = false;
        assertEquals("12 candies already out", w.skip());
        w.liveOwn = 0;
        assertEquals("120 candies already out on the server", w.skip());
        w.liveServer = 0;
        assertNull(w.skip());
    }

    @Test
    void theGamesWorldIsMatchedByNameIgnoringCase() {
        assertTrue(CandyRules.gamesWorld("Games", List.of("games")));
        assertFalse(CandyRules.gamesWorld("world", List.of("games")));
        assertFalse(CandyRules.gamesWorld("world", List.of()));
        assertFalse(CandyRules.gamesWorld(null, List.of("games")));
        assertEquals(4, CandyRules.idleMinutes(299_999));
        assertEquals(5, CandyRules.idleMinutes(300_000));
        assertEquals(0, CandyRules.idleMinutes(-5));
    }

    // ---- the ring --------------------------------------------------------------------------

    /** Every offset a pass can return: drawn many times, at every yaw, as well as the whole candidate ring. */
    private static Set<Offset> everyOffset(int max) {
        Set<Offset> all = new HashSet<>(CandyRules.ringCandidates(CandyRules.RING_MIN, max));
        Random random = new Random(31);
        for (int yaw = -180; yaw < 360; yaw += 5) {
            for (int i = 0; i < 20; i++) {
                all.addAll(CandyRules.ringOffsets(yaw, CandyRules.RING_MIN, max, random));
            }
        }
        return all;
    }

    @Test
    void neverInReach() {
        // red-team M1 #4: a player who never moves never collects a candy, wherever in the block they stand
        for (int max : List.of(CandyRules.SKY_MAX, CandyRules.POP_MAX)) {
            for (Offset o : everyOffset(max)) {
                for (int i = 0; i <= 10; i++) {
                    for (int j = 0; j <= 10; j++) {
                        double px = Math.min(i / 10.0, 0.999);
                        double pz = Math.min(j / 10.0, 0.999);
                        double dx = o.bx() + 0.5 - px;
                        double dz = o.bz() + 0.5 - pz;
                        assertFalse(CandyRules.inReach(dx, dz),
                                o + " is in reach of a player standing at (" + px + ", " + pz + ")");
                    }
                }
            }
        }
        // and the model is real: one block out would be in reach
        assertTrue(CandyRules.inReach(1.0, 0.0), "the old 8 blocks around the feet");
        assertTrue(CandyRules.inReach(1.4, 1.4));
        assertFalse(CandyRules.inReach(1.5, 0.0));
    }

    @Test
    void theRingIsByBlockDistance() {
        for (Offset o : everyOffset(CandyRules.SKY_MAX)) {
            assertTrue(o.distance() >= 2 && o.distance() <= 4, "pass A: " + o);
        }
        for (Offset o : everyOffset(CandyRules.POP_MAX)) {
            assertTrue(o.distance() >= 2 && o.distance() <= 3, "pass B: " + o);
        }
        assertEquals(9 * 9 - 3 * 3, CandyRules.ringCandidates(2, 4).size());
        assertEquals(7 * 7 - 3 * 3, CandyRules.ringCandidates(2, 3).size());
        assertTrue(CandyRules.ringCandidates(0, 3).stream().allMatch(o -> o.distance() >= 2),
                "never the feet block or the 8 around it, whatever is asked");
    }

    @Test
    void theFrontArcComesFirst() {
        Random random = new Random(7);
        for (int run = 0; run < 50; run++) {
            List<Offset> tries = CandyRules.ringOffsets(0f, 2, 4, random);
            assertEquals(CandyRules.TRIES, tries.size());
            assertEquals(CandyRules.TRIES, new HashSet<>(tries).size(), "no offset twice");
            for (Offset o : tries.subList(0, CandyRules.FRONT_TRIES)) {
                // yaw 0 faces +Z: within 70 degrees of it
                double angle = Math.toDegrees(Math.atan2(o.bx(), o.bz()));
                assertTrue(Math.abs(angle) <= 70 + 1e-6, o + " is " + angle + " degrees off the look");
                assertTrue(o.bz() > 0, o + " is in front");
            }
        }
        // and at every yaw, for both passes, the first 6 are in front
        for (int yaw = -180; yaw < 360; yaw += 15) {
            for (int max : List.of(CandyRules.SKY_MAX, CandyRules.POP_MAX)) {
                List<Offset> tries = CandyRules.ringOffsets(yaw, 2, max, random);
                assertEquals(CandyRules.TRIES, tries.size());
                for (Offset o : tries.subList(0, CandyRules.FRONT_TRIES)) {
                    assertTrue(CandyRules.inFront(o, yaw, CandyRules.FRONT_ARC_DEGREES), o + " at yaw " + yaw);
                }
            }
        }
        // yaw 90 faces -X
        for (Offset o : CandyRules.ringOffsets(90f, 2, 4, random).subList(0, CandyRules.FRONT_TRIES)) {
            assertTrue(o.bx() < 0, o + " faces west");
        }
        assertTrue(CandyRules.inFront(new Offset(0, 2), 0f, 70));
        assertTrue(CandyRules.inFront(new Offset(2, 2), 0f, 70), "45 degrees");
        assertFalse(CandyRules.inFront(new Offset(2, 0), 0f, 70), "90 degrees");
        assertFalse(CandyRules.inFront(new Offset(0, -2), 0f, 70), "behind");
    }

    // ---- columns and pops ------------------------------------------------------------------

    private static final int FEET = 65;
    private static final int MIN = -64;

    private static Column column(int top) {
        return new Column(top, true, false, "GRASS_BLOCK", true, false, false, false, true);
    }

    @Test
    void theColumnsFromTheSky() {
        assertTrue(CandyRules.columnOk(column(64), FEET, MIN, false), "flat");
        assertFalse(CandyRules.columnOk(column(FEET + 4), FEET, MIN, false), "a roof");
        assertFalse(CandyRules.columnOk(new Column(FEET + 6, true, false, "OAK_LEAVES", true, false, false, false,
                true), FEET, MIN, false), "a canopy");
        assertTrue(CandyRules.columnOk(column(63), FEET, MIN, false), "one down");
        assertFalse(CandyRules.columnOk(column(62), FEET, MIN, false), "two down: a hole she can't climb out of");
        assertTrue(CandyRules.columnOk(column(66), FEET, MIN, false), "two up");
        assertFalse(CandyRules.columnOk(column(67), FEET, MIN, false), "three up");
        assertFalse(CandyRules.columnOk(column(MIN - 1), MIN, MIN, false), "the void");
        assertFalse(CandyRules.columnOk(new Column(64, false, true, "WATER", true, false, false, false, true),
                FEET, MIN, false), "water");
        assertFalse(CandyRules.columnOk(new Column(64, false, true, "LAVA", true, false, false, false, true),
                FEET, MIN, false), "lava");
        for (String hazard : List.of("CACTUS", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE", "POWDER_SNOW")) {
            assertFalse(CandyRules.columnOk(new Column(64, true, false, hazard, true, false, false, false, true),
                    FEET, MIN, false), hazard);
        }
        assertFalse(CandyRules.columnOk(new Column(64, true, false, "STONE", true, false, false, true, true),
                FEET, MIN, false), "lava beside");
        assertFalse(CandyRules.columnOk(new Column(64, true, false, "STONE", true, false, false, false, false),
                FEET, MIN, false), "behind a wall, a fence or a hedge");
        Column grass = new Column(64, true, false, "GRASS_BLOCK", false, true, false, false, true);
        assertFalse(CandyRules.columnOk(grass, FEET, MIN, false), "tall grass: not in the first round");
        assertTrue(CandyRules.columnOk(grass, FEET, MIN, true), "allowed in the second");
        Column fire = new Column(64, true, false, "NETHERRACK", false, true, true, false, true);
        assertFalse(CandyRules.columnOk(fire, FEET, MIN, true), "never fire");
        assertFalse(CandyRules.columnOk(new Column(64, true, false, "STONE", false, false, false, false, true),
                FEET, MIN, true), "a solid landing block");
    }

    @Test
    void theBandAndTheSpawnHeight() {
        assertTrue(CandyRules.inBand(FEET - 1, FEET));
        assertFalse(CandyRules.inBand(FEET - 2, FEET));
        assertTrue(CandyRules.inBand(FEET + 2, FEET));
        assertFalse(CandyRules.inBand(FEET + 3, FEET));
        assertEquals(64 + 1 + 8, CandyRules.spawnY(64, 320), "8 above the landing block");
        assertEquals(318, CandyRules.spawnY(314, 320), "under the build limit");
        assertTrue(CandyRules.hazard("cactus"));
        assertFalse(CandyRules.hazard("STONE"));
        assertFalse(CandyRules.hazard(null));
    }

    private static Floor floor() {
        return new Floor(true, "OAK_PLANKS", true, false, true, false, false, true);
    }

    @Test
    void thePopsFromTheFloor() {
        assertTrue(CandyRules.popOk(floor()), "a room's floor");
        assertFalse(CandyRules.popOk(new Floor(false, "AIR", true, false, true, false, false, true)), "no floor");
        assertFalse(CandyRules.popOk(new Floor(true, "MAGMA_BLOCK", true, false, true, false, false, true)));
        assertFalse(CandyRules.popOk(new Floor(true, "CAMPFIRE", true, false, true, false, false, true)));
        assertFalse(CandyRules.popOk(new Floor(true, "STONE", false, false, true, false, false, true)), "a wall");
        assertFalse(CandyRules.popOk(new Floor(true, "STONE", true, true, true, false, false, true)), "water on it");
        assertFalse(CandyRules.popOk(new Floor(true, "STONE", true, false, false, false, false, true)), "low roof");
        assertFalse(CandyRules.popOk(new Floor(true, "STONE", true, false, true, true, false, true)), "fire above");
        assertFalse(CandyRules.popOk(new Floor(true, "STONE", true, false, true, false, true, true)), "lava beside");
        assertFalse(CandyRules.popOk(new Floor(true, "STONE", true, false, true, false, false, false)), "out of sight");
        assertEquals(0, (int) CandyRules.POP_STEPS.get(0), "the player's own floor first");
        assertEquals(Set.of(-1, 0, 1), new HashSet<>(CandyRules.POP_STEPS));
        assertEquals(64.6, CandyRules.popY(63), 1e-9, "0.6 above the floor");
    }

    // ---- the requeue -----------------------------------------------------------------------

    @Test
    void anAppleWithNoSpotIsRetriedThenDroppedUncounted() {
        // swimming, a 1-wide bridge, a pillar: no pass ever finds a spot
        java.util.function.BooleanSupplier placed = () -> false;
        int requeued = 0;
        int attempts = 0;
        int spawned = 0;
        int counted = 0;
        while (true) {
            attempts++;
            if (placed.getAsBoolean()) {
                spawned++;
                counted++;
                break;
            }
            if (!CandyRules.requeue(requeued)) {
                break;
            }
            requeued++;
        }
        assertEquals(1 + CandyRules.MAX_REQUEUES, attempts, "the first try and 10 retries, a second apart");
        assertEquals(10, requeued);
        assertEquals(0, spawned, "then dropped silently");
        assertEquals(0, counted, "and nothing counts");
        assertTrue(CandyRules.requeue(0));
        assertTrue(CandyRules.requeue(9));
        assertFalse(CandyRules.requeue(10));
        assertEquals(20, CandyRules.REQUEUE_TICKS, "1 s later");
    }

    // ---- golden ----------------------------------------------------------------------------

    private static Random fixed(double roll) {
        return new Random() {
            @Override
            public double nextDouble() {
                return roll;
            }
        };
    }

    @Test
    void theGoldenRule() {
        assertTrue(CandyRules.golden(0.039, 4, false, true), "a 0.039 roll at 4%");
        assertFalse(CandyRules.golden(0.04, 4, false, true), "0.04 is not under 4%");
        assertTrue(CandyRules.golden(fixed(0.039), 4, false, true));
        assertFalse(CandyRules.golden(fixed(0.04), 4, false, true));
        assertTrue(CandyRules.golden(0.99, 4, true, false), "the 3rd apple while no golden is seen");
        assertFalse(CandyRules.golden(0.99, 4, true, true), "not once one is");
        assertFalse(CandyRules.golden(0.0, 0, false, true), "0% never");
        assertTrue(CandyRules.golden(0.9999, 100, false, true), "100% always");
        assertEquals(2, CandyRules.goldenIndex(6), "the 3rd apple");
        assertEquals(2, CandyRules.goldenIndex(3));
        assertEquals(1, CandyRules.goldenIndex(2), "a shorter shower: its last");
        assertEquals(0, CandyRules.goldenIndex(1));
    }

    @Test
    void everyShowerHasAGoldenUntilOneIsPickedUp() {
        Random never = fixed(0.99);
        for (int shower = 0; shower < 5; shower++) {
            int goldens = 0;
            for (int i = 0; i < 6; i++) {
                boolean g = CandyRules.golden(never, 4, i == CandyRules.goldenIndex(6), false);
                assertEquals(i == 2, g, "shower " + shower + ", apple " + (i + 1));
                goldens += g ? 1 : 0;
            }
            assertEquals(1, goldens, "at most one extra golden a shower");
        }
        for (int i = 0; i < 6; i++) {
            assertFalse(CandyRules.golden(never, 4, i == CandyRules.goldenIndex(6), true), "seen: only the roll");
        }
    }

    // ---- tokens ----------------------------------------------------------------------------

    @Test
    void tokensToPayWithItsCap() {
        assertEquals(5, CandyRules.tokensToPay(0, 5, 10, true, true));
        assertEquals(2, CandyRules.tokensToPay(8, 5, 10, true, true), "cut to what is left of the day");
        assertEquals(0, CandyRules.tokensToPay(10, 5, 10, true, true));
        assertEquals(0, CandyRules.tokensToPay(12, 5, 10, true, true), "never negative");
        assertEquals(0, CandyRules.tokensToPay(0, 5, 0, true, true), "cap 0");
        assertEquals(0, CandyRules.tokensToPay(0, 0, 10, true, true), "golden_tokens 0");
        assertEquals(0, CandyRules.tokensToPay(0, 5, 10, false, true), "window closed: a stray candy pays nothing");
        assertEquals(0, CandyRules.tokensToPay(0, 5, 10, true, CandyRules.paysTokens(GameMode.CREATIVE)), "Creative");
        assertTrue(CandyRules.paysTokens(GameMode.SURVIVAL));
        assertTrue(CandyRules.paysTokens(GameMode.ADVENTURE));
        assertFalse(CandyRules.paysTokens(GameMode.SPECTATOR));
        assertFalse(CandyRules.paysTokens(null));
    }

    @Test
    void theCapIsReadFromTheLedger() {
        UUID p = UUID.randomUUID();
        List<TokenDao.SourceTotal> totals = List.of(new TokenDao.SourceTotal(p, "QUEST", 20, 0),
                new TokenDao.SourceTotal(p, "EVENT_CANDY", 8, 0), new TokenDao.SourceTotal(p, "PRIZE", 0, 250));
        assertEquals(8, CandyRules.paidToday(totals), "only Candy Apples' lines");
        assertEquals(0, CandyRules.paidToday(List.of()));
        assertEquals(0, CandyRules.paidToday(null));
        // a day of goldens: 5, 5, then nothing more (the ceiling is 10 a day)
        long paid = 0;
        int[] got = new int[4];
        for (int i = 0; i < got.length; i++) {
            got[i] = CandyRules.tokensToPay(paid, 5, 10, true, true);
            paid += got[i];
        }
        assertEquals(List.of(5, 5, 0, 0), List.of(got[0], got[1], got[2], got[3]));
    }

    // ---- what counts -----------------------------------------------------------------------

    @Test
    void crossedAndMilestones() {
        assertTrue(CandyRules.crossed(30, 31, 31));
        assertFalse(CandyRules.crossed(31, 32, 31), "once");
        assertTrue(CandyRules.crossed(29, 31, 31), "a stack of 2");
        assertFalse(CandyRules.crossed(0, 30, 31));
        assertTrue(CandyRules.crossed(0, 200, 31), "an admin's add");
        assertTrue(CandyRules.milestone(9, 10, 31));
        assertFalse(CandyRules.milestone(10, 11, 31));
        assertTrue(CandyRules.milestone(19, 21, 31));
        assertTrue(CandyRules.milestone(29, 30, 31));
        assertFalse(CandyRules.milestone(30, 31, 31), "the goal has its own fanfare");
        assertFalse(CandyRules.milestone(29, 30, 30), "a goal of 30: the bag's, not a milestone's");
        assertFalse(CandyRules.milestone(41, 42, 31), "none after the goal");
        assertEquals(17 / 31f, CandyRules.progress(17, 31), 1e-6);
        assertEquals(1f, CandyRules.progress(35, 31), "never above 1");
        assertEquals(0f, CandyRules.progress(0, 31));
    }

    @Test
    void onlyAnOwnersShowerCandyCounts() {
        // E1: a candy dropped with Q is a new, untagged entity
        assertEquals(0, CandyRules.counts(false, true, 1), "untagged");
        assertEquals(0, CandyRules.counts(true, false, 1), "someone else's");
        assertEquals(1, CandyRules.counts(true, true, 1));
        assertEquals(0, CandyRules.counts(true, true, 0), "nothing picked up");
        // a full bag still counts her own candy at her feet
        assertTrue(CandyRules.touchCounts(true, 0.8, false), "owned, within 1.0, no room: counted");
        assertTrue(CandyRules.touchCounts(true, 1.0, false));
        assertFalse(CandyRules.touchCounts(true, 0.8, true), "room: left to vanilla");
        assertFalse(CandyRules.touchCounts(false, 0.5, false), "another player's candy");
        assertFalse(CandyRules.touchCounts(true, 1.01, false), "too far");
    }

    @Test
    void theKeysCarryTheYear() {
        assertEquals("candy:2026", CandyRules.counterKey(2026));
        assertEquals("candy:2026:golden_seen", CandyRules.goldenSeenKey(2026));
        assertEquals("candy:2026:bag", CandyRules.bagKey(2026));
        assertEquals("candy:2027", CandyRules.counterKey(2027), "next October starts from 0");
        UUID owner = UUID.randomUUID();
        long slot = CandyRules.slotId(DAY, 10);
        assertEquals(owner + ":" + slot, CandyRules.dropTag(owner, slot));
        assertEquals(owner, CandyRules.dropOwner(CandyRules.dropTag(owner, slot)));
        assertNull(CandyRules.dropOwner("not-a-uuid:5"));
        assertNull(CandyRules.dropOwner(null));
    }
}
