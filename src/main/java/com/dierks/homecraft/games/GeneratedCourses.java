package com.dierks.homecraft.games;

import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.List;

/**
 * What the course engines ask about generated courses (GEN-SPEC §0.2 R5, §3.4), through
 * {@link GamesService#generated()}.
 *
 * <p>A generated course is playable only while the Fresh Courses engine vouches for its blocks:
 * the gate is shut at every boot until the live half is verified, and shut whenever the
 * {@code fresh_courses} game is off. So the engines don't trust a row with a {@code gen:} block on its own;
 * they ask {@link #live}. A run in progress keeps counting while the layout it started on still
 * stands ({@link #standing}), even after the next layout went live.
 *
 * <p>Until the engine is installed — and whenever Fresh Courses is off — this is {@link #NONE}: no
 * generated course is live, none is standing, and no area is protected, so hand-built courses
 * behave exactly as before and a leftover generated row simply stays closed.
 */
public interface GeneratedCourses {

    /**
     * Whether course {@code courseId} may be played now. A course with no tag (hand-built) is not
     * this gate's business: always true. A generated one only while its live layout is verified.
     */
    boolean live(String courseId, GenTag tag);

    /**
     * Whether the layout {@code tag} names still stands: the live layout, or the previous one until
     * its half starts being cleared. A run on a standing layout counts. False for no tag.
     */
    boolean standing(GenTag tag);

    /** What a player reads when generated course {@code courseId} can't be played now. */
    String closedLine(String courseId);

    /** When the next set of courses is due (epoch ms), or -1 when nothing is scheduled. */
    long nextChangeAt();

    /** Whether block (x, y, z) of {@code world} is inside a Fresh Courses half (anyone's edits are refused there). */
    boolean inArea(String world, int x, int y, int z);

    // ---- what a finish pays (games.fresh): the shipped values until the engine says otherwise ----

    /**
     * The tokens the first counted finish of course {@code courseId} pays in its set
     * ({@code games.fresh.rewards}, GEN-SPEC §5.3, weekly addendum §4): the slot's shipped weekly
     * amount by default, 0 for a course that isn't a slot.
     */
    default int dailyClear(String courseId) {
        Slots.Def slot = Slots.of(courseId);
        return slot == null ? 0 : slot.weeklyClear();
    }

    /**
     * What the first counted finish of {@code courseId} pays in a set of {@code cadence} days: pay
     * with the run's own {@code tag.cadence()} (and {@code tag.slot()}), so a layout kept over a
     * cadence change pays by the set it is. The engine scales between the daily and weekly tables.
     */
    default int dailyClear(String courseId, int cadence) {
        return dailyClear(courseId);
    }

    /**
     * The Star Chart goals of the week starting {@code weekKey}, each with its own tokens, fixed
     * for the week once handed out: pay and show only these. Shipped: 6 stars (+1) and 12 (+2).
     */
    default List<DailyStars.Goal> goals(long weekKey) {
        return SHIPPED_GOALS;
    }

    /** This week's Star Chart goals ({@code games.fresh.star_goals}; shipped weekly 6 and 12). */
    default List<Integer> starGoals() {
        return SHIPPED_STAR_GOALS;
    }

    /**
     * Tokens per Star Chart goal, once a week each, for a caller that pays every goal the same
     * (the smallest goal's: shipped 1). The engine knows each goal's own ({@code star_goals}).
     */
    default int starGoalReward() {
        return 1;
    }

    /** The most star-goal tokens a day ({@code games.fresh.daily_cap}; shipped 2). */
    default int starGoalCap() {
        return 2;
    }

    /** {@code games.fresh.star_goals.weekly} as shipped. */
    List<Integer> SHIPPED_STAR_GOALS = List.of(6, 12);

    /** {@code games.fresh.star_goals.weekly} and {@code weekly_tokens} as shipped. */
    List<DailyStars.Goal> SHIPPED_GOALS = List.of(new DailyStars.Goal(6, 1), new DailyStars.Goal(12, 2));

    /** No engine, or Fresh Courses is off: nothing generated is live or standing; no area is kept. */
    GeneratedCourses NONE = new GeneratedCourses() {
        @Override
        public boolean live(String courseId, GenTag tag) {
            return tag == null;
        }

        @Override
        public boolean standing(GenTag tag) {
            return false;
        }

        @Override
        public String closedLine(String courseId) {
            Slots.Def slot = Slots.of(courseId);
            return GenCopy.closed(slot == null ? "That course" : slot.name());
        }

        @Override
        public long nextChangeAt() {
            return -1;
        }

        @Override
        public boolean inArea(String world, int x, int y, int z) {
            return false;
        }
    };
}
