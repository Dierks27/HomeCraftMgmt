package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Token Counter: check your balance and see how earning is going — login
 * streak and progress toward the next playtime token (Phase 9).
 */
public final class TokenCounterMenu extends Menu {

    private final Player player;

    public TokenCounterMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(27, Text.of("&eToken Counter"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, i >= 18 ? Menus.FILLER : null, null);
        }
        PluginConfig.Arcade arc = plugin.config().arcade();
        int tokens = plugin.arcade().balance(player.getUniqueId());
        int streak = plugin.arcade().streak(player.getUniqueId());

        set(4, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens), null);

        set(11, Menus.icon(Material.CLOCK, "&dLogin Streak",
                "&7Current: &f" + streak + " day" + (streak == 1 ? "" : "s"),
                "&7Log in each day for escalating tokens.",
                "&7Tomorrow: &a+" + arc.streakReward(streak + 1) + " tokens"), null);

        int toNext = plugin.arcade().minutesToNextPlaytimeToken(player);
        set(13, Menus.icon(Material.DIAMOND_PICKAXE, "&bPlaytime",
                arc.playtimeEnabled() && arc.playtimeMinutesPerToken() > 0
                        ? "&7Next token in &f~" + toNext + " min &7of play"
                        : "&7Playtime rewards are off",
                "&8Just keep playing — tokens accrue."), null);

        set(15, Menus.icon(Material.CHEST, "&6Spend at the Arcade",
                "&7Crate Machine, Scratch Booth, Pity Exchange.",
                "&7Right-click those machines to play."), null);

        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }
}
