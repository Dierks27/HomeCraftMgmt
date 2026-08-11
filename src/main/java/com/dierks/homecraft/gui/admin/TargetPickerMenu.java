package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pick a block or a mob by clicking its icon (never by typing a name). Optional
 * chat filter narrows a large list. Returns the chosen Material/EntityType name.
 */
public final class TargetPickerMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final boolean blocks; // true = block materials, false = living entities
    private final Consumer<String> onPick;
    private final Runnable onBack;
    private String filter = "";
    private int page;

    public TargetPickerMenu(HomeCraftManagement plugin, Player player, boolean blocks,
                            Consumer<String> onPick, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.blocks = blocks;
        this.onPick = onPick;
        this.onBack = onBack;
        init(54, Text.of(blocks ? "&aPick a block" : "&aPick a mob"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        List<String> names = candidates();
        int pages = Math.max(1, (int) Math.ceil(names.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= names.size()) {
                set(i, null, null);
                continue;
            }
            String name = names.get(idx);
            set(i, iconFor(name), e -> onPick.accept(name));
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(47, Menus.icon(Material.OAK_SIGN, "&bSearch",
                filter.isBlank() ? "&7Click to filter" : "&7Filter: &f" + filter),
                e -> plugin.chatPrompts().prompt(player, "Type a filter (or 'all'):", input -> {
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
        if ((page + 1) * PAGE_SIZE < names.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private List<String> candidates() {
        List<String> out = new ArrayList<>();
        String f = filter.toLowerCase();
        if (blocks) {
            for (Material m : Material.values()) {
                if (m.isBlock() && !m.isAir() && (f.isBlank() || m.name().toLowerCase().contains(f))) {
                    out.add(m.name());
                }
            }
        } else {
            for (EntityType t : EntityType.values()) {
                if (t == EntityType.UNKNOWN || t == EntityType.PLAYER) {
                    continue;
                }
                Class<?> clazz = t.getEntityClass();
                boolean living = clazz != null && org.bukkit.entity.LivingEntity.class.isAssignableFrom(clazz);
                if (living && (f.isBlank() || t.name().toLowerCase().contains(f))) {
                    out.add(t.name());
                }
            }
        }
        return out;
    }

    private ItemStack iconFor(String name) {
        Material icon;
        if (blocks) {
            Material m = Material.matchMaterial(name);
            icon = m != null ? m : Material.STONE;
        } else {
            Material egg = Material.matchMaterial(name + "_SPAWN_EGG");
            icon = egg != null ? egg : Material.EGG;
        }
        return Menus.icon(icon, "&f" + name, "&7Click to choose");
    }
}
