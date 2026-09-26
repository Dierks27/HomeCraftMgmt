package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Scratch-Ticket booth: buy a ticket for tokens and scratch it for a weighted-random token
 * payout or the jackpot (§3.9). Right-clicking a placed booth opens this.
 */
public final class ScratchMenu extends Menu {

    private final Player player;

    public ScratchMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(27, Text.of("&aScratch-Ticket Booth"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, i >= 18 ? Menus.FILLER : null, null);
        }
        PluginConfig.Lotto lotto = plugin.config().arcade().lotto();
        int tokens = plugin.tokens().balance(player.getUniqueId());
        set(4, Menus.icon(Material.PAPER, "&aScratch Ticket &7- &6" + lotto.ticketTokens() + " tokens",
                "&7Jackpot now: &6" + plugin.arcade().pot() + " tokens",
                "&8Scratch it and see what you win!"), null);
        set(13, Menus.icon(Material.SHEARS, "&eJackpot &6" + plugin.arcade().pot() + " &e- Scratch!",
                "&7You have &6" + tokens + " tokens&7.",
                "&8—", "&aClick to scratch"), e -> {
            var r = plugin.arcade().scratch(player);
            if (r.ok()) {
                new RevealMenu(plugin, player, r, () -> new ScratchMenu(plugin, player).open(player)).open(player);
            } else {
                player.sendMessage(Text.of("&c" + r.error()));
                refresh();
            }
        });
        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }
}
