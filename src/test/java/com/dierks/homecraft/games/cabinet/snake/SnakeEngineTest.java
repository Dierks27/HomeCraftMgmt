package com.dierks.homecraft.games.cabinet.snake;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Scores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snake's rules, with no server.
 *
 * <p>Pinned here: a run starts as a snake of three heading right in the middle row; a step moves
 * the head one square; turns are relative to the snake and at most ONE is queued per move; an
 * apple grows the snake by one and a new apple never lands on it; a wall (no wrap-around) or the
 * snake's own body ends the run, but following its own tail is fine; the apples come from the
 * seed, the same for the same seed; the speed curve is one tick faster every five apples and
 * never below three; and the milestones can't be set past a full field.
 */
class SnakeEngineTest {

    @Test
    void aRunStartsAsASnakeOfThreeHeadingRightInTheMiddleRow() {
        SnakeEngine run = new SnakeEngine(1);
        assertEquals(SnakeEngine.START_LENGTH, run.length(), "three squares long");
        assertEquals(SnakeEngine.Heading.RIGHT, run.heading(), "heading right, toward the open field");
        assertEquals(SnakeEngine.cell(2, 2), run.head(), "head in the middle row");
        assertEquals(SnakeEngine.Look.BODY, run.look(0, 2), "tail at the left wall");
        assertTrue(run.apple() >= 0, "an apple is waiting");
        assertEquals(SnakeEngine.Look.APPLE, run.look(run.apple() % 7, run.apple() / 7), "not under the snake");
        assertEquals(SnakeEngine.State.LIVE, run.state(), "live");
    }

    @Test
    void aStepMovesTheHeadOneSquareAndTheTailFollows() {
        SnakeEngine run = away(new SnakeEngine(1));
        run.step();
        assertEquals(SnakeEngine.cell(3, 2), run.head(), "one square right");
        assertEquals(SnakeEngine.Look.EMPTY, run.look(0, 2), "the tail left its square");
        assertEquals(3, run.length(), "no apple, no growth");
        assertEquals(1, run.moves(), "one move counted");
    }

    @Test
    void turnsAreRelativeToTheWayTheSnakeFaces() {
        SnakeEngine run = away(new SnakeEngine(1));
        run.turnLeft();
        run.step();
        assertEquals(SnakeEngine.Heading.UP, run.heading(), "left of right is up");
        run.turnLeft();
        run.step();
        assertEquals(SnakeEngine.Heading.LEFT, run.heading(), "left of up is left");
        run.turnRight();
        run.step();
        assertEquals(SnakeEngine.Heading.UP, run.heading(), "right of left is up again");
        for (SnakeEngine.Heading h : SnakeEngine.Heading.values()) {
            assertEquals(h, h.left().right(), "a left then a right faces the same way");
            assertEquals(h, h.left().left().left().left(), "four lefts are a full circle");
        }
    }

    @Test
    void onlyOneTurnIsQueuedPerMove() {
        SnakeEngine run = away(new SnakeEngine(1));
        assertTrue(run.turnLeft(), "the first turn is queued");
        assertEquals(SnakeEngine.Heading.UP, run.nextHeading(), "and shows as the next heading");
        assertFalse(run.turnLeft(), "a second tap before the move is ignored, so a double tap can't reverse into the neck");
        assertFalse(run.turnRight(), "whichever way");
        run.step();
        assertEquals(SnakeEngine.Heading.UP, run.heading(), "only the first turn was taken");
        assertTrue(run.turnRight(), "after the move a new turn can be queued");
    }

    @Test
    void anAppleGrowsTheSnakeAndTheNextOneIsNeverUnderIt() {
        SnakeEngine run = new SnakeEngine(3);
        run.appleAt(SnakeEngine.cell(3, 2));
        run.step();
        assertEquals(1, run.apples(), "one apple eaten");
        assertEquals(4, run.length(), "and one square longer");
        assertEquals(SnakeEngine.Look.BODY, run.look(0, 2), "the tail stayed put while growing");
        assertNotEquals(-1, run.apple(), "a new apple appeared");
        assertEquals(SnakeEngine.Look.APPLE, run.look(run.apple() % 7, run.apple() / 7), "on a free square");
    }

    @Test
    void theWallEndsTheRunWithNoWrapAround() {
        SnakeEngine run = away(new SnakeEngine(1));
        for (int i = 0; i < 4; i++) {
            assertEquals(SnakeEngine.State.LIVE, run.step(), "room to move up to the right wall");
        }
        assertEquals(SnakeEngine.cell(6, 2), run.head(), "at the right wall");
        assertEquals(SnakeEngine.State.CRASHED, run.step(), "one more step hits the wall");
        assertEquals(SnakeEngine.cell(6, 2), run.head(), "the snake didn't come out on the other side");
        assertEquals(SnakeEngine.Look.CRASH, run.look(6, 2), "the crash is shown where it happened");
        assertFalse(run.turnLeft(), "no turns after the crash");
        assertEquals(SnakeEngine.State.CRASHED, run.step(), "and nothing moves");
        assertEquals(4, run.moves(), "the crash itself isn't a move");
    }

    @Test
    void runningIntoItsOwnBodyEndsTheRun() {
        SnakeEngine run = new SnakeEngine(1);
        run.appleAt(SnakeEngine.cell(3, 2));
        run.step();
        run.appleAt(SnakeEngine.cell(4, 2));
        run.step();
        away(run);
        assertEquals(5, run.length(), "five long, head at (4,2)");
        run.turnRight();
        run.step();
        run.turnRight();
        run.step();
        run.turnRight();
        assertEquals(SnakeEngine.State.CRASHED, run.step(), "three rights turn a snake of five into its own body");
        assertEquals(SnakeEngine.cell(3, 3), run.head(), "the head stops before the body");
    }

    @Test
    void followingItsOwnTailIsFine() {
        SnakeEngine run = new SnakeEngine(1);
        run.appleAt(SnakeEngine.cell(3, 2));
        run.step();
        away(run);
        assertEquals(4, run.length(), "four long");
        run.turnRight();
        run.step();
        run.turnRight();
        run.step();
        run.turnRight();
        assertEquals(SnakeEngine.State.LIVE, run.step(), "a snake of four circling a 2x2 square moves into the square its tail just left");
        assertEquals(SnakeEngine.cell(2, 2), run.head(), "where the tail was");
    }

    @Test
    void theApplesComeFromTheSeed() {
        List<List<Integer>> sequences = new ArrayList<>();
        for (long seed = 0; seed < 20; seed++) {
            List<Integer> a = apples(seed);
            assertEquals(a, apples(seed), "the same seed and moves give the same apples (seed " + seed + ")");
            sequences.add(a);
        }
        assertTrue(sequences.stream().distinct().count() > 10, "different seeds give different apples");
    }

    @Test
    void anAppleNeverLandsOnTheSnake() {
        SplittableRandom taps = new SplittableRandom(99);
        for (long seed = 0; seed < 200; seed++) {
            SnakeEngine run = new SnakeEngine(seed);
            for (int i = 0; i < 200 && !run.state().over(); i++) {
                int t = taps.nextInt(4);
                if (t == 0) {
                    run.turnLeft();
                } else if (t == 1) {
                    run.turnRight();
                }
                run.step();
                if (run.apple() >= 0 && run.state() == SnakeEngine.State.LIVE) {
                    assertEquals(SnakeEngine.Look.APPLE, run.look(run.apple() % 7, run.apple() / 7),
                            "the apple is on a free square (seed " + seed + ", move " + i + ")");
                }
            }
        }
    }

    @Test
    void itGetsOneTickFasterEveryFiveApplesAndNeverBelowThree() {
        assertEquals(6, SnakeEngine.interval(6, 0), "the Java start");
        assertEquals(6, SnakeEngine.interval(6, 4), "no change before five apples");
        assertEquals(5, SnakeEngine.interval(6, 5), "a little faster at five");
        assertEquals(4, SnakeEngine.interval(6, 10), "and again at ten");
        assertEquals(3, SnakeEngine.interval(6, 15), "three ticks at fifteen");
        assertEquals(3, SnakeEngine.interval(6, 30), "never below three");
        assertEquals(10, SnakeEngine.interval(10, 0), "the Bedrock start is slower");
        assertEquals(4, SnakeEngine.interval(10, 30), "and stays slower all the way");
        assertEquals(3, SnakeEngine.interval(3, 0), "a start at the floor stays there");
        assertEquals(1, SnakeEngine.speedLevel(6, 0), "speed 1 at the start");
        assertEquals(4, SnakeEngine.speedLevel(6, 25), "speed 4 once it reached three ticks");
    }

    @Test
    void theMilestonesStayWithinAFullField() {
        assertEquals(32, SnakeEngine.MAX_APPLES, "a 7x5 field holds a snake of 35: 32 apples after the first 3");
        List<String> warns = new ArrayList<>();
        SnakeSettings parsed = SnakeSettings.parse(new GamesConfig.Node("games.snake",
                Map.of("milestones", List.of(10, 20, 40)), warns::add), SnakeSettings.defaults());
        assertEquals(List.of(10, 20, 32), parsed.milestones(), "a gold past a full field is pulled back to one");
        assertEquals(1, warns.size(), "with one warning");
        assertEquals(List.of(10, 20, 30), SnakeSettings.defaults().milestonesFor(Scores.CLASSIC),
                "Classic's shipped milestones");
        assertEquals(List.of(), SnakeSettings.defaults().milestonesFor(Scores.daily(1)), "the daily boards have none");
    }

    /** Move the apple out of the way (top right corner) so a scripted run eats nothing. */
    private static SnakeEngine away(SnakeEngine run) {
        run.appleAt(SnakeEngine.cell(6, 0));
        return run;
    }

    /** Where the apple was after every move of a run steered by the same fixed taps. */
    private static List<Integer> apples(long seed) {
        SnakeEngine run = new SnakeEngine(seed);
        SplittableRandom taps = new SplittableRandom(7);
        List<Integer> out = new ArrayList<>();
        out.add(run.apple());
        for (int i = 0; i < 100 && !run.state().over(); i++) {
            int t = taps.nextInt(3);
            if (t == 0) {
                run.turnLeft();
            } else if (t == 1) {
                run.turnRight();
            }
            run.step();
            out.add(run.apple());
        }
        return out;
    }
}
