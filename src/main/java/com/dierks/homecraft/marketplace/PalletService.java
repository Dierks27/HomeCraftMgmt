package com.dierks.homecraft.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.storage.PalletDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The Crate Marketplace engine: a Pallet holds one item type at a fixed price
 * with a stock count (auto-deactivates when dry). A buyer purchases from the
 * Marketplace GUI — Vault withdraws (price + shipping), one item is pulled from
 * the seller's Pallet stock and shipped to the buyer's Mailbox, and the seller
 * is paid the price minus a % commission (the commission + shipping are burned as
 * a money sink). Ordinary items only — Minis stay on their tracked system.
 */
public final class PalletService {

    public record Result(boolean ok, String error, long deliverAt) {
        static Result fail(String e) {
            return new Result(false, e, 0);
        }
    }

    private final HomeCraftManagement plugin;
    private final PalletDao dao;
    private final EconomyService economy;
    private final DeliveryService deliveries;
    private final Categorizer categorizer;

    public PalletService(HomeCraftManagement plugin, PalletDao dao, EconomyService economy,
                         DeliveryService deliveries, Categorizer categorizer) {
        this.plugin = plugin;
        this.dao = dao;
        this.economy = economy;
        this.deliveries = deliveries;
        this.categorizer = categorizer;
    }

    public Optional<PalletDao.Listing> at(Location loc) {
        try {
            return dao.at(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read pallet: " + e.getMessage());
            return Optional.empty();
        }
    }

    public List<PalletDao.Listing> listActive(String department) {
        try {
            return dao.listActive(department);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to list marketplace: " + e.getMessage());
            return List.of();
        }
    }

    /** Owner loads the held item into the Pallet (new listing or restock). */
    public Result stock(Player owner, Location loc, double price) {
        PluginConfig.Marketplace mp = plugin.config().marketplace();
        if (mp.requireProtectedLand() && !plugin.protection().isProtectedLand(loc)) {
            return Result.fail("A Pallet only works on claimed/protected land.");
        }
        ItemStack held = owner.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return Result.fail("Hold the item you want to sell in your main hand.");
        }
        if (plugin.miniService().isMini(held)) {
            return Result.fail("Minis are sold at the Vending Machine / Auction House, not Pallets.");
        }
        if (mp.isBanned(held.getType())) {
            return Result.fail("That item can't be listed on the Marketplace.");
        }
        int amount = held.getAmount();
        ItemStack template = held.clone();
        template.setAmount(1);

        Optional<PalletDao.Listing> existing = at(loc);
        try {
            if (existing.isPresent()) {
                ItemStack current = Items.fromBase64(existing.get().itemB64());
                if (current == null || !current.isSimilar(template)) {
                    return Result.fail("This Pallet already holds a different item — take it back first.");
                }
                double newPrice = price > 0 ? price : existing.get().price();
                dao.upsert(loc, owner.getUniqueId(), existing.get().itemB64(), newPrice,
                        existing.get().stock() + amount, existing.get().department(), System.currentTimeMillis());
            } else {
                if (price <= 0) {
                    return Result.fail("Set a price above 0 first.");
                }
                String dept = categorizer.department(held.getType());
                dao.upsert(loc, owner.getUniqueId(), Items.toBase64(template), price, amount, dept,
                        System.currentTimeMillis());
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to stock pallet: " + e.getMessage());
            return Result.fail("Could not save the listing — try again.");
        }
        held.setAmount(0); // consumed the whole held stack into the pallet
        return new Result(true, null, 0);
    }

    public Result setPrice(Player owner, Location loc, double price) {
        Optional<PalletDao.Listing> opt = at(loc);
        if (opt.isEmpty()) {
            return Result.fail("Nothing is loaded here.");
        }
        if (!opt.get().owner().equals(owner.getUniqueId()) && !owner.hasPermission("hcm.admin")) {
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
        return new Result(true, null, 0);
    }

    /** Owner empties the Pallet, taking back all remaining stock. */
    public Result takeBack(Player owner, Location loc) {
        Optional<PalletDao.Listing> opt = at(loc);
        if (opt.isEmpty()) {
            return Result.fail("Nothing is loaded here.");
        }
        if (!opt.get().owner().equals(owner.getUniqueId()) && !owner.hasPermission("hcm.admin")) {
            return Result.fail("This isn't yours.");
        }
        returnStock(owner, opt.get());
        deleteAt(loc);
        return new Result(true, null, 0);
    }

    /** On block break: return all stock to the breaker and clear the listing. */
    public void onBlockBroken(Location loc, Player breaker) {
        at(loc).ifPresent(l -> {
            returnStock(breaker, l);
            deleteAt(loc);
        });
    }

    /** A buyer purchases one unit from a listing; it ships to their Mailbox. */
    public Result buy(Player buyer, long listingId, PluginConfig.ShippingTier tier) {
        Optional<PalletDao.Listing> opt;
        try {
            opt = dao.byId(listingId);
        } catch (SQLException e) {
            return Result.fail("Could not read the listing.");
        }
        if (opt.isEmpty() || !opt.get().active() || opt.get().stock() <= 0) {
            return Result.fail("That listing is sold out.");
        }
        PalletDao.Listing l = opt.get();
        if (l.owner().equals(buyer.getUniqueId())) {
            return Result.fail("You can't buy your own listing.");
        }
        if (!economy.isEnabled()) {
            return Result.fail("The economy is offline.");
        }
        ItemStack item = Items.fromBase64(l.itemB64());
        if (item == null) {
            return Result.fail("The listed item is unreadable.");
        }
        double price = l.price();
        double shipping = plugin.orderService().shippingCost(price, tier);
        double total = price + shipping;
        if (!economy.has(buyer, total)) {
            return Result.fail("You can't afford " + economy.format(total) + " ("
                    + economy.format(price) + " + " + economy.format(shipping) + " shipping).");
        }
        double commission = plugin.config().marketplace().commissionPercent();
        double sellerGets = price * (1.0 - commission / 100.0);

        if (!economy.withdraw(buyer, total)) {
            return Result.fail("Payment failed.");
        }
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.owner());
        economy.deposit(seller, sellerGets); // the rest (commission + shipping) is burned

        try {
            dao.updateStock(listingId, l.stock() - 1);
        } catch (SQLException e) {
            economy.deposit(buyer, total); // roll back on write failure
            economy.withdraw(seller, sellerGets);
            return Result.fail("Could not update stock — refunded.");
        }

        long deliverAt = System.currentTimeMillis() + (long) (tier.realHours() * 3_600_000L);
        ItemStack one = item.clone();
        one.setAmount(1);
        deliveries.create(buyer.getUniqueId(), one, label(one), "MARKETPLACE", deliverAt);

        Player sellerOnline = Bukkit.getPlayer(l.owner());
        if (sellerOnline != null) {
            sellerOnline.sendMessage(Text.of("&aA Marketplace sale earned you &f"
                    + economy.format(sellerGets) + "&a."));
        }
        return new Result(true, null, deliverAt);
    }

    // ---- helpers ----------------------------------------------------------

    private void returnStock(Player to, PalletDao.Listing l) {
        ItemStack template = Items.fromBase64(l.itemB64());
        if (template == null) {
            return;
        }
        int remaining = l.stock();
        int max = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            ItemStack give = template.clone();
            give.setAmount(n);
            to.getInventory().addItem(give).values()
                    .forEach(drop -> to.getWorld().dropItemNaturally(to.getLocation(), drop));
            remaining -= n;
        }
    }

    private void deleteAt(Location loc) {
        try {
            dao.deleteAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete pallet: " + e.getMessage());
        }
    }

    private String label(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(item.getItemMeta().displayName());
        }
        String n = item.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
