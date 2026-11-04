package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyService;
import com.dierks.homecraft.command.HcmCommand.CandyState;
import com.dierks.homecraft.config.CandyConfig;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.Whereabouts;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@code /hcm harvest} (0.38.6): the Harvest Basket's twin of {@code /hcm candy}. With no word: the player's count,
 * their Pilgrim Hat and the next shower, and a hat they are owed is tried again. The admin words:
 * {@code now [player]} a shower now for every eligible player (or the named one), one line each with the reason
 * anyone was skipped; {@code status}; {@code clear} removes every live treat, counting nothing; {@code hat <player>}
 * gives the hat back and sets its mark (the restore); {@code add <player> <n>} adds to a count, then the goal check,
 * to test the hat without collecting 26.
 *
 * <p>The same order as the candy command: the permission and the words are checked before anything is read, so a
 * refusal touches nothing (and needs no plugin). It reuses {@link HcmCommand}'s package statics that take a
 * {@link CandyConfig.Candy} for the window, the schedule and the times; its words are its own. The candy command
 * itself is left exactly as it was.
 */
public final class HarvestCommand {

    /** {@code /hcm harvest}'s admin words (hcm.admin). With no word it is the player's own count (hcm.arcade.use). */
    static final List<String> VERBS = List.of("now", "status", "clear", "hat", "add");
    static final String USAGE = "Usage: /hcm harvest [now [player]|status|clear|hat <player>|add <player> <n>]";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US);

    private HarvestCommand() {
    }

    /** {@code /hcm harvest ...}: {@code args[0]} is "harvest". */
    static void handle(HomeCraftManagement plugin, CommandSender sender, String[] args) {
        String verb = verb(args);
        if (verb == null) {
            if (!deny(sender, "hcm.arcade.use")) {
                sender.sendMessage(Text.of("&c" + (sender.hasPermission("hcm.admin") ? USAGE : "Usage: /hcm harvest")));
            }
            return;
        }
        if (deny(sender, permission(verb))) {
            return;
        }
        String wrong = argsError(verb, args);
        if (wrong != null) {
            sender.sendMessage(Text.of("&c" + wrong));
            return;
        }
        if (verb.isEmpty() && !(sender instanceof Player)) {
            sender.sendMessage(Text.of("&cOnly players collect treats. &7" + USAGE));
            return;
        }
        CandyService harvest = plugin.harvest();
        if (harvest == null) {
            sender.sendMessage(Text.of("&cThe Harvest Basket is not running."));
            return;
        }
        switch (verb) {
            case "now" -> now(plugin, sender, args, harvest);
            case "status" -> status(plugin, sender, harvest);
            case "clear" -> {
                int n = harvest.clear();
                sender.sendMessage(Text.of("&aCleared " + n + " treat" + (n == 1 ? "" : "s")
                        + ". &7Nothing was counted."));
            }
            case "hat" -> hat(plugin, sender, args[2], harvest);
            case "add" -> add(plugin, sender, args[2], HcmCommand.candyAddAmount(args[3]), harvest);
            default -> mine(plugin, (Player) sender, harvest);
        }
    }

    // ---- the words -----------------------------------------------------------------------

    /** The player's count, their hat, and when the next shower is. An owed hat is tried again. */
    private static void mine(HomeCraftManagement plugin, Player player, CandyService harvest) {
        CandyConfig.Candy c = harvest.settings();
        AchievementDao dao = new AchievementDao(plugin.database());
        UUID id = player.getUniqueId();
        long found;
        boolean hat;
        boolean away = false;
        try {
            found = dao.counter(id, harvest.profile().counterKey(c.year()));
            hat = dao.counter(id, harvest.profile().rewardKey(c.year())) > 0;
            if (found >= c.goal() && !hat) {
                // Owed (no room, or back from a game since): tried again now, even after the window.
                away = harvest.tryBag(player) == CandyService.BagResult.WAITING;
                hat = dao.counter(id, harvest.profile().rewardKey(c.year())) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read " + player.getName() + "'s treats: " + e.getMessage());
            player.sendMessage(Text.of("&cYour treats couldn't be read right now. Try again in a moment."));
            return;
        }
        CandyState state = state(plugin, c);
        String next = null;
        if (state == CandyState.OPEN) {
            LocalDateTime now = localNow(plugin);
            LocalDateTime at = HcmCommand.nextShower(now, c, held(plugin));
            next = at == null ? null : HcmCommand.showerWhen(now, at);
        }
        for (String line : mineLines(state, c.from(), found, c.goal(), hat, next, away)) {
            player.sendMessage(Text.of(line));
        }
    }

    /** A shower now for every eligible player, or the named one, with the reason anyone was skipped. */
    private static void now(HomeCraftManagement plugin, CommandSender sender, String[] args, CandyService harvest) {
        CandyConfig.Candy c = harvest.settings();
        String closed = closed(state(plugin, c), c);
        if (closed == null) {
            closed = harvest.nowRefusal(); // open, but its task isn't running
        }
        if (closed != null) {
            sender.sendMessage(Text.of("&c" + closed));
            return;
        }
        List<Player> who = new ArrayList<>();
        if (args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Text.of("&cPlayer '" + args[2] + "' is not online."));
                return;
            }
            who.add(target);
        } else {
            who.addAll(Bukkit.getOnlinePlayers());
            who.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        }
        if (who.isEmpty()) {
            sender.sendMessage(Text.of("&7Nobody is online."));
            return;
        }
        int showered = 0;
        for (Player p : who) {
            String why = harvest.showerNow(p);
            if (why == null) {
                showered++;
            }
            sender.sendMessage(Text.of(HcmCommand.candyNowLine(p.getName(), why)));
        }
        if (who.size() > 1) {
            sender.sendMessage(Text.of("&7" + showered + " of " + who.size() + " showered."));
        }
    }

    /** On or off; the window and the schedule; the next shower; live treats; each online player's line. */
    private static void status(HomeCraftManagement plugin, CommandSender sender, CandyService harvest) {
        CandyConfig.Candy c = harvest.settings();
        ZoneId zone = plugin.clock().zone();
        CandyState state = state(plugin, c);
        sender.sendMessage(Text.of("&6Harvest Basket " + c.year() + " &7- " + stateText(state)));
        sender.sendMessage(Text.of("&7" + HcmCommand.candyWindowText(c, zone)));
        sender.sendMessage(Text.of("&7Next shower " + HcmCommand.candyNextText(state, localNow(plugin), c,
                held(plugin))));
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        Map<UUID, Integer> live = harvest.liveByPlayer();
        sender.sendMessage(Text.of("&7Live treats: &f" + harvest.liveTotal() + " &7(at most "
                + CandyRules.LIVE_PER_PLAYER + " a player, " + CandyRules.LIVE_ON_SERVER + " on the server)"));
        if (online.isEmpty()) {
            sender.sendMessage(Text.of("&7Nobody is online."));
        }
        AchievementDao dao = new AchievementDao(plugin.database());
        for (Player p : online) {
            long found = -1;
            boolean hat = false;
            try {
                found = dao.counter(p.getUniqueId(), harvest.profile().counterKey(c.year()));
                hat = dao.counter(p.getUniqueId(), harvest.profile().rewardKey(c.year())) > 0;
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to read " + p.getName() + "'s treats: " + e.getMessage());
            }
            sender.sendMessage(Text.of(playerLine(p.getName(), found, c.goal(), hat,
                    live.getOrDefault(p.getUniqueId(), 0), CandyRules.idleMinutes(p.getIdleDuration().toMillis()))));
        }
    }

    /** {@code hat <player>}: the Pilgrim Hat given back free and marked (room needed, home from every game). */
    private static void hat(HomeCraftManagement plugin, CommandSender sender, String name, CandyService harvest) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer '" + name + "' is not online."));
            return;
        }
        if (!Whereabouts.homeFromGames(plugin, target)) {
            sender.sendMessage(Text.of("&c" + target.getName() + " is in a game. Try again when they're back."));
            return;
        }
        if (target.isDead()) {
            sender.sendMessage(Text.of("&c" + target.getName() + " is respawning. Try again in a moment."));
            return;
        }
        String refused = harvest.giveBag(target);
        if (refused != null) {
            sender.sendMessage(Text.of("&c" + refused));
            return;
        }
        int year = harvest.settings().year();
        plugin.getLogger().info("Admin " + sender.getName() + " gave a Pilgrim Hat " + year + " to " + target.getName());
        sender.sendMessage(Text.of("&aGave the Pilgrim Hat " + year + " to " + target.getName() + "."));
    }

    /** {@code add <player> <n>}: n more on the player's count, then the goal check. */
    private static void add(HomeCraftManagement plugin, CommandSender sender, String name, int n,
                            CandyService harvest) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer '" + name + "' is not online."));
            return;
        }
        CandyService.Added added = harvest.add(target, n);
        if (added == null) {
            sender.sendMessage(Text.of("&cThat couldn't be saved. See the console."));
            return;
        }
        plugin.getLogger().info("Admin " + sender.getName() + " added " + n + " treats for " + target.getName());
        sender.sendMessage(Text.of("&aAdded " + n + " treat" + (n == 1 ? "" : "s") + " for " + target.getName()
                + ": &f" + added.total() + " &7/ " + harvest.settings().goal()));
        String hatLine = addHatLine(target.getName(), added.bag());
        if (hatLine != null) {
            sender.sendMessage(Text.of(hatLine));
        }
    }

    // ---- the plugin's facts ---------------------------------------------------------------

    private static CandyState state(HomeCraftManagement plugin, CandyConfig.Candy c) {
        return HcmCommand.candyState(c, plugin.config().arcade().enabled(), plugin.clock().today());
    }

    private static LocalDateTime localNow(HomeCraftManagement plugin) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(plugin.clock().nowMillis()), plugin.clock().zone());
    }

    /** The restart hold at a shower's start: the Games' own, or the same settings read without them. */
    private static Predicate<LocalDateTime> held(HomeCraftManagement plugin) {
        ZoneId zone = plugin.clock().zone();
        com.dierks.homecraft.games.RestartHold hold;
        if (plugin.games() != null) {
            hold = plugin.games().restartHold();
        } else {
            com.dierks.homecraft.config.GamesConfig.Common common = plugin.config().games().common();
            hold = new com.dierks.homecraft.games.RestartHold(common.restartTimes(), zone, common.restartHoldMinutes());
        }
        return t -> CandyRules.heldByRestart(CandyRules.slotId(t.toLocalDate(), t.getHour() * 60 + t.getMinute()),
                zone, hold);
    }

    private static boolean deny(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        sender.sendMessage(Text.of("&cYou don't have permission."));
        return true;
    }

    // ---- pure: the words and what they say -----------------------------------------------

    /** The word, lower case: "" for none, null for one it doesn't know. */
    static String verb(String[] args) {
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            return "";
        }
        String w = args[1].trim().toLowerCase(Locale.ROOT);
        return VERBS.contains(w) ? w : null;
    }

    /** Anyone with the Arcade for their own count, admins for the rest (no new nodes). */
    static String permission(String verb) {
        return verb == null || verb.isEmpty() ? "hcm.arcade.use" : "hcm.admin";
    }

    /** What is wrong with the words after {@code /hcm harvest <verb>}, or null when they will do. */
    static String argsError(String verb, String[] args) {
        if ("hat".equals(verb) && args.length < 3) {
            return "Usage: /hcm harvest hat <player>";
        }
        if ("add".equals(verb)) {
            if (args.length < 4) {
                return "Usage: /hcm harvest add <player> <n>";
            }
            if (HcmCommand.candyAddAmount(args[3]) < 0) {
                return "Add 1-" + HcmCommand.CANDY_ADD_MAX + " treats: /hcm harvest add <player> <n>";
            }
        }
        return null;
    }

    /** Why {@code now} refuses, or null when the basket is open. */
    static String closed(CandyState state, CandyConfig.Candy c) {
        return switch (state) {
            case OPEN -> null;
            case NOT_YET -> "The Harvest Basket starts on " + DAY.format(c.from()) + ".";
            case OVER -> "The Harvest Basket is over for this year.";
            case OFF -> "The Harvest Basket is switched off in config.yml (" + CandyConfig.HARVEST_PATH + ").";
            case ARCADE_OFF -> "The Arcade is switched off (arcade.enabled: false), and the Harvest Basket with it.";
        };
    }

    /** The status's headline word for the state. */
    static String stateText(CandyState state) {
        return switch (state) {
            case OPEN -> "&aon";
            case NOT_YET -> "&enot yet";
            case OVER -> "&7over";
            case OFF -> "&coff &7(" + CandyConfig.HARVEST_PATH + ")";
            case ARCADE_OFF -> "&coff &7(arcade.enabled: false)";
        };
    }

    /** One player's line in the status: their count, their treats out, idle minutes and the hat. */
    static String playerLine(String name, long found, int goal, boolean hat, int live, long idleMinutes) {
        String state = hat ? "&agiven" : found >= goal ? "&6owed" : "&7not yet";
        return "&e" + name + " &7- found &f" + (found < 0 ? "?" : String.valueOf(found)) + "&7/" + goal + ", &f" + live
                + " &7out, idle &f" + idleMinutes + " &7min, hat: " + state;
    }

    /** What the goal check after {@code add} did, for the admin; null when there is nothing to say. */
    static String addHatLine(String name, CandyService.BagResult result) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case GIVEN -> "&a" + name + " got their Pilgrim Hat.";
            case WAITING -> "&7Their Pilgrim Hat is waiting: " + name + " is in a game or another world.";
            case FULL -> "&7Their Pilgrim Hat is waiting: " + name + "'s bag is full.";
            case FAILED -> "&cTheir Pilgrim Hat couldn't be given. See the console.";
            case HAS_IT, NOT_YET -> null;
        };
    }

    /**
     * What {@code /hcm harvest} tells a player: their count and the hat, then the next shower ({@code next}; null
     * when none is left) while the basket is open, or why there is none. {@code away}: an owed hat waits for the
     * player to come back from a game.
     */
    static List<String> mineLines(CandyState state, LocalDate from, long found, int goal, boolean hat, String next,
                                  boolean away) {
        List<String> out = new ArrayList<>();
        switch (state) {
            case NOT_YET -> out.add("&6Treats start falling on " + DAY.format(from) + "!");
            case OVER -> {
                out.add("&7The Harvest Basket is over for this year.");
                if (found > 0 || hat) {
                    out.add(found(found, goal, hat, false, away));
                }
            }
            case OPEN -> out.add(found(found, goal, hat, true, away)
                    + (next == null ? " &7That was the last shower this year!" : " &7Next shower " + next + "."));
            default -> {
                out.add(found(found, goal, hat, true, away));
                out.add("&7No treats are falling right now.");
            }
        }
        return out;
    }

    /** "You've found 17 treats. 9 more for the Pilgrim Hat!", or the hat got or waiting. */
    static String found(long found, int goal, boolean hat, boolean stillFalling, boolean away) {
        String head = (stillFalling ? "&6You've found &f" : "&6You found &f") + found + " &6treat"
                + (found == 1 ? "" : "s") + ".";
        if (hat) {
            return head + " &aYou got your Pilgrim Hat!";
        }
        if (found >= goal) {
            return head + (away ? " &eYour Pilgrim Hat is waiting: it comes when you're back from the Games or"
                    + " another world!" : " &eYour Pilgrim Hat is waiting: make room on your head or in your bag!");
        }
        return stillFalling ? head + " &f" + (goal - found) + " &6more for the Pilgrim Hat!" : head;
    }
}
