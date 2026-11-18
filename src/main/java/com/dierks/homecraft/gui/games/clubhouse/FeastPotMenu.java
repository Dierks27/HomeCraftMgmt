package com.dierks.homecraft.gui.games.clubhouse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.arcade.feast.FeastService;
import com.dierks.homecraft.arcade.feast.HomePantry;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The Clubhouse's Feast Pot (36; 0.42.0), tapped on the feast table: the food waiting in the visitor's saved home
 * inventory, one picture per food (as many as there are, up to a stack), and a tap puts one in. Pictures, not
 * words; the family bar shows after each tap, the munch and the rising chime say it went in.
 *
 * <pre>
 *  row 0     4 the pot: the family's total (its name), how it works (lore)
 *  rows 1-2  9-26 one food each, most points first
 *  row 3     31 Close
 * </pre>
 * With no food at home, row 1's middle says so with an empty bowl. It belongs to the Clubhouse, so it opens in the
 * Clubhouse's session.
 */
public final class FeastPotMenu extends GameMenu {

    static final int POT = 4;
    static final int FIRST = 9;
    static final int MAX = 18;
    static final int NONE = 13;

    private final FeastService feast;
    private final Location pot;

    public FeastPotMenu(HomeCraftManagement plugin, Game clubhouse, Player viewer, FeastService feast, Location pot) {
        super(plugin, clubhouse, viewer, null);
        this.feast = feast;
        this.pot = pot;
        init(36, Text.of("&6Feast Pot"));
    }

    @Override
    protected void build() {
        fill();
        FeastRules.FeastView v = feast.view();
        set(POT, Menus.icon(Material.CAULDRON, FeastRules.line(v.total(), v.goal()),
                "&7Tap a food to put one in.", "&7It comes from your things at home."), null);
        List<HomePantry.Food> foods = feast.homeFood(viewer);
        if (foods.isEmpty()) {
            set(NONE, Menus.icon(Material.BOWL, "&7No food at home"), null);
        }
        for (int i = 0; i < Math.min(MAX, foods.size()); i++) {
            HomePantry.Food f = foods.get(i);
            Material m = Material.matchMaterial(f.type());
            if (m == null) {
                continue;
            }
            ItemStack icon = Menus.icon(m, "&e+" + f.points() + " &7(" + f.count() + " at home)");
            icon.setAmount(Math.max(1, Math.min(m.getMaxStackSize(), f.count())));
            set(FIRST + i, icon, e -> {
                feast.feedFromHome(viewer, f.type(), pot);
                refresh();
            });
        }
        exitTile();
    }
}
