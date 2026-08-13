package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniIds;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Card Packs admin section (Phase 10, Part E) — fully click-driven. Existing pack
 * types list as icons (left-click to edit, right-click to remove); "+ New pack" types
 * a name and drops you into the pack editor. Everything persists to config live — no
 * YAML by hand.
 */
public final class PacksAdminMenu extends Menu {

    private final Player player;
    private final Runnable onBack;

    public PacksAdminMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&5Card Packs"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        set(45, Menus.icon(Material.BOOK, "&eCard Packs",
                "&7Buyable booster packs of Cards.",
                "&eLeft-click&7 a pack to edit it.",
                "&cRight-click&7 a pack to remove it."), null);

        List<Pack.PackDef> packs = plugin.packs().packs();
        int slot = 0;
        for (Pack.PackDef p : packs) {
            if (slot >= 45) {
                break;
            }
            set(slot++, packIcon(p), e -> {
                if (e.getClick().isRightClick()) {
                    plugin.packs().delete(p.id());
                    refresh();
                } else {
                    new PackEditMenu(plugin, player, p.id(), this::reopen).open(player);
                }
            });
        }

        set(48, Menus.icon(Material.LIME_DYE, "&a+ New pack", "&7Type a name for the new pack"),
                e -> plugin.chatPrompts().prompt(player, "New pack name (e.g. Starter Pack):", input -> {
                    createPack(input);
                }));
        set(53, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private org.bukkit.inventory.ItemStack packIcon(Pack.PackDef p) {
        List<String> lore = new ArrayList<>();
        lore.add("&7id: &f" + p.id());
        lore.add("&7Price: &6" + plugin.economy().format(p.price()));
        lore.add("&7Cards per open: &f" + p.cardCount());
        lore.add("&7Pool: &f" + p.pool().size() + " card(s)");
        if (!p.isValid()) {
            lore.add("&cNeeds cards before it can be sold.");
        }
        lore.add("&eLeft: edit  &cRight: remove");
        return Menus.icon(Material.PAPER, "&b" + p.displayName(), lore.toArray(new String[0]));
    }

    private void createPack(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            reopen();
            return;
        }
        String id = MiniIds.slug(trimmed);
        if (plugin.packs().pack(id) == null) {
            plugin.packs().upsert(new Pack.PackDef(id, trimmed, 100, 3, new ArrayList<>()));
        }
        new PackEditMenu(plugin, player, id, this::reopen).open(player);
    }

    private void reopen() {
        new PacksAdminMenu(plugin, player, onBack).open(player);
    }
}
