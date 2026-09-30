package com.dierks.homecraft.games.chance.hilo;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Higher or Lower's settings: {@code games.higher_lower} (spec §5.6, R1.6) — and, because the odds
 * are solved while they are read, the engine object itself: the screen, {@code /hcm arcade odds},
 * the feed and every run dealt all read the SAME {@link #odds()} (spec §3.1.1).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * <p><b>The solve (R1.1).</b> For each stake every {@code r} from 0.800 to 1.000 is solved exactly
 * for its best-play return ({@link HigherLowerMath}), and the stake takes the largest return at or
 * below {@code rtp} — or, when even the lowest reachable one is under 85, the smallest one from 85
 * up (one INFO line). A stake with nothing inside 85-95 is left out with one WARN; when that leaves
 * no stake at all, one WARN says so and the game stays closed ({@link HigherLower#configEnabled()}).
 *
 * @param enabled       the game's own switch (it also needs {@code games.enabled})
 * @param stakes        the choices of tokens to put in, smallest first, as configured
 * @param dailyLimit    rounds a player gets a day
 * @param rtp           the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param maxMultiplier the pot cashes out by itself at this many times the tokens put in
 * @param maxGuesses    the pot cashes out by itself after this many right guesses
 * @param odds          the solved stakes, smallest first: the only ones a run can start at
 */
public record HigherLowerSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                  int maxMultiplier, int maxGuesses, List<Odds> odds) {

    /** The game id, for the solve's log lines. */
    static final String ID = "higher_lower";

    /** The leaves under {@code games.higher_lower}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp",
            "max_multiplier", "max_guesses");

    public HigherLowerSettings {
        stakes = List.copyOf(stakes);
        odds = List.copyOf(odds);
    }

    /**
     * One playable stake.
     *
     * @param terms what a run at this stake pays (written into every round started at it)
     * @param rtp   its best-play return, computed exactly (a fraction: 0.8965)
     */
    public record Odds(HigherLowerRun.Terms terms, double rtp) {

        public int stake() {
            return terms.in();
        }
    }

    /** The shipped settings. */
    public static HigherLowerSettings defaults() {
        List<Integer> stakes = List.of(10, 20, 50);
        double rtp = 90;
        int maxMultiplier = 20;
        int maxGuesses = 10;
        int maxPayout = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new HigherLowerSettings(true, stakes, 30, rtp, maxMultiplier, maxGuesses,
                solve(stakes, rtp, maxMultiplier, maxGuesses, maxPayout, null, null, null));
    }

    /** Read {@code games.higher_lower} over {@code d}; never throws. */
    public static HigherLowerSettings parse(GamesConfig.Node n, HigherLowerSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        int maxMultiplier = n.whole("max_multiplier", d.maxMultiplier(), 2, 100);
        int maxGuesses = n.whole("max_guesses", d.maxGuesses(), 1, 50);
        GamesConfig.Common common = n.common() != null ? n.common() : GamesConfig.Common.defaults();
        List<Odds> odds = solve(stakes, rtp, maxMultiplier, maxGuesses, common.maxPayoutFor(stakes),
                n.key("stakes"), n::warn, n::info);
        return new HigherLowerSettings(enabled, stakes, dailyLimit, rtp, maxMultiplier, maxGuesses, odds);
    }

    /**
     * The R1.1 solve for every stake.
     *
     * @param maxPayout {@code games.max_payout}, floored at the largest stake
     * @param key       the stakes key, for the WARN lines
     * @param warn      a stake left out, or none left ({@code null}: silent, for the shipped defaults)
     * @param info      a stake that could only land above its target ({@code null}: silent)
     */
    static List<Odds> solve(List<Integer> stakes, double rtp, int maxMultiplier, int maxGuesses, int maxPayout,
                            String key, Consumer<String> warn, Consumer<String> info) {
        Ratio target = Ratio.percent(rtp);
        List<Odds> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int stake : stakes) {
            int cap = (int) Math.min((long) maxMultiplier * stake, maxPayout);
            Ratio[] values = HigherLowerMath.achievable(stake, cap, maxGuesses);
            RtpLimits.Pick pick = RtpLimits.pick(target, values);
            if (pick == null) {
                double[] d = new double[values.length];
                for (int i = 0; i < values.length; i++) {
                    d[i] = values[i] == null ? Double.NaN : values[i].value();
                }
                dropped.add(stake + " (nearest " + RtpLimits.nearest(d) + ")");
                continue;
            }
            if (pick.aboveTarget() && info != null) {
                info.accept(ID + " stake " + stake + ": " + RtpLimits.tenthPercent(pick.rtp()) + "% (target "
                        + fmt(rtp) + " not reachable in whole tokens)");
            }
            HigherLowerRun.Terms terms = new HigherLowerRun.Terms(stake, HigherLowerMath.rAt(pick.index()), cap,
                    maxGuesses);
            out.add(new Odds(terms, pick.rtp()));
        }
        if (!dropped.isEmpty() && warn != null) {
            if (out.isEmpty()) {
                warn.accept(key + " " + stakes + ": no stake can give back 85-95 of every 100 tokens in whole"
                        + " tokens (" + String.join(", ", dropped) + ") - Higher or Lower is closed until it is fixed");
            } else {
                for (String d : dropped) {
                    warn.accept(key + " " + d + " can't give back 85-95 of every 100 tokens in whole tokens"
                            + " - that stake is left out");
                }
            }
        }
        return out;
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    /** The stakes a run can start at (the solved ones), smallest first. */
    public List<Integer> playable() {
        return odds.stream().map(Odds::stake).toList();
    }

    /** The odds for this stake, or {@code null} if it isn't playable. */
    public Odds odds(int stake) {
        for (Odds o : odds) {
            if (o.stake() == stake) {
                return o;
            }
        }
        return null;
    }

    /** The lowest per-stake return (the one number that stands for the game); 0 with no stake. */
    public double lowestRtp() {
        return odds.stream().mapToDouble(Odds::rtp).min().orElse(0);
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
