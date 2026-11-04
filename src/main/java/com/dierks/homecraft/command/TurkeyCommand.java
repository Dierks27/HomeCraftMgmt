package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.turkey.TurkeyRules;
import com.dierks.homecraft.arcade.turkey.TurkeyService;
import com.dierks.homecraft.config.TurkeyConfig;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /hcm turkey} (0.38.6). With no word: how many turkeys you caught today and when the next one comes. The
 * admin words: {@code now [player]} a turkey now for every player (or the named one), one line each with the
 * reason a player got none; {@code status}; {@code clear} removes every turkey.
 *
 * <p>Outside the window (or with the switch off) {@code now} is a test run: the turkey and the catch moment, but no
 * claim, no feather, no tokens and no welcome mark, so trying it out never spends anyone's real catch.
 *
 * <p>The permission and the word are checked before the plugin is touched, so a refusal reads nothing; the
 * {@code HcmCommand} route hands every {@code turkey} word here.
 */
public final class TurkeyCommand {

    /** The admin words (hcm.admin). With no word it is the player's own line (hcm.arcade.use). */
    static final List<String> VERBS = List.of("now", "status", "clear");
    static final String USAGE = "Usage: /hcm turkey [now [player]|status|clear]";
    static final String DENIED = "&cYou don't have permission.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    /** Where Catch the Turkey stands today. */
    enum State { OPEN, NOT_YET, OVER, OFF, ARCADE_OFF }

    private TurkeyCommand() {
    }

    /** {@code /hcm turkey [word ...]}: {@code args[0]} is "turkey". */
    public static void handle(HomeCraftManagement plugin, CommandSender sender, String[] args) {
        String verb = verb(args);
        if (verb == null) {
            if (!deny(sender, "hcm.arcade.use")) {
                sender.sendMessage(Text.of("&c" + (sender.hasPermission("hcm.admin") ? USAGE : "Usage: /hcm turkey")));
            }
            return;
        }
        if (deny(sender, permission(verb))) {
            return;
        }
        if (verb.isEmpty() && !(sender instanceof Player)) {
            sender.sendMessage(Text.of("&cOnly players catch turkeys. &7" + USAGE));
            return;
        }
        TurkeyService turkey = plugin.turkey();
        if (turkey == null) {
            sender.sendMessage(Text.of("&cCatch the Turkey is not running."));
            return;
        }
        switch (verb) {
            case "now" -> now(plugin, sender, args, turkey);
            case "status" -> status(plugin, sender, turkey);
            case "clear" -> {
                int n = turkey.clear();
                plugin.getLogger().info("Admin " + sender.getName() + " cleared " + n + " turkey" + (n == 1 ? "" : "s"));
                sender.sendMessage(Text.of("&aCleared " + n + " turkey" + (n == 1 ? "" : "s") + "."));
            }
            default -> mine(plugin, (Player) sender, turkey);
        }
    }

    /** {@code /hcm turkey}: caught today, and the next turkey. */
    private static void mine(HomeCraftManagement plugin, Player player, TurkeyService turkey) {
        TurkeyConfig.Turkey c = turkey.settings();
        State state = state(c, plugin.config().arcade().enabled(), plugin.clock().today());
        String next = null;
        if (state == State.OPEN) {
            LocalDateTime at = turkey.nextRun();
            next = at == null ? null : when(localNow(plugin), at);
        }
        for (String line : mineLines(state, c.from(), turkey.caughtToday(player.getUniqueId()), next)) {
            player.sendMessage(Text.of(line));
        }
    }

    /** {@code /hcm turkey now [player]}: a turkey now for every player, or the named one. */
    private static void now(HomeCraftManagement plugin, CommandSender sender, String[] args, TurkeyService turkey) {
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
        TurkeyService.NowResult result = turkey.runNow(who);
        plugin.getLogger().info("Admin " + sender.getName() + " started turkey run " + result.runId()
                + (result.test() ? " (a test run)" : "") + " for " + who.size() + " player" + (who.size() == 1 ? "" : "s"));
        if (result.test()) {
            sender.sendMessage(Text.of("&7" + TEST_LINE));
        }
        int got = 0;
        for (Map.Entry<String, String> e : result.skipped().entrySet()) {
            if (e.getValue() == null) {
                got++;
            }
            sender.sendMessage(Text.of(nowLine(e.getKey(), e.getValue())));
        }
        if (who.size() > 1) {
            sender.sendMessage(Text.of("&7" + got + " of " + who.size() + " got a turkey."));
        }
    }

    /** What an admin is told before a test run's lines. */
    static final String TEST_LINE = "Test run (outside the window): the turkey and the moment only. No claim, "
            + "no feather, no tokens.";

    /** {@code /hcm turkey status}: on or off, the window, the next run, and each run going on. */
    private static void status(HomeCraftManagement plugin, CommandSender sender, TurkeyService turkey) {
        TurkeyConfig.Turkey c = turkey.settings();
        ZoneId zone = plugin.clock().zone();
        State state = state(c, plugin.config().arcade().enabled(), plugin.clock().today());
        sender.sendMessage(Text.of("&6Catch the Turkey " + c.year() + " &7- " + stateText(state)));
        sender.sendMessage(Text.of("&7" + windowText(c, zone)));
        LocalDateTime next = state == State.OPEN || state == State.NOT_YET ? turkey.nextRun() : null;
        sender.sendMessage(Text.of("&7Next run &f" + (next == null ? "- none" : when(localNow(plugin), next))));
        List<TurkeyService.RunView> runs = turkey.runsNow();
        if (runs.isEmpty()) {
            sender.sendMessage(Text.of("&7No turkeys out."));
        }
        for (TurkeyService.RunView v : runs) {
            sender.sendMessage(Text.of(runLine(v.id(), v.welcome(), v.test(), v.secondsLeft(), v.turkeys(), v.catchers())));
        }
    }

    // ---- the words (pure) -------------------------------------------------------------------

    /** The word, lower case: "" for none, null for one it doesn't know. */
    static String verb(String[] args) {
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            return "";
        }
        String w = args[1].trim().toLowerCase(Locale.ROOT);
        return VERBS.contains(w) ? w : null;
    }

    /** Who may use the word: anyone with the Arcade for their own line, admins for the rest (no new nodes). */
    static String permission(String verb) {
        return verb == null || verb.isEmpty() ? "hcm.arcade.use" : "hcm.admin";
    }

    private static boolean deny(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        sender.sendMessage(Text.of(DENIED));
        return true;
    }

    /** Where it stands on {@code today}: over, not yet, switched off (or a bad window), then the Arcade's switch. */
    static State state(TurkeyConfig.Turkey c, boolean arcadeOn, LocalDate today) {
        boolean window = !c.from().isAfter(c.to());
        if (window && today.isAfter(c.to())) {
            return State.OVER;
        }
        if (window && today.isBefore(c.from())) {
            return State.NOT_YET;
        }
        if (!c.enabled() || !window) {
            return State.OFF;
        }
        return arcadeOn ? State.OPEN : State.ARCADE_OFF;
    }

    /** The status's headline word. */
    static String stateText(State state) {
        return switch (state) {
            case OPEN -> "&aon";
            case NOT_YET -> "&enot yet &7(now is a test run)";
            case OVER -> "&7over &7(now is a test run)";
            case OFF -> "&coff &7(arcade.events.turkey; now is a test run)";
            case ARCADE_OFF -> "&coff &7(arcade.enabled: false; now is a test run)";
        };
    }

    /** The window and the schedule, with the zone the dates are read in. */
    static String windowText(TurkeyConfig.Turkey c, ZoneId zone) {
        String window = c.from().getYear() == c.to().getYear()
                ? DAY.format(c.from()) + " - " + DAY.format(c.to()) + " " + c.to().getYear()
                : DAY.format(c.from()) + " " + c.from().getYear() + " - " + DAY.format(c.to()) + " " + c.to().getYear();
        int first = CandyRules.offsetOn(c.offsetMinutes(), c.everyMinutes());
        return "Window: &f" + window + " &7(" + (zone == null || zone.equals(ZoneOffset.UTC) ? "UTC" : zone.getId())
                + "). A run every &f" + c.everyMinutes() + " &7min from &f"
                + String.format(Locale.ROOT, "%02d:%02d", first / 60, first % 60) + "&7, &f" + c.runMinutes()
                + " &7min each" + (c.welcome() ? ", and a welcome turkey" : "") + ".";
    }

    /** "at 4:50 PM", "tomorrow at 12:50 AM" or "on 22 Nov at 12:50 AM", seen from {@code now}. */
    static String when(LocalDateTime now, LocalDateTime at) {
        String clock = CLOCK.format(at);
        LocalDate day = at.toLocalDate();
        if (day.equals(now.toLocalDate())) {
            return "at " + clock;
        }
        if (day.equals(now.toLocalDate().plusDays(1))) {
            return "tomorrow at " + clock;
        }
        return "on " + DAY.format(day) + " at " + clock;
    }

    /** One line of {@code now}: "turkey", or the reason the player got none. */
    static String nowLine(String name, String why) {
        return why == null ? "&a" + name + " &7- turkey" : "&e" + name + " &7- " + why;
    }

    /** One run in the status: its id and kind, the seconds left, the turkeys out (and the cap), who caught one. */
    static String runLine(String id, boolean welcome, boolean test, long secondsLeft, int turkeys, List<String> catchers) {
        return "&7Run &f" + id + (welcome ? " &7(welcome)" : "") + (test ? " &7(test)" : "") + " &7- &f" + secondsLeft
                + "s &7left, &f" + turkeys + " &7out" + (turkeys >= TurkeyRules.MAX_TURKEYS_PER_RUN ? " &c(cap)" : "")
                + ", caught by: &f" + (catchers.isEmpty() ? "nobody yet" : String.join(", ", catchers));
    }

    /** What {@code /hcm turkey} tells a player, by the state: today's catches and the next turkey, or why none. */
    static List<String> mineLines(State state, LocalDate from, int caughtToday, String next) {
        List<String> out = new ArrayList<>();
        switch (state) {
            case NOT_YET -> out.add("&6Turkeys come on " + DAY.format(from) + "! Tap one to catch it.");
            case OVER -> out.add("&7Catch the Turkey is over for this year.");
            case OPEN -> out.add("&6Turkeys caught today: &f" + caughtToday
                    + (next == null ? " &7That was the last turkey this year!" : " &7Next turkey " + next + "."));
            default -> out.add("&7No turkeys right now.");
        }
        return out;
    }

    private static LocalDateTime localNow(HomeCraftManagement plugin) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(plugin.clock().nowMillis()), plugin.clock().zone());
    }
}
