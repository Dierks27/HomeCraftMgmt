package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniDraft;
import com.dierks.homecraft.storage.MiniDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The admin catalog browser: every Mini as its rendered head with live
 * minted/cap + circulation, click to open the edit form. Paginated. Distinct
 * from the player Museum — this is the management view.
 */
public final class MiniListMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;
    private int page;

    public MiniListMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&4Manage Minis"));
    }

    @Override
    protected void build() {
        List<MiniDef> list = plugin.miniService().catalogList();
        page = Math.max(0, Math.min(page, Math.max(0, (int) Math.ceil(list.size() / (double) PAGE_SIZE) - 1)));

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        if (list.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No Minis yet",
                    "&8Add one, or import from the web."), null);
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= list.size()) {
                set(i, null, null);
                continue;
            }
            MiniDef def = list.get(idx);
            set(i, adminIcon(def), e -> new MiniEditMenu(
                    plugin, player, MiniDraft.from(def), true, onBack).open(player));
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(48, Menus.icon(Material.NAME_TAG, "&aAdd Mini (manual)"),
                e -> new MiniEditMenu(plugin, player, plugin.miniService().blankDraft(), false, onBack).open(player));
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

    // (blank drafts are built by MiniService.blankDraft())

    /** The Mini's rendered icon plus an "click to edit" hint for admins. */
    private ItemStack adminIcon(MiniDef def) {
        ItemStack icon = plugin.miniService().icon(def);
        MiniDao.Counts c = plugin.miniService().counts(def.id());
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.of("&8id: " + def.id()));
            lore.add(Text.of("&8minted " + c.minted() + " · circ " + c.circulation()));
            lore.add(Text.of("&eClick to edit"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }
}
