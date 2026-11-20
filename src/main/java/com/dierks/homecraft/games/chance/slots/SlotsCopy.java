package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.FeedWriter;
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
            return "&7" + s + ", " + s + ", " + s + " &8(a " + theme.wild() + " counts)";
        }
        return "&7Any two the same " + theme.noun() + " &8(a " + theme.wild() + " counts)";
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
        out.add("A " + theme.wild() + " stands in for any " + theme.noun() + ". " + theme.blank() + " never pays.");
        if (table.pot()) {
            out.add("Every spin adds to the pot. Three Wilds takes it all.");
        }
        if (table.freeSpins() > 0) {
            out.add("Three " + theme.wild() + "s also give " + freeSpins(table.freeSpins()) + ".");
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
        return oddsLine(name, engine.playable() ? engine.lowestRtp() : Double.NaN, dailyLimit);
    }

    /**
     * {@link #oddsLine(String, SlotsEngine, int)} from the lowest value itself ({@code NaN}: closed), for a machine
     * whose stakes each have an engine of their own.
     */
    public static String oddsLine(String name, double lowest, int dailyLimit) {
        if (Double.isNaN(lowest)) {
            return "&6" + name + " &7— closed";
        }
        return "&6" + name + " &7— " + RtpLimits.playerLine(lowest) + " · " + dailyLimit
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
        return "Three reels, one line. Three the same pays that " + theme.noun() + "'s line (a " + theme.wild()
                + " stands in for any " + theme.noun() + "), two the same " + (two > 1 ? "pays a little"
                : two == 1 ? "gives yours back" : "pays nothing") + ", and " + theme.blank() + " never pays."
                + (table.pot() ? " Every spin adds to the pot, and three Wilds takes it all." : "")
                + (table.freeSpins() > 0 ? " Three " + theme.wild() + "s on the line also give "
                + freeSpins(table.freeSpins()) + ", played at once at the same stake; a free spin gives no more."
                : "");
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

    // ---- Deep Mine: three rows that all count ----------------------------------------------------

    /** Deep Mine's rules: every row is a line, and a spin plays all three. */
    public static List<String> deepRules(SlotTable table) {
        SlotTheme theme = table.theme();
        int two = table.engine().multiple(Line.TWO_THE_SAME);
        return List.of("Three rows of " + theme.reelsOf() + ". Every row counts.",
                "You put the same on each row, so a spin is three times that.",
                two > 1 ? "On a row, three the same pays the most. Two the same pays a little."
                        : two == 1 ? "On a row, three the same pays the most. Two the same gives that row's back."
                        : "On a row, only three the same pays.",
                "A " + theme.wild() + " stands in for any " + theme.noun() + ". " + theme.blank() + " never pays.",
                "Every spin is decided before the reels move.");
    }

    /** The website's one-line rules for Deep Mine. */
    public static String deepFeedRules(SlotTable table) {
        SlotTheme theme = table.theme();
        int two = table.engine().multiple(Line.TWO_THE_SAME);
        return "Three rows of three reels, and every row is a line: a spin puts the same on each row, so it costs"
                + " three times that. On a row, three the same pays that " + theme.noun() + "'s line (a "
                + theme.wild() + " stands in for any " + theme.noun() + "), two the same " + (two > 1
                ? "pays a little" : two == 1 ? "gives that row's back" : "pays nothing") + ", and " + theme.blank()
                + " never pays. The paytable is what one row pays.";
    }

    /** "2 a row · 6 tokens a spin" (or "$5 a row · $15 a spin"). */
    public static String deepStake(int perLine, boolean money) {
        int total = DeepMineEngine.total(perLine);
        return money ? Parlor.dollars(perLine) + " a row · " + Parlor.dollars(total) + " a spin"
                : perLine + " a row · " + tokens(total) + " a spin";
    }

    /** The Spin tile: "&amp;a&amp;lSpin &amp;7- &amp;63 lines × 2 = 6 tokens in" (or "3 lines × $5 = $15 in"). */
    public static String deepSpin(int perLine, boolean money) {
        int total = DeepMineEngine.total(perLine);
        return "&a&lSpin &7- &6" + DeepMineEngine.LINES + " lines × " + (money ? Parlor.dollars(perLine) + " = "
                + Parlor.dollars(total) : perLine + " = " + tokens(total)) + " in";
    }

    /** Why three rows of {@code perLine} dollars can't be played: the whole spin is over the biggest stake. */
    public static String deepOverMost(int perLine, int most) {
        return DeepMineEngine.LINES + " rows of " + Parlor.dollars(perLine) + " is "
                + Parlor.dollars(DeepMineEngine.total(perLine)) + ". The most is " + Parlor.dollars(most) + ".";
    }

    /** A paytable tile's lore on Deep Mine: what makes the line on a row, and what that row gives back. */
    public static List<String> deepLineLore(SlotTheme theme, LineOdds line) {
        List<String> lore = new ArrayList<>();
        lore.add(made(theme, line.line()));
        lore.add("&7" + tokens(line.stake()) + " on a row → " + (line.win()
                ? "&6" + line.payout() + " &7back"
                : "that row's " + line.payout() + " back"));
        lore.add("&7Comes up on a row about 1 in " + grouped(line.oneIn()) + " spins.");
        return lore;
    }

    /** "&amp;7At least one row pays about 92 spins in 100.", from the exact chance. */
    public static String deepHitLine(Frac anyRowPays) {
        return "&7At least one row pays about " + Math.round(anyRowPays.value() * 100) + " spins in 100.";
    }

    /**
     * A line for each row that paid: "&amp;7Top row: Three coal → &amp;66". What a row gave back is only ever a number
     * here: whether the spin was a win is the total's to say.
     */
    public static List<String> deepRows(SlotTheme theme, DeepMineEngine.Dig dig) {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < dig.rows().size(); r++) {
            Spin row = dig.rows().get(r);
            if (row.payout() > 0 && row.line() != null) {
                out.add(deepRow(theme, r, row));
            }
        }
        return out;
    }

    /** One row: "&amp;7Top row: Three coal → &amp;66", or "&amp;8Top row: nothing". */
    public static String deepRow(SlotTheme theme, int index, Spin row) {
        String name = DeepMineEngine.rowName(index) + " row: ";
        return row.payout() > 0 && row.line() != null
                ? "&7" + name + theme.label(row.line()) + " → &6" + row.payout()
                : "&8" + name + "nothing";
    }

    /**
     * The whole spin's result: a win only when more came back than the three rows cost, "Your N back" when exactly
     * that came back, a plain amount for less, and "No win this time." for nothing.
     */
    public static String deepResult(SlotTheme theme, DeepMineEngine.Dig dig) {
        if (dig.win()) {
            int paid = dig.paidRows();
            String what = paid == 1 ? theme.label(paidRow(dig).line()) + "!" : paid + " rows paid!";
            return "&a" + what + " &6+" + dig.payout() + " tokens";
        }
        return plain(dig.stake(), dig.payout());
    }

    private static Spin paidRow(DeepMineEngine.Dig dig) {
        for (Spin row : dig.rows()) {
            if (row.payout() > 0) {
                return row;
            }
        }
        return dig.rows().get(0);
    }

    /** A result that is not a win: "Your N back.", "N back." or "No win this time.". */
    private static String plain(int stake, int payout) {
        if (payout == stake) {
            return "&7Your " + payout + " back.";
        }
        return payout > 0 ? "&7" + payout + " back." : "&7No win this time.";
    }

    /** Deep Mine's admin detail: each stake's exact return, the solved blank symbol, and how often rows pay. */
    public static List<String> deepOddsDetail(SlotTable table, SlotBank bank) {
        List<String> out = new ArrayList<>();
        for (int perLine : bank.stakes()) {
            DeepMineEngine spin = new DeepMineEngine(bank.engine(perLine), perLine);
            StakeOdds o = spin.odds();
            out.add("&7  " + tokens(spin.total()) + " in (" + perLine + " a row): &f" + RtpLimits.tenthPercent(o.rtp())
                    + "% &7· " + table.theme().blank() + " " + o.stone() + " of " + o.total()
                    + " · a row pays 1 in " + String.format(Locale.ROOT, "%.1f", 1.0 / o.hitChance())
                    + " · any row 1 in " + String.format(Locale.ROOT, "%.2f", 1.0 / spin.anyRowPays().value())
                    + " · a spin's most " + spin.most()
                    + (o.aboveTarget() ? " &8(target " + fmt(table.rtp()) + " not reachable, next above)" : ""));
        }
        for (SlotBank.Dropped d : bank.dropped()) {
            out.add("&c  " + d.stake() + " a row: dropped &7(nearest " + d.nearest() + ")");
        }
        return out;
    }

    // ---- Mob Slots: free spins --------------------------------------------------------------------

    /** "5 free spins" / "1 free spin". */
    public static String freeSpins(int n) {
        return n + " free spin" + (n == 1 ? "" : "s");
    }

    /** The Creeper line's paytable tile: "&amp;fThree creepers &amp;6×20 &amp;7+ &amp;a5 free spins &amp;7· 1 in 182". */
    public static String bonusLineName(SlotTheme theme, LineOdds line, int freeSpins) {
        String pays = line.capped() ? "&6" + line.payout() + " tokens" : "&6×" + line.multiple();
        return "&f" + theme.label(line.line()) + " " + pays + " &7+ &a" + freeSpins(freeSpins) + " &7· 1 in "
                + grouped(line.oneIn());
    }

    /** Its lore: the line's own, and what the free spins are. */
    public static List<String> bonusLineLore(SlotTheme theme, LineOdds line, int freeSpins) {
        List<String> lore = new ArrayList<>(lineLore(theme, line));
        lore.add(2, "&7And " + freeSpins(freeSpins) + " at the same stake, played at once.");
        lore.add(3, "&7A free spin pays like any spin, and gives no more.");
        return lore;
    }

    /** The result tile the moment the Creepers land: "&amp;aThree creepers! &amp;e5 free spins!". */
    public static String bonusStart(SlotTheme theme, int freeSpins) {
        return "&a" + theme.label(Line.THREE_WILDS) + "! &e" + freeSpins(freeSpins) + "!";
    }

    /**
     * The result tile while the free spins land: "&amp;eFree spin 2 of 5 &amp;7· 44 tokens back so far" (what the round
     * has given back up to and including the spin that just stopped).
     */
    public static String freeSpinLine(int number, int of, int soFar) {
        return "&eFree spin " + number + " of " + of + " &7· " + tokens(soFar) + " back so far";
    }

    /**
     * A whole Mob Slots round's result. Without free spins it is the spin's own; with them it names them and gives
     * the round's one total: a win only when more came back than went in.
     */
    public static String bonusResult(SlotTheme theme, FreeSpinsEngine.Round round) {
        if (!round.bonus()) {
            return result(theme, round.base());
        }
        String what = theme.label(Line.THREE_WILDS) + " and " + freeSpins(round.free().size());
        if (round.win()) {
            return "&a" + what + "! &6+" + round.payout() + " tokens";
        }
        return "&7" + what + ". " + plain(round.stake(), round.payout()).substring(2);
    }

    /** The private title for a round with free spins: {title, subtitle}. */
    public static String[] bonusTitle(FreeSpinsEngine.Round round) {
        return new String[]{"&e" + freeSpins(round.free().size()) + "!", "&6+" + round.payout() + " tokens"};
    }

    /** "&amp;7Three Creepers give 5 free spins about 1 spin in 182." */
    public static String bonusHitLine(SlotTheme theme, int freeSpins, Frac chance) {
        return "&7Three " + theme.wild() + "s give " + freeSpins(freeSpins) + " about 1 spin in "
                + grouped(FeedWriter.oneIn(chance.value())) + ".";
    }

    /**
     * Mob Slots' admin detail: each stake's exact return with the free spins counted, one spin's own return, the
     * solved blank symbol, and how often the free spins come.
     */
    public static List<String> bonusOddsDetail(SlotTable table, SlotBank bank) {
        List<String> out = new ArrayList<>();
        int n = table.freeSpins();
        for (int stake : bank.stakes()) {
            FreeSpinsEngine round = new FreeSpinsEngine(bank.engine(stake), stake, n);
            StakeOdds o = bank.odds(stake);
            Frac back = round.givesBack();
            out.add("&7  " + tokens(stake) + " in: &f" + RtpLimits.tenthPercent(back.value()) + "%"
                    + " &7· one spin " + RtpLimits.tenthPercent(o.rtp()) + "% · " + table.theme().blank() + " "
                    + o.stone() + " of " + o.total()
                    + " · pays 1 in " + String.format(Locale.ROOT, "%.1f", 1.0 / o.hitChance())
                    + (n > 0 ? " · " + freeSpins(n) + " 1 in " + grouped(FeedWriter.oneIn(round.creepers().value()))
                    : "")
                    + " · a round's most " + round.most()
                    + (back.compareTo(Frac.percent(table.rtp())) > 0 ? " &8(target " + fmt(table.rtp())
                    + " not reachable, next above)" : ""));
        }
        for (SlotBank.Dropped d : bank.dropped()) {
            out.add("&c  " + tokens(d.stake()) + " in: dropped &7(nearest " + d.nearest() + ")");
        }
        return out;
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
