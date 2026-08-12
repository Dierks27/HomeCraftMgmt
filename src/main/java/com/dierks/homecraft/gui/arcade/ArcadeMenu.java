package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Arcade hub (§3.9): see your token balance + streak, open loot crates, run
 * the pity exchange, and scratch a lotto ticket. Reached via {@code /hcm arcade}
 * or a placed Arcade block. All currency is in-game (tokens + Vault money).
 */
public final class ArcadeMenu extends Menu {

    private final Player player;

    public ArcadeMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(54, Text.of("&5&lArcade"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        PluginConfig.Arcade arc = plugin.config().arcade();
        int tokens = plugin.arcade().balance(player.getUniqueId());
        int streak = plugin.arcade().streak(player.getUniqueId());

        set(4, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens,
                "&7Login streak: &f" + streak + " day" + (streak == 1 ? "" : "s"),
                "&8Earn tokens by playing — login streaks & playtime."), null);

        // One icon per configured crate.
        int slot = 10;
        List<String> crateIds = new ArrayList<>(arc.crates().keySet());
        for (String id : crateIds) {
            if (slot > 16) {
                break;
            }
            PluginConfig.Crate crate = arc.crates().get(id);
            set(slot++, Menus.icon(Material.CHEST, crate.display(),
                    "&7Cost: &6" + crate.costTokens() + " token" + (crate.costTokens() == 1 ? "" : "s"),
                    "&7Rewards: &f" + crate.rewards().size() + " possible",
                    "&8—", "&eClick to view odds & open"),
                    e -> new CrateMenu(plugin, player, id, this::reopen).open(player));
        }

        // Pity exchange.
        if (arc.pityTokens() > 0) {
            set(29, Menus.icon(Material.NETHER_STAR, "&bPity Exchange",
                    "&7Spend &6" + arc.pityTokens() + " tokens &7for a",
                    "&7guaranteed &d" + arc.pityRarity() + "+ &7Mini.",
                    "&8—", "&eClick to redeem"), e -> {
                var r = plugin.arcade().pity(player);
                if (r.ok()) {
                    new RevealMenu(plugin, player, r, this::reopen).open(player);
                } else {
                    player.sendMessage(Text.of("&c" + r.error()));
                    refresh();
                }
            });
        }

        // Lotto / scratch ticket.
        set(33, Menus.icon(Material.PAPER, "&aScratch Ticket",
                "&7Cost: &6" + plugin.economy().format(arc.lotto().ticketCost()),
                "&7Randomised payout — a bit of hype.",
                "&8—", "&eClick to scratch"), e -> {
            var r = plugin.arcade().scratch(player);
            if (r.ok()) {
                new RevealMenu(plugin, player, r, this::reopen).open(player);
            } else {
                player.sendMessage(Text.of("&c" + r.error()));
                refresh();
            }
        });

        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private void reopen() {
        new ArcadeMenu(plugin, player).open(player);
    }
}
