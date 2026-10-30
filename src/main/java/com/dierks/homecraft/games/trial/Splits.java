package com.dierks.homecraft.games.trial;

/**
 * Checkpoint splits (0.38.4, the ghost's first step): on a solo run, the action bar's checkpoint line adds how
 * far ahead of or behind the player's own best run on this course they are ("+0.4 s" red, "-0.6 s" green), and
 * the finish line says where the run gained or lost. The best run's checkpoint times are kept in
 * {@code game_splits} (schema v39) on a personal best, keyed on the course id AND its layout hash, so a Fresh
 * course's next edition (or an edited course) starts fresh and an old run is never compared. Never shown in race
 * mode (a party race's or Race Night's run) or a warm-up, and never to anyone else. Pure (no Bukkit), tested.
 */
final class Splits {

    private Splits() {
    }

    /**
     * The run's checkpoint times so far, ms from the start per target (checkpoints first, the finish last),
     * 0 for one not reached yet; an empty array before the clock runs.
     */
    static long[] soFar(TrialRun run) {
        if (run.progress == null) {
            return new long[0];
        }
        long[] t = run.progress.times();
        for (int i = 0; i < t.length; i++) {
            t[i] = t[i] == 0 ? 0 : run.elapsedMs(t[i]);
        }
        return t;
    }

    /** {@link #soFar} at the finish, with the finish's own time {@code finishMs} as the last entry. */
    static long[] fromRun(TrialRun run, long finishMs) {
        long[] t = soFar(run);
        if (t.length > 0) {
            t[t.length - 1] = finishMs;
        }
        return t;
    }

    /**
     * The action bar's add-on at checkpoint {@code index}: " &amp;c+0.4 s" behind the best, " &amp;a-0.6 s"
     * ahead, " &amp;7=0.0 s" level (tenths, rounded down, as {@link TrialText#time}); {@code ""} with no best, in a
     * race or a warm-up, for an index either run hasn't, or when either time is 0.
     */
    static String deltaText(long[] best, long[] now, int index, boolean race, boolean warmup) {
        if (best == null || now == null || race || warmup || index < 0 || index >= best.length || index >= now.length
                || best[index] == 0 || now[index] == 0) {
            return "";
        }
        long delta = now[index] - best[index];
        long tenths = Math.abs(delta) / 100;
        String n = tenths / 10 + "." + tenths % 10 + " s";
        if (tenths == 0) {
            return " &7=" + n;
        }
        return delta > 0 ? " &c+" + n : " &a-" + n;
    }

    /**
     * The finish's sentence: "&amp;7Faster than your best from checkpoint 3." / "&amp;7Behind your best from
     * checkpoint 2." (the first checkpoint from which the gap keeps the finish's sign), "on the last stretch" when
     * only the finish does, "&amp;7Level with your best all the way." (or "at the line"); {@code null} without a
     * best, or when the two runs don't have the same targets.
     */
    static String finishLine(long[] best, long[] now) {
        if (best == null || now == null || best.length == 0 || best.length != now.length) {
            return null;
        }
        int last = best.length - 1;
        if (best[last] == 0 || now[last] == 0) {
            return null;
        }
        int sign = Long.signum(now[last] - best[last]);
        int from = last;
        while (from > 0 && best[from - 1] != 0 && now[from - 1] != 0
                && Long.signum(now[from - 1] - best[from - 1]) == sign) {
            from--;
        }
        if (sign == 0) {
            return from == 0 ? "&7Level with your best all the way." : "&7Level with your best at the line.";
        }
        String where = from == last ? " on the last stretch." : " from checkpoint " + (from + 1) + ".";
        return (sign < 0 ? "&7Faster than your best" : "&7Behind your best") + where;
    }
}
