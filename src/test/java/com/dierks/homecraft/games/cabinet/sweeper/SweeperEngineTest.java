package com.dierks.homecraft.games.cabinet.sweeper;

import com.dierks.homecraft.games.cabinet.CabinetGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creeper Sweeper's rules, with no server.
 *
 * <p>Pinned here: the first dig is never a creeper (Classic places after it, away from it and
 * its neighbours; the daily board comes with a safe opening dug); the creeper count is exact;
 * zeros flood-fill and stop at numbers (and at flags); numbers are right and never leak from an
 * undug square; clearing every safe square wins and a creeper is a Boom that shows every creeper;
 * flags toggle, protect a square and never outnumber the creepers; the clock starts on the first
 * dig and freezes at the end; the daily board is the same for the same seed; and the milestone
 * times compare in milliseconds.
 */
class SweeperEngineTest {

    private static final long T0 = 1_000_000L;

    @Test
    void theFirstDigAndItsNeighboursAreNeverACreeperOnAClassicBoard() {
        for (int mines : new int[]{6, 10, 30}) {
            for (long seed = 0; seed < 40; seed++) {
                for (int cell = 0; cell < SweeperEngine.CELLS; cell++) {
                    SweeperEngine board = SweeperEngine.classic(mines, seed * 101 + cell);
                    assertFalse(board.placed(), "a Classic board has no creepers before the first dig");
                    board.dig(cell, T0);
                    assertNotEquals(SweeperEngine.State.LOST, board.state(),
                            "the first dig on " + cell + " (seed " + seed + ", " + mines + " creepers) is safe");
                    for (int c = 0; c < SweeperEngine.CELLS; c++) {
                        if (SweeperEngine.touching(c, cell)) {
                            assertFalse(board.mineAt(c), "no creeper next to the first dig, so it always opens an area");
                        }
                    }
                    assertEquals(mines, count(board), "exactly the configured number of creepers is placed");
                }
            }
        }
    }

    @Test
    void theFirstDigOpensAnAreaBecauseItHasNoCreeperAroundIt() {
        SweeperEngine board = SweeperEngine.classic(8, 7);
        board.dig(22, T0);
        assertEquals(0, board.number(22), "the first dig's neighbours are all clear, so it reads 0");
        for (int n : SweeperEngine.neighbours(22)) {
            assertEquals(SweeperEngine.Look.OPEN, board.look(n), "a zero opens every square around it");
        }
    }

    @Test
    void aZeroFloodFillsItsAreaAndStopsAtTheNumbers() {
        // A wall of creepers down column 4: digging the left side opens columns 0-3 and nothing past the wall.
        SweeperEngine board = SweeperEngine.fixed(1, 4, 13, 22, 31, 40);
        board.dig(0, T0);
        for (int y = 0; y < SweeperEngine.ROWS; y++) {
            for (int x = 0; x < SweeperEngine.COLS; x++) {
                int c = y * SweeperEngine.COLS + x;
                if (x <= 3) {
                    assertEquals(SweeperEngine.Look.OPEN, board.look(c), "the flood reaches (" + x + "," + y + ")");
                } else {
                    assertEquals(SweeperEngine.Look.HIDDEN, board.look(c), "the flood stops at the wall: (" + x + "," + y + ")");
                }
            }
        }
        assertEquals(0, board.number(2), "column 2 touches no creeper");
        assertEquals(2, board.number(3), "a top-edge square beside the wall touches two creepers");
        assertEquals(3, board.number(12), "a middle square beside the wall touches three");
        assertEquals(SweeperEngine.State.LIVE, board.state(), "the right side is still to dig");
    }

    @Test
    void aFloodFillLeavesFlaggedSquaresAlone() {
        SweeperEngine board = SweeperEngine.fixed(1, 44);
        assertTrue(board.toggleFlag(1), "a flag goes on an undug square");
        board.dig(0, T0);
        assertEquals(SweeperEngine.Look.FLAG, board.look(1), "the flood doesn't dig under a flag");
        assertEquals(SweeperEngine.State.LIVE, board.state(), "the flagged safe square still has to be dug");
        board.toggleFlag(1);
        board.dig(1, T0 + 5);
        assertEquals(SweeperEngine.State.WON, board.state(), "unflagging and digging it clears the board");
    }

    @Test
    void numbersCountTheCreepersAroundAndNeverShowOnAnUndugSquare() {
        SweeperEngine board = SweeperEngine.fixed(1, 0, 2, 20);
        assertEquals(-1, board.number(1), "an undug square tells nothing");
        board.dig(1, T0);
        assertEquals(2, board.number(1), "square 1 sits between the creepers on 0 and 2");
        board.dig(10, T0);
        assertEquals(3, board.number(10), "square 10 touches all three: 0, 2 and 20");
        board.dig(11, T0);
        assertEquals(2, board.number(11), "square 11 touches 2 and 20");
    }

    @Test
    void everyUndugSquareLooksTheSameWhateverIsUnderIt() {
        SweeperEngine board = SweeperEngine.fixed(1, 10, 20, 30);
        board.dig(0, T0);
        for (int c = 0; c < SweeperEngine.CELLS; c++) {
            if (board.look(c) != SweeperEngine.Look.OPEN) {
                assertEquals(SweeperEngine.Look.HIDDEN, board.look(c), "square " + c + " shows nothing before the end");
                assertEquals(-1, board.number(c), "square " + c + " leaks no number");
            }
        }
    }

    @Test
    void diggingEverySafeSquareWinsAndFlagsTheCreepers() {
        SweeperEngine board = SweeperEngine.fixed(1, 10, 44);
        for (int c = 0; c < SweeperEngine.CELLS; c++) {
            if (c != 10 && c != 44) {
                board.dig(c, T0 + c);
            }
        }
        assertEquals(SweeperEngine.State.WON, board.state(), "every safe square dug is a clear");
        assertEquals(SweeperEngine.Look.FLAG, board.look(10), "a cleared board shows its creepers flagged");
        assertEquals(SweeperEngine.Look.FLAG, board.look(44), "every creeper, not just the first");
        assertFalse(board.dig(10, T0 + 100), "nothing can be dug on a finished board");
        assertEquals(SweeperEngine.State.WON, board.state(), "a finished board stays finished");
    }

    @Test
    void diggingACreeperIsABoomThatShowsEveryCreeper() {
        SweeperEngine board = SweeperEngine.fixed(1, 10, 20, 30);
        board.toggleFlag(30);
        board.toggleFlag(0);
        board.dig(44, T0);
        board.dig(10, T0 + 500);
        assertEquals(SweeperEngine.State.LOST, board.state(), "a creeper dug ends the board");
        assertEquals(SweeperEngine.Look.BOOM, board.look(10), "the dug creeper is the Boom");
        assertEquals(SweeperEngine.Look.CREEPER, board.look(20), "an unflagged creeper is shown");
        assertEquals(SweeperEngine.Look.FLAG, board.look(30), "a right flag stays a flag");
        assertEquals(SweeperEngine.Look.WRONG_FLAG, board.look(0), "a flag with no creeper under it is shown as wrong");
        assertFalse(board.toggleFlag(5), "no flags on a finished board");
        assertEquals(500, board.timeMs(T0 + 9_999), "the clock stopped at the Boom");
    }

    @Test
    void flagsToggleProtectASquareAndNeverOutnumberTheCreepers() {
        SweeperEngine board = SweeperEngine.fixed(1, 10, 20);
        assertTrue(board.toggleFlag(5), "flag on");
        assertEquals(1, board.creepersLeft(), "the counter goes down with each flag");
        assertFalse(board.dig(5, T0), "a flagged square can't be dug by accident");
        assertEquals(SweeperEngine.Look.FLAG, board.look(5), "it is still flagged");
        assertTrue(board.toggleFlag(6), "a second flag");
        assertFalse(board.toggleFlag(7), "no more flags than creepers");
        assertTrue(board.toggleFlag(5), "flag off");
        assertEquals(1, board.creepersLeft(), "taking a flag off counts it back");
        board.dig(44, T0);
        assertFalse(board.toggleFlag(44), "a dug square can't be flagged");
    }

    @Test
    void theClockStartsOnTheFirstDigAndFreezesAtTheEnd() {
        SweeperEngine board = SweeperEngine.fixed(1, 44);
        board.toggleFlag(44);
        assertEquals(0, board.timeMs(T0 + 5_000), "a flag doesn't start the clock");
        assertEquals(SweeperEngine.State.READY, board.state(), "still waiting for the first dig");
        board.dig(0, T0 + 5_000);
        assertEquals(SweeperEngine.State.WON, board.state(), "one dig clears a board whose only creeper is in the far corner");
        assertEquals(0, board.timeMs(T0 + 9_000), "a one-dig clear takes no time on the clock");

        SweeperEngine two = SweeperEngine.fixed(1, 4, 13, 22, 31, 40);
        two.dig(0, T0);
        assertEquals(1_500, two.timeMs(T0 + 1_500), "the clock runs from the first dig");
        two.dig(8, T0 + 2_000);
        assertEquals(SweeperEngine.State.WON, two.state(), "the right side floods open too");
        assertEquals(2_000, two.timeMs(T0 + 60_000), "and freezes at the clearing dig");
    }

    @Test
    void theDailyBoardIsTheSameForTheSameSeedAndStartsWithASafeOpening() {
        for (long seed = 0; seed < 200; seed++) {
            SweeperEngine a = SweeperEngine.daily(8, seed);
            SweeperEngine b = SweeperEngine.daily(8, seed);
            for (int c = 0; c < SweeperEngine.CELLS; c++) {
                assertEquals(a.mineAt(c), b.mineAt(c), "the same seed lays the same creepers (seed " + seed + ")");
                assertEquals(a.look(c), b.look(c), "and digs the same opening (seed " + seed + ")");
                if (a.look(c) == SweeperEngine.Look.OPEN) {
                    assertFalse(a.mineAt(c), "the opening never uncovers a creeper");
                }
            }
            assertEquals(8, count(a), "the daily board has the configured creepers");
            assertEquals(SweeperEngine.State.READY, a.state(), "the opening isn't the player's dig: the clock waits");
            assertEquals(0, a.timeMs(T0), "no time on the clock before the player digs");
            assertTrue(opened(a) > 0, "some squares are already dug");
            assertTrue(opened(a) < SweeperEngine.CELLS - 8, "the opening never clears the board by itself");
        }
        boolean differs = false;
        SweeperEngine first = SweeperEngine.daily(8, 1);
        SweeperEngine other = SweeperEngine.daily(8, 2);
        for (int c = 0; c < SweeperEngine.CELLS; c++) {
            differs |= first.mineAt(c) != other.mineAt(c);
        }
        assertTrue(differs, "another day's seed gives another board");
    }

    @Test
    void clearTimesReadAsMinutesSecondsAndTenthsCutDown() {
        assertEquals("0:00.0", SweeperEngine.clock(0), "nothing on the clock");
        assertEquals("1:23.4", SweeperEngine.clock(83_456), "minutes, seconds, tenths");
        assertEquals("0:45.9", SweeperEngine.clock(45_999), "cut down, so 45.99 s never reads as 46");
        assertEquals("10:00.0", SweeperEngine.clock(600_000), "ten minutes");
    }

    @Test
    void milestoneTimesCompareInMillisecondsSoAFractionOverDoesNotCount() {
        CreeperSweeperSettings s = CreeperSweeperSettings.defaults();
        assertEquals(List.of(180_000, 90_000, 45_000), s.milestonesFor("easy"), "easy's 180/90/45 s in the score's unit");
        assertEquals(List.of(), s.milestonesFor("daily:20000"), "the daily boards have no milestones");
        assertEquals(List.of(1, 2), CabinetGame.milestonesReached(s.milestonesFor("easy"), 45_300, true),
                "45.3 s is within bronze and silver but not the 45 s gold");
        assertEquals(List.of(1, 2, 3), CabinetGame.milestonesReached(s.milestonesFor("easy"), 45_000, true),
                "exactly 45 s is gold");
        assertEquals(8, s.minesFor("normal"), "normal ships with 8 creepers");
        assertEquals(8, s.minesFor("daily"), "anything else uses the normal count");
    }

    private static int count(SweeperEngine board) {
        int n = 0;
        for (int c = 0; c < SweeperEngine.CELLS; c++) {
            if (board.mineAt(c)) {
                n++;
            }
        }
        return n;
    }

    private static int opened(SweeperEngine board) {
        int n = 0;
        for (int c = 0; c < SweeperEngine.CELLS; c++) {
            if (board.look(c) == SweeperEngine.Look.OPEN) {
                n++;
            }
        }
        return n;
    }
}
