package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The Star Chart's arithmetic (GEN-SPEC §5.2, §5.3), pure so it is tested on its own and the
 * database only has to store what it says.
 *
 * <p>The week's total is the sum of each course-day's BEST stars, kept by delta: a better run adds
 * only the difference to the week, an equal or worse one adds nothing. So the chart only ever
 * rises, replaying a course never inflates it, and a crash between the two writes can't count a
 * run twice (the DAO does both in one transaction).
 *
 * <p>A weekly goal ({@code star_goals}, shipped 10 and 25) is crossed once, by the run that takes
 * the week from under it to at or over it; its reward ref is {@code ms:gweek:<week>:<goal>}, so it
 * stays the same if the owner reorders the list, and is paid once a week whichever run pays it
 * ({@link #reached}: a goal the day's caps held back is paid by a later run).
 */
public final class DailyStars {

    private DailyStars() {
    }

    /** What a run of {@code stars} adds to the week, when the day's best so far is {@code best} (null: none). */
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
