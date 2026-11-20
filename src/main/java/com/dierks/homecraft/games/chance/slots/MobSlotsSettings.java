package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob Slots' settings: {@code games.mob_slots}. The Ore Slots shape with the mobs' own names, and one key more:
 * {@code free_spins}, how many free spins three Creepers on the line give (0 makes it a plain machine).
 *
 * <p><b>The return is the whole round's.</b> A round is a spin and, about once in a couple of hundred, the free spins
 * after it, so it gives back {@code L × (1 + p × N)}: {@code L} one spin's exact return, {@code p} the exact chance
 * of three Creepers, {@code N} the free spins. The one-line {@link SlotsEngine} solves {@code L} for a target it is
 * given, and {@code p} depends on what it solved, so {@link #solve} searches for the engine target that puts the
 * WHOLE round at {@code rtp}, then works the round's return out exactly from that engine's own whole weights and
 * payouts. That exact value is what players read and what must be inside the 85-95 band; a stake whose round can't
 * land there is not offered.
 *
 * <p><b>The cap.</b> A round may pay {@code games.max_payout} (never less than its own stake), and it is at most
 * {@code N + 1} spins, so each stake's engine is solved with a cap of that divided by {@code N + 1}
 * ({@link FreeSpinsEngine#spinCap}). Each stake has an engine of its own for this ({@link #bank}).
 *
 * <p>One limit of searching through the one-line engine: the engine itself never solves one spin under 85, so a
 * round can't be brought lower than {@code 85 × (1 + p × N)}. A target under that lands just above it (still inside
 * the band, one INFO line), and what the screen shows is what it really reaches.
 *
 * @param enabled    the machine's own switch
 * @param stakes     the tokens a spin may put in, smallest first, as configured
 * @param dailyLimit rounds a player gets a day (the free spins are part of their round)
 * @param rtp        the target return of a whole round, a percent (85-95)
 * @param freeSpins  the free spins three Creepers give (0-10)
 * @param reels      how often each symbol lands, as whole-number weights (Cobweb is solved)
 * @param pays       what each line pays, as a multiple of what was put in
 * @param bank       each offered stake's solved spin, from exactly these values and the payout cap
 */
public record MobSlotsSettings(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp, int freeSpins,
                               Map<String, Integer> reels, Map<String, Integer> pays, SlotBank bank)
        implements SlotTable {

    /** The leaves under {@code games.mob_slots}, in config order: {@code free_spins} comes after {@code rtp}. */
    public static final List<String> KEYS = keys();

    /** The engine targets tried are whole thousandths of a percent: far finer than one step of the blank symbol. */
    private static final int STEPS_PER_PERCENT = 1000;
    /** Solved stakes, kept: the same numbers always solve the same, and a search is a dozen solves. */
    private static final Map<String, Solved> SOLVED = new ConcurrentHashMap<>();
    private static final int KEPT = 512;

    public MobSlotsSettings {
        stakes = List.copyOf(stakes);
        reels = Collections.unmodifiableMap(new LinkedHashMap<>(reels));
        pays = Collections.unmodifiableMap(new LinkedHashMap<>(pays));
    }

    private static List<String> keys() {
        List<String> out = new ArrayList<>(SlotSettings.keys(SlotTheme.MOB, false));
        out.add(out.indexOf("rtp") + 1, "free_spins");
        return List.copyOf(out);
    }

    /**
     * Mob Slots as shipped: 1 or 2 tokens a spin, two the same gives the stake back, three the same pays 2 to 20
     * times it, and three Creepers (about one spin in 182) pay 20 times and give 5 free spins. With the shipped
     * {@code games.max_payout} of 250 one spin may pay 41, so the top line at 2 in (40) is never cut and a whole
     * round is at most 240.
     */
    public static MobSlotsSettings defaults() {
        return of(true, List.of(1, 2), 50, 90, 5,
                SlotsTables.map("rotten_flesh", 10, "bone", 8, "string", 6, "ender_pearl", 4, "blaze_rod", 2,
                        "creeper", 10),
                SlotsTables.map("two", 1, "rotten_flesh", 2, "bone", 3, "string", 4, "ender_pearl", 6,
                        "blaze_rod", 10, "creeper", 20),
                GamesConfig.Common.defaults().maxPayout());
    }

    /** What a whole round for {@code stake} tokens may pay: the cap, never under the stake itself. */
    public static int roundCap(int maxPayout, int stake) {
        return Math.max(maxPayout, stake);
    }

    /**
     * What the search for one stake found.
     *
     * @param engine      the one-line engine: it takes the stake when the stake is offered, and no stake when not
     * @param givesBack   the whole round's exact return ({@code null} when the stake is not offered)
     * @param aboveTarget the round could only land above the target (still inside the band)
     * @param nearest     for a stake that is not offered: the nearest returns it could reach, for the WARN
     * @param spinCap     the most one spin may pay
     */
    public record Solved(SlotsEngine engine, Frac givesBack, boolean aboveTarget, String nearest, int spinCap) {

        /** Whether the stake is offered: its whole round is inside the band. */
        public boolean offered() {
            return givesBack != null;
        }
    }

    /**
     * Find the one-line engine whose whole round (the spin and its free spins) gives back the most that is at or
     * below {@code rtp}; if nothing at or below it is in reach, the least above it that is still inside the band;
     * else the stake is not offered.
     *
     * <p>The search is over the engine's own target: a higher target never solves a heavier blank symbol, so the
     * round's return never falls as the target rises, and a halving search finds the highest target whose round is
     * at or below {@code rtp}. Every comparison is exact ({@link Frac}), never a double.
     *
     * @param engineReels weights by {@link Symbol#key()}
     * @param enginePays  multiples by {@link Line#key()}
     * @param roundCap    the most the whole round may pay
     */
    public static Solved solve(Map<String, Integer> engineReels, Map<String, Integer> enginePays, int stake,
                               int roundCap, double rtp, int freeSpins) {
        int n = Math.max(0, Math.min(FreeSpinsEngine.MAX_FREE_SPINS, freeSpins));
        double target = Double.isFinite(rtp)
                ? Math.max(RtpLimits.MIN_PERCENT, Math.min(RtpLimits.MAX_PERCENT, rtp)) : RtpLimits.DEFAULT_PERCENT;
        String key = stake + ":" + roundCap + ":" + target + ":" + n + ":" + engineReels + ":" + enginePays;
        Solved kept = SOLVED.get(key);
        if (kept != null) {
            return kept;
        }
        Solved solved = search(engineReels, enginePays, stake, roundCap, target, n);
        if (SOLVED.size() >= KEPT) {
            SOLVED.clear();
        }
        SOLVED.put(key, solved);
        return solved;
    }

    private static Solved search(Map<String, Integer> reels, Map<String, Integer> pays, int stake, int roundCap,
                                 double target, int n) {
        int cap = FreeSpinsEngine.spinCap(roundCap, n);
        SlotsEngine none = SlotsEngine.solve(reels, pays, List.of(), Math.max(1, cap), target);
        if (stake <= 0 || cap < 1) {
            return new Solved(none, null, false, "nothing", cap); // not even one token a spin fits under the cap
        }
        Frac want = Frac.percent(target);
        SlotsEngine top = at(reels, pays, stake, cap, target);
        if (top.odds(stake) == null) {
            // one spin can't land in the band at this target, so it can't at any lower one either
            String nearest = top.dropped().isEmpty() ? "nothing" : top.dropped().get(0).nearest();
            return new Solved(none, null, false, nearest, cap);
        }
        if (round(top, stake, n).compareTo(want) <= 0) {
            return found(top, stake, n, want, cap); // no free spins to count, or none that lift it past the target
        }
        int lo = (int) Math.round(RtpLimits.MIN_PERCENT * STEPS_PER_PERCENT);
        int hi = (int) Math.floor(target * STEPS_PER_PERCENT) + 1; // never tried itself: every target tried is <= rtp
        SlotsEngine low = at(reels, pays, stake, cap, RtpLimits.MIN_PERCENT);
        if (low.odds(stake) == null || round(low, stake, n).compareTo(want) > 0) {
            // even the lowest spin the engine solves gives a round over the target: the least there is
            SlotsEngine least = low.odds(stake) != null ? low : top;
            Frac back = round(least, stake, n);
            if (back.compareTo(Frac.MAX) > 0) {
                return new Solved(none, null, false, SlotBank.percent(back), cap);
            }
            return new Solved(least, back, true, null, cap);
        }
        SlotsEngine best = low; // at lo: at or below the target
        while (hi - lo > 1) {
            int mid = lo + (hi - lo) / 2;
            SlotsEngine e = at(reels, pays, stake, cap, mid / (double) STEPS_PER_PERCENT);
            if (e.odds(stake) != null && round(e, stake, n).compareTo(want) <= 0) {
                lo = mid;
                best = e;
            } else {
                hi = mid;
            }
        }
        return found(best, stake, n, want, cap);
    }

    private static SlotsEngine at(Map<String, Integer> reels, Map<String, Integer> pays, int stake, int cap,
                                  double target) {
        return SlotsEngine.solve(reels, pays, List.of(stake), cap, target);
    }

    private static Frac round(SlotsEngine engine, int stake, int n) {
        return SlotOdds.withFreeSpins(engine, stake, n);
    }

    /** An engine whose round is at or below the target: offered when that is inside the band. */
    private static Solved found(SlotsEngine engine, int stake, int n, Frac want, int cap) {
        Frac back = round(engine, stake, n);
        if (!back.inBand()) {
            SlotsEngine none = SlotsEngine.solve(Map.of(), Map.of(), List.of(), Math.max(1, cap), 90);
            return new Solved(none, null, false, SlotBank.percent(back), cap);
        }
        return new Solved(engine, back, back.compareTo(want) > 0, null, cap);
    }

    /** Settings with every stake's spin solved against {@code games.max_payout}. */
    public static MobSlotsSettings of(boolean enabled, List<Integer> stakes, int dailyLimit, double rtp,
                                      int freeSpins, Map<String, Integer> reels, Map<String, Integer> pays,
                                      int maxPayout) {
        int n = Math.max(0, Math.min(FreeSpinsEngine.MAX_FREE_SPINS, freeSpins));
        Map<String, Integer> r = SlotTheme.MOB.engineReels(reels);
        Map<String, Integer> p = SlotTheme.MOB.enginePays(pays);
        SlotBank.Builder bank = new SlotBank.Builder();
        for (int stake : stakes) {
            Solved s = solve(r, p, stake, roundCap(maxPayout, stake), rtp, n);
            if (s.offered()) {
                bank.add(stake, s.engine());
            } else {
                bank.drop(stake, s.nearest(), s.spinCap(), s.engine());
            }
        }
        return new MobSlotsSettings(enabled, stakes, dailyLimit, rtp, n, reels, pays,
                bank.build(SlotsEngine.solve(r, p, List.of(), 1, rtp)));
    }

    /** Read {@code games.mob_slots} over {@code d}, then solve; never throws. */
    public static MobSlotsSettings parse(GamesConfig.Node n, MobSlotsSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = SlotsTables.stakes(n, d.stakes(), OreSlotsSettings.MAX_STAKES);
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        int freeSpins = n.whole("free_spins", d.freeSpins(), 0, FreeSpinsEngine.MAX_FREE_SPINS);
        Map<String, Integer> reels = n.wholeMap("reels", d.reels(), 0, OreSlotsSettings.MAX_WEIGHT);
        Map<String, Integer> pays = n.wholeMap("pays", d.pays(), 0, OreSlotsSettings.MAX_MULTIPLE);
        if (freeSpins > 0) {
            pays = creepersOnTop(n, pays);
        }
        GamesConfig.Common common = n.common() != null ? n.common() : GamesConfig.Common.defaults();
        MobSlotsSettings s = of(enabled, stakes, dailyLimit, rtp, freeSpins, reels, pays, common.maxPayout());
        s.bank().report(n, MobSlots.NAME, "a spin's most", reels, pays);
        Frac want = Frac.percent(rtp);
        for (int stake : s.offered()) {
            Frac back = s.round(stake).givesBack();
            if (back.compareTo(want) > 0) {
                n.info(MobSlots.ID + " stake " + stake + ": " + RtpLimits.tenthPercent(back.value()) + "% (target "
                        + SlotsTables.fmt(rtp) + " not reachable: one spin is never solved under 85, and the free"
                        + " spins come on top)");
            }
        }
        return s;
    }

    /**
     * {@code pays} with the Creeper line no lower than any other: three Creepers make every line, and the best one
     * pays, so a Creeper line under another would never be the one on the paytable that says "free spins" (one WARN,
     * and it is raised).
     */
    private static Map<String, Integer> creepersOnTop(GamesConfig.Node n, Map<String, Integer> pays) {
        String creeper = SlotTheme.MOB.key(Symbol.WILD);
        int top = 0;
        for (Map.Entry<String, Integer> e : pays.entrySet()) {
            if (!e.getKey().equals(creeper)) {
                top = Math.max(top, e.getValue());
            }
        }
        int own = pays.getOrDefault(creeper, 0);
        if (own >= top) {
            return pays;
        }
        n.warn(n.child("pays").key(creeper) + " " + own + " is under another line's " + top
                + ": three Creepers give the free spins, so theirs is never the smaller line - using " + top);
        Map<String, Integer> out = new LinkedHashMap<>(pays);
        out.put(creeper, top);
        return Collections.unmodifiableMap(out);
    }

    /**
     * The spin for {@code dollars} in the Game Parlor, found exactly as a token stake's is, under what the Parlor's
     * rules let that many dollars pay; not {@link Solved#offered} when its round can't land in the band.
     */
    public Solved moneySpin(int dollars, Parlor.Rules rules) {
        return solve(engineReels(), enginePays(), dollars, rules.capFor(dollars), rtp, freeSpins);
    }

    @Override
    public SlotTheme theme() {
        return SlotTheme.MOB;
    }

    @Override
    public Map<String, Integer> engineReels() {
        return SlotTheme.MOB.engineReels(reels);
    }

    @Override
    public Map<String, Integer> enginePays() {
        return SlotTheme.MOB.enginePays(pays);
    }

    /** The smallest offered stake's spin (each stake has its own: {@link #engine(int)}). */
    @Override
    public SlotsEngine engine() {
        return bank.first();
    }

    /** The spin for {@code stake} tokens, or {@code null} when that stake is not offered. */
    @Override
    public SlotsEngine engine(int stake) {
        return bank.engine(stake);
    }

    @Override
    public List<Integer> offered() {
        return bank.stakes();
    }

    /** One whole round for {@code stake} tokens; {@code null} when that stake is not offered. */
    public FreeSpinsEngine round(int stake) {
        SlotsEngine spin = bank.engine(stake);
        return spin == null ? null : new FreeSpinsEngine(spin, stake, freeSpins);
    }

    /**
     * What a stake's whole round gives back, a fraction: one spin's exact return times {@code 1 + p × N}, from the
     * spin's own odds (the Creeper's weight is this table's, on the engine's scale). The number players read is
     * THIS, floored: never one spin's return on its own.
     */
    @Override
    public double givesBack(StakeOdds odds) {
        BigInteger ways = BigInteger.valueOf(odds.total()).pow(3);
        long creeper = (long) Math.max(0, reels.getOrDefault(SlotTheme.MOB.key(Symbol.WILD), 0)) * SlotsEngine.SCALE;
        BigInteger more = BigInteger.valueOf(creeper).pow(3).multiply(BigInteger.valueOf(freeSpins));
        return new Frac(odds.rtpNum(), odds.rtpDen()).times(new Frac(ways.add(more), ways)).value();
    }
}
