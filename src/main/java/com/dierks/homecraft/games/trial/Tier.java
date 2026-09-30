package com.dierks.homecraft.games.trial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How hard a course is (spec §11). The tier decides the one-time first-clear reward
 * ({@code games.trials.first_clear.<tier>}) and the order courses are listed in; the hardest one
 * is named the way the builders feel about it once it is finished.
 */
public enum Tier {
    EASY("easy", "Easy"),
    MEDIUM("medium", "Medium"),
    HARD("hard", "Hard"),
    EXTREME("extreme", "Why did we build this?");

    private final String id;
    private final String label;

    Tier(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** The config and storage word ({@code easy}). */
    public String id() {
        return id;
    }

    /** What players read ("Why did we build this?"). */
    public String label() {
        return label;
    }

    /** The tier called {@code word} (any case), or {@code null}. */
    public static Tier of(String word) {
        if (word == null) {
            return null;
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        for (Tier t : values()) {
            if (t.id.equals(w)) {
                return t;
            }
        }
        return null;
    }

    /** Every tier's word, easiest first. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Tier t : values()) {
            out.add(t.id);
        }
        return out;
    }
}
