package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Mob Races' settings: {@code games.mob_races}, and what a winning pick pays at each stake, solved as they are read.
 *
 * <p>Six racers, each as likely to win as any other, so a pick wins one race in six. A winning pick pays one whole
 * amount; anything else pays nothing. That amount is solved per stake ({@link #solve(int, double, int)}): of the
 * returns whole amounts can give ({@code pays / (6 x stake)}), the one nearest the target from below that is inside
 * the 85-95 band locked in code ({@link RtpLimits#pick}). A stake no whole amount can put in the band is left out with
 * one WARN, and the game closes only when none is left.
 *
 * @param enabled       the game's own switch
 * @param stakes        what a player may put on a racer, smallest first, as configured
 * @param dailyLimit    races a player may enter a day
 * @param rtp           the target return, a percent (85-95)
 * @param gatherSeconds how long the track waits for more entries after the first
 * @param raceSeconds   how long the winner takes to reach the finish
 * @param odds          the solved stakes, smallest first: every stake a player can actually pick
 */
public record MobRacesSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp, int gatherSeconds,
                               int raceSeconds, List<Odds> odds) {

    public static final String ID = "mob_races";
    /** The leaves under {@code games.mob_races}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp", "gather_seconds",
            "race_seconds");

    /** The limits of the two clocks, in seconds. */
    public static final int GATHER_MIN = 5;
    public static final int GATHER_MAX = 120;
    public static final int RACE_MIN = 10;
    public static final int RACE_MAX = 60;
    /** About one lap of the track for every this many seconds of race, so the racers keep one pace. */
    static final double SECONDS_A_LAP = 8.5;

    /**
     * Mob Races at one stake, as solved.
     *
     * @param stake what goes on a racer
     * @param pays  what a winning pick gets back (always more than {@code stake}: inside the band it is at least 5.1
     *              times it)
     */
    public record Odds(int stake, int pays) {

        /** The exact return: {@code pays / (6 x stake)}. */
        public RtpLimits.Ratio ratio() {
            return new RtpLimits.Ratio(pays, (long) Racer.COUNT * stake);
        }

        /** The exact return as a fraction: publish and floor THIS, never the target. */
        public double rtp() {
            return ratio().value();
        }
    }

    public MobRacesSettings {
        stakes = List.copyOf(stakes);
        odds = List.copyOf(odds);
    }

    /** The shipped settings: 5, 10 or 20 on a racer; a winning pick pays 27, 54 or 108 (exactly 90 of every 100). */
    public static MobRacesSettings defaults() {
        List<Integer> stakes = List.of(5, 10, 20);
        double rtp = 90;
        int cap = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new MobRacesSettings(true, stakes, 30, rtp, 20, 25,
                solve("games." + ID, stakes, rtp, cap, w -> { }, i -> { }));
    }

    /** Read {@code games.mob_races} over {@code d}, then solve; never throws. */
    public static MobRacesSettings parse(GamesConfig.Node n, MobRacesSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = List.copyOf(new TreeSet<>(n.intList("stakes", d.stakes(), 1, 1000)));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        int gather = n.whole("gather_seconds", d.gatherSeconds(), GATHER_MIN, GATHER_MAX);
        int race = n.whole("race_seconds", d.raceSeconds(), RACE_MIN, RACE_MAX);
        GamesConfig.Common common = n.common() == null ? GamesConfig.Common.defaults() : n.common();
        List<Odds> odds = solve(n.path(), stakes, rtp, common.maxPayoutFor(stakes), n::warn, n::info);
        return new MobRacesSettings(enabled, stakes, dailyLimit, rtp, gather, race, odds);
    }

    /**
     * What a winning pick pays with {@code stake} on a racer, for a target of {@code rtp} percent, when no play may
     * give back more than {@code cap}; {@code null} when no whole amount lands inside the band there.
     *
     * <p>The candidates are every whole amount from one more than the stake up to the cap; each one's return is
     * exactly {@code pays / (6 x stake)}.
     */
    public static Odds solve(int stake, double rtp, int cap) {
        if (stake <= 0) {
            return null;
        }
        // nothing over 95 of every 100 is ever taken, so the candidates stop at the stake's own 6 x
        int most = (int) Math.min(cap, (long) Racer.COUNT * stake);
        if (most <= stake) {
            return null;
        }
        RtpLimits.Ratio[] can = new RtpLimits.Ratio[most - stake];
        for (int i = 0; i < can.length; i++) {
            can[i] = new RtpLimits.Ratio(stake + 1 + i, (long) Racer.COUNT * stake);
        }
        RtpLimits.Pick pick = RtpLimits.pick(RtpLimits.Ratio.percent(rtp), can);
        return pick == null ? null : new Odds(stake, stake + 1 + pick.index());
    }

    /** Solve every stake: one WARN per stake left out (one in all when none is left), one INFO per stake over its target. */
    static List<Odds> solve(String path, List<Integer> stakes, double rtp, int cap, Consumer<String> warn,
                            Consumer<String> info) {
        List<Odds> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        RtpLimits.Ratio target = RtpLimits.Ratio.percent(rtp);
        for (int stake : stakes) {
            Odds o = solve(stake, rtp, cap);
            if (o == null) {
                boolean capped = solve(stake, rtp, Integer.MAX_VALUE) != null;
                dropped.add(path + ".stakes " + stake + " is off: " + (capped ? "max_payout " + cap
                        + " is under what a winning pick must pay there"
                        : "no whole amount gives back 85-95 of every 100 at that stake"));
                continue;
            }
            out.add(o);
            if (o.ratio().compareTo(target) > 0) {
                info.accept("mob_races stake " + stake + ": " + RtpLimits.tenthPercent(o.rtp()) + "% (target "
                        + fmt(rtp) + " not reachable in whole tokens)");
            }
        }
        if (out.isEmpty() && !stakes.isEmpty()) {
            warn.accept(path + " has no stake that can give back 85-95 of every 100 tokens - Mob Races is closed"
                    + " until it is fixed");
            return out;
        }
        dropped.forEach(warn);
        return out;
    }

    /** The solved odds for a stake, or {@code null} when that stake isn't offered. */
    public Odds odds(int stake) {
        for (Odds o : odds) {
            if (o.stake() == stake) {
                return o;
            }
        }
        return null;
    }

    /** The stakes a player can pick (the configured ones the solve kept), smallest first. */
    public List<Integer> open() {
        return odds.stream().map(Odds::stake).toList();
    }

    /** The lowest exact return over the offered stakes ({@code NaN} with none): the game's one honest number. */
    public double lowestRtp() {
        double low = Double.NaN;
        for (Odds o : odds) {
            if (Double.isNaN(low) || o.rtp() < low) {
                low = o.rtp();
            }
        }
        return low;
    }

    /** How long the winner takes to reach the finish, in ticks. */
    public int raceTicks() {
        return raceSeconds * 20;
    }

    /** How many laps a race is, for its length: about one for every {@value #SECONDS_A_LAP} seconds, at least one. */
    public int laps() {
        return laps(raceSeconds);
    }

    static int laps(int raceSeconds) {
        return Math.max(1, (int) Math.round(raceSeconds / SECONDS_A_LAP));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
