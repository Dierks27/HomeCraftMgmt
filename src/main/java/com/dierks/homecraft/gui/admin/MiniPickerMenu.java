package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Pick a Mini from the catalog by clicking its rendered head (optionally filtered). */
public final class MiniPickerMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Consumer<String> onPick;
    private final Runnable onBack;
    private String filter = "";
    private int page;

    public MiniPickerMenu(HomeCraftManagement plugin, Player player, Consumer<String> onPick, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onPick = onPick;
        this.onBack = onBack;
        init(54, Text.of("&aPick a Mini"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        List<MiniDef> all = plugin.miniService().catalogList();
        List<MiniDef> list = new ArrayList<>();
        for (MiniDef d : all) {
            if (filter.isBlank() || d.name().toLowerCase().contains(filter.toLowerCase())
                    || d.id().contains(filter.toLowerCase())) {
                list.add(d);
            }
        }
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= list.size()) {
                set(i, null, null);
                continue;
            }
            MiniDef def = list.get(idx);
            set(i, plugin.miniService().icon(def), e -> onPick.accept(def.id()));
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(47, Menus.icon(Material.OAK_SIGN, "&bSearch",
                filter.isBlank() ? "&7Click to filter by name" : "&7Filter: &f" + filter),
                e -> plugin.chatPrompts().prompt(player, "Type a name filter (or 'all'):", input -> {
                    filter = input.equalsIgnoreCase("all") ? "" : input;
                    page = 0;
                    open(player);
                }));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        if ((page + 1) * PAGE_SIZE < list.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }
}
