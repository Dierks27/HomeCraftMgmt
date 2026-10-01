package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.trial.TrialKind;

/**
 * The only boat physics the Mountain Run's proof uses (Course Variety §2.11, §4.1): how far a boat
 * can possibly fly off a drop, from two facts that hold on any client, never from a simulation.
 *
 * <ul>
 *   <li><b>F3, the speed cap.</b> No boat goes faster than {@value #MAX_SPEED} blocks a tick across
 *       the ground: {@link TrialKind#BOAT}'s own {@code maxSpeed} (75 blocks a second), which
 *       {@code FairPlay.tooFast} voids anyway, and blue ice tops out below it.</li>
 *   <li><b>F4, the fall.</b> A boat in the air falls at least as fast as a drag-free
 *       {@value #GRAVITY} blocks a tick², so after n ticks it has dropped at least
 *       {@code 0.02 n (n + 1)}: {@link #fallTicks t(1)} = 7 ticks (1.12) and t(2) = 10 (2.2).</li>
 * </ul>
 *
 * <p>So off a drop of d blocks a boat is in the air at most t(d) ticks, and covers at most
 * {@link #flight L(d)} = 3.75 × ⌈1.3 × t(d)⌉ blocks, rounded up to whole blocks: L(1) = 38 and
 * L(2) = 49. The 1.3 is a 30% margin for Bedrock's own client physics, which no Java model can
 * prove. The {@link #zone flight zone} adds 2 for the hull and a margin: Z(1) = 40, Z(2) = 51. Every
 * wall round a zone stands 2 above the lip (W2), so a flying boat can't leave the track, and
 * checkpoints keep out of zones, so a boat is on the ground when it crosses one.
 *
 * <p>Integer arithmetic throughout, so the numbers are exact on every platform ({@code 1.3 × 10} is
 * not 13 in floating point). Changing any number here is an ALGO bump: planned layouts depend on
 * it. Pinned by {@code BoatEnvelopeTest}. Pure: no Bukkit.
 */
public final class BoatEnvelope {

    /** F3: the most blocks a boat covers across the ground in one tick (75 blocks a second). */
    public static final double MAX_SPEED = 3.75;
    /** F4: the least a boat in the air speeds up downward each tick, blocks a tick². */
    public static final double GRAVITY = 0.04;
    /** The margin on the air time for Bedrock's client physics, in tenths (1.3). */
    public static final int MARGIN_TENTHS = 13;
    /** The zone is this much wider than the flight: the hull and a margin. */
    public static final int ZONE_EXTRA = 2;
    /** The biggest drop a Mountain Run may have: Java breaks a boat that falls 3 or more (V8). */
    public static final int MAX_DROP = 2;

    /** Ticks a second. */
    private static final int TICKS = 20;

    private BoatEnvelope() {
    }

    /**
     * F4: the most ticks a boat can be in the air falling {@code blocks}: the least n with
     * {@code 0.02 n (n + 1) ≥ blocks}, worked in hundredths so it is exact. 0 for no drop.
     */
    public static int fallTicks(int blocks) {
        if (blocks <= 0) {
            return 0;
        }
        int n = 0;
        // 0.02 n (n + 1) ≥ d  ⇔  2 n (n + 1) ≥ 100 d
        while (2L * n * (n + 1) < 100L * blocks) {
            n++;
        }
        return n;
    }

    /**
     * L(d): the farthest a boat can fly across the ground off a drop of {@code blocks}, whole blocks:
     * {@value #MAX_SPEED} × ⌈1.3 × t(d)⌉, rounded up. 38 for 1, 49 for 2.
     */
    public static int flight(int blocks) {
        int ticks = (MARGIN_TENTHS * fallTicks(blocks) + 9) / 10; // ⌈1.3 t⌉
        return (15 * ticks + 3) / 4; // ⌈3.75 × ticks⌉
    }

    /** Z(d) = L(d) + {@value #ZONE_EXTRA}: how far round a lip its flight zone reaches. 40 for 1, 51 for 2. */
    public static int zone(int blocks) {
        return flight(blocks) + ZONE_EXTRA;
    }

    /** The speed cap in blocks a second: the same number {@link TrialKind#BOAT} judges runs by. */
    public static double maxBlocksPerSecond() {
        return MAX_SPEED * TICKS;
    }
}
