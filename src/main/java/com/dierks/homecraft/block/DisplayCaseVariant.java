package com.dierks.homecraft.block;

import java.util.Locale;

/**
 * Display Case pedestal styles. Each has its own head skin ({@code skins.display_case.<key>})
 * and its own vanilla recipe ({@code recipes.display_case.<key>}). The variant travels on
 * the item and the placed tile in PDC ({@code Keys.DISPLAY_VARIANT}); a case with no tag
 * (pre-variant placements) reads as {@link #PLAIN}. The case wears its pedestal skin
 * whether empty or loaded — the Mini floats above it as an ItemDisplay.
 */
public enum DisplayCaseVariant {
    PLAIN("Plain"),
    ROYAL("Royal");

    private final String label;

    DisplayCaseVariant(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DisplayCaseVariant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String k = raw.trim().toUpperCase(Locale.ROOT);
        for (DisplayCaseVariant v : values()) {
            if (v.name().equals(k)) {
                return v;
            }
        }
        return null;
    }

    public static DisplayCaseVariant parseOrPlain(String raw) {
        DisplayCaseVariant v = parse(raw);
        return v != null ? v : PLAIN;
    }
}
