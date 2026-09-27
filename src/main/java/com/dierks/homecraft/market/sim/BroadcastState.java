package com.dierks.homecraft.market.sim;

import java.util.Objects;

/**
 * The anti-spam counters every market broadcast shares (§6.1, §6.4): when the last one went
 * out, how many the current local day has had, and whether the one-time intro is done.
 * Mutable; {@link MarketSimulator} updates it when a tick broadcasts, and the service does the
 * same for admin-forced broadcasts. Persisted in {@code market_sim_meta} as
 * {@code broadcast_day}, {@code broadcasts_today}, {@code last_broadcast_at} and
 * {@code intro_done}.
 */
public final class BroadcastState {

    private long day = Schedule.NO_DAY;
    private int count;
    private long lastAt;
    private boolean introDone;

    /** Nothing broadcast yet, intro not done. */
    public BroadcastState() {
    }

    /** Read back from meta. */
    public BroadcastState(long day, int count, long lastAt, boolean introDone) {
        this.day = day;
        this.count = Math.max(0, count);
        this.lastAt = lastAt;
        this.introDone = introDone;
    }

    /** Broadcasts on local day {@code day} (0 when the counter is for another day). */
    public int countOn(long localDay) {
        return day == localDay ? count : 0;
    }

    /** Count one broadcast at {@code at} on local day {@code localDay}. */
    public void record(long at, long localDay) {
        if (day != localDay) {
            day = localDay;
            count = 0;
        }
        count++;
        lastAt = at;
    }

    /** The local day {@link #count()} is for. */
    public long day() {
        return day;
    }

    public int count() {
        return count;
    }

    /** When the last market broadcast went out (0 = never). */
    public long lastAt() {
        return lastAt;
    }

    public boolean introDone() {
        return introDone;
    }

    public void setIntroDone(boolean done) {
        this.introDone = done;
    }

    public BroadcastState copy() {
        return new BroadcastState(day, count, lastAt, introDone);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BroadcastState b)) {
            return false;
        }
        return day == b.day && count == b.count && lastAt == b.lastAt && introDone == b.introDone;
    }

    @Override
    public int hashCode() {
        return Objects.hash(day, count, lastAt, introDone);
    }

    @Override
    public String toString() {
        return "BroadcastState[day=" + day + ", count=" + count + ", lastAt=" + lastAt
                + ", introDone=" + introDone + "]";
    }
}
