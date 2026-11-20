package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Dropped;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every slot machine line a player or admin reads, built from the engine (spec §5.1 "published odds
 * come from the same engine object", R1.2, R1.3).
 *
 * <p>Pure text, no Bukkit, so a test can change the config's {@code rtp} and watch the screen's
 * give-back line, the paytable, {@code /hcm arcade odds} and the website all move together. The
 * wording follows the house rules: "tokens in" and "gives back about N of every 100 tokens" with
 * the computed number floored, a win only when more comes back than went in, "Your N back" when
 * the same comes back, and a plain "No win this time." otherwise.
 *
 * <p>Every line takes the machine's {@link SlotTheme}, which names its symbols and lines; the methods
 * without one are Ore Slots', word for word as they were. A pot machine's lines say "the pot", in dollars and cents.
 */
public final class SlotsCopy {

    private SlotsCopy() {
    }

    /** The player's give-back line for a stake: "&amp;eGives back about 89 of every 100 tokens". */
    public static String giveBack(StakeOdds odds) {
        return giveBack(odds.rtp());
    }

    /**
     * The give-back line for a stake of this table: the lines' exact return and, on a pot machine, the share that
     * goes into the pot.
     */
    public static String giveBack(SlotTable table, StakeOdds odds) {
        return giveBack(table.givesBack(odds));
    }

    private static String giveBack(double fraction) {
        String line = RtpLimits.playerLine(fraction);
        return "&e" + Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    /** A paytable tile's name: "&amp;fThree diamonds &amp;6×40 &amp;7· 1 in 1,829". */
    public static String lineName(LineOdds line) {
        return lineName(SlotTheme.ORE, line);
    }

    /** {@link #lineName(LineOdds)} with the theme's name for the line. */
    public static String lineName(SlotTheme theme, LineOdds line) {
        String pays = line.capped() ? "&6" + line.payout() + " tokens" : "&6×" + line.multiple();
        return "&f" + theme.label(line.line()) + " " + pays + " &7· 1 in " + grouped(line.oneIn());
    }

    /** A paytable tile's lore: what makes the line and what it gives back at the stake. */
    public static List<String> lineLore(LineOdds line) {
        return lineLore(SlotTheme.ORE, line);
    }

    /** {@link #lineLore(LineOdds)} with the theme's names. */
    public static List<String> lineLore(SlotTheme theme, LineOdds line) {
        List<String> lore = new ArrayList<>();
        lore.add(made(theme, line.line()));
        lore.add("&7" + tokens(line.stake()) + " in → " + (line.win()
                ? "&6" + line.payout() + " &7back"
                : "your " + line.payout() + " back"));
        lore.add("&7Comes up about 1 in " + grouped(line.oneIn()) + " spins.");
        return lore;
    }

    /** What makes a line: "&amp;7Diamond, Diamond, Diamond &amp;8(a Wild counts)". */
    private static String made(SlotTheme theme, Line l) {
        if (l == Line.THREE_WILDS) {
            String w = theme.label(Symbol.WILD);
            return "&7" + w + ", " + w + ", " + w;
        }
        if (l.symbol() != null) {
            String s = theme.label(l.symbol());
            return "&7" + s + ", " + s + ", " + s + " &8(a Wild counts)";
        }
        return "&7Any two the same " + theme.noun() + " &8(a Wild counts)";
    }

    /** A pot machine's three-Wilds tile: "&amp;fThree wilds &amp;6takes the pot &amp;7· 1 in 594". */
    public static String potLineName(SlotTheme theme, LineOdds line) {
        return "&f" + theme.label(line.line()) + " &6takes the pot &7· 1 in " + grouped(line.oneIn());
    }

    /** Its lore: what makes it, what the pot holds now, how often it comes up. */
    public static List<String> potLineLore(SlotTheme theme, LineOdds line, long potCents) {
        List<String> lore = new ArrayList<>();
        lore.add(made(theme, line.line()));
        lore.add("&7The whole pot: &6" + Parlor.money(potCents));
        lore.add("&7Comes up about 1 in " + grouped(line.oneIn()) + " spins.");
        return lore;
    }

    /** The pot's own tile: "&amp;6The pot: &amp;e$212.25". */
    public static String potName(long potCents) {
        return "&6The pot: &e" + Parlor.money(potCents);
    }

    /** Under it: how it is taken, what feeds it, where it starts again. */
    public static List<String> potLore(SlotTheme theme, long feedCents, long seedCents) {
        String w = theme.label(Symbol.WILD);
        List<String> lore = new ArrayList<>();
        lore.add("&7" + w + ", " + w + ", " + w + " takes it all.");
        if (feedCents > 0) {
            lore.add("&7Every spin adds " + Parlor.money(feedCents) + ", whoever spins.");
        }
        lore.add("&7It starts again at " + Parlor.money(seedCents) + ".");
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

    /** A pot machine's key fact: "$5 a spin · pot $212". */
    public static String potFact(int price, long potCents) {
        return Parlor.dollars(price) + " a spin · pot " + Parlor.money(wholeDollars(potCents));
    }

    /** A pot machine's label in the room: "The Motherlode · $212" (whole dollars, so it changes now and then). */
    public static String potLabel(String name, long potCents) {
        return name + " · " + Parlor.money(wholeDollars(potCents));
    }

    private static long wholeDollars(long cents) {
        return Math.max(0, cents) / 100 * 100;
    }

    /** Ore Slots' rules, word for word as they have always been (whatever its config says). */
    private static final List<String> ORE_RULES = List.of("Three reels of ores. Match them on the line.",
            "Three the same pays the most. Two the same pays a little.",
            "A Wild stands in for any ore. Stone never pays.",
            "Every spin is decided before the reels move.");

    /**
     * The lines of a machine's rules (its tile and its rules tile). A themed machine's come from what it really
     * pays: one whose "two the same" pays nothing says so, and a pot machine says what the pot is. Ore Slots' are
     * the four it has always shown.
     */
    public static List<String> rules(SlotTable table) {
        SlotTheme theme = table.theme();
        if (theme == SlotTheme.ORE) {
            return ORE_RULES;
        }
        int two = table.engine().multiple(Line.TWO_THE_SAME);
        List<String> out = new ArrayList<>();
        out.add("Three reels of " + theme.reelsOf() + ". Match them on the line.");
        out.add(two > 1 ? "Three the same pays the most. Two the same pays a little."
                : two == 1 ? "Three the same pays the most. Two the same gives yours back."
                : "Only three the same pays, and it pays big.");
        out.add("A Wild stands in for any " + theme.noun() + ". " + theme.blank() + " never pays.");
        if (table.pot()) {
            out.add("Every spin adds to the pot. Three Wilds takes it all.");
        }
        out.add("Every spin is decided before the reels move.");
        return List.copyOf(out);
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

    /**
     * A pot machine's line for {@code /hcm arcade odds}: "&amp;6The Motherlode &amp;7— gives back about 90 of every 100
     * dollars, the pot's share counted · $5 a spin · 50 plays a day".
     */
    public static String potOddsLine(String name, SlotTable table, SlotsEngine engine) {
        StakeOdds odds = engine.playable() ? engine.odds().values().iterator().next() : null;
        if (odds == null) {
            return "&6" + name + " &7— closed";
        }
        int limit = table.dailyLimit();
        return "&6" + name + " &7— gives back about " + RtpLimits.wholePercent(table.givesBack(odds))
                + " of every 100 dollars, the pot's share counted · " + Parlor.dollars(odds.stake()) + " a spin · "
                + limit + " play" + (limit == 1 ? "" : "s") + " a day";
    }

    /** The admin's per-stake detail (one decimal, floored, plus the solved Stone and spread). */
    public static List<String> oddsDetail(SlotsEngine engine) {
        return oddsDetail(SlotTheme.ORE, engine);
    }

    /** {@link #oddsDetail(SlotsEngine)} with the theme's name for the blank symbol. */
    public static List<String> oddsDetail(SlotTheme theme, SlotsEngine engine) {
        List<String> out = new ArrayList<>();
        for (StakeOdds o : engine.odds().values()) {
            out.add("&7  " + tokens(o.stake()) + " in: &f" + RtpLimits.tenthPercent(o.rtp()) + "%"
                    + " &7· " + theme.blank() + " " + o.stone() + " of " + o.total()
                    + " · pays 1 in " + String.format(Locale.ROOT, "%.1f", 1.0 / o.hitChance())
                    + " · SD " + String.format(Locale.ROOT, "%.2f", o.sd())
                    + (o.aboveTarget() ? " &8(target " + fmt(engine.targetPercent()) + " not reachable, next above)" : ""));
        }
        for (Dropped d : engine.dropped()) {
            out.add("&c  " + tokens(d.stake()) + " in: dropped &7(nearest " + d.nearest() + ")");
        }
        return out;
    }

    /**
     * A pot machine's admin detail: the lines' exact return and the pot's share apart, the solved blank symbol, how
     * often the pot is taken, and what it holds now.
     */
    public static List<String> potOddsDetail(SlotTable table, SlotsEngine engine, long potCents, long seedCents) {
        List<String> out = new ArrayList<>();
        for (StakeOdds o : engine.odds().values()) {
            LineOdds wilds = o.line(Line.THREE_WILDS);
            out.add("&7  " + Parlor.dollars(o.stake()) + " in: lines &f" + RtpLimits.tenthPercent(o.rtp()) + "%"
                    + " &7+ pot &f" + table.feedPercent() + "%"
                    + " &7· " + table.theme().blank() + " " + o.stone() + " of " + o.total()
                    + " · pays 1 in " + String.format(Locale.ROOT, "%.1f", 1.0 / o.hitChance())
                    + (wilds == null ? "" : " · the pot 1 in " + grouped(wilds.oneIn())));
            out.add("&7  the pot now: &f" + Parlor.money(potCents) + " &7(it starts at " + Parlor.money(seedCents)
                    + ")");
        }
        for (Dropped d : engine.dropped()) {
            out.add("&c  " + Parlor.dollars(d.stake()) + " in: dropped &7(nearest " + d.nearest() + ")");
        }
        return out;
    }

    /** The website's one-line rules. */
    public static String feedRules() {
        return "Three reels, one line. Three the same pays that ore's line (a Wild stands in for any ore), "
                + "two the same pays a little, and Stone never pays.";
    }

    /** {@link #feedRules()} for a themed machine, from what it really pays (Ore Slots: {@link #feedRules()} itself). */
    public static String feedRules(SlotTable table) {
        SlotTheme theme = table.theme();
        if (theme == SlotTheme.ORE) {
            return feedRules();
        }
        int two = table.engine().multiple(Line.TWO_THE_SAME);
        return "Three reels, one line. Three the same pays that " + theme.noun() + "'s line (a Wild stands in for any "
                + theme.noun() + "), two the same " + (two > 1 ? "pays a little" : two == 1 ? "gives yours back"
                : "pays nothing") + ", and " + theme.blank() + " never pays."
                + (table.pot() ? " Every spin adds to the pot, and three Wilds takes it all." : "");
    }

    /** The result in chat and on the screen: a win, "Your N back", or "No win this time.". */
    public static String result(Spin spin) {
        return result(SlotTheme.ORE, spin);
    }

    /** {@link #result(Spin)} with the theme's name for the line. */
    public static String result(SlotTheme theme, Spin spin) {
        if (spin.win()) {
            return "&a" + theme.label(spin.line()) + "! &6+" + spin.payout() + " tokens";
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
        return title(SlotTheme.ORE, spin);
    }

    /** {@link #title(Spin)} with the theme's name for the line. */
    public static String[] title(SlotTheme theme, Spin spin) {
        return new String[]{"&e" + theme.label(spin.line()) + "!", "&6+" + spin.payout() + " tokens"};
    }

    /** Three Wilds on a pot machine, in chat and on the screen: "&amp;aThree wilds! You took the pot: &amp;6+$212.25". */
    public static String potResult(SlotTheme theme, long potCents) {
        return "&a" + theme.label(Line.THREE_WILDS) + "! You took the pot: &6+" + Parlor.money(potCents);
    }

    /** Its private title: {title, subtitle}. */
    public static String[] potTitle(long potCents) {
        return new String[]{"&eYou took the pot!", "&6+" + Parlor.money(potCents)};
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
