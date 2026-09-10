package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Click-to-pick any real item Material, with a chat-prompt filter.
 *
 * <p>Only {@code isItem() && !isAir()} materials are offered: the market hands the buyer
 * {@code new ItemStack(material, qty)} after taking their money, so a non-item entry would
 * charge for nothing. Legacy materials are excluded by name — they resolve but are not real
 * in a modern server.
 */
public final class MaterialPickerMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Consumer<Material> onPick;
    private final Runnable onBack;
    private String filter;
    private int page;

    public MaterialPickerMenu(HomeCraftManagement plugin, Player player, String filter,
                              Consumer<Material> onPick, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.filter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        this.onPick = onPick;
        this.onBack = onBack;
        init(54, Text.of("&2Pick a material"));
    }

    /** Every tradable material, filtered and alphabetical. */
    private List<Material> matches() {
        List<Material> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isItem() || m.isAir() || m.name().startsWith("LEGACY_")) {
                continue;
            }
            if (filter.isEmpty() || m.name().toLowerCase(Locale.ROOT).contains(filter)) {
                out.add(m);
            }
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    @Override
    protected void build() {
        List<Material> list = matches();
        page = Math.max(0, Math.min(page, Math.max(0, (int) Math.ceil(list.size() / (double) PAGE_SIZE) - 1)));

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= list.size()) {
                set(i, null, null);
                continue;
            }
            Material m = list.get(idx);
            set(i, Menus.icon(m, "&e" + m.name(), "&7Click to use this material."), e -> {
                onPick.accept(m);
            });
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(47, Menus.icon(Material.HOPPER, "&bFilter",
                filter.isEmpty() ? "&7Showing everything." : "&7Matching: &f" + filter,
                "&7Click to type a filter."), e ->
                plugin.chatPrompts().prompt(player, "Filter materials by name (or 'all'):", input -> {
                    String v = input.trim();
                    String next = v.equalsIgnoreCase("all") ? "" : v;
                    new MaterialPickerMenu(plugin, player, next, onPick, onBack).open(player);
                }));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        set(51, Menus.icon(Material.PAPER, "&8" + list.size() + " material(s)"), null);
        if ((page + 1) * PAGE_SIZE < list.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }
}
