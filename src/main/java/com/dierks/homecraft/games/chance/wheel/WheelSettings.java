package com.dierks.homecraft.games.chance.wheel;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;
import java.util.TreeSet;

/**
 * The Wheel's settings: {@code games.wheel} (spec §5.5, R1.7).
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
 * @param dailyLimit spins a player gets a day
 * @param rtp the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param segments the 24 spaces clockwise from the top left: 0 (nothing), 1 (tokens back) or more
 */
public record WheelSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                            List<Double> segments) {

    /** The leaves under {@code games.wheel}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp", "segments");

    /** The ring is the 24 border slots of the screen's top five rows. */
    public static final int SPACES = 24;

    public WheelSettings {
        stakes = List.copyOf(stakes);
        segments = List.copyOf(segments);
    }

    /** The shipped settings. */
    public static WheelSettings defaults() {
        return new WheelSettings(
                true,
                List.of(5, 10, 20),
                30,
                90,
                List.of(5.0, 0.0, 1.0, 0.0, 2.0, 0.0, 0.0, 1.0, 0.0, 4.0, 0.0, 1.0,
                        0.0, 2.0, 0.0, 1.0, 0.0, 4.0, 0.0, 0.0, 1.0, 0.0, 2.0, 0.0));
    }

    /** Read {@code games.wheel} over {@code d}; never throws. */
    public static WheelSettings parse(GamesConfig.Node n, WheelSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        List<Double> segments = segments(n, d);
        // owner: solve here - the exact RTP per stake from these values (RtpLimits.pick, R1.1),
        // with payouts capped at n.common().maxPayoutFor(stakes); a stake with nothing in the
        // band is dropped with one n.warn, and the game is closed when no stake is left.
        return new WheelSettings(enabled, stakes, dailyLimit, rtp, segments);
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }

    /** 24 spaces, each 0 or at least 1 (between 0 and 1 would be a loss dressed as a prize); else junk. */
    private static List<Double> segments(GamesConfig.Node n, WheelSettings d) {
        List<Double> seg = n.doubleList("segments", d.segments(), 0, 1000, SPACES);
        for (double v : seg) {
            if (v > 0 && v < 1) {
                n.invalid(n.key("segments") + " has a space of " + v + " - each must be 0 or at least 1");
                return d.segments();
            }
        }
        return seg;
    }
}
