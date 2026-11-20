package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep Mine's settings: {@code games.deep_mine}. The Ore Slots shape (stakes, plays a day, target, reels, pays),
 * with one difference in what the numbers mean: {@code stakes} is what goes on EACH row, and a spin plays all three
 * rows, so it costs three times that.
 *
 * <p>Every row is a line drawn by the one-line {@link SlotsEngine}, solved for that row's stake. A stake's engine
 * is solved under a cap of a third of what that stake's whole spin may pay ({@link DeepMineEngine#lineCap}): the
 * spin may pay {@code games.max_payout}, and never less than the spin itself costs. So three rows can never add up
 * to more than the round is held to, and since each row gives back the line's exact return, the whole spin gives
 * back exactly that. Each stake has an engine of its own for this ({@link #bank}).
 *
 * @param enabled    the machine's own switch
 * @param stakes     what a player may put on each row, smallest first, as configured (a spin is three rows)
 * @param dailyLimit spins a player gets a day
 * @param rtp        the target return, a percent (85-95)
 * @param reels      how often each symbol lands, as whole-number weights (Tuff is solved)
 * @param pays       what a row pays, as a multiple of what is on that row
 * @param bank       each offered stake's solved line, from exactly these values and the payout cap
 */
public record DeepMineSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                               Map<String, Integer> reels, Map<String, Integer> pays, SlotBank bank)
        implements SlotTable {

    /** The leaves under {@code games.deep_mine}, in config order. */
    public static final List<String> KEYS = SlotSettings.keys(SlotTheme.DEEP, false);

    public DeepMineSettings {
        stakes = List.copyOf(stakes);
        reels = Collections.unmodifiableMap(new LinkedHashMap<>(reels));
        pays = Collections.unmodifiableMap(new LinkedHashMap<>(pays));
    }

    /**
     * Deep Mine as shipped: 1, 2 or 5 on each row (a spin costs 3, 6 or 15). Two the same gives that row's stake
     * back, three the same pays 3 to 16 times the row's stake, so any three the same gives back at least the whole
     * spin. The top line on the biggest stake is 5 → 80, and three of them are 240, under the shipped
     * {@code games.max_payout} of 250.
     */
    public static DeepMineSettings defaults() {
        return of(true, List.of(1, 2, 5), 50, 90,
                SlotsTables.map("coal", 10, "copper", 8, "iron", 6, "gold", 4, "diamond", 2, "wild", 4),
                SlotsTables.map("two", 1, "coal", 3, "copper", 4, "iron", 6, "gold", 10, "diamond", 15, "wild", 16),
                GamesConfig.Common.defaults().maxPayout());
    }

    /** What a whole spin with {@code perLine} on each row may pay in tokens: the cap, never under the spin itself. */
    public static int roundCap(int maxPayout, int perLine) {
        return Math.max(maxPayout, DeepMineEngine.total(perLine));
    }

    /**
     * The one-line engine for {@code perLine} on a row, when the whole spin may pay {@code roundCap}: a row's cap is
     * a third of it. The engine takes no stake when that row can't land inside the band.
     */
    public static SlotsEngine line(Map<String, Integer> engineReels, Map<String, Integer> enginePays, int perLine,
                                   int roundCap, double rtp) {
        return SlotsEngine.solve(engineReels, enginePays, List.of(perLine), DeepMineEngine.lineCap(roundCap), rtp);
    }

    /** Settings with every stake's line solved against {@code games.max_payout}. */
    public static DeepMineSettings of(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                      Map<String, Integer> reels, Map<String, Integer> pays, int maxPayout) {
        Map<String, Integer> r = SlotTheme.DEEP.engineReels(reels);
        Map<String, Integer> p = SlotTheme.DEEP.enginePays(pays);
        SlotBank.Builder bank = new SlotBank.Builder();
        for (int stake : stakes) {
            bank.add(stake, line(r, p, stake, roundCap(maxPayout, stake), rtp));
        }
        return new DeepMineSettings(enabled, stakes, dailyLimit, rtp, reels, pays,
                bank.build(SlotsEngine.solve(r, p, List.of(), 1, rtp)));
    }

    /** Read {@code games.deep_mine} over {@code d}, then solve; never throws. */
    public static DeepMineSettings parse(GamesConfig.Node n, DeepMineSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = SlotsTables.stakes(n, d.stakes(), OreSlotsSettings.MAX_STAKES);
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        Map<String, Integer> reels = n.wholeMap("reels", d.reels(), 0, OreSlotsSettings.MAX_WEIGHT);
        Map<String, Integer> pays = n.wholeMap("pays", d.pays(), 0, OreSlotsSettings.MAX_MULTIPLE);
        GamesConfig.Common common = n.common() != null ? n.common() : GamesConfig.Common.defaults();
        DeepMineSettings s = of(enabled, stakes, dailyLimit, rtp, reels, pays, common.maxPayout());
        s.bank().report(n, DeepMine.NAME, "a row's most", reels, pays);
        for (int stake : s.offered()) {
            StakeOdds o = s.bank().odds(stake);
            if (o.aboveTarget()) {
                n.info(DeepMine.ID + " stake " + stake + ": " + RtpLimits.tenthPercent(o.rtp()) + "% (target "
                        + SlotsTables.fmt(rtp) + " not reachable with whole weights)");
            }
        }
        return s;
    }

    /**
     * The line for {@code perLine} dollars on a row in the Game Parlor: a spin is three rows, and the whole spin may
     * pay what the Parlor's rules allow that many dollars. The engine takes no stake when it can't land in the band.
     */
    public SlotsEngine moneyLine(int perLine, Parlor.Rules rules) {
        return line(engineReels(), enginePays(), perLine, rules.capFor(DeepMineEngine.total(perLine)), rtp);
    }

    @Override
    public SlotTheme theme() {
        return SlotTheme.DEEP;
    }

    @Override
    public Map<String, Integer> engineReels() {
        return SlotTheme.DEEP.engineReels(reels);
    }

    @Override
    public Map<String, Integer> enginePays() {
        return SlotTheme.DEEP.enginePays(pays);
    }

    /** The smallest offered stake's line (each stake has its own: {@link #engine(int)}). */
    @Override
    public SlotsEngine engine() {
        return bank.first();
    }

    /** The line for {@code perLine} tokens on a row, or {@code null} when that stake is not offered. */
    @Override
    public SlotsEngine engine(int perLine) {
        return bank.engine(perLine);
    }

    /** What may go on each row, after the solve dropped any that could not land in the band. */
    @Override
    public List<Integer> offered() {
        return bank.stakes();
    }

    /** One whole spin of the three rows with {@code perLine} tokens on each; {@code null} when it is not offered. */
    public DeepMineEngine spin(int perLine) {
        SlotsEngine line = bank.engine(perLine);
        return line == null ? null : new DeepMineEngine(line, perLine);
    }
}
