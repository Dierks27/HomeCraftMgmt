package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyService;
import com.dierks.homecraft.config.CandyConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketService;
import com.dierks.homecraft.market.MarketState;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code /hcm} — admin & utility commands.
 * <ul>
 *   <li>{@code /hcm reload} — reload config.yml + recipes + market catalog. (admin)</li>
 *   <li>{@code /hcm give <printer|pc|vending|display|mailbox [variant]|pallet|arcade|…> [player]} — hand out a custom item. (admin)</li>
 *   <li>{@code /hcm give prize <id> [player]} — give back a lost Prize Counter prize, free; a
 *       collector given to someone else is a present. (admin)</li>
 *   <li>{@code /hcm market list} — list catalog + live prices. (hcm.market.list, op-only)</li>
 *   <li>{@code /hcm market price|history <item>} — inspect one item. (hcm.market.price, all)</li>
 *   <li>{@code /hcm market sell <item> <qty>} — sell into the engine. (hcm.market.order)</li>
 *   <li>{@code /hcm market buy <item> <qty>} — admin only (hcm.market.buy). Players order at
 *       the store, where shipping is charged; this command pays no shipping and waits for
 *       nothing, so leaving it open to everyone made every shipping tier pointless.</li>
 *   <li>{@code /hcm market resetstock <item|all>} — reseed stock + price from config. (admin)</li>
 *   <li>{@code /hcm market setstock <item> <amount>} — set one item's stock. (admin)</li>
 *   <li>{@code /hcm market news [on|off]} — the Crate Market news; news in chat on or off.
 *       (hcm.market.price, all)</li>
 *   <li>{@code /hcm market news <item> <up|down|wanted> …} and {@code /hcm market sim …} — run
 *       and test the live market; see {@link MarketSimCommand}. (hcm.market.sim, op-only)</li>
 *   <li>{@code /hcm balance} — Vault money + Arcade tokens together. (hcm.market.price)</li>
 *   <li>{@code /hcm play …}, {@code /hcm leave} and {@code /hcm games …} — playing and running the
 *       Games; see {@link GamesCommand}. (hcm.games.play / hcm.games.admin)</li>
 *   <li>{@code /hcm candy} — your candy apples, your Trick-or-Treat Bag and the next shower.
 *       (hcm.arcade.use)</li>
 *   <li>{@code /hcm candy now [player]|status|clear|bag <player>|add <player> <n>} — run the Candy
 *       Apple shower. (admin)</li>
 * </ul>
 *
 * <p>Each subcommand checks its own permission — the command node itself is ungated, so
 * splitting {@code hcm.use} into {@code hcm.market.list} / {@code hcm.market.price} can
 * hide the full catalog dump from players without also locking them out of {@code /hcm}.
 */
public final class HcmCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_QTY = 4096;
    /** How long a {@code resetstock all} confirmation stays armed. */
    private static final long RESET_CONFIRM_WINDOW_MS = 10_000L;

    private final HomeCraftManagement plugin;
    /** Sender name → when they armed a {@code resetstock all}. */
    private final Map<String, Long> resetConfirmations = new HashMap<>();
    /** {@code /hcm market news …} and {@code /hcm market sim …} (the live market). */
    private final MarketSimCommand liveMarket;
    /** {@code /hcm play …}, {@code /hcm leave} and {@code /hcm games …} (the Games). */
    private final GamesCommand gamesCommand;

    public HcmCommand(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.liveMarket = new MarketSimCommand(plugin);
        this.gamesCommand = new GamesCommand(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        // Inside a world game only play, leave, games and help work: any other screen could hand
        // the player items the session would then lose, or take them somewhere it can't follow.
        if (gamesCommand.refuseInSession(sender, args)) {
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
                }
                String raceNight = com.dierks.homecraft.games.event.RaceNight.reloadWarning(plugin.games()); // EV fix: §A.9
                if (raceNight != null) {
                    sender.sendMessage(Text.of(raceNight));
                }
                plugin.reloadAll();
                sender.sendMessage(Text.of("&aHomeCraft Management configuration reloaded."));
            }
            case "give" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
                }
                handleGive(sender, args);
            }
            case "admin" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the Admin Studio."));
                    return true;
                }
                new com.dierks.homecraft.gui.admin.AdminMenu(plugin, player).open(player);
            }
            case "market" -> handleMarket(sender, args);
            case "display", "displays" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
                }
                handleDisplay(sender, args);
            }
            case "mini", "minis" -> handleMini(sender, args);
            case "printer" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
                }
                handlePrinter(sender, args);
            }
            case "binder" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open a binder."));
                    return true;
                }
                new com.dierks.homecraft.gui.mini.BinderMenu(plugin, player, false).open(player);
            }
            case "packs", "pack" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the pack menus."));
                    return true;
                }
                if (sender.hasPermission("hcm.admin")) {
                    new com.dierks.homecraft.gui.admin.PacksAdminMenu(plugin, player, null).open(player);
                } else {
                    new com.dierks.homecraft.gui.mini.PackShopMenu(plugin, player, null).open(player);
                }
            }
            case "arcade" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("odds")) {
                    gamesCommand.odds(sender); // players: one line per game of chance; admins: the detail
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the Arcade."));
                    return true;
                }
                if (denyUnless(sender, "hcm.arcade.use")) {
                    return true;
                }
                new com.dierks.homecraft.gui.arcade.ArcadeMenu(plugin, player).open(player);
            }
            case "balance", "bal", "wallet" -> handleBalance(sender);
            case "trail", "trails" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players have trails."));
                    return true;
                }
                if (args.length >= 2 && !args[1].equalsIgnoreCase("toggle")) {
                    if (args[1].equalsIgnoreCase("off")) {
                        var cur = plugin.trails().current(player);
                        player.sendMessage(Text.of(cur == null ? "&7Your trail is already off."
                                : plugin.trails().toggle(player)));
                    } else {
                        player.sendMessage(Text.of(plugin.trails().select(player, args[1])));
                    }
                } else {
                    player.sendMessage(Text.of(plugin.trails().toggle(player)));
                }
            }
            case "tokens" -> handleTokens(sender, args);
            case "play" -> gamesCommand.play(sender, args);
            case "leave" -> gamesCommand.leave(sender, args);
            case "games" -> gamesCommand.admin(sender, args);
            case "config" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
                }
                if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
                    sender.sendMessage(Text.of("&7Usage: /hcm config reset <arcade|arcade.<part>|games|games.<part>|packs"
                            + "|minis.loot.natural|minis.effects|clock> [confirm]"));
                    return true;
                }
                boolean confirm = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
                for (String line : plugin.resetConfigSection(args[2], confirm)) {
                    sender.sendMessage(Text.of(line));
                }
            }
            case "homes" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
                }
                if (args.length < 2 || !args[1].equalsIgnoreCase("refresh")) {
                    sender.sendMessage(Text.of("&7Usage: /hcm homes refresh [player]"));
                    return true;
                }
                var homes = plugin.homes();
                if (homes == null || !homes.available()) {
                    sender.sendMessage(Text.of("&cThe +1 Home perk needs LuckPerms and an Essentials config.yml."));
                    return true;
                }
                homes.reload();
                if (args.length >= 3) {
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        sender.sendMessage(Text.of("&cPlayer '" + args[2] + "' is not online."));
                        return true;
                    }
                    homes.refresh(target, 0);
                    sender.sendMessage(Text.of("&a" + target.getName() + ": base " + homes.base(target) + " + bought "
                            + homes.bonus(target.getUniqueId()) + " = " + homes.total(target) + " homes. Checked."));
                } else {
                    homes.refreshAll();
                    sender.sendMessage(Text.of("&aChecked every online player's homes."));
                }
            }
            case "achievements", "achievement" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players have achievements."));
                    return true;
                }
                if (denyUnless(sender, "hcm.achievements.use")) {
                    return true;
                }
                com.dierks.homecraft.gui.arcade.ArcadeScreens.achievements(plugin, player);
            }
            case "guide" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cThe guide is a screen — open it in game."));
                    return true;
                }
                if (denyUnless(sender, "hcm.guide.use")) {
                    return true;
                }
                int page = 0;
                if (args.length >= 2) {
                    page = switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "minis", "packs", "cards" -> com.dierks.homecraft.gui.arcade.GuideMenu.MINIS;
                        case "wild", "hunt" -> com.dierks.homecraft.gui.arcade.GuideMenu.WILD;
                        case "arcade" -> com.dierks.homecraft.gui.arcade.GuideMenu.ARCADE;
                        case "games" -> com.dierks.homecraft.gui.arcade.GuideMenu.GAMES_PAGE;
                        default -> com.dierks.homecraft.gui.arcade.GuideMenu.TOKENS;
                    };
                }
                com.dierks.homecraft.gui.arcade.ArcadeScreens.guide(plugin, player, page);
            }
            case "quests", "quest" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the Quests board."));
                    return true;
                }
                if (denyUnless(sender, "hcm.quests.use")) {
                    return true;
                }
                new com.dierks.homecraft.gui.arcade.QuestsMenu(plugin, player, null).open(player);
            }
            case "courier" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the courier board."));
                    return true;
                }
                if (denyUnless(sender, "hcm.courier.use")) {
                    return true;
                }
                if (!plugin.config().courier().enabled()) {
                    sender.sendMessage(Text.of("&cThe courier board is closed."));
                    return true;
                }
                // Admin/testing route. The player-facing way in is the Courier Site on the PC
                // (§3.2, §2.2) — this exists so a run can be driven without walking to one.
                new com.dierks.homecraft.gui.courier.JobBoardMenu(plugin, player, null).open(player);
            }
            case "museum" -> handleMuseum(sender, args);
            case "hunt" -> handleHunt(sender, args);
            case "candy" -> handleCandy(sender, args);
            case "backup" -> handleBackup(sender, args);
            case "auction", "auctions" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the Auction House."));
                    return true;
                }
                if (denyUnless(sender, "hcm.auction.list")) {
                    return true;
                }
                new com.dierks.homecraft.gui.mini.AuctionMenu(plugin, player, null).open(player);
            }
            default -> usage(sender);
        }
        return true;
    }

    // ---------------------------------------------------------------------
    //  mini (admin/test — the Museum GUI is the player-facing path)
    // ---------------------------------------------------------------------

    private void handleMini(CommandSender sender, String[] args) {
        com.dierks.homecraft.mini.MiniService minis = plugin.miniService();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "museum";

        switch (sub) {
            case "museum" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the Museum."));
                    return;
                }
                // Same node as /hcm museum — this is the same door, and a bare `/hcm mini`
                // defaults here, so leaving it open would make hcm.museum.use unenforceable.
                if (denyUnless(sender, "hcm.museum.use")) {
                    return;
                }
                new com.dierks.homecraft.gui.MuseumMenu(plugin, player, null).open(player);
            }
            case "list" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return;
                }
                if (minis.catalog().isEmpty()) {
                    sender.sendMessage(Text.of("&7No Minis configured."));
                    return;
                }
                sender.sendMessage(Text.of("&5Minis — " + minis.catalog().size() + " entry(ies):"));
                for (com.dierks.homecraft.mini.MiniDef def : minis.catalog()) {
                    var c = minis.counts(def.id());
                    String cap = def.uncapped() ? "∞" : Long.toString(def.cap());
                    sender.sendMessage(Text.of("&d" + def.id() + " &7(" + def.rarity().display() + ") &f" + def.name()
                            + " &7minted " + c.minted() + "/" + cap + ", circ " + c.circulation()
                            + (c.escaped() > 0 ? ", got away " + c.escaped() : "")
                            + (minis.reserved(def.id()) > 0 ? ", " + minis.reserved(def.id()) + " loose in the wild" : "")));
                }
            }
            case "give" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.of("&cUsage: /hcm mini give <id> [player]"));
                    return;
                }
                String id = com.dierks.homecraft.mini.MiniIds.slug(args[2]);
                Player target;
                if (args.length >= 4) {
                    target = Bukkit.getPlayerExact(args[3]);
                    if (target == null) {
                        sender.sendMessage(Text.of("&cPlayer '" + args[3] + "' is not online."));
                        return;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(Text.of("&cSpecify a player: /hcm mini give " + id + " <player>"));
                    return;
                }
                com.dierks.homecraft.mini.MiniService.MintResult r = minis.giveAdmin(target, id);
                sender.sendMessage(r.ok()
                        ? Text.of("&aGave " + id + " #" + r.mintNumber() + " to " + target.getName() + ".")
                        : Text.of("&c" + r.error()));
            }
            case "capturestand" -> handleCaptureStand(sender, args);
            case "repair-escaped" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return;
                }
                handleRepairEscaped(sender, args.length >= 3 && args[2].equalsIgnoreCase("confirm"));
            }
            default -> sender.sendMessage(Text.of("&cUsage: /hcm mini <museum|list|give|capturestand|repair-escaped> …"));
        }
    }

    /**
     * {@code /hcm mini repair-escaped [confirm]} — give back the mint numbers the old wild hunt
     * burned. Before the hunt minted on pickup, a spawn minted its copy the moment it appeared
     * and retired it when it escaped, so the number and the cap slot were gone for good. Those
     * copies are exactly the retired ones that never had an owner and never changed hands.
     *
     * <p>Without {@code confirm} it is a dry run that lists what it found. With it: a database
     * backup first, then each copy's row is deleted, it leaves {@code minted} and
     * {@code destroyed}, is counted as escaped, and its number goes back into the pool the next
     * mint of that Mini draws from, lowest first.
     */
    private void handleRepairEscaped(CommandSender sender, boolean confirm) {
        List<com.dierks.homecraft.storage.MiniDao.EscapedCopy> found;
        try {
            found = plugin.miniService().escapedCopies();
        } catch (java.sql.SQLException e) {
            sender.sendMessage(Text.of("&cCould not read the Mini records: " + e.getMessage()));
            return;
        }
        if (found.isEmpty()) {
            sender.sendMessage(Text.of("&aNo escaped copies to repair — every mint number is accounted for."));
            return;
        }
        Map<String, List<Long>> byMini = new java.util.LinkedHashMap<>();
        for (com.dierks.homecraft.storage.MiniDao.EscapedCopy c : found) {
            byMini.computeIfAbsent(c.miniId(), k -> new ArrayList<>()).add(c.mintNumber());
        }
        sender.sendMessage(Text.of("&6" + (confirm ? "Repairing" : "Dry run:") + " &f" + found.size()
                + " &6escaped cop" + (found.size() == 1 ? "y" : "ies") + " across &f" + byMini.size() + " &6Mini(s)"));
        for (Map.Entry<String, List<Long>> e : byMini.entrySet()) {
            List<String> numbers = new ArrayList<>();
            for (long n : e.getValue()) {
                numbers.add("#" + n);
            }
            com.dierks.homecraft.mini.MiniDef def = plugin.miniService().def(e.getKey());
            sender.sendMessage(Text.of("&e" + (def != null ? def.name() : e.getKey()) + " &7(" + e.getKey() + "): &f"
                    + String.join(", ", numbers)));
        }
        if (!confirm) {
            sender.sendMessage(Text.of("&7Nothing changed. Run &f/hcm mini repair-escaped confirm &7to give "
                    + "these numbers back (a database backup is taken first)."));
            return;
        }
        java.io.File backup = plugin.backups() != null ? plugin.backups().backupNow("pre-repair-escaped") : null;
        if (backup == null) {
            sender.sendMessage(Text.of("&cThe database backup failed, so nothing was repaired. Check the log."));
            return;
        }
        try {
            int done = plugin.miniService().repairEscaped(found);
            plugin.getLogger().info("repair-escaped: " + done + " escaped cop" + (done == 1 ? "y" : "ies")
                    + " repaired by " + sender.getName() + " (backup " + backup.getName() + ").");
            sender.sendMessage(Text.of("&aRepaired &f" + done + "&a. Backup: &f" + backup.getName()
                    + "&a. Those numbers are re-used, lowest first, the next time each Mini is made."));
        } catch (java.sql.SQLException e) {
            sender.sendMessage(Text.of("&cRepair failed and was rolled back: " + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    //  hunt (admin — the wild hunt)
    // ---------------------------------------------------------------------

    private void handleHunt(CommandSender sender, String[] args) {
        if (denyUnless(sender, "hcm.admin")) {
            return;
        }
        com.dierks.homecraft.hunt.HuntService hunt = plugin.hunt();
        if (hunt == null) {
            sender.sendMessage(Text.of("&cThe wild hunt is not running."));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "spawn" -> {
                com.dierks.homecraft.mini.Rarity rarity = null;
                int next = 2;
                if (args.length > next) {
                    try {
                        rarity = com.dierks.homecraft.mini.Rarity.valueOf(args[next].toUpperCase(Locale.ROOT));
                        next++;
                    } catch (IllegalArgumentException ignored) {
                        // not a rarity — treat it as the player name
                    }
                }
                Player target;
                // Naming a player puts it right by them. With no name it lands the way a natural
                // roll of yours would, so an armed Mini Lure (or the next-target override) wins.
                boolean asRoll = args.length <= next;
                if (!asRoll) {
                    target = Bukkit.getPlayerExact(args[next]);
                    if (target == null) {
                        sender.sendMessage(Text.of("&cPlayer '" + args[next] + "' is not online."));
                        return;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(Text.of("&cUsage: /hcm hunt spawn [rarity] <player>"));
                    return;
                }
                String err = hunt.forceSpawn(rarity, target, asRoll);
                sender.sendMessage(err == null
                        ? Text.of(asRoll ? "&aA wild Mini appeared (as a natural roll — a Mini Lure wins). "
                                + "&7/hcm hunt status for where."
                                : "&aA wild Mini appeared near " + target.getName() + ". &7/hcm hunt status for where.")
                        : Text.of("&c" + err));
            }
            case "clear" -> {
                int n = hunt.clear();
                sender.sendMessage(Text.of("&aCleared " + n + " wild Mini" + (n == 1 ? "" : "s")
                        + ". &7Nothing was minted and nothing counts as escaped."));
            }
            case "status" -> {
                java.util.Collection<com.dierks.homecraft.hunt.HuntService.Hunt> live = hunt.live();
                sender.sendMessage(Text.of("&6Wild hunt &7— " + live.size() + " live"));
                for (com.dierks.homecraft.hunt.HuntService.Hunt h : live) {
                    com.dierks.homecraft.mini.MiniDef def = plugin.miniService().def(h.miniId());
                    String who = h.target() == null ? "?" : playerName(h.target());
                    sender.sendMessage(Text.of("&e" + (def != null ? def.name() + " &7(" + def.rarity().display() + ")"
                            : h.miniId()) + " &7" + h.grade().display() + (h.shiny() ? " Shiny" : "")
                            + " near &f" + who + " &7at &f" + h.world() + " " + h.x() + "," + h.y() + "," + h.z()
                            + " &7— " + com.dierks.homecraft.gui.Menus.duration(hunt.msLeft(h)) + " left, hint "
                            + h.hintStage()));
                }
                Map<java.util.UUID, Long> lures = hunt.lures();
                if (!lures.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (java.util.UUID id : lures.keySet()) {
                        names.add(playerName(id));
                    }
                    sender.sendMessage(Text.of("&7Armed lures (earliest first): &f" + String.join(", ", names)));
                }
                if (hunt.nextTarget() != null) {
                    sender.sendMessage(Text.of("&7Next spawn goes to: &f" + playerName(hunt.nextTarget())));
                }
            }
            default -> sender.sendMessage(Text.of("&cUsage: /hcm hunt <spawn [rarity] [player]|status|clear>"));
        }
    }

    // ---------------------------------------------------------------------
    //  candy (the Candy Apple shower: your own count; admins run it)
    // ---------------------------------------------------------------------

    /** {@code /hcm candy}'s admin words (hcm.admin). With no word it is the player's own count (hcm.arcade.use). */
    static final List<String> CANDY_VERBS = List.of("now", "status", "clear", "bag", "add");
    /** The most {@code /hcm candy add} adds at once. */
    static final int CANDY_ADD_MAX = 200;
    static final String CANDY_USAGE = "Usage: /hcm candy [now [player]|status|clear|bag <player>|add <player> <n>]";
    private static final DateTimeFormatter CANDY_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US);
    private static final DateTimeFormatter CANDY_CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    /** Where the shower stands today (D18): the words {@code /hcm candy} and its admin words use. */
    enum CandyState { OPEN, NOT_YET, OVER, OFF, ARCADE_OFF }

    /**
     * {@code /hcm candy} (HALLOWEEN-SPEC D19). With no word: the player's count, their bag and the next
     * shower, and a bag they are owed is tried again. The admin words: {@code now [player]} a shower now for
     * every eligible player (or the named one), one line each; {@code status}; {@code clear} removes every
     * live candy, counting nothing; {@code bag <player>} gives a bag back and sets its mark (the restore);
     * {@code add <player> <n>} adds to a count, then the goal check, to test the bag without collecting 31.
     *
     * <p>The permission and the words are checked before anything is read, so a refusal touches nothing.
     * Inside a world game the whole of {@code /hcm candy} is refused already ({@link GamesCommand#refuseInSession}).
     */
    private void handleCandy(CommandSender sender, String[] args) {
        String verb = candyVerb(args);
        if (verb == null) {
            if (!denyUnless(sender, "hcm.arcade.use")) {
                sender.sendMessage(Text.of("&c" + (sender.hasPermission("hcm.admin") ? CANDY_USAGE : "Usage: /hcm candy")));
            }
            return;
        }
        if (denyUnless(sender, candyPermission(verb))) {
            return;
        }
        String wrong = candyArgsError(verb, args);
        if (wrong != null) {
            sender.sendMessage(Text.of("&c" + wrong));
            return;
        }
        if (verb.isEmpty() && !(sender instanceof Player)) {
            sender.sendMessage(Text.of("&cOnly players collect candy apples. &7" + CANDY_USAGE));
            return;
        }
        CandyService candy = plugin.candy();
        if (candy == null) {
            sender.sendMessage(Text.of("&cThe Candy Apple shower is not running."));
            return;
        }
        switch (verb) {
            case "now" -> candyNow(sender, args, candy);
            case "status" -> candyStatus(sender, candy);
            case "clear" -> {
                int n = candy.clear();
                sender.sendMessage(Text.of("&aCleared " + n + " candy apple" + (n == 1 ? "" : "s")
                        + ". &7Nothing was counted."));
            }
            case "bag" -> candyBag(sender, args[2], candy);
            case "add" -> candyAdd(sender, args[2], candyAddAmount(args[3]), candy);
            default -> candyMine((Player) sender, candy);
        }
    }

    /** {@code /hcm candy}: the player's count, their bag, and when the next shower is. An owed bag is tried again. */
    private void candyMine(Player player, CandyService candy) {
        CandyConfig.Candy c = plugin.config().candy();
        com.dierks.homecraft.storage.AchievementDao dao = new com.dierks.homecraft.storage.AchievementDao(plugin.database());
        long found;
        boolean bag;
        boolean away = false;
        try {
            found = dao.counter(player.getUniqueId(), CandyRules.counterKey(c.year()));
            bag = dao.counter(player.getUniqueId(), CandyRules.bagKey(c.year())) > 0;
            if (found >= c.goal() && !bag) {
                // Owed (a full bag, or back from a game since): tried again now, even after the window (D18).
                away = candy.tryBag(player) == CandyService.BagResult.WAITING;
                bag = dao.counter(player.getUniqueId(), CandyRules.bagKey(c.year())) > 0;
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("Failed to read " + player.getName() + "'s candy apples: " + e.getMessage());
            player.sendMessage(Text.of("&cYour candy apples couldn't be read right now. Try again in a moment."));
            return;
        }
        CandyState state = candyState();
        String next = null;
        if (state == CandyState.OPEN) {
            LocalDateTime now = localNow();
            LocalDateTime at = nextShower(now, c, candyHeld());
            next = at == null ? null : showerWhen(now, at);
        }
        for (String line : candyMineLines(state, c.from(), found, c.goal(), bag, next, away)) {
            player.sendMessage(Text.of(line));
        }
    }

    /** {@code /hcm candy now [player]}: a shower now for every eligible player, or the named one. */
    private void candyNow(CommandSender sender, String[] args, CandyService candy) {
        String closed = candyClosed(candyState(), plugin.config().candy());
        if (closed == null) {
            closed = candy.nowRefusal(); // open, but its task isn't running
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
            who.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        }
        if (who.isEmpty()) {
            sender.sendMessage(Text.of("&7Nobody is online."));
            return;
        }
        int showered = 0;
        for (Player p : who) {
            String why = candy.showerNow(p);
            if (why == null) {
                showered++;
            }
            sender.sendMessage(Text.of(candyNowLine(p.getName(), why)));
        }
        if (who.size() > 1) {
            sender.sendMessage(Text.of("&7" + showered + " of " + who.size() + " showered."));
        }
    }

    /**
     * {@code /hcm candy status}: on or off; the window, in which zone; the next shower (and one a restart
     * skips); live candy; each online player's count, bag and idle minutes; and when this season's
     * collectors leave the shop, with the zone, so a UTC fallback shows (red-team m8a).
     */
    private void candyStatus(CommandSender sender, CandyService candy) {
        CandyConfig.Candy c = plugin.config().candy();
        ZoneId zone = plugin.clock().zone();
        LocalDate today = plugin.clock().today();
        CandyState state = candyState(c, plugin.config().arcade().enabled(), today);
        sender.sendMessage(Text.of("&6Candy Apple shower " + c.year() + " &7- " + candyStateText(state)));
        sender.sendMessage(Text.of("&7" + candyWindowText(c, zone)));
        sender.sendMessage(Text.of("&7Next shower " + candyNextText(state, localNow(), c, candyHeld())));

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        Map<java.util.UUID, Integer> live = candy.liveByPlayer();
        int total = candy.liveTotal();
        sender.sendMessage(Text.of("&7Live candy: &f" + total + " &7(at most " + CandyRules.LIVE_PER_PLAYER
                + " a player, " + CandyRules.LIVE_ON_SERVER + " on the server)"));
        if (online.isEmpty()) {
            sender.sendMessage(Text.of("&7Nobody is online."));
        }
        com.dierks.homecraft.storage.AchievementDao dao = new com.dierks.homecraft.storage.AchievementDao(plugin.database());
        for (Player p : online) {
            long found = -1;
            boolean bag = false;
            try {
                found = dao.counter(p.getUniqueId(), CandyRules.counterKey(c.year()));
                bag = dao.counter(p.getUniqueId(), CandyRules.bagKey(c.year())) > 0;
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("Failed to read " + p.getName() + "'s candy apples: " + e.getMessage());
            }
            sender.sendMessage(Text.of(candyPlayerLine(p.getName(), found, c.goal(), bag, live.getOrDefault(p.getUniqueId(), 0),
                    CandyRules.idleMinutes(p.getIdleDuration().toMillis()))));
        }
        boolean onSale = plugin.config().arcade().collectorsOnSale();
        for (com.dierks.homecraft.config.PluginConfig.Prize p : com.dierks.homecraft.arcade.Collectors.ROWS) {
            if (p.collector() != null && p.collector().inSeason(today)) {
                sender.sendMessage(Text.of("&7" + collectorSaleLine(p.display(), p.collector(), onSale, zone)));
            }
        }
    }

    /** {@code /hcm candy bag <player>}: one bag, given back free and marked (room needed, home from every game). */
    private void candyBag(CommandSender sender, String name, CandyService candy) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer '" + name + "' is not online."));
            return;
        }
        if (!homeFromGames(target)) {
            // A world game's restore would wipe a bag handed into its inventory, mark and all (red-team m2).
            sender.sendMessage(Text.of("&c" + target.getName() + " is in a game. Try again when they're back."));
            return;
        }
        if (target.isDead()) {
            sender.sendMessage(Text.of("&c" + target.getName() + " is respawning. Try again in a moment."));
            return;
        }
        if (target.getInventory().firstEmpty() == -1) {
            sender.sendMessage(Text.of("&c" + target.getName() + "'s bag is full."));
            return;
        }
        String refused = candy.giveBag(target);
        if (refused != null) {
            sender.sendMessage(Text.of("&c" + refused));
            return;
        }
        int year = plugin.config().candy().year();
        plugin.getLogger().info("Admin " + sender.getName() + " gave a Trick-or-Treat Bag " + year + " to " + target.getName());
        sender.sendMessage(Text.of("&aGave the Trick-or-Treat Bag " + year + " to " + target.getName() + "."));
    }

    /** {@code /hcm candy add <player> <n>}: n more on the player's count, then the goal check (D15). */
    private void candyAdd(CommandSender sender, String name, int n, CandyService candy) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer '" + name + "' is not online."));
            return;
        }
        CandyConfig.Candy c = plugin.config().candy();
        // Through the service, which keeps the online players' counts; it runs the goal check after the write.
        CandyService.Added added = candy.add(target, n);
        if (added == null) {
            sender.sendMessage(Text.of("&cThat couldn't be saved. See the console."));
            return;
        }
        plugin.getLogger().info("Admin " + sender.getName() + " added " + n + " candy apples for " + target.getName());
        sender.sendMessage(Text.of("&aAdded " + n + " candy apple" + (n == 1 ? "" : "s") + " for " + target.getName()
                + ": &f" + added.total() + " &7/ " + c.goal()));
        String bagLine = candyAddBagLine(target.getName(), added.bag());
        if (bagLine != null) {
            sender.sendMessage(Text.of(bagLine));
        }
    }

    /** What the goal check after {@code /hcm candy add} did, for the admin; null when there is nothing to say. */
    static String candyAddBagLine(String name, CandyService.BagResult bag) {
        if (bag == null) {
            return null;
        }
        return switch (bag) {
            case GIVEN -> "&a" + name + " got their Trick-or-Treat Bag.";
            case WAITING -> "&7Their Trick-or-Treat Bag is waiting: " + name + " is in a game or another world.";
            case FULL -> "&7Their Trick-or-Treat Bag is waiting: " + name + "'s bag is full.";
            case FAILED -> "&cTheir Trick-or-Treat Bag couldn't be given. See the console.";
            case HAS_IT, NOT_YET -> null;
        };
    }

    private CandyState candyState() {
        return candyState(plugin.config().candy(), plugin.config().arcade().enabled(), plugin.clock().today());
    }

    /** The wall clock where the players live ({@code clock.time_zone}): slots are local minutes. */
    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(plugin.clock().nowMillis()), plugin.clock().zone());
    }

    /**
     * Whether a restart is close at a slot's start, so the shower skips it (D10): the Games' own hold, or,
     * with the Games off, the same settings read without them.
     */
    private Predicate<LocalDateTime> candyHeld() {
        com.dierks.homecraft.games.GamesService games = plugin.games();
        ZoneId zone = plugin.clock().zone();
        com.dierks.homecraft.games.RestartHold hold;
        if (games != null) {
            hold = games.restartHold();
        } else {
            com.dierks.homecraft.config.GamesConfig.Common common = plugin.config().games().common();
            hold = new com.dierks.homecraft.games.RestartHold(common.restartTimes(), zone, common.restartHoldMinutes());
        }
        return t -> CandyRules.heldByRestart(CandyRules.slotId(t.toLocalDate(), t.getHour() * 60 + t.getMinute()),
                zone, hold);
    }

    /** {@code /hcm candy}'s word, lower case: "" for none, null for one it doesn't know. */
    static String candyVerb(String[] args) {
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            return "";
        }
        String w = args[1].trim().toLowerCase(Locale.ROOT);
        return CANDY_VERBS.contains(w) ? w : null;
    }

    /** Who may use the word: anyone with the Arcade for their own count, admins for the rest (no new nodes). */
    static String candyPermission(String verb) {
        return verb == null || verb.isEmpty() ? "hcm.arcade.use" : "hcm.admin";
    }

    /** What is wrong with the words after {@code /hcm candy <verb>}, or null when they will do. */
    static String candyArgsError(String verb, String[] args) {
        if ("bag".equals(verb) && args.length < 3) {
            return "Usage: /hcm candy bag <player>";
        }
        if ("add".equals(verb)) {
            if (args.length < 4) {
                return "Usage: /hcm candy add <player> <n>";
            }
            if (candyAddAmount(args[3]) < 0) {
                return "Add 1-" + CANDY_ADD_MAX + " candy apples: /hcm candy add <player> <n>";
            }
        }
        return null;
    }

    /** {@code /hcm candy add}'s number: a whole 1-{@value #CANDY_ADD_MAX}, else -1. */
    static int candyAddAmount(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n >= 1 && n <= CANDY_ADD_MAX ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Where the shower stands on {@code today}: over after {@code to} and not yet before {@code from} (when the
     * window reads the right way round), then switched off ({@code enabled}, or a date config.yml got wrong),
     * then the Arcade's own switch.
     */
    static CandyState candyState(CandyConfig.Candy c, boolean arcadeOn, LocalDate today) {
        boolean window = !c.from().isAfter(c.to());
        if (window && today.isAfter(c.to())) {
            return CandyState.OVER;
        }
        if (window && today.isBefore(c.from())) {
            return CandyState.NOT_YET;
        }
        if (!c.enabled() || !window) {
            return CandyState.OFF;
        }
        return arcadeOn ? CandyState.OPEN : CandyState.ARCADE_OFF;
    }

    /** Why {@code /hcm candy now} refuses (D18, D19), or null when the shower is open. */
    static String candyClosed(CandyState state, CandyConfig.Candy c) {
        return switch (state) {
            case OPEN -> null;
            case NOT_YET -> "The Candy Apple shower starts on " + CANDY_DAY.format(c.from()) + ".";
            case OVER -> "The Candy Apple shower is over for this year.";
            case OFF -> "The Candy Apple shower is switched off in config.yml (arcade.events.candy).";
            case ARCADE_OFF -> "The Arcade is switched off (arcade.enabled: false), and the candy with it.";
        };
    }

    /** The status's headline word for the state. */
    static String candyStateText(CandyState state) {
        return switch (state) {
            case OPEN -> "&aon";
            case NOT_YET -> "&enot yet";
            case OVER -> "&7over";
            case OFF -> "&coff &7(arcade.events.candy)";
            case ARCADE_OFF -> "&coff &7(arcade.enabled: false)";
        };
    }

    /** The window and the schedule, with the zone the dates are read in. */
    static String candyWindowText(CandyConfig.Candy c, ZoneId zone) {
        String window = c.from().getYear() == c.to().getYear()
                ? CANDY_DAY.format(c.from()) + " - " + CANDY_DAY.format(c.to()) + " " + c.to().getYear()
                : CANDY_DAY.format(c.from()) + " " + c.from().getYear() + " - " + CANDY_DAY.format(c.to()) + " "
                        + c.to().getYear();
        int every = c.everyMinutes();
        String out = "Window: &f" + window + " &7(" + zoneName(zone) + "). Every &f" + every + " &7min from &f"
                + minuteText(CandyRules.offsetOn(c.offsetMinutes(), every));
        int last = CandyRules.everyOn(every, c.lastDayEveryMinutes(), true);
        if (last != every) {
            out += "&7, every &f" + last + " &7min on " + CANDY_DAY.format(c.to()) + " from &f"
                    + minuteText(CandyRules.offsetOn(c.offsetMinutes(), last));
        }
        return out + "&7.";
    }

    /** {@code 00:10}: a minute of the day on a 24-hour clock. */
    private static String minuteText(int minuteOfDay) {
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /** A zone's name for players to read: "UTC" rather than "Z" for the fallback. */
    static String zoneName(ZoneId zone) {
        return zone == null || zone.equals(ZoneOffset.UTC) ? "UTC" : zone.getId();
    }

    /**
     * The next shower after {@code after} that a restart won't skip, or null when the window has none left.
     * {@code held} is the restart hold at a slot's start (null: nothing is held).
     */
    static LocalDateTime nextShower(LocalDateTime after, CandyConfig.Candy c, Predicate<LocalDateTime> held) {
        LocalDateTime at = after;
        // A hold covers minutes, so a few slots at most; the bound only stops a hold that never lets go.
        for (int i = 0; i < 1000 && at != null; i++) {
            at = CandyRules.nextSlot(at, c.from(), c.to(), c.everyMinutes(), c.offsetMinutes(), c.lastDayEveryMinutes());
            if (at == null || held == null || !held.test(at)) {
                return at;
            }
        }
        return null;
    }

    /** "at 7:30 PM", "tomorrow at 12:10 AM" or "on 1 Oct at 12:10 AM", seen from {@code now}. */
    static String showerWhen(LocalDateTime now, LocalDateTime at) {
        String clock = CANDY_CLOCK.format(at);
        LocalDate day = at.toLocalDate();
        if (day.equals(now.toLocalDate())) {
            return "at " + clock;
        }
        if (day.equals(now.toLocalDate().plusDays(1))) {
            return "tomorrow at " + clock;
        }
        return "on " + CANDY_DAY.format(day) + " at " + clock;
    }

    /** The status's next shower, naming one a restart skips and the one after it. */
    static String candyNextText(CandyState state, LocalDateTime now, CandyConfig.Candy c, Predicate<LocalDateTime> held) {
        if (state != CandyState.OPEN && state != CandyState.NOT_YET) {
            return "&f- none";
        }
        LocalDateTime first = CandyRules.nextSlot(now, c.from(), c.to(), c.everyMinutes(), c.offsetMinutes(),
                c.lastDayEveryMinutes());
        if (first == null) {
            return "&f- none left this year";
        }
        if (held == null || !held.test(first)) {
            return "&f" + showerWhen(now, first);
        }
        LocalDateTime then = nextShower(first, c, held);
        return "&f" + showerWhen(now, first) + " &c(skipped: a restart is due) &7then "
                + (then == null ? "&fnone" : "&f" + showerWhen(now, then));
    }

    /** One line of {@code /hcm candy now}: "showered", or the reason the player was skipped (D17). */
    static String candyNowLine(String name, String why) {
        return why == null ? "&a" + name + " &7- showered" : "&e" + name + " &7- " + why;
    }

    /** One player's line in the status: their count, their candy out, idle minutes (kid-bedrock #15) and the bag. */
    static String candyPlayerLine(String name, long found, int goal, boolean bag, int live, long idleMinutes) {
        String state = bag ? "&agiven" : found >= goal ? "&6owed" : "&7not yet";
        return "&e" + name + " &7- found &f" + (found < 0 ? "?" : String.valueOf(found)) + "&7/" + goal + ", &f" + live
                + " &7out, idle &f" + idleMinutes + " &7min, bag: " + state;
    }

    /** "Pumpkin Cap on sale until 23:59 on 31 Oct (America/Chicago)": its last minute, in the server's zone. */
    static String collectorSaleLine(String display, com.dierks.homecraft.config.PluginConfig.Collector collector,
                                    boolean onSale, ZoneId zone) {
        String name = Text.plain(display);
        String year = " " + collector.year();
        if (name.endsWith(year)) {
            name = name.substring(0, name.length() - year.length());
        }
        return onSale ? name + " on sale until 23:59 on " + CANDY_DAY.format(collector.to()) + " (" + zoneName(zone) + ")"
                : name + " is not on sale (arcade.collectors.on_sale: false)";
    }

    /**
     * What {@code /hcm candy} tells a player: their count and the bag, then the next shower ({@code next}, from
     * {@link #showerWhen}; null when none is left) while the shower is open, or why there is none.
     */
    static List<String> candyMineLines(CandyState state, LocalDate from, long found, int goal, boolean bag, String next) {
        return candyMineLines(state, from, found, goal, bag, next, false);
    }

    /**
     * {@link #candyMineLines(CandyState, LocalDate, long, int, boolean, String)}, where {@code away} says an owed
     * bag waits because the player is in a game or another world, not for room in their bag.
     */
    static List<String> candyMineLines(CandyState state, LocalDate from, long found, int goal, boolean bag, String next,
                                       boolean away) {
        List<String> out = new ArrayList<>();
        switch (state) {
            case NOT_YET -> out.add("&6Candy apples start falling on " + CANDY_DAY.format(from) + "!");
            case OVER -> {
                out.add("&7The Candy Apple shower is over for this year.");
                if (found > 0 || bag) {
                    out.add(candyFound(found, goal, bag, false, away));
                }
            }
            case OPEN -> out.add(candyFound(found, goal, bag, true, away)
                    + (next == null ? " &7That was the last shower this year!" : " &7Next shower " + next + "."));
            default -> {
                out.add(candyFound(found, goal, bag, true, away));
                out.add("&7No candy apples are falling right now.");
            }
        }
        return out;
    }

    /** "You've found 17 candy apples. 14 more for the Trick-or-Treat Bag!", or the bag got or waiting. */
    static String candyFound(long found, int goal, boolean bag, boolean stillFalling) {
        return candyFound(found, goal, bag, stillFalling, false);
    }

    /** {@link #candyFound(long, int, boolean, boolean)}; {@code away}: an owed bag waits for the player to come back. */
    static String candyFound(long found, int goal, boolean bag, boolean stillFalling, boolean away) {
        String head = (stillFalling ? "&6You've found &f" : "&6You found &f") + found + " &6candy apple"
                + (found == 1 ? "" : "s") + ".";
        if (bag) {
            return head + " &aYou got your Trick-or-Treat Bag!";
        }
        if (found >= goal) {
            return head + (away ? " &eYour Trick-or-Treat Bag is waiting: it comes when you're back from the Games or"
                    + " another world!" : " &eYour Trick-or-Treat Bag is waiting: make room in your bag!");
        }
        return stillFalling ? head + " &f" + (goal - found) + " &6more for the Trick-or-Treat Bag!" : head;
    }

    /** {@code /hcm museum [id]} — open the browse-only Museum, optionally straight onto one Mini's card. */
    private void handleMuseum(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players can open the Museum."));
            return;
        }
        if (denyUnless(sender, "hcm.museum.use")) {
            return;
        }
        if (args.length >= 2 && !args[1].isBlank()) {
            String id = com.dierks.homecraft.mini.MiniIds.slug(args[1]);
            if (plugin.miniService().def(id) == null) {
                sender.sendMessage(Text.of("&cNo Mini called '" + args[1] + "'."));
                return;
            }
            com.dierks.homecraft.gui.MuseumMenu.openFor(plugin, player, id);
            return;
        }
        new com.dierks.homecraft.gui.MuseumMenu(plugin, player, null).open(player);
    }

    /** Capture the pose + equipment of the armor stand the admin is looking at into a Mini. */
    private void handleCaptureStand(CommandSender sender, String[] args) {
        if (denyUnless(sender, "hcm.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players can capture an armor stand."));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm mini capturestand <miniId>"));
            return;
        }
        String id = com.dierks.homecraft.mini.MiniIds.slug(args[2]);
        if (plugin.miniService().def(id) == null) {
            sender.sendMessage(Text.of("&cNo such Mini '" + id + "'."));
            return;
        }
        org.bukkit.entity.Entity target = player.getTargetEntity(6);
        if (!(target instanceof org.bukkit.entity.ArmorStand stand)) {
            sender.sendMessage(Text.of("&cLook directly at the armor stand you posed, then run this again."));
            return;
        }
        plugin.miniService().saveStand(id, com.dierks.homecraft.mini.StandData.capture(stand));
        sender.sendMessage(Text.of("&aCaptured pose + equipment into &f" + id
                + "&a. Placing that Mini now spawns this stand."));
    }

    // ---------------------------------------------------------------------
    //  give
    // ---------------------------------------------------------------------

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.of("&cUsage: /hcm give <printer|pc|card|filament|…> [args] [player]"));
            return;
        }
        String kind = args[1].toLowerCase(Locale.ROOT);
        // Card & filament take an extra argument, so they resolve their own target.
        if (kind.equals("card")) {
            handleGiveCard(sender, args);
            return;
        }
        if (kind.equals("filament")) {
            handleGiveFilament(sender, args);
            return;
        }
        if (kind.equals("pack")) {
            handleGivePack(sender, args);
            return;
        }
        if (kind.equals("mailbox")) {
            handleGiveMailbox(sender, args);
            return;
        }
        if (kind.equals("display")) {
            handleGiveDisplay(sender, args);
            return;
        }
        if (kind.equals("prize")) {
            handleGivePrize(sender, args);
            return;
        }
        // The Auction House and the individual Arcade machines (crate/scratch/pity/counter)
        // are no longer given out: auctions are reached via /hcm auction, the Arcade via
        // its hub block. Already-placed machines keep working.
        ItemStack item;
        switch (kind) {
            case "printer" -> item = plugin.items().printer();
            case "binder" -> item = plugin.binder().items().binder();
            case "pc" -> item = plugin.items().pc();
            case "vending" -> item = plugin.items().vendingMachine();
            case "pallet" -> item = plugin.items().pallet();
            case "arcade" -> item = plugin.items().arcade();
            case "muffler", "sound_muffler" -> item = plugin.items().soundMuffler();
            default -> {
                sender.sendMessage(Text.of("&cUnknown item '" + args[1]
                        + "'. Use printer, pc, card, filament, pack, prize, binder, vending, display, "
                        + "mailbox [variant], pallet, arcade, or muffler."));
                return;
            }
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Text.of("&cPlayer '" + args[2] + "' is not online."));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Text.of("&cSpecify a player: /hcm give " + args[1] + " <player>"));
            return;
        }

        target.getInventory().addItem(item).values()
                .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
        sender.sendMessage(Text.of("&aGave " + args[1] + " to " + target.getName() + "."));
    }

    /**
     * {@code /hcm give mailbox [variant] [player]} — hand out a Mailbox. The variant is
     * optional (defaults to wood); {@code /hcm give mailbox <player>} still works.
     */
    private void handleGiveMailbox(CommandSender sender, String[] args) {
        com.dierks.homecraft.block.MailboxVariant variant = com.dierks.homecraft.block.MailboxVariant.WOOD;
        int targetIdx = 2;
        if (args.length >= 3) {
            com.dierks.homecraft.block.MailboxVariant parsed = com.dierks.homecraft.block.MailboxVariant.parse(args[2]);
            if (parsed != null) {
                variant = parsed;
                targetIdx = 3;
            } else if (Bukkit.getPlayerExact(args[2]) == null) {
                sender.sendMessage(Text.of("&cUnknown Mailbox variant '" + args[2] + "'. Use one of: "
                        + mailboxVariantList() + "."));
                return;
            }
        }
        Player target = resolveTarget(sender, args, targetIdx, "give mailbox " + variant.key());
        if (target == null) {
            return;
        }
        ItemStack item = plugin.items().mailbox(variant);
        target.getInventory().addItem(item).values()
                .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
        sender.sendMessage(Text.of("&aGave a " + variant.label() + " Mailbox to " + target.getName() + "."));
    }

    /** {@code /hcm give display [variant] [player]} — a Display Case in a pedestal style (default plain). */
    private void handleGiveDisplay(CommandSender sender, String[] args) {
        com.dierks.homecraft.block.DisplayCaseVariant variant = com.dierks.homecraft.block.DisplayCaseVariant.PLAIN;
        int targetIdx = 2;
        if (args.length >= 3) {
            com.dierks.homecraft.block.DisplayCaseVariant parsed = com.dierks.homecraft.block.DisplayCaseVariant.parse(args[2]);
            if (parsed != null) {
                variant = parsed;
                targetIdx = 3;
            } else if (Bukkit.getPlayerExact(args[2]) == null) {
                sender.sendMessage(Text.of("&cUnknown Display Case style '" + args[2] + "'. Use plain or royal."));
                return;
            }
        }
        Player target = resolveTarget(sender, args, targetIdx, "give display " + variant.key());
        if (target == null) {
            return;
        }
        ItemStack item = plugin.items().displayCase(variant);
        target.getInventory().addItem(item).values()
                .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
        sender.sendMessage(Text.of("&aGave a " + variant.label() + " Display Case to " + target.getName() + "."));
    }

    /**
     * {@code /hcm give prize <id> [player]} — give back a lost prize, free (HALLOWEEN-SPEC D19). It
     * works out of season and whatever the player is recorded as having, because restoring is the
     * point, and {@code arcade.collectors.gifting} doesn't apply: it is an admin path. The player must
     * be home from every game, because a world game's inventory restore would wipe what they're given.
     *
     * <p>A collector goes through {@link com.dierks.homecraft.arcade.PrizeService#restore}: its claim is
     * written, or found already there, so a restore never counts twice; a cap needs room by the
     * present's placement rule and never goes on the ground; and given by a player to someone else it
     * is a present, with "A gift from" on the cap and the whole moment. Any other row is handed out as
     * a crate would ({@code grant}), and an item only into a free bag slot, never onto the ground.
     */
    private void handleGivePrize(CommandSender sender, String[] args) {
        String id = givePrizeId(args);
        if (id == null) {
            sender.sendMessage(Text.of("&cUsage: /hcm give prize <id> [player]"));
            return;
        }
        com.dierks.homecraft.config.PluginConfig.Prize p = plugin.config().arcade().prize(id);
        if (p == null) {
            sender.sendMessage(Text.of("&cUnknown prize '" + id + "'. Use a row id from arcade.prizes, or "
                    + String.join(", ", com.dierks.homecraft.arcade.Collectors.SHIPPED_IDS) + "."));
            return;
        }
        String refused = notGivenBack(p.type());
        if (refused != null) {
            sender.sendMessage(Text.of("&c" + refused));
            return;
        }
        Player target = resolveTarget(sender, args, GIVE_PRIZE_PLAYER, "give prize " + p.id());
        if (target == null) {
            return;
        }
        if (!homeFromGames(target)) {
            sender.sendMessage(Text.of("&c" + target.getName() + " is in a game. Try again when they're back."));
            return;
        }
        if (target.isDead()) {
            sender.sendMessage(Text.of("&c" + target.getName() + " is respawning. Try again in a moment."));
            return;
        }
        if (p.collector() == null && givesAnItem(p.type()) && target.getInventory().firstEmpty() == -1) {
            // A crate's hand-over drops what doesn't fit; a give-back never goes on the ground.
            sender.sendMessage(Text.of("&c" + target.getName() + "'s bag is full."));
            return;
        }
        String from = sender instanceof Player sp ? presentFrom(sp.getName(), sp.getUniqueId(), target.getUniqueId()) : null;
        var out = p.collector() != null ? plugin.prizes().restore(target, p, from) : plugin.prizes().grant(target, p, 0);
        if (!out.ok()) {
            sender.sendMessage(Text.of("&c" + out.error()));
            return;
        }
        plugin.getLogger().info("Admin " + sender.getName() + " gave " + p.id() + " to " + target.getName());
        boolean self = sender instanceof Player me && me.getUniqueId().equals(target.getUniqueId());
        if (p.collector() == null && !self) {
            // A collector's restore tells the player itself (the present's moment, or "yours again").
            target.sendMessage(Text.of("&a✔ You got " + out.label() + "&a!"));
            com.dierks.homecraft.util.Sounds.received(target);
        }
        sender.sendMessage(Text.of("&aGave " + out.label() + " &ato " + target.getName() + "."));
    }

    /** Where {@code /hcm give prize <id> [player]} reads the player (none: the sender). */
    static final int GIVE_PRIZE_PLAYER = 3;

    /** The prize id of {@code /hcm give prize <id> [player]}, or null when it is missing (show the usage). */
    static String givePrizeId(String[] args) {
        if (args == null || args.length < 3 || args[2] == null || args[2].isBlank()) {
            return null;
        }
        return args[2].trim();
    }

    /**
     * Who a give-back is a present from: the sending player when it goes to someone else, else null
     * (a plain restore: given to yourself, or from the console, it is not a present).
     */
    static String presentFrom(String senderName, java.util.UUID senderId, java.util.UUID targetId) {
        if (senderName == null || senderName.isBlank() || senderId == null || senderId.equals(targetId)) {
            return null;
        }
        return senderName;
    }

    /**
     * Why a row is not given back by {@code /hcm give prize}, or null. A +1 Home is a perk recorded by
     * its purchase, not an item: granting one unrecorded would vanish at the next refresh.
     */
    static String notGivenBack(com.dierks.homecraft.config.PluginConfig.PrizeType type) {
        if (type == com.dierks.homecraft.config.PluginConfig.PrizeType.HOME_SLOT) {
            return "A +1 Home isn't an item: /hcm homes refresh <player> gives back the homes they bought.";
        }
        return null;
    }

    /**
     * Whether a (non-collector) row is handed over as an item, which needs a free bag slot: a give-back
     * never drops one on the ground. A trail, a command row and the rest are not items.
     */
    static boolean givesAnItem(com.dierks.homecraft.config.PluginConfig.PrizeType type) {
        return switch (type) {
            case BOOST, RADAR, LURE, FIREWORK, HAT, TROPHY, FILAMENT, BLOCK, PACK -> true;
            default -> false;
        };
    }

    /** Home from every game: no session, nothing on the way back, no saved-state row sending them back. */
    private boolean homeFromGames(Player player) {
        var games = plugin.games();
        return games == null || (games.sessions().session(player) == null && games.sessions().home(player));
    }

    /** {@code /hcm backup now} — write a SQLite backup immediately (hcm.admin). */
    private void handleBackup(CommandSender sender, String[] args) {
        if (denyUnless(sender, "hcm.admin")) {
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("now")) {
            sender.sendMessage(Text.of("&cUsage: /hcm backup now"));
            return;
        }
        java.io.File out = plugin.backups().backupNow("manual");
        if (out == null) {
            sender.sendMessage(Text.of("&cBackup failed — see the console."));
            return;
        }
        sender.sendMessage(Text.of("&aBackup written: &f" + out.getName() + " &7("
                + com.dierks.homecraft.storage.BackupService.human(out.length()) + ") in "
                + out.getParentFile().getName() + "/"));
    }

    private String mailboxVariantList() {
        StringBuilder sb = new StringBuilder();
        for (com.dierks.homecraft.block.MailboxVariant v : com.dierks.homecraft.block.MailboxVariant.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v.key());
        }
        return sb.toString();
    }

    /** {@code /hcm give card <miniId> [player]} — hand out a sealed Card (admin, ignores cap). */
    private void handleGiveCard(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm give card <miniId> [player]"));
            return;
        }
        String id = com.dierks.homecraft.mini.MiniIds.slug(args[2]);
        // Validate against the real catalog up front so a typo can never mint a
        // "dead" Card that points at a non-existent Mini.
        if (plugin.miniService().def(id) == null) {
            sender.sendMessage(Text.of("&cUnknown Mini id '" + id + "'. A Card must match a real Mini."));
            sender.sendMessage(Text.of("&7Try &f/hcm mini list&7 to see valid ids."));
            return;
        }
        Player target = resolveTarget(sender, args, 3, "give card " + id);
        if (target == null) {
            return;
        }
        var r = plugin.cards().giveAdmin(target, id);
        sender.sendMessage(r.ok()
                ? Text.of("&aGave a " + id + " Card to " + target.getName() + ".")
                : Text.of("&c" + r.error()));
    }

    /** {@code /hcm give pack <id> [player]} — hand out a sealed Card Pack item (admin). */
    private void handleGivePack(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm give pack <id> [player]"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        ItemStack pack = plugin.packs().packItem(id);
        if (pack == null) {
            sender.sendMessage(Text.of("&cUnknown pack '" + id + "'. See &f/hcm packs&c."));
            return;
        }
        Player target = resolveTarget(sender, args, 3, "give pack " + id);
        if (target == null) {
            return;
        }
        target.getInventory().addItem(pack).values()
                .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
        sender.sendMessage(Text.of("&aGave a " + id + " pack to " + target.getName() + "."));
    }

    /** {@code /hcm give filament <color> <amount> [player]} — hand out printer filament (admin). */
    private void handleGiveFilament(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm give filament <color> <amount> [player]"));
            return;
        }
        org.bukkit.DyeColor color;
        try {
            color = org.bukkit.DyeColor.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.of("&cUnknown filament colour '" + args[2] + "'."));
            return;
        }
        int amount = parseQty(sender, args[3]);
        if (amount <= 0) {
            return;
        }
        Player target = resolveTarget(sender, args, 4, "give filament " + args[2] + " " + amount);
        if (target == null) {
            return;
        }
        ItemStack fil = plugin.miniService().filamentItems().filament(color, amount);
        target.getInventory().addItem(fil).values()
                .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
        sender.sendMessage(Text.of("&aGave " + amount + " " + args[2].toLowerCase(Locale.ROOT)
                + " filament to " + target.getName() + "."));
    }

    /** Resolve the target player at {@code args[idx]}, defaulting to the sender. */
    private Player resolveTarget(CommandSender sender, String[] args, int idx, String usageTail) {
        if (args.length > idx) {
            Player t = Bukkit.getPlayerExact(args[idx]);
            if (t == null) {
                sender.sendMessage(Text.of("&cPlayer '" + args[idx] + "' is not online."));
            }
            return t;
        }
        if (sender instanceof Player p) {
            return p;
        }
        sender.sendMessage(Text.of("&cSpecify a player: /hcm " + usageTail + " <player>"));
        return null;
    }

    /** {@code /hcm printer <public|private>} — flag the printer you're looking at (admin). */
    private void handlePrinter(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players can flag a printer."));
            return;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("public") || args[1].equalsIgnoreCase("private"))) {
            sender.sendMessage(Text.of("&cUsage: /hcm printer <public|private> &7(look at the Printer)"));
            return;
        }
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null) {
            sender.sendMessage(Text.of("&cLook at a placed Printer, then run this again."));
            return;
        }
        var placed = plugin.blockService().at(target.getLocation());
        if (placed.isEmpty() || placed.get().type() != com.dierks.homecraft.block.CustomBlockType.PRINTER) {
            sender.sendMessage(Text.of("&cThat isn't a Printer. Look directly at one."));
            return;
        }
        boolean makePublic = args[1].equalsIgnoreCase("public");
        plugin.printers().setPublic(target.getLocation(), makePublic);
        sender.sendMessage(makePublic
                ? Text.of("&aThis Printer is now &fpublic &a— free prints (no Shiny).")
                : Text.of("&aThis Printer is now &fprivate &a— filament + fee, Shiny unlocked."));
    }

    // ---------------------------------------------------------------------
    //  market
    // ---------------------------------------------------------------------

    private void handleMarket(CommandSender sender, String[] args) {
        MarketService market = plugin.market();
        // Bare `/hcm market` used to mean "list". Now that the full catalog dump is
        // op-only, show a player what they CAN do instead of denying them outright.
        if (args.length < 2 && !sender.hasPermission("hcm.market.list")) {
            marketUsage(sender);
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";

        switch (sub) {
            case "list" -> {
                // Dumping all 263 commodities is an admin/console view; players browse in the PC GUI.
                if (denyUnless(sender, "hcm.market.list")) {
                    return;
                }
                if (market.catalog().isEmpty()) {
                    sender.sendMessage(Text.of("&7The market catalog is empty."));
                    return;
                }
                sender.sendMessage(Text.of("&6Market — " + market.catalog().size() + " commodity(ies):"));
                for (MarketItem item : market.catalog()) {
                    MarketState st = market.state(item.id());
                    String stock = st.stock() <= 0 ? "&cOUT" : "&f" + st.stock();
                    sender.sendMessage(Text.of("&e" + item.id() + " &7buy &a"
                            + plugin.economy().format(market.buyPrice(item.id())) + " &7sell &c"
                            + plugin.economy().format(market.sellPrice(item.id())) + " &7stock " + stock));
                }
            }
            case "price" -> {
                // Checking one item is a player convenience — no PC block needed.
                if (denyUnless(sender, "hcm.market.price")) {
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.of("&cUsage: /hcm market price <item>"));
                    return;
                }
                MarketItem item = market.item(args[2].toLowerCase(Locale.ROOT));
                if (item == null) {
                    sender.sendMessage(Text.of("&cNo market item '" + args[2] + "'."));
                    return;
                }
                MarketState state = market.state(item.id());
                long stock = state.stock();
                sender.sendMessage(Text.of("&6" + item.label()));
                sender.sendMessage(Text.of("&7  stock: " + (stock <= 0 ? "&cOUT OF STOCK" : "&f" + stock)
                        + " &7/ full &f" + item.fullStock()));
                sender.sendMessage(Text.of("&7  buy: &a" + plugin.economy().format(market.buyPrice(item.id()))
                        + " &7 sell: &c" + plugin.economy().format(market.sellPrice(item.id()))
                        + " &7 mid: &f" + plugin.economy().format(market.price(item.id()))));
                // The live market's usual price, mood and badge; nothing while it is off.
                for (String line : liveMarket.priceLines(item)) {
                    sender.sendMessage(Text.of(line));
                }
                sender.sendMessage(Text.of("&7  floor: &f" + plugin.economy().format(item.floor())
                        + " &7 ceiling: &f" + plugin.economy().format(item.ceiling())));
            }
            case "history" -> {
                if (denyUnless(sender, "hcm.market.price")) {
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.of("&cUsage: /hcm market history <item>"));
                    return;
                }
                MarketItem item = market.item(args[2].toLowerCase(Locale.ROOT));
                if (item == null) {
                    sender.sendMessage(Text.of("&cNo market item '" + args[2] + "'."));
                    return;
                }
                var snapshots = market.recentHistory(item.id(), 10);
                if (snapshots.isEmpty()) {
                    sender.sendMessage(Text.of("&7No price history yet for " + item.label() + "&7."));
                    return;
                }
                sender.sendMessage(Text.of("&6" + item.label() + " &7— recent snapshots:"));
                long now = System.currentTimeMillis();
                for (var snap : snapshots) {
                    long minutesAgo = Math.max(0, (now - snap.recordedAt()) / 60_000L);
                    sender.sendMessage(Text.of("&7  " + minutesAgo + "m ago: &f"
                            + plugin.economy().format(snap.price()) + " &7stock &f" + snap.stock()));
                }
            }
            case "buy" -> handleTrade(sender, args, true);
            case "sell" -> handleTrade(sender, args, false);
            case "resetstock" -> handleResetStock(sender, args);
            case "setstock" -> handleSetStock(sender, args);
            case "news" -> liveMarket.news(sender, args);
            case "sim" -> liveMarket.sim(sender, args);
            default -> marketUsage(sender);
        }
    }

    /** The market subcommands this particular sender is actually allowed to run. */
    private void marketUsage(CommandSender sender) {
        List<String> subs = new ArrayList<>();
        if (sender.hasPermission("hcm.market.list")) {
            subs.add("list");
        }
        if (sender.hasPermission("hcm.market.price")) {
            subs.add("price <item>");
            subs.add("history <item>");
        }
        subs.addAll(MarketSimCommand.usageParts(sender));
        if (sender.hasPermission("hcm.market.order")) {
            subs.add("sell <item> <qty>");
        }
        if (sender.hasPermission("hcm.market.buy")) {
            subs.add("buy <item> <qty>");
        }
        if (sender.hasPermission("hcm.admin")) {
            subs.add("resetstock <item|all>");
            subs.add("setstock <item> <amount>");
        }
        if (subs.isEmpty()) {
            sender.sendMessage(Text.of("&cYou don't have permission."));
            return;
        }
        sender.sendMessage(Text.of("&cUsage: &e/hcm market &7" + String.join(" &7| &7", subs)));
    }

    /**
     * {@code /hcm market resetstock <item|all>} — reseed stock from config's
     * {@code initial_stock} and snap the price back onto the curve. This is the intended
     * way to apply a new economy design: editing the catalog and reloading preserves
     * existing stock by design, so redesigned items would otherwise keep old prices.
     *
     * <p>{@code all} is destructive across the whole catalog, so it is confirm-gated.
     */
    private void handleResetStock(CommandSender sender, String[] args) {
        if (denyUnless(sender, "hcm.admin")) {
            return;
        }
        MarketService market = plugin.market();
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm market resetstock <item|all>"));
            return;
        }
        String target = args[2].toLowerCase(Locale.ROOT);

        if (target.equals("all")) {
            int size = market.catalog().size();
            if (!confirmed(sender)) {
                sender.sendMessage(Text.of("&eThis will reset stock for &f" + size + " &eitem(s) to their config "
                        + "&finitial_stock&e, recalculating every price from the curve."));
                sender.sendMessage(Text.of("&eType the command again within &f"
                        + (RESET_CONFIRM_WINDOW_MS / 1000) + "s &eto confirm."));
                return;
            }
            int reset = market.resetAllStock();
            sender.sendMessage(Text.of("&aReset stock + price for &f" + reset + " &acommodity(ies) from config."));
            return;
        }

        MarketService.StockResult result = market.resetStock(target);
        if (!result.ok()) {
            sender.sendMessage(Text.of("&c" + result.error()));
            return;
        }
        sender.sendMessage(Text.of("&aReset &f" + target + " &7→ stock &f" + result.stock()
                + " &7mid &f" + plugin.economy().format(result.price()) + usualNote(target, result.price())
                + (result.capped() ? " &8(initial_stock capped below full_stock)" : "")));
    }

    /** {@code /hcm market setstock <item> <amount>} — fine-tune one item without a full reset. */
    private void handleSetStock(CommandSender sender, String[] args) {
        if (denyUnless(sender, "hcm.admin")) {
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm market setstock <item> <amount>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.of("&cStock must be a whole number."));
            return;
        }
        if (amount < 0) {
            sender.sendMessage(Text.of("&cStock cannot be negative."));
            return;
        }

        MarketService market = plugin.market();
        MarketItem item = market.item(id);
        MarketService.StockResult result = market.setStock(id, amount);
        if (!result.ok()) {
            sender.sendMessage(Text.of("&c" + result.error()));
            return;
        }
        if (result.capped() && item != null) {
            sender.sendMessage(Text.of("&eStock must stay below &ffull_stock &e(" + item.fullStock()
                    + ") so the market always has room to sell into — capped at &f" + result.stock() + "&e."));
        }
        sender.sendMessage(Text.of("&aSet &f" + id + " &7→ stock &f" + result.stock()
                + " &7mid &f" + plugin.economy().format(result.price()) + usualNote(id, result.price())));
    }

    /**
     * {@code " &8(usual $X)"} when the live market has moved {@code id}'s mid away from its usual
     * (balanced) price, so an admin who just snapped a price to the curve sees both; {@code ""}
     * otherwise — with the live market off the two are always equal and the line reads as before.
     */
    private String usualNote(String id, double mid) {
        double usual = plugin.market().usualPrice(id);
        if (Double.isNaN(usual) || usual == mid) {
            return "";
        }
        return " &8(usual " + plugin.economy().format(usual) + ")";
    }

    /**
     * Two-step confirmation for {@code resetstock all}: the first call arms, a second call
     * from the same sender within {@link #RESET_CONFIRM_WINDOW_MS} executes.
     */
    private boolean confirmed(CommandSender sender) {
        String key = sender.getName();
        long now = System.currentTimeMillis();
        Long armed = resetConfirmations.get(key);
        if (armed != null && now - armed <= RESET_CONFIRM_WINDOW_MS) {
            resetConfirmations.remove(key);
            return true;
        }
        resetConfirmations.put(key, now);
        return false;
    }

    private void handleTrade(CommandSender sender, String[] args, boolean buy) {
        if (denyUnless(sender, "hcm.market.order")) {
            return;
        }
        // Buying from the house market has to go through the store, where shipping is
        // charged and waited for. This command bypassed both — and it was reachable by
        // everyone, because hcm.market.order defaults to true and /hcm has no gate of its
        // own. Closing the GUI button alone would have moved the hole, not shut it.
        if (buy && !sender.hasPermission("hcm.market.buy")) {
            sender.sendMessage(Text.of("&cOrders are placed at the store, not from a command."));
            sender.sendMessage(Text.of("&7Right-click a PC and pick a shipping speed."));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players can trade on the market."));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Text.of("&cUsage: /hcm market " + (buy ? "buy" : "sell") + " <item> <qty>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        int qty = parseQty(sender, args[3]);
        if (qty <= 0) {
            return;
        }

        MarketService market = plugin.market();
        MarketService.TradeResult result = buy ? market.buy(player, id, qty) : market.sell(player, id, qty);
        if (!result.ok()) {
            player.sendMessage(Text.of("&c" + result.error()));
            return;
        }
        MarketItem item = market.item(id);
        String verb = buy ? "Bought" : "Sold";
        String flow = buy ? "&7 for &f" : "&7 for &a+";
        String cap = result.qty() < qty ? " &8(capped from " + qty + ")" : "";
        player.sendMessage(Text.of("&a" + verb + " &f" + result.qty() + " &7" + item.label() + cap
                + flow + plugin.economy().format(result.amount())
                + "&7. price &f" + plugin.economy().format(result.priceAfter())
                + " &7stock &f" + result.stockAfter()));
    }

    private int parseQty(CommandSender sender, String raw) {
        int qty;
        try {
            qty = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.of("&cQuantity must be a number."));
            return -1;
        }
        if (qty < 1 || qty > MAX_QTY) {
            sender.sendMessage(Text.of("&cQuantity must be between 1 and " + MAX_QTY + "."));
            return -1;
        }
        return qty;
    }

    // ---------------------------------------------------------------------

    private boolean denyUnless(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        sender.sendMessage(Text.of("&cYou don't have permission."));
        return true;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Text.of("&6HomeCraft Management"));
        if (sender.hasPermission("hcm.admin")) {
            sender.sendMessage(Text.of("&e/hcm admin &7- open the Admin Studio (manage & import Minis)"));
            sender.sendMessage(Text.of("&e/hcm reload &7- reload config & recipes"));
            sender.sendMessage(Text.of("&e/hcm give <printer|pc|vending|display [plain|royal]|mailbox [variant]|pallet|arcade> [player]"));
            sender.sendMessage(Text.of("&e/hcm backup now &7- write a database backup"));
            sender.sendMessage(Text.of("&e/hcm give <card <id>|pack <id>|binder|filament <color> <n>> [player]"));
            sender.sendMessage(Text.of("&e/hcm give prize <id> [player] &7- give back a lost prize (free)"));
            sender.sendMessage(Text.of("&e/hcm printer <public|private> &7- flag the Printer you're looking at"));
            sender.sendMessage(Text.of("&e/hcm tokens give|set|take <player> <n> &7- adjust tokens"));
            sender.sendMessage(Text.of("&e/hcm arcade odds &7- what each game of chance gives back, crate values"));
            sender.sendMessage(Text.of("&e/hcm tokens audit [days] [player] &7- tokens earned/spent by source"));
            sender.sendMessage(Text.of("&e/hcm tokens history <player> [n] &7- a player's last token changes"));
        }
        if (sender.hasPermission("hcm.admin")) {
            sender.sendMessage(Text.of("&e/hcm market resetstock <item|all> &7- reseed stock+price from config"));
            sender.sendMessage(Text.of("&e/hcm market setstock <item> <amount> &7- set one item's stock"));
        }
        if (sender.hasPermission("hcm.market.list")) {
            sender.sendMessage(Text.of("&e/hcm market list &7- list commodities (buy/sell/stock)"));
        }
        if (sender.hasPermission("hcm.market.price")) {
            sender.sendMessage(Text.of("&e/hcm market price <item> &7- inspect a commodity"));
            sender.sendMessage(Text.of("&e/hcm market history <item> &7- recent price snapshots"));
            sender.sendMessage(Text.of("&e/hcm balance &7- your money and Arcade tokens"));
        }
        for (String line : MarketSimCommand.helpLines(sender)) {
            sender.sendMessage(Text.of(line));
        }
        if (sender.hasPermission("hcm.market.order")) {
            sender.sendMessage(Text.of("&e/hcm market sell <item> <qty> &7- sell into the market"));
        }
        if (sender.hasPermission("hcm.market.buy")) {
            sender.sendMessage(Text.of("&e/hcm market buy <item> <qty> &7- admin buy, no shipping"));
        }
        if (!sender.hasPermission("hcm.admin") && sender.hasPermission("hcm.arcade.use")) {
            sender.sendMessage(Text.of("&e/hcm arcade odds &7- what each game of chance gives back"));
        }
        for (String line : gamesCommand.helpLines(sender)) {
            sender.sendMessage(Text.of(line));
        }
        sender.sendMessage(Text.of("&e/hcm museum [id] &7- browse the Mini Museum"));
        sender.sendMessage(Text.of("&e/hcm trail [name|off] &7- turn your trail on or off"));
        sender.sendMessage(Text.of("&e/hcm auction &7- open the Mini Auction House"));
        if (sender.hasPermission("hcm.admin")) {
            sender.sendMessage(Text.of("&e/hcm mini list|give <id> [player] &7- admin Minis"));
            sender.sendMessage(Text.of("&e/hcm mini repair-escaped [confirm] &7- give back numbers old escapes burned"));
            sender.sendMessage(Text.of("&e/hcm hunt spawn [rarity] [player]|status|clear &7- the wild hunt"));
            sender.sendMessage(Text.of("&e/hcm candy now [player]|status|clear|bag <player>|add <player> <n> &7- the Candy Apple shower"));
        }
        if (sender.hasPermission("hcm.arcade.use")) {
            sender.sendMessage(Text.of("&e/hcm candy &7- your candy apples and the next shower"));
        }
    }

    // ---------------------------------------------------------------------
    //  balance (combined wallet: Vault money + Arcade tokens)
    // ---------------------------------------------------------------------

    /**
     * {@code /hcm balance} — money and tokens in one place. Essentials' {@code /balance}
     * only knows about Vault; tokens live in HCM, so a player otherwise has to check two
     * commands to see what they can actually spend.
     */
    private void handleBalance(CommandSender sender) {
        if (denyUnless(sender, "hcm.market.price")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players have a balance."));
            return;
        }
        player.sendMessage(plugin.economy().isEnabled()
                ? Text.of("&7Balance: &a" + plugin.economy().format(plugin.economy().balance(player)))
                : Text.of("&7Balance: &cunavailable &7(no Vault economy)"));
        player.sendMessage(Text.of("&7Tokens: &6" + plugin.tokens().balance(player.getUniqueId())));
    }

    // ---------------------------------------------------------------------
    //  tokens (player: balance; admin: give/set/take)
    // ---------------------------------------------------------------------

    private void handleTokens(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("audit")) {
                if (!denyUnless(sender, "hcm.admin")) {
                    handleTokenAudit(sender, args);
                }
                return;
            }
            if (sub.equals("history")) {
                if (!denyUnless(sender, "hcm.admin")) {
                    handleTokenHistory(sender, args);
                }
                return;
            }
            if (sub.equals("give") || sub.equals("add") || sub.equals("set") || sub.equals("take")) {
                if (denyUnless(sender, "hcm.admin")) {
                    return;
                }
                if (args.length < 4) {
                    sender.sendMessage(Text.of("&cUsage: /hcm tokens " + sub + " <player> <amount>"));
                    return;
                }
                org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                int n;
                try {
                    n = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Text.of("&cAmount must be a whole number."));
                    return;
                }
                int bal = switch (sub) {
                    case "set" -> plugin.tokens().adminSet(target.getUniqueId(), n);
                    case "take" -> plugin.tokens().adminTake(target.getUniqueId(), n);
                    default -> plugin.tokens().adminAdd(target.getUniqueId(), n);
                };
                sender.sendMessage(Text.of("&aTokens for &f" + args[2] + "&a: now &6" + bal + "&a."));
                return;
            }
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players have a token balance."));
            return;
        }
        int t = plugin.tokens().balance(player.getUniqueId());
        int s = plugin.tokens().streak(player.getUniqueId());
        player.sendMessage(Text.of("&eArcade tokens: &6" + t + " &7(login streak: " + s + " day"
                + (s == 1 ? "" : "s") + "). &7Play at the Arcade machines or &f/hcm arcade&7."));
    }

    /**
     * {@code /hcm tokens audit [days] [player]} — what was earned and spent, by source, over the
     * last {@code days} local days (default 7; 1 = today so far). Per player, with per-day
     * averages, then the whole server. Read straight from the ledger, so it is what happened
     * rather than what the config says should happen.
     */
    private void handleTokenAudit(CommandSender sender, String[] args) {
        int days = 7;
        String who = null;
        int next = 2;
        if (args.length > next) {
            try {
                days = Math.max(1, Math.min(365, Integer.parseInt(args[next])));
                next++;
            } catch (NumberFormatException ignored) {
                // not a number — it is the player name, and days stays the default
            }
        }
        if (args.length > next) {
            who = args[next];
        }
        java.util.UUID only = null;
        if (who != null) {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(who);
            only = op.getUniqueId();
        }
        com.dierks.homecraft.util.GameClock clock = plugin.clock();
        long firstDay = clock.dayKey() - (days - 1);
        long since = clock.startOfDay(firstDay);
        List<com.dierks.homecraft.storage.TokenDao.SourceTotal> rows = plugin.tokens().totals(since, only);

        String from = java.time.LocalDate.ofEpochDay(firstDay)
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ROOT));
        sender.sendMessage(Text.of("&6Token audit &7— " + (days == 1 ? "today so far" : "the last " + days
                + " days, since " + from) + " &8(" + clock.zone().getId() + ")"));
        if (rows.isEmpty()) {
            sender.sendMessage(Text.of("&7No token has moved in that window."));
            return;
        }
        Map<java.util.UUID, List<com.dierks.homecraft.storage.TokenDao.SourceTotal>> byPlayer =
                new java.util.LinkedHashMap<>();
        for (com.dierks.homecraft.storage.TokenDao.SourceTotal r : rows) {
            byPlayer.computeIfAbsent(r.player(), k -> new ArrayList<>()).add(r);
        }
        long allEarned = 0;
        long allSpent = 0;
        Map<String, long[]> allBySource = new java.util.TreeMap<>();
        for (Map.Entry<java.util.UUID, List<com.dierks.homecraft.storage.TokenDao.SourceTotal>> e
                : byPlayer.entrySet()) {
            long earned = 0;
            long spent = 0;
            List<String> earnedParts = new ArrayList<>();
            List<String> spentParts = new ArrayList<>();
            for (com.dierks.homecraft.storage.TokenDao.SourceTotal r : e.getValue()) {
                earned += r.earned();
                spent += r.spent();
                if (r.earned() > 0) {
                    earnedParts.add(sourceLabel(r.source()) + " " + r.earned());
                }
                if (r.spent() > 0) {
                    spentParts.add(sourceLabel(r.source()) + " " + r.spent());
                }
                long[] agg = allBySource.computeIfAbsent(r.source(), k -> new long[2]);
                agg[0] += r.earned();
                agg[1] += r.spent();
            }
            allEarned += earned;
            allSpent += spent;
            sender.sendMessage(Text.of(auditLine(playerName(e.getKey()), earned, spent, days)));
            if (!earnedParts.isEmpty()) {
                sender.sendMessage(Text.of("&7   earned: &f" + String.join("&7, &f", earnedParts)));
            }
            if (!spentParts.isEmpty()) {
                sender.sendMessage(Text.of("&7   spent: &f" + String.join("&7, &f", spentParts)));
            }
        }
        if (only == null && byPlayer.size() > 1) {
            sender.sendMessage(Text.of(auditLine("Everyone", allEarned, allSpent, days)));
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, long[]> e : allBySource.entrySet()) {
                parts.add(sourceLabel(e.getKey()) + " &a+" + e.getValue()[0] + "&7/&c-" + e.getValue()[1]);
            }
            sender.sendMessage(Text.of("&7   by source: &f" + String.join("&7, &f", parts)));
        }
    }

    private static String auditLine(String who, long earned, long spent, int days) {
        long net = earned - spent;
        return "&e" + who + " &7earned &a+" + earned + " &7(" + perDay(earned, days) + "/day) · spent &c-"
                + spent + " &7(" + perDay(spent, days) + "/day) · net &f" + (net >= 0 ? "+" : "") + net;
    }

    private static String perDay(long total, int days) {
        return String.format(Locale.ROOT, "%.1f", total / (double) Math.max(1, days));
    }

    /** {@code /hcm tokens history <player> [n]} — a player's last n ledger lines, newest first. */
    private void handleTokenHistory(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.of("&cUsage: /hcm tokens history <player> [n]"));
            return;
        }
        int n = 10;
        if (args.length >= 4) {
            try {
                n = Math.max(1, Math.min(100, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                sender.sendMessage(Text.of("&cn must be a whole number."));
                return;
            }
        }
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        List<com.dierks.homecraft.storage.TokenDao.LedgerRow> rows =
                plugin.tokens().history(target.getUniqueId(), n);
        sender.sendMessage(Text.of("&6Token history &7— " + args[2] + " &8(balance "
                + plugin.tokens().balance(target.getUniqueId()) + ")"));
        if (rows.isEmpty()) {
            sender.sendMessage(Text.of("&7Nothing recorded yet."));
            return;
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("MMM d HH:mm", Locale.ROOT).withZone(plugin.clock().zone());
        for (com.dierks.homecraft.storage.TokenDao.LedgerRow r : rows) {
            String detail = r.detail() == null || r.detail().isBlank() ? "" : ": " + r.detail();
            sender.sendMessage(Text.of("&8" + fmt.format(java.time.Instant.ofEpochMilli(r.at())) + " "
                    + (r.delta() >= 0 ? "&a+" : "&c") + r.delta() + " &7" + sourceLabel(r.source()) + detail
                    + " &8→ " + r.balanceAfter()));
        }
    }

    private static String sourceLabel(String stored) {
        com.dierks.homecraft.arcade.TokenService.Source s = com.dierks.homecraft.arcade.TokenService.Source.of(stored);
        return s != null ? s.label() : stored;
    }

    private static String playerName(java.util.UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------------
    //  display (admin — bind in-game economy displays, GUI-first)
    // ---------------------------------------------------------------------

    private void handleDisplay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players can bind displays."));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "sign" -> bindSignDisplay(player, args);
            case "hologram", "holo" -> bindHologramDisplay(player, args);
            case "tv" -> bindTvPanelDisplay(player, args);
            case "remove" -> removeDisplay(player);
            case "cleanup" -> cleanupDisplays(player);
            default -> {
                // The Market News board (@news) is offered only while the live market runs; with it
                // off or paused the help reads exactly as 0.32's.
                boolean news = newsBoardOffered();
                player.sendMessage(Text.of("&e/hcm display sign &7- bind the sign you're looking at to a commodity"));
                player.sendMessage(Text.of(news
                        ? "&e/hcm display hologram &7- float a live-price hologram (or the Market News board) above the block you're looking at"
                        : "&e/hcm display hologram &7- float a live-price hologram above the block you're looking at"));
                player.sendMessage(Text.of(news
                        ? "&e/hcm display tv [commodity|@news] [scale] &7- mount a flat price-screen panel (or the Market News board) on the wall you're looking at"
                        : "&e/hcm display tv [commodity] [scale] &7- mount a flat price-screen panel on the wall you're looking at"));
                player.sendMessage(Text.of("&e/hcm display sign|hologram|tv @board:<game or course> &7- a leaderboard:"
                        + " the top 5 (a sign: 3) of a game, a course, or a Fresh course's current set"));
                player.sendMessage(Text.of("&e/hcm display remove &7- unbind the display block you're looking at"));
                player.sendMessage(Text.of("&e/hcm display cleanup &7- despawn every plugin-owned display entity in loaded chunks (wipes strays)"));
            }
        }
    }

    private void bindSignDisplay(Player player, String[] args) {
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null || !(target.getState() instanceof org.bukkit.block.Sign)) {
            player.sendMessage(Text.of("&cLook at a placed sign, then run &f/hcm display sign&c."));
            return;
        }
        org.bukkit.Location loc = target.getLocation();
        if (args.length >= 3 && com.dierks.homecraft.display.BoardDisplay.is(args[2])) {
            var r = plugin.displayService().bindSign(player, loc, args[2]);
            player.sendMessage(r.ok() ? Text.of("&aSign bound to " + shows(args[2]) + "&a.") : Text.of("&c" + r.error()));
            return;
        }
        new com.dierks.homecraft.gui.display.CommodityPickerMenu(plugin, player, "Bind sign → commodity",
                id -> {
                    var r = plugin.displayService().bindSign(player, loc, id);
                    player.sendMessage(r.ok()
                            ? Text.of("&aSign board bound to &f" + id + "&a — it now shows the live price.")
                            : Text.of("&c" + r.error()));
                    player.closeInventory();
                },
                player::closeInventory).open(player);
    }

    private void bindHologramDisplay(Player player, String[] args) {
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            player.sendMessage(Text.of("&cLook at the block you want the hologram to float above, then run &f/hcm display hologram&c."));
            return;
        }
        org.bukkit.Location loc = target.getLocation();
        if (args.length >= 3 && com.dierks.homecraft.display.BoardDisplay.is(args[2])) {
            var r = plugin.displayService().bindHologram(player, loc, args[2]);
            player.sendMessage(r.ok() ? Text.of("&aHologram floating above the block, showing " + shows(args[2]) + "&a.")
                    : Text.of("&c" + r.error()));
            return;
        }
        // offerNews: a hologram can also be the Market News board (@news) - the picker offers it only
        // while the live market runs, and is 0.32's picker while it is off or paused.
        new com.dierks.homecraft.gui.display.CommodityPickerMenu(plugin, player, "Bind hologram → commodity",
                id -> {
                    var r = plugin.displayService().bindHologram(player, loc, id);
                    player.sendMessage(r.ok()
                            ? Text.of("&aHologram floating above the block, showing " + shows(id) + "&a live.")
                            : Text.of("&c" + r.error()));
                    player.closeInventory();
                },
                player::closeInventory, true).open(player);
    }

    private void removeDisplay(Player player) {
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null) {
            player.sendMessage(Text.of("&cLook at the display block you want to unbind."));
            return;
        }
        var r = plugin.displayService().removeAny(target.getLocation());
        player.sendMessage(r.ok() ? Text.of("&aDisplay unbound.") : Text.of("&c" + r.error()));
    }

    /** {@code /hcm display tv [commodity] [scale]} — mount a flat price-panel on the wall. */
    private void bindTvPanelDisplay(Player player, String[] args) {
        org.bukkit.util.RayTraceResult ray = player.rayTraceBlocks(6);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) {
            player.sendMessage(Text.of("&cLook at a wall, then run &f/hcm display tv [commodity] [scale]&c."));
            return;
        }
        org.bukkit.block.Block wall = ray.getHitBlock();
        org.bukkit.block.BlockFace face = ray.getHitBlockFace();
        float scale = plugin.config().displays().tv().scale();
        if (args.length >= 4) {
            try {
                scale = Float.parseFloat(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(Text.of("&cScale must be a number (e.g. 3.0). Using the default."));
            }
        }
        final float panelScale = scale;
        if (args.length >= 3) {
            // A commodity id goes through as typed, as in 0.32; "@news" in any case is the news
            // board while the live market runs (DisplayService refuses it while it is off).
            String id = newsBoardOffered() && com.dierks.homecraft.display.DisplayService.NEWS_ID.equalsIgnoreCase(args[2])
                    ? com.dierks.homecraft.display.DisplayService.NEWS_ID : args[2];
            reportTvBind(player, plugin.displayService().bindTvPanel(player, wall, face, id, panelScale), id);
            return;
        }
        // No commodity argument → pick one from the GUI (mounts on the wall we captured above).
        // offerNews: a TV can also be the Market News board (@news) - the picker offers it only
        // while the live market runs, and is 0.32's picker while it is off or paused.
        new com.dierks.homecraft.gui.display.CommodityPickerMenu(plugin, player, "Bind TV panel → commodity",
                id -> {
                    reportTvBind(player, plugin.displayService().bindTvPanel(player, wall, face, id, panelScale), id);
                    player.closeInventory();
                },
                player::closeInventory, true).open(player);
    }

    private void reportTvBind(Player player, com.dierks.homecraft.display.DisplayService.Result r, String id) {
        player.sendMessage(r.ok()
                ? Text.of("&aTV price panel mounted on the wall, showing " + shows(id) + "&a live.")
                : Text.of("&c" + r.error()));
    }

    /**
     * Whether the display binders offer the Market News board ({@code @news}): only while the live
     * market runs, so with it off or paused the pickers, help and tab completion are 0.32's.
     */
    private boolean newsBoardOffered() {
        return com.dierks.homecraft.gui.MarketNewsMenu.live(plugin) != null;
    }

    /**
     * What a freshly bound display shows: {@code &fwheat}, the Market News board for {@code @news},
     * or a leaderboard for {@code @board:<id>}.
     */
    private static String shows(String id) {
        if (com.dierks.homecraft.display.BoardDisplay.is(id)) {
            var t = com.dierks.homecraft.display.BoardDisplay.parse(id);
            return "&fthe " + (t == null ? id : t.id()) + " leaderboard";
        }
        return com.dierks.homecraft.display.DisplayService.NEWS_ID.equals(id) ? "&fthe Market News board" : "&f" + id;
    }

    /** Despawn every plugin-owned display entity in loaded chunks (wipes stray/leaked holograms). */
    private void cleanupDisplays(Player player) {
        int removed = plugin.displayService().purgeOwnedDisplays();
        player.sendMessage(removed > 0
                ? Text.of("&aRemoved &f" + removed + "&a display "
                        + (removed == 1 ? "entity" : "entities") + " from loaded chunks. "
                        + "&7Live panels/holograms respawn on the next refresh.")
                : Text.of("&7No plugin-owned display entities found in loaded chunks."));
    }

    // ---------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("hcm.admin")) {
                addMatches(out, args[0], "admin", "reload", "give", "market", "display", "mini", "museum", "printer", "packs", "binder", "auction", "arcade", "balance", "tokens", "quests", "courier", "backup", "hunt", "candy", "trail", "achievements", "guide", "config", "homes");
            } else {
                addMatches(out, args[0], "market", "mini", "museum", "packs", "binder", "auction", "arcade", "candy", "balance", "tokens", "quests", "courier", "trail", "achievements", "guide");
            }
            if (sender.hasPermission(GamesCommand.PLAY)) {
                addMatches(out, args[0], "play", "leave");
            }
            if (sender.hasPermission(GamesCommand.ADMIN)) {
                addMatches(out, args[0], "games");
            }
        } else if (List.of("play", "leave", "games").contains(args[0].toLowerCase(Locale.ROOT))) {
            out.addAll(gamesCommand.complete(sender, args));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("config") && sender.hasPermission("hcm.admin")) {
            addMatches(out, args[1], "reset");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("config") && sender.hasPermission("hcm.admin")) {
            addMatches(out, args[2], "arcade", "arcade.quests", "arcade.prizes", "arcade.crates", "arcade.achievements",
                    "arcade.lotto", "games", "games.break", "packs", "minis.loot.natural", "minis.effects", "clock");
            for (com.dierks.homecraft.games.GameSpec<?> spec : com.dierks.homecraft.games.GameCatalog.SPECS) {
                addMatches(out, args[2], "games." + com.dierks.homecraft.config.GamesConfig.block(spec.id()));
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("config") && sender.hasPermission("hcm.admin")) {
            addMatches(out, args[3], "confirm");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("homes") && sender.hasPermission("hcm.admin")) {
            addMatches(out, args[1], "refresh");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("guide")) {
            addMatches(out, args[1], "tokens", "minis", "wild", "arcade", "games");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("museum")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.mini.MiniDef def : plugin.miniService().catalog()) {
                if (def.id().startsWith(prefix)) {
                    out.add(def.id());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mini")) {
            addMatches(out, args[1], "museum", "list", "give", "capturestand");
            if (sender.hasPermission("hcm.admin")) {
                addMatches(out, args[1], "repair-escaped");
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("mini") && args[1].equalsIgnoreCase("repair-escaped")) {
            addMatches(out, args[2], "confirm");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("hunt") && sender.hasPermission("hcm.admin")) {
            addMatches(out, args[1], "spawn", "status", "clear");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("hunt") && args[1].equalsIgnoreCase("spawn")) {
            for (com.dierks.homecraft.mini.Rarity r : com.dierks.homecraft.mini.Rarity.values()) {
                addMatches(out, args[2], r.name().toLowerCase(Locale.ROOT));
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("hunt") && args[1].equalsIgnoreCase("spawn")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("candy") && sender.hasPermission("hcm.admin")) {
            addMatches(out, args[1], CANDY_VERBS.toArray(new String[0]));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("candy") && sender.hasPermission("hcm.admin")
                && List.of("now", "bag", "add").contains(args[1].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("mini")
                && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("capturestand"))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.mini.MiniDef def : plugin.miniService().catalog()) {
                if (def.id().startsWith(prefix)) {
                    out.add(def.id());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            addMatches(out, args[1], "printer", "binder", "pc", "card", "filament", "pack", "prize", "vending", "display",
                    "mailbox", "pallet", "arcade", "muffler");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("prize")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.config.PluginConfig.Prize p : plugin.config().arcade().prizes()) {
                if (p.id().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(p.id());
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("prize")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("mailbox")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.block.MailboxVariant v : com.dierks.homecraft.block.MailboxVariant.values()) {
                if (v.key().startsWith(prefix)) {
                    out.add(v.key());
                }
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("mailbox")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("pack")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.mini.Pack.PackDef p : plugin.packs().packs()) {
                if (p.id().startsWith(prefix)) {
                    out.add(p.id());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("card")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.mini.MiniDef def : plugin.miniService().catalog()) {
                if (def.id().startsWith(prefix)) {
                    out.add(def.id());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("filament")) {
            String prefix = args[2].toUpperCase(Locale.ROOT);
            for (org.bukkit.DyeColor col : org.bukkit.DyeColor.values()) {
                if (col.name().startsWith(prefix)) {
                    out.add(col.name().toLowerCase(Locale.ROOT));
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("filament")) {
            addMatches(out, args[3], "1", "3", "8", "16", "64");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && args[1].equalsIgnoreCase("display")) {
            addMatches(out, args[2], "plain", "royal");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("arcade") && sender.hasPermission("hcm.arcade.use")) {
            addMatches(out, args[1], "odds");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("trail") && sender instanceof Player tp) {
            addMatches(out, args[1], "off");
            for (var o : plugin.trails().owned(tp)) {
                addMatches(out, args[1], o.prize().id());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("backup")) {
            addMatches(out, args[1], "now");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("printer")) {
            addMatches(out, args[1], "public", "private");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tokens")) {
            if (sender.hasPermission("hcm.admin")) {
                addMatches(out, args[1], "give", "set", "take", "audit", "history");
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("tokens")
                && args[1].equalsIgnoreCase("audit")) {
            addMatches(out, args[2], "1", "7", "30");
        } else if ((args.length == 3 && args[0].equalsIgnoreCase("tokens")
                && List.of("give", "add", "set", "take", "history").contains(args[1].toLowerCase(Locale.ROOT)))
                || (args.length == 4 && args[0].equalsIgnoreCase("tokens") && args[1].equalsIgnoreCase("audit"))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("display")) {
            addMatches(out, args[1], "sign", "hologram", "tv", "remove", "cleanup");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("display") && args[1].equalsIgnoreCase("tv")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            if (newsBoardOffered()) {
                addMatches(out, prefix, com.dierks.homecraft.display.DisplayService.NEWS_ID);
            }
            for (String board : plugin.displayService().boardTargets()) {
                addMatches(out, prefix, board);
            }
            for (MarketItem item : plugin.market().catalog()) {
                if (item.id().startsWith(prefix)) {
                    out.add(item.id());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("display")
                && List.of("sign", "hologram", "holo").contains(args[1].toLowerCase(Locale.ROOT))) {
            for (String board : plugin.displayService().boardTargets()) {
                addMatches(out, args[2], board);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("market")) {
            addMatches(out, args[1], "list", "price", "history", "sell");
            if (sender.hasPermission(MarketSimCommand.READ_PERMISSION)) {
                addMatches(out, args[1], "news");
            }
            if (sender.hasPermission(MarketSimCommand.PERMISSION)) {
                addMatches(out, args[1], "sim");
            }
            if (sender.hasPermission("hcm.market.buy")) {
                addMatches(out, args[1], "buy");
            }
            if (sender.hasPermission("hcm.admin")) {
                addMatches(out, args[1], "resetstock", "setstock");
            }
        } else if (args.length >= 3 && args[0].equalsIgnoreCase("market")
                && (args[1].equalsIgnoreCase("news") || args[1].equalsIgnoreCase("sim"))) {
            out.addAll(liveMarket.complete(sender, args));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("market")
                && List.of("price", "history", "buy", "sell", "resetstock", "setstock")
                        .contains(args[1].toLowerCase(Locale.ROOT))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            if (args[1].equalsIgnoreCase("resetstock")) {
                addMatches(out, prefix, "all");
            }
            for (MarketItem item : plugin.market().catalog()) {
                if (item.id().startsWith(prefix)) {
                    out.add(item.id());
                }
            }
        }
        return out;
    }

    private void addMatches(List<String> out, String prefix, String... options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.startsWith(lower)) {
                out.add(option);
            }
        }
    }
}
