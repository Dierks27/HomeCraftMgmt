package com.dierks.homecraft.games.gen.dropper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A witness's input program (EVENTS-DROPPER-SPEC §B.1.5 step 2): the keys held tick by tick, written
 * as text in the plan's summary so the validator can replay the proof from the plan alone, and the
 * input changes the tier limits (counted from the key a walk-off is already holding).
 */
class DropProgramTest {

    private static final DropProgram.Dir S = DropProgram.Dir.S;

    @Test
    void theLastSegmentIsHeldToTheEnd() {
        DropProgram p = DropProgram.parse("S7 E3 -*");
        assertNotNull(p, "a program in its text form");
        assertEquals(S, p.at(0), "tick 0 is the first segment");
        assertEquals(S, p.at(6), "held for 7 ticks");
        assertEquals(DropProgram.Dir.E, p.at(7), "then the next");
        assertEquals(DropProgram.Dir.NONE, p.at(10), "the last from tick 10");
        assertEquals(DropProgram.Dir.NONE, p.at(500), "for good");
    }

    @Test
    void theTextFormRoundTrips() {
        for (String text : List.of("S*", "-*", "N10 S*", "S4 SE13 -*", "S4 E3 S6 N3 -*", "w0 nw2 -*")) {
            DropProgram p = DropProgram.parse(text);
            assertNotNull(p, text + " parses");
            assertEquals(p, DropProgram.parse(p.encode()), text + ": what it writes reads back the same");
        }
        assertEquals("W0 NW2 -*", DropProgram.parse("w0 nw2 -*").encode(), "any case in, upper case out");
    }

    @Test
    void textThatIsntAProgramIsRefused() {
        for (String bad : List.of("", "   ", "S", "S7", "S7 -3", "X3 -*", "S-1 -*", "S999 -*", "S7 -* E2")) {
            assertNull(DropProgram.parse(bad), "'" + bad + "' is not a program");
        }
        assertNull(DropProgram.parse(null), "nor is nothing");
        assertThrows(IllegalArgumentException.class, () -> new DropProgram(List.of()),
                "a program has at least one segment");
    }

    @Test
    void walkingOffStraightAndHoldingOnIsNoChange() {
        assertEquals(0, DropProgram.parse("S*").changes(S), "a walk-off is already holding forward");
        assertEquals(1, DropProgram.parse("S7 -*").changes(S), "letting go is one change");
        assertEquals(1, DropProgram.parse("-*").changes(S), "letting go at once is one change too");
        assertEquals(List.of(0), DropProgram.parse("-*").changeTicks(S), "made at tick 0");
    }

    @Test
    void everyTurnCountsAndZeroTickSegmentsDont() {
        DropProgram p = DropProgram.parse("S4 E3 S6 N3 -*");
        assertEquals(List.of(4, 7, 13, 16), p.changeTicks(S), "four turns, at the tick each starts");
        assertEquals(4, p.changes(S), "four changes");
        assertEquals(16, p.lastChange(S), "the last at tick 16");
        assertEquals(0, DropProgram.parse("S0 S*").changes(S), "a segment of no ticks changes nothing");
        assertEquals(1, DropProgram.parse("S3 E0 -*").changes(S), "E for 0 ticks isn't a change; letting go is");
        assertEquals(-1, DropProgram.parse("S*").lastChange(S), "no change: -1");
    }

    @Test
    void theMirrorIsTheSameLineOnTheOtherSide() {
        DropProgram p = DropProgram.parse("S4 SE3 E2 -*");
        assertEquals("S4 SW3 W2 -*", p.mirrored(S).encode(), "facing south, east and west swap; south stays");
        assertEquals("E4 NE3 N2 -*", DropProgram.parse("E4 SE3 S2 -*").mirrored(DropProgram.Dir.E).encode(),
                "facing east, north and south swap");
        assertEquals(p.changes(S), p.mirrored(S).changes(S), "a mirror makes the same number of changes");
    }

    @Test
    void theCompassKeysAreUnitVectorsWithNorthUp() {
        assertArrayEquals(new double[]{0, -1}, DropProgram.Dir.N.input(), 1e-12, "north is -z, as on a map");
        assertArrayEquals(new double[]{0, 0}, DropProgram.Dir.NONE.input(), 0, "no key is no input");
        double[] ne = DropProgram.Dir.NE.input();
        assertEquals(1, Math.hypot(ne[0], ne[1]), 1e-12, "a diagonal is a unit vector: no faster diagonal");
        assertEquals(DropProgram.Dir.W, DropProgram.Dir.E.opposite(), "east's opposite is west");
        assertEquals(DropProgram.Dir.SW, DropProgram.Dir.NE.opposite(), "north-east's is south-west");
        assertEquals(DropProgram.Dir.NONE, DropProgram.Dir.NONE.opposite(), "no key stays no key");
        assertEquals(DropProgram.Dir.SE, DropProgram.Dir.of(1, 1), "(1, 1) is south-east");
    }

    @Test
    void itSteersARunAsAController() {
        DropProgram p = DropProgram.parse("E2 -*");
        DropRun.Controller c = p.controller();
        assertArrayEquals(new double[]{1, 0}, c.input(1, null), 1e-12, "tick 1 is still east");
        assertArrayEquals(new double[]{0, 0}, c.input(2, null), 0, "tick 2 lets go");
    }
}
