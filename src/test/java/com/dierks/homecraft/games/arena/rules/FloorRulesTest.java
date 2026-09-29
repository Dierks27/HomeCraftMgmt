package com.dierks.homecraft.games.arena.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.CX;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.CZ;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.LOW_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.MID_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.OUT_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.TOP_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.on;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floors during a round (EVENTS-DROPPER-SPEC §B.3.3 step 4, WP-F's FloorRulesTest list):
 * footprints at edges and corners, red then air exactly {@code fade_ticks} later, standing still
 * and jumping in place both fall, sudden-death rings end every round, and "out" is below y 181.
 */
class FloorRulesTest {

    private static final int TOP = TOP_Y + 1;

    private static FloorRules rules(FloorLayout l, int fade) {
        return new FloorRules(l, fade, 3600, RoundSettings.RING_TICKS);
    }

    // ---- the footprint ------------------------------------------------------------------------

    @Test
    void aPlayerInTheMiddleOfACellStandsOnThatCellAlone() {
        assertEquals(List.of(new Cell(10, 20)), FloorRules.footprint(10.5, 20.5),
                "a 0.6-wide footprint in the middle of a block is on that block only");
    }

    @Test
    void aFootprintOverAnEdgeIsOnBothCells() {
        assertEquals(List.of(new Cell(9, 20), new Cell(10, 20)), FloorRules.footprint(10.1, 20.5),
                "0.2 into the next block along x: two cells");
        assertEquals(List.of(new Cell(10, 19), new Cell(10, 20)), FloorRules.footprint(10.5, 20.05),
                "and along z");
    }

    @Test
    void aFootprintOverACornerIsOnFourCells() {
        assertEquals(List.of(new Cell(9, 19), new Cell(9, 20), new Cell(10, 19), new Cell(10, 20)),
                FloorRules.footprint(10.0, 20.0), "straddling a corner: all four, never more");
        assertEquals(List.of(new Cell(-1, -1), new Cell(-1, 0), new Cell(0, -1), new Cell(0, 0)),
                FloorRules.footprint(0.1, -0.1), "negative coordinates round the same way (floor, not towards 0)");
    }

    @Test
    void aFootprintOnlyTouchingAnEdgeIsNotOnTheNextCell() {
        assertEquals(List.of(new Cell(10, 20)), FloorRules.footprint(10.3, 20.5),
                "the footprint's left edge exactly on the block edge: not on the block to the left");
        assertEquals(List.of(new Cell(10, 20)), FloorRules.footprint(10.7, 20.5),
                "its right edge exactly on the next block's edge (10.7 + 0.3 is not quite 11.0 in doubles): not on it");
        assertEquals(List.of(new Cell(10, 20)), FloorRules.footprint(10.7000000001, 20.5),
                "a hair past, still within rounding: not on it");
        assertEquals(List.of(new Cell(10, 20), new Cell(11, 20)), FloorRules.footprint(10.71, 20.5),
                "a hundredth over: on both");
    }

    // ---- marking and fading ----------------------------------------------------------------------

    @Test
    void aSteppedOnCellTurnsRedAtOnceAndIsAirExactlyFadeTicksLater() {
        FloorLayout l = ArenaFixtures.small();
        for (int fade : new int[]{6, 10, 20}) {
            FloorRules f = rules(l, fade);
            List<FloorWrite> first = f.step(0, List.of(on(CX + 2, CZ + 2, TOP)));
            assertEquals(List.of(new FloorWrite(0, CX + 2, TOP_Y, CZ + 2, CellState.RED)), first,
                    "the cell under the feet turns red on the tick they stand on it");
            for (int t = 1; t < fade; t++) {
                assertEquals(List.of(), f.step(t, List.of()), "fade " + fade + ": nothing changes at tick " + t);
                assertEquals(CellState.RED, f.state(0, CX + 2, CZ + 2), "still red (and still holding) at " + t);
            }
            assertEquals(List.of(new FloorWrite(0, CX + 2, TOP_Y, CZ + 2, CellState.AIR)), f.step(fade, List.of()),
                    "fade " + fade + ": air exactly " + fade + " ticks after it went red");
            assertEquals(CellState.AIR, f.state(0, CX + 2, CZ + 2), "and it stays air");
        }
    }

    @Test
    void aRedCellIsNeverMarkedAgainSoItsFadeNeverMoves() {
        FloorRules f = rules(ArenaFixtures.small(), 10);
        f.step(0, List.of(on(CX + 1, CZ + 1, TOP)));
        for (int t = 1; t < 10; t++) {
            assertEquals(List.of(), f.step(t, List.of(on(CX + 1, CZ + 1, TOP))),
                    "standing on red writes nothing new at tick " + t);
        }
        assertEquals(CellState.AIR, stateAfter(f, 10, CX + 1, CZ + 1), "and it goes at tick 10, as first marked");
        assertEquals(1, f.marks(), "one mark");
        assertEquals(1, f.fades(), "one fade");
    }

    @Test
    void onlyFeetOnAFloorTopAndNotGoingUpMarkIt() {
        FloorRules f = rules(ArenaFixtures.small(), 10);
        assertEquals(0, f.standingOn(new Feet(CX + 0.5, TOP, CZ + 0.5, 0)), "on the top floor");
        assertEquals(0, f.standingOn(new Feet(CX + 0.5, TOP + 0.1, CZ + 0.5, -0.3)),
                "0.1 above and coming down: a landing is caught this tick");
        assertEquals(0, f.standingOn(new Feet(CX + 0.5, TOP - 1e-9, CZ + 0.5, 0)),
                "a rounding hair below the top is still on it");
        assertEquals(0, f.standingOn(new Feet(CX + 0.5, TOP - 1e-5, CZ + 0.5, 0)),
                "the server's own slack (it takes feet up to 1e-5 into a block) is still on it (F review #12)");
        double eyeThroughAFloat = (float) (TOP + 1.62); // a Bedrock position comes through Geyser as a float
        assertEquals(0, f.standingOn(new Feet(CX + 0.5, eyeThroughAFloat - 1.62, CZ + 0.5, 0)),
                "feet worked out from a float eye height (a few millionths under) are still on it");
        assertEquals(-1, f.standingOn(new Feet(CX + 0.5, TOP - 0.01, CZ + 0.5, -0.1)), "a hundredth under is falling");
        assertEquals(-1, f.standingOn(new Feet(CX + 0.5, TOP + 0.11, CZ + 0.5, -0.3)), "0.11 above is in the air");
        assertEquals(-1, f.standingOn(new Feet(CX + 0.5, TOP, CZ + 0.5, 0.42)), "jumping off marks nothing");
        assertEquals(-1, f.standingOn(new Feet(CX + 0.5, TOP - 3, CZ + 0.5, -0.5)), "between floors, falling");
        assertEquals(1, f.standingOn(new Feet(CX + 0.5, MID_Y + 1, CZ + 0.5, 0)), "on the middle floor");
        assertEquals(2, f.standingOn(new Feet(CX + 0.5, LOW_Y + 1, CZ + 0.5, 0)), "on the bottom floor");
        assertEquals(List.of(), f.step(0, List.of(new Feet(CX + 0.5, TOP, CZ + 0.5, 0.42))),
                "rising feet write nothing");
    }

    @Test
    void aFootprintMarksAtMostFourCellsAndOnlyPlannedOnes() {
        FloorLayout l = ArenaFixtures.small();
        FloorRules f = rules(l, 10);
        List<FloorWrite> corner = f.step(0, List.of(new Feet(CX + 3.0, TOP, CZ + 3.0, 0)));
        assertEquals(4, corner.size(), "a corner marks the four cells round it");
        List<FloorWrite> edge = f.step(1, List.of(new Feet(CX + 0.1, TOP, CZ + 0.5, 0)));
        assertEquals(List.of(new FloorWrite(0, CX, TOP_Y, CZ, CellState.RED)), edge,
                "on the floor's edge, half over nothing: only the planned cell is written, never the air beside it");
        for (FloorWrite w : corner) {
            assertTrue(l.isFloorCell(w.x(), w.y(), w.z()), "every write is a planned floor cell: " + w);
        }
    }

    @Test
    void theFloorsOnlyMoveForward() {
        FloorRules f = rules(ArenaFixtures.small(), 10);
        f.step(5, List.of());
        assertThrows(IllegalStateException.class, () -> f.step(5, List.of()), "the same tick twice");
        assertThrows(IllegalStateException.class, () -> f.step(4, List.of()), "a tick back");
        assertThrows(IllegalArgumentException.class, () -> new FloorWrite(0, 0, 0, 0, CellState.SOLID),
                "a round never writes the floor back: only the reset does");
    }

    @Test
    void aSkippedTickIsCaughtUp() {
        FloorRules f = rules(ArenaFixtures.small(), 10);
        f.step(0, List.of(on(CX, CZ, TOP)));
        List<FloorWrite> late = f.step(25, List.of());
        assertEquals(List.of(new FloorWrite(0, CX, TOP_Y, CZ, CellState.AIR)), late,
                "a fade due while ticks were skipped happens at the next tick");
    }

    // ---- bodies: standing still and jumping in place both fall ------------------------------------

    /** Runs one body on its own floors until it is out; returns the play tick it went out. */
    private static long untilOut(FloorRules f, ArenaSim.Body b, long max) {
        for (long t = 0; t < max; t++) {
            f.step(t, List.of(b.feet()));
            if (f.isOut(b.feet())) {
                return t;
            }
            ArenaSim.move(b, f);
        }
        return -1;
    }

    @Test
    void standingStillFallsThroughEveryFloorAndOut() {
        FloorLayout l = ArenaFixtures.small();
        FloorRules f = rules(l, 10);
        ArenaSim.Body b = new ArenaSim.Body(ArenaFixtures.p(1), ArenaSim.still());
        b.place(CX + 2.5, TOP, CZ + 2.5, true, 0);
        long out = untilOut(f, b, 400);
        assertTrue(out > 0, "a player who never moves is out in the end");
        assertEquals(CellState.AIR, f.state(0, CX + 2, CZ + 2), "the top cell under them went");
        assertEquals(CellState.AIR, f.state(1, CX + 2, CZ + 2), "then the middle one they landed on");
        assertEquals(CellState.AIR, f.state(2, CX + 2, CZ + 2), "then the bottom one");
        assertEquals(3, f.marks(), "one cell a floor: standing still marks exactly the cell under you");
        assertTrue(out >= 3 * 10 + 2 * 15, "three fades and two 8-block falls take at least 60 ticks: " + out);
        assertTrue(out < 120, "and well under 6 s: standing still is the quickest way out: " + out);
    }

    @Test
    void jumpingInPlaceFallsToo() {
        FloorLayout l = ArenaFixtures.small();
        for (int fade : new int[]{6, 10, 20}) {
            FloorRules f = rules(l, fade);
            ArenaSim.Body b = new ArenaSim.Body(ArenaFixtures.p(1), ArenaSim.hopper());
            b.place(CX + 2.5, TOP, CZ + 2.5, true, 0);
            long out = untilOut(f, b, 600);
            assertTrue(out > 0, "fade " + fade + ": a player jumping in place is out in the end");
            assertEquals(CellState.AIR, f.state(2, CX + 2, CZ + 2), "fade " + fade + ": every floor under them went");
        }
    }

    @Test
    void walkingOntoFreshGlassLastsLongerThanStandingStill() {
        FloorLayout l = ArenaFixtures.discs();
        FloorRules still = rules(l, 10);
        ArenaSim.Body s = new ArenaSim.Body(ArenaFixtures.p(1), ArenaSim.still());
        s.place(CX + 0.5, TOP, CZ + 0.5, true, 0);
        FloorRules moving = rules(l, 10);
        ArenaSim.Body r = new ArenaSim.Body(ArenaFixtures.p(2), ArenaSim.runner());
        r.place(CX + 0.5, TOP, CZ + 0.5, true, 0);
        long stillOut = untilOut(still, s, 10_000);
        long runnerOut = untilOut(moving, r, 10_000);
        assertTrue(runnerOut > 10 * stillOut, "keep moving is the whole game: a runner lasts far longer ("
                + runnerOut + " ticks) than standing still (" + stillOut + ")");
    }

    // ---- sudden death and out ------------------------------------------------------------------

    @Test
    void suddenDeathTakesOneRingOfEveryFloorEveryTwoSecondsUntilNothingIsLeft() {
        FloorLayout l = ArenaFixtures.discs();
        int start = 200;
        FloorRules f = new FloorRules(l, 10, start, RoundSettings.RING_TICKS);
        for (int t = 0; t < start; t++) {
            assertEquals(List.of(), f.step(t, List.of()), "nothing falls on its own before sudden death (tick " + t
                    + ")");
        }
        List<FloorWrite> ring0 = f.step(start, List.of());
        int rimCells = 0;
        for (int layer = 0; layer < 3; layer++) {
            for (int n = 0; n < l.cellCount(layer); n++) {
                if (l.ring(layer, n) == 0) {
                    rimCells++;
                }
            }
        }
        assertEquals(rimCells, ring0.size(), "at sudden death the outer ring of every floor turns red");
        for (FloorWrite w : ring0) {
            assertEquals(CellState.RED, w.state(), "red first, as always: " + w);
            assertEquals(0, l.ring(w.layer(), l.ordinal(w.layer(), w.x(), w.z())), "only ring 0: " + w);
        }
        assertEquals(1, f.ringsDone(), "one ring down");
        long t = start + 1;
        while (!f.allGone()) {
            f.step(t++, List.of());
            assertTrue(t < start + 100L * RoundSettings.RING_TICKS, "the floors must run out");
        }
        assertEquals(l.maxRings(), f.ringsDone(), "every ring fell");
        assertEquals(f.lastRingAt() + 10, t - 1, "the last cell goes one fade after the last ring, 2 s a ring");
        assertEquals(start + (long) (l.maxRings() - 1) * RoundSettings.RING_TICKS, f.lastRingAt(),
                "ring k falls at sudden death + 2 s x k");
        for (int layer = 0; layer < 3; layer++) {
            assertEquals(0, f.left(layer), "floor " + layer + " is all air");
        }
    }

    @Test
    void outIsBelowY181AndNotAHairAbove() {
        FloorRules f = rules(ArenaFixtures.small(), 10);
        assertTrue(f.isOut(new Feet(CX, OUT_Y - 0.001, CZ, -1)), "just below 181 is out");
        assertFalse(f.isOut(new Feet(CX, OUT_Y, CZ, -1)), "at 181 exactly is not out yet");
        assertFalse(f.isOut(new Feet(CX, LOW_Y + 1, CZ, 0)), "standing on the bottom floor is not out");
        assertFalse(f.isOut(null), "no feet, no verdict");
    }

    private static CellState stateAfter(FloorRules f, long t, int x, int z) {
        f.step(t, List.of());
        return f.state(0, x, z);
    }
}
