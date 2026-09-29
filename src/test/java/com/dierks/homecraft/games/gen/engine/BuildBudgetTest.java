package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One tick's building budget (GEN-SPEC §3.3): writes (online and idle), milliseconds checked every
 * 32 writes, chunk snapshots, and the pause above {@code pause_above_mspt} that only lifts a quarter
 * lower.
 */
class BuildBudgetTest {

    private static final DailySettings.Budget CFG = new DailySettings.Budget(500, 5000, 4, 4, 2, 40);

    private long nanos;

    private int opsUntilRefused(BuildBudget b) {
        int n = 0;
        while (b.op()) {
            n++;
        }
        return n;
    }

    @Test
    void writesStopAtTheOnlineOrIdleBudget() {
        BuildBudget b = new BuildBudget(() -> nanos);
        assertTrue(b.begin(CFG, true, 20), "a healthy server builds");
        assertEquals(500, opsUntilRefused(b), "500 writes a tick while anyone is online");
        b.end();
        assertTrue(b.begin(CFG, false, 20), "the next tick starts afresh");
        assertEquals(5000, opsUntilRefused(b), "5000 while nobody is");
        b.end();
        assertEquals(5500, b.ops(), "the total is kept for status");
        assertEquals(2, b.ticks(), "as are the ticks that did work");
    }

    @Test
    void timeIsCheckedEvery32WritesAndBeforeEachSnapshot() {
        BuildBudget b = new BuildBudget(() -> nanos);
        b.begin(CFG, false, 20);
        for (int i = 0; i < 40; i++) {
            assertTrue(b.op(), "write " + i + " is in time");
        }
        nanos += 5_000_000L; // 5 ms: over the 4 ms budget
        int more = opsUntilRefused(b);
        assertEquals(24, more, "it keeps going until the next 32-write check (40 -> 64), then stops");
        assertFalse(b.snapshot(), "and no snapshot is taken over time either");
        b.end();
        assertEquals(5.0, b.maxMillis(), 0.001, "the longest tick is remembered");

        BuildBudget s = new BuildBudget(() -> nanos);
        s.begin(CFG, false, 20);
        for (int i = 0; i < CFG.snapshotsPerTick(); i++) {
            assertTrue(s.snapshot(), "snapshot " + i + " fits");
        }
        assertFalse(s.snapshot(), "snapshots_per_tick is the most a tick takes");
    }

    @Test
    void aSlowServerPausesBuildingUntilItIsAQuarterBelowTheLine() {
        BuildBudget b = new BuildBudget(() -> nanos);
        assertTrue(b.begin(CFG, false, 39), "39 ms is under the 40 ms line");
        b.end();
        assertFalse(b.begin(CFG, false, 41), "41 ms pauses it");
        assertFalse(b.op(), "no write while paused");
        assertFalse(b.snapshot(), "no snapshot either");
        b.end();
        assertEquals(1, b.pauses(), "one pause, for one WARN");
        assertFalse(b.begin(CFG, false, 35), "35 ms isn't low enough to resume");
        b.end();
        assertFalse(b.begin(CFG, false, 30), "nor is 30 exactly");
        b.end();
        assertTrue(b.begin(CFG, false, 29.9), "below 30 it goes on");
        assertTrue(b.op(), "and writes again");
        b.end();
        assertEquals(1, b.pauses(), "still one pause");
        assertEquals(30.0, CFG.resumeBelowMspt(), 1e-9, "a quarter below 40 is 30");
    }
}
