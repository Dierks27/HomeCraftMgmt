package com.dierks.homecraft.mini;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The print-grade ladder: a Mini's polish tier, rolled at the Printer (or by a
 * wild drop / crate) from the published odds. Three grades — STANDARD (☆),
 * GRADED (★★), MINT (★★★). Grade owns the <b>stars</b> and the value multiplier;
 * rarity owns the name colour and glint (a grade never competes for colour).
 *
 * <p>Names, symbols and multipliers are config data ({@code minis.grades}) applied
 * through {@link #configure} on every load; the built-in values are the defaults.
 * Legacy five-grade names (Gray/Green → STANDARD, Blue/Purple → GRADED, Gold →
 * MINT) parse transparently so existing items migrate on read.
 */
public enum Grade {
    STANDARD("Standard", "☆", 1.0),
    GRADED("Graded", "★★", 2.5),
    MINT("Mint", "★★★", 6.0);

    /** Config-overridable presentation of a grade. */
    public record Style(String display, String symbol, double valueMultiplier) {
    }

    private static volatile Map<Grade, Style> styles = new EnumMap<>(Grade.class);

    private final String defaultDisplay;
    private final String defaultSymbol;
    private final double defaultMultiplier;

    Grade(String defaultDisplay, String defaultSymbol, double defaultMultiplier) {
        this.defaultDisplay = defaultDisplay;
        this.defaultSymbol = defaultSymbol;
        this.defaultMultiplier = defaultMultiplier;
    }

    /** Apply config overrides (missing grades keep their built-in defaults). */
    public static void configure(Map<Grade, Style> overrides) {
        Map<Grade, Style> next = new EnumMap<>(Grade.class);
        if (overrides != null) {
            next.putAll(overrides);
        }
        styles = next;
    }

    public String symbol() {
        Style s = styles.get(this);
        return s != null && usable(s.symbol()) ? s.symbol() : defaultSymbol;
    }

    /**
     * True when a configured symbol is something we can actually show.
     *
     * <p>The one rejection worth making is a symbol that is nothing but question marks.
     * That is not a choice anybody types; it is what a UTF-8 file looks like after an
     * editor saves it back as ANSI/Windows-1252 — every character it cannot represent
     * becomes the byte {@code '?'}. The stars go in as ☆ and come out as "?", so a Mini
     * renders as "Creeper ?" and the cause is invisible from in-game.
     *
     * <p>Falling back to the built-in star costs an admin nothing (a deliberate symbol is
     * never all question marks) and turns a permanently broken name into a cosmetic
     * no-op while they fix the file — which {@code HomeCraftManagement.repairGradeSymbols}
     * also offers to do for them.
     */
    static boolean usable(String symbol) {
        return symbol != null && !symbol.isBlank() && !isMangledSymbol(symbol);
    }

    /** True if {@code symbol} is the wreckage of a non-UTF-8 save: question marks and nothing else. */
    public static boolean isMangledSymbol(String symbol) {
        if (symbol == null) {
            return false;
        }
        String s = symbol.trim();
        return !s.isEmpty() && s.chars().allMatch(ch -> ch == '?');
    }

    public String display() {
        Style s = styles.get(this);
        return s != null && s.display() != null && !s.display().isBlank() ? s.display() : defaultDisplay;
    }

    public double valueMultiplier() {
        Style s = styles.get(this);
        return s != null && s.valueMultiplier() > 0 ? s.valueMultiplier() : defaultMultiplier;
    }

    /** The lowest grade (the floor of every odds table). */
    public static Grade lowest() {
        return STANDARD;
    }

    /** The highest grade. */
    public static Grade highest() {
        return MINT;
    }

    /**
     * Tolerant parse: enum names and config display names (case-insensitive), plus
     * the legacy five-grade names. Anything unknown (or null) reads as STANDARD.
     */
    public static Grade parse(String s) {
        if (s == null) {
            return STANDARD;
        }
        String k = s.trim().toUpperCase(Locale.ROOT);
        if (k.isEmpty()) {
            return STANDARD;
        }
        switch (k) {
            case "GRAY", "GREY", "GREEN" -> {
                return STANDARD;
            }
            case "BLUE", "PURPLE" -> {
                return GRADED;
            }
            case "GOLD" -> {
                return MINT;
            }
            default -> {
                // fall through to enum / display-name matching
            }
        }
        for (Grade g : values()) {
            if (g.name().equals(k) || g.display().toUpperCase(Locale.ROOT).equals(k)) {
                return g;
            }
        }
        return STANDARD;
    }

    /** True if the raw stored name is one of the retired five-grade names (needs a re-render). */
    public static boolean isLegacyName(String s) {
        if (s == null) {
            return false;
        }
        String k = s.trim().toUpperCase(Locale.ROOT);
        return k.equals("GRAY") || k.equals("GREY") || k.equals("GREEN") || k.equals("BLUE")
                || k.equals("PURPLE") || k.equals("GOLD");
    }
}
