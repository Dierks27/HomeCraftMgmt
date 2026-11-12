package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.graves.Grave;
import com.dierks.homecraft.graves.GraveService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /hcm graves ...} (HCM-GRAVES §5, §7).
 *
 * <ul>
 *   <li>{@code /hcm graves}: your own open graves (world, place, age, stacks). {@code hcm.graves.use}.</li>
 *   <li>{@code /hcm graves compass [id]}: a compass to your newest grave (or that one), in place of one you lost.
 *       {@code hcm.graves.use}.</li>
 *   <li>{@code /hcm graves list [player]}: every open grave, or one player's. {@code hcm.graves.admin}.</li>
 *   <li>{@code /hcm graves tp <id>}: go to a grave.</li>
 *   <li>{@code /hcm graves open <id>}: the grave screen, from anywhere (see and take).</li>
 *   <li>{@code /hcm graves restore <id> [player]}: everything in it straight to its owner (back into the slots it
 *       came from) or to another player, wherever they are: the "it glitched" fix. They must be online.</li>
 *   <li>{@code /hcm graves remove <id> [--delete]}, then the same with {@code confirm} within 15 seconds: the grave
 *       goes and its things drop on the ground where it stood, or with {@code --delete} are deleted.</li>
 * </ul>
 */
public final class GravesCommand {

    /** The admin verbs, in the order help and tab completion list them. */
    static final List<String> VERBS = List.of("compass", "list", "tp", "open", "restore", "remove");
    /** How long a {@code remove} waits for its {@code confirm}. */
    static final long CONFIRM_MS = 15_000L;

    private final HomeCraftManagement plugin;
    /** Sender name → the remove they asked for (id and --delete) and when. */
    private final Map<String, Pending> pending = new HashMap<>();

    record Pending(long id, boolean delete, long at) {
    }

    public GravesCommand(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** {@code /hcm graves ...}; {@code args} include the {@code graves} word. */
    public void handle(CommandSender sender, String[] args) {
        GraveService graves = plugin.graves();
        if (graves == null) {
            sender.sendMessage(Text.of("&cGraves aren't running (see the console)."));
            return;
        }
        String verb = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (verb.isEmpty() || verb.equals("mine")) {
            mine(sender, graves);
            return;
        }
        if (verb.equals("compass")) {
            compass(sender, graves, args.length >= 3 ? args[2] : null);
            return;
        }
        if (!sender.hasPermission(GraveService.ADMIN)) {
            sender.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        switch (verb) {
            case "list" -> list(sender, graves, args.length >= 3 ? args[2] : null);
            case "tp" -> withGrave(sender, graves, args, g -> tp(sender, g));
            case "open" -> withGrave(sender, graves, args, g -> {
                if (sender instanceof Player p) {
                    graves.openScreen(p, g);
                } else {
                    sender.sendMessage(Text.of("&cOnly players can open a grave."));
                }
            });
            case "restore" -> withGrave(sender, graves, args, g -> restore(sender, graves, g,
                    args.length >= 4 ? args[3] : null));
            case "remove" -> remove(sender, graves, args);
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Text.of("&e/hcm graves &7- your graves"));
        sender.sendMessage(Text.of("&e/hcm graves compass [id] &7- a compass to your grave"));
        sender.sendMessage(Text.of("&e/hcm graves list [player] &7- every open grave, or one player's"));
        sender.sendMessage(Text.of("&e/hcm graves tp <id> &7- go to a grave"));
        sender.sendMessage(Text.of("&e/hcm graves open <id> &7- look in a grave, take what you need"));
        sender.sendMessage(Text.of("&e/hcm graves restore <id> [player] &7- give everything in it to its owner (or"
                + " a player), wherever they are"));
        sender.sendMessage(Text.of("&e/hcm graves remove <id> [--delete] &7- take a grave away; its things drop"
                + " there (or are deleted)"));
    }

    private void mine(CommandSender sender, GraveService graves) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&7Console: &e/hcm graves list [player]"));
            return;
        }
        if (!p.hasPermission(GraveService.USE) && !p.hasPermission(GraveService.ADMIN)) {
            p.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        List<Grave> mine = new ArrayList<>();
        for (Grave g : graves.openGraves()) {
            if (g.owner().equals(p.getUniqueId())) {
                mine.add(g);
            }
        }
        if (mine.isEmpty()) {
            p.sendMessage(Text.of("&7You have no graves. &8(When you die, your things wait for you in one.)"));
            return;
        }
        p.sendMessage(Text.of("&6Your graves &7(" + mine.size() + ") &8- right-click one to get your things back"));
        for (Grave g : mine) {
            p.sendMessage(Text.of(graves.line(g, false)));
        }
        p.sendMessage(Text.of("&8Lost the compass? &7/hcm graves compass"));
    }

    /** A compass to your newest open grave, or to the one named (yours; an admin's: anyone's). */
    private void compass(CommandSender sender, GraveService graves, String idArg) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly players can carry a compass."));
            return;
        }
        if (!p.hasPermission(GraveService.USE) && !p.hasPermission(GraveService.ADMIN)) {
            p.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        Grave target = null;
        if (idArg != null) {
            Long id = id(idArg);
            Grave g = id == null ? null : graves.openGrave(id);
            if (g != null && (g.owner().equals(p.getUniqueId()) || p.hasPermission(GraveService.ADMIN))) {
                target = g;
            }
        } else {
            for (Grave g : graves.openGraves()) {
                if (g.owner().equals(p.getUniqueId())) {
                    target = g; // oldest first: the last one is the newest
                }
            }
        }
        if (target == null) {
            p.sendMessage(Text.of(idArg == null ? "&7You have no graves." : "&cNo grave of yours #" + idArg + "."));
            return;
        }
        if (!graves.giveCompass(p, target)) {
            p.sendMessage(Text.of("&cThat grave's world isn't loaded."));
        }
    }

    private void list(CommandSender sender, GraveService graves, String name) {
        List<Grave> rows = graves.openGraves();
        String who = "everyone";
        if (name != null) {
            OfflinePlayer op = LooksCommand.offline(name);
            if (op == null) {
                sender.sendMessage(Text.of("&cNo player called '" + name + "' has been here."));
                return;
            }
            rows.removeIf(g -> !g.owner().equals(op.getUniqueId()));
            who = op.getName() == null ? name : op.getName();
        }
        sender.sendMessage(Text.of("&6Open graves &7- " + who + " &8(" + rows.size() + ")"));
        if (rows.isEmpty()) {
            sender.sendMessage(Text.of("&7None."));
        }
        for (Grave g : rows) {
            sender.sendMessage(Text.of(graves.line(g, name == null)));
        }
    }

    private interface GraveAction {
        void run(Grave g);
    }

    /** The grave named by {@code args[2]}, or a message saying why there isn't one. */
    private void withGrave(CommandSender sender, GraveService graves, String[] args, GraveAction action) {
        Long id = args.length >= 3 ? id(args[2]) : null;
        if (id == null) {
            sender.sendMessage(Text.of("&cWhich grave? &7/hcm graves " + args[1].toLowerCase(Locale.ROOT)
                    + " <id> &8(/hcm graves list shows the ids)"));
            return;
        }
        Grave g = graves.openGrave(id);
        if (g == null) {
            sender.sendMessage(Text.of("&cNo open grave #" + id + "." + closedNote(graves, id)));
            return;
        }
        action.run(g);
    }

    private static String closedNote(GraveService graves, long id) {
        try {
            Grave g = graves.fresh(id);
            return g == null ? "" : " &8(" + g.state().toLowerCase(Locale.ROOT) + " by "
                    + (g.closedBy().isEmpty() ? "?" : name(g.closedBy())) + ")";
        } catch (SQLException e) {
            return "";
        }
    }

    private static String name(String uuidOrWord) {
        try {
            OfflinePlayer p = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuidOrWord));
            return p.getName() == null ? uuidOrWord : p.getName();
        } catch (IllegalArgumentException e) {
            return uuidOrWord;
        }
    }

    /** {@code 12} or {@code #12}. */
    static Long id(String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        try {
            long v = Long.parseLong(t);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void tp(CommandSender sender, Grave g) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly players can go to a grave."));
            return;
        }
        World w = Bukkit.getWorld(g.world());
        if (w == null) {
            p.sendMessage(Text.of("&cThe world '" + g.world() + "' isn't loaded."));
            return;
        }
        p.teleportAsync(new Location(w, g.x() + 0.5, g.y() + 1, g.z() + 0.5, p.getLocation().getYaw(),
                p.getLocation().getPitch()));
        p.sendMessage(Text.of("&7Going to grave &e#" + g.id() + " &7(" + g.ownerName() + ")."));
    }

    private void restore(CommandSender sender, GraveService graves, Grave g, String name) {
        Player to = name == null ? Bukkit.getPlayer(g.owner()) : Bukkit.getPlayerExact(name);
        if (to == null || !to.isOnline()) {
            sender.sendMessage(Text.of("&c" + (name == null ? g.ownerName() : name) + " isn't online: they need to"
                    + " be, to be given the things."));
            return;
        }
        if (graves.restore(g.id(), to)) {
            sender.sendMessage(Text.of("&aGrave #" + g.id() + " given to " + to.getName() + "."));
            if (!to.equals(sender)) {
                to.sendMessage(Text.of("&7An admin gave you " + (g.owner().equals(to.getUniqueId())
                        ? "your things back from your grave." : "the things from " + g.ownerName() + "'s grave.")));
            }
        } else {
            sender.sendMessage(Text.of("&cGrave #" + g.id() + " couldn't be given (it's being opened, or it's"
                    + " already empty)."));
        }
    }

    private void remove(CommandSender sender, GraveService graves, String[] args) {
        Long id = args.length >= 3 ? id(args[2]) : null;
        if (id == null) {
            sender.sendMessage(Text.of("&cWhich grave? &7/hcm graves remove <id> [--delete]"));
            return;
        }
        boolean delete = false;
        boolean confirm = false;
        for (int i = 3; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            delete |= a.equals("--delete") || a.equals("delete");
            confirm |= a.equals("confirm");
        }
        Grave g = graves.openGrave(id);
        if (g == null) {
            sender.sendMessage(Text.of("&cNo open grave #" + id + "."));
            return;
        }
        long now = System.currentTimeMillis();
        Pending asked = pending.get(sender.getName());
        if (!confirm || asked == null || asked.id() != id || asked.delete() != delete || now - asked.at() > CONFIRM_MS) {
            pending.put(sender.getName(), new Pending(id, delete, now));
            sender.sendMessage(Text.of("&eGrave #" + id + " &7(" + g.ownerName() + ", " + g.stacks() + " stack"
                    + (g.stacks() == 1 ? "" : "s") + ") at " + GraveService.where(g) + " will be taken away, and "
                    + (delete ? "&ceverything in it deleted&7." : "its things dropped on the ground there.")));
            sender.sendMessage(Text.of("&7Type &e/hcm graves remove " + id + (delete ? " --delete" : "")
                    + " confirm &7within 15 seconds."));
            return;
        }
        pending.remove(sender.getName());
        if (graves.remove(id, sender.getName(), delete)) {
            sender.sendMessage(Text.of("&aGrave #" + id + " removed" + (delete ? " and its things deleted." : "; its"
                    + " things are on the ground there.")));
        } else {
            sender.sendMessage(Text.of("&cGrave #" + id + " couldn't be removed (it's being opened, or it's gone)."));
        }
    }

    /** Completions after {@code /hcm graves}. */
    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(GraveService.ADMIN)) {
            if (args.length == 2 && "compass".startsWith(args[1].toLowerCase(Locale.ROOT))
                    && sender.hasPermission(GraveService.USE)) {
                out.add("compass");
            }
            return out;
        }
        if (args.length == 2) {
            for (String v : VERBS) {
                if (v.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(v);
                }
            }
        } else if (args.length == 3 && !args[1].equalsIgnoreCase("list")) {
            GraveService graves = plugin.graves();
            if (graves != null) {
                for (Grave g : graves.openGraves()) {
                    String id = Long.toString(g.id());
                    if (id.startsWith(args[2])) {
                        out.add(id);
                    }
                }
            }
        } else if (args.length == 3 && Bukkit.getServer() != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 4 && args[1].equalsIgnoreCase("remove")) {
            for (String v : List.of("--delete", "confirm")) {
                if (v.startsWith(args[3].toLowerCase(Locale.ROOT))) {
                    out.add(v);
                }
            }
        } else if (args.length == 4 && args[1].equalsIgnoreCase("restore") && Bukkit.getServer() != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 5 && args[1].equalsIgnoreCase("remove")) {
            if ("confirm".startsWith(args[4].toLowerCase(Locale.ROOT))) {
                out.add("confirm");
            }
        }
        return out;
    }
}
