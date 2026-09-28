package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;

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
 * The odds are solved in {@link #parse} too, from the same values the game plays with, so a
 * configuration that can't be solved inside the RTP band closes the game the moment it loads.
 *
 * @param enabled the game's own switch (it also needs {@code games.enabled})
 * @param stakes the choices of tokens to put in, smallest first
 * @param dailyLimit plays a player gets a day
 * @param rtp the target return, a percent; clamped to 85-95 in code ({@code RtpLimits})
 * @param reels how often each symbol lands, as whole-number weights (0 = never); Stone is solved
 * @param pays what each line pays, as a multiple of the tokens put in ({@code two}: any two the same)
 */
public record OreSlotsSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                               Map<String, Integer> reels, Map<String, Integer> pays) {

    /** The leaves under {@code games.ore_slots}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "stakes", "daily_limit", "rtp", "reels.coal",
            "reels.copper", "reels.iron", "reels.gold", "reels.diamond", "reels.wild", "pays.two",
            "pays.coal", "pays.copper", "pays.iron", "pays.gold", "pays.diamond", "pays.wild");

    public OreSlotsSettings {
        stakes = List.copyOf(stakes);
        reels = Collections.unmodifiableMap(new LinkedHashMap<>(reels));
        pays = Collections.unmodifiableMap(new LinkedHashMap<>(pays));
    }

    /** The shipped settings. */
    public static OreSlotsSettings defaults() {
        return new OreSlotsSettings(
                true,
                List.of(1, 2, 5),
                50,
                90,
                map("coal", 10, "copper", 8, "iron", 6, "gold", 4, "diamond", 2, "wild", 2),
                map("two", 2, "coal", 4, "copper", 6, "iron", 10, "gold", 20, "diamond", 40, "wild", 50));
    }

    /** Read {@code games.ore_slots} over {@code d}; never throws. */
    public static OreSlotsSettings parse(GamesConfig.Node n, OreSlotsSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = sortedDistinct(n.intList("stakes", d.stakes(), 1, 1000));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        Map<String, Integer> reels = n.wholeMap("reels", d.reels(), 0, 1_000_000);
        Map<String, Integer> pays = n.wholeMap("pays", d.pays(), 0, 10_000);
        // owner: solve here - the exact RTP per stake from these values (RtpLimits.pick, R1.1),
        // with payouts capped at n.common().maxPayoutFor(stakes); a stake with nothing in the
        // band is dropped with one n.warn, and the game is closed when no stake is left.
        return new OreSlotsSettings(enabled, stakes, dailyLimit, rtp, reels, pays);
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
}
