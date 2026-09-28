package com.dierks.homecraft.games.golf;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's round of a course, as a score (spec §12): strokes per hole, the pick-up rule and
 * the scorecard. Pure, so the counting is tested without a server; the ball and the player are
 * the game's.
 *
 * <p><b>Strokes.</b> Every putt is a stroke, and so is every penalty: the ball in water or lava,
 * out of bounds, or put back with "Reset ball". <b>Picking up</b> keeps a lost hole from dragging
 * on: once the ball is at rest and the strokes have reached par + {@code max_over_par} without it
 * in the cup, the hole is over and scores exactly that ("Picked up"). A hole never scores more than
 * that, however the last penalty landed.
 */
public final class GolfRun {

    /**
     * One finished hole.
     *
     * @param par      its par
     * @param strokes  what it scored (never more than par + max over par)
     * @param pickedUp true when it ended by the pick-up rule, not in the cup
     */
    public record HoleScore(int par, int strokes, boolean pickedUp) {

        /** In the cup with the first stroke. */
        public boolean holeInOne() {
            return !pickedUp && strokes == 1;
        }

        /** Strokes against par: negative is under. */
        public int vsPar() {
            return strokes - par;
        }
    }

    private final List<Integer> pars;
    private final int maxOverPar;
    private final List<HoleScore> done = new ArrayList<>();
    private int strokes;

    /**
     * @param pars       each hole's par, in order
     * @param maxOverPar strokes over par before a hole is picked up (at least 1)
     */
    public GolfRun(List<Integer> pars, int maxOverPar) {
        if (pars == null || pars.isEmpty()) {
            throw new IllegalArgumentException("a round needs at least one hole");
        }
        this.pars = List.copyOf(pars);
        this.maxOverPar = Math.max(1, maxOverPar);
    }

    /** The hole being played, 0-based ({@link #holes()} once the round is over). */
    public int hole() {
        return done.size();
    }

    /** How many holes the round has. */
    public int holes() {
        return pars.size();
    }

    /** The current hole's par (the last hole's once over). */
    public int par() {
        return pars.get(Math.min(hole(), pars.size() - 1));
    }

    /** Strokes on the current hole so far. */
    public int strokes() {
        return strokes;
    }

    /** The most a hole can score: par + max over par. */
    public int limit() {
        return par() + maxOverPar;
    }

    /** Every hole played so far. */
    public List<HoleScore> scores() {
        return List.copyOf(done);
    }

    /** Whether every hole is done. */
    public boolean finished() {
        return done.size() >= pars.size();
    }

    /** A putt. */
    public void stroke() {
        if (!finished()) {
            strokes++;
        }
    }

    /** A penalty stroke: water, lava, out of bounds, or "Reset ball". */
    public void penalty() {
        stroke();
    }

    /** Whether the hole, with the ball at rest and not in the cup, must be picked up now. */
    public boolean mustPickUp() {
        return !finished() && strokes >= limit();
    }

    /** The ball is in the cup: the hole is done with its strokes (capped at the limit). */
    public HoleScore inCup() {
        return end(false);
    }

    /** The hole is picked up: it scores the limit. */
    public HoleScore pickUp() {
        return end(true);
    }

    private HoleScore end(boolean pickedUp) {
        if (finished()) {
            throw new IllegalStateException("the round is over");
        }
        int limit = limit();
        HoleScore s = new HoleScore(par(), pickedUp ? limit : Math.min(Math.max(1, strokes), limit), pickedUp);
        done.add(s);
        strokes = 0;
        return s;
    }

    /** Strokes over the holes played. */
    public int total() {
        int t = 0;
        for (HoleScore s : done) {
            t += s.strokes();
        }
        return t;
    }

    /** Par over the holes played. */
    public int parPlayed() {
        int t = 0;
        for (HoleScore s : done) {
            t += s.par();
        }
        return t;
    }

    /** Par for the whole course. */
    public int coursePar() {
        int t = 0;
        for (int p : pars) {
            t += p;
        }
        return t;
    }

    /** Strokes against par over the holes played: negative is under. */
    public int vsPar() {
        return total() - parPlayed();
    }

    /** Whether the finished round is at par or better (the par reward). */
    public boolean parOrBetter() {
        return finished() && total() <= coursePar();
    }

    /** The holes (1-based) that were holes-in-one. */
    public List<Integer> holesInOne() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < done.size(); i++) {
            if (done.get(i).holeInOne()) {
                out.add(i + 1);
            }
        }
        return out;
    }

    // ---- words (pure, tested) -------------------------------------------------------------------

    /** Strokes against par in words: "par", "1 under par", "3 over par". */
    public static String vsParText(int diff) {
        if (diff == 0) {
            return "par";
        }
        return Math.abs(diff) + (diff < 0 ? " under par" : " over par");
    }

    /** Strokes against par in short: "E" (even), "-1", "+3". */
    public static String vsParShort(int diff) {
        return diff == 0 ? "E" : (diff > 0 ? "+" : "") + diff;
    }

    /** "1 stroke", "3 strokes". */
    public static String strokesText(int strokes) {
        return strokes + (strokes == 1 ? " stroke" : " strokes");
    }

    /** What a finished hole is called: "Hole in one!", "Birdie!", "Par", "Picked up"... */
    public static String holeWord(HoleScore s) {
        if (s.pickedUp()) {
            return "Picked up";
        }
        if (s.holeInOne()) {
            return "Hole in one!";
        }
        return switch (s.vsPar()) {
            case -3 -> "Albatross!";
            case -2 -> "Eagle!";
            case -1 -> "Birdie!";
            case 0 -> "Par";
            case 1 -> "Bogey";
            case 2 -> "Double bogey";
            default -> s.vsPar() < 0 ? vsParText(s.vsPar()) + "!" : vsParText(s.vsPar());
        };
    }
}
