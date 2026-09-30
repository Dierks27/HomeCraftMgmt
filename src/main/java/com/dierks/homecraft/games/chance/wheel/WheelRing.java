package com.dierks.homecraft.games.chance.wheel;

/**
 * Where the Wheel's spaces sit on the screen and how the highlight travels to the one a spin
 * landed on (spec §5.5, R1.7).
 *
 * <p>The ring is the border of the screen's top five rows, clockwise from the top-left corner, so
 * the whole wheel is in view at once and the middle is free for the rules and the result.
 *
 * <p>The spin is honest by construction. The landing space is decided (and paid) before the
 * highlight moves; the highlight then makes a fixed number of whole laps plus the distance to that
 * space, over a fixed number of frames on a fixed tick schedule. {@link #path} takes only where it
 * starts and where it must stop: it never sees a prize, so it can't slow down beside a big one or
 * creep past it. Every step moves at least one space and no step is longer than the one before, so
 * it never pauses on a neighbour and then lurches on. Bedrock gets fewer, slower frames (rapid
 * inventory updates stutter through Geyser).
 */
public final class WheelRing {

    /** The 24 ring slots, clockwise from the top left: space {@code i} is at {@code SLOTS[i]}. */
    public static final int[] SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 17, 26, 35, 44, 43, 42, 41, 40, 39, 38, 37, 36,
            27, 18, 9};

    /**
     * How a spin is shown.
     *
     * @param frames how many times the highlight moves
     * @param laps   whole turns before the last one
     * @param ticks  how long the whole spin takes (20 ticks = 1 second)
     */
    public record Plan(int frames, int laps, int ticks) {
    }

    /** Java: 20 frames, two laps, 1.9 seconds. */
    public static final Plan JAVA = new Plan(20, 2, 38);
    /** Bedrock: 8 frames, one lap, 1.6 seconds. */
    public static final Plan BEDROCK = new Plan(8, 1, 32);

    private WheelRing() {
    }

    /** How far the highlight travels: whole laps, then clockwise from {@code start} to {@code target}. */
    public static int distance(int start, int target, Plan plan) {
        int n = SLOTS.length;
        int offset = Math.floorMod(target - start, n);
        return plan.laps() * n + offset;
    }

    /**
     * The space under the highlight after each frame: {@code plan.frames()} entries, the last one
     * {@code target}. Depends on nothing but {@code start}, {@code target} and the plan.
     */
    public static int[] path(int start, int target, Plan plan) {
        int[] steps = steps(distance(start, target, plan), plan.frames());
        int[] out = new int[steps.length];
        int at = start;
        for (int i = 0; i < steps.length; i++) {
            at = Math.floorMod(at + steps[i], SLOTS.length);
            out[i] = at;
        }
        return out;
    }

    /**
     * Split {@code distance} into {@code frames} steps: each at least one space, none longer than
     * the one before (big strides first, one space at a time at the end), adding up exactly.
     */
    static int[] steps(int distance, int frames) {
        int n = Math.max(1, frames);
        int[] out = new int[n];
        int extra = Math.max(0, distance - n);
        long weights = (long) n * (n + 1) / 2;
        int given = 0;
        for (int i = 0; i < n; i++) {
            out[i] = 1 + (int) ((long) extra * (n - i) / weights);
            given += out[i] - 1;
        }
        for (int i = 0; given < extra; i++, given++) {
            out[i % n]++;
        }
        return out;
    }

    /**
     * The tick (after the spin starts) at which each frame is shown: a fixed schedule that slows
     * down, the same for every spin with this plan whatever it lands on, ending at
     * {@code plan.ticks()}.
     */
    public static long[] schedule(Plan plan) {
        int n = Math.max(1, plan.frames());
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double x = (double) i / n;
            w[i] = 1 + 3 * x * x;
            sum += w[i];
        }
        long[] gap = new long[n];
        long used = 0;
        for (int i = 0; i < n; i++) {
            gap[i] = Math.max(1, (long) Math.floor(plan.ticks() * w[i] / sum));
            used += gap[i];
        }
        // Hand the rounding back to the last frames, so the gaps only ever grow.
        for (int i = n - 1; used < plan.ticks(); i = i == 0 ? n - 1 : i - 1) {
            gap[i]++;
            used++;
        }
        long[] at = new long[n];
        long t = 0;
        for (int i = 0; i < n; i++) {
            t += gap[i];
            at[i] = t;
        }
        return at;
    }
}
