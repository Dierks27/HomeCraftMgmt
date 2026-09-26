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
        int tokens = plugin.tokens().balance(player.getUniqueId());
        int streak = plugin.tokens().streak(player.getUniqueId());

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

        // Pity exchange: a guaranteed Card at or above the configured rarity. It pays a CARD —
        // it used to promise a "Mini", and a Card is a Card.
        if (arc.pityTokens() > 0) {
            set(29, Menus.icon(Material.NETHER_STAR, "&bGet " + arc.pityRarity().article() + " "
                            + plugin.miniService().rarityFloorText(arc.pityRarity()) + " &bCard",
                    "&7Costs &6" + arc.pityTokens() + " tokens&7.",
                    "&7Print the Card at a Printer",
                    "&7to make the Mini.",
                    "&8—", "&eClick to get one"), e -> {
                var r = plugin.arcade().pity(player);
                if (r.ok()) {
                    new RevealMenu(plugin, player, r, this::reopen).open(player);
                } else {
                    player.sendMessage(Text.of("&c" + r.error()));
                    refresh();
                }
            });
        }

        // The Scratch Ticket — tokens in, tokens out, with the jackpot in its NAME for Bedrock.
        set(33, Menus.icon(Material.PAPER, "&aScratch Ticket &7- Jackpot &6" + plugin.arcade().pot(),
                "&7Costs &6" + arc.lotto().ticketTokens() + " tokens&7.",
                "&7Scratch it and see what you win!",
                "&8—", "&eClick to scratch"), e -> {
            var r = plugin.arcade().scratch(player);
            if (r.ok()) {
                new RevealMenu(plugin, player, r, this::reopen).open(player);
            } else {
                player.sendMessage(Text.of("&c" + r.error()));
                refresh();
            }
        });

        // The Prize Counter — the known-outcome half. Sits opposite the pity exchange so
        // the two ways to spend tokens (a price, a pull) read as a pair.
        if (!arc.prizes().isEmpty()) {
            set(27, Menus.icon(Material.ITEM_FRAME, "&6Prize Counter",
                    "&7Trade tokens for something you",
                    "&7choose — no odds, no surprises.",
                    "&8—", "&eClick to browse"),
                    e -> new PrizeCounterMenu(plugin, player, this::reopen).open(player));
        }

        // Daily / weekly quests.
        set(31, Menus.icon(Material.WRITABLE_BOOK, "&dQuests",
                "&7Daily & weekly objectives that pay",
                "&7out tokens on completion.",
                "&8—", "&eClick to view"), e -> new QuestsMenu(plugin, player, this::reopen).open(player));

        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private void reopen() {
        new ArcadeMenu(plugin, player).open(player);
    }
}
