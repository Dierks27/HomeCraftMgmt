package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hcm games parlor ...} (0.46.0): the Game Parlor's rules, set by command and kept in the database (the
 * owner: "Can you make these commands instead?", and of the day's limit "a way to shut them off if the admin gives the
 * okay ... just upping the limit should be just fine").
 *
 * <pre>
 *  /hcm games parlor                                   what is set, and today's money in and back
 *  /hcm games parlor only on|off                       the games of chance are played only in the Parlor
 *  /hcm games parlor money on|off                      playing for money
 *  /hcm games parlor limit &lt;dollars|off&gt;               the most a player may put in a day
 *  /hcm games parlor limit &lt;player&gt; &lt;dollars|off|default&gt;   one player's own day limit
 *  /hcm games parlor stake &lt;dollars&gt;                   the biggest stake
 *  /hcm games parlor payout &lt;dollars&gt;                  the most one play gives back
 *  /hcm games parlor chips &lt;a&gt; [b] [c]                 the chips a stake is built from
 * </pre>
 */
final class ParlorCommand {

    /** The words after {@code parlor}. */
    static final List<String> VERBS = List.of("only", "money", "limit", "stake", "payout", "chips");

    private ParlorCommand() {
    }

    /** The help lines, for {@code /hcm games help}. */
    static List<String> help() {
        return List.of(
                "&e/hcm games parlor &7- the Game Parlor's rules, and today's money in and back",
                "&e/hcm games parlor only on|off &7- games of chance only in the Clubhouse's Game Parlor",
                "&e/hcm games parlor money on|off &7- playing for money there (needs " + Parlor.PERMISSION_MONEY + ")",
                "&e/hcm games parlor limit <dollars|off> &7- the most a player may put in a day",
                "&e/hcm games parlor limit <player> <dollars|off|default> &7- one player's own day limit",
                "&e/hcm games parlor stake <dollars> &7- the biggest stake",
                "&e/hcm games parlor payout <dollars> &7- the most one play gives back",
                "&e/hcm games parlor chips <a> [b] [c] &7- the chips a stake is built from");
    }

    /**
     * Run it; {@code rest} is what follows {@code /hcm games parlor}.
     *
     * @return what to log (who did what), or {@code null} when nothing changed
     */
    static String handle(HomeCraftManagement plugin, GamesService games, CommandSender sender, String[] rest) {
        Parlor parlor = games.parlor();
        if (rest.length == 0) {
            for (String line : status(plugin, games)) {
                sender.sendMessage(Text.of(line));
            }
            return null;
        }
        String verb = rest[0].toLowerCase(Locale.ROOT);
        String value = rest.length >= 2 ? rest[1].toLowerCase(Locale.ROOT) : "";
        switch (verb) {
            case "only" -> {
                if (!onOff(value)) {
                    return usage(sender, "only on|off");
                }
                parlor.onlyHere(value.equals("on"));
                sender.sendMessage(Text.of(value.equals("on")
                        ? "&aGames of chance are played only in the Clubhouse's Game Parlor now."
                        + (games.parlorOpen() ? "" : " &7(There is no Parlor right now, so they stay where they were.)")
                        : "&aGames of chance are back on the Games screen and /hcm play, as well as in the Parlor."));
                return "parlor only " + value;
            }
            case "money" -> {
                if (!onOff(value)) {
                    return usage(sender, "money on|off");
                }
                parlor.money(value.equals("on"));
                sender.sendMessage(Text.of(value.equals("on")
                        ? "&aPlaying for money in the Game Parlor is on. &7Give it to a player with: /lp user <player>"
                        + " permission set " + Parlor.PERMISSION_MONEY + " true"
                        : "&aPlaying for money is off. &7A hand already on a table finishes as it was dealt."));
                return "parlor money " + value;
            }
            case "limit" -> {
                return limit(parlor, sender, rest);
            }
            case "stake" -> {
                int dollars = whole(value, 1, Parlor.STAKE_CEILING);
                if (dollars < 0) {
                    return usage(sender, "stake <dollars 1-" + Parlor.STAKE_CEILING + ">");
                }
                if (dollars < parlor.rules().minStake()) {
                    sender.sendMessage(Text.of("&cThe smallest chip is " + Parlor.dollars(parlor.rules().minStake())
                            + ": the biggest stake can't be under it."));
                    return null;
                }
                parlor.maxStake(dollars);
                sender.sendMessage(Text.of("&aThe biggest stake in the Game Parlor is &6" + Parlor.dollars(dollars)
                        + "&a now."));
                return "parlor stake " + dollars;
            }
            case "payout" -> {
                int dollars = whole(value, 1, Parlor.PAYOUT_CEILING);
                if (dollars < 0) {
                    return usage(sender, "payout <dollars 1-" + Parlor.PAYOUT_CEILING + ">");
                }
                parlor.maxPayout(dollars);
                sender.sendMessage(Text.of("&aThe most one play gives back in the Game Parlor is &6"
                        + Parlor.dollars(dollars) + "&a now. &7Each game works out again what it pays under it."));
                return "parlor payout " + dollars;
            }
            case "chips" -> {
                List<Integer> chips = new ArrayList<>();
                for (int i = 1; i < rest.length; i++) {
                    int chip = whole(rest[i], 1, Parlor.STAKE_CEILING);
                    if (chip < 0 || chips.contains(chip)) {
                        return usage(sender, "chips <a> [b] [c]  (whole dollars, each different)");
                    }
                    chips.add(chip);
                }
                if (chips.isEmpty() || chips.size() > Parlor.MAX_CHIPS) {
                    return usage(sender, "chips <a> [b] [c]  (one to " + Parlor.MAX_CHIPS + " chips)");
                }
                int smallest = chips.stream().mapToInt(Integer::intValue).min().orElse(1);
                if (smallest > parlor.rules().maxStake()) {
                    sender.sendMessage(Text.of("&cThe biggest stake is " + Parlor.dollars(parlor.rules().maxStake())
                            + ": one chip has to fit under it."));
                    return null;
                }
                parlor.chips(chips);
                sender.sendMessage(Text.of("&aThe Game Parlor's chips are &6" + chipsText(parlor.rules().chips())
                        + "&a now."));
                return "parlor chips " + chipsText(parlor.rules().chips());
            }
            default -> {
                for (String line : help()) {
                    sender.sendMessage(Text.of(line));
                }
                return null;
            }
        }
    }

    private static String limit(Parlor parlor, CommandSender sender, String[] rest) {
        if (rest.length == 2) {
            int dollars = amount(rest[1]);
            if (dollars == BAD) {
                return usage(sender, "limit <dollars|off>   or   limit <player> <dollars|off|default>");
            }
            parlor.limit(dollars);
            sender.sendMessage(Text.of(dollars == Parlor.NO_LIMIT
                    ? "&aThere is no day limit on money in the Game Parlor now. &7(A player's own limit still holds.)"
                    : "&aThe most a player may put into the Game Parlor's games is &6" + Parlor.dollars(dollars)
                    + " &aa day now."));
            return "parlor limit " + (dollars == Parlor.NO_LIMIT ? "off" : dollars);
        }
        if (rest.length != 3) {
            return usage(sender, "limit <dollars|off>   or   limit <player> <dollars|off|default>");
        }
        OfflinePlayer who = Bukkit.getOfflinePlayerIfCached(rest[1]);
        if (who == null || !(who.hasPlayedBefore() || who.isOnline())) {
            sender.sendMessage(Text.of("&cNo player called " + rest[1] + " has played here."));
            return null;
        }
        String name = who.getName() == null ? rest[1] : who.getName();
        boolean follow = rest[2].equalsIgnoreCase("default");
        int dollars = follow ? 0 : amount(rest[2]);
        if (dollars == BAD) {
            return usage(sender, "limit <player> <dollars|off|default>");
        }
        if (!parlor.limit(who.getUniqueId(), follow ? null : dollars)) {
            sender.sendMessage(Text.of("&cThat couldn't be kept right now - check the console."));
            return null;
        }
        sender.sendMessage(Text.of(follow ? "&f" + name + " &afollows everyone's day limit again."
                : dollars == Parlor.NO_LIMIT ? "&f" + name + " &ahas no day limit on money in the Game Parlor now."
                : "&aThe most &f" + name + " &amay put into the Game Parlor's games is &6" + Parlor.dollars(dollars)
                + " &aa day now."));
        return "parlor limit " + name + " " + (follow ? "default" : dollars == Parlor.NO_LIMIT ? "off" : dollars);
    }

    /** What is set, in an admin's words. */
    static List<String> status(HomeCraftManagement plugin, GamesService games) {
        Parlor.Rules r = games.parlor().rules();
        List<String> out = new ArrayList<>();
        out.add("&6The Game Parlor");
        out.add("&7Games of chance only in the Parlor: " + onOff(r.onlyHere())
                + (r.onlyHere() && !games.parlorOpen() ? " &8(no Parlor right now, so they are everywhere)" : ""));
        out.add("&7Playing for money: " + onOff(r.money()) + " &8(needs " + Parlor.PERMISSION_MONEY + ")"
                + (plugin.economy() == null || !plugin.economy().isEnabled() ? " &c- no economy is connected" : ""));
        out.add("&7Chips: &f" + chipsText(r.chips()) + " &7· biggest stake: &f" + Parlor.dollars(r.maxStake())
                + " &7· most one play gives back: &f" + Parlor.dollars(r.maxPayout()));
        out.add("&7A day: &f" + (r.limit() == Parlor.NO_LIMIT ? "no limit" : Parlor.dollars(r.limit()) + " a player"));
        try {
            int[] today = games.dao().moneyTotals(plugin.clock().dayKey());
            out.add("&7Today: &f" + Parlor.dollars(today[0]) + " &7put in, &f" + Parlor.dollars(today[1])
                    + " &7given back.");
        } catch (SQLException e) {
            out.add("&7Today: &8couldn't be read");
        }
        out.add("&8/hcm games parlor only|money|limit|stake|payout|chips");
        return out;
    }

    static String chipsText(List<Integer> chips) {
        List<String> parts = new ArrayList<>();
        for (int c : chips) {
            parts.add(Parlor.dollars(c));
        }
        return String.join(" ", parts);
    }

    /** Tab completion; {@code rest} is what follows {@code parlor} (the last one being typed). */
    static List<String> tab(String[] rest) {
        if (rest.length <= 1) {
            return VERBS;
        }
        if (rest.length == 2) {
            return switch (rest[0].toLowerCase(Locale.ROOT)) {
                case "only", "money" -> List.of("on", "off");
                case "limit" -> List.of("off", "100", "250", "500", "1000");
                case "stake" -> List.of("25", "50", "100");
                case "payout" -> List.of("250", "500", "1000");
                case "chips" -> List.of("5", "1");
                default -> List.of();
            };
        }
        if (rest.length == 3 && rest[0].equalsIgnoreCase("limit")) {
            return List.of("default", "off", "100", "250", "500");
        }
        if (rest[0].equalsIgnoreCase("chips") && rest.length <= 1 + Parlor.MAX_CHIPS) {
            return List.of("10", "25", "50", "100");
        }
        return List.of();
    }

    private static final int BAD = Integer.MIN_VALUE;

    /** Whole dollars from 0 up, "off" for no limit, or {@link #BAD}. */
    private static int amount(String text) {
        if (text.equalsIgnoreCase("off") || text.equalsIgnoreCase("none")) {
            return Parlor.NO_LIMIT;
        }
        int v = whole(text, 0, 1_000_000);
        return v < 0 ? BAD : v;
    }

    /** A whole number from {@code min} to {@code max}, or -1. */
    private static int whole(String text, int min, int max) {
        try {
            int v = Integer.parseInt(text.startsWith("$") ? text.substring(1) : text);
            return v < min || v > max ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean onOff(String s) {
        return s.equals("on") || s.equals("off");
    }

    private static String onOff(boolean on) {
        return on ? "&aon" : "&coff";
    }

    private static String usage(CommandSender sender, String what) {
        sender.sendMessage(Text.of("&cUsage: /hcm games parlor " + what));
        return null;
    }
}
