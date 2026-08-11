package com.dierks.homecraft.mini;

import java.util.Set;

/** Helpers for deriving stable, unique Mini ids from display names. */
public final class MiniIds {

    private MiniIds() {
    }

    /** Lower-case, underscore-separated slug of a name (e.g. "Piggy Mini" → "piggy_mini"). */
    public static String slug(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /**
     * A slug guaranteed not to collide with {@code taken}: appends {@code _2},
     * {@code _3}, … until free. Blank names fall back to "mini".
     */
    public static String unique(String name, Set<String> taken) {
        String base = slug(name);
        if (base.isBlank()) {
            base = "mini";
        }
        if (!taken.contains(base)) {
            return base;
        }
        int n = 2;
        while (taken.contains(base + "_" + n)) {
            n++;
        }
        return base + "_" + n;
    }
}
