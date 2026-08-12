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
import java.util.List;
import java.util.Locale;

/**
 * {@code /hcm} — admin & utility commands.
 * <ul>
 *   <li>{@code /hcm reload} — reload config.yml + recipes + market catalog. (admin)</li>
 *   <li>{@code /hcm give <workbench|pc> [player]} — hand out a custom item. (admin)</li>
 *   <li>{@code /hcm market list} — list catalog + live prices.</li>
 *   <li>{@code /hcm market price <item>} — inspect one item.</li>
 *   <li>{@code /hcm market buy|sell <item> <qty>} — trade against the engine. (hcm.market.order)</li>
 * </ul>
 */
public final class HcmCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_QTY = 4096;

    private final HomeCraftManagement plugin;

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
            case "auction", "auctions" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.of("&cOnly players can open the Auction House."));
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
                    sender.sendMessage(Text.of("&d" + def.id() + " &7(" + def.rarity() + ") &f" + def.name()
                            + " &7minted " + c.minted() + "/" + cap + ", circ " + c.circulation()));
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
            default -> sender.sendMessage(Text.of("&cUsage: /hcm mini <museum|list|give|capturestand> …"));
        }
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
            sender.sendMessage(Text.of("&cUsage: /hcm give <workbench|pc> [player]"));
            return;
        }
        ItemStack item;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "workbench" -> item = plugin.items().workbench();
            case "pc" -> item = plugin.items().pc();
            case "vending" -> item = plugin.items().vendingMachine();
            case "display" -> item = plugin.items().displayCase();
            case "auction" -> item = plugin.items().auctionHouse();
            case "mailbox" -> item = plugin.items().mailbox();
            case "pallet" -> item = plugin.items().pallet();
            default -> {
                sender.sendMessage(Text.of("&cUnknown item '" + args[1]
                        + "'. Use workbench, pc, vending, display, auction, mailbox, or pallet."));
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

    // ---------------------------------------------------------------------
    //  market
    // ---------------------------------------------------------------------

    private void handleMarket(CommandSender sender, String[] args) {
        MarketService market = plugin.market();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";

        switch (sub) {
            case "list" -> {
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
                        + " &7 mid: &f" + plugin.economy().format(state.currentPrice())));
                sender.sendMessage(Text.of("&7  floor: &f" + plugin.economy().format(item.floor())
                        + " &7 ceiling: &f" + plugin.economy().format(item.ceiling())));
            }
            case "history" -> {
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
            default -> sender.sendMessage(Text.of("&cUsage: /hcm market <list|price|history|buy|sell> …"));
        }
    }

    private void handleTrade(CommandSender sender, String[] args, boolean buy) {
        if (denyUnless(sender, "hcm.market.order")) {
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
            sender.sendMessage(Text.of("&e/hcm give <workbench|pc> [player] &7- get a custom item"));
        }
        sender.sendMessage(Text.of("&e/hcm market list &7- list commodities (buy/sell/stock)"));
        sender.sendMessage(Text.of("&e/hcm market price <item> &7- inspect a commodity"));
        sender.sendMessage(Text.of("&e/hcm market history <item> &7- recent price snapshots"));
        sender.sendMessage(Text.of("&e/hcm market buy|sell <item> <qty> &7- trade"));
        sender.sendMessage(Text.of("&e/hcm mini museum &7- open the Mini Museum"));
        sender.sendMessage(Text.of("&e/hcm auction &7- open the Mini Auction House"));
        if (sender.hasPermission("hcm.admin")) {
            sender.sendMessage(Text.of("&e/hcm mini list|give <id> [player] &7- admin Minis"));
        }
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
            case "remove" -> removeDisplay(player);
            default -> {
                player.sendMessage(Text.of("&e/hcm display sign &7- bind the sign you're looking at to a commodity"));
                player.sendMessage(Text.of("&e/hcm display hologram &7- float a live-price hologram above the block you're looking at"));
                player.sendMessage(Text.of("&e/hcm display remove &7- unbind the display block you're looking at"));
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

    // ---------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("hcm.admin")) {
                addMatches(out, args[0], "admin", "reload", "give", "market", "display", "mini", "auction");
            } else {
                addMatches(out, args[0], "market", "mini", "auction");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mini")) {
            addMatches(out, args[1], "museum", "list", "give", "capturestand");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("mini")
                && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("capturestand"))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (com.dierks.homecraft.mini.MiniDef def : plugin.miniService().catalog()) {
                if (def.id().startsWith(prefix)) {
                    out.add(def.id());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            addMatches(out, args[1], "workbench", "pc", "vending", "display", "auction", "mailbox", "pallet");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("display")) {
            addMatches(out, args[1], "sign", "hologram", "maptv", "remove");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("market")) {
            addMatches(out, args[1], "list", "price", "history", "buy", "sell");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("market")
                && List.of("price", "history", "buy", "sell").contains(args[1].toLowerCase(Locale.ROOT))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
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
