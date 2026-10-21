package com.dierks.homecraft.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.block.BlockSkins;
import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.integration.EconomyService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.PalletDao;
import com.dierks.homecraft.util.GiveAfterWrite;
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

    /** The Mailbox source of stock coming home from a Pallet (0.37.3). */
    public static final String SOURCE = "PALLET";

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
        if (!plugin.sandbox().check(owner, "marketplace listing")) {
            return Result.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        PluginConfig.Marketplace mp = plugin.config().marketplace();
        if (mp.requireProtectedLand() && !plugin.protection().isProtectedLand(loc)) {
            return Result.fail("A Pallet only works on claimed/protected land.");
        }
        ItemStack held = owner.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return Result.fail("Hold the item you want to sell in your main hand.");
        }
        String refusal = stockRefusal(plugin.miniService().isMini(held),
                com.dierks.homecraft.util.TokenPrizes.carries(held),
                com.dierks.homecraft.courier.CourierPackage.is(held), mp.isBanned(held.getType()));
        if (refusal != null) {
            return Result.fail(refusal);
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
                // The EXISTING owner, not whoever is restocking. An admin can open "Stock more"
                // on someone else's Pallet, and passing the caller here quietly re-homed the
                // listing — every later sale would have paid the restocker instead of the seller.
                dao.upsert(loc, existing.get().owner(), existing.get().itemB64(), newPrice,
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
        refreshSkin(loc);
        return new Result(true, null, 0);
    }

    /**
     * Why a held stack can't be put on a Pallet, or null when it can (pure; the checks in their order).
     *
     * @param carries {@code TokenPrizes.carries}: a prize, or something holding one (or holding anything too deep to
     *                read, 0.37.3)
     */
    public static String stockRefusal(boolean mini, boolean carries, boolean courierPackage, boolean banned) {
        if (mini) {
            return "Minis are sold at the Vending Machine / Auction House, not Pallets.";
        }
        // Tokens never become dollars: nothing bought or won with tokens is listed (§11 #9).
        if (carries) {
            return com.dierks.homecraft.util.TokenPrizes.REFUSAL;
        }
        // A delivery crate is worth nothing and must stay worth nothing: anything that survives
        // its job is an item you can mint on demand by taking Courier work. This is the one
        // listing surface that accepts an arbitrary stack, so it is the one that has to say no.
        if (courierPackage) {
            return "A delivery crate is not yours to sell.";
        }
        if (banned) {
            return "That item can't be listed on the Marketplace.";
        }
        return null;
    }

    /**
     * Two visual states: a placed Pallet wears {@code skins.pallet_empty} with no
     * listing and {@code skins.pallet_used} once one is loaded. Applied to the placed
     * head the same way the Display Case trophy is (no-op if the base isn't a head).
     */
    public void refreshSkin(Location loc) {
        String empty = plugin.config().skin(CustomBlockType.PALLET);
        String used = plugin.config().skinNamed("pallet_used");
        boolean loaded = at(loc).isPresent();
        String texture = loaded && !used.isBlank() ? used : empty;
        BlockSkins.apply(loc.getBlock(), texture);
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

    /**
     * Owner empties the Pallet, taking back all remaining stock. The listing is deleted first and the stock handed
     * over after (0.37.3, {@link GiveAfterWrite}): a failed delete gives nothing, and what doesn't fit the bag goes
     * to the Mailbox, one row per stack, never the ground.
     */
    public Result takeBack(Player owner, Location loc) {
        Optional<PalletDao.Listing> opt = at(loc);
        if (opt.isEmpty()) {
            return Result.fail("Nothing is loaded here.");
        }
        if (!opt.get().owner().equals(owner.getUniqueId()) && !owner.hasPermission("hcm.admin")) {
            return Result.fail("This isn't yours.");
        }
        PalletDao.Listing l = opt.get();
        ItemStack template = Items.fromBase64(l.itemB64());
        if (template == null || l.stock() <= 0) {
            // Nothing to hand over (as before): the empty or unreadable listing just goes.
            deleteAt(loc);
            refreshSkin(loc);
            return new Result(true, null, 0);
        }
        String label = "From your Pallet";
        GiveAfterWrite.Outcome out = deliveries.handOver(owner, template, l.stock(), label, SOURCE,
                toMailbox -> plugin.database().transaction(c -> {
                    if (!dao.deleteAt(loc)) {
                        return false;
                    }
                    deliveries.queueStacks(owner.getUniqueId(), template, toMailbox, label, SOURCE);
                    return true;
                }));
        if (out.status() == GiveAfterWrite.Status.NOT_SAVED) {
            return Result.fail("Could not save - try again.");
        }
        if (out.status() == GiveAfterWrite.Status.GONE) {
            return Result.fail("Nothing is loaded here.");
        }
        if (out.mailed() > 0) {
            owner.sendMessage(Text.of("&e✉ Your bag is full, so the rest went to your Mailbox (PC or Mailbox)."));
        }
        refreshSkin(loc);
        return new Result(true, null, 0);
    }

    /**
     * A Pallet is being broken (the break is final, 0.37.3): its stock goes back to whoever stocked it, always
     * into their Mailbox, one row per full stack, in one transaction with the listing's delete. Never the breaker,
     * never the ground; a failed write changes nothing.
     *
     * @return how many items went back (0 for an empty Pallet)
     * @throws SQLException when it couldn't be written: nothing changed, and the block should stay
     */
    public int returnStock(Location loc) throws SQLException {
        Optional<PalletDao.Listing> opt = dao.at(loc);
        if (opt.isEmpty()) {
            return 0;
        }
        PalletDao.Listing l = opt.get();
        ItemStack template = Items.fromBase64(l.itemB64());
        int max = template == null ? 1 : Math.max(1, template.getMaxStackSize());
        int back = returnStock(plugin.database(), dao, plugin.deliveries()::queueReady, loc, max,
                template == null ? null : n -> Items.toBase64(GiveAfterWrite.withAmount(template, n)));
        Player online = Bukkit.getPlayer(l.owner());
        if (online != null && back > 0) {
            online.sendMessage(Text.of("&e✉ The " + back + " " + label(template) + " from your Pallet "
                    + (back == 1 ? "is" : "are") + " in your Mailbox (PC or Mailbox)."));
        }
        return back;
    }

    /**
     * {@link #returnStock(Location)}'s one transaction (a test passes its own Mailbox and encoder): the listing at
     * {@code loc} is deleted and its stock queued for its owner, one row per stack of at most {@code maxStack}.
     *
     * @param encode a stack of {@code n} of the listed item as Base64; null when the item can't be read (the
     *               listing is only deleted, as before: there is nothing to hand back)
     */
    static int returnStock(Database db, PalletDao dao, DeliveryService.Mailbox mailbox, Location loc, int maxStack,
                           java.util.function.IntFunction<String> encode) throws SQLException {
        return db.transaction(c -> {
            Optional<PalletDao.Listing> opt = dao.at(loc);
            if (opt.isEmpty()) {
                return 0;
            }
            PalletDao.Listing l = opt.get();
            int stock = encode == null ? 0 : Math.max(0, l.stock());
            dao.deleteAt(loc);
            for (int n : GiveAfterWrite.stacks(stock, maxStack)) {
                mailbox.queueReady(l.owner(), encode.apply(n), "From your Pallet", SOURCE);
            }
            return stock;
        });
    }

    /** A buyer purchases one unit from a listing; it ships to their Mailbox. */
    /**
     * Buy one unit of a listing.
     *
     * @param quotedPrice the unit price the buyer was shown, so a seller repricing mid-checkout
     *                    cannot charge more than that; 0 skips the check
     */
    public Result buy(Player buyer, long listingId, double quotedPrice, PluginConfig.ShippingTier tier) {
        if (!plugin.sandbox().check(buyer, "marketplace purchase")) {
            return Result.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
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
        // The checkout screen was painted from an earlier read of this row, and the seller can
        // reprice at their Pallet in between. Being charged MORE than the figure on screen is the
        // harm, so refuse that and send them back for a fresh quote; a price that has fallen is
        // simply honoured at the lower number. Callers passing 0 opt out of the check.
        if (quotedPrice > 0 && l.price() > quotedPrice + 0.0001) {
            return Result.fail("The price just changed to " + economy.format(l.price())
                    + " — reopen the listing.");
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

        long deliverAt = System.currentTimeMillis() + tier.deliveryMillis();
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

    private void deleteAt(Location loc) {
        try {
            dao.deleteAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete pallet: " + e.getMessage());
        }
    }

    private String label(ItemStack item) {
        if (item == null) {
            return "items";
        }
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(item.getItemMeta().displayName());
        }
        String n = item.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
