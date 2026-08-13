package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.BinderDao;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Map;

/**
 * The Card Binder (Round 3a): a per-player card store + set tracker. Cards move
 * between the player's inventory and their binder (persisted in the DB); the album
 * view lights owned cards and greys missing ones for set-completion goals.
 */
public final class BinderService {

    private final HomeCraftManagement plugin;
    private final BinderDao dao;
    private final BinderItems items = new BinderItems();

    public BinderService(HomeCraftManagement plugin, BinderDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public BinderItems items() {
        return items;
    }

    /** Every card in a player's binder (card id → count). */
    public Map<String, Integer> contents(Player player) {
        try {
            return dao.all(player.getUniqueId());
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read binder: " + e.getMessage());
            return Map.of();
        }
    }

    public int count(Player player, String cardId) {
        try {
            return dao.count(player.getUniqueId(), cardId);
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Move every Card in the player's inventory into their binder. Returns the count moved. */
    public int depositAll(Player player) {
        CardItems cards = plugin.miniService().cardItems();
        int moved = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null) {
                continue;
            }
            String id = cards.cardIdOf(it);
            if (id == null) {
                continue;
            }
            int amt = it.getAmount();
            try {
                dao.add(player.getUniqueId(), id, amt);
                it.setAmount(0);
                moved += amt;
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to deposit card " + id + ": " + e.getMessage());
            }
        }
        return moved;
    }

    /** Withdraw up to {@code n} of a card from the binder as items. Returns the count withdrawn. */
    public int withdraw(Player player, String cardId, int n) {
        try {
            int taken = dao.take(player.getUniqueId(), cardId, n);
            if (taken > 0) {
                ItemStack card = plugin.miniService().cardFor(cardId);
                if (card != null) {
                    card.setAmount(taken);
                    player.getInventory().addItem(card).values()
                            .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
                }
            }
            return taken;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to withdraw card " + cardId + ": " + e.getMessage());
            return 0;
        }
    }
}
