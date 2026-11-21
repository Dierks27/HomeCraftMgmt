package com.dierks.homecraft.games.lava.rules;

import java.util.Locale;

/**
 * The Floor Is Lava's three ways to play (the owner's names: Little, Group, Challenge).
 *
 * <ul>
 *   <li>{@link #LITTLE}: nobody can lose. Collect every star, however many times the lava comes.</li>
 *   <li>{@link #GROUP}: everyone together, one shared star count, the lava a block higher every round.</li>
 *   <li>{@link #CHALLENGE}: the lava keeps rising and never drains; touch it and you are out; the last one standing
 *       wins.</li>
 * </ul>
 */
public enum LavaMode {
    LITTLE("Little", "little"),
    GROUP("Group", "group"),
    CHALLENGE("Challenge", "challenge");

    /** What every score board of this game starts with: the secret trophy room reads them as bests. */
    public static final String BOARD_PREFIX = "lava:";

    private final String label;
    private final String id;

    LavaMode(String label, String id) {
        this.label = label;
        this.id = id;
    }

    /** The name players read. */
    public String label() {
        return label;
    }

    /** Its id: {@code /hcm play floor_is_lava_<id>}'s last word, and its score board's ({@link #board}). */
    public String id() {
        return id;
    }

    /** This mode's score board ({@code lava:little}, {@code lava:group}, {@code lava:challenge}). */
    public String board() {
        return BOARD_PREFIX + id;
    }

    /** The mode with this id, or {@code null}. */
    public static LavaMode of(String id) {
        if (id == null) {
            return null;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        for (LavaMode m : values()) {
            if (m.id.equals(k)) {
                return m;
            }
        }
        return null;
    }
}
