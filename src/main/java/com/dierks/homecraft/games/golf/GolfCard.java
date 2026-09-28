package com.dierks.homecraft.games.golf;

import java.util.List;

/**
 * A scorecard as a screen or a chat line shows it: a snapshot of one round (spec §12), so the
 * screen never reaches into a round that may have moved on or ended.
 *
 * @param courseId   the course
 * @param courseName its name
 * @param pars       every hole's par
 * @param scores     the holes finished so far, in order
 * @param strokes    strokes on the hole being played (0 between holes and at the end)
 * @param between    a hole just ended and the next one is coming
 */
public record GolfCard(String courseId, String courseName, List<Integer> pars, List<GolfRun.HoleScore> scores,
                       int strokes, boolean between) {

    public GolfCard {
        pars = List.copyOf(pars);
        scores = List.copyOf(scores);
    }

    /** The card of a round as it stands. */
    static GolfCard of(GolfCourse course, GolfRun run, boolean between) {
        return new GolfCard(course.id(), course.name(), course.pars(), run.scores(), between ? 0 : run.strokes(), between);
    }

    /** Every hole is done. */
    public boolean finished() {
        return scores.size() >= pars.size();
    }

    /** The hole being played (0-based), or -1 between holes and at the end. */
    public int playing() {
        return between || finished() ? -1 : scores.size();
    }

    /** Strokes over the holes finished. */
    public int total() {
        int t = 0;
        for (GolfRun.HoleScore s : scores) {
            t += s.strokes();
        }
        return t;
    }

    /** Strokes against par over the holes finished. */
    public int vsPar() {
        int par = 0;
        for (GolfRun.HoleScore s : scores) {
            par += s.par();
        }
        return total() - par;
    }

    /** Par for the course. */
    public int par() {
        int t = 0;
        for (int p : pars) {
            t += p;
        }
        return t;
    }

    /**
     * The chat line: each finished hole's strokes, coloured against par (green under, white par,
     * yellow over, red picked up), then the total: "&amp;7Card: &amp;a2 &amp;f4 &amp;e5 &amp;8| &amp;fTotal 11 &amp;7(+1)".
     */
    public String line() {
        StringBuilder b = new StringBuilder("&7Card:");
        for (GolfRun.HoleScore s : scores) {
            b.append(' ').append(colour(s)).append(s.strokes());
        }
        return b.append(" &8| &fTotal ").append(total()).append(" &7(").append(GolfRun.vsParShort(vsPar())).append(')')
                .toString();
    }

    /** A finished hole's colour code. */
    public static String colour(GolfRun.HoleScore s) {
        if (s.pickedUp()) {
            return "&c";
        }
        return s.vsPar() < 0 ? "&a" : s.vsPar() == 0 ? "&f" : "&e";
    }
}
