package com.dierks.homecraft.muffler;

import java.util.Locale;

/**
 * What a Sound Muffler does to one sound. "Normal" is not a level: it is the absence of one.
 *
 * <p>{@link #ALLOW} exists only on a single picked sound, as the exception to a group — "hush the
 * villagers, but let me hear them trade". A group is never set to it.
 */
public enum MuffleLevel {
    /** Always play this one, whatever its group says. */
    ALLOW("&aAlways play", "&a"),
    /** Played at the muffler's quieter volume. */
    QUIETER("&eQuieter", "&e"),
    /** Not played at all. */
    SILENT("&cSilent", "&c");

    private final String label;
    private final String colour;

    MuffleLevel(String label, String colour) {
        this.label = label;
        this.colour = colour;
    }

    /** '&amp;'-coded name for menus. */
    public String label() {
        return label;
    }

    /** The colour code the level is shown in. */
    public String colour() {
        return colour;
    }

    /** Whether this level actually changes a sound (ALLOW lets it through untouched). */
    public boolean hushes() {
        return this != ALLOW;
    }

    /** The case-insensitive name, or null for anything else. */
    public static MuffleLevel parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A group's click cycle: Normal → Quieter → Silent → Normal ({@code null} is Normal). */
    public static MuffleLevel nextForGroup(MuffleLevel current) {
        if (current == null || current == ALLOW) {
            return QUIETER;
        }
        return current == QUIETER ? SILENT : null;
    }

    /** A group's cycle backwards (right-click). */
    public static MuffleLevel previousForGroup(MuffleLevel current) {
        if (current == null || current == ALLOW) {
            return SILENT;
        }
        return current == SILENT ? QUIETER : null;
    }

    /** A single sound's cycle: not picked → Quieter → Silent → Always play → not picked. */
    public static MuffleLevel nextForSound(MuffleLevel current) {
        if (current == null) {
            return QUIETER;
        }
        return switch (current) {
            case QUIETER -> SILENT;
            case SILENT -> ALLOW;
            case ALLOW -> null;
        };
    }

    /** A single sound's cycle backwards (right-click). */
    public static MuffleLevel previousForSound(MuffleLevel current) {
        if (current == null) {
            return ALLOW;
        }
        return switch (current) {
            case ALLOW -> SILENT;
            case SILENT -> QUIETER;
            case QUIETER -> null;
        };
    }
}
