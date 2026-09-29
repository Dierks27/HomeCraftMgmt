package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;

import java.util.function.LongSupplier;

/**
 * How much building one tick may do (GEN-SPEC §3.3, steps 4-6, 8): at most
 * {@code blocks_per_tick} block writes while anyone is online ({@code blocks_per_tick_idle} while
 * nobody is), at most {@code max_ms_per_tick} of main-thread time (checked with {@code nanoTime}
 * every 32 writes, and before every chunk snapshot), at most {@code snapshots_per_tick} chunk
 * snapshots, and nothing at all while the server is struggling: building pauses when Paper's
 * average tick time goes above {@code pause_above_mspt} and resumes only once it is back a quarter
 * lower (30 for 40), so it can't flap on the line.
 *
 * <p>Pure: the clock is handed in, so tests drive it tick by tick. It also keeps the numbers the
 * status line shows ("372 ops in 2 ticks (max 1.1 ms)").
 */
public final class BuildBudget {

    /** The time budget is checked every this many writes. */
    public static final int CHECK_EVERY = 32;

    private final LongSupplier nanos;
    private DailySettings.Budget cfg;
    private boolean paused;
    private int pauses;
    private boolean working;
    private int ops;
    private int opLimit;
    private int snapshots;
    private long tickStart;
    private boolean tickUsed;
    // for status and logs
    private int ticks;
    private long totalOps;
    private long maxNanos;

    public BuildBudget(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /**
     * Start a tick.
     *
     * @param online whether anyone is online (the smaller write budget)
     * @param mspt   Paper's average tick time
     * @return whether building may run this tick (false while paused)
     */
    public boolean begin(DailySettings.Budget budget, boolean online, double mspt) {
        this.cfg = budget;
        if (paused && mspt < budget.resumeBelowMspt()) {
            paused = false;
        } else if (!paused && mspt > budget.pauseAboveMspt()) {
            paused = true;
            pauses++;
        }
        working = !paused;
        ops = 0;
        snapshots = 0;
        tickUsed = false;
        opLimit = online ? budget.blocksPerTick() : budget.blocksPerTickIdle();
        tickStart = nanos.getAsLong();
        return working;
    }

    /** May one more block be written this tick? Counts it when it may. */
    public boolean op() {
        if (!working || ops >= opLimit) {
            return false;
        }
        if (ops % CHECK_EVERY == 0 && overTime()) {
            return false;
        }
        ops++;
        totalOps++;
        tickUsed = true;
        return true;
    }

    /** May one more chunk be snapshotted this tick? Counts it when it may. */
    public boolean snapshot() {
        if (!working || snapshots >= cfg.snapshotsPerTick() || overTime()) {
            return false;
        }
        snapshots++;
        tickUsed = true;
        return true;
    }

    /** End the tick: remember how long it took, if it did anything. */
    public void end() {
        if (working && tickUsed) {
            ticks++;
            maxNanos = Math.max(maxNanos, nanos.getAsLong() - tickStart);
        }
        working = false;
    }

    /** Whether building is paused for a slow server. */
    public boolean paused() {
        return paused;
    }

    /** How many times this budget paused (a WARN is logged for the first, once per build). */
    public int pauses() {
        return pauses;
    }

    /** Ticks that did any work. */
    public int ticks() {
        return ticks;
    }

    /** Writes allowed so far. */
    public long ops() {
        return totalOps;
    }

    /** The longest tick, in milliseconds. */
    public double maxMillis() {
        return maxNanos / 1_000_000.0;
    }

    private boolean overTime() {
        return nanos.getAsLong() - tickStart >= cfg.maxMsPerTick() * 1_000_000L;
    }
}
