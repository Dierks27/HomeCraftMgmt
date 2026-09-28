package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;
import java.util.TreeSet;

/**
 * Twenty-One's settings: {@code games.twenty_one} (spec §5.4, R1.5).
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
 * @param dailyLimit hands a player gets a day
 * @param rtp the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param naturalBonus how much more a two-card 21 pays than a plain win (1.5 = half as much again)
 */
public record TwentyOneSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                double naturalBonus) {

    /** The leaves under {@code games.twenty_one}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp",
            "natural_bonus");

    public TwentyOneSettings {
        stakes = List.copyOf(stakes);
    }

    /** The shipped settings. */
    public static TwentyOneSettings defaults() {
        return new TwentyOneSettings(
                true,
                List.of(5, 10, 20),
                30,
                90,
                1.5);
    }

    /** Read {@code games.twenty_one} over {@code d}; never throws. */
    public static TwentyOneSettings parse(GamesConfig.Node n, TwentyOneSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        double naturalBonus = n.num("natural_bonus", d.naturalBonus(), 1.0, 3.0);
        // owner: solve here - the exact RTP per stake from these values (RtpLimits.pick, R1.1),
        // with payouts capped at n.common().maxPayoutFor(stakes); a stake with nothing in the
        // band is dropped with one n.warn, and the game is closed when no stake is left.
        return new TwentyOneSettings(enabled, stakes, dailyLimit, rtp, naturalBonus);
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }
}
