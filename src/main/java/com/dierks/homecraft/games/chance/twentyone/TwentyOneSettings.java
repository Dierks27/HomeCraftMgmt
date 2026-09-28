package com.dierks.homecraft.games.chance.twentyone;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Twenty-One's settings: {@code games.twenty_one} (spec §5.4, R1.5) — and, because the odds are
 * solved while they are read, the engine object itself: the screen, {@code /hcm arcade odds}, the
 * feed and every hand dealt all read the SAME {@link #odds()} (spec §3.1.1).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * <p><b>The solve (R1.1).</b> For each stake the win multiplier {@code m} runs over [0.5, 1.0];
 * each distinct set of whole-token payouts it can produce has an exact best-play return
 * ({@link TwentyOneMath}), and the stake takes the largest one at or below {@code rtp} — or,
 * when even the lowest reachable one is under 85, the smallest one from 85 up (one INFO line). A
 * stake with nothing inside 85-95 is left out with one WARN; when that leaves no stake at all, one
 * WARN says so and the game stays closed ({@link TwentyOne#configEnabled()}).
 *
 * @param enabled      the game's own switch (it also needs {@code games.enabled})
 * @param stakes       the choices of tokens to put in, smallest first, as configured
 * @param dailyLimit   hands a player gets a day
 * @param rtp          the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param naturalBonus how much more a two-card 21 pays than a plain win (1.5 = half as much again)
 * @param odds         the solved stakes, smallest first: the only ones a hand can be dealt at
 */
public record TwentyOneSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                double naturalBonus, List<Odds> odds) {

    /** The game id, for the solve's log lines. */
    static final String ID = "twenty_one";

    /** The leaves under {@code games.twenty_one}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp",
            "natural_bonus");

    public TwentyOneSettings {
        stakes = List.copyOf(stakes);
        odds = List.copyOf(odds);
    }

    /**
     * One playable stake.
     *
     * @param terms what a hand at this stake pays (written into every round dealt at it)
     * @param rtp   its best-play return per token put in, computed exactly (a fraction: 0.8975)
     */
    public record Odds(TwentyOneHand.Terms terms, double rtp) {

        public int stake() {
            return terms.in();
        }

        public TwentyOneMath.Payouts payouts() {
            return terms.payouts();
        }
    }

    /** The shipped settings. */
    public static TwentyOneSettings defaults() {
        List<Integer> stakes = List.of(5, 10, 20);
        double rtp = 90;
        double bonus = 1.5;
        int cap = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new TwentyOneSettings(true, stakes, 30, rtp, bonus, solve(stakes, rtp, bonus, cap, null, null, null));
    }

    /** Read {@code games.twenty_one} over {@code d}; never throws. */
    public static TwentyOneSettings parse(GamesConfig.Node n, TwentyOneSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        double naturalBonus = n.num("natural_bonus", d.naturalBonus(), 1.0, 3.0);
        GamesConfig.Common common = n.common() != null ? n.common() : GamesConfig.Common.defaults();
        List<Odds> odds = solve(stakes, rtp, naturalBonus, common.maxPayoutFor(stakes), n.key("stakes"), n::warn,
                n::info);
        return new TwentyOneSettings(enabled, stakes, dailyLimit, rtp, naturalBonus, odds);
    }

    /**
     * The R1.1 solve for every stake.
     *
     * @param key  the stakes key, for the WARN lines
     * @param warn a stake left out, or none left ({@code null}: silent, for the shipped defaults)
     * @param info a stake that could only land above its target ({@code null}: silent)
     */
    static List<Odds> solve(List<Integer> stakes, double rtp, double naturalBonus, int cap, String key,
                            Consumer<String> warn, Consumer<String> info) {
        Ratio target = Ratio.percent(rtp);
        Ratio bonus = bonusRatio(naturalBonus);
        List<Odds> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int stake : stakes) {
            List<TwentyOneMath.Candidate> cands = TwentyOneMath.candidates(stake, bonus, cap);
            TwentyOneMath.Choice c = TwentyOneMath.choose(target, cands);
            if (c.index() < 0) {
                dropped.add(stake + " (nearest " + RtpLimits.nearest(c.nearest()) + ")");
                continue;
            }
            if (c.aboveTarget() && info != null) {
                info.accept(ID + " stake " + stake + ": " + RtpLimits.tenthPercent(c.solution().rtp()) + "% (target "
                        + fmt(rtp) + " not reachable in whole tokens)");
            }
            out.add(new Odds(new TwentyOneHand.Terms(stake, cands.get(c.index()).m(), bonus, cap), c.solution().rtp()));
        }
        if (!dropped.isEmpty() && warn != null) {
            if (out.isEmpty()) {
                warn.accept(key + " " + stakes + ": no stake can give back 85-95 of every 100 tokens in whole"
                        + " tokens (" + String.join(", ", dropped) + ") - Twenty-One is closed until it is fixed");
            } else {
                for (String d : dropped) {
                    warn.accept(key + " " + d + " can't give back 85-95 of every 100 tokens in whole tokens"
                            + " - that stake is left out");
                }
            }
        }
        return out;
    }

    /** natural_bonus as the exact fraction its decimal says (1.5 → 3/2). */
    static Ratio bonusRatio(double naturalBonus) {
        BigDecimal b = BigDecimal.valueOf(naturalBonus).stripTrailingZeros();
        BigInteger num = b.unscaledValue();
        BigInteger den = BigInteger.ONE;
        if (b.scale() > 0) {
            den = BigInteger.TEN.pow(b.scale());
        } else if (b.scale() < 0) {
            num = num.multiply(BigInteger.TEN.pow(-b.scale()));
        }
        return TwentyOneMath.reduced(num, den);
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    /** The stakes a hand can be dealt at (the solved ones), smallest first. */
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
