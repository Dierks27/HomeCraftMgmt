package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.math.BigInteger;

/**
 * The exact numbers Deep Mine and Mob Slots publish, each worked out from a solved {@link SlotsEngine}'s own whole
 * weights and whole payouts. Nothing here is simulated and nothing is a double until it is shown.
 *
 * <ul>
 *   <li>one spin's return is the engine's own fraction ({@link #line});</li>
 *   <li>the chance of three Wilds is the Wild's weight over the reel's total, cubed ({@link #threeWilds});</li>
 *   <li>the chance a spin pays anything is every paying triple's weight over the total cubed ({@link #pays});</li>
 *   <li>Deep Mine, three rows drawn apart: the chance at least one pays is {@code 1 - (1 - p)³}
 *       ({@link #atLeastOnce});</li>
 *   <li>Mob Slots, {@code N} free spins when the Creepers land, none won again inside them: a round gives back
 *       {@code L × (1 + p × N)} ({@link #withFreeSpins}).</li>
 * </ul>
 */
public final class SlotOdds {

    private static final Symbol[] SYMBOLS = Symbol.values();

    private SlotOdds() {
    }

    /** What one spin gives back per token in, exactly; {@code null} for a stake the engine doesn't take. */
    public static Frac line(SlotsEngine engine, int stake) {
        StakeOdds o = engine.odds(stake);
        return o == null ? null : new Frac(o.rtpNum(), o.rtpDen());
    }

    /** The reel's total weight at this stake, cubed: how many ways three reels can land. */
    private static BigInteger ways(StakeOdds o) {
        return BigInteger.valueOf(o.total()).pow(3);
    }

    /** The exact chance all three reels land the Wild's symbol (Mob Slots: the Creeper). */
    public static Frac threeWilds(SlotsEngine engine, int stake) {
        StakeOdds o = engine.odds(stake);
        if (o == null) {
            return null;
        }
        return new Frac(BigInteger.valueOf(engine.weight(stake, Symbol.WILD)).pow(3), ways(o));
    }

    /** The exact chance one spin pays anything at all, from all 343 ways the three symbols can fall. */
    public static Frac pays(SlotsEngine engine, int stake) {
        StakeOdds o = engine.odds(stake);
        if (o == null) {
            return null;
        }
        BigInteger hits = BigInteger.ZERO;
        for (Symbol a : SYMBOLS) {
            for (Symbol b : SYMBOLS) {
                for (Symbol c : SYMBOLS) {
                    if (engine.payout(stake, a, b, c) > 0) {
                        hits = hits.add(BigInteger.valueOf(engine.weight(stake, a))
                                .multiply(BigInteger.valueOf(engine.weight(stake, b)))
                                .multiply(BigInteger.valueOf(engine.weight(stake, c))));
                    }
                }
            }
        }
        return new Frac(hits, ways(o));
    }

    /** The chance something with chance {@code p} happens at least once in {@code times} separate goes. */
    public static Frac atLeastOnce(Frac p, int times) {
        BigInteger all = p.den().pow(times);
        BigInteger never = p.den().subtract(p.num()).pow(times);
        return new Frac(all.subtract(never), all);
    }

    /**
     * What a whole round gives back per token in when three Wilds on the first spin give {@code freeSpins} more
     * spins of the same engine at the same stake, and a free spin never gives more: {@code L × (1 + p × N)}.
     */
    public static Frac withFreeSpins(SlotsEngine engine, int stake, int freeSpins) {
        Frac line = line(engine, stake);
        if (line == null) {
            return null;
        }
        Frac p = threeWilds(engine, stake);
        Frac more = new Frac(p.den().add(p.num().multiply(BigInteger.valueOf(Math.max(0, freeSpins)))), p.den());
        return line.times(more);
    }
}
