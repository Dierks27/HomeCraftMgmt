package com.dierks.homecraft.gui.games.clubhouse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.arcade.feast.FeastService;
import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Clubhouse's Feast Pot (27; 0.44.0), tapped on the feast table. Inside the Clubhouse a visitor's own things
 * aren't on them (the Games world keeps its own inventory), so the food can't go in here: one big button takes them
 * to the real Feast Pot at home, food and all (the visit ends, their things come back, and they arrive beside the
 * pot). The family's total is on the pot's picture.
 *
 * <pre>
 *  row 0     4 the pot: the family's total
 *  row 1     10-16 Take me to the Feast Pot (one big button), or "not set yet"
 *  row 2     22 Close
 * </pre>
 */
public final class FeastPotMenu extends GameMenu {

    static final int POT = 4;
    static final int[] GO = {10, 11, 12, 13, 14, 15, 16};

    private final FeastService feast;

    public FeastPotMenu(HomeCraftManagement plugin, Game clubhouse, Player viewer, FeastService feast) {
        super(plugin, clubhouse, viewer, null);
        this.feast = feast;
        init(27, Text.of(MenuStyle.title("Feast Pot")));
    }

    @Override
    protected void build() {
        fill();
        FeastRules.FeastView v = feast.view();
        set(POT, Menus.icon(Material.CAULDRON, FeastRules.line(v.total(), v.goal()),
                "&7Your food is at home.", "&7The Feast Pot is there too."), null);
        if (feast.pot() == null) {
            set(GO[3], Menus.icon(Material.GRAY_DYE, "&7The Feast Pot isn't set yet"), null); // never Close's icon
        } else {
            for (int slot : GO) {
                set(slot, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&a&lTake me to the Feast Pot",
                        "&7Home, with your things,", "&7right beside the pot."), true), e -> go());
            }
        }
        exitTile();
    }

    private void go() {
        if (!feast.bringToPot(viewer)) {
            return;
        }
        // a tick later: closing a screen from inside its own click is unsafe
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            viewer.closeInventory();
            plugin.games().sessions().leave(viewer, EndReason.FINISH);
        });
    }
}
