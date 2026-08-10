package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mini Museum &amp; Shop: browse every collectible with its rarity styling,
 * live minted/cap and circulation, and click to mint one (buy at its price via
 * Vault). Minted-out Minis show as trade-only.
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
        init(54, Text.of("&5Mini Museum &8&l·&r &7Collectibles"));
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
            set(i, minis.icon(def), e -> mint(def));
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
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

    private void mint(MiniDef def) {
        if (!player.hasPermission("hcm.mini.buy")) {
            player.sendMessage(Text.of("&cYou can't buy Minis."));
            return;
        }
        MiniDao.Counts c = plugin.miniService().counts(def.id());
        if (!def.uncapped() && c.minted() >= def.cap()) {
            player.sendMessage(Text.of("&c" + def.name() + " is minted out — trade only."));
            return;
        }
        MiniService.MintResult r = plugin.miniService().mint(player, def.id());
        player.sendMessage(r.ok()
                ? Text.of("&aMinted &f" + def.name() + " &7#" + r.mintNumber() + "&a!")
                : Text.of("&c" + r.error()));
        refresh();
    }
}
