package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import org.bukkit.entity.Player;

/**
 * Doors straight into one Arcade screen, with Back going to the hub — for the retired machine
 * blocks that still stand in the world and for commands.
 */
public final class ArcadeScreens {

    private ArcadeScreens() {
    }

    private static Runnable hub(HomeCraftManagement plugin, Player player) {
        return () -> new ArcadeMenu(plugin, player).open(player);
    }

    /** The only crate, or a list of every crate, or the hub if there are none. */
    public static void crate(HomeCraftManagement plugin, Player player) {
        var crates = plugin.config().arcade().crates();
        if (crates.isEmpty()) {
            new ArcadeMenu(plugin, player).open(player);
        } else if (crates.size() == 1) {
            new CrateMenu(plugin, player, crates.keySet().iterator().next(), hub(plugin, player)).open(player);
        } else {
            new CrateListMenu(plugin, player, hub(plugin, player)).open(player);
        }
    }

    /** The Prize Counter's Minis tab, where the Rare Card lives. */
    public static void minisTab(HomeCraftManagement plugin, Player player) {
        new PrizeCounterMenu(plugin, player, hub(plugin, player), PrizeTab.MINIS, 0).open(player);
    }

    public static void wallet(HomeCraftManagement plugin, Player player) {
        new WalletMenu(plugin, player, hub(plugin, player)).open(player);
    }

    public static void achievements(HomeCraftManagement plugin, Player player) {
        new AchievementsMenu(plugin, player, null, 0).open(player);
    }

    public static void guide(HomeCraftManagement plugin, Player player, int page) {
        new GuideMenu(plugin, player, page, null).open(player);
    }
}
