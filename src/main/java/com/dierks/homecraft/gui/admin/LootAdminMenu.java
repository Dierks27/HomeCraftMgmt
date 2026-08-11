package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Drops / Loot admin section: manage the loot lists (weighted bags of Minis)
 * and the sources (trigger + match → list @ chance) that mint Wild Drops. Loot
 * lists are separate from the Mini catalog — a Mini is referenced by id with a
 * weight, so editing the Mini once updates it everywhere.
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
                "&7Weighted bags of Minis.", "&7Left-click a list: add an entry",
                "&7Right-click a list: remove it"), null);
        int slot = 9;
        for (Loot.LootList list : loot.lists()) {
            if (slot >= 18) {
                break;
            }
            set(slot++, Menus.icon(Material.CHEST, "&f" + list.id(),
                    "&7" + list.entries().size() + " ent(ies)",
                    "&8" + summarize(list),
                    "&7Left: add entry  Right: remove"), e -> {
                if (e.getClick().isRightClick()) {
                    removeList(loot, list.id());
                } else {
                    addEntry(loot, list.id());
                }
            });
        }

        set(27, Menus.icon(Material.TRIPWIRE_HOOK, "&eSources",
                "&7trigger + match → list @ chance%.", "&7Right-click a source: remove it"), null);
        slot = 36;
        for (int i = 0; i < loot.sources().size() && slot < 45; i++) {
            Loot.LootSource s = loot.sources().get(i);
            int index = i;
            set(slot++, Menus.icon(Material.DISPENSER,
                    "&f" + s.trigger() + " " + s.match(),
                    "&7→ list &f" + s.listId(),
                    "&7chance &f" + s.chancePercent() + "%",
                    "&7Right-click to remove"), e -> {
                if (e.getClick().isRightClick()) {
                    removeSource(loot, index);
                }
            });
        }

        set(45, Menus.icon(Material.LIME_DYE, "&a+ Add loot list"),
                e -> plugin.chatPrompts().prompt(player, "New loot list id (e.g. common_blocks):", input -> {
                    addList(plugin.miniService().loot(), input.trim());
                }));
        set(46, Menus.icon(Material.LIME_DYE, "&a+ Add source",
                "&7Type: &ftrigger match list chance_percent",
                "&8e.g. BLOCK_BREAK STONE common_blocks 0.0001"),
                e -> plugin.chatPrompts().prompt(player,
                        "Add source — 'trigger match list chance_percent':", this::parseAddSource));
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
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.miniId()).append("x").append((int) e.weight());
            if (sb.length() > 40) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    private void addList(Loot.MiniLoot loot, String id) {
        if (id.isEmpty()) {
            reopen();
            return;
        }
        List<Loot.LootList> lists = new ArrayList<>(loot.lists());
        if (lists.stream().noneMatch(l -> l.id().equalsIgnoreCase(id))) {
            lists.add(new Loot.LootList(id, new ArrayList<>()));
            plugin.miniService().saveLoot(new Loot.MiniLoot(lists, loot.sources()));
        }
        reopen();
    }

    private void removeList(Loot.MiniLoot loot, String id) {
        List<Loot.LootList> lists = new ArrayList<>(loot.lists());
        lists.removeIf(l -> l.id().equalsIgnoreCase(id));
        plugin.miniService().saveLoot(new Loot.MiniLoot(lists, loot.sources()));
        reopen();
    }

    private void addEntry(Loot.MiniLoot loot, String listId) {
        plugin.chatPrompts().prompt(player, "Add entry to '" + listId + "' — 'miniId weight':", input -> {
            String[] parts = input.trim().split("\\s+");
            if (parts.length < 1 || parts[0].isEmpty()) {
                reopen();
                return;
            }
            double weight = 1;
            if (parts.length >= 2) {
                try {
                    weight = Double.parseDouble(parts[1]);
                } catch (NumberFormatException ignored) {
                    // keep default weight
                }
            }
            List<Loot.LootList> lists = new ArrayList<>();
            for (Loot.LootList l : loot.lists()) {
                if (l.id().equalsIgnoreCase(listId)) {
                    List<Loot.LootEntry> entries = new ArrayList<>(l.entries());
                    entries.add(new Loot.LootEntry(parts[0], Math.max(0.0001, weight)));
                    lists.add(new Loot.LootList(l.id(), entries));
                } else {
                    lists.add(l);
                }
            }
            plugin.miniService().saveLoot(new Loot.MiniLoot(lists, loot.sources()));
            reopen();
        });
    }

    private void parseAddSource(String input) {
        String[] p = input.trim().split("\\s+");
        if (p.length < 4) {
            player.sendMessage(Text.of("&cNeed: trigger match list chance_percent"));
            reopen();
            return;
        }
        double chance;
        try {
            chance = Double.parseDouble(p[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(Text.of("&cchance_percent must be a number."));
            reopen();
            return;
        }
        Loot.MiniLoot loot = plugin.miniService().loot();
        List<Loot.LootSource> sources = new ArrayList<>(loot.sources());
        sources.add(new Loot.LootSource(Loot.Trigger.parse(p[0]), p[1], p[2], Math.max(0, chance)));
        plugin.miniService().saveLoot(new Loot.MiniLoot(loot.lists(), sources));
        reopen();
    }

    private void removeSource(Loot.MiniLoot loot, int index) {
        List<Loot.LootSource> sources = new ArrayList<>(loot.sources());
        if (index >= 0 && index < sources.size()) {
            sources.remove(index);
            plugin.miniService().saveLoot(new Loot.MiniLoot(loot.lists(), sources));
        }
        reopen();
    }

    private void reopen() {
        new LootAdminMenu(plugin, player, onBack).open(player);
    }
}
