package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Manage the admin's own Mini Type/category list (create / rename / remove). These
 * are our categories for organising Minis — entirely independent of the
 * minecraft-heads.com browse categories used only to find heads to import.
 */
public final class CategoriesMenu extends Menu {

    private final Player player;
    private final Runnable onBack;

    public CategoriesMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&eMini Categories"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        List<String> cats = plugin.miniService().categories();
        for (int i = 0; i < cats.size() && i < 45; i++) {
            String cat = cats.get(i);
            set(i, Menus.icon(Material.NAME_TAG, "&f" + cat,
                    "&7Left-click: rename", "&7Right-click: remove"), e -> {
                if (e.getClick().isRightClick()) {
                    remove(cat);
                } else {
                    rename(cat);
                }
            });
        }

        set(48, Menus.icon(Material.LIME_DYE, "&a+ Add category"),
                e -> plugin.chatPrompts().prompt(player, "Type the new category name:", input -> {
                    plugin.miniService().addCategory(input);
                    reopen();
                }));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private void rename(String cat) {
        plugin.chatPrompts().prompt(player, "New name for '" + cat + "':", input -> {
            String v = input.trim();
            if (!v.isEmpty()) {
                List<String> next = new ArrayList<>(plugin.miniService().categories());
                next.replaceAll(c -> c.equalsIgnoreCase(cat) ? v : c);
                plugin.miniService().saveCategories(next);
            }
            reopen();
        });
    }

    private void remove(String cat) {
        List<String> next = new ArrayList<>(plugin.miniService().categories());
        next.removeIf(c -> c.equalsIgnoreCase(cat));
        plugin.miniService().saveCategories(next);
        reopen();
    }

    private void reopen() {
        new CategoriesMenu(plugin, player, onBack).open(player);
    }
}
