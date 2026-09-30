package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Star Chart's arithmetic and the cadence's rewards (GEN-SPEC §5.2, §5.3, weekly addendum §4),
 * pure so it is tested on its own and the database only has to store what it says.
 *
 * <p>The week's total is the sum of each course's BEST stars per edition, kept by delta: a better
 * run adds only the difference to the week, an equal or worse one adds nothing. So the chart only
 * ever rises, replaying a course never inflates it, and a crash between the two writes can't count
 * a run twice (the DAO does both in one transaction).
 *
 * <p>A weekly goal is crossed once, by the run that takes the week from under it to at or over it;
 * its reward ref is {@code ms:gweek:<week>:<goal>}, so it stays the same if the owner reorders the
 * list, and is paid once a week whichever run pays it ({@link #reached}: a goal the day's caps held
 * back is paid by a later run).
 *
 * <p><b>Scaling with the cadence.</b> The owner keeps two ends in config, one for a daily cadence
 * and one for a weekly one, so switching the cadence needs no retuning. A first-finish reward for
 * N days between 1 and 7 is {@code round(daily + (weekly - daily) * (N - 1) / 6)}, and 7 or more
 * uses the weekly end ({@link #scaled}). The Star Chart's goals scale the same way pairwise, their
 * token payouts follow the nearer end, and no goal is ever above 80% of what the week can give:
 * 3 stars × the courses that are on × the editions that start in the week ({@link #goals}).
 */
public final class DailyStars {

    /** No goal is ever above this share of the week's possible stars. */
    public static final double GOAL_CEILING = 0.8;

    private DailyStars() {
    }

    /**
     * One Star Chart goal.
     *
     * @param stars  the stars a player needs in the week
     * @param tokens what reaching it pays, once a week
     */
    public record Goal(int stars, int tokens) {
    }

    // ---- the chart --------------------------------------------------------------------------------

    /** What a run of {@code stars} adds to the week, when the best so far is {@code best} (null: none). */
    public static int delta(Long best, int stars) {
        long before = best == null ? 0 : Math.max(0, best);
        return (int) Math.max(0, Math.min(Stars.MAX, stars) - before);
    }

    /**
     * The goals the week crossed going from {@code before} to {@code after} stars: each goal with
     * {@code before < goal <= after}, smallest first, once each even if the list repeats one. None
     * when the total didn't rise.
     */
    public static List<Integer> crossed(long before, long after, List<Integer> goals) {
        List<Integer> out = new ArrayList<>();
        if (goals == null || after <= before) {
            return out;
        }
        for (int g : sorted(goals)) {
            if (before < g && g <= after) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * Every goal the week's {@code total} has reached, smallest first, once each. A counted finish
     * offers each of them (its once-a-week ref pays it at most once), so a goal crossed on a day
     * the caps were full is paid by a later run that week, like the cabinets' milestones.
     */
    public static List<Integer> reached(long total, List<Integer> goals) {
        List<Integer> out = new ArrayList<>();
        for (int g : sorted(goals)) {
            if (g <= total) {
                out.add(g);
            }
        }
        return out;
    }

    /** The next goal above {@code total}, or -1 when every goal is reached. */
    public static int nextGoal(long total, List<Integer> goals) {
        for (int g : sorted(goals)) {
            if (g > total) {
                return g;
            }
        }
        return -1;
    }

    // ---- the cadence ------------------------------------------------------------------------------

    /**
     * An amount between the daily end and the weekly end for an N-day cadence:
     * {@code round(daily + (weekly - daily) * (N - 1) / 6)} for N from 1 to 7 (halves round up),
     * the weekly end for 7 and more. Exact integer arithmetic, so every N gives the same answer
     * everywhere.
     */
    public static int scaled(int cadence, int daily, int weekly) {
        int n = Edition.clampCadence(cadence);
        if (n >= Edition.WEEKLY) {
            return weekly;
        }
        long sixths = 6L * daily + (long) (weekly - daily) * (n - 1);
        return (int) Math.floorDiv(sixths + 3, 6);
    }

    /** Whether an N-day cadence is nearer the weekly end than the daily one (N = 4, halfway, counts as weekly). */
    public static boolean nearerWeekly(int cadence) {
        int n = Edition.clampCadence(cadence);
        return n - 1 >= Edition.WEEKLY - n;
    }

    /**
     * The Star Chart goals for an N-day cadence, unclamped: the weekly end for N of 7 or more, the
     * daily end for N = 1, and in between each goal {@link #scaled} pairwise (when both ends list
     * the same number of goals; otherwise the nearer end's list), with the nearer end's tokens.
     * Smallest first, each once, none at or below 0.
     */
    public static List<Goal> goals(int cadence, List<Goal> daily, List<Goal> weekly) {
        int n = Edition.clampCadence(cadence);
        List<Goal> d = daily == null ? List.of() : daily;
        List<Goal> w = weekly == null ? List.of() : weekly;
        List<Goal> near = nearerWeekly(n) ? w : d;
        if (n >= Edition.WEEKLY || n == Edition.DAILY || d.size() != w.size()) {
            return tidy(n >= Edition.WEEKLY ? w : n == Edition.DAILY ? d : near);
        }
        List<Goal> out = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            out.add(new Goal(scaled(n, d.get(i).stars(), w.get(i).stars()), near.get(i).tokens()));
        }
        return tidy(out);
    }

    /** The most stars a week can give: 3 × the courses that are on × the editions that start in it. */
    public static int weekMax(int coursesOn, int editionsInWeek) {
        return Stars.MAX * Math.max(0, coursesOn) * Math.max(0, editionsInWeek);
    }

    /**
     * {@code goals} with none above {@value #GOAL_CEILING} of {@code weekMax} (rounded down): a goal
     * above it is lowered to it. Goals that land on the same number become one, paying the larger
     * of their tokens; a goal at or below 0 is dropped (a week that can give nothing has no goals).
     */
    public static List<Goal> clamp(List<Goal> goals, int weekMax) {
        int ceiling = (int) Math.floor(Math.max(0, weekMax) * GOAL_CEILING + 1e-9);
        List<Goal> out = new ArrayList<>();
        for (Goal g : goals == null ? List.<Goal>of() : goals) {
            out.add(new Goal(Math.min(g.stars(), ceiling), g.tokens()));
        }
        return tidy(out);
    }

    /** The stars of each goal, smallest first (what {@link #crossed} and {@link #nextGoal} take). */
    public static List<Integer> stars(List<Goal> goals) {
        List<Integer> out = new ArrayList<>();
        for (Goal g : goals == null ? List.<Goal>of() : goals) {
            out.add(g.stars());
        }
        return out;
    }

    /** What reaching {@code stars} pays among {@code goals}, or 0 when it isn't one of them. */
    public static int tokens(List<Goal> goals, int stars) {
        for (Goal g : goals == null ? List.<Goal>of() : goals) {
            if (g.stars() == stars) {
                return g.tokens();
            }
        }
        return 0;
    }

    /** Positive goals, smallest first, one per number (the larger tokens win), tokens never below 0. */
    public static List<Goal> tidy(List<Goal> goals) {
        TreeMap<Integer, Integer> by = new TreeMap<>();
        for (Goal g : goals == null ? List.<Goal>of() : goals) {
            if (g != null && g.stars() > 0) {
                by.merge(g.stars(), Math.max(0, g.tokens()), Math::max);
            }
        }
        List<Goal> out = new ArrayList<>();
        by.forEach((stars, tokens) -> out.add(new Goal(stars, tokens)));
        return List.copyOf(out);
    }

    /** The positive goals, smallest first, each once. */
    private static TreeSet<Integer> sorted(List<Integer> goals) {
        TreeSet<Integer> out = new TreeSet<>();
        if (goals != null) {
            for (Integer g : goals) {
                if (g != null && g > 0) {
                    out.add(g);
                }
            }
        }
        return out;
    }
}
