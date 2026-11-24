package com.dierks.homecraft.games.chance.poker;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.RtpLimits.Ratio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 0.57.0: Video Poker's settings, {@code games.video_poker}, and (because every stake's return is counted while they
 * are read) the engine object the screen, {@code /hcm arcade odds} and the website all read.
 *
 * <p><b>The pay table</b> ({@code pays}) is how many times the tokens put in each hand pays: Jacks or Better 1 (your
 * tokens back, never called a win), Two Pair 2, Three of a Kind 3, Straight 4, Flush 5, Full House 6, Four of a Kind
 * 25, Straight Flush 50, Royal Flush 100. That is the well-known "6/5" Jacks or Better table (a Full House 6, a Flush
 * 5) with a small Royal Flush, and its best play gives back exactly 93.476... of every 100 tokens; the casino
 * machines' 800 for a Royal would take it past the 95 this server allows.
 *
 * <p><b>Every stake is counted exactly</b> ({@link PokerMath}): each hand's prize is its multiple of the stake in
 * whole tokens, held under the payout cap ({@code games.max_payout}, or in the Game Parlor its biggest payout), and
 * the best play's return over every deal, hold and draw is worked out for exactly those prizes. A stake whose return
 * is outside 85-95 of every 100 is left out with one WARN naming it; with none left the game is closed (one WARN).
 * The shipped table's numbers are known ({@link #KNOWN}, pinned by a test that counts them again), so the shipped
 * settings cost nothing to read; any other table is counted when config is read (a second or two, once).
 *
 * @param enabled    the game's own switch (it also needs {@code games.enabled})
 * @param stakes     the configured choices of tokens to put in, smallest first
 * @param dailyLimit hands a player gets a day
 * @param pays       each hand's multiple of the stake, by its key ({@link PokerHand#key()}), best hand first
 * @param odds       the stakes that can be played, smallest first, each with its prizes and exact return
 */
public record VideoPokerSettings(boolean enabled, List<Integer> stakes, int dailyLimit, Map<String, Integer> pays,
                                 List<Odds> odds) {

    /** The game id. */
    public static final String ID = "video_poker";

    /** The pay table's hands, best first, as config lists them. */
    public static final List<PokerHand> ROWS = List.of(PokerHand.ROYAL_FLUSH, PokerHand.STRAIGHT_FLUSH,
            PokerHand.FOUR_OF_A_KIND, PokerHand.FULL_HOUSE, PokerHand.FLUSH, PokerHand.STRAIGHT,
            PokerHand.THREE_OF_A_KIND, PokerHand.TWO_PAIR, PokerHand.JACKS_OR_BETTER);

    /** The leaves under {@code games.video_poker}, in config order. */
    public static final List<String> KEYS = keys();

    /** The most any one hand may pay, as a multiple of the stake. */
    public static final int PAY_MAX = 10_000;

    /**
     * Returns already counted, by prizes over stake in lowest terms ({@code "0,1,2,3,4,5,6,25,50,100/1"}): the
     * shipped table, and the same with the Royal Flush held to 50 times (5 tokens under the shipped payout cap, or
     * $10 under the Parlor's). {@code VideoPokerMathTest} counts each again and must get exactly these.
     */
    static final Map<String, Ratio> KNOWN = Map.of(
            "0,1,2,3,4,5,6,25,50,100/1", new Ratio(11_763_161_728L, 12_584_110_175L),
            "0,1,2,3,4,5,6,25,50,50/1", new Ratio(310_261_386_467L, 332_220_508_620L));

    /** Returns counted this run, by the same key (each table and stake is counted once). */
    private static final Map<String, Ratio> COUNTED = new ConcurrentHashMap<>();

    public VideoPokerSettings {
        stakes = List.copyOf(stakes);
        pays = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pays));
        odds = List.copyOf(odds);
    }

    /**
     * One stake that can be played.
     *
     * @param stake  tokens (or dollars) put in
     * @param prizes what each hand pays for it, by {@link PokerHand#ordinal()} (NOTHING's is 0), held under the cap
     * @param exact  the best play's return, exactly
     */
    public record Odds(int stake, List<Integer> prizes, Ratio exact) {

        public Odds {
            prizes = List.copyOf(prizes);
        }

        /** The return as a fraction (0.93476...). */
        public double rtp() {
            return exact.value();
        }

        /** What {@code hand} pays at this stake. */
        public int prize(PokerHand hand) {
            return prizes.get(hand.ordinal());
        }

        /** The prizes as an array, for a deal. */
        public int[] prizeArray() {
            int[] out = new int[prizes.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = prizes.get(i);
            }
            return out;
        }

        /** Whether a hand's prize was held down by the payout cap (it pays less than its multiple). */
        public boolean capped(PokerHand hand, int multiple) {
            return (long) multiple * stake > prize(hand);
        }
    }

    private static List<String> keys() {
        List<String> k = new ArrayList<>(List.of("enabled", "stakes", "daily_limit"));
        for (PokerHand h : ROWS) {
            k.add("pays." + h.key());
        }
        return List.copyOf(k);
    }

    /** The shipped pay table: 6/5 Jacks or Better with a Royal Flush of 100. */
    public static Map<String, Integer> shippedPays() {
        Map<String, Integer> p = new LinkedHashMap<>();
        p.put(PokerHand.ROYAL_FLUSH.key(), 100);
        p.put(PokerHand.STRAIGHT_FLUSH.key(), 50);
        p.put(PokerHand.FOUR_OF_A_KIND.key(), 25);
        p.put(PokerHand.FULL_HOUSE.key(), 6);
        p.put(PokerHand.FLUSH.key(), 5);
        p.put(PokerHand.STRAIGHT.key(), 4);
        p.put(PokerHand.THREE_OF_A_KIND.key(), 3);
        p.put(PokerHand.TWO_PAIR.key(), 2);
        p.put(PokerHand.JACKS_OR_BETTER.key(), 1);
        return p;
    }

    /** The shipped settings: 1, 2 or 5 tokens a hand (like a real machine's one to five coins), 50 hands a day. */
    public static VideoPokerSettings defaults() {
        List<Integer> stakes = List.of(1, 2, 5);
        Map<String, Integer> pays = shippedPays();
        int cap = GamesConfig.Common.defaults().maxPayoutFor(stakes);
        return new VideoPokerSettings(true, stakes, 50, pays, solve("games." + ID, stakes, pays, cap, w -> { }));
    }

    /** Read {@code games.video_poker} over {@code d}; never throws. */
    public static VideoPokerSettings parse(GamesConfig.Node n, VideoPokerSettings d) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = List.copyOf(new TreeSet<>(n.intList("stakes", d.stakes(), 1, 1000)));
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        Map<String, Integer> read = n.wholeMap("pays", d.pays(), 0, PAY_MAX);
        Map<String, Integer> pays = new LinkedHashMap<>();
        for (PokerHand h : ROWS) {
            pays.put(h.key(), read.getOrDefault(h.key(), d.pays().getOrDefault(h.key(), 0)));
        }
        GamesConfig.Common common = n.common() == null ? GamesConfig.Common.defaults() : n.common();
        List<Odds> odds = solve(n.path(), stakes, pays, common.maxPayoutFor(stakes), n::warn);
        return new VideoPokerSettings(enabled, stakes, dailyLimit, pays, odds);
    }

    /**
     * Count every stake (R1.1): one WARN per stake left out, one in all when none is left.
     *
     * @param cap the payout cap (never below the largest stake)
     */
    static List<Odds> solve(String path, List<Integer> stakes, Map<String, Integer> pays, int cap,
                            Consumer<String> warn) {
        List<Odds> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int stake : stakes) {
            Odds o = odds(pays, stake, cap);
            if (RtpLimits.inBand(o.exact())) {
                out.add(o);
            } else {
                dropped.add(stake + ": " + RtpLimits.tenthPercent(o.rtp()) + "%");
            }
        }
        if (out.isEmpty() && !stakes.isEmpty()) {
            warn.accept(path + ".pays can't give back 85-95 of every 100 tokens at any stake ("
                    + String.join("; ", dropped) + " with the best play) - Video Poker is closed until it is fixed");
            return out;
        }
        for (String d : dropped) {
            warn.accept(path + ".stakes " + d.substring(0, d.indexOf(':')) + " is off: the pay table gives back "
                    + d.substring(d.indexOf(':') + 2) + " there with the best play (85-95 needed)");
        }
        return out;
    }

    /** A stake's prizes (each multiple of it, held to {@code cap}) and their exact best-play return. */
    public static Odds odds(Map<String, Integer> pays, int stake, int cap) {
        int[] prizes = new int[PokerHand.COUNT];
        for (PokerHand h : ROWS) {
            long p = (long) pays.getOrDefault(h.key(), 0) * stake;
            prizes[h.ordinal()] = (int) Math.min(p, Math.max(cap, stake));
        }
        List<Integer> list = new ArrayList<>(prizes.length);
        for (int p : prizes) {
            list.add(p);
        }
        return new Odds(stake, list, exactReturn(prizes, stake));
    }

    /** The best play's exact return for these prizes: known, counted this run, or counted now. */
    static Ratio exactReturn(int[] prizes, int stake) {
        String key = key(prizes, stake);
        Ratio r = KNOWN.get(key);
        if (r != null) {
            return r;
        }
        return COUNTED.computeIfAbsent(key, k -> PokerMath.exactReturn(prizes, stake));
    }

    /** Prizes over stake in lowest terms: "0,1,2,...,100/1" (a stake of 5 paying five times as much is the same). */
    static String key(int[] prizes, int stake) {
        int[] p = PokerMath.clean(prizes);
        long g = stake;
        for (int x : p) {
            g = gcd(g, x);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            b.append(i == 0 ? "" : ",").append(p[i] / g);
        }
        return b.append('/').append(stake / g).toString();
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    // ---- reading it --------------------------------------------------------------------------------------------

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

    /** The lowest return of any stake offered (the game's one number), or {@code NaN} with none. */
    public double lowestRtp() {
        double low = Double.NaN;
        for (Odds o : odds) {
            if (Double.isNaN(low) || o.rtp() < low) {
                low = o.rtp();
            }
        }
        return low;
    }

    /** A hand's multiple in the pay table (0 for NOTHING). */
    public int multiple(PokerHand hand) {
        return hand == PokerHand.NOTHING ? 0 : pays.getOrDefault(hand.key(), 0);
    }
}
