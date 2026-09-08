package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.MiniDetailMenu;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mini Museum — <b>browse-only</b>: every collectible with its rarity styling,
 * live minted/cap, circulation and value appraisal. Click one for its detail card
 * (who owns it, how to get one). Nothing is minted or bought here; Cards → Printer,
 * wild drops, natural spawns and crates are the only mint paths.
 */
public final class MuseumMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;
    private int page;

    public MuseumMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of(plugin.config().menuTitles().museum()));
    }

    /** Open the Museum on the page holding {@code focusId} and pop that Mini's detail card. */
    public static void openFor(HomeCraftManagement plugin, Player player, String focusId) {
        MuseumMenu menu = new MuseumMenu(plugin, player, null);
        List<MiniDef> list = new ArrayList<>(plugin.miniService().catalog());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(focusId)) {
                menu.page = i / PAGE_SIZE;
                MiniDef def = list.get(i);
                new MiniDetailMenu(plugin, player, def, () -> menu.open(player)).open(player);
                return;
            }
        }
        menu.open(player);
    }

    @Override
    protected void build() {
        MiniService minis = plugin.miniService();
        List<MiniDef> list = new ArrayList<>(minis.catalog());
        page = Math.max(0, Math.min(page, Math.max(0, (int) Math.ceil(list.size() / (double) PAGE_SIZE) - 1)));

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        if (list.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No Minis configured",
                    "&8Add some under 'minis:' in config.yml."), null);
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= list.size()) {
                set(i, null, null);
                continue;
            }
            MiniDef def = list.get(idx);
            set(i, minis.icon(def), e -> new MiniDetailMenu(plugin, player, def, () -> open(player)).open(player));
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(47, Menus.icon(Material.BOOK, "&dBrowse only",
                "&7Minis are printed from Cards at a Printer,",
                "&7or found in the wild, in crates, and as",
                "&7natural spawns. Click a Mini for details."), null);
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
