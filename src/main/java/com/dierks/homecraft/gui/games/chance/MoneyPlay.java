package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.gui.Menus;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tokens or money, on a game of chance's screen in the Game Parlor (0.46.0; the owner: "Being able to choose between
 * if you want to play for tokens or money would be ideal", "you click the 5, 10 or 25 to simply add to the amount").
 *
 * <p>One of these belongs to each open screen. While its player may play for money ({@link Parlor#offered}: the
 * permission, in the Parlor, money play on) the screen shows a switch; switched to money, the stake buttons become
 * chips that add up to the dollars the next play puts in, with a tile that takes them back. Anywhere else, and for
 * everyone else, there is no switch and the screen is exactly the tokens screen.
 *
 * <p>Nothing here moves money: the play gate checks the amount again when it goes in.
 */
final class MoneyPlay {

    /** The chips' pictures, smallest first. */
    private static final Material[] CHIP = {Material.COPPER_INGOT, Material.IRON_INGOT, Material.GOLD_INGOT};

    private final HomeCraftManagement plugin;
    private final GamesService games;
    private final Player viewer;
    /** Playing for dollars. */
    private boolean money;
    /** The dollars the next play puts in (Deep Mine: on each of its rows). */
    private int stake;
    /** How many times the chips' amount a play costs: 1, or 3 for Deep Mine's three rows. */
    private final int times;

    MoneyPlay(HomeCraftManagement plugin, GamesService games, Player viewer) {
        this(plugin, games, viewer, 1);
    }

    /**
     * The chips build an amount a play costs {@code times} over (Deep Mine: what goes on each of three rows), so a
     * chip goes on only while the whole play still fits the biggest stake, the day's limit and the balance.
     */
    MoneyPlay(HomeCraftManagement plugin, GamesService games, Player viewer, int times) {
        this.plugin = plugin;
        this.games = games;
        this.viewer = viewer;
        this.times = Math.max(1, times);
    }

    /** Whether the switch is shown now. */
    boolean offered() {
        return games.parlor().offered(viewer);
    }

    /** Whether the next play is for dollars (never once the player may no longer play for money here). */
    boolean on() {
        if (money && !offered()) {
            money = false;
        }
        return money;
    }

    /** A round already on the table says which it is (a resumed hand). */
    void follow(boolean roundIsMoney, int roundStake) {
        money = roundIsMoney;
        if (roundIsMoney) {
            stake = roundStake;
        }
    }

    /** The dollars the next play puts in. */
    int stake() {
        return stake;
    }

    Parlor.Rules rules() {
        return games.parlor().rules();
    }

    /** Switch between tokens and money (only while money is offered); money starts at the smallest chip. */
    void flip() {
        if (!offered()) {
            money = false;
            return;
        }
        money = !money;
        if (money && stake <= 0) {
            add(rules().minStake());
        }
    }

    /** One more chip on the stake, if the biggest stake, the day's limit and the balance allow it. */
    void add(int chip) {
        stake = Parlor.add(stake * times, chip * times, rules(), games.parlor().leftToday(viewer.getUniqueId()),
                balance()) / times;
    }

    /** Take the chips back. */
    void clear() {
        stake = 0;
    }

    double balance() {
        return plugin.economy() == null ? 0 : plugin.economy().balance(viewer);
    }

    /** "5 tokens" or "$5". */
    static String amount(boolean money, int n) {
        return money ? Parlor.dollars(n) : n + " token" + (n == 1 ? "" : "s");
    }

    /** "tokens" or "dollars", for "of every 100 ...". */
    static String unit(boolean money) {
        return money ? "dollars" : "tokens";
    }

    /**
     * A whole number as a line shows it: after a colour code ("&amp;617" is 17 in gold, never 617) or after anything that
     * is not a digit, a colour code's own character or a dollar sign.
     */
    private static final String NUMBER = "(?:(?<=[&§][0-9a-fk-orA-FK-OR])|(?<![&§\\d$]))(\\d[\\d,]*)";
    private static final java.util.regex.Pattern TOKENS = java.util.regex.Pattern.compile(NUMBER + " tokens?\\b");
    /** "5 in →": only before the arrow ("1 in 1,829 spins" is not an amount). */
    private static final java.util.regex.Pattern IN = java.util.regex.Pattern.compile(NUMBER + " in →");
    /** "→ 18", "→ &amp;618", "put in 5": the amount after it, past any colour code. */
    private static final java.util.regex.Pattern AFTER =
            java.util.regex.Pattern.compile("(→ |put in )((?:[&§][0-9a-fk-orA-FK-OR])*)(\\d[\\d,]*)");
    private static final java.util.regex.Pattern BACK = java.util.regex.Pattern.compile(NUMBER + "( (?:[&§][0-9a-fk-orA-FK-OR])?back\\b)");

    /**
     * A line written for tokens, as it reads for dollars: "17 tokens" is "$17", "Your 10 back" is "Your $10 back",
     * "5 in → 9 back" is "$5 in → $9 back". The games' own words (their prizes, their paytables) are written once,
     * for tokens; a money screen passes them through here. Unchanged when {@code money} is false.
     */
    static String say(boolean money, String line) {
        if (!money || line == null) {
            return line;
        }
        String out = TOKENS.matcher(line).replaceAll("\\$$1");
        out = IN.matcher(out).replaceAll("\\$$1 in →");
        out = AFTER.matcher(out).replaceAll("$1$2\\$$3");
        out = BACK.matcher(out).replaceAll("\\$$1$2");
        // (a colour code's letter sits right against the word, so "not after a letter" is asked of two back)
        out = out.replaceAll("(?<![A-Za-z]{2})[Tt]okens put into games of chance today",
                "Money put into the Parlor's games today");
        return out.replaceAll("(?<![A-Za-z]{2})tokens\\b", "money").replaceAll("(?<![A-Za-z]{2})Tokens\\b", "Money");
    }

    /** {@link #say(boolean, String)} over lore lines. */
    static String[] say(boolean money, List<String> lines) {
        String[] out = new String[lines.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = say(money, lines.get(i));
        }
        return out;
    }

    // ---- tiles --------------------------------------------------------------------------------------------

    /** The switch: what the next play is for, and a click changes it. */
    ItemStack switchTile() {
        return on()
                ? Menus.glint(Menus.icon(Material.GOLD_BLOCK, "&6Playing for money", "&eClick to play for tokens"), true)
                : Menus.icon(Material.SUNFLOWER, "&ePlaying for tokens", "&eClick to play for money");
    }

    /** Chip {@code i} (0 is the smallest): "+$5", or why it can't go on now. */
    ItemStack chipTile(int i) {
        Parlor.Rules r = rules();
        int chip = r.chips().get(i);
        String why = Parlor.chipRefusal(stake * times, chip * times, r,
                games.parlor().leftToday(viewer.getUniqueId()), balance());
        if (why != null) {
            return CardGameTiles.unlit("+" + Parlor.dollars(chip), why);
        }
        return Menus.count(Menus.icon(CHIP[Math.min(i, CHIP.length - 1)], "&6+" + Parlor.dollars(chip),
                "&eClick to add it"), Math.min(64, chip));
    }

    /** How many chip tiles there are. */
    int chips() {
        return rules().chips().size();
    }

    /** The chip at {@code i}. */
    int chip(int i) {
        return rules().chips().get(i);
    }

    /** The stake so far, and a click takes the chips back. */
    ItemStack stakeTile() {
        if (stake <= 0) {
            return Menus.icon(Material.BOWL, "&7No chips yet", "&7Click a chip to add it.");
        }
        return Menus.glint(Menus.icon(Material.RAW_GOLD, "&6" + Parlor.dollars(stake) + " in",
                "&eClick to take your chips back"), true);
    }

    /** "You have $123.45". */
    ItemStack balanceTile() {
        return Menus.icon(Material.GOLD_INGOT, "&eYou have " + Menus.money(plugin, balance()));
    }

    /**
     * "Today: $35 of $250", with the plays left of this game today underneath.
     *
     * @param plays "hands", "runs" or "spins"
     */
    ItemStack todayTile(Game game, int dailyLimit, String plays) {
        int in = Math.max(0, games.parlor().inToday(viewer.getUniqueId()));
        int limit = games.parlor().limit(viewer.getUniqueId());
        String name = limit == Parlor.NO_LIMIT ? "&eToday: &6" + Parlor.dollars(in)
                : "&eToday: &6" + Parlor.dollars(in) + " &eof &6" + Parlor.dollars(limit);
        List<String> lore = new ArrayList<>();
        lore.add("&7Money put into the Parlor's games today.");
        try {
            int played = games.dao().playsToday(viewer.getUniqueId(), game.id(), plugin.clock().dayKey());
            lore.add("&7" + game.name() + " " + plays + " left today: &f" + Math.max(0, dailyLimit - played));
        } catch (SQLException | RuntimeException e) {
            // The count is a courtesy; the gate still enforces the limit when the money goes in.
        }
        Breaks breaks = games.breaks();
        if (breaks != null) {
            lore.add("&7A pause of your own: Wallet → Take a break.");
        }
        return Menus.icon(Material.CLOCK, name, lore.toArray(new String[0]));
    }
}
