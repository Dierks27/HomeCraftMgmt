package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.storage.MiniVendingDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

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

    /** Owner empties a Display Case, returning the Mini. */
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
        giveOrDrop(owner, item);
        try {
            displayDao.deleteAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear display: " + e.getMessage());
        }
        return Result.success();
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
        if (price <= 0) {
            return Result.fail("Set a price above 0 first.");
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
        return Result.success();
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
        return Result.success();
    }

    /** Owner takes a listing back out of the machine. */
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
        giveOrDrop(owner, item);
        removeVending(listingId);
        return Result.success();
    }

    /** A buyer purchases one listing: Vault seller-payment, item transfer, tracked. */
    public Result buyVending(Player buyer, long listingId) {
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
        giveOrDrop(buyer, item);
        plugin.miniService().transferOwner(l.uid(), buyer.getUniqueId());
        plugin.miniService().recordSale(l.uid(), l.miniId(), price, l.owner(), buyer.getUniqueId(), VENDING);
        removeVending(listingId);
        Player sellerOnline = Bukkit.getPlayer(l.owner());
        if (sellerOnline != null) {
            sellerOnline.sendMessage(Text.of("&aYour Mini sold for &f" + economy.format(price) + "&a."));
        }
        return Result.success();
    }

    // ---- break handling ---------------------------------------------------

    /** On block break: return every loaded Mini (display + all vending listings) to the breaker. */
    public void onBlockBroken(Location loc, Player breaker) {
        at(loc).ifPresent(display -> {
            ItemStack item = Items.fromBase64(display.itemB64());
            if (item != null) {
                dropTo(breaker, loc, item);
            }
            try {
                displayDao.deleteAt(loc);
            } catch (SQLException ignored) {
                // best effort
            }
        });
        for (MiniVendingDao.Listing l : vendingAt(loc)) {
            ItemStack item = Items.fromBase64(l.itemB64());
            if (item != null) {
                dropTo(breaker, loc, item);
            }
            removeVending(l.id());
        }
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

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    private void dropTo(Player player, Location loc, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(drop -> loc.getWorld().dropItemNaturally(loc.toCenterLocation(), drop));
    }
}
