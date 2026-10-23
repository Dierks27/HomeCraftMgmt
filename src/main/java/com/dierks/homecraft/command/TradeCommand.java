package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.trade.TradePickerMenu;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.TradeDao;
import com.dierks.homecraft.trade.ItemFacts;
import com.dierks.homecraft.trade.TradeService;
import com.dierks.homecraft.trade.TradeSession;
import com.dierks.homecraft.trade.TradeStore;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * The trade window's commands (SHOP-V2-SPEC §4.2, §4.6, §4.12).
 *
 * <ul>
 *   <li>{@code /trade <player>} asks (or says yes, when they asked first); {@code /trade} alone opens the picker;
 *       {@code /trade accept|deny [player]}, {@code /trade cancel} (a request you sent, or the trade you're in),
 *       {@code /trade on|off} (whether people may ask you). {@code /hcm trade ...} is the same, for when another
 *       plugin owns {@code /trade} (Paper also keeps {@code /homecraftmanagement:trade}). {@code hcm.trade.use}.</li>
 *   <li>{@code /hcm trades [player] [n]}, {@code /hcm trades show <id>}, {@code /hcm trades settle <id>
 *       release|void [player] [confirm]}: the log, for an admin ({@code hcm.admin}). Settle is for a trade the
 *       server didn't see finish: nothing is settled on its own, and the verb that fits where its bags are works
 *       (read from the mark each bag is saved with at the next start; red-team findings TCS-2, TCS-3); {@code void
 *       <player>} when only that player's bag holds it (their own offer goes to their Mailbox), and {@code confirm}
 *       when the admin looked at both bags and the read is wrong (red-team findings TCR4-OVERRIDE-3, TCR4-2).</li>
 * </ul>
 */
public final class TradeCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HomeCraftManagement plugin;

    public TradeCommand(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        trade(sender, args);
        return true;
    }

    /** {@code /trade ...}, and {@code /hcm trade ...} with the {@code trade} word taken off. */
    public void trade(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.of("&cOnly players can trade."));
            return;
        }
        if (!p.hasPermission(TradeService.PERMISSION)) {
            sender.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        TradeService trades = plugin == null ? null : plugin.trades();
        if (trades == null || !trades.settings().enabled()) {
            sender.sendMessage(Text.of("&cTrading is switched off."));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String name = args.length >= 2 ? args[1] : null;
        switch (sub) {
            case "" -> new TradePickerMenu(plugin, trades, p, null).open(p);
            case "accept", "yes" -> trades.accept(p, name);
            case "deny", "no" -> trades.deny(p, name);
            case "cancel" -> trades.withdraw(p);
            case "on" -> {
                trades.setTakesTrades(p.getUniqueId(), true);
                p.sendMessage(Text.of("&aPeople can ask you to trade."));
            }
            case "off" -> {
                trades.setTakesTrades(p.getUniqueId(), false);
                p.sendMessage(Text.of("&7Nobody can ask you to trade now. &8(" + trades.typed()
                        + " on turns it back on)"));
            }
            default -> {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || !p.canSee(target)) {
                    p.sendMessage(Text.of("&cThat player isn't online."));
                    return;
                }
                trades.ask(p, target);
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        return complete(sender, args);
    }

    /** {@code /trade} and {@code /hcm trade}'s completions (args after the word). */
    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(TradeService.PERMISSION)) {
            return out;
        }
        if (args.length == 1) {
            String a = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("accept", "deny", "cancel", "on", "off")) {
                if (s.startsWith(a)) {
                    out.add(s);
                }
            }
            players(sender, a, out);
        } else if (args.length == 2 && List.of("accept", "deny").contains(args[0].toLowerCase(Locale.ROOT))) {
            players(sender, args[1].toLowerCase(Locale.ROOT), out);
        }
        return out;
    }

    private static void players(CommandSender sender, String prefix, List<String> out) {
        if (Bukkit.getServer() == null) {
            return; // no server (the command's own test): nobody is online
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix) && !p.equals(sender)
                    && (!(sender instanceof Player me) || me.canSee(p))) {
                out.add(p.getName());
            }
        }
    }

    // ---- /hcm trades (admin) -------------------------------------------------------------------------

    /** {@code /hcm trades [player] [n] | show <id> | settle <id> release|void} (args include {@code trades}). */
    public void trades(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hcm.admin")) {
            sender.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        TradeService trades = plugin == null ? null : plugin.trades();
        if (trades == null) {
            sender.sendMessage(Text.of("&cThe trade log isn't available."));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            switch (sub) {
                case "show" -> show(sender, trades, args);
                case "settle" -> settle(sender, trades, args);
                default -> list(sender, trades.store().trades(), args);
            }
        } catch (SQLException e) {
            sender.sendMessage(Text.of("&cCouldn't read the trade log: " + e.getMessage()));
        }
    }

    /** {@code /hcm trades [player] [n]}: the last n (10), one line each, newest first. */
    private void list(CommandSender sender, TradeDao dao, String[] args) throws SQLException {
        ListQuery q = listQuery(args, name -> {
            OfflinePlayer p = LooksCommand.offline(name);
            return p == null ? null : p.getUniqueId();
        });
        if (q.unknown() != null) {
            sender.sendMessage(Text.of("&cNo player called '" + q.unknown() + "' has been here."));
            return;
        }
        List<TradeDao.Trade> rows = dao.recent(q.player(), q.n());
        sender.sendMessage(Text.of("&6Trades &7- " + q.who() + " &8(last " + rows.size() + ")"));
        if (rows.isEmpty()) {
            sender.sendMessage(Text.of("&7No trades yet."));
        }
        for (TradeDao.Trade t : rows) {
            sender.sendMessage(Text.of(line(t)));
        }
    }

    /**
     * {@code /hcm trades [player] [n]} read: whose trades ({@code player} null: everyone's), how many, or the name
     * nobody here has ({@code unknown}).
     */
    record ListQuery(UUID player, String who, int n, String unknown) {
    }

    /**
     * Read {@code /hcm trades [player] [n]}. A name is looked up by {@code resolve}, the server's own records only
     * ({@link LooksCommand#offline}): a name it has never seen is said so, never looked up on the web on the main
     * thread, and never listed as "No trades yet" for a made-up id (red-team findings TT-2, CMD-7).
     */
    static ListQuery listQuery(String[] args, Function<String, UUID> resolve) {
        UUID player = null;
        int n = 10;
        String who = "everyone";
        for (int i = 1; i < args.length; i++) {
            Integer k = number(args[i]);
            if (k != null) {
                n = Math.max(1, Math.min(100, k));
            } else {
                UUID id = resolve.apply(args[i]);
                if (id == null) {
                    return new ListQuery(null, args[i], n, args[i]);
                }
                player = id;
                who = args[i];
            }
        }
        return new ListQuery(player, who, n, null);
    }

    /** One trade in one line. */
    static String line(TradeDao.Trade t) {
        String colour = switch (t.outcome()) {
            case TradeDao.DONE -> "&a";
            case TradeDao.PENDING -> "&c";
            default -> "&7";
        };
        return "&e#" + t.id() + " &8" + when(t.closedAt()) + " " + colour + t.outcome() + " &f" + t.aName() + "&7: "
                + gave(t.aSummary(), t.aMoney()) + " &8| &f" + t.bName() + "&7: " + gave(t.bSummary(), t.bMoney())
                + (t.mailbox() > 0 ? " &8(" + t.mailbox() + " to Mailbox)" : "");
    }

    private static String gave(String summary, double money) {
        String items = summary == null || summary.isBlank() ? "" : summary;
        String cash = money > 0 ? String.format(Locale.ROOT, "$%.2f", money) : "";
        if (items.isEmpty() && cash.isEmpty()) {
            return "nothing";
        }
        return items.isEmpty() ? cash : cash.isEmpty() ? items : items + ", " + cash;
    }

    private static String when(long millis) {
        return WHEN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** {@code /hcm trades show <id>}: both sides in full, and the trade's Mailbox rows. */
    private void show(CommandSender sender, TradeService trades, String[] args) throws SQLException {
        Long id = args.length >= 3 ? id(args[2]) : null;
        if (id == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm trades show <id>"));
            return;
        }
        TradeDao.Trade t = trades.store().trades().byId(id).orElse(null);
        if (t == null) {
            sender.sendMessage(Text.of("&cNo trade #" + id + "."));
            return;
        }
        boolean pending = TradeDao.PENDING.equals(t.outcome());
        TradeStore.Saved state = pending ? trades.store().state(t, trades.marks()) : null;
        // red-team findings TCR4-2, AW-1: each bag read now, for a finished trade too (an admin who put a player file
        // back looks at the trades since; nothing reopens on its own)
        boolean read = pending || TradeDao.DONE.equals(t.outcome());
        Boolean[] now = read ? new Boolean[] {trades.store().holdsNow(t, trades.marks(), TradeSession.Side.A),
                trades.store().holdsNow(t, trades.marks(), TradeSession.Side.B)} : null;
        String money = pending ? trades.moneyLine(t) : "";
        for (String l : showLines(t, plugin.deliveries() == null ? List.of() : rows(t.id()), state, now, money,
                TradeCommand::stack)) {
            sender.sendMessage(Text.of(l));
        }
    }

    /**
     * What one stored stack is, as the swap's summary names it ("40 Oak Log"; red-team finding
     * SETTLE-HELD-AMOUNTS-4: the admin who takes a trade's offer out of a bag subtracts what is held).
     */
    static String stack(String b64) {
        ItemStack item = Items.fromBase64(b64);
        if (item == null) {
            return "a stack that can't be read";
        }
        ItemFacts f = ItemFacts.of(item);
        String name = f == null ? item.getType().name() : f.name();
        return item.getAmount() > 1 ? item.getAmount() + " " + name : name;
    }

    private List<DeliveryDao.Delivery> rows(long id) throws SQLException {
        return new DeliveryDao(plugin.database()).bySource(TradeStore.source(id));
    }

    /** {@link #showLines(TradeDao.Trade, List, TradeStore.Saved, Boolean[], String, Function)} with no live read. */
    static List<String> showLines(TradeDao.Trade t, List<DeliveryDao.Delivery> mail, TradeStore.Saved state) {
        return showLines(t, mail, state, null, "", b64 -> b64);
    }

    /**
     * What {@code show} prints (pure, for the test): each Mailbox row with what it holds ({@code what}, a stored stack
     * named). For a PENDING trade: {@code state} is where its bags are (what the start wrote), {@code now} each bag's
     * exact read now (null: can't say; for a DONE trade too), and {@code money} what its money did.
     */
    static List<String> showLines(TradeDao.Trade t, List<DeliveryDao.Delivery> mail, TradeStore.Saved state,
                                  Boolean[] now, String money, Function<String, String> what) {
        List<String> out = new ArrayList<>();
        boolean pending = TradeDao.PENDING.equals(t.outcome());
        out.add("&6Trade #" + t.id() + " &7- " + t.outcome() + " &8(" + t.world() + ", opened " + when(t.openedAt())
                + ", closed " + when(t.closedAt()) + ")");
        if (t.reason() != null && !t.reason().isBlank() && !pending) {
            out.add("&7Why: &f" + t.reason());
        }
        out.add("&f" + t.aName() + " &7gave: &f" + gave(t.aSummary(), t.aMoney()) + stored(t.aItems()));
        out.add("&f" + t.bName() + " &7gave: &f" + gave(t.bSummary(), t.bMoney()) + stored(t.bItems()));
        if (!mail.isEmpty()) {
            out.add("&7Mailbox rows: &f" + mail.size());
            for (DeliveryDao.Delivery d : mail) {
                out.add("&8  #" + d.id() + " " + d.status() + " &7for " + name(d.player()) + ": &f"
                        + what.apply(d.itemB64()) + " &8(" + d.label() + ")");
            }
        }
        if (!pending && now != null) {
            // red-team finding AW-1: a finished trade is never reopened; this only says where each bag is now, and
            // explains a bag from before it only when there is one (R5-NORMAL-1)
            boolean older = Boolean.FALSE.equals(now[0]) || Boolean.FALSE.equals(now[1]);
            out.add("&7Its bags now: &f" + bagNow(t.aName(), now[0]) + "&7; &f" + bagNow(t.bName(), now[1])
                    + (older ? " &8(a finished trade is never reopened: a bag from before it was put back from an"
                    + " older copy, so set right by hand only what you want undone)" : ""));
        }
        if (pending) {
            out.add("&cThis trade didn't finish. &7Its Mailbox rows are held until it is settled.");
            if (now != null) {
                out.add("&7Its bags now: &f" + bagNow(t.aName(), now[0]) + "&7; &f" + bagNow(t.bName(), now[1]));
            }
            // red-team findings TW-2, TCS-2, TCS-3: which verb, from where its bags are (their marks)
            TradeStore.Saved s = state == null ? TradeStore.Saved.UNKNOWN : state;
            String verb = TradeStore.verb(t, s);
            out.add((verb == null ? "&eLook first: " : verb.equals("release") ? "&aRelease it: " : "&eVoid it: ")
                    + "&7" + TradeStore.settleAdvice(t, s) + ".");
            if (money != null && !money.isEmpty()) {
                out.add("&7" + money);
            }
            // red-team finding TCR4-OVERRIDE-3: what each verb does, and the admin's word over the read
            out.add("&8release hands the held rows out (done); void deletes them and puts Mini owners and claims back;"
                    + " void <player> also sends that player's own offer to their Mailbox; add confirm to go against"
                    + " the read.");
        }
        return out;
    }

    /** One bag as its mark reads now, exactly (red-team finding TCR4-2). */
    private static String bagNow(String name, Boolean holds) {
        return name + "'s " + (holds == null ? "can't say (a later trade, or no file or mark)"
                : holds ? "holds the trade" : "is from before it");
    }

    private static String stored(String items) {
        if (items == null || items.isBlank()) {
            return "";
        }
        return " &8(" + items.split("\n").length + " stack(s) stored)";
    }

    private static String name(UUID id) {
        try {
            String n = Bukkit.getOfflinePlayer(id).getName();
            return n == null ? id.toString().substring(0, 8) : n;
        } catch (RuntimeException e) {
            return id.toString().substring(0, 8);
        }
    }

    /** {@code settle <id> release|void [player] [confirm]} read; null when it isn't that. */
    record Settle(long id, boolean release, String player, boolean confirm) {
    }

    /** Read {@code /hcm trades settle <id> release|void [player] [confirm]} (a player only after void). */
    static Settle settleArgs(String[] args) {
        Long id = args.length >= 3 ? id(args[2]) : null;
        String how = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        if (id == null || !(how.equals("release") || how.equals("void"))) {
            return null;
        }
        String player = null;
        boolean confirm = false;
        for (int i = 4; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("confirm") && !confirm) {
                confirm = true;
            } else if (how.equals("void") && player == null && !confirm) {
                player = args[i];
            } else {
                return null;
            }
        }
        return new Settle(id, how.equals("release"), player, confirm);
    }

    /**
     * {@code /hcm trades settle <id> release|void [player] [confirm]}: a PENDING trade's held Mailbox rows handed out,
     * or deleted (and, with a player, that player's own offer to their Mailbox); a verb that doesn't fit its bags is
     * refused unless the admin confirms they looked.
     */
    private void settle(CommandSender sender, TradeService trades, String[] args) throws SQLException {
        Settle s = settleArgs(args);
        if (s == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm trades settle <id> release|void [player] [confirm]"));
            return;
        }
        for (String l : trades.settle(s.id(), s.release(), s.player(), s.confirm(), sender.getName())) {
            sender.sendMessage(Text.of(l));
        }
    }

    /** {@code /hcm trades} completions (args include the {@code trades} word). */
    public List<String> completeTrades(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("hcm.admin")) {
            return out;
        }
        if (args.length == 2) {
            for (String s : List.of("show", "settle")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            players(sender, args[1].toLowerCase(Locale.ROOT), out);
        } else if (args.length == 4 && args[1].equalsIgnoreCase("settle")) {
            for (String s : List.of("release", "void")) {
                if (s.startsWith(args[3].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length >= 5 && args[1].equalsIgnoreCase("settle")
                && "confirm".startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) {
            out.add("confirm");
        }
        return out;
    }

    /** The help lines a sender may use. */
    public static List<String> helpLines(CommandSender sender) {
        List<String> out = new ArrayList<>();
        if (sender.hasPermission(TradeService.PERMISSION)) {
            out.add("&e/trade <player> &7- trade with someone (or /hcm trade <player>)");
        }
        if (sender.hasPermission("hcm.admin")) {
            out.add("&e/hcm trades [player] [n] | show <id> | settle <id> release|void [player] [confirm] &7- the"
                    + " trade log");
        }
        return out;
    }

    private static Integer number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long id(String s) {
        try {
            return Long.parseLong(s.startsWith("#") ? s.substring(1) : s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code args} without its first word. */
    public static String[] rest(String[] args) {
        return args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
    }
}
