package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.integration.HeadEntry;
import com.dierks.homecraft.integration.HeadLibraryService;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The searchable visual head library: browse a minecraft-heads.com category as a
 * grid of the actual rendered heads, filter by keyword, checkmark several, then
 * import — textures pulled automatically. A single {@link WebImportMenu} instance
 * holds the browse state (category, filter, selection) so async loads and chat
 * searches update it in place.
 */
public final class WebImportMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;
    private final Set<HeadEntry> selected = new LinkedHashSet<>();

    private String slug;              // null = category picker
    private String label = "";
    private List<HeadEntry> entries = new ArrayList<>();
    private List<HeadEntry> filtered = new ArrayList<>();
    private String keyword = "";
    private boolean sortAsc = true;
    private int page;
    private boolean loading;
    private String error;

    public WebImportMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&bImport from Web"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, null, null);
        }
        for (int slotIdx = 45; slotIdx < 54; slotIdx++) {
            set(slotIdx, Menus.FILLER, null);
        }

        if (loading) {
            set(22, Menus.icon(Material.CLOCK, "&eLoading " + label + "…",
                    "&7Fetching heads from the web."), null);
            set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
            return;
        }
        if (slug == null) {
            buildCategoryPicker();
            return;
        }
        if (error != null) {
            set(20, Menus.icon(Material.BARRIER, "&cFetch failed",
                    "&7" + error, "&8Try another category, or add a Mini by hand."), null);
            set(24, Menus.icon(Material.NAME_TAG, "&aAdd Mini manually instead"),
                    e -> new MiniEditMenu(plugin, player, plugin.miniService().blankDraft(), false, onBack).open(player));
            set(46, Menus.icon(Material.CHEST, "&eCategories"), e -> showPicker());
            set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
            return;
        }
        buildGrid();
    }

    // ---- category picker --------------------------------------------------

    // Inner slots (avoiding the frame edges) used to lay out the category buttons.
    private static final int[] PICKER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private void buildCategoryPicker() {
        set(4, Menus.icon(Material.COMPASS, "&bWeb Head Library",
                "&7Pick a category to browse.",
                "&7Heads load on demand and cache."), null);
        List<HeadLibraryService.Category> cats = HeadLibraryService.CATEGORIES;
        for (int i = 0; i < cats.size() && i < PICKER_SLOTS.length; i++) {
            HeadLibraryService.Category cat = cats.get(i);
            set(PICKER_SLOTS[i], Menus.icon(Material.PLAYER_HEAD, "&e" + cat.label(),
                    plugin.heads().isCached(cat.slug()) ? "&8cached" : "&7Click to load"),
                    e -> loadCategory(cat.slug(), cat.label()));
        }
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
    }

    private void loadCategory(String catSlug, String catLabel) {
        this.slug = catSlug;
        this.label = catLabel;
        this.error = null;
        this.loading = true;
        this.page = 0;
        this.keyword = "";
        refresh();
        plugin.heads().fetchCategory(catSlug,
                list -> {
                    this.loading = false;
                    this.entries = list;
                    applyFilter();
                    refresh();
                },
                err -> {
                    this.loading = false;
                    this.error = err;
                    refresh();
                });
    }

    private void showPicker() {
        this.slug = null;
        this.error = null;
        this.entries = new ArrayList<>();
        this.filtered = new ArrayList<>();
        this.keyword = "";
        this.page = 0;
        refresh();
    }

    // ---- grid -------------------------------------------------------------

    private void buildGrid() {
        int pages = Math.max(1, (int) Math.ceil(filtered.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= filtered.size()) {
                continue;
            }
            HeadEntry entry = filtered.get(idx);
            boolean picked = selected.contains(entry);
            List<String> lore = new ArrayList<>();
            lore.add("&7Category: &f" + label);
            if (entry.tags() != null && !entry.tags().isBlank()) {
                lore.add("&8" + trim(entry.tags(), 40));
            }
            lore.add("&8—");
            lore.add(picked ? "&a✔ Selected — click to remove" : "&eClick to select");
            ItemStack icon = Heads.textured(entry.texture(), "&f" + entry.name(), lore);
            if (picked) {
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.setEnchantmentGlintOverride(true);
                    icon.setItemMeta(meta);
                }
            }
            set(i, icon, e -> {
                if (!selected.remove(entry)) {
                    selected.add(entry);
                }
                refresh();
            });
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(46, Menus.icon(Material.CHEST, "&eCategories", "&7Back to category list"), e -> showPicker());
        set(47, Menus.icon(Material.OAK_SIGN, "&bSearch / filter",
                keyword.isBlank() ? "&7Filter this category by keyword" : "&7Filter: &f" + keyword,
                "&8Click to type a keyword"),
                e -> plugin.chatPrompts().prompt(player, "Type a keyword to filter (or 'all' to clear):", input -> {
                    keyword = input.equalsIgnoreCase("all") ? "" : input;
                    page = 0;
                    applyFilter();
                    open(player);
                }));
        set(48, Menus.icon(Material.HOPPER, "&bSort: " + (sortAsc ? "A–Z" : "Z–A")), e -> {
            sortAsc = !sortAsc;
            applyFilter();
            refresh();
        });
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
        set(50, Menus.icon(Material.BOOK, "&7" + filtered.size() + " shown &8/ " + entries.size() + " in " + label,
                "&7Page &f" + (page + 1) + "&7/&f" + pages), null);
        set(51, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Import Selected (" + selected.size() + ")",
                "&7Assign metadata, then import",
                "&7with textures pulled automatically."),
                e -> importSelected());
        set(52, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&cClear selection"), e -> {
            selected.clear();
            refresh();
        });
        if ((page + 1) * PAGE_SIZE < filtered.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private void applyFilter() {
        List<HeadEntry> list = new ArrayList<>();
        for (HeadEntry e : entries) {
            if (e.matches(keyword)) {
                list.add(e);
            }
        }
        list.sort((a, b) -> sortAsc
                ? a.name().compareToIgnoreCase(b.name())
                : b.name().compareToIgnoreCase(a.name()));
        this.filtered = list;
    }

    private void importSelected() {
        if (selected.isEmpty()) {
            player.sendMessage(Text.of("&eSelect at least one head first."));
            return;
        }
        new ImportMetadataMenu(plugin, player, new ArrayList<>(selected), () -> open(player)).open(player);
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
