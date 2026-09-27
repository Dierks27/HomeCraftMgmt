package com.dierks.homecraft.market.sim;

import java.util.Locale;

/**
 * Who started a {@code market_events} row. Stored by {@link #name()} (the {@code source}
 * column); {@code /api/news} writes {@link #id()}.
 */
public enum Source {
    /** The planner rolled it. */
    SIM,
    /** An admin forced it with {@code /hcm market news} or {@code /hcm market sim}. */
    ADMIN,
    /** A calendar season. */
    CALENDAR,
    /** A real-world commodity price. */
    REAL;

    /** Lower-case id for the feeds: {@code sim}, {@code admin}, {@code calendar}, {@code real}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive parse; {@code null} for anything unknown. */
    public static Source parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
