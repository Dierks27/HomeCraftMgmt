package com.dierks.homecraft.gui.games.hub;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Bag (36; 0.44.0), from the Hub kit: a picture of everything that goes home with the visitor (all they carry
 * but the kit: a turkey's feather, a Mini). It only shows: the things themselves stay in the inventory, where the
 * session banks them at the end and hands them over at home.
 *
 * <pre>
 *  row 0     4 the Bag: "It all comes home with you"
 *  rows 1-2  9-26 what's in it
 *  row 3     31 Close
 * </pre>
 */
public final class BagMenu extends GameMenu {

    static final int BAG = 4;
    static final int FIRST = 9;
    static final int MAX = 18;
    static final int EMPTY = 13;

    public BagMenu(HomeCraftManagement plugin, Game hub, Player viewer) {
        super(plugin, hub, viewer, null);
        init(36, Text.of("&6Your Bag"));
    }

    /** Everything the visitor carries but the kit (the Bag's contents), in inventory order. */
    public static List<ItemStack> contents(ItemStack[] inventory) {
        List<ItemStack> out = new ArrayList<>();
        if (inventory == null) {
            return out;
        }
        for (ItemStack i : inventory) {
            if (i != null && !i.getType().isAir() && i.getAmount() > 0 && !KitItems.isKit(i)) {
                out.add(i);
            }
        }
        return out;
    }

    @Override
    protected void build() {
        fill();
        List<ItemStack> in = contents(viewer.getInventory().getContents());
        int things = 0;
        for (ItemStack i : in) {
            things += i.getAmount();
        }
        set(BAG, Menus.icon(Material.BUNDLE, "&6Your Bag &7- " + things + (things == 1 ? " thing" : " things"),
                "&7It all comes home with you", "&7when you leave."), null);
        if (in.isEmpty()) {
            set(EMPTY, Menus.icon(Material.FEATHER, "&7Empty - what you win here goes in"), null);
        }
        for (int i = 0; i < Math.min(MAX, in.size()); i++) {
            set(FIRST + i, in.get(i).clone(), null);
        }
        exitTile();
    }
}
