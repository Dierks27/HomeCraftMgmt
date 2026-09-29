package com.dierks.homecraft.games;

/**
 * Every way a skill game can pay tokens (spec §6.1, R1.22, R2.16).
 *
 * <p>The kind decides the rules the database enforces, so no game can pay a reward the wrong way:
 * <ul>
 *   <li>{@link #capped()}: counts toward the game's {@code daily_cap} and the server-wide
 *       {@code games.skill_daily_cap}. Only a {@link #FIRST_CLEAR} is exempt: it is bounded by
 *       the number of courses.</li>
 *   <li>{@link #acrossGames()}: once per day across ALL games, not per game (the featured bonus,
 *       the course of the week), so its row is stored under the game {@code '*'}.</li>
 *   <li>{@link #repeatable()}: may be paid again (stored with an empty ref). Every other kind is
 *       one-time and carries a stable ref ({@code daily:<day>}, {@code ms:<board>:<n>}, ...)
 *       that the database refuses to pay twice.</li>
 * </ul>
 * A personal best is announced and recorded but pays nothing (R1.22); the kind exists so the
 * rules stay in one place if that ever changes.
 */
public enum RewardKind {
    /** A new personal best (pays nothing as shipped). */
    PERSONAL_BEST,
    /** The daily challenge, once per game per day (ref {@code daily:<day>}). */
    DAILY_CHALLENGE,
    /** A bronze/silver/gold score threshold on a board, once ever (ref {@code ms:<board>:<n>}). */
    MILESTONE,
    /** The first finish of today's featured game, once a day across games (ref {@code featured:<day>}). */
    FEATURED,
    /** The first finish of a course, once ever and not capped (ref {@code first_clear:<course>}). */
    FIRST_CLEAR,
    /** The week's best time on a course, once per course per week (ref {@code weekly:<course>:<week>}). */
    WEEKLY_BEST,
    /** Finishing the course of the week, once a day across games (ref {@code cotw:<day>}). */
    COURSE_OF_WEEK,
    /** Golf at par or better, once per course per day (ref {@code par:<course>:<day>}). */
    PAR,
    /** A golf hole-in-one in a finished run, once per hole per day (ref {@code hio:<course>:<hole>:<day>}). */
    HOLE_IN_ONE,
    /**
     * The first counted finish of a Daily Courses layout's course day, once per course per course
     * day, a reroll included (ref {@code dclear:<course>:<day>}). Capped, per game.
     */
    DAILY_CLEAR;

    /** Whether it counts toward the daily caps (everything but a first clear). */
    public boolean capped() {
        return this != FIRST_CLEAR;
    }

    /** Whether it is once per day across every game (stored under the game {@code '*'}). */
    public boolean acrossGames() {
        return this == FEATURED || this == COURSE_OF_WEEK;
    }

    /** Whether it may be paid more than once for the same thing (stored with an empty ref). */
    public boolean repeatable() {
        return this == PERSONAL_BEST;
    }
}
