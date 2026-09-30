package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Dropped;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every Ore Slots line a player or admin reads, built from the engine (spec §5.1 "published odds
 * come from the same engine object", R1.2, R1.3).
 *
 * <p>Pure text, no Bukkit, so a test can change the config's {@code rtp} and watch the screen's
 * give-back line, the paytable, {@code /hcm arcade odds} and the website all move together. The
 * wording follows the house rules: "tokens in" and "gives back about N of every 100 tokens" with
 * the computed number floored, a win only when more comes back than went in, "Your N back" when
 * the same comes back, and a plain "No win this time." otherwise.
 */
public final class SlotsCopy {

    private SlotsCopy() {
    }

    /** The player's give-back line for a stake: "&amp;eGives back about 89 of every 100 tokens". */
    public static String giveBack(StakeOdds odds) {
        String line = RtpLimits.playerLine(odds.rtp());
        return "&e" + Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    /** A paytable tile's name: "&amp;fThree diamonds &amp;6×40 &amp;7· 1 in 1,829". */
    public static String lineName(LineOdds line) {
        String pays = line.capped() ? "&6" + line.payout() + " tokens" : "&6×" + line.multiple();
        return "&f" + line.line().label() + " " + pays + " &7· 1 in " + grouped(line.oneIn());
    }

    /** A paytable tile's lore: what makes the line and what it gives back at the stake. */
    public static List<String> lineLore(LineOdds line) {
        List<String> lore = new ArrayList<>();
        Line l = line.line();
        if (l == Line.THREE_WILDS) {
            lore.add("&7Wild, Wild, Wild");
        } else if (l.symbol() != null) {
            String s = l.symbol().label();
            lore.add("&7" + s + ", " + s + ", " + s + " &8(a Wild counts)");
        } else {
            lore.add("&7Any two the same ore &8(a Wild counts)");
        }
        lore.add("&7" + tokens(line.stake()) + " in → " + (line.win()
                ? "&6" + line.payout() + " &7back"
                : "your " + line.payout() + " back"));
        lore.add("&7Comes up about 1 in " + grouped(line.oneIn()) + " spins.");
        return lore;
    }

    /** "&amp;7A spin pays something about 1 in 3 times." */
    public static String hitLine(StakeOdds odds) {
        return "&7A spin pays something about 1 in " + grouped(odds.hitOneIn()) + " times.";
    }

    /** The tile's key fact: "1-5 tokens" (or "5 tokens" with one stake). */
    public static String stakeRange(List<Integer> stakes) {
        if (stakes.isEmpty()) {
            return "closed";
        }
        int lo = stakes.get(0);
        int hi = stakes.get(stakes.size() - 1);
        return lo == hi ? tokens(lo) : lo + "-" + hi + " tokens";
    }

    /**
     * The one line everyone gets from {@code /hcm arcade odds} (spec R1.21), with the LOWEST
     * computed value over the stakes: "&amp;6Ore Slots &amp;7— gives back about 89 of every 100 tokens
     * · 50 plays a day".
     */
    public static String oddsLine(String name, SlotsEngine engine, int dailyLimit) {
        if (!engine.playable()) {
            return "&6" + name + " &7— closed";
        }
        return "&6" + name + " &7— " + RtpLimits.playerLine(engine.lowestRtp()) + " · " + dailyLimit
                + " play" + (dailyLimit == 1 ? "" : "s") + " a day";
    }

    /** The admin's per-stake detail (one decimal, floored, plus the solved Stone and spread). */
    public static List<String> oddsDetail(SlotsEngine engine) {
        List<String> out = new ArrayList<>();
        for (StakeOdds o : engine.odds().values()) {
            out.add("&7  " + tokens(o.stake()) + " in: &f" + RtpLimits.tenthPercent(o.rtp()) + "%"
                    + " &7· Stone " + o.stone() + " of " + o.total()
                    + " · pays 1 in " + String.format(Locale.ROOT, "%.1f", 1.0 / o.hitChance())
                    + " · SD " + String.format(Locale.ROOT, "%.2f", o.sd())
                    + (o.aboveTarget() ? " &8(target " + fmt(engine.targetPercent()) + " not reachable, next above)" : ""));
        }
        for (Dropped d : engine.dropped()) {
            out.add("&c  " + tokens(d.stake()) + " in: dropped &7(nearest " + d.nearest() + ")");
        }
        return out;
    }

    /** The website's one-line rules. */
    public static String feedRules() {
        return "Three reels, one line. Three the same pays that ore's line (a Wild stands in for any ore), "
                + "two the same pays a little, and Stone never pays.";
    }

    /** The result in chat and on the screen: a win, "Your N back", or "No win this time.". */
    public static String result(Spin spin) {
        if (spin.win()) {
            return "&a" + spin.line().label() + "! &6+" + spin.payout() + " tokens";
        }
        if (spin.payout() == spin.stake()) {
            return "&7Your " + spin.payout() + " back.";
        }
        if (spin.payout() > 0) {
            return "&7" + spin.payout() + " back.";
        }
        return "&7No win this time.";
    }

    /** The private title for a line of ×20 or more: {title, subtitle}; never "BIG WIN". */
    public static String[] title(Spin spin) {
        return new String[]{"&e" + spin.line().label() + "!", "&6+" + spin.payout() + " tokens"};
    }

    /** "1 token" / "5 tokens". */
    public static String tokens(int n) {
        return n + " token" + (n == 1 ? "" : "s");
    }

    /** 12805 → "12,805". */
    public static String grouped(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
