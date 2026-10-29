package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoint splits (0.38.4, the ghost's first step): the checkpoint line's "+0.4 s" / "-0.6 s" against the
 * player's best run (tenths, rounded down, red behind and green ahead), nothing at all in a race, a warm-up, with no
 * best or where either run has no time; the finish's sentence names the checkpoint from which the run stayed ahead
 * (or behind); and a run's times are read off its progress in ms from the start, the finish's own time last.
 */
class SplitsTest {

    private static final long[] BEST = {10_000, 20_000, 30_000, 40_000};

    @Test
    void theCheckpointLineSaysHowFarAheadOrBehindTheBestInTenths() {
        long[] now = {10_450, 19_350, 30_000, 0};
        assertEquals(" &c+0.4 s", Splits.deltaText(BEST, now, 0, false, false), "behind: red, tenths rounded down");
        assertEquals(" &a-0.6 s", Splits.deltaText(BEST, now, 1, false, false), "ahead: green");
        assertEquals(" &7=0.0 s", Splits.deltaText(BEST, now, 2, false, false), "level");
        assertEquals(" &7=0.0 s", Splits.deltaText(BEST, new long[]{10_099, 0, 0, 0}, 0, false, false),
                "under a tenth either way reads as level");
        assertEquals(" &c+70.0 s", Splits.deltaText(new long[]{10_000}, new long[]{80_000}, 0, false, false),
                "a long gap, in seconds");
    }

    @Test
    void nothingIsShownInARaceAWarmUpOrWithoutAComparison() {
        long[] now = {10_450, 19_350, 30_000, 0};
        assertEquals("", Splits.deltaText(null, now, 0, false, false), "no best run yet");
        assertEquals("", Splits.deltaText(BEST, now, 0, true, false), "race mode: never (not to others either)");
        assertEquals("", Splits.deltaText(BEST, now, 0, false, true), "a warm-up: never");
        assertEquals("", Splits.deltaText(BEST, now, 3, false, false), "the finish isn't reached yet (0)");
        assertEquals("", Splits.deltaText(BEST, now, 4, false, false), "past the end");
        assertEquals("", Splits.deltaText(new long[]{10_000}, now, 1, false, false), "a best with fewer targets");
        assertEquals("", Splits.deltaText(BEST, null, 0, false, false), "no run");
    }

    @Test
    void theFinishLineNamesWhereTheRunGainedOrLost() {
        assertEquals("&7Faster than your best from checkpoint 2.",
                Splits.finishLine(BEST, new long[]{10_500, 19_000, 29_000, 39_000}),
                "behind at 1, ahead from 2 to the line");
        assertEquals("&7Behind your best from checkpoint 1.",
                Splits.finishLine(BEST, new long[]{10_500, 21_000, 31_000, 41_000}), "behind all the way");
        assertEquals("&7Faster than your best on the last stretch.",
                Splits.finishLine(BEST, new long[]{10_500, 20_500, 30_500, 39_900}), "only the finish is ahead");
        assertEquals("&7Level with your best all the way.", Splits.finishLine(BEST, BEST.clone()));
        assertEquals("&7Level with your best at the line.",
                Splits.finishLine(BEST, new long[]{9_000, 21_000, 30_000, 40_000}));
        assertNull(Splits.finishLine(null, BEST), "no best: nothing to say");
        assertNull(Splits.finishLine(BEST, new long[]{10_000, 20_000}), "not the same targets: never compared");
        assertNull(Splits.finishLine(BEST, new long[]{10_000, 20_000, 30_000, 0}), "no finish time");
    }

    @Test
    void aRunsTimesAreReadOffItsProgressInMillisecondsWithTheFinishLast() {
        TrialRun run = new TrialRun(UUID.randomUUID(), LapsTest.straight(), false, TimeTrials.COUNTDOWN_TICKS + 1);
        assertArrayEquals(new long[0], Splits.soFar(run), "before the clock runs: nothing");
        long start = 5_000_000_000L;
        run.progress = new Progress(LapsTest.straight(), new Point(0, 65, 0), start);
        run.progress.move(new Point(0, 65, 16), start + 2_000_000_000L); // through checkpoint 1 (z 15, radius 3)
        long[] soFar = Splits.soFar(run);
        assertEquals(4, soFar.length, "three checkpoints and the finish");
        assertTrue(soFar[0] > 0 && soFar[0] < 2_000, "checkpoint 1's time, in ms from the start: " + soFar[0]);
        assertEquals(0, soFar[1], "not reached yet");
        assertEquals(0, soFar[3], "the finish neither");
        long[] done = Splits.fromRun(run, 61_234);
        assertEquals(soFar[0], done[0], "the checkpoints as they were");
        assertEquals(61_234, done[3], "the finish's own time last");
        assertEquals(0, Splits.fromRun(new TrialRun(UUID.randomUUID(), LapsTest.straight(), false, 1), 100).length,
                "no progress, no times (nothing to store)");
    }
}
