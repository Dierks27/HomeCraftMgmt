package com.dierks.homecraft.games.chance.fuse;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 0.57.0: Creeper Fuse's settings, {@code games.creeper_fuse}, and the engine object the screen, the fuse,
 * {@code /hcm arcade odds} and the website all read.
 *
 * <p>{@code rtp} is what every cash-out gives back on average ({@link FuseMath}: the fuse's chance of still burning
 * past {@code m} is {@code rtp / m}). The pay is whole tokens, rounded down, so each stake is checked for the least a
 * cash-out anywhere from 1.00x to its own top can give back ({@link FuseMath#lowestReturn}): a stake where that is
 * under 85 of every 100 (5 tokens: 5 x 1.19 pays 5) is left out with one WARN naming it, and with none left the game
 * is closed (one WARN). A stake's own top is where its pay reaches the payout cap ({@code games.max_payout}): the fuse
 * cashes it out there by itself, which changes nothing about what it gives back.
 *
 * @param enabled       the game's own switch (it also needs {@code games.enabled})
 * @param stakes        the configured choices of tokens to put in, smallest first
 * @param dailyLimit    fuses a player may put in for a day
 * @param rtp           what every cash-out gives back of 100, a percent clamped to 85-95 ({@link RtpLimits})
 * @param gatherSeconds how long the first put-in waits for others before the fuse is lit
 * @param doubleSeconds how long the multiple takes to double (1.00x to 2.00x, 2.00x to 4.00x ...)
 * @param top           the most the multiple climbs to, in hundredths (2000 is 20.00x): the fuse burns out there
 * @param autoChoices   the auto cash-out multiples a player can pick, in hundredths, smallest first
 * @param odds          the stakes that can be played, smallest first
 */
public record CreeperFuseSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                  int gatherSeconds, int doubleSeconds, int top, List<Integer> autoChoices,
                                  List<Odds> odds) {

    /** The game id. */
    public static final String ID = "creeper_fuse";

    /** The leaves under {@code games.creeper_fuse}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp", "gather_seconds",
            "double_seconds", "top", "auto_cash_out");

    public static final int GATHER_MIN = 5;
    public static final int GATHER_MAX = 60;
    public static final int DOUBLE_MIN = 2;
    public static final int DOUBLE_MAX = 30;
    /** The top, as a multiple: 2x to 100x. */
    public static final double TOP_MIN = 2;
    public static final double TOP_MAX = 100;
    /** The most auto cash-out choices the screen has room for. */
    public static final int AUTO_MAX = 5;

    public CreeperFuseSettings {
        stakes = List.copyOf(stakes);
        autoChoices = List.copyOf(autoChoices);
        odds = List.copyOf(odds);
    }

    /**
     * One stake that can be played.
     *
     * @param stake  tokens (or dollars) put in
     * @param cap    the most a cash-out at it pays (the payout cap, never below the stake)
     * @param top    the most it can cash out at, in hundredths: the fuse's top, or lower where the cap is reached
     * @param lowest the least a cash-out at it gives back, per token in (rounding down to whole tokens)
     */
    public record Odds(int stake, int cap, int top, double lowest) {
    }

    /** The shipped settings. */
    public static CreeperFuseSettings defaults() {
        List<Integer> stakes = List.of(10, 20, 50);
        double rtp = 94;
        int top = 2000;
        int cap = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new CreeperFuseSettings(true, stakes, 30, rtp, 10, 6, top, List.of(150, 200, 300, 500, 1000),
                solve("games." + ID, stakes, rtp, top, cap, w -> { }));
    }

    /** Read {@code games.creeper_fuse} over {@code d}; never throws. */
    public static CreeperFuseSettings parse(GamesConfig.Node n, CreeperFuseSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = List.copyOf(new TreeSet<>(n.intList("stakes", d.stakes(), 1, 1000)));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        int gather = n.whole("gather_seconds", d.gatherSeconds(), GATHER_MIN, GATHER_MAX);
        int doubling = n.whole("double_seconds", d.doubleSeconds(), DOUBLE_MIN, DOUBLE_MAX);
        int top = hundredths(n.num("top", d.top() / 100.0, TOP_MIN, TOP_MAX));
        List<Integer> auto = autoChoices(n, d, top);
        GamesConfig.Common common = n.common() == null ? GamesConfig.Common.defaults() : n.common();
        List<Odds> odds = solve(n.path(), stakes, rtp, top, common.maxPayoutFor(stakes), n::warn);
        return new CreeperFuseSettings(enabled, stakes, dailyLimit, rtp, gather, doubling, top, auto, odds);
    }

    /** The auto cash-out choices: above 1.00x and under the top, at most {@link #AUTO_MAX}, smallest first. */
    private static List<Integer> autoChoices(GamesConfig.Node n, CreeperFuseSettings d, int top) {
        List<Double> read = n.doubleList("auto_cash_out", hundredthsList(d.autoChoices()), 1.01, TOP_MAX);
        TreeSet<Integer> out = new TreeSet<>();
        for (double v : read) {
            int m = hundredths(v);
            if (m > FuseMath.ONE && m < top) {
                out.add(m);
            }
        }
        List<Integer> list = new ArrayList<>(out);
        if (list.size() > AUTO_MAX) {
            n.warn(n.key("auto_cash_out") + " has " + list.size() + " choices - the screen shows the first " + AUTO_MAX);
            list = list.subList(0, AUTO_MAX);
        }
        return List.copyOf(list);
    }

    private static List<Double> hundredthsList(List<Integer> ms) {
        List<Double> out = new ArrayList<>(ms.size());
        for (int m : ms) {
            out.add(m / 100.0);
        }
        return out;
    }

    /** 2.5 as 250. */
    static int hundredths(double multiple) {
        return (int) Math.round(multiple * 100.0);
    }

    /**
     * Every stake's own top and the least a cash-out there gives back: one WARN per stake left out (one in all when
     * none is left).
     *
     * @param cap the payout cap (never below the largest stake)
     */
    static List<Odds> solve(String path, List<Integer> stakes, double rtp, int top, int cap, Consumer<String> warn) {
        Ratio r = Ratio.percent(rtp);
        List<Odds> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int stake : stakes) {
            Odds o = odds(r, stake, cap, top);
            if (o.lowest() >= RtpLimits.MIN - 1e-12) {
                out.add(o);
            } else {
                dropped.add(stake + " (a cash-out there can give back as little as "
                        + RtpLimits.tenthPercent(o.lowest()) + "%)");
            }
        }
        if (out.isEmpty() && !stakes.isEmpty()) {
            warn.accept(path + ".stakes has no stake whose every cash-out gives back 85 or more of every 100 ("
                    + String.join("; ", dropped) + ") - Creeper Fuse is closed until it is fixed");
            return out;
        }
        for (String d : dropped) {
            warn.accept(path + ".stakes " + d + " is off: the pay is whole tokens, rounded down - raise rtp or the"
                    + " stake");
        }
        return out;
    }

    /** One stake's terms under {@code cap}, with the least a cash-out gives back there. */
    public static Odds odds(Ratio rtp, int stake, int cap, int top) {
        int most = FuseMath.topFor(stake, cap, top);
        BigInteger[] low = FuseMath.lowestReturn(rtp, stake, cap, top);
        double lowest = new java.math.BigDecimal(low[0]).divide(new java.math.BigDecimal(low[1]),
                java.math.MathContext.DECIMAL64).doubleValue();
        return new Odds(stake, Math.max(cap, stake), most, lowest);
    }

    // ---- reading it --------------------------------------------------------------------------------------------

    /** The target as an exact fraction (94 is 47/50). */
    public Ratio ratio() {
        return Ratio.percent(rtp);
    }

    /** The return as a fraction (0.94). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    /** The odds at a stake, or {@code null} when it isn't offered. */
    public Odds odds(int stake) {
        for (Odds o : odds) {
            if (o.stake() == stake) {
                return o;
            }
        }
        return null;
    }

    /** The stakes a player can pick, smallest first. */
    public List<Integer> open() {
        return odds.stream().map(Odds::stake).toList();
    }

    /** How long the multiple takes to double, in ms. */
    public long doubleMs() {
        return doubleSeconds * 1000L;
    }
}
