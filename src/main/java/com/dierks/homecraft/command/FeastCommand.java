package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.arcade.feast.FeastService;
import com.dierks.homecraft.config.FeastConfig;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.storage.FeastDao;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /hcm feast} (0.38.6): the Family Feast. With no word: the family's line and the player's own gifts. The
 * admin words: {@code pot} makes the block they look at the Feast Pot ({@code pot clear} removes it); {@code status}
 * the table, the helpers, the markers, the pot and the edition; {@code test} plays the fill's moment and the show over
 * the pot for them (and the Clubhouse's ceremony when they are in it), with no marker and no pay; {@code reset
 * confirm} wipes the configured edition, refused for a year's edition once anyone was paid.
 *
 * <p>As the candy and harvest commands: the permission and the words are checked before anything is read, so a
 * refusal touches nothing (and needs no plugin).
 */
public final class FeastCommand {

    /** {@code /hcm feast}'s admin words (hcm.admin). With no word it is the family's line (hcm.arcade.use). */
    static final List<String> VERBS = List.of("pot", "status", "test", "reset");
    static final String USAGE = "Usage: /hcm feast [pot [clear]|status|test|reset confirm]";

    private FeastCommand() {
    }

    /** {@code /hcm feast ...}: {@code args[0]} is "feast". */
    static void handle(HomeCraftManagement plugin, CommandSender sender, String[] args) {
        String verb = verb(args);
        if (verb == null) {
            if (!deny(sender, "hcm.arcade.use")) {
                sender.sendMessage(Text.of("&c" + (sender.hasPermission("hcm.admin") ? USAGE : "Usage: /hcm feast")));
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
        if ((verb.isEmpty() || verb.equals("test") || (verb.equals("pot") && args.length < 3))
                && !(sender instanceof Player)) {
            sender.sendMessage(Text.of("&cOnly a player can do that. &7" + USAGE));
            return;
        }
        FeastService feast = plugin.feast();
        if (feast == null) {
            sender.sendMessage(Text.of("&cThe Family Feast is not running."));
            return;
        }
        switch (verb) {
            case "pot" -> sender.sendMessage(Text.of(args.length >= 3 ? feast.clearPot() : feast.setPot((Player) sender)));
            case "status" -> {
                for (String line : status(plugin, feast)) {
                    sender.sendMessage(Text.of(line));
                }
            }
            case "test" -> test(plugin, (Player) sender, feast);
            case "reset" -> reset(plugin, sender, feast);
            default -> mine(plugin, (Player) sender, feast);
        }
    }

    // ---- the words -----------------------------------------------------------------------

    /** The family's line and bar, and the player's own gifts. */
    private static void mine(HomeCraftManagement plugin, Player player, FeastService feast) {
        FeastRules.FeastView v = feast.view();
        FeastConfig.Feast c = feast.settings();
        long mine;
        try {
            mine = new GamesDao(plugin.database()).best(player.getUniqueId(), FeastRules.GAME,
                    FeastRules.board(c.edition())) instanceof Long l ? l : 0L;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read " + player.getName() + "'s Feast gifts: " + e.getMessage());
            player.sendMessage(Text.of("&cYour gifts couldn't be read right now. Try again in a moment."));
            return;
        }
        for (String line : mineLines(v, mine, c, plugin.clock().today().isAfter(c.to()))) {
            player.sendMessage(Text.of(line));
        }
    }

    /** {@code status}: the table, the helpers, the markers, the pot, the edition, and the Clubhouse. */
    static List<String> status(HomeCraftManagement plugin, FeastService feast) {
        FeastConfig.Feast c = feast.settings();
        FeastRules.FeastView v = feast.view();
        List<String> out = new ArrayList<>();
        out.add("&6Family Feast &7- edition &f" + c.edition() + "&7, " + (v.open() ? "&aopen" : "&cclosed")
                + " &7(" + c.from() + " to " + c.to() + (c.enabled() ? "" : ", switched off") + ")");
        out.add("&7Table: &f" + v.total() + " / " + v.goal() + " &7(" + v.plates() + " of " + FeastRules.CELLS
                + " plates), " + v.helpers() + " helper" + (v.helpers() == 1 ? "" : "s")
                + (c.dailyPointsPerPlayer() > 0 ? ", at most " + c.dailyPointsPerPlayer() + " a day each" : ""));
        List<String> names = new ArrayList<>();
        for (UUID id : feast.helpers()) {
            String n = feast.name(id);
            names.add(n == null ? id.toString().substring(0, 8) : n);
        }
        if (!names.isEmpty()) {
            out.add("&7Helpers (first gift first): &f" + String.join(", ", names));
        }
        out.add("&7Full: " + (v.done() ? "&ayes" : "&fno") + "&7, show over the pot: "
                + (feast.homeShown() ? "&agiven" : "&fnot yet") + "&7, reward " + c.tokens() + " tokens each"
                + (c.creativeHelpersPaid() ? "" : " (not for Creative-only helpers)"));
        String mini = FeastRules.miniId(c.edition());
        out.add("&7Turkey Mini: " + (mini == null ? "none for a test edition" : mini + ", one for each helper"));
        FeastRules.Spot pot = feast.pot();
        if (pot == null) {
            out.add("&7Pot: &cnot set &7(look at a block and /hcm feast pot)");
        } else {
            Boolean standing = feast.potStanding();
            out.add("&7Pot: &f" + pot.world() + " " + pot.x() + ", " + pot.y() + ", " + pot.z()
                    + (standing == null ? " &7(not loaded)" : standing ? "" : " &c- pot: missing"));
        }
        boolean club = plugin.games() != null && Clubhouse.offered(plugin.games());
        out.add("&7Clubhouse tables: " + (club ? "&aopen" : "&fnot open (the progress shows at the pot only)"));
        return out;
    }

    /** {@code test}: the fill's moment and the show over the pot for the admin, and the room's ceremony if inside. */
    private static void test(HomeCraftManagement plugin, Player player, FeastService feast) {
        feast.test(player);
        boolean room = plugin.games() != null && Clubhouse.feastTest(plugin.games(), player);
        player.sendMessage(Text.of("&aPlayed the Feast's moment" + (feast.pot() == null ? "" : " and the show over "
                + "the pot") + (room ? ", and the Clubhouse's ceremony" : "") + ". &7No marker, no pay."));
    }

    /** {@code reset confirm}: wipe the configured edition (refused for a year's once it filled or gave anything). */
    private static void reset(HomeCraftManagement plugin, CommandSender sender, FeastService feast) {
        String ed = feast.settings().edition();
        FeastDao.Reset r;
        try {
            r = feast.reset();
        } catch (SQLException e) {
            plugin.getLogger().warning("The Family Feast could not reset " + ed + ": " + e.getMessage());
            sender.sendMessage(Text.of("&cThat couldn't be done. See the console."));
            return;
        }
        sender.sendMessage(Text.of(resetLine(ed, r)));
        if (!r.refused()) {
            plugin.getLogger().info("Admin " + sender.getName() + " reset the Family Feast " + ed + ".");
        }
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

    /** Anyone with the Arcade for the family's line, admins for the rest (no new nodes). */
    static String permission(String verb) {
        return verb == null || verb.isEmpty() ? "hcm.arcade.use" : "hcm.admin";
    }

    /** What is wrong with the words after {@code /hcm feast <verb>}, or null when they will do. */
    static String argsError(String verb, String[] args) {
        if ("reset".equals(verb) && (args.length < 3 || !args[2].equalsIgnoreCase("confirm"))) {
            return "This wipes the Feast's edition (its points, markers and test rewards). Type /hcm feast reset "
                    + "confirm";
        }
        if ("pot".equals(verb) && args.length >= 3 && !args[2].equalsIgnoreCase("clear")) {
            return "Usage: /hcm feast pot [clear]";
        }
        return null;
    }

    /** The player's lines: the family's line and bar, their own gifts, and whether it is open. */
    static List<String> mineLines(FeastRules.FeastView v, long mine, FeastConfig.Feast c, boolean over) {
        List<String> out = new ArrayList<>();
        out.add(FeastRules.line(v.total(), v.goal()) + " " + FeastRules.bar(v.total(), v.goal()));
        out.add("&7You gave &f" + mine + " &7point" + (mine == 1 ? "" : "s") + ".");
        if (v.done()) {
            out.add("&aThe tables are full! &7Thank you for helping.");
        } else if (v.open()) {
            out.add("&7Tap the Feast Pot holding food to put it in.");
        } else {
            out.add(over ? "&7The Family Feast is over." : "&7The Family Feast opens on " + c.from() + ".");
        }
        return out;
    }

    /** What {@code reset} says. */
    static String resetLine(String edition, FeastDao.Reset r) {
        if (r.refused()) {
            return "&cThe Family Feast " + edition + " has filled or given its rewards, so it can't be reset (it "
                    + "would give them twice). Use a test edition to try things out.";
        }
        return "&aThe Family Feast " + edition + " is reset: " + r.scores() + " score rows, " + r.markers()
                + " markers, " + r.counters() + " player marks" + (r.rewards() > 0 ? ", " + r.rewards()
                + " test rewards (their tokens stay paid)" : "") + ".";
    }
}
