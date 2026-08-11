package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The Drops / Loot admin section — fully click-driven. Loot lists (weighted bags
 * of Minis) open a visual editor; drop sources are built with a click flow
 * (trigger + visual target picker + list + chance steppers). The only typing is a
 * new list's name. The config.yml representation is kept in sync underneath.
 */
public final class LootAdminMenu extends Menu {

    private final Player player;
    private final Runnable onBack;

    public LootAdminMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&5Drops / Loot"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        Loot.MiniLoot loot = plugin.miniService().loot();

        set(0, Menus.icon(Material.BOOK, "&eLoot lists",
                "&7Weighted bags of Minis.",
                "&7Left-click a list to edit it.",
                "&7Right-click a list to remove it."), null);
        int slot = 9;
        for (Loot.LootList list : loot.lists()) {
            if (slot >= 18) {
                break;
            }
            set(slot++, Menus.icon(Material.CHEST, "&f" + list.id(),
                    "&7" + list.entries().size() + " Mini(s)",
                    "&8" + summarize(list),
                    "&eLeft: edit  &cRight: remove"), e -> {
                if (e.getClick().isRightClick()) {
                    removeList(list.id());
                } else {
                    new LootListEditMenu(plugin, player, list.id(), this::reopen).open(player);
                }
            });
        }

        set(27, Menus.icon(Material.TRIPWIRE_HOOK, "&eDrop sources",
                "&7trigger + target → list @ chance.",
                "&7Right-click a source to remove it."), null);
        int sslot = 36;
        List<Loot.LootSource> sources = loot.sources();
        for (int i = 0; i < sources.size() && sslot < 45; i++) {
            Loot.LootSource s = sources.get(i);
            int index = i;
            set(sslot++, Menus.icon(Material.DISPENSER,
                    "&f" + s.trigger() + " " + s.match(),
                    "&7→ list &f" + s.listId(),
                    "&7chance &f" + plain(s.chancePercent()) + "%",
                    "&cRight-click to remove"), e -> {
                if (e.getClick().isRightClick()) {
                    removeSource(index);
                }
            });
        }

        set(45, Menus.icon(Material.LIME_DYE, "&a+ New loot list", "&7Type a name for the new list"),
                e -> plugin.chatPrompts().prompt(player, "New loot list name (e.g. common_blocks):", input -> {
                    addList(input.trim());
                }));
        set(46, Menus.icon(Material.LIME_DYE, "&a+ New drop source", "&7Build it by clicking — no typing"),
                e -> new SourceCreateMenu(plugin, player, this::reopen).open(player));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private String summarize(Loot.LootList list) {
        StringBuilder sb = new StringBuilder();
        for (Loot.LootEntry e : list.entries()) {
            if (sb.length() > 40) {
                sb.append("…");
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.miniId()).append("x").append((int) e.weight());
        }
        return sb.toString();
    }

    private void addList(String id) {
        if (!id.isEmpty()) {
            Loot.MiniLoot loot = plugin.miniService().loot();
            List<Loot.LootList> lists = new ArrayList<>(loot.lists());
            if (lists.stream().noneMatch(l -> l.id().equalsIgnoreCase(id))) {
                lists.add(new Loot.LootList(id, new ArrayList<>()));
                plugin.miniService().saveLoot(new Loot.MiniLoot(lists, loot.sources()));
            }
        }
        reopen();
    }

    private void removeList(String id) {
        Loot.MiniLoot loot = plugin.miniService().loot();
        List<Loot.LootList> lists = new ArrayList<>(loot.lists());
        lists.removeIf(l -> l.id().equalsIgnoreCase(id));
        plugin.miniService().saveLoot(new Loot.MiniLoot(lists, loot.sources()));
        refresh();
    }

    private void removeSource(int index) {
        Loot.MiniLoot loot = plugin.miniService().loot();
        List<Loot.LootSource> sources = new ArrayList<>(loot.sources());
        if (index >= 0 && index < sources.size()) {
            sources.remove(index);
            plugin.miniService().saveLoot(new Loot.MiniLoot(loot.lists(), sources));
        }
        refresh();
    }

    private String plain(double v) {
        return new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
    }

    private void reopen() {
        new LootAdminMenu(plugin, player, onBack).open(player);
    }
}
