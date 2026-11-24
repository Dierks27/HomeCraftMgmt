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
    LITTLE("Little", "little", "little"),
    GROUP("Group", "group", "group2"),
    CHALLENGE("Challenge", "challenge", "challenge2");

    /** What every score board of this game starts with: the secret trophy room reads them as bests. */
    public static final String BOARD_PREFIX = "lava:";

    private final String label;
    private final String id;
    private final String boardId;

    LavaMode(String label, String id, String boardId) {
        this.boardId = boardId;
        this.label = label;
        this.id = id;
    }

    /** The name players read. */
    public String label() {
        return label;
    }

    /** Its id ({@code /hcm} and the settings say it). */
    public String id() {
        return id;
    }

    /**
     * This mode's score board: {@code lava:little}, and (0.57.0) {@code lava:group2} and {@code lava:challenge2}. Group
     * and Challenge became different games in 0.57.0 (star goals and hearts, stars to keep collecting, a moving room,
     * surprises), so their old bests ({@code lava:group}, {@code lava:challenge}) stay where they are as trophies and
     * new boards start; Little is still "every star", its board the same.
     */
    public String board() {
        return BOARD_PREFIX + boardId;
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
