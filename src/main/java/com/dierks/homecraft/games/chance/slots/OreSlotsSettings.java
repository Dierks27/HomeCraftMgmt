package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>It is a {@link SlotTable}, the shape the shared machine and its screen read, so the themed machines
 * ({@link SlotSettings}) run through the same code. Its keys and its lines are as they always were.
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
                               Map<String, Integer> reels, Map<String, Integer> pays, SlotsEngine engine)
        implements SlotTable {

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
                SlotsTables.map("coal", 10, "copper", 8, "iron", 6, "gold", 4, "diamond", 2, "wild", 2),
                SlotsTables.map("two", 2, "coal", 4, "copper", 6, "iron", 10, "gold", 20, "diamond", 40, "wild", 50),
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
        List<Integer> stakes = SlotsTables.stakes(n, d.stakes(), MAX_STAKES);
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        Map<String, Integer> reels = n.wholeMap("reels", d.reels(), 0, MAX_WEIGHT);
        Map<String, Integer> pays = n.wholeMap("pays", d.pays(), 0, MAX_MULTIPLE);
        GamesConfig.Common common = n.common() != null ? n.common() : GamesConfig.Common.defaults();
        OreSlotsSettings s = of(enabled, stakes, dailyLimit, rtp, reels, pays, common.maxPayoutFor(stakes));
        SlotsTables.report(n, "ore_slots", "Ore Slots", "tokens", s.engine(), s.rtp(), s.reels(), s.pays(), false);
        return s;
    }

    /** Ore Slots' own look: the ores. */
    @Override
    public SlotTheme theme() {
        return SlotTheme.ORE;
    }

    /** Its reels are already written with the engine's keys. */
    @Override
    public Map<String, Integer> engineReels() {
        return reels;
    }

    @Override
    public Map<String, Integer> enginePays() {
        return pays;
    }

    /** The target return as a fraction (0.90). */
    public double rtpFraction() {
        return rtp / 100.0;
    }
}
