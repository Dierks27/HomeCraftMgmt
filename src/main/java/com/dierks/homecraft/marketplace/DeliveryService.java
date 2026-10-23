package com.dierks.homecraft.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.util.GiveAfterWrite;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * The Mailbox delivery queue: Marketplace purchases (and future web orders) that
 * ship to a player's Mailbox after a real-time delay. Items wait safely until
 * collected — nothing ever drops or vanishes. Collected at a Mailbox block or the
 * PC ("virtual inbox"), so a player without a Mailbox is never stuck.
 */
public final class DeliveryService {

    public record Result(boolean ok, String error) {
        static Result fail(String e) {
            return new Result(false, e);
        }
        static Result success() {
            return new Result(true, null);
        }
    }

    /** Where stock going home is written: {@link #queueReady(UUID, String, String, String)}, or a test's own. */
    @FunctionalInterface
    public interface Mailbox {
        void queueReady(UUID player, String itemB64, String label, String source) throws SQLException;
    }

    private final HomeCraftManagement plugin;
    private final DeliveryDao dao;

    public DeliveryService(HomeCraftManagement plugin, DeliveryDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** Queue an item for delivery to {@code player} arriving at {@code deliverAt}. */
    public void create(UUID player, ItemStack item, String label, String source, long deliverAt) {
        try {
            dao.insert(player, Items.toBase64(item), label, source, System.currentTimeMillis(), deliverAt,
                    DeliveryDao.IN_TRANSIT);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to queue delivery: " + e.getMessage());
        }
    }

    /**
     * Put an item straight into {@code player}'s Mailbox, ready to collect (0.37.3): stock coming back from a broken
     * Vending Machine, Display Case or Pallet, a placed prize that didn't fit the breaker's bag, a hand-over's
     * remainder. Unlike {@link #create} it throws, so it can be written inside a caller's {@code Database.transaction}
     * and roll back with it.
     */
    public void queueReady(UUID player, ItemStack item, String label, String source) throws SQLException {
        queueReady(player, Items.toBase64(item), label, source);
    }

    /** {@link #queueReady(UUID, ItemStack, String, String)} for an item already stored as Base64 (a listing's). */
    public void queueReady(UUID player, String itemB64, String label, String source) throws SQLException {
        long now = System.currentTimeMillis();
        dao.insert(player, itemB64, label, source, now, now, DeliveryDao.READY);
    }

    /**
     * Put an item in {@code player}'s Mailbox for a trade that hasn't finished yet (0.38, SHOP-V2-SPEC §4.6, §4.7):
     * status HELD, which nobody can collect until {@link #release} turns it READY once both bags are saved. Written
     * inside the swap's transaction; it throws, so it rolls back with it.
     *
     * @param source the trade's own tag, {@code TRADE#<id>}, so its rows are released (or voided) together
     */
    public void queueHeld(UUID player, ItemStack item, String label, String source) throws SQLException {
        queueHeld(player, Items.toBase64(item), label, source);
    }

    /** {@link #queueHeld(UUID, ItemStack, String, String)} for an item already stored as Base64. */
    public void queueHeld(UUID player, String itemB64, String label, String source) throws SQLException {
        long now = System.currentTimeMillis();
        dao.insert(player, itemB64, label, source, now, now, DeliveryDao.HELD);
    }

    /** Release one trade's HELD rows: READY to collect now. Joins a caller's transaction. */
    public int release(String source) throws SQLException {
        return dao.release(source, System.currentTimeMillis());
    }

    /** Flip due in-transit deliveries to READY and post a "you've got mail" notice. */
    public void tick() {
        try {
            for (DeliveryDao.Delivery d : dao.findDue(System.currentTimeMillis())) {
                dao.updateStatus(d.id(), DeliveryDao.READY);
                Player online = plugin.getServer().getPlayer(d.player());
                if (online != null) {
                    online.sendMessage(Text.of("&e✉ You've got mail — &f" + d.label()
                            + " &earrived in your Mailbox."));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to process deliveries: " + e.getMessage());
        }
    }

    public List<DeliveryDao.Delivery> activeFor(Player player) {
        try {
            return dao.activeFor(player.getUniqueId());
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to list deliveries: " + e.getMessage());
            return List.of();
        }
    }

    public int readyCount(Player player) {
        int n = 0;
        for (DeliveryDao.Delivery d : activeFor(player)) {
            if (d.status().equals(DeliveryDao.READY)) {
                n++;
            }
        }
        return n;
    }

    /** Notify a player on login if mail is waiting. */
    public void notifyOnJoin(Player player) {
        int ready = readyCount(player);
        if (ready > 0) {
            player.sendMessage(Text.of("&e✉ You've got mail — &f" + ready
                    + " &edelivery(ies) ready. Collect them at your Mailbox or PC."));
        }
    }

    /**
     * Collect one READY delivery into the player's inventory. If the inventory is full, whatever fits is taken and
     * the rest stays in the Mailbox (never drops). Write first, then give (0.37.3, {@link GiveAfterWrite}): the row
     * is marked COLLECTED (or rewritten to the part that won't fit) before anything goes into the bag, so a failed
     * write gives nothing; and if the bag then takes less than planned, the remainder comes back as a new READY row.
     */
    public Result collect(Player player, long deliveryId) {
        DeliveryDao.Delivery d;
        try {
            d = dao.byId(deliveryId).orElse(null);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to collect delivery: " + e.getMessage());
            return Result.fail("Could not collect — try again.");
        }
        if (d == null || !d.player().equals(player.getUniqueId())) {
            return Result.fail("That delivery isn't yours.");
        }
        if (!d.status().equals(DeliveryDao.READY)) {
            return Result.fail("That delivery is still in transit.");
        }
        ItemStack item = Items.fromBase64(d.itemB64());
        if (item == null) {
            try {
                dao.updateStatus(deliveryId, DeliveryDao.COLLECTED);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to collect delivery: " + e.getMessage());
            }
            return Result.fail("The stored item was unreadable.");
        }
        if (GiveAfterWrite.room(player.getInventory(), item) <= 0) {
            return Result.fail("Your inventory is full — the rest is still in your Mailbox.");
        }
        GiveAfterWrite.Outcome out = handOver(player, item, item.getAmount(), d.label(), d.source(), stays -> {
            int left = stays.stream().mapToInt(Integer::intValue).sum();
            return left == 0 ? dao.updateStatusIf(d.id(), DeliveryDao.READY, DeliveryDao.COLLECTED)
                    : dao.updateItemIf(d.id(), DeliveryDao.READY,
                            Items.toBase64(GiveAfterWrite.withAmount(item, left)));
        });
        if (out.status() == GiveAfterWrite.Status.GONE) {
            return Result.fail("That delivery isn't there any more.");
        }
        if (!out.ok()) {
            return Result.fail("Could not collect — try again.");
        }
        return out.mailed() > 0 ? Result.fail("Your inventory is full — the rest is still in your Mailbox.")
                : Result.success();
    }

    /**
     * Hand {@code amount} of {@code template} to {@code to} in the safe order ({@link GiveAfterWrite}): {@code write}
     * first (the row's delete or new status, with a Mailbox row for each stack that won't fit, in one transaction),
     * then the bag; a remainder the bag still won't take comes back to {@code to}'s Mailbox. Never the ground.
     */
    public GiveAfterWrite.Outcome handOver(Player to, ItemStack template, int amount, String label, String source,
                                           GiveAfterWrite.Write write) {
        Inventory bag = to.getInventory();
        return GiveAfterWrite.run(amount, GiveAfterWrite.room(bag, template), GiveAfterWrite.maxStack(bag, template),
                new GiveAfterWrite.Steps() {
                    @Override
                    public boolean write(List<Integer> toMailbox) throws SQLException {
                        return write.write(toMailbox);
                    }

                    @Override
                    public int give(int n) {
                        return GiveAfterWrite.give(bag, template, n);
                    }

                    @Override
                    public void writeBack(List<Integer> amounts) throws SQLException {
                        plugin.database().transaction(c -> {
                            queueStacks(to.getUniqueId(), template, amounts, label, source);
                            return null;
                        });
                    }

                    @Override
                    public void notSaved(SQLException e) {
                        plugin.getLogger().severe("Couldn't save taking " + amount + " x " + label + " out for "
                                + to.getName() + ", so nothing was given: " + e.getMessage());
                    }

                    @Override
                    public void lost(int n, SQLException e) {
                        plugin.getLogger().severe("Mailbox: " + n + " x " + label + " didn't fit " + to.getName()
                                + "'s bag and couldn't be written to their Mailbox (" + e.getMessage()
                                + "). Give it back by hand; the item: "
                                + Items.toBase64(GiveAfterWrite.withAmount(template, n)));
                    }
                });
    }

    /** {@link #queueReady} once for each amount of {@code template} (one row per stack). */
    public void queueStacks(UUID player, ItemStack template, List<Integer> amounts, String label, String source)
            throws SQLException {
        for (int n : amounts) {
            queueReady(player, GiveAfterWrite.withAmount(template, n), label, source);
        }
    }
}
