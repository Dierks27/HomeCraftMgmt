package com.dierks.homecraft.mini;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The print-grade ladder (Phase 9): a printed Mini's polish tier, rolled at the
 * Printer from the card's published odds. Grade drives the Mini's name colour +
 * glint and its value multiplier (§3.5 / Part F).
 *
 * <p>Gray → Green → Blue → Purple → Gold, ascending.
 */
public enum Grade {
    GRAY("⬜", "Gray", NamedTextColor.GRAY, false, 1.0),
    GREEN("🟩", "Green", NamedTextColor.GREEN, false, 1.6),
    BLUE("🟦", "Blue", NamedTextColor.AQUA, true, 2.6),
    PURPLE("🟪", "Purple", NamedTextColor.LIGHT_PURPLE, true, 4.2),
    GOLD("🟨", "Gold", NamedTextColor.GOLD, true, 8.0);

    private final String symbol;
    private final String display;
    private final NamedTextColor color;
    private final boolean glint;
    private final double valueMultiplier;

    Grade(String symbol, String display, NamedTextColor color, boolean glint, double valueMultiplier) {
        this.symbol = symbol;
        this.display = display;
        this.color = color;
        this.glint = glint;
        this.valueMultiplier = valueMultiplier;
    }

    public String symbol() {
        return symbol;
    }

    public String display() {
        return display;
    }

    public NamedTextColor color() {
        return color;
    }

    /** Whether a printed Mini of this grade carries an enchant glint. */
    public boolean glint() {
        return glint;
    }

    public double valueMultiplier() {
        return valueMultiplier;
    }

    /** Tolerant parse (case-insensitive); defaults to GRAY on anything unknown. */
    public static Grade parse(String s) {
        if (s == null) {
            return GRAY;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GRAY;
        }
    }
}
