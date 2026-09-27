package com.dierks.homecraft.market.sim;

/**
 * Who is online, as far as announcements care: how many players could hear one, and how long
 * the longest of those sessions has run. {@code MarketNewsService} builds it from join times
 * (muted players left out); tests build it from a schedule.
 *
 * <p>A due flash is only released once someone has been on for its join delay (§5.4), so
 * logging in cannot trigger news, only let news that is already due go out.
 *
 * @param count            online players who would receive a broadcast
 * @param longestSessionMs the longest of their current sessions, in ms (0 when nobody is on)
 */
public record OnlineInfo(int count, long longestSessionMs) {

    /** Nobody online. */
    public static final OnlineInfo NOBODY = new OnlineInfo(0, 0L);

    public OnlineInfo {
        count = Math.max(0, count);
        longestSessionMs = count == 0 ? 0L : Math.max(0L, longestSessionMs);
    }

    /** True when anyone is online. */
    public boolean anyone() {
        return count > 0;
    }

    /** True when someone is online and has been for at least {@code delayMs}. */
    public boolean ready(long delayMs) {
        return count > 0 && longestSessionMs >= delayMs;
    }
}
