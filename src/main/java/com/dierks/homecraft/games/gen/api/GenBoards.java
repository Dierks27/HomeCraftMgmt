package com.dierks.homecraft.games.gen.api;

import java.util.Locale;

/**
 * The one spelling of every Daily Courses board (GEN-SPEC §5.2), and the way back from a name.
 *
 * <ul>
 *   <li>{@code gday:<id>:<editionKey>} — one layout's leaderboard ("Today's best"), in the course's
 *       own game ({@code trials} or {@code golf}), lower is better. A reroll is a new edition and
 *       so a fresh board; nothing is ever cleared.</li>
 *   <li>{@code gstars:<id>:<day>} — a player's best stars on a course that course-day (any reroll),
 *       in game {@value #GAME}, higher is better.</li>
 *   <li>{@code gweek:<weekKey>} — the Star Chart: the sum of those bests over the week, in game
 *       {@value #GAME}, higher is better.</li>
 * </ul>
 * Days and weeks are local epoch days ({@link Edition}).
 */
public final class GenBoards {

    /** The game the star boards are kept under. */
    public static final String GAME = "daily";
    public static final String DAY_PREFIX = "gday:";
    public static final String STARS_PREFIX = "gstars:";
    public static final String WEEK_PREFIX = "gweek:";

    /** Which board a name is. */
    public enum Kind {
        DAY, STARS, WEEK
    }

    /**
     * A board name read back.
     *
     * @param kind     which board
     * @param courseId the course ({@code null} for the week)
     * @param day      the course day ({@code gday}, {@code gstars}); the week's first day for {@code gweek}
     * @param reroll   {@code gday} only: the edition's reroll (0 for none)
     */
    public record Board(Kind kind, String courseId, long day, int reroll) {
    }

    private GenBoards() {
    }

    /** One layout's leaderboard. */
    public static String day(String courseId, String editionKey) {
        return DAY_PREFIX + courseId + ":" + editionKey;
    }

    /** The leaderboard of the layout {@code tag} names. */
    public static String day(GenTag tag) {
        return day(tag.slot(), tag.editionKey());
    }

    /** A course's best-stars board for a course day. */
    public static String stars(String courseId, long day) {
        return STARS_PREFIX + courseId + ":" + day;
    }

    /** The Star Chart for a week. */
    public static String week(long weekKey) {
        return WEEK_PREFIX + weekKey;
    }

    /** A board name read back, or {@code null} when it isn't a well-formed Daily Courses board. */
    public static Board parse(String board) {
        if (board == null) {
            return null;
        }
        try {
            if (board.startsWith(WEEK_PREFIX)) {
                return new Board(Kind.WEEK, null, Long.parseLong(board.substring(WEEK_PREFIX.length())), 0);
            }
            boolean day = board.startsWith(DAY_PREFIX);
            if (!day && !board.startsWith(STARS_PREFIX)) {
                return null;
            }
            String rest = board.substring(day ? DAY_PREFIX.length() : STARS_PREFIX.length());
            int colon = rest.lastIndexOf(':');
            if (colon <= 0) {
                return null;
            }
            String id = rest.substring(0, colon);
            String key = rest.substring(colon + 1).toLowerCase(Locale.ROOT);
            int r = key.indexOf('r');
            if (r >= 0 && !day) {
                return null; // a stars board is per day, never per reroll
            }
            long d = Long.parseLong(r < 0 ? key : key.substring(0, r));
            int reroll = r < 0 ? 0 : Integer.parseInt(key.substring(r + 1));
            if (reroll < 0 || (r >= 0 && reroll == 0)) {
                return null;
            }
            return new Board(day ? Kind.DAY : Kind.STARS, id, d, reroll);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether {@code board} is a Daily Courses board. */
    public static boolean generated(String board) {
        return parse(board) != null;
    }
}
