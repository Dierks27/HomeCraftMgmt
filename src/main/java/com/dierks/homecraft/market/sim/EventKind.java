package com.dierks.homecraft.market.sim;

import java.util.Locale;

/**
 * What a {@code market_events} row is. Stored by {@link #name()} (the {@code kind} column);
 * the feeds use {@link #id()}.
 *
 * <ul>
 *   <li>{@link #HOT} / {@link #DEAL}: a story — silent ramp, hold at full strength, fade.
 *       Up to +15% / -15%.</li>
 *   <li>{@link #UP} / {@link #DOWN}: a NEWS FLASH — an instant jump of up to 25% that decays
 *       (6 h half-life, gone at 30 h).</li>
 *   <li>{@link #WANTED}: an info-only flash for a sold-out item. No effect on price.</li>
 *   <li>{@link #SEASON}: a calendar season started. Its effect comes from the calendar, not
 *       the row.</li>
 *   <li>{@link #REAL}: a real-world commodity move — instant, then decays (24 h half-life,
 *       gone at 96 h). A predictable layer, capped with the seasons.</li>
 * </ul>
 */
public enum EventKind {
    HOT, DEAL, UP, DOWN, WANTED, SEASON, REAL;

    /** HOT or DEAL: the ramp / hold / fade shape. */
    public boolean story() {
        return this == HOT || this == DEAL;
    }

    /** UP or DOWN: a NEWS FLASH. */
    public boolean news() {
        return this == UP || this == DOWN;
    }

    /** UP, DOWN or REAL: an instant jump that decays ({@link SimMath#shock}). */
    public boolean shock() {
        return this == UP || this == DOWN || this == REAL;
    }

    /**
     * HOT, DEAL, UP or DOWN: the kinds summed into the event part {@code e} of the multiplier.
     * REAL is deliberately NOT one of them — it belongs to the predictable layer {@code q},
     * which is capped with the seasons.
     */
    public boolean mood() {
        return story() || news();
    }

    /** +1 for HOT/UP, -1 for DEAL/DOWN, 0 for everything else (REAL carries its own sign). */
    public int sign() {
        return switch (this) {
            case HOT, UP -> 1;
            case DEAL, DOWN -> -1;
            default -> 0;
        };
    }

    /** Lower-case id for the feeds and commands: {@code hot}, {@code deal}, {@code up}, … */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive parse of a name or id; {@code null} for anything unknown. */
    public static EventKind parse(String s) {
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
