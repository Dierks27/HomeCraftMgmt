package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.block.CustomBlockGuard;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.marketplace.DeliveryService;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.storage.MiniVendingDao;
import com.dierks.homecraft.util.GiveAfterWrite;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Mini secondary market's fixed-price venue. A Vending Machine holds MANY
 * Minis — each its own priced listing (buyers browse a grid and click one). A
 * Display Case shows a single Mini as a trophy (no sale). Every purchase moves
 * Vault money to the (possibly offline) seller, hands the exact stored Mini to
 * the buyer, and logs the sale + ownership change — all tracked, never QuickShop.
 */
public final class VendingService {

    public static final String VENDING = "VENDING";
    public static final String DISPLAY = "DISPLAY";

    /** What the Display Case and the Vending Machine are called in the lines their owners see. */
    public static final String DISPLAY_CASE = CustomBlockGuard.name(CustomBlockType.DISPLAY_CASE);
    public static final String VENDING_MACHINE = CustomBlockGuard.name(CustomBlockType.MINI_VENDING_MACHINE);

    public record Result(boolean ok, String error) {
        static Result fail(String error) {
            return new Result(false, error);
        }

        static Result success() {
            return new Result(true, null);
        }
    }

    private final HomeCraftManagement plugin;
    private final MiniListingDao displayDao;   // Display Case (one per block)
    private final MiniVendingDao vendingDao;   // Vending Machine (many per block)
    private final EconomyService economy;

    public VendingService(HomeCraftManagement plugin, MiniListingDao displayDao,
                          MiniVendingDao vendingDao, EconomyService economy) {
        this.plugin = plugin;
        this.displayDao = displayDao;
        this.vendingDao = vendingDao;
        this.economy = economy;
    }

    // ---- Display Case (single) --------------------------------------------

    public Optional<MiniListingDao.Listing> at(Location loc) {
        try {
            return displayDao.at(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read Mini display: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Owner loads the held Mini into a Display Case (kind DISPLAY). */
    public Result load(Player owner, Location loc, String kind, double price) {
        if (at(loc).isPresent()) {
            return Result.fail("This block already holds a Mini.");
        }
        if (com.dierks.homecraft.util.TokenPrizes.carries(owner.getInventory().getItemInMainHand())) {
            return Result.fail(com.dierks.homecraft.util.TokenPrizes.REFUSAL);
        }
        MiniService.HeldMini held = plugin.miniService().getHeldMini(owner);
        if (held == null) {
            return Result.fail("Hold the Mini you want to load in your main hand.");
        }
        ItemStack one = held.item().clone();
        one.setAmount(1);
        try {
            displayDao.create(loc, kind, owner.getUniqueId(), held.ref().uid(), held.ref().miniId(),
                    held.ref().mintNumber(), Math.max(0, price), Items.toBase64(one), System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create display: " + e.getMessage());
            return Result.fail("Could not save — try again.");
        }
        held.item().setAmount(held.item().getAmount() - 1);
        return Result.success();
    }

    /**
     * Owner empties a Display Case, returning the Mini. The row is deleted first and the Mini handed over after
     * (0.37.3): a failed delete gives nothing, and a full bag sends it to the Mailbox, never the ground.
     */
    public Result reclaim(Player owner, Location loc) {
        Optional<MiniListingDao.Listing> opt = at(loc);
        if (opt.isEmpty()) {
            return Result.fail("Nothing is loaded here.");
        }
        if (!opt.get().owner().equals(owner.getUniqueId()) && !owner.hasPermission("hcm.admin")) {
            return Result.fail("This isn't yours.");
        }
        ItemStack item = Items.fromBase64(opt.get().itemB64());
        if (item == null) {
            return Result.fail("The stored Mini is unreadable.");
        }
        return takeOut(owner, item, DISPLAY_CASE, () -> displayDao.deleteAt(loc));
    }

    // ---- Vending Machine (many) -------------------------------------------

    public List<MiniVendingDao.Listing> vendingAt(Location loc) {
        try {
            return vendingDao.listingsAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read vending listings: " + e.getMessage());
            return List.of();
        }
    }

    /** Owner stocks the held Mini as a new priced listing in the machine. */
    public Result addVending(Player owner, Location loc, double price) {
        if (!plugin.sandbox().check(owner, "vending listing")) {
            return Result.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        if (price <= 0) {
            return Result.fail("Set a price above 0 first.");
        }
        if (com.dierks.homecraft.util.TokenPrizes.carries(owner.getInventory().getItemInMainHand())) {
            return Result.fail(com.dierks.homecraft.util.TokenPrizes.REFUSAL);
        }
        MiniService.HeldMini held = plugin.miniService().getHeldMini(owner);
        if (held == null) {
            return Result.fail("Hold the Mini you want to sell in your main hand.");
        }
        ItemStack one = held.item().clone();
        one.setAmount(1);
        try {
            vendingDao.add(loc, owner.getUniqueId(), held.ref().uid(), held.ref().miniId(),
                    held.ref().mintNumber(), price, Items.toBase64(one), System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to add vending listing: " + e.getMessage());
            return Result.fail("Could not save the listing — try again.");
        }
        held.item().setAmount(held.item().getAmount() - 1);
        shopsRefresh(loc);
        return Result.success();
    }

    private void shopsRefresh(Location loc) {
        if (plugin.shops() != null && loc != null) {
            plugin.shops().refresh(loc);
        }
    }

    private void shopsRefresh(long listingId) {
        vendingById(listingId).ifPresent(l -> shopsRefresh(new org.bukkit.Location(
                org.bukkit.Bukkit.getWorld(l.world()), l.x(), l.y(), l.z())));
    }

    public Result setVendingPrice(Player owner, long listingId, double price) {
        Optional<MiniVendingDao.Listing> opt = vendingById(listingId);
        if (opt.isEmpty()) {
            return Result.fail("That listing is gone.");
        }
        if (!opt.get().owner().equals(owner.getUniqueId()) && !owner.hasPermission("hcm.admin")) {
            return Result.fail("This isn't yours.");
        }
        if (price <= 0) {
            return Result.fail("Price must be above 0.");
        }
        try {
            vendingDao.updatePrice(listingId, price);
        } catch (SQLException e) {
            return Result.fail("Could not update the price.");
        }
        shopsRefresh(listingId);
        return Result.success();
    }

    /**
     * Owner takes a listing back out of the machine: the listing is deleted first and the Mini handed over after
     * (0.37.3), as {@link #reclaim}.
     */
    public Result unlistVending(Player owner, long listingId) {
        Optional<MiniVendingDao.Listing> opt = vendingById(listingId);
        if (opt.isEmpty()) {
            return Result.fail("That listing is gone.");
        }
        MiniVendingDao.Listing l = opt.get();
        if (!l.owner().equals(owner.getUniqueId()) && !owner.hasPermission("hcm.admin")) {
            return Result.fail("This isn't yours.");
        }
        ItemStack item = Items.fromBase64(l.itemB64());
        if (item == null) {
            return Result.fail("The stored Mini is unreadable.");
        }
        Result r = takeOut(owner, item, VENDING_MACHINE, () -> vendingDao.remove(listingId));
        shopsRefresh(new org.bukkit.Location(org.bukkit.Bukkit.getWorld(l.world()), l.x(), l.y(), l.z()));
        return r;
    }

    /** A row's delete, for {@link #takeOut}: false when it was already gone. */
    @FunctionalInterface
    private interface Delete {
        boolean delete() throws SQLException;
    }

    /**
     * Take a Mini out of storage for {@code to}: in one transaction its row is deleted (and, if the bag is full, it is
     * queued in their Mailbox), and only then does it go into the bag ({@link GiveAfterWrite}).
     */
    private Result takeOut(Player to, ItemStack item, String block, Delete delete) {
        DeliveryService mail = plugin.deliveries();
        String label = "From your " + block;
        GiveAfterWrite.Outcome out = mail.handOver(to, item, item.getAmount(), label, VENDING,
                toMailbox -> plugin.database().transaction(c -> {
                    if (!delete.delete()) {
                        return false;
                    }
                    mail.queueStacks(to.getUniqueId(), item, toMailbox, label, VENDING);
                    return true;
                }));
        return switch (out.status()) {
            case NOT_SAVED -> Result.fail("Could not save - try again.");
            case GONE -> Result.fail("That listing is gone.");
            case DONE -> {
                if (out.mailed() > 0) {
                    to.sendMessage(Text.of("&e✉ Your bag is full, so it went to your Mailbox (PC or Mailbox)."));
                }
                yield Result.success();
            }
        };
    }

    /** A buyer purchases one listing: Vault seller-payment, item transfer, tracked. */
    public Result buyVending(Player buyer, long listingId) {
        if (!plugin.sandbox().check(buyer, "vending purchase")) {
            return Result.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        Optional<MiniVendingDao.Listing> opt = vendingById(listingId);
        if (opt.isEmpty()) {
            return Result.fail("That listing is gone.");
        }
        MiniVendingDao.Listing l = opt.get();
        if (l.owner().equals(buyer.getUniqueId())) {
            return Result.fail("You can't buy your own listing — take it back instead.");
        }
        if (!economy.isEnabled()) {
            return Result.fail("The economy is offline.");
        }
        double price = l.price();
        if (!economy.has(buyer, price)) {
            return Result.fail("You can't afford " + economy.format(price) + ".");
        }
        ItemStack item = Items.fromBase64(l.itemB64());
        if (item == null) {
            return Result.fail("The stored Mini is unreadable — ask the owner to relist.");
        }
        if (!economy.withdraw(buyer, price)) {
            return Result.fail("Payment failed.");
        }
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.owner());
        if (!economy.deposit(seller, price)) {
            economy.deposit(buyer, price);
            return Result.fail("Could not pay the seller — refunded.");
        }
        giveOrMail(buyer, item);
        plugin.miniService().transferOwner(l.uid(), buyer.getUniqueId());
        plugin.miniService().recordSale(l.uid(), l.miniId(), price, l.owner(), buyer.getUniqueId(), VENDING);
        removeVending(listingId);
        shopsRefresh(new org.bukkit.Location(org.bukkit.Bukkit.getWorld(l.world()), l.x(), l.y(), l.z()));
        Player sellerOnline = Bukkit.getPlayer(l.owner());
        if (sellerOnline != null) {
            sellerOnline.sendMessage(Text.of("&aYour Mini sold for &f" + economy.format(price) + "&a."));
        }
        return Result.success();
    }

    // ---- break handling ---------------------------------------------------

    /**
     * One Mini going back to its owner's Mailbox from a broken block.
     *
     * @param owner the listing's owner: whoever stocked it, never whoever broke the block
     * @param block {@link #DISPLAY_CASE} or {@link #VENDING_MACHINE}: the block it came out of
     */
    public record Return(UUID owner, String itemB64, String block) {

        public String label() {
            return "From your " + block;
        }
    }

    /**
     * What a broken {@code block} gives back (pure): every Mini in it, the single display row's and each Vending
     * listing's, each to the owner of its listing. The breaker and the ground are never in it.
     */
    public static List<Return> returnPlan(String block, Optional<MiniListingDao.Listing> display,
                                          List<MiniVendingDao.Listing> vending) {
        List<Return> out = new ArrayList<>();
        display.ifPresent(d -> out.add(new Return(d.owner(), d.itemB64(), block)));
        for (MiniVendingDao.Listing l : vending) {
            out.add(new Return(l.owner(), l.itemB64(), block));
        }
        return out;
    }

    /**
     * A Vending Machine or Display Case is being broken (the break is final, 0.37.3): every Mini in it goes back to
     * whoever stocked it, always into their Mailbox, in one transaction with the row deletes, so a crash can lose
     * nothing and a failed write keeps every listing where it was. The Mailbox is used even when the owner breaks
     * their own block: it survives a crash, never drops, and doesn't depend on which inventory group they stand in.
     *
     * @param block {@link #DISPLAY_CASE} or {@link #VENDING_MACHINE}: the block being broken, named in the Mailbox
     * @return what went back (empty for an empty block)
     * @throws SQLException when it couldn't be written: nothing changed, and the block should stay
     */
    public List<Return> returnStock(Location loc, String block) throws SQLException {
        List<Return> back = returnStock(plugin.database(), displayDao, vendingDao, plugin.deliveries()::queueReady,
                loc, block);
        if (plugin.effects() != null) {
            plugin.effects().unregisterBlock(loc);
        }
        Map<UUID, Integer> perOwner = new LinkedHashMap<>();
        for (Return r : back) {
            perOwner.merge(r.owner(), 1, Integer::sum);
        }
        for (Map.Entry<UUID, Integer> e : perOwner.entrySet()) {
            Player online = Bukkit.getPlayer(e.getKey());
            if (online != null) {
                online.sendMessage(Text.of(returnedLine(e.getValue(), back.get(0).block())));
            }
        }
        return back;
    }

    /** {@link #returnStock(Location, String)}'s one transaction, over its DAOs and a Mailbox (a test's own). */
    static List<Return> returnStock(Database db, MiniListingDao displayDao, MiniVendingDao vendingDao,
                                    DeliveryService.Mailbox mailbox, Location loc, String block) throws SQLException {
        return db.transaction(c -> {
            Optional<MiniListingDao.Listing> display = displayDao.at(loc);
            List<MiniVendingDao.Listing> vending = vendingDao.listingsAt(loc);
            List<Return> plan = returnPlan(block, display, vending);
            if (display.isPresent()) {
                displayDao.deleteAt(loc);
            }
            for (MiniVendingDao.Listing l : vending) {
                vendingDao.remove(l.id());
            }
            for (Return r : plan) {
                mailbox.queueReady(r.owner(), r.itemB64(), r.label(), VENDING);
            }
            return plan;
        });
    }

    /** What an owner is told when their Minis come home from a broken block. */
    public static String returnedLine(int n, String block) {
        return n == 1 ? "&e✉ Your Mini from your " + block + " is in your Mailbox (PC or Mailbox)."
                : "&e✉ Your " + n + " Minis from your " + block + " are in your Mailbox (PC or Mailbox).";
    }

    // ---- helpers ----------------------------------------------------------

    private Optional<MiniVendingDao.Listing> vendingById(long id) {
        try {
            return vendingDao.byId(id);
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private void removeVending(long id) {
        try {
            vendingDao.remove(id);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to remove vending listing: " + e.getMessage());
        }
    }

    /**
     * A bought Mini into the buyer's bag; with no room it goes to their Mailbox, not the ground. Only if that write
     * fails too does it land at their feet, so a Mini that was paid for is never lost.
     */
    private void giveOrMail(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            try {
                plugin.deliveries().queueReady(player.getUniqueId(), left, "From a Vending Machine", VENDING);
                player.sendMessage(Text.of("&e✉ Your bag is full, so it went to your Mailbox (PC or Mailbox)."));
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to mail a bought Mini to " + player.getName() + ": "
                        + e.getMessage());
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }
}
