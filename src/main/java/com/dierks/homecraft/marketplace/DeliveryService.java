package com.dierks.homecraft.marketplace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
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
        static Result ok() {
            return new Result(true, null);
        }
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
     * Collect one READY delivery into the player's inventory. If the inventory is
     * full, whatever fits is taken and the rest stays in the Mailbox (never drops).
     */
    public Result collect(Player player, long deliveryId) {
        try {
            DeliveryDao.Delivery d = dao.byId(deliveryId).orElse(null);
            if (d == null || !d.player().equals(player.getUniqueId())) {
                return Result.fail("That delivery isn't yours.");
            }
            if (!d.status().equals(DeliveryDao.READY)) {
                return Result.fail("That delivery is still in transit.");
            }
            ItemStack item = Items.fromBase64(d.itemB64());
            if (item == null) {
                dao.updateStatus(deliveryId, DeliveryDao.COLLECTED);
                return Result.fail("The stored item was unreadable.");
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (leftover.isEmpty()) {
                dao.updateStatus(deliveryId, DeliveryDao.COLLECTED);
                return Result.ok();
            }
            // Inventory full — keep the remainder in the Mailbox.
            ItemStack rem = leftover.values().iterator().next();
            dao.updateItem(deliveryId, Items.toBase64(rem));
            return Result.fail("Your inventory is full — the rest is still in your Mailbox.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to collect delivery: " + e.getMessage());
            return Result.fail("Could not collect — try again.");
        }
    }
}
