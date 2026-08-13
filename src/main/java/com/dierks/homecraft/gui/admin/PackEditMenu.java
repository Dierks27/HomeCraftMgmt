package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit one Card Pack by clicking (Phase 10, Part E): rename it, step its price and
 * cards-per-open, and build its weighted card pool — click "+ Add Card" to pick a
 * Card, then left/right-click each pooled card to raise/lower its odds (shift-click
 * removes). Everything persists to config live.
 */
public final class PackEditMenu extends Menu {

    /** Pool cards fill slots 9..44 (36 max shown). */
    private static final int POOL_START = 9;
    private static final int POOL_END = 45;

    private final Player player;
    private final String packId;
    private final Runnable onBack;

    public PackEditMenu(HomeCraftManagement plugin, Player player, String packId, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.packId = packId;
        this.onBack = onBack;
        init(54, Text.of("&5Pack: &f" + packId));
    }

    @Override
    protected void build() {
        Pack.PackDef pack = plugin.packs().pack(packId);
        if (pack == null) {
            back();
            return;
        }
        for (int i = 0; i < 9; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = POOL_END; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }

        set(0, Menus.icon(Material.PAPER, "&b" + pack.displayName(),
                "&7id: &f" + pack.id(),
                "&7Price: &6" + plugin.economy().format(pack.price()),
                "&7Cards per open: &f" + pack.cardCount(),
                "&7Pool: &f" + pack.pool().size() + " card(s)"), null);

        set(2, Menus.icon(Material.NAME_TAG, "&eRename", "&7Click to type a new display name"),
                e -> plugin.chatPrompts().prompt(player, "New display name for this pack:", input -> {
                    rename(input);
                }));

        set(4, Menus.icon(Material.GOLD_INGOT, "&6Price: &f" + plugin.economy().format(pack.price()),
                "&7Left &8+10  &7Right &8-10",
                "&7Shift-left &8+100  &7Shift-right &8-100"), e -> {
            double step = e.isShiftClick() ? 100 : 10;
            changePrice(e.getClick().isRightClick() ? -step : step);
        });

        set(6, Menus.icon(Material.PAPER, "&bCards per open: &f" + pack.cardCount(),
                "&7Left &8+1  &7Right &8-1"), e ->
                changeCount(e.getClick().isRightClick() ? -1 : 1));

        double total = pack.totalWeight();
        List<Pack.PackEntry> pool = pack.pool();
        for (int i = 0; i < pool.size() && POOL_START + i < POOL_END; i++) {
            Pack.PackEntry entry = pool.get(i);
            set(POOL_START + i, entryIcon(entry, total), e -> {
                if (e.isShiftClick()) {
                    changeEntry(entry.miniId(), 0, true);
                } else if (e.getClick().isRightClick()) {
                    changeEntry(entry.miniId(), entry.weight() - 1, false);
                } else {
                    changeEntry(entry.miniId(), entry.weight() + 1, false);
                }
            });
        }

        set(48, Menus.icon(Material.LIME_DYE, "&a+ Add Card", "&7Pick a Card to add to the pool"),
                e -> new MiniPickerMenu(plugin, player, miniId -> {
                    changeEntry(miniId, 1, false);
                    reopen();
                }, this::reopen).open(player));
        set(53, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
    }

    private ItemStack entryIcon(Pack.PackEntry entry, double total) {
        MiniDef def = plugin.miniService().def(entry.miniId());
        ItemStack icon = def != null ? plugin.miniService().cardFor(entry.miniId())
                : Menus.icon(Material.PAPER, "&f" + entry.miniId());
        if (icon == null) {
            icon = Menus.icon(Material.PAPER, "&f" + entry.miniId());
        }
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.hasLore()
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.of("&8—"));
            double pct = total > 0 ? entry.weight() / total * 100.0 : 0;
            lore.add(Text.of("&7Weight: &f" + (int) entry.weight()
                    + " &8(" + String.format(java.util.Locale.ROOT, "%.1f", pct) + "%)"));
            lore.add(Text.of("&7Left-click &8+1  &7Right-click &8-1"));
            lore.add(Text.of("&7Shift-click &8remove"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void rename(String name) {
        String trimmed = name == null ? "" : name.trim();
        Pack.PackDef pack = plugin.packs().pack(packId);
        if (pack != null && !trimmed.isEmpty()) {
            plugin.packs().upsert(new Pack.PackDef(pack.id(), trimmed, pack.price(),
                    pack.cardCount(), pack.pool()));
        }
        reopen();
    }

    private void changePrice(double delta) {
        Pack.PackDef pack = plugin.packs().pack(packId);
        if (pack != null) {
            double price = Math.max(0, pack.price() + delta);
            plugin.packs().upsert(new Pack.PackDef(pack.id(), pack.displayName(), price,
                    pack.cardCount(), pack.pool()));
        }
        refresh();
    }

    private void changeCount(int delta) {
        Pack.PackDef pack = plugin.packs().pack(packId);
        if (pack != null) {
            int count = Math.max(1, pack.cardCount() + delta);
            plugin.packs().upsert(new Pack.PackDef(pack.id(), pack.displayName(), pack.price(),
                    count, pack.pool()));
        }
        refresh();
    }

    private void changeEntry(String miniId, double weight, boolean remove) {
        Pack.PackDef pack = plugin.packs().pack(packId);
        if (pack == null) {
            return;
        }
        List<Pack.PackEntry> pool = new ArrayList<>();
        boolean found = false;
        for (Pack.PackEntry e : pack.pool()) {
            if (e.miniId().equals(miniId)) {
                found = true;
                if (!remove) {
                    pool.add(new Pack.PackEntry(miniId, Math.max(1, weight)));
                }
            } else {
                pool.add(e);
            }
        }
        if (!found && !remove) {
            pool.add(new Pack.PackEntry(miniId, Math.max(1, weight)));
        }
        plugin.packs().upsert(new Pack.PackDef(pack.id(), pack.displayName(), pack.price(),
                pack.cardCount(), pool));
        refresh();
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }

    private void reopen() {
        new PackEditMenu(plugin, player, packId, onBack).open(player);
    }
}
