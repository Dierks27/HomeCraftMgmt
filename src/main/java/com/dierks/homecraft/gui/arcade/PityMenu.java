package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Pity Exchange kiosk: spend N tokens for a guaranteed Rare+ Mini so a cold
 * streak never fully burns you (Phase 9). Right-clicking a placed kiosk opens this.
 */
public final class PityMenu extends Menu {

    private final Player player;

    public PityMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(27, Text.of("&bPity Exchange"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, i >= 18 ? Menus.FILLER : null, null);
        }
        PluginConfig.Arcade arc = plugin.config().arcade();
        int tokens = plugin.tokens().balance(player.getUniqueId());
        set(4, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens), null);

        if (arc.pityTokens() <= 0) {
            set(13, Menus.icon(Material.BARRIER, "&cNot available right now"), null);
        } else {
            set(13, Menus.icon(Material.NETHER_STAR, "&bGet " + arc.pityRarity().article() + " "
                            + plugin.miniService().rarityFloorText(arc.pityRarity()) + " &bCard",
                    "&7Costs &6" + arc.pityTokens() + " tokens&7.",
                    "&7Print the Card at a Printer",
                    "&7to make the Mini.",
                    "&8—", "&aClick to get one"), e -> {
                var r = plugin.arcade().pity(player);
                if (r.ok()) {
                    new RevealMenu(plugin, player, r, () -> new PityMenu(plugin, player).open(player)).open(player);
                } else {
                    player.sendMessage(Text.of("&c" + r.error()));
                    refresh();
                }
            });
        }
        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }
}
