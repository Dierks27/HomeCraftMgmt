package com.dierks.homecraft.games;

/**
 * Every way a skill game can pay tokens (spec §6.1, R1.22, R2.16).
 *
 * <p>The kind decides the rules the database enforces, so no game can pay a reward the wrong way:
 * <ul>
 *   <li>{@link #capped()}: counts toward the game's {@code daily_cap} and the server-wide
 *       {@code games.skill_daily_cap}. A {@link #FIRST_CLEAR} is exempt (it is bounded by the
 *       number of courses), and so is an {@link #EVENT_PRIZE} (it is bounded by the prize rules:
 *       at most one prize a player a night, on at most a few prize nights a week).</li>
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
    /**
     * Golf at par or better, once per course per day (ref {@code par:<course>:<day>}); on a Fresh
     * Courses layout once per set ({@code par:<slot>:<edition>}).
     */
    PAR,
    /**
     * A golf hole-in-one in a finished run, once per hole per day (ref {@code hio:<course>:<hole>:<day>});
     * on a Fresh Courses layout once per set ({@code hio:<slot>:<hole>:<edition>}).
     */
    HOLE_IN_ONE,
    /**
     * FRESH_CLEAR: the first counted finish of a Fresh Courses course in one set, once per course
     * per set, a reroll included (ref {@code fresh:<slot>:<edition>}, {@link SkillRewards#freshClearRef}).
     * Capped, per game, and paid all or nothing ({@link SkillRewards#payWhole}): a day whose caps
     * can't pay all of it pays none and records none, so a later day of the set still can. The
     * constant keeps its first name so nothing stored under it changes.
     */
    DAILY_CLEAR,
    /**
     * A Race Night prize (EVENTS-DROPPER-SPEC §A.3, D1): the server's fixed tokens for the night's
     * places and finishers, once per player per night (ref {@code event:<id>},
     * {@link SkillRewards#eventRef}). Not capped: the skill caps are a day's budget for play, and a
     * prize night that a Snake afternoon had already "used up" would pay nothing. It is bounded by
     * construction instead: one prize a player a night, and only on the week's few prize nights.
     * One-time and per game, and paid through {@link SkillRewards#pay}, so never for a game of
     * chance.
     */
    EVENT_PRIZE;

    /** Whether it counts toward the daily caps (everything but a first clear and an event prize). */
    public boolean capped() {
        return this != FIRST_CLEAR && this != EVENT_PRIZE;
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
