package com.dierks.homecraft.games.chance.coinflip;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Coin Flip's settings: {@code games.coin_flip} (spec §5.7, R1.8).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * <p>The odds are solved in {@link #parse} too (the R1.1 rule): per stake, what the winner gets,
 * a whole number of tokens out of the {@code 2 · stake} put in, so that each player gets back as
 * close to {@code rtp} as whole tokens allow. There is no separate fee: what the winner doesn't
 * get is simply gone. A stake that can't land inside 85-95 is left out with one WARN (saying
 * whether whole tokens or {@code max_payout} hold it back), and the game closes only when no stake
 * is left (one WARN for the lot). The screens, the flip, {@code /hcm arcade odds} and the website
 * all read this one object.
 *
 * @param enabled        the game's own switch (it also needs {@code games.enabled}); ships false
 * @param stakes         the configured choices of tokens to put in, smallest first
 * @param dailyLimit     flips a player gets a day
 * @param pairDailyLimit flips the same two players get a day
 * @param rtp            the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param maxDistance    how close the two must stand, in blocks (0 = anywhere in the same world)
 * @param inviteSeconds  how long an invite waits for an answer
 * @param odds           the solved stakes, smallest first: every stake a player can actually pick
 */
public record CoinFlipSettings(boolean enabled, List<Integer> stakes, int dailyLimit, int pairDailyLimit,
                               double rtp, int maxDistance, int inviteSeconds, List<CoinFlipOdds> odds) {

    /** The leaves under {@code games.coin_flip}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "pair_daily_limit",
            "rtp", "max_distance", "invite_seconds");

    public CoinFlipSettings {
        stakes = List.copyOf(stakes);
        odds = List.copyOf(odds);
    }

    /** The shipped settings. */
    public static CoinFlipSettings defaults() {
        List<Integer> stakes = List.of(5, 10, 25);
        double rtp = 90;
        int cap = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new CoinFlipSettings(
                false,
                stakes,
                5,
                2,
                rtp,
                32,
                60,
                solve("games.coin_flip", stakes, rtp, cap, w -> { }, i -> { }));
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
        GamesConfig.Common common = n.common() == null ? GamesConfig.Common.defaults() : n.common();
        List<CoinFlipOdds> odds = solve(n.path(), stakes, rtp, common.maxPayoutFor(stakes), n::warn, n::info);
        return new CoinFlipSettings(enabled, stakes, dailyLimit,
                pairDailyLimit, rtp, maxDistance, inviteSeconds, odds);
    }

    /**
     * What one stake can reach: the winner gets 0, 1, ... tokens (up to the cap, and one past
     * everything put in, so the WARN can name the nearest value above the band), each giving back
     * {@code pays / (2 · stake)}.
     */
    static RtpLimits.Ratio[] reachable(int stake, int cap) {
        int top = Math.min(Math.max(stake, cap), 2 * stake + 1);
        RtpLimits.Ratio[] out = new RtpLimits.Ratio[top + 1];
        for (int pays = 0; pays <= top; pays++) {
            out[pays] = new RtpLimits.Ratio(pays, 2L * stake);
        }
        return out;
    }

    /**
     * Solve every stake (spec R1.1): the winner's share is the index of the pick in
     * {@link #reachable}. One WARN per dropped stake (one in all when none is left), one INFO per
     * stake that could only land above its target.
     */
    static List<CoinFlipOdds> solve(String path, List<Integer> stakes, double rtp, int cap,
                                    Consumer<String> warn, Consumer<String> info) {
        RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
        List<CoinFlipOdds> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (int stake : stakes) {
            RtpLimits.Ratio[] reach = reachable(stake, cap);
            RtpLimits.Pick pick = RtpLimits.pick(target, reach);
            if (pick != null) {
                CoinFlipOdds o = new CoinFlipOdds(stake, pick.index());
                out.add(o);
                if (pick.aboveTarget()) {
                    info.accept("coin_flip stake " + stake + ": " + RtpLimits.tenthPercent(o.rtp())
                            + "% (target " + fmt(rtp) + " not reachable in whole tokens)");
                }
                continue;
            }
            double[] values = new double[reach.length];
            for (int i = 0; i < reach.length; i++) {
                values[i] = reach[i].value();
            }
            String nearest = RtpLimits.nearest(values);
            all.add(stake + ": " + nearest);
            if (RtpLimits.pick(target, reachable(stake, Integer.MAX_VALUE)) != null) {
                dropped.add(path + ".stakes " + stake + " is off: max_payout " + cap
                        + " holds the winner's share down to " + nearest + " back");
            } else {
                dropped.add(path + ".stakes " + stake + " can't give back 85-95 of every 100 tokens in whole "
                        + "tokens (it reaches " + nearest + ") - that stake is off");
            }
        }
        if (out.isEmpty() && !stakes.isEmpty()) {
            warn.accept(path + " has no stake that can give back 85-95 of every 100 tokens ("
                    + String.join("; ", all) + ") - Coin Flip is closed until it is fixed");
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
    public CoinFlipOdds odds(int stake) {
        for (CoinFlipOdds o : odds) {
            if (o.stake() == stake) {
                return o;
            }
        }
        return null;
    }

    /** The stakes a player can pick (the configured ones the solve kept), smallest first. */
    public List<Integer> open() {
        return odds.stream().map(CoinFlipOdds::stake).toList();
    }

    /** The stake that gives back least: its number stands for the whole game. {@code null} if none. */
    public CoinFlipOdds lowest() {
        CoinFlipOdds low = null;
        for (CoinFlipOdds o : odds) {
            if (low == null || o.ratio().compareTo(low.ratio()) < 0) {
                low = o;
            }
        }
        return low;
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
