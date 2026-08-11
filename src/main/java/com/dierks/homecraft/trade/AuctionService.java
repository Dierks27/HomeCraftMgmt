package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.MiniAuctionDao;
import com.dierks.homecraft.storage.MiniInboxDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Mini Auction House: timed auctions with a single escrowed top bid. A bid
 * withdraws the bidder immediately; the previous leader is auto-refunded when
 * outbid. On close (durable — a scheduler catches expiries even after a restart)
 * the winner already paid, the seller is paid, and the Mini transfers — tracked.
 * Winnings/returns owed to an offline player wait in their inbox until login.
 */
public final class AuctionService {

    public static final String VENUE = "AUCTION";

    public record Result(boolean ok, String error, long auctionId) {
        static Result fail(String error) {
            return new Result(false, error, 0);
        }

        static Result ok(long id) {
            return new Result(true, null, id);
        }
    }

    private final HomeCraftManagement plugin;
    private final MiniAuctionDao dao;
    private final MiniInboxDao inbox;
    private final EconomyService economy;

    public AuctionService(HomeCraftManagement plugin, MiniAuctionDao dao, MiniInboxDao inbox,
                          EconomyService economy) {
        this.plugin = plugin;
        this.dao = dao;
        this.inbox = inbox;
        this.economy = economy;
    }

    private double minIncrement() {
        return Math.max(0.01, plugin.getConfig().getDouble("minis.auction.min_increment", 1.0));
    }

    private long antiSnipeMs() {
        return Math.max(0, plugin.getConfig().getInt("minis.auction.anti_snipe_seconds", 15)) * 1000L;
    }

    private int maxDurationMinutes() {
        return Math.max(1, plugin.getConfig().getInt("minis.auction.max_duration_minutes", 10080)); // 7 days
    }

    public List<MiniAuctionDao.Auction> active() {
        try {
            return dao.active();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read auctions: " + e.getMessage());
            return List.of();
        }
    }

    public Optional<MiniAuctionDao.Auction> byId(long id) {
        try {
            return dao.byId(id);
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    /** Seller lists the Mini in their main hand for a timed auction. */
    public Result create(Player seller, double startBid, double buyNow, int durationMinutes) {
        MiniService.HeldMini held = plugin.miniService().getHeldMini(seller);
        if (held == null) {
            return Result.fail("Hold the Mini you want to auction in your main hand.");
        }
        MiniService.MiniRef ref = held.ref();
        if (startBid <= 0) {
            return Result.fail("Starting bid must be above 0.");
        }
        if (buyNow < 0 || (buyNow > 0 && buyNow < startBid)) {
            return Result.fail("Buy-It-Now must be 0 (off) or at least the starting bid.");
        }
        if (durationMinutes < 1 || durationMinutes > maxDurationMinutes()) {
            return Result.fail("Duration must be 1–" + maxDurationMinutes() + " minutes.");
        }
        ItemStack one = held.item().clone();
        one.setAmount(1);
        long now = System.currentTimeMillis();
        try {
            long id = dao.create(ref.uid(), ref.miniId(), ref.mintNumber(), seller.getUniqueId(),
                    startBid, buyNow, Items.toBase64(one), now + durationMinutes * 60_000L, now);
            held.item().setAmount(held.item().getAmount() - 1);
            return Result.ok(id);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create auction: " + e.getMessage());
            return Result.fail("Could not create the auction.");
        }
    }

    public Result bid(Player bidder, long id, double amount) {
        Optional<MiniAuctionDao.Auction> opt = byId(id);
        if (opt.isEmpty() || !opt.get().status().equals(MiniAuctionDao.ACTIVE)) {
            return Result.fail("That auction isn't active.");
        }
        MiniAuctionDao.Auction a = opt.get();
        if (a.seller().equals(bidder.getUniqueId())) {
            return Result.fail("You can't bid on your own auction.");
        }
        if (bidder.getUniqueId().equals(a.currentBidder())) {
            return Result.fail("You're already the top bidder.");
        }
        double min = a.hasBid() ? a.currentBid() + minIncrement() : a.startBid();
        if (amount < min) {
            return Result.fail("Minimum bid is " + economy.format(min) + ".");
        }
        if (!economy.isEnabled() || !economy.has(bidder, amount)) {
            return Result.fail("You can't afford " + economy.format(amount) + ".");
        }
        if (!economy.withdraw(bidder, amount)) {
            return Result.fail("Payment failed.");
        }
        // Refund the previous leader (money — safe even if they're offline).
        if (a.hasBid()) {
            economy.deposit(Bukkit.getOfflinePlayer(a.currentBidder()), a.currentBid());
            notify(a.currentBidder(), "&eYou were outbid on a Mini auction (#" + id + ").");
        }
        try {
            dao.updateBid(id, amount, bidder.getUniqueId());
            // Anti-snipe: a late bid extends the timer.
            long now = System.currentTimeMillis();
            long snipe = antiSnipeMs();
            if (snipe > 0 && a.endAt() - now <= snipe) {
                dao.extendEnd(id, now + snipe);
            }
        } catch (SQLException e) {
            economy.deposit(bidder, amount); // roll back the hold on a write failure
            return Result.fail("Could not record the bid — refunded.");
        }
        return Result.ok(id);
    }

    public Result buyNow(Player buyer, long id) {
        Optional<MiniAuctionDao.Auction> opt = byId(id);
        if (opt.isEmpty() || !opt.get().status().equals(MiniAuctionDao.ACTIVE)) {
            return Result.fail("That auction isn't active.");
        }
        MiniAuctionDao.Auction a = opt.get();
        if (a.buyNow() <= 0) {
            return Result.fail("This auction has no Buy-It-Now.");
        }
        if (a.seller().equals(buyer.getUniqueId())) {
            return Result.fail("You can't buy your own auction.");
        }
        if (!economy.isEnabled() || !economy.has(buyer, a.buyNow())) {
            return Result.fail("You can't afford " + economy.format(a.buyNow()) + ".");
        }
        if (!economy.withdraw(buyer, a.buyNow())) {
            return Result.fail("Payment failed.");
        }
        if (a.hasBid()) {
            economy.deposit(Bukkit.getOfflinePlayer(a.currentBidder()), a.currentBid());
            notify(a.currentBidder(), "&eAn auction you were leading (#" + id + ") was bought out.");
        }
        settle(a, buyer.getUniqueId(), a.buyNow());
        return Result.ok(id);
    }

    public Result cancel(Player seller, long id) {
        Optional<MiniAuctionDao.Auction> opt = byId(id);
        if (opt.isEmpty() || !opt.get().status().equals(MiniAuctionDao.ACTIVE)) {
            return Result.fail("That auction isn't active.");
        }
        MiniAuctionDao.Auction a = opt.get();
        if (!a.seller().equals(seller.getUniqueId()) && !seller.hasPermission("hcm.admin")) {
            return Result.fail("This isn't your auction.");
        }
        if (a.hasBid()) {
            return Result.fail("Can't cancel — it already has a bid.");
        }
        ItemStack item = Items.fromBase64(a.itemB64());
        if (item != null) {
            deliver(a.seller(), item);
        }
        close(id);
        return Result.ok(id);
    }

    /** Close every auction whose timer has elapsed (called by the scheduler). */
    public void closeDue() {
        List<MiniAuctionDao.Auction> due;
        try {
            due = dao.dueBy(System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to scan due auctions: " + e.getMessage());
            return;
        }
        for (MiniAuctionDao.Auction a : due) {
            if (a.hasBid()) {
                settle(a, a.currentBidder(), a.currentBid());
                notify(a.currentBidder(), "&aYou won a Mini auction (#" + a.id() + ") for &f"
                        + economy.format(a.currentBid()) + "&a! Delivered.");
            } else {
                ItemStack item = Items.fromBase64(a.itemB64());
                if (item != null) {
                    deliver(a.seller(), item);
                }
                notify(a.seller(), "&7Your Mini auction (#" + a.id() + ") ended with no bids — returned.");
                close(a.id());
            }
        }
    }

    /** Finalize a won/bought auction: pay the seller, hand the winner the Mini, track it. */
    private void settle(MiniAuctionDao.Auction a, UUID winner, double price) {
        economy.deposit(Bukkit.getOfflinePlayer(a.seller()), price);
        ItemStack item = Items.fromBase64(a.itemB64());
        if (item != null) {
            deliver(winner, item);
        }
        plugin.miniService().transferOwner(a.uid(), winner);
        plugin.miniService().recordSale(a.uid(), a.miniId(), price, a.seller(), winner, VENUE);
        notify(a.seller(), "&aYour Mini auction (#" + a.id() + ") sold for &f" + economy.format(price) + "&a.");
        close(a.id());
    }

    private void close(long id) {
        try {
            dao.setStatus(id, MiniAuctionDao.CLOSED);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to close auction " + id + ": " + e.getMessage());
        }
    }

    /** Hand an item to a player if online, else queue it in their inbox for login. */
    private void deliver(UUID playerId, ItemStack item) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            online.getInventory().addItem(item).values()
                    .forEach(drop -> online.getWorld().dropItemNaturally(online.getLocation(), drop));
            return;
        }
        try {
            inbox.addPending(playerId, Items.toBase64(item), "auction", System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to queue auction delivery: " + e.getMessage());
        }
    }

    private void notify(UUID playerId, String message) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            online.sendMessage(Text.of(message));
            return;
        }
        try {
            inbox.addNotification(playerId, message, System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to queue notification: " + e.getMessage());
        }
    }

    /** Deliver any queued notifications + items to a player on login. */
    public void deliverInbox(Player player) {
        try {
            for (String msg : inbox.drainNotifications(player.getUniqueId())) {
                player.sendMessage(Text.of(msg));
            }
            List<String> items = inbox.drainPending(player.getUniqueId());
            for (String b64 : items) {
                ItemStack item = Items.fromBase64(b64);
                if (item != null) {
                    player.getInventory().addItem(item).values()
                            .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
                }
            }
            if (!items.isEmpty()) {
                player.sendMessage(Text.of("&aDelivered " + items.size() + " Mini(s) you were owed."));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to deliver inbox for " + player.getName() + ": " + e.getMessage());
        }
    }
}
