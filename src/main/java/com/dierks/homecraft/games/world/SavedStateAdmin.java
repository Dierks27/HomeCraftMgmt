package com.dierks.homecraft.games.world;

import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /hcm games saved <player> show | restore | return | discard confirm} (spec §7.6, R2.17):
 * the admin's way to see and finish a saved state the automatic paths couldn't — a snapshot that
 * no longer reads after an upgrade, a return point whose world was deleted, a support question
 * about what someone had.
 *
 * <ul>
 *   <li>{@code show} — phase, game, session world, where they came from, how many stacks are
 *       saved and kept for later (works offline; also the last finished session, kept a week).</li>
 *   <li>{@code restore} — put the saved things back now, by the usual rules: in the session world
 *       only, what they hold now kept for them first, overwriting, then home (online). The admin
 *       is told what really happened (or why it couldn't).</li>
 *   <li>{@code return} — send them home once their things are back (online).</li>
 *   <li>{@code discard confirm} — delete the row for good (logged). Without {@code confirm} it
 *       only says what would go.</li>
 * </ul>
 *
 * <p>Players are found like {@code /hcm games break}: {@code getOfflinePlayerIfCached}, and they
 * must have played here.
 */
final class SavedStateAdmin {

    static final String PERMISSION = "hcm.games.admin";
    static final List<String> ACTIONS = List.of("show", "restore", "return", "discard");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.ROOT);

    private final BukkitPort port;

    SavedStateAdmin(BukkitPort port) {
        this.port = port;
    }

    /**
     * The words after {@code saved}. A leading {@code games saved} (the whole command line) or
     * {@code saved} is skipped too, so the caller can pass whichever it has.
     */
    static String[] words(String[] args) {
        if (args == null) {
            return new String[0];
        }
        int from = 0;
        if (args.length >= 2 && args[0].equalsIgnoreCase("games") && args[1].equalsIgnoreCase("saved")) {
            from = 2;
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("saved") && !ACTIONS.contains(args[1].toLowerCase(Locale.ROOT))) {
            from = 1;
        } else if (args.length == 1 && args[0].equalsIgnoreCase("saved")) {
            from = 1;
        }
        String[] out = new String[args.length - from];
        System.arraycopy(args, from, out, 0, out.length);
        return out;
    }

    void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        String[] a = words(args);
        if (a.length < 2 || !ACTIONS.contains(a[1].toLowerCase(Locale.ROOT))) {
            usage(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (target == null || !(target.hasPlayedBefore() || target.isOnline())) {
            sender.sendMessage(Text.of("&cNo player called " + a[0] + " has played here."));
            return;
        }
        String name = target.getName() == null ? a[0] : target.getName();
        UUID id = target.getUniqueId();
        SessionCore<Player, ItemStack> core = port.core();
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "show" -> show(sender, id, name);
            case "restore", "return" -> {
                Player online = target.getPlayer();
                if (online == null || !online.isOnline()) {
                    sender.sendMessage(Text.of("&c" + name + " needs to be online for that. &7Their things are kept."));
                    return;
                }
                String result = a[1].equalsIgnoreCase("restore") ? core.adminRestore(online) : core.adminReturn(online);
                sender.sendMessage(Text.of(result));
                port.plugin().getLogger().info("Games: " + sender.getName() + " ran saved " + a[1].toLowerCase(Locale.ROOT)
                        + " for " + name + ".");
            }
            case "discard" -> {
                if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) {
                    show(sender, id, name);
                    sender.sendMessage(Text.of("&eType &f/hcm games saved " + name
                            + " discard confirm &eto delete their saved things for good."));
                    return;
                }
                sender.sendMessage(Text.of(core.adminDiscard(id, name, sender.getName())));
            }
            default -> usage(sender);
        }
    }

    private void show(CommandSender sender, UUID id, String name) {
        SessionCore<Player, ItemStack> core = port.core();
        Session live = core.session(id);
        SavedState row = core.rowOrNull(id);
        if (row == null) {
            sender.sendMessage(Text.of("&7No saved things for &f" + name + "&7."));
            SavedState done = core.lastDone(id);
            if (done != null) {
                sender.sendMessage(Text.of("&7Last game: &f" + done.gameId() + ref(done) + " &7finished &f"
                        + when(done.doneAt() == null ? done.createdAt() : done.doneAt()) + " &7(session "
                        + done.sessionId() + ")"));
            }
            if (live != null) {
                sender.sendMessage(Text.of("&7In a game now: &f" + live.gameId() + " &7(" + live.phase() + ")"));
            }
            return;
        }
        sender.sendMessage(Text.of("&eSaved things for &f" + name + " &7(session " + row.sessionId() + ")"));
        sender.sendMessage(Text.of("&7Phase: &f" + row.phase() + " &7— " + (SavedState.ACTIVE.equals(row.phase())
                ? "not put back yet" : "put back, not home yet")));
        sender.sendMessage(Text.of("&7Game: &f" + row.gameId() + ref(row) + " &7in &f" + row.sessionWorld()
                + " &7since &f" + when(row.createdAt())));
        sender.sendMessage(Text.of("&7Came from: &f" + row.world() + " " + Math.round(row.x()) + ", "
                + Math.round(row.y()) + ", " + Math.round(row.z())
                + (Bukkit.getWorld(row.world() == null ? "" : row.world()) == null ? " &c(world gone: spawn instead)" : "")));
        sender.sendMessage(Text.of("&7Items: &f" + count(row.items()) + " &7· kept for later: &f" + count(row.carry())
                + " &7· level &f" + row.xpLevel() + " &7· " + row.gameMode()));
        if (live != null) {
            sender.sendMessage(Text.of("&7In a game now: &f" + live.gameId() + " &7(" + live.phase() + ")"));
        } else if (core.recovering(id)) {
            sender.sendMessage(Text.of("&7On the way back right now."));
        }
        sender.sendMessage(Text.of("&7Next: &e" + (SavedState.ACTIVE.equals(row.phase()) ? "restore" : "return")
                + " &7(online) or &ediscard confirm"));
    }

    private static String ref(SavedState s) {
        return s.ref() == null || s.ref().isEmpty() ? "" : " (" + s.ref() + ")";
    }

    private static String when(long millis) {
        return WHEN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** How many stacks a blob holds, or why it can't be read. */
    private static String count(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return "0 stacks";
        }
        try {
            int n = BukkitStateAdapter.decode(blob).size();
            return n + (n == 1 ? " stack" : " stacks");
        } catch (RuntimeException e) {
            return "unreadable (" + blob.length + " bytes)";
        }
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Text.of("&e/hcm games saved <player> show|restore|return|discard confirm &7- a player's saved game state"));
    }

    /** Tab completion: online players, then the actions, then {@code confirm} after discard. */
    static List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERMISSION)) {
            return out;
        }
        String[] a = words(args);
        if (a.length == 0) {
            return out;
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) {
                    out.add(p.getName());
                }
            }
        } else if (a.length == 2) {
            for (String action : ACTIONS) {
                if (action.startsWith(last)) {
                    out.add(action);
                }
            }
        } else if (a.length == 3 && a[1].equalsIgnoreCase("discard") && "confirm".startsWith(last)) {
            out.add("confirm");
        }
        return out;
    }
}
