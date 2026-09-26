package com.dierks.homecraft.command;

import com.dierks.homecraft.HomeCraftManagement;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /hcm} — admin & utility commands.
 * <ul>
 *   <li>{@code /hcm reload} — reload config.yml + recipes + market catalog. (admin)</li>
 *   <li>{@code /hcm give <printer|pc|vending|display|mailbox [variant]|pallet|arcade|…> [player]} — hand out a custom item. (admin)</li>
 *   <li>{@code /hcm market list} — list catalog + live prices. (hcm.market.list, op-only)</li>
 *   <li>{@code /hcm market price|history <item>} — inspect one item. (hcm.market.price, all)</li>
 *   <li>{@code /hcm market sell <item> <qty>} — sell into the engine. (hcm.market.order)</li>
 *   <li>{@code /hcm market buy <item> <qty>} — admin only (hcm.market.buy). Players order at
 *       the store, where shipping is charged; this command pays no shipping and waits for
 *       nothing, so leaving it open to everyone made every shipping tier pointless.</li>
 *   <li>{@code /hcm market resetstock <item|all>} — reseed stock + price from config. (admin)</li>
 *   <li>{@code /hcm market setstock <item> <amount>} — set one item's stock. (admin)</li>
 *   <li>{@code /hcm balance} — Vault money + Arcade tokens together. (hcm.market.price)</li>
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

    public HcmCommand(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (denyUnless(sender, "hcm.admin")) {
                    return true;
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
                    if (!denyUnless(sender, "hcm.admin")) {
                        for (String line : plugin.arcade().oddsReport()) {
                            sender.sendMessage(Text.of(line));
                        }
                    }
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
            default -> {
                sender.sendMessage(Text.of("&cUnknown item '" + args[1]
                        + "'. Use printer, pc, card, filament, pack, binder, vending, display, "
                        + "mailbox [variant], pallet, or arcade."));
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
                + " &7mid &f" + plugin.economy().format(result.price())
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
                + " &7mid &f" + plugin.economy().format(result.price())));
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
            sender.sendMessage(Text.of("&e/hcm printer <public|private> &7- flag the Printer you're looking at"));
            sender.sendMessage(Text.of("&e/hcm tokens give|set|take <player> <n> &7- adjust tokens"));
            sender.sendMessage(Text.of("&e/hcm arcade odds &7- Scratch Ticket RTP and each crate's value"));
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
        if (sender.hasPermission("hcm.market.order")) {
            sender.sendMessage(Text.of("&e/hcm market sell <item> <qty> &7- sell into the market"));
        }
        if (sender.hasPermission("hcm.market.buy")) {
            sender.sendMessage(Text.of("&e/hcm market buy <item> <qty> &7- admin buy, no shipping"));
        }
        sender.sendMessage(Text.of("&e/hcm museum [id] &7- browse the Mini Museum"));
        sender.sendMessage(Text.of("&e/hcm trail [name|off] &7- turn your trail on or off"));
        sender.sendMessage(Text.of("&e/hcm auction &7- open the Mini Auction House"));
        if (sender.hasPermission("hcm.admin")) {
            sender.sendMessage(Text.of("&e/hcm mini list|give <id> [player] &7- admin Minis"));
            sender.sendMessage(Text.of("&e/hcm mini repair-escaped [confirm] &7- give back numbers old escapes burned"));
            sender.sendMessage(Text.of("&e/hcm hunt spawn [rarity] [player]|status|clear &7- the wild hunt"));
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
            case "sign" -> bindSignDisplay(player);
            case "hologram", "holo" -> bindHologramDisplay(player);
            case "tv" -> bindTvPanelDisplay(player, args);
            case "remove" -> removeDisplay(player);
            case "cleanup" -> cleanupDisplays(player);
            default -> {
                player.sendMessage(Text.of("&e/hcm display sign &7- bind the sign you're looking at to a commodity"));
                player.sendMessage(Text.of("&e/hcm display hologram &7- float a live-price hologram above the block you're looking at"));
                player.sendMessage(Text.of("&e/hcm display tv [commodity] [scale] &7- mount a flat price-screen panel on the wall you're looking at"));
                player.sendMessage(Text.of("&e/hcm display remove &7- unbind the display block you're looking at"));
                player.sendMessage(Text.of("&e/hcm display cleanup &7- despawn every plugin-owned display entity in loaded chunks (wipes strays)"));
            }
        }
    }

    private void bindSignDisplay(Player player) {
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null || !(target.getState() instanceof org.bukkit.block.Sign)) {
            player.sendMessage(Text.of("&cLook at a placed sign, then run &f/hcm display sign&c."));
            return;
        }
        org.bukkit.Location loc = target.getLocation();
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

    private void bindHologramDisplay(Player player) {
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            player.sendMessage(Text.of("&cLook at the block you want the hologram to float above, then run &f/hcm display hologram&c."));
            return;
        }
        org.bukkit.Location loc = target.getLocation();
        new com.dierks.homecraft.gui.display.CommodityPickerMenu(plugin, player, "Bind hologram → commodity",
                id -> {
                    var r = plugin.displayService().bindHologram(player, loc, id);
                    player.sendMessage(r.ok()
                            ? Text.of("&aHologram floating above the block, showing &f" + id + "&a live.")
                            : Text.of("&c" + r.error()));
                    player.closeInventory();
                },
                player::closeInventory).open(player);
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
            reportTvBind(player, plugin.displayService().bindTvPanel(player, wall, face, args[2], panelScale), args[2]);
            return;
        }
        // No commodity argument → pick one from the GUI (mounts on the wall we captured above).
        new com.dierks.homecraft.gui.display.CommodityPickerMenu(plugin, player, "Bind TV panel → commodity",
                id -> {
                    reportTvBind(player, plugin.displayService().bindTvPanel(player, wall, face, id, panelScale), id);
                    player.closeInventory();
                },
                player::closeInventory).open(player);
    }

    private void reportTvBind(Player player, com.dierks.homecraft.display.DisplayService.Result r, String id) {
        player.sendMessage(r.ok()
                ? Text.of("&aTV price panel mounted on the wall, showing &f" + id + "&a live.")
                : Text.of("&c" + r.error()));
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
                addMatches(out, args[0], "admin", "reload", "give", "market", "display", "mini", "museum", "printer", "packs", "binder", "auction", "arcade", "balance", "tokens", "quests", "courier", "backup", "hunt", "trail");
            } else {
                addMatches(out, args[0], "market", "mini", "museum", "packs", "binder", "auction", "arcade", "balance", "tokens", "courier", "trail");
            }
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
        } else if (args.length == 3 && args[0].equalsIgnoreCase("mini")
                && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("capturestand"))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.mini.MiniDef def : plugin.miniService().catalog()) {
                if (def.id().startsWith(prefix)) {
                    out.add(def.id());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            addMatches(out, args[1], "printer", "binder", "pc", "card", "filament", "pack", "vending", "display",
                    "mailbox", "pallet", "arcade");
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
        } else if (args.length == 2 && args[0].equalsIgnoreCase("arcade") && sender.hasPermission("hcm.admin")) {
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
            for (MarketItem item : plugin.market().catalog()) {
                if (item.id().startsWith(prefix)) {
                    out.add(item.id());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("market")) {
            addMatches(out, args[1], "list", "price", "history", "sell");
            if (sender.hasPermission("hcm.market.buy")) {
                addMatches(out, args[1], "buy");
            }
            if (sender.hasPermission("hcm.admin")) {
                addMatches(out, args[1], "resetstock", "setstock");
            }
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
