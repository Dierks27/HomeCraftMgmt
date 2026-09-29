package com.dierks.homecraft.games.gen.api;

/**
 * Stars, the kid-facing score of a daily course (GEN-SPEC §5.2): one for finishing, two for a good
 * run, three for a great one. Finishing is always worth a star, so the youngest player who just
 * gets round Easy Parkour every day fills their Star Chart too.
 *
 * <p>Only a counted run earns stars (a test, voided or stale run earns none): that is the caller's
 * to decide; these only turn a counted result into stars.
 */
public final class Stars {

    /** The most stars a course gives. */
    public static final int MAX = 3;

    private Stars() {
    }

    /**
     * A time trial's stars: 3 at or under {@code goldMs}, 2 at or under {@code silverMs}, 1 for
     * finishing. A star time of 0 or less isn't set, so it can't be reached.
     */
    public static int trial(long ms, long goldMs, long silverMs) {
        if (goldMs > 0 && ms <= goldMs) {
            return 3;
        }
        if (silverMs > 0 && ms <= silverMs) {
            return 2;
        }
        return 1;
    }

    /** A golf round's stars: 3 at or under par, 2 at or under {@link #golfSilver}, 1 for finishing. */
    public static int golf(int strokes, int par, int holes) {
        if (strokes <= par) {
            return 3;
        }
        return strokes <= golfSilver(par, holes) ? 2 : 1;
    }

    /** The 2-star line of a golf course: par + one stroke for every three holes, rounded up. */
    public static int golfSilver(int par, int holes) {
        return par + (Math.max(0, holes) + 2) / 3;
    }

    /**
     * A star time: the reference time times the tier's factor ({@code games.daily.stars}), rounded
     * UP to a whole second, so the time on the screen is one a player can really make.
     */
    public static long threshold(long refMs, double factor) {
        if (refMs <= 0 || !(factor > 0) || Double.isInfinite(factor)) {
            return 0;
        }
        double ms = refMs * factor;
        return (long) Math.ceil(ms / 1000.0 - 1e-9) * 1000L;
    }

    /** What a time trial needs for its next star: the silver time at 1, the gold time at 2; -1 at 3. */
    public static long nextTrialMs(int stars, long goldMs, long silverMs) {
        return switch (stars) {
            case 3 -> -1;
            case 2 -> goldMs;
            default -> silverMs;
        };
    }

    /** What a golf round needs for its next star, in strokes; -1 at 3. */
    public static int nextGolfStrokes(int stars, int par, int holes) {
        return switch (stars) {
            case 3 -> -1;
            case 2 -> par;
            default -> golfSilver(par, holes);
        };
    }

    /** Stars as shown: filled then empty, three in all ("★★☆"). Both glyphs are below U+FFFF. */
    public static String text(int stars) {
        int n = Math.max(0, Math.min(MAX, stars));
        return "★".repeat(n) + "☆".repeat(MAX - n);
    }
}
