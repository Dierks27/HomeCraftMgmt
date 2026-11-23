package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.config.GamesConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Mob Races' settings: {@code games.mob_races}, and which stakes can be offered at all.
 *
 * <p>What a pick pays is not fixed here: each race has its card ({@link RaceCard}), each racer its own chance on it,
 * and each racer and stake its own pay ({@link RaceCard#pay(int, int, double, int)}), the whole amount whose return
 * is closest to {@link #rtp} inside the 85-95 band locked in code and no more than {@link #cap}. A pair with no such
 * amount is simply not offered in that race. A stake is left out altogether (one WARN) only when not even the strongest
 * favourite a card can have ({@value RaceCard#HIGH} parts in {@value RaceCard#WHOLE}) could be paid there: its pay
 * would be over the cap. The game closes only when no stake is left.
 *
 * @param enabled       the game's own switch
 * @param stakes        what a player may put on a racer, smallest first, as configured
 * @param dailyLimit    races a player may enter a day
 * @param rtp           the target return, a percent (85-95)
 * @param gatherSeconds how long the track waits for more entries after the first
 * @param raceSeconds   how long the winner takes to reach the finish
 * @param cap           the most one token entry may pay ({@code games.max_payout}, never under the largest stake)
 * @param open          the stakes a player can pick, smallest first: the configured ones that can be offered
 */
public record MobRacesSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp, int gatherSeconds,
                               int raceSeconds, int cap, List<Integer> open) {

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

    public MobRacesSettings {
        stakes = List.copyOf(stakes);
        open = List.copyOf(open);
    }

    /** The shipped settings: 5, 10 or 20 on a racer, aiming at 90 of every 100 back, 30 races a day. */
    public static MobRacesSettings defaults() {
        List<Integer> stakes = List.of(5, 10, 20);
        double rtp = 90;
        int cap = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new MobRacesSettings(true, stakes, 30, rtp, 20, 25, cap, open("games." + ID, stakes, rtp, cap,
                w -> { }));
    }

    /** These settings with {@code dailyLimit} races a day (tests and admin tools). */
    public MobRacesSettings withDailyLimit(int dailyLimit) {
        return new MobRacesSettings(enabled, stakes, dailyLimit, rtp, gatherSeconds, raceSeconds, cap, open);
    }

    /** Read {@code games.mob_races} over {@code d}; never throws. */
    public static MobRacesSettings parse(GamesConfig.Node n, MobRacesSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = List.copyOf(new TreeSet<>(n.intList("stakes", d.stakes(), 1, 1000)));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        int gather = n.whole("gather_seconds", d.gatherSeconds(), GATHER_MIN, GATHER_MAX);
        int race = n.whole("race_seconds", d.raceSeconds(), RACE_MIN, RACE_MAX);
        GamesConfig.Common common = n.common() == null ? GamesConfig.Common.defaults() : n.common();
        int cap = common.maxPayoutFor(stakes);
        return new MobRacesSettings(enabled, stakes, dailyLimit, rtp, gather, race, cap,
                open(n.path(), stakes, rtp, cap, n::warn));
    }

    /**
     * Whether {@code stake} can be offered at all under {@code cap}: the strongest favourite a card can have
     * ({@value RaceCard#HIGH} parts) has a pay there inside the band. Any weaker racer pays more, so it may not.
     */
    public static boolean offerable(int stake, double rtp, int cap) {
        return RaceCard.pay(RaceCard.HIGH, stake, rtp, cap) != null;
    }

    /** The stakes that can be offered: one WARN per stake left out (one in all when none is left). */
    static List<Integer> open(String path, List<Integer> stakes, double rtp, int cap, Consumer<String> warn) {
        List<Integer> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int stake : stakes) {
            if (offerable(stake, rtp, cap)) {
                out.add(stake);
            } else {
                boolean capped = offerable(stake, rtp, Integer.MAX_VALUE);
                dropped.add(path + ".stakes " + stake + " is off: " + (capped ? "max_payout " + cap
                        + " is under what even a favourite must pay there"
                        : "no whole amount gives back 85-95 of every 100 at that stake"));
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

    /** Whether {@code stake} is one a player can pick (in tokens). */
    public boolean offers(int stake) {
        return open.contains(stake);
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
}
