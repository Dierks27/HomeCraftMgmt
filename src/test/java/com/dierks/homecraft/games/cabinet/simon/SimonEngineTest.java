package com.dierks.homecraft.games.cabinet.simon;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simon Says's rules and pacing without a server.
 *
 * <p>Pinned here: the pattern comes from the seed alone and only ever grows at the end; presses
 * count only in the player's turn; playing the whole pattern back adds one pad; a wrong pad ends
 * the game with the score = the longest pattern played back in full; and the tempo lights every
 * step once, in order, with a dark gap between, quicker as it grows but never below its floor,
 * and slower on Bedrock.
 */
class SimonEngineTest {

    @Test
    void thePatternComesFromTheSeedAlone() {
        assertArrayEquals(SimonEngine.pattern(5, 20), SimonEngine.pattern(5, 20),
                "today's daily pattern is the same for everyone");
        assertFalse(Arrays.equals(SimonEngine.pattern(5, 20), SimonEngine.pattern(6, 20)),
                "another seed plays another pattern");
        for (int pad : SimonEngine.pattern(5, 200)) {
            assertTrue(pad >= 0 && pad < SimonEngine.PADS, "every step is one of the four pads");
        }
    }

    @Test
    void pressesDontCountWhileThePatternIsShowing() {
        SimonEngine e = new SimonEngine(1);
        assertEquals(SimonEngine.Phase.SHOWING, e.phase(), "a game starts by showing the first pad");
        assertEquals(SimonEngine.Press.IGNORED, e.press(e.step(0)), "a press during the show is ignored");
        assertEquals(SimonEngine.Phase.SHOWING, e.phase(), "and changes nothing");
    }

    @Test
    void playingThePatternBackAddsOnePadAndKeepsTheOldOnes() {
        SimonEngine e = new SimonEngine(77);
        List<Integer> before = new ArrayList<>();
        for (int round = 1; round <= 12; round++) {
            assertEquals(round, e.length(), "round " + round + " shows " + round + " pads");
            for (int i = 0; i < before.size(); i++) {
                assertEquals(before.get(i), e.step(i), "step " + i + " must not change as the pattern grows");
            }
            e.shown();
            for (int i = 0; i < round; i++) {
                SimonEngine.Press p = e.press(e.step(i));
                assertEquals(i == round - 1 ? SimonEngine.Press.ROUND_DONE : SimonEngine.Press.RIGHT, p,
                        "round " + round + " pad " + i + ": the last right pad finishes the round, the others don't");
            }
            assertEquals(SimonEngine.Phase.SHOWING, e.phase(), "a finished round goes straight to showing the next");
            assertEquals(round, e.score(), "the score is the longest pattern played back");
            before.clear();
            for (int i = 0; i < round; i++) {
                before.add(e.step(i));
            }
        }
        assertEquals(SimonEngine.pattern(77, 13)[12], e.step(12), "the thirteenth pad is the seed's thirteenth");
    }

    @Test
    void aWrongPadEndsTheGameWithTheLastFullPattern() {
        SimonEngine e = new SimonEngine(3);
        for (int round = 1; round <= 4; round++) {
            e.shown();
            for (int i = 0; i < round; i++) {
                e.press(e.step(i));
            }
        }
        e.shown();
        e.press(e.step(0));
        int wrong = (e.step(1) + 1) % SimonEngine.PADS;
        assertEquals(SimonEngine.Press.WRONG, e.press(wrong), "a wrong pad is game over");
        assertTrue(e.over(), "the game is over");
        assertEquals(4, e.score(), "four pads were played back in full; the fifth wasn't");
        assertEquals(SimonEngine.Press.IGNORED, e.press(e.step(1)), "nothing counts after the end");
    }

    @Test
    void aWrongFirstPressScoresNothing() {
        SimonEngine e = new SimonEngine(8);
        e.shown();
        assertEquals(SimonEngine.Press.WRONG, e.press((e.step(0) + 2) % 4), "wrong from the start");
        assertEquals(0, e.score(), "nothing was played back");
    }

    @Test
    void theWholePatternEndsTheGameAsComplete() {
        SimonEngine e = new SimonEngine(9);
        SimonEngine.Press last = null;
        while (!e.over()) {
            e.shown();
            for (int i = 0; i < e.length() && !e.over(); i++) {
                last = e.press(e.step(i));
            }
        }
        assertEquals(SimonEngine.Press.COMPLETE, last, "the longest pattern there is ends the game");
        assertEquals(SimonEngine.MAX, e.score(), "with the top score");
    }

    @Test
    void theTempoLightsEveryStepOnceInOrderWithDarkBetween() {
        for (SimonEngine.Tempo tempo : List.of(SimonEngine.Tempo.JAVA, SimonEngine.Tempo.BEDROCK)) {
            for (int length : new int[] {1, 5, 8, 20}) {
                List<Integer> lit = new ArrayList<>();
                int previous = -1;
                for (int tick = 0; tick < tempo.total(length) + 5; tick++) {
                    int step = tempo.litAt(tick, length);
                    if (step >= 0 && step != previous) {
                        lit.add(step);
                    }
                    if (step >= 0 && previous >= 0) {
                        assertEquals(previous, step, "a light stays on its step until a dark gap");
                    }
                    previous = step;
                }
                List<Integer> expected = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    expected.add(i);
                }
                assertEquals(expected, lit, "every step lights once, in order, for length " + length);
                assertEquals(-1, tempo.litAt(0, length), "a short pause before the first light");
                assertEquals(-1, tempo.litAt(tempo.total(length), length), "dark once the show is over");
            }
        }
    }

    @Test
    void theTempoQuickensButNeverPastItsFloorAndBedrockIsSlower() {
        SimonEngine.Tempo java = SimonEngine.Tempo.JAVA;
        assertTrue(java.onFor(20) < java.onFor(1), "a long pattern plays a little quicker");
        assertEquals(java.minOn(), java.onFor(SimonEngine.MAX), "but never quicker than the floor");
        for (int length = 1; length <= SimonEngine.MAX; length++) {
            assertTrue(SimonEngine.Tempo.BEDROCK.onFor(length) > java.onFor(length),
                    "Bedrock lights stay on longer at length " + length);
            assertTrue(SimonEngine.Tempo.BEDROCK.total(length) > java.total(length),
                    "and the whole show is slower at length " + length);
        }
    }
}
