package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;

import java.util.function.BooleanSupplier;

/**
 * Counted work (GEN-SPEC §4.0, §4.3): simulated putts, not seconds, so a seed gives the same
 * course on a slow host and in CI. A search spends from one of these and gives up when it is
 * spent; the planner decides what that means (a re-roll, or the fallback hole). A cancelled job
 * (a reload, a stop) is noticed here too, between units of work.
 */
final class Work {

    private final long cap;
    private final BooleanSupplier cancelled;
    private long used;
    private int sinceCheck;

    /** Up to {@code cap} putts; {@code cancelled} (may be null) is asked every few hundred. */
    Work(long cap, BooleanSupplier cancelled) {
        this.cap = cap;
        this.cancelled = cancelled == null ? () -> false : cancelled;
    }

    /** Unlimited, never cancelled: for the checks and the tests. */
    static Work unlimited() {
        return new Work(Long.MAX_VALUE, null);
    }

    /** Spend one putt; false once the cap is spent (nothing is spent then). */
    boolean spend() {
        if (used >= cap) {
            return false;
        }
        used++;
        return true;
    }

    /** Spend {@code n} putts that were already played (a replay); may go past the cap. */
    void charge(long n) {
        used += n;
    }

    /** Putts spent so far. */
    long used() {
        return used;
    }

    /** Throw if the job was cancelled; cheap to call often (it asks every 256 calls). */
    void checkCancelled() throws GenFailed {
        if (++sinceCheck >= 256) {
            sinceCheck = 0;
            if (cancelled.getAsBoolean()) {
                throw new GenFailed("cancelled");
            }
        }
    }
}
