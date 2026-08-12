package com.dierks.homecraft.display;

/**
 * Shared trend-arrow formatting for every in-game display (signs, holograms,
 * map-TVs) and the PlaceholderAPI expansion. A commodity's 24h percent change
 * maps to ▲ (up), ▼ (down), or ▬ (flat), with a small dead-zone so tiny noise
 * reads as flat.
 */
public final class Trend {

    private Trend() {
    }

    public static final String UP = "▲";
    public static final String DOWN = "▼";
    public static final String FLAT = "▬";

    private static final double DEAD_ZONE = 0.05; // percent

    public static String arrow(double changePercent) {
        if (changePercent > DEAD_ZONE) {
            return UP;
        }
        if (changePercent < -DEAD_ZONE) {
            return DOWN;
        }
        return FLAT;
    }

    /** A Minecraft colour code (&a/&c/&7) matching the trend direction. */
    public static String color(double changePercent) {
        if (changePercent > DEAD_ZONE) {
            return "&a";
        }
        if (changePercent < -DEAD_ZONE) {
            return "&c";
        }
        return "&7";
    }

    /** e.g. "▲ 3.20%" / "▼ 1.10%" / "▬ 0.00%". */
    public static String label(double changePercent) {
        return arrow(changePercent) + " " + String.format(java.util.Locale.ROOT, "%.2f%%", Math.abs(changePercent));
    }
}
