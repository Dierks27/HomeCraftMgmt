package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Ore Slots's settings: {@code games.ore_slots} (spec §5.3, R1.4).
 *
 * <p>{@link #defaults()} is the single source of the shipped values: the bundled config.yml
 * block parses to exactly it without a WARN ({@code GamesConfigTest}). {@link #parse} reads each
 * key over the defaults: an out-of-range number is clamped with one WARN naming its full key,
 * junk closes the game (one WARN, {@link GamesConfig.Node#invalid}); it never throws.
 *
 * <p>The odds are solved in {@link #parse} too, from the same values the game plays with, and the
 * result is {@link #engine()}: this record IS the engine object the screen, {@code /hcm arcade
 * odds}, the website and the spin all read (spec §3.1.1). A stake the solve can't fit inside the
 * 85-95 band is dropped with one WARN naming it; if no stake is left the game closes with one WARN
 * the moment it loads; a stake that could only land just above its target logs one INFO line.
 *
 * @param enabled    the game's own switch (it also needs {@code games.enabled})
 * @param stakes     the choices of tokens to put in, smallest first, as configured
 * @param dailyLimit spins a player gets a day
 * @param rtp        the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param reels      how often each symbol lands, as whole-number weights (0 = never); Stone is solved
 * @param pays       what each line pays, as a multiple of the tokens put in ({@code two}: any two the same)
 * @param engine     the solved odds and the draw, from exactly these values and the payout cap
 */
public record OreSlotsSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                               Map<String, Integer> reels, Map<String, Integer> pays, SlotsEngine engine) {

    /** The leaves under {@code games.ore_slots}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp", "reels.coal",
            "reels.copper", "reels.iron", "reels.gold", "reels.diamond", "reels.wild", "pays.two",
            "pays.coal", "pays.copper", "pays.iron", "pays.gold", "pays.diamond", "pays.wild");

    /** The screen has room for this many stake buttons. */
    public static final int MAX_STAKES = 7;
    /** The most a reel weight may be, so every reel's total stays a whole {@code int}. */
    public static final int MAX_WEIGHT = 10_000;
    /** The most a line may pay, as a multiple of the tokens in. */
    public static final int MAX_MULTIPLE = 10_000;

    public OreSlotsSettings {
        stakes = List.copyOf(stakes);
        reels = Collections.unmodifiableMap(new LinkedHashMap<>(reels));
        pays = Collections.unmodifiableMap(new LinkedHashMap<>(pays));
    }

    /** The shipped settings (solved against the shipped {@code games.max_payout}). */
    public static OreSlotsSettings defaults() {
        List<Integer> stakes = List.of(1, 2, 5);
        return of(true, stakes, 50, 90,
                map("coal", 10, "copper", 8, "iron", 6, "gold", 4, "diamond", 2, "wild", 2),
                map("two", 2, "coal", 4, "copper", 6, "iron", 10, "gold", 20, "diamond", 40, "wild", 50),
                GamesConfig.Common.defaults().maxPayoutFor(stakes));
    }

    /** Settings with their engine solved against the payout cap {@code cap}. */
    public static OreSlotsSettings of(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                      Map<String, Integer> reels, Map<String, Integer> pays, int cap) {
        return new OreSlotsSettings(enabled, stakes, dailyLimit, rtp, reels, pays,
                SlotsEngine.solve(reels, pays, stakes, cap, rtp));
    }

    /** Read {@code games.ore_slots} over {@code d}, then solve; never throws. */
    public static OreSlotsSettings parse(GamesConfig.Node n, OreSlotsSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        if (stakes.size() > MAX_STAKES) {
            List<Integer> kept = List.copyOf(stakes.subList(0, MAX_STAKES));
            n.warn(n.key("stakes") + " " + stakes + " has more than " + MAX_STAKES
                    + " stakes, which is all the screen shows - using " + kept);
            stakes = kept;
        }
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        Map<String, Integer> reels = n.wholeMap("reels", d.reels(), 0, MAX_WEIGHT);
        Map<String, Integer> pays = n.wholeMap("pays", d.pays(), 0, MAX_MULTIPLE);
        GamesConfig.Common common = n.common() != null ? n.common() : GamesConfig.Common.defaults();
        OreSlotsSettings s = of(enabled, stakes, dailyLimit, rtp, reels, pays, common.maxPayoutFor(stakes));
        report(n, s);
        return s;
    }

    /** The solve's WARN and INFO lines (spec §5.1): a dropped stake, no stake left, just above target. */
    private static void report(GamesConfig.Node n, OreSlotsSettings s) {
        SlotsEngine e = s.engine();
        String band = "85-95 of every 100 tokens";
        if (!e.playable()) {
            n.invalid(n.path() + " can't give back " + band + " at any stake (" + why(s) + ")");
            return;
        }
        for (SlotsEngine.Dropped d : e.dropped()) {
            n.warn(n.key("stakes") + " " + d.stake() + " can't give back " + band + " (nearest " + d.nearest()
                    + " with max_payout " + e.cap() + ") - Ore Slots doesn't take " + d.stake() + " tokens");
        }
        for (SlotsEngine.StakeOdds o : e.odds().values()) {
            if (o.aboveTarget()) {
                n.info("ore_slots stake " + o.stake() + ": " + RtpLimits.tenthPercent(o.rtp()) + "% (target "
                        + fmt(s.rtp()) + " not reachable with whole weights)");
            }
        }
    }

    /** Why nothing could be solved, for the one WARN. */
    private static String why(OreSlotsSettings s) {
        if (s.reels().values().stream().allMatch(w -> w == 0)) {
            return "every reel weight is 0";
        }
        if (s.pays().values().stream().allMatch(p -> p == 0)) {
            return "no line pays anything";
        }
        StringBuilder sb = new StringBuilder();
        for (SlotsEngine.Dropped d : s.engine().dropped()) {
            sb.append(sb.length() == 0 ? "" : ", ").append("stake ").append(d.stake()).append(": nearest ")
                    .append(d.nearest());
        }
        return sb.toString();
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }

    /** An ordered, unmodifiable map from key, value pairs. */
    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return List.copyOf(new TreeSet<>(in));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
