package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A themed slot machine's settings: {@code games.nether_slots}, {@code games.motherlode} and
 * {@code games.farm_slots}. The same shape as {@link OreSlotsSettings} and solved by the same {@link SlotsEngine},
 * with the reels and lines written under the theme's own names ({@code reels.quartz}, {@code pays.ghast_tear}).
 *
 * <p><b>A pot machine</b> ({@link #pot}: The Motherlode, Farm Slots) is played for money only, at one set price:
 * {@code stakes} holds that one price in dollars, and {@code feed_percent} of every spin's price goes into the
 * machine's pot. Its lines are solved for {@code rtp - feed_percent}, so the lines and the pot together give back
 * {@code rtp}; three Wilds is the pot's line, and what that line pays ({@code pays.wild} times the price) is what
 * the pot starts at. So {@code pays.wild} is never under another line's multiple (three Wilds would then pay as that
 * other line), and {@code feed_percent} never takes the lines under the 85 they must give back on their own, nor
 * the lines and the pot together over 95.
 *
 * @param theme       how it looks and what it calls things (never read from config)
 * @param pot         a pot machine: money only, one set price (never read from config)
 * @param enabled     the machine's own switch
 * @param stakes      the tokens a spin may put in, smallest first; a pot machine: its one price, in dollars
 * @param dailyLimit  spins a player gets a day
 * @param rtp         the target return, a percent (85-95): the lines and, on a pot machine, the pot together
 * @param feedPercent a pot machine: the share of every spin's price that goes into the pot (else 0)
 * @param reels       how often each symbol lands, by the theme's keys (the blank one is solved)
 * @param pays        what each line pays, as a multiple of what was put in, by the theme's keys
 * @param engine      the solved odds and the draw, from exactly these values and the payout cap
 */
public record SlotSettings(SlotTheme theme, boolean pot, boolean enabled, List<Integer> stakes, int dailyLimit,
                           double rtp, int feedPercent, Map<String, Integer> reels, Map<String, Integer> pays,
                           SlotsEngine engine) implements SlotTable {

    /**
     * The most of a spin's price that may go into a pot, a percent. The band is ten points wide and the lines alone
     * must stay in it, landing at 85 or the smallest step over it; one point is kept back so the lines and the pot's
     * share together can never pass 95.
     */
    public static final int MAX_FEED = 9;

    public SlotSettings {
        stakes = List.copyOf(stakes);
        reels = Collections.unmodifiableMap(new LinkedHashMap<>(reels));
        pays = Collections.unmodifiableMap(new LinkedHashMap<>(pays));
    }

    /**
     * Nether Slots as shipped: it pays rarely and big. Two the same pays nothing, and three the same pays from 6
     * to 125 times what was put in (Ore Slots: 4 to 50), about one spin in 12 (Ore Slots: one in 3). The top line is
     * 2 in → 250, the shipped {@code games.max_payout}.
     */
    public static SlotSettings nether() {
        return of(SlotTheme.NETHER, false, true, List.of(1, 2), 50, 90, 0,
                SlotsTables.map("quartz", 12, "magma_cream", 9, "blaze_rod", 6, "ghast_tear", 3, "nether_star", 2,
                        "wild", 2),
                SlotsTables.map("two", 0, "quartz", 6, "magma_cream", 10, "blaze_rod", 20, "ghast_tear", 50,
                        "nether_star", 100, "wild", 125),
                GamesConfig.Common.defaults().maxPayoutFor(List.of(1, 2)));
    }

    /**
     * The Motherlode as shipped: $5 a spin, 5 of every 100 into the pot, the pot starting at $50 (the Wild line's
     * 10 times the price) and taken about once in 600 spins.
     */
    public static SlotSettings motherlode() {
        return of(SlotTheme.MOTHERLODE, true, true, List.of(5), 50, 90, 5,
                SlotsTables.map("redstone", 10, "lapis", 8, "amethyst", 6, "emerald", 4, "netherite", 2, "wild", 5),
                SlotsTables.map("two", 1, "redstone", 2, "lapis", 3, "amethyst", 5, "emerald", 8, "netherite", 10,
                        "wild", 10),
                GamesConfig.Common.defaults().maxPayoutFor(List.of(5)));
    }

    /**
     * Farm Slots as shipped: $1 a spin, 5 of every 100 into its own small pot, the pot starting at $10 and taken
     * about once in 146 spins.
     */
    public static SlotSettings farm() {
        return of(SlotTheme.FARM, true, true, List.of(1), 100, 90, 5,
                SlotsTables.map("wheat", 10, "carrot", 8, "potato", 6, "beetroot", 4, "golden_carrot", 2, "wild", 11),
                SlotsTables.map("two", 1, "wheat", 2, "carrot", 3, "potato", 4, "beetroot", 5, "golden_carrot", 8,
                        "wild", 10),
                GamesConfig.Common.defaults().maxPayoutFor(List.of(1)));
    }

    /** The leaves a machine ships under its section, in config order. */
    public static List<String> keys(SlotTheme theme, boolean pot) {
        List<String> out = new ArrayList<>(List.of("enabled", "stakes", "daily_limit", "rtp"));
        if (pot) {
            out.add("feed_percent");
        }
        for (String k : theme.reelKeys()) {
            out.add("reels." + k);
        }
        for (String k : theme.payKeys()) {
            out.add("pays." + k);
        }
        return List.copyOf(out);
    }

    /** Settings with their engine solved against the payout cap {@code cap}. */
    public static SlotSettings of(SlotTheme theme, boolean pot, boolean enabled, List<Integer> stakes,
                                  int dailyLimit, double rtp, int feedPercent, Map<String, Integer> reels,
                                  Map<String, Integer> pays, int cap) {
        int feed = pot ? feedPercent : 0;
        return new SlotSettings(theme, pot, enabled, stakes, dailyLimit, rtp, feed, reels, pays,
                SlotsEngine.solve(theme.engineReels(reels), theme.enginePays(pays), stakes, cap, rtp - feed));
    }

    /**
     * Read the machine's section over {@code d} (which says which theme it is and whether it has a pot), then solve;
     * never throws.
     *
     * @param id   the machine's id ("nether_slots"), for the solve's INFO line
     * @param name its name ("Nether Slots"), for a dropped stake's WARN
     */
    public static SlotSettings parse(GamesConfig.Node n, SlotSettings d, String id, String name) {
        boolean enabled = n.enabled(d.enabled());
        List<Integer> stakes = SlotsTables.stakes(n, d.stakes(), OreSlotsSettings.MAX_STAKES);
        if (d.pot() && stakes.size() > 1) {
            List<Integer> one = List.of(stakes.get(0));
            n.warn(n.key("stakes") + " " + stakes + " has more than one price, and " + name
                    + " has one set price - using " + one);
            stakes = one;
        }
        int dailyLimit = n.whole("daily_limit", d.dailyLimit(), 1, 10_000);
        double rtp = n.rtp("rtp", d.rtp());
        int feed = 0;
        if (d.pot()) {
            feed = n.whole("feed_percent", d.feedPercent(), 0, MAX_FEED);
            int room = (int) Math.floor(rtp - RtpLimits.MIN_PERCENT + 1e-9);
            if (feed > room) {
                n.warn(n.key("feed_percent") + " " + feed + " leaves the lines under the 85 of every 100 they must"
                        + " give back on their own (rtp " + SlotsTables.fmt(rtp) + ") - using " + room);
                feed = room;
            }
        }
        Map<String, Integer> reels = n.wholeMap("reels", d.reels(), 0, OreSlotsSettings.MAX_WEIGHT);
        Map<String, Integer> pays = n.wholeMap("pays", d.pays(), 0, OreSlotsSettings.MAX_MULTIPLE);
        if (d.pot()) {
            pays = wildOnTop(n, pays);
        }
        GamesConfig.Common common = n.common() != null ? n.common() : GamesConfig.Common.defaults();
        SlotSettings s = of(d.theme(), d.pot(), enabled, stakes, dailyLimit, rtp, feed, reels, pays,
                common.maxPayoutFor(stakes));
        if (overTheBand(s)) {
            // lines that could only land over their target, with a share that then passes 95: give the pot less
            int asked = feed;
            while (feed > 0 && overTheBand(s)) {
                feed--;
                s = of(d.theme(), d.pot(), enabled, stakes, dailyLimit, rtp, feed, reels, pays,
                        common.maxPayoutFor(stakes));
            }
            n.warn(n.key("feed_percent") + " " + asked + " would give back more than 95 of every 100 with these"
                    + " lines - using " + feed);
        }
        SlotsTables.report(n, id, name, d.pot() ? "dollars" : "tokens", s.engine(), s.linesRtp(), s.reels(),
                s.pays(), d.pot());
        return s;
    }

    /**
     * A pot machine's {@code pays} with the Wild line no lower than any other: three Wilds make every line, and the
     * best one pays, so a Wild line under another would never be the one that pays (one WARN, and it is raised).
     */
    private static Map<String, Integer> wildOnTop(GamesConfig.Node n, Map<String, Integer> pays) {
        int top = 0;
        for (Map.Entry<String, Integer> e : pays.entrySet()) {
            if (!e.getKey().equals(Line.THREE_WILDS.key())) {
                top = Math.max(top, e.getValue());
            }
        }
        int wild = pays.getOrDefault(Line.THREE_WILDS.key(), 0);
        if (wild >= top) {
            return pays;
        }
        n.warn(n.child("pays").key(Line.THREE_WILDS.key()) + " " + wild + " is under another line's " + top
                + ": three Wilds takes the pot, so it is never the smaller line - using " + top);
        Map<String, Integer> out = new LinkedHashMap<>(pays);
        out.put(Line.THREE_WILDS.key(), top);
        return Collections.unmodifiableMap(out);
    }

    /** Whether any stake's lines and pot's share together give back more than the band allows. */
    private static boolean overTheBand(SlotSettings s) {
        for (SlotsEngine.StakeOdds o : s.engine().odds().values()) {
            if (s.givesBack(o) > RtpLimits.MAX) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, Integer> engineReels() {
        return theme.engineReels(reels);
    }

    @Override
    public Map<String, Integer> enginePays() {
        return theme.enginePays(pays);
    }

    /** A pot machine's one set price, in dollars (any machine: its smallest stake; 0 with none). */
    public int price() {
        return stakes.isEmpty() ? 0 : stakes.get(0);
    }
}
