package com.dierks.homecraft.games.gen.api;

import java.util.Locale;

/**
 * The one spelling of every Fresh Courses board (GEN-SPEC §5.2, weekly addendum §3), and the way
 * back from a name.
 *
 * <ul>
 *   <li>{@code gfresh:<id>:<editionKey>} — one layout's leaderboard, in the course's own game
 *       ({@code trials} or {@code golf}), lower is better. The edition key ({@code 7:38},
 *       {@code 7:38r1}) carries the cadence, so a daily and a weekly edition never share a board,
 *       and a reroll is a fresh board; nothing is ever cleared.</li>
 *   <li>{@code gstars:<id>:<edition>} — a player's best stars on a course in one edition (any
 *       reroll: {@code 7:38}), in game {@value #GAME}, higher is better. {@code gstars:<id>:<day>}
 *       (a course day) is still read and written for callers that key stars by day.</li>
 *   <li>{@code gweek:<weekKey>} — the Star Chart: the sum of those bests over the week, in game
 *       {@value #GAME}, higher is better. Per week whatever the cadence.</li>
 * </ul>
 * Days and weeks are local epoch days ({@link Edition}).
 */
public final class GenBoards {

    /** The game the star boards are kept under: Fresh Courses' own id. */
    public static final String GAME = Slots.DAILY;
    public static final String DAY_PREFIX = "gfresh:";
    public static final String STARS_PREFIX = "gstars:";
    public static final String WEEK_PREFIX = "gweek:";

    /** Which board a name is. */
    public enum Kind {
        /** One edition's leaderboard of one course ({@code gfresh:}). */
        DAY,
        /** A player's best stars on a course ({@code gstars:}). */
        STARS,
        /** The Star Chart ({@code gweek:}). */
        WEEK
    }

    /**
     * A board name read back.
     *
     * @param kind     which board
     * @param courseId the course ({@code null} for the week)
     * @param day      for an edition's board, the earliest day that edition can start on
     *                 ({@link Edition.Key#firstDay}: its real start is at most N-1 days later); a
     *                 day-keyed stars board's course day; the week's first day for {@code gweek}
     * @param reroll   {@code gfresh} only: the edition's reroll (0 for none)
     * @param edition  the edition key without the reroll ({@code 7:38}), or {@code ""} for a week or
     *                 a day-keyed stars board
     */
    public record Board(Kind kind, String courseId, long day, int reroll, String edition) {

        public Board {
            edition = edition == null ? "" : edition;
        }

        /** A board that isn't an edition's (a week, or stars kept by day). */
        public Board(Kind kind, String courseId, long day, int reroll) {
            this(kind, courseId, day, reroll, "");
        }

        /** The edition's cadence in days, or 0 when the board isn't an edition's. */
        public int cadence() {
            Edition.Key k = Edition.Key.parse(edition);
            return k == null ? 0 : k.cadence();
        }
    }

    private GenBoards() {
    }

    /** One layout's leaderboard ({@code editionKey} is {@code 7:38} or {@code 7:38r1}). */
    public static String day(String courseId, String editionKey) {
        return DAY_PREFIX + courseId + ":" + editionKey;
    }

    /** The leaderboard of the layout {@code tag} names. */
    public static String day(GenTag tag) {
        return day(tag.slot(), tag.editionKey());
    }

    /** A course's best-stars board for one edition ({@code edition} without a reroll: {@code 7:38}). */
    public static String stars(String courseId, String edition) {
        return STARS_PREFIX + courseId + ":" + edition;
    }

    /**
     * A course's best-stars board for the edition {@code tag} is in (any reroll). For an archived
     * course recalled into a Classics slot (GEN-SPEC-KEEP §3) it is the recall's own board, kept
     * by the day it was recalled ({@code gstars:<classic slot>:<day>}): its stars count toward the
     * current week's chart even for a player who earned them on the original, while its time board
     * and first-finish reward stay the original edition's.
     */
    public static String stars(GenTag tag) {
        if (tag.recall() != null) {
            return stars(tag.recall().slot(), tag.recall().day());
        }
        return stars(tag.slot(), tag.edition());
    }

    /**
     * The one-time first-finish reward's ref for the layout {@code tag} names:
     * {@code fresh:<slot>:<edition>} (no reroll). A recalled course carries its original slot and
     * edition, so this is the ORIGINAL edition's ref: whoever cleared it back then isn't paid
     * again, and a new player is.
     */
    public static String clearRef(GenTag tag) {
        return "fresh:" + tag.slot() + ":" + tag.edition();
    }

    /** A course's best-stars board kept by course day (for callers that key stars by day). */
    public static String stars(String courseId, long day) {
        return STARS_PREFIX + courseId + ":" + day;
    }

    /** The Star Chart for a week. */
    public static String week(long weekKey) {
        return WEEK_PREFIX + weekKey;
    }

    /** A board name read back, or {@code null} when it isn't a well-formed Fresh Courses board. Never throws. */
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
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            String id = rest.substring(0, colon);
            String key = rest.substring(colon + 1).toLowerCase(Locale.ROOT);
            if (!day && key.indexOf(':') < 0) {
                return new Board(Kind.STARS, id, Long.parseLong(key), 0); // stars kept by course day
            }
            Edition.Key k = Edition.Key.parse(key);
            if (k == null || !key.equals(k.toString()) || (!day && k.reroll() > 0)) {
                return null; // a stars board is per edition, never per reroll
            }
            return new Board(day ? Kind.DAY : Kind.STARS, id, k.firstDay(), k.reroll(), k.base());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether {@code board} is a Fresh Courses board. */
    public static boolean generated(String board) {
        return parse(board) != null;
    }
}
