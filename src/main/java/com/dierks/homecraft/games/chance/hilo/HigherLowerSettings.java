package com.dierks.homecraft.games.chance.hilo;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;
import java.util.TreeSet;

/**
 * Higher or Lower's settings: {@code games.higher_lower} (spec §5.6, R1.6).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 * The odds are solved in {@link #parse} too, from the same values the game plays with, so a
 * configuration that can't be solved inside the RTP band closes the game the moment it loads.
 *
 * @param enabled the game's own switch (it also needs {@code games.enabled})
 * @param stakes the choices of tokens to put in, smallest first
 * @param dailyLimit rounds a player gets a day
 * @param rtp the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param maxMultiplier the pot cashes out by itself at this many times the tokens put in
 * @param maxGuesses the pot cashes out by itself after this many guesses
 */
public record HigherLowerSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                  int maxMultiplier, int maxGuesses) {

    /** The leaves under {@code games.higher_lower}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp",
            "max_multiplier", "max_guesses");

    public HigherLowerSettings {
        stakes = List.copyOf(stakes);
    }

    /** The shipped settings. */
    public static HigherLowerSettings defaults() {
        return new HigherLowerSettings(
                true,
                List.of(10, 20, 50),
                30,
                90,
                20,
                10);
    }

    /** Read {@code games.higher_lower} over {@code d}; never throws. */
    public static HigherLowerSettings parse(GamesConfig.Node n, HigherLowerSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        int maxMultiplier = n.whole("max_multiplier", d.maxMultiplier(), 2, 100);
        int maxGuesses = n.whole("max_guesses", d.maxGuesses(), 1, 50);
        // owner: solve here - the exact RTP per stake from these values (RtpLimits.pick, R1.1),
        // with payouts capped at n.common().maxPayoutFor(stakes); a stake with nothing in the
        // band is dropped with one n.warn, and the game is closed when no stake is left.
        return new HigherLowerSettings(enabled, stakes, dailyLimit, rtp, maxMultiplier, maxGuesses);
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }
}
