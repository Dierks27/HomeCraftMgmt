package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.looks.LookService;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.gui.arcade.GiftPickerMenu;
import com.dierks.homecraft.gui.arcade.LookMenu;
import com.dierks.homecraft.gui.arcade.MyLooksMenu;
import com.dierks.homecraft.storage.AccountMoveDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /hcm looks ...} and {@code /hcm account move} (SHOP-V2-SPEC §3.14, §3.17): the wardrobe's commands. Routed
 * from {@link HcmCommand}; the rules are {@link LookService}'s and {@link AccountMoveDao}'s, and this only parses,
 * checks permissions, reports and logs.
 * <ul>
 *   <li>{@code /hcm looks} — My Looks; {@code /hcm looks renew <id>} — the look screen (the {@code [Buy again]}
 *       link's target); {@code /hcm looks gift <id> <player>} — "Give a present" on that look and person (the
 *       giver's {@code [Give it again]} link). Nothing is ever bought from chat: each opens a screen.
 *       (hcm.arcade.use)</li>
 *   <li>{@code /hcm looks list <player>}, {@code give <player> <id> [days]}, {@code end <player> <id> [days]},
 *       {@code preview <id>} — the admin's tools, for a free test look and the look session (hcm.admin)</li>
 *   <li>{@code /hcm account move <from> <to> [confirm]} — every economy record of one account to another, in one
 *       transaction; without {@code confirm} it only lists what would move (hcm.admin)</li>
 * </ul>
 */
final class LooksCommand {

    static final String USE = "hcm.arcade.use";
    static final String ADMIN = "hcm.admin";

    private final HomeCraftManagement plugin;

    LooksCommand(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** What {@code /hcm looks ...} asks for. */
    enum Sub { OPEN, RENEW, GIFT, LIST, GIVE, END, PREVIEW, USAGE }

    /** The sub-command of {@code args} (args[0] is "looks"), or USAGE when it is unknown or short of arguments. */
    static Sub sub(String[] args) {
        if (args.length < 2) {
            return Sub.OPEN;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "renew" -> args.length >= 3 ? Sub.RENEW : Sub.USAGE;
            case "gift" -> args.length >= 4 ? Sub.GIFT : Sub.USAGE;
            case "list" -> args.length >= 3 ? Sub.LIST : Sub.USAGE;
            case "give" -> args.length >= 4 ? Sub.GIVE : Sub.USAGE;
            case "end" -> args.length >= 4 ? Sub.END : Sub.USAGE;
            case "preview" -> args.length >= 3 ? Sub.PREVIEW : Sub.USAGE;
            default -> Sub.USAGE;
        };
    }

    /** Whether a sub-command is the admin's. */
    static boolean admin(Sub s) {
        return s == Sub.LIST || s == Sub.GIVE || s == Sub.END || s == Sub.PREVIEW;
    }

    /**
     * {@code /hcm looks end}'s days: a decimal 0 or more ("5", "0.5"), or null when it isn't one. Missing means "now",
     * which the caller passes as null without asking here.
     */
    static Double endDays(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(raw.trim());
            return Double.isFinite(d) && d >= 0 && d <= 3650 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code /hcm looks give}'s days: a whole number 1-365, or null when it isn't one. */
    static Integer giveDays(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int d = Integer.parseInt(raw.trim());
            return d >= 1 && d <= 365 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void looks(CommandSender sender, String[] args) {
        Sub sub = sub(args);
        if (sub == Sub.USAGE) {
            usage(sender);
            return;
        }
        if (!sender.hasPermission(admin(sub) ? ADMIN : USE)) {
            sender.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        LookService looks = plugin.looks();
        if (looks == null) {
            sender.sendMessage(Text.of("&cMy Looks isn't running."));
            return;
        }
        switch (sub) {
            case OPEN -> {
                if (sender instanceof Player p) {
                    new MyLooksMenu(plugin, p, null).open(p);
                } else {
                    sender.sendMessage(Text.of("&cOnly players have looks."));
                }
            }
            case RENEW -> {
                if (sender instanceof Player p) {
                    new LookMenu(plugin, p, args[2], () -> new MyLooksMenu(plugin, p, null).open(p)).open(p);
                } else {
                    sender.sendMessage(Text.of(ONLY_PLAYERS_OPEN));
                }
            }
            case GIFT -> gift(sender, args[2], args[3]);
            case LIST -> list(sender, args[2]);
            case GIVE -> give(sender, args);
            case END -> end(sender, args);
            case PREVIEW -> preview(sender, args[2]);
            default -> usage(sender);
        }
    }

    /** {@code renew} and {@code gift} from the console: they open a screen, so they need a player (CMD-6). */
    static final String ONLY_PLAYERS_OPEN = "&cOnly players can open that.";

    private void gift(CommandSender sender, String id, String name) {
        if (!(sender instanceof Player giver)) {
            sender.sendMessage(Text.of(ONLY_PLAYERS_OPEN));
            return;
        }
        Prize p = plugin.config().arcade().prize(id);
        Player r = Bukkit.getPlayerExact(name);
        if (p == null) {
            sender.sendMessage(Text.of("&cThat isn't for sale right now."));
            return;
        }
        if (r == null) {
            sender.sendMessage(Text.of("&c" + name + " isn't online."));
            return;
        }
        GiftPickerMenu.openFor(plugin, giver, p, r.getUniqueId());
    }

    private void list(CommandSender sender, String name) {
        OfflinePlayer target = offline(name);
        if (target == null) {
            sender.sendMessage(Text.of("&cNo player called '" + name + "' has been here."));
            return;
        }
        List<String> lines = plugin.looks().list(target.getUniqueId());
        sender.sendMessage(Text.of("&6" + display(target, name) + "'s looks &7(" + lines.size() + ")"));
        if (lines.isEmpty()) {
            sender.sendMessage(Text.of("&7  none"));
        }
        for (String l : lines) {
            sender.sendMessage(Text.of("&7  " + l));
        }
    }

    private void give(CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(Text.of("&c" + args[2] + " isn't online: a look is handed over, so they must be."));
            return;
        }
        Prize p = plugin.config().arcade().prize(args[3]);
        if (p == null) {
            sender.sendMessage(Text.of("&cUnknown look '" + args[3] + "'. Use a hat, cap or trail row id."));
            return;
        }
        Integer days = null;
        if (args.length >= 5) {
            if (p.keeper()) {
                sender.sendMessage(Text.of(keeperNoDays(p.id())));
                return;
            }
            days = giveDays(args[4]);
            if (days == null) {
                sender.sendMessage(Text.of("&cDays must be a whole number from 1 to 365."));
                return;
            }
        }
        UUID adminId = sender instanceof Player sp ? sp.getUniqueId() : null;
        String[] told = {null};
        String why = plugin.looks().give(adminId, sender.getName(), target, p, days, line -> told[0] = line);
        if (why != null) {
            sender.sendMessage(Text.of("&c" + why));
            return;
        }
        sender.sendMessage(Text.of(givenConfirmation(target.getName(), Text.plain(p.display()), days, told[0],
                p.keeper())));
    }

    /**
     * {@code [days]} typed on a Keeper (red-team finding CMD-3): a Keeper is given for good and nothing ends one, so a
     * "for 2 days" that was quietly ignored left a Keeper for good. Refused before anything is given.
     */
    static String keeperNoDays(String id) {
        return "&cA Keeper is kept for good - no days. /hcm looks preview " + id + " shows one without giving it.";
    }

    /**
     * The admin's line after {@code /hcm looks give}: what was given and for how long ("for good" for a Keeper), and
     * the line the receiver read, which says where the key went (red-team finding BR-4), so a key that went to My
     * Looks isn't a surprise.
     */
    static String givenConfirmation(String target, String look, Integer days, String told, boolean keeper) {
        String length = keeper ? " for good" : days != null ? " for " + days + (days == 1 ? " day" : " days") : "";
        return "&aGave " + target + " the " + look + length + "."
                + (told == null || told.isBlank() ? "" : " &7" + target + " reads: \"" + told + "\"");
    }

    private void end(CommandSender sender, String[] args) {
        OfflinePlayer target = offline(args[2]);
        if (target == null) {
            sender.sendMessage(Text.of("&cNo player called '" + args[2] + "' has been here."));
            return;
        }
        Double days = null;
        if (args.length >= 5) {
            days = endDays(args[4]);
            if (days == null) {
                sender.sendMessage(Text.of("&cDays must be a number, 0 or more (e.g. 5 or 0.5)."));
                return;
            }
        }
        String why = plugin.looks().end(target.getUniqueId(), args[3], days);
        if (why != null) {
            sender.sendMessage(Text.of("&c" + why));
            return;
        }
        plugin.getLogger().info("Admin " + sender.getName() + " moved the end of " + display(target, args[2]) + "'s "
                + args[3] + (days == null ? " to now" : " to " + days + " days from now"));
        sender.sendMessage(Text.of("&a" + display(target, args[2]) + "'s " + args[3] + (days == null ? " ends now."
                : " now ends in " + days + " days.")));
    }

    private void preview(CommandSender sender, String id) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(Text.of("&cOnly a player can stand next to a preview."));
            return;
        }
        Prize p = plugin.config().arcade().prize(id);
        if (p == null) {
            sender.sendMessage(Text.of("&cUnknown look '" + id + "'."));
            return;
        }
        String why = plugin.looks().preview(admin, p);
        sender.sendMessage(Text.of(why == null ? "&aThe preview stands for 30 seconds. It claims nothing."
                : "&c" + why));
    }

    // ---- /hcm account move --------------------------------------------------------------------

    /**
     * The dry run's last line. Items are traded over after the move and before linking (README "Her own Java account,
     * later"): trading first gives the new account her Minis, and the move then refuses (red-team finding CMD-2).
     */
    static final String DRY_RUN_LAST = "&7Items stay with the old account: after the move, trade them over in the "
            + "trade window, before linking. Run it again with &fconfirm &7to move.";

    void account(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("move")) {
            sender.sendMessage(Text.of("&cUsage: /hcm account move <from> <to> [confirm]"));
            return;
        }
        UUID from = uuid(args[2]);
        UUID to = uuid(args[3]);
        if (from == null || to == null) {
            sender.sendMessage(Text.of("&cUse a name that has been on this server, or a UUID."));
            return;
        }
        if (plugin.looks() != null && (plugin.looks().inGame(from) || plugin.looks().inGame(to))) {
            sender.sendMessage(Text.of("&cOne of them is in a game. Try again when they're back."));
            return;
        }
        boolean confirm = args.length >= 5 && args[4].equalsIgnoreCase("confirm");
        AccountMoveDao dao = new AccountMoveDao(plugin.database());
        try {
            String fromName = nameOf(from, args[2]);
            String toName = nameOf(to, args[3]);
            AccountMoveDao.Plan plan = confirm
                    ? dao.move(from, to, plugin.clock().nowMillis(), fromName, toName) : dao.plan(from, to);
            sender.sendMessage(Text.of("&6" + (confirm && !plan.refused() ? "Moved" : "Would move") + " from "
                    + fromName + " to " + toName + ":"));
            for (String line : plan.lines()) {
                sender.sendMessage(Text.of("&7  " + line));
            }
            if (plan.refused()) {
                sender.sendMessage(Text.of("&cRefused, nothing changes: " + String.join("; ", plan.overlap())));
                return;
            }
            if (!confirm) {
                sender.sendMessage(Text.of(DRY_RUN_LAST));
                return;
            }
            plugin.getLogger().info("Admin " + sender.getName() + " moved the account " + fromName + " (" + from
                    + ") to " + toName + " (" + to + ")");
            if (plugin.looks() != null) {
                plugin.looks().moved(List.of(from, to));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("The account move failed (nothing moved): " + e.getMessage());
            sender.sendMessage(Text.of("&cThe move failed, so nothing moved: " + e.getMessage()));
        }
    }

    // ---- help and completion ------------------------------------------------------------------

    static List<String> helpLines(CommandSender sender) {
        List<String> out = new ArrayList<>();
        if (sender.hasPermission(USE)) {
            out.add("&e/hcm looks &7- My Looks: your hats, caps and trails");
        }
        if (sender.hasPermission(ADMIN)) {
            out.add("&e/hcm looks list <player> &7- someone's looks and their heads-up");
            out.add("&e/hcm looks give <player> <id> [days] &7- a free look (a test look: a few days)");
            out.add("&e/hcm looks end <player> <id> [days] &7- end a look now, or in a few days");
            out.add("&e/hcm looks preview <id> &7- a look on a stand for 30 s (claims nothing)");
            out.add("&e/hcm account move <from> <to> [confirm] &7- move one account's things to another");
        }
        return out;
    }

    private void usage(CommandSender sender) {
        for (String line : helpLines(sender)) {
            sender.sendMessage(Text.of(line));
        }
    }

    List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args[0].equalsIgnoreCase("account")) {
            if (sender.hasPermission(ADMIN)) {
                if (args.length == 2) {
                    match(out, args[1], "move");
                } else if (args.length == 3 || args.length == 4) {
                    players(out, args[args.length - 1]);
                } else if (args.length == 5) {
                    match(out, args[4], "confirm");
                }
            }
            return out;
        }
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 2) {
            match(out, args[1], "renew", "gift");
            if (admin) {
                match(out, args[1], "list", "give", "end", "preview");
            }
            return out;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if ((sub.equals("renew") || sub.equals("gift") || sub.equals("preview")) && args.length == 3) {
            looksIds(out, args[2]);
        } else if (sub.equals("gift") && args.length == 4) {
            players(out, args[3]);
        } else if (admin && (sub.equals("list") || sub.equals("give") || sub.equals("end")) && args.length == 3) {
            players(out, args[2]);
        } else if (admin && (sub.equals("give") || sub.equals("end")) && args.length == 4) {
            looksIds(out, args[3]);
        } else if (admin && sub.equals("end") && args.length == 5) {
            match(out, args[4], "5", "0.5");
        }
        return out;
    }

    private void looksIds(List<String> out, String prefix) {
        for (Prize p : plugin.config().arcade().prizes()) {
            if (p.rental() || p.keeper() || p.collector() != null) {
                match(out, prefix, p.id());
            }
        }
    }

    private static void players(List<String> out, String prefix) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(p.getName());
            }
        }
    }

    private static void match(List<String> out, String prefix, String... options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
    }

    // ---- players by name ----------------------------------------------------------------------

    /**
     * An online player by name, else one the server has seen (its name cache: no web lookup), or null. Also
     * {@code /hcm trades <player>}'s lookup.
     */
    static OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        UUID id = uuidOf(name);
        if (id != null) {
            return Bukkit.getOfflinePlayer(id);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }

    private static UUID uuid(String nameOrId) {
        OfflinePlayer p = offline(nameOrId);
        return p == null ? null : p.getUniqueId();
    }

    /** A UUID written out, or null when {@code raw} isn't one. */
    static UUID uuidOf(String raw) {
        if (raw == null || raw.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String nameOf(UUID id, String typed) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? typed : n;
    }

    private static String display(OfflinePlayer p, String typed) {
        return p.getName() == null ? typed : p.getName();
    }
}
