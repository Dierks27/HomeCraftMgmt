package com.dierks.homecraft.games.chance.wheel;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.RtpLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The Wheel's settings: {@code games.wheel} (spec §5.5, R1.7) — and, because the odds are solved
 * while they are read, the Wheel's engine too.
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * <p>The odds are solved in {@link #parse} from the same values the game plays with: per stake,
 * the prize on every space ({@link WheelMath}, the R1.1 rule). A stake that can't give back 85-95
 * of every 100 tokens is left out with one WARN naming it and the nearest returns (and whether it
 * is the spaces or {@code max_payout} holding it back), and the Wheel closes only when no stake is
 * left (one WARN for the lot). The screen, the spin ({@link #decide}), {@code /hcm arcade odds}
 * and the website all read this one object, never a copy.
 *
 * @param enabled    the game's own switch (it also needs {@code games.enabled})
 * @param stakes     the configured choices of tokens to put in, smallest first
 * @param dailyLimit spins a player gets a day
 * @param rtp        the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param segments   the 24 spaces clockwise from the top left: 0 (nothing), 1 (tokens back) or more
 *                   (config may give 8 or 12, repeated around the ring)
 * @param odds       the solved stakes, smallest first: every stake a player can actually pick
 */
public record WheelSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                            List<Double> segments, List<WheelOdds> odds)
        implements ChanceRounds.InstantEngine<WheelSpin> {

    /** The leaves under {@code games.wheel}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp", "segments");

    /** The ring is the 24 border slots of the screen's top five rows. */
    public static final int SPACES = 24;

    /** The engine version stored with every round: bump it if {@link #decide} ever changes. */
    public static final int VERSION = 1;

    public WheelSettings {
        stakes = List.copyOf(stakes);
        segments = List.copyOf(segments);
        odds = List.copyOf(odds);
    }

    /** The shipped settings. */
    public static WheelSettings defaults() {
        List<Integer> stakes = List.of(5, 10, 20);
        double rtp = 90;
        List<Double> segments = List.of(5.0, 0.0, 1.0, 0.0, 2.0, 0.0, 0.0, 1.0, 0.0, 4.0, 0.0, 1.0,
                0.0, 2.0, 0.0, 1.0, 0.0, 4.0, 0.0, 0.0, 1.0, 0.0, 2.0, 0.0);
        int cap = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new WheelSettings(true, stakes, 30, rtp, segments,
                solve("games.wheel", stakes, rtp, segments, cap, w -> { }, i -> { }));
    }

    /** Read {@code games.wheel} over {@code d}; never throws. */
    public static WheelSettings parse(GamesConfig.Node n, WheelSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        List<Double> segments = segments(n, d);
        GamesConfig.Common common = n.common() == null ? GamesConfig.Common.defaults() : n.common();
        List<WheelOdds> odds = solve(n.path(), stakes, rtp, segments, common.maxPayoutFor(stakes), n::warn, n::info);
        return new WheelSettings(enabled, stakes, dailyLimit, rtp, segments, odds);
    }

    /**
     * Solve every stake (spec R1.1): one WARN per dropped stake (one in all when none is left), one
     * INFO per stake that could only land above its target. The shipped values say nothing.
     *
     * @param path the section's key ({@code games.wheel}), for the lines
     * @param cap  the payout cap ({@code max_payout}, never below the largest stake)
     */
    static List<WheelOdds> solve(String path, List<Integer> stakes, double rtp, List<Double> segments, int cap,
                                 Consumer<String> warn, Consumer<String> info) {
        RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
        List<WheelOdds> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (int stake : stakes) {
            WheelMath.Solve s = WheelMath.solve(stake, segments, cap, target);
            if (s.odds() != null) {
                out.add(s.odds());
                if (s.aboveTarget()) {
                    info.accept("wheel stake " + stake + ": " + RtpLimits.tenthPercent(s.odds().rtp())
                            + "% (target " + fmt(rtp) + " not reachable in whole tokens)");
                }
                continue;
            }
            String nearest = RtpLimits.nearest(s.reachable());
            all.add(stake + ": " + nearest);
            if (WheelMath.solve(stake, segments, Integer.MAX_VALUE, target).odds() != null) {
                dropped.add(path + ".stakes " + stake + " is off: max_payout " + cap
                        + " holds its prizes down to " + nearest + " back");
            } else {
                dropped.add(path + ".segments can't give back 85-95 of every 100 tokens at stake " + stake
                        + " (it reaches " + nearest + ") - that stake is off");
            }
        }
        if (out.isEmpty() && !stakes.isEmpty()) {
            warn.accept(path + " has no stake that can give back 85-95 of every 100 tokens ("
                    + String.join("; ", all) + ") - the Wheel is closed until it is fixed");
            return out;
        }
        dropped.forEach(warn);
        return out;
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    /** The solved odds for a stake, or {@code null} when that stake isn't offered. */
    public WheelOdds odds(int stake) {
        for (WheelOdds o : odds) {
            if (o.stake() == stake) {
                return o;
            }
        }
        return null;
    }

    /** The stakes a player can pick (the configured ones the solve kept), smallest first. */
    public List<Integer> open() {
        return odds.stream().map(WheelOdds::stake).toList();
    }

    /** The stake that gives back least: its number stands for the whole game. {@code null} if none. */
    public WheelOdds lowest() {
        WheelOdds low = null;
        for (WheelOdds o : odds) {
            if (low == null || o.ratio().compareTo(low.ratio()) < 0) {
                low = o;
            }
        }
        return low;
    }

    // ---- the engine -------------------------------------------------------------------------

    /**
     * Where a spin lands, from its seed alone: one draw of {@code nextInt(24)}, so every space is
     * exactly as likely as the tiles say.
     *
     * @throws IllegalArgumentException for a stake this wheel doesn't offer (screens only offer
     *                                  {@link #open()} ones)
     */
    @Override
    public WheelSpin decide(long seed, int stake) {
        WheelOdds o = odds(stake);
        if (o == null) {
            throw new IllegalArgumentException("the Wheel takes no stake of " + stake);
        }
        int space = new SplittableRandom(seed).nextInt(SPACES);
        int prize = o.prize(space);
        String data = "v=" + VERSION + ";space=" + space + ";base=" + fmt(segments.get(space)) + ";prize=" + prize;
        return new WheelSpin(stake, space, prize, o.result(prize), data);
    }

    @Override
    public int payout(WheelSpin outcome) {
        return outcome.prize();
    }

    @Override
    public String data(WheelSpin outcome) {
        return outcome.data();
    }

    // ---- reading ----------------------------------------------------------------------------

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }

    /**
     * The 24 spaces, each 0 or at least 1 (between 0 and 1 would be a loss dressed as a prize). A
     * list of 8 or 12 is a pattern repeated around the ring (3 or 2 times); any other length is junk.
     */
    private static List<Double> segments(GamesConfig.Node n, WheelSettings d) {
        if (!n.has("segments")) {
            return d.segments();
        }
        List<Double> seg = n.doubleList("segments", d.segments(), 0, 1000);
        if (seg == d.segments()) {
            return seg; // unreadable: already junk, with its WARN
        }
        if (SPACES % seg.size() != 0 || seg.size() < 8) {
            n.invalid(n.key("segments") + " has " + seg.size() + " spaces - give " + SPACES
                    + " (or 8 or 12, repeated around the ring)");
            return d.segments();
        }
        for (double v : seg) {
            if (v > 0 && v < 1) {
                n.invalid(n.key("segments") + " has a space of " + fmt(v) + " - each must be 0 or at least 1");
                return d.segments();
            }
        }
        List<Double> ring = new ArrayList<>(SPACES);
        while (ring.size() < SPACES) {
            ring.addAll(seg);
        }
        return List.copyOf(ring);
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
