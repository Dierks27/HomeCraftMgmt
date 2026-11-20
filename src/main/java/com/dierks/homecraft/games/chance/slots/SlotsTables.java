package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What every slot machine's settings do the same way when they are read: the stakes the screen has room
 * for, and the solve's WARN and INFO lines (spec §5.1). Ore Slots' lines read exactly as they always have.
 */
final class SlotsTables {

    private SlotsTables() {
    }

    /** {@code stakes} read over {@code d}: distinct, smallest first, and no more than the screen shows. */
    static List<Integer> stakes(GamesConfig.Node n, List<Integer> d, int most) {
        List<Integer> stakes = List.copyOf(new TreeSet<>(n.intList("stakes", d, 1, 1000)));
        if (stakes.size() > most) {
            List<Integer> kept = List.copyOf(stakes.subList(0, most));
            n.warn(n.key("stakes") + " " + stakes + " has more than " + most
                    + " stakes, which is all the screen shows - using " + kept);
            stakes = kept;
        }
        return stakes;
    }

    /**
     * The solve's WARN and INFO lines: a dropped stake, no stake left (the machine closes), a stake that could only
     * land just above its target.
     *
     * @param id     the machine's id, for the INFO line ("ore_slots")
     * @param name   its name, for the WARN ("Ore Slots doesn't take 5 tokens")
     * @param unit   "tokens" or "dollars"
     * @param target the target the lines were solved for, a percent
     * @param quiet  no INFO for landing just above the target (a pot machine's lines aim at the band's floor on
     *               purpose, so they always do)
     */
    static void report(GamesConfig.Node n, String id, String name, String unit, SlotsEngine e, double target,
                       Map<String, Integer> reels, Map<String, Integer> pays, boolean quiet) {
        String band = "85-95 of every 100 " + unit;
        if (!e.playable()) {
            n.invalid(n.path() + " can't give back " + band + " at any stake (" + why(e, reels, pays) + ")");
            return;
        }
        for (SlotsEngine.Dropped d : e.dropped()) {
            n.warn(n.key("stakes") + " " + d.stake() + " can't give back " + band + " (nearest " + d.nearest()
                    + " with max_payout " + e.cap() + ") - " + name + " doesn't take " + d.stake() + " " + unit);
        }
        if (quiet) {
            return;
        }
        for (SlotsEngine.StakeOdds o : e.odds().values()) {
            if (o.aboveTarget()) {
                n.info(id + " stake " + o.stake() + ": " + RtpLimits.tenthPercent(o.rtp()) + "% (target "
                        + fmt(target) + " not reachable with whole weights)");
            }
        }
    }

    /** Why nothing could be solved, for the one WARN. */
    private static String why(SlotsEngine e, Map<String, Integer> reels, Map<String, Integer> pays) {
        if (reels.values().stream().allMatch(w -> w == 0)) {
            return "every reel weight is 0";
        }
        if (pays.values().stream().allMatch(p -> p == 0)) {
            return "no line pays anything";
        }
        StringBuilder sb = new StringBuilder();
        for (SlotsEngine.Dropped d : e.dropped()) {
            sb.append(sb.length() == 0 ? "" : ", ").append("stake ").append(d.stake()).append(": nearest ")
                    .append(d.nearest());
        }
        return sb.toString();
    }

    /** An ordered, unmodifiable map from key, value pairs. */
    static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
