package com.dierks.homecraft.market.sim;

import java.util.Locale;

/**
 * The badge an item shows on tiles, signs, holograms, TVs and placeholders.
 *
 * <p>Precedence when several apply: UP/DOWN &gt; HOT/DEAL &gt; WANTED ({@link #precedence()}).
 * The "Hot &amp; Deals" sort orders HOT, UP, DEAL, DOWN, WANTED, then no badge
 * ({@link #sortRank()}).
 */
public enum Badge {
    NONE, HOT, DEAL, UP, DOWN, WANTED;

    /** True for every badge except {@link #NONE}. */
    public boolean shown() {
        return this != NONE;
    }

    /** Higher wins when an item qualifies for more than one: UP/DOWN 3, HOT/DEAL 2, WANTED 1, NONE 0. */
    public int precedence() {
        return switch (this) {
            case UP, DOWN -> 3;
            case HOT, DEAL -> 2;
            case WANTED -> 1;
            case NONE -> 0;
        };
    }

    /** Position in the "Hot &amp; Deals" sort: HOT 0, UP 1, DEAL 2, DOWN 3, WANTED 4, NONE 5. */
    public int sortRank() {
        return switch (this) {
            case HOT -> 0;
            case UP -> 1;
            case DEAL -> 2;
            case DOWN -> 3;
            case WANTED -> 4;
            case NONE -> 5;
        };
    }

    /** The badge an event of this kind shows while it is badged; {@link #NONE} for SEASON/REAL. */
    public static Badge of(EventKind kind) {
        if (kind == null) {
            return NONE;
        }
        return switch (kind) {
            case HOT -> HOT;
            case DEAL -> DEAL;
            case UP -> UP;
            case DOWN -> DOWN;
            case WANTED -> WANTED;
            case SEASON, REAL -> NONE;
        };
    }

    /** Lower-case id for the feeds ({@code hot}, {@code deal}, …); empty for {@link #NONE}. */
    public String id() {
        return this == NONE ? "" : name().toLowerCase(Locale.ROOT);
    }
}
