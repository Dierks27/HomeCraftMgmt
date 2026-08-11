package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.MiniListingDao;
import com.dierks.homecraft.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The Mini secondary market's fixed-price venue: a Vending Machine sells one Mini
 * at the owner's price; a Display Case shows one as a trophy (no sale). Every
 * transfer moves Vault money to the (possibly offline) seller, hands the exact
 * stored Mini to the buyer, and logs the sale + ownership change — all tracked,
 * never through QuickShop.
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
    private final MiniListingDao dao;
    private final EconomyService economy;

    public VendingService(HomeCraftManagement plugin, MiniListingDao dao, EconomyService economy) {
        this.plugin = plugin;
        this.dao = dao;
        this.economy = economy;
    }

    public Optional<MiniListingDao.Listing> at(Location loc) {
        try {
            return dao.at(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read Mini listing: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Owner loads the Mini in their main hand into the block at {@code loc}. */
    public Result load(Player owner, Location loc, String kind, double price) {
        if (at(loc).isPresent()) {
            return Result.fail("This block already holds a Mini.");
        }
        MiniService.HeldMini held = plugin.miniService().getHeldMini(owner);
        if (held == null) {
            return Result.fail("Hold the Mini you want to load in your main hand.");
        }
        MiniService.MiniRef ref = held.ref();
        if (kind.equals(VENDING) && price <= 0) {
            return Result.fail("Set a price above 0 first.");
        }
        ItemStack one = held.item().clone();
        one.setAmount(1);
        String b64 = Items.toBase64(one);
        try {
            dao.create(loc, kind, owner.getUniqueId(), ref.uid(), ref.miniId(), ref.mintNumber(),
                    Math.max(0, price), b64, System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create Mini listing: " + e.getMessage());
            return Result.fail("Could not save the listing — try again.");
        }
        held.item().setAmount(held.item().getAmount() - 1); // consume exactly one from hand
        return Result.success();
    }

    /** Owner reclaims the Mini (unlist / empty a Display Case), returning it to their inventory. */
    public Result reclaim(Player owner, Location loc) {
        Optional<MiniListingDao.Listing> opt = at(loc);
        if (opt.isEmpty()) {
            return Result.fail("Nothing is loaded here.");
        }
        MiniListingDao.Listing listing = opt.get();
        if (!listing.owner().equals(owner.getUniqueId()) && !owner.hasPermission("hcm.admin")) {
            return Result.fail("This isn't yours.");
        }
        ItemStack item = Items.fromBase64(listing.itemB64());
        if (item == null) {
            return Result.fail("The stored Mini is unreadable.");
        }
        giveOrDrop(owner, item);
        delete(loc);
        return Result.success();
    }

    public Result setPrice(Player owner, Location loc, double price) {
        Optional<MiniListingDao.Listing> opt = at(loc);
        if (opt.isEmpty() || !opt.get().kind().equals(VENDING)) {
            return Result.fail("No Vending listing here.");
        }
        if (!opt.get().owner().equals(owner.getUniqueId())) {
            return Result.fail("This isn't yours.");
        }
        if (price <= 0) {
            return Result.fail("Price must be above 0.");
        }
        try {
            dao.updatePrice(loc, price);
        } catch (SQLException e) {
            return Result.fail("Could not update the price.");
        }
        return Result.success();
    }

    /** A buyer purchases the listed Mini: Vault seller-payment, item transfer, tracked. */
    public Result buy(Player buyer, Location loc) {
        Optional<MiniListingDao.Listing> opt = at(loc);
        if (opt.isEmpty()) {
            return Result.fail("Nothing is for sale here.");
        }
        MiniListingDao.Listing listing = opt.get();
        if (!listing.kind().equals(VENDING)) {
            return Result.fail("This is a Display Case — not for sale.");
        }
        if (listing.owner().equals(buyer.getUniqueId())) {
            return Result.fail("You can't buy your own listing — unlist it instead.");
        }
        if (!economy.isEnabled()) {
            return Result.fail("The economy is offline.");
        }
        double price = listing.price();
        if (!economy.has(buyer, price)) {
            return Result.fail("You can't afford " + economy.format(price) + ".");
        }
        ItemStack item = Items.fromBase64(listing.itemB64());
        if (item == null) {
            return Result.fail("The stored Mini is unreadable — ask the owner to relist.");
        }
        // Money first: withdraw the buyer, pay the (possibly offline) seller.
        if (!economy.withdraw(buyer, price)) {
            return Result.fail("Payment failed.");
        }
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.owner());
        if (!economy.deposit(seller, price)) {
            // Refund the buyer rather than lose their money if the seller deposit fails.
            economy.deposit(buyer, price);
            return Result.fail("Could not pay the seller — refunded.");
        }
        giveOrDrop(buyer, item);
        plugin.miniService().transferOwner(listing.uid(), buyer.getUniqueId());
        plugin.miniService().recordSale(listing.uid(), listing.miniId(), price,
                listing.owner(), buyer.getUniqueId(), VENDING);
        delete(loc);
        // Tell the seller if they're online.
        Player sellerOnline = Bukkit.getPlayer(listing.owner());
        if (sellerOnline != null) {
            sellerOnline.sendMessage(com.dierks.homecraft.util.Text.of(
                    "&aYour Mini sold for &f" + economy.format(price) + "&a."));
        }
        return Result.success();
    }

    /** On block break: hand any loaded Mini to the breaker and clear the listing. */
    public void onBlockBroken(Location loc, Player breaker) {
        Optional<MiniListingDao.Listing> opt = at(loc);
        if (opt.isEmpty()) {
            return;
        }
        ItemStack item = Items.fromBase64(opt.get().itemB64());
        if (item != null) {
            breaker.getInventory().addItem(item).values()
                    .forEach(drop -> breaker.getWorld().dropItemNaturally(loc.toCenterLocation(), drop));
        }
        delete(loc);
    }

    private void delete(Location loc) {
        try {
            dao.deleteAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete Mini listing: " + e.getMessage());
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    /** For a UUID owner check without a Player (e.g. block break by someone else). */
    public boolean isOwner(MiniListingDao.Listing listing, UUID player) {
        return listing.owner().equals(player);
    }
}
