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
 * Every crate, for a server with more than the hub's three crate slots — and what a placed Crate
 * Machine opens when there is more than one crate to choose from.
 */
public final class CrateListMenu extends Menu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private final Player player;
    private final Runnable back;

    public CrateListMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        super(plugin);
        this.player = player;
        this.back = back;
        init(54, Text.of("&6Crates"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        List<String> ids = new ArrayList<>(plugin.config().arcade().crates().keySet());
        if (ids.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No crates right now"), null);
        }
        for (int i = 0; i < ids.size() && i < SLOTS.length; i++) {
            String id = ids.get(i);
            PluginConfig.Crate crate = plugin.config().arcade().crates().get(id);
            set(SLOTS[i], ArcadeIcons.of(plugin, player, "crate", Material.CHEST,
                    crate.display() + " &7- &6" + crate.costTokens() + " tokens",
                    "&7See what's inside and the chances.", "&eClick to look"),
                    e -> new CrateMenu(plugin, player, id, this::reopen).open(player));
        }
        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private void reopen() {
        new CrateListMenu(plugin, player, back).open(player);
    }
}
