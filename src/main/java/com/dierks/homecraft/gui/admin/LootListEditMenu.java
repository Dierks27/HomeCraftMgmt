package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit one loot list by clicking: add Minis from the catalog (rendered heads),
 * adjust each entry's weight with clicks (left +1, right −1), and shift-click to
 * remove. No typing.
 */
public final class LootListEditMenu extends Menu {

    private final Player player;
    private final String listId;
    private final Runnable onBack;

    public LootListEditMenu(HomeCraftManagement plugin, Player player, String listId, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.listId = listId;
        this.onBack = onBack;
        init(54, Text.of("&5Loot list: &f" + listId));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        Loot.LootList list = current();
        if (list == null) {
            back();
            return;
        }
        List<Loot.LootEntry> entries = list.entries();
        for (int i = 0; i < entries.size() && i < 45; i++) {
            Loot.LootEntry entry = entries.get(i);
            set(i, entryIcon(entry), e -> {
                if (e.isShiftClick()) {
                    changeEntry(entry.miniId(), 0, true);
                } else if (e.getClick().isRightClick()) {
                    changeEntry(entry.miniId(), entry.weight() - 1, false);
                } else {
                    changeEntry(entry.miniId(), entry.weight() + 1, false);
                }
            });
        }

        set(48, Menus.icon(Material.LIME_DYE, "&a+ Add Mini", "&7Pick a Mini to add to this list"),
                e -> new MiniPickerMenu(plugin, player, miniId -> {
                    changeEntry(miniId, 1, false); // add at weight 1 (no-op if already present)
                    reopen();
                }, this::reopen).open(player));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
    }

    private ItemStack entryIcon(Loot.LootEntry entry) {
        MiniDef def = plugin.miniService().def(entry.miniId());
        ItemStack icon = def != null ? plugin.miniService().icon(def)
                : Menus.icon(Material.PLAYER_HEAD, "&f" + entry.miniId());
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.of("&8—"));
            lore.add(Text.of("&7Weight: &f" + (int) entry.weight()));
            lore.add(Text.of("&7Left-click &8+1  &7Right-click &8-1"));
            lore.add(Text.of("&7Shift-click &8remove"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /** Add/update/remove an entry's weight, then persist. */
    private void changeEntry(String miniId, double weight, boolean remove) {
        Loot.MiniLoot loot = plugin.miniService().loot();
        List<Loot.LootList> lists = new ArrayList<>();
        for (Loot.LootList l : loot.lists()) {
            if (!l.id().equals(listId)) {
                lists.add(l);
                continue;
            }
            List<Loot.LootEntry> entries = new ArrayList<>();
            boolean found = false;
            for (Loot.LootEntry e : l.entries()) {
                if (e.miniId().equals(miniId)) {
                    found = true;
                    if (!remove) {
                        entries.add(new Loot.LootEntry(miniId, Math.max(1, weight)));
                    }
                } else {
                    entries.add(e);
                }
            }
            if (!found && !remove) {
                entries.add(new Loot.LootEntry(miniId, Math.max(1, weight)));
            }
            lists.add(new Loot.LootList(l.id(), entries));
        }
        plugin.miniService().saveLoot(new Loot.MiniLoot(lists, loot.sources()));
        refresh();
    }

    private Loot.LootList current() {
        for (Loot.LootList l : plugin.miniService().loot().lists()) {
            if (l.id().equals(listId)) {
                return l;
            }
        }
        return null;
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }

    private void reopen() {
        new LootListEditMenu(plugin, player, listId, onBack).open(player);
    }
}
