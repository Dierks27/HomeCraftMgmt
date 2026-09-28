package com.dierks.homecraft.games.chance.coinflip;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;
import java.util.TreeSet;

/**
 * Coin Flip's settings: {@code games.coin_flip} (spec §5.7, R1.8).
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
 * @param dailyLimit flips a player gets a day
 * @param pairDailyLimit flips the same two players get a day
 * @param rtp the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param maxDistance how close the two must stand, in blocks (0 = anywhere in the same world)
 * @param inviteSeconds how long an invite waits for an answer
 */
public record CoinFlipSettings(boolean enabled, List<Integer> stakes, int dailyLimit, int pairDailyLimit,
                               double rtp, int maxDistance, int inviteSeconds) {

    /** The leaves under {@code games.coin_flip}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "pair_daily_limit",
            "rtp", "max_distance", "invite_seconds");

    public CoinFlipSettings {
        stakes = List.copyOf(stakes);
    }

    /** The shipped settings. */
    public static CoinFlipSettings defaults() {
        return new CoinFlipSettings(
                false,
                List.of(5, 10, 25),
                5,
                2,
                90,
                32,
                60);
    }

    /** Read {@code games.coin_flip} over {@code d}; never throws. */
    public static CoinFlipSettings parse(GamesConfig.Node n, CoinFlipSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        int pairDailyLimit = n.whole("pair_daily_limit", d.pairDailyLimit(), 1, 100);
        double rtp = n.rtp("rtp", d.rtp());
        int maxDistance = n.whole("max_distance", d.maxDistance(), 0, 100_000);
        int inviteSeconds = n.whole("invite_seconds", d.inviteSeconds(), 10, 600);
        // owner: solve here - the exact RTP per stake from these values (RtpLimits.pick, R1.1),
        // with payouts capped at n.common().maxPayoutFor(stakes); a stake with nothing in the
        // band is dropped with one n.warn, and the game is closed when no stake is left.
        return new CoinFlipSettings(enabled, stakes, dailyLimit,
                pairDailyLimit, rtp, maxDistance, inviteSeconds);
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }
}
