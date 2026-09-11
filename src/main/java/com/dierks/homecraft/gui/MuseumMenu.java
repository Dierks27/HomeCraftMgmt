package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.mini.MiniDetailMenu;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Mini Museum — <b>browse-only</b>: every collectible with its rarity styling, live
 * minted/cap, circulation and value appraisal. Click one for its detail card. Nothing is
 * minted or bought here; Cards → Printer, wild drops, natural spawns and crates are the
 * only mint paths.
 *
 * <p>Grouped rather than flat: each group gets a rarity-coloured header showing how much
 * of it the viewing player has collected, and the Minis follow. Headers and Minis share
 * one paged list, so a group runs across a page boundary rather than leaving gaps. The
 * grouping, rarity filter and owned-only toggle live in {@link BrowseState}.
 */
public final class MuseumMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final Runnable onBack;

    public MuseumMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of(plugin.config().menuTitles().museum()));
    }

    /**
     * Open the Museum on the page holding {@code focusId} and pop that Mini's detail card.
     *
     * <p>Resets the view to the configured default and clears the filters first: this is
     * reached from a "found a Mini" broadcast, and a rarity filter or owned-only toggle
     * left over from earlier browsing could otherwise hide the very Mini being shown.
     */
    public static void openFor(HomeCraftManagement plugin, Player player, String focusId) {
        BrowseState.Museum state = plugin.browseState().museum(player);
        state.view = BrowseState.View.of(plugin.config().menuDefaults().museumView());
        state.rarity = null;
        state.ownedOnly = false;
        state.page = 0;

        MuseumMenu menu = new MuseumMenu(plugin, player, null);
        List<Row> rows = menu.rows(state);
        for (int i = 0; i < rows.size(); i++) {
            MiniDef def = rows.get(i).def();
            if (def != null && def.id().equals(focusId)) {
                state.page = i / PAGE_SIZE;
                new MiniDetailMenu(plugin, player, def, () -> menu.open(player)).open(player);
                return;
            }
        }
        menu.open(player);
    }

    /** One slot in the paged list: a group header, or a Mini. */
    private record Row(String header, Rarity headerRarity, int owned, int total, MiniDef def) {
        static Row header(String title, Rarity rarity, int owned, int total) {
            return new Row(title, rarity, owned, total, null);
        }

        static Row mini(MiniDef def) {
            return new Row(null, null, 0, 0, def);
        }
    }

    /** The filtered, grouped, header-interleaved list the grid pages over. */
    private List<Row> rows(BrowseState.Museum state) {
        MiniService minis = plugin.miniService();
        Set<String> owned = minis.ownedIds(player.getUniqueId());

        List<MiniDef> kept = new ArrayList<>();
        for (MiniDef def : minis.catalogList()) {
            if (state.rarity != null && def.rarity() != state.rarity) {
                continue;
            }
            if (state.ownedOnly && !owned.contains(def.id())) {
                continue;
            }
            kept.add(def);
        }

        // LinkedHashMap keeps catalog order for series; the rarity view re-orders below.
        Map<String, List<MiniDef>> groups = new LinkedHashMap<>();
        for (MiniDef def : kept) {
            groups.computeIfAbsent(groupKey(state, def), k -> new ArrayList<>()).add(def);
        }

        List<String> order = new ArrayList<>(groups.keySet());
        if (state.view == BrowseState.View.RARITY) {
            // Legendary first — the reason to open a rarity view at all.
            order.sort((a, b) -> rarityRank(b) - rarityRank(a));
        }

        List<Row> out = new ArrayList<>();
        for (String key : order) {
            List<MiniDef> group = groups.get(key);
            int have = 0;
            for (MiniDef def : group) {
                if (owned.contains(def.id())) {
                    have++;
                }
            }
            out.add(Row.header(key, headerRarity(group), have, group.size()));
            for (MiniDef def : group) {
                out.add(Row.mini(def));
            }
        }
        return out;
    }

    private String groupKey(BrowseState.Museum state, MiniDef def) {
        return switch (state.view) {
            case SERIES -> def.series() == null || def.series().isBlank() ? "Unsorted" : def.series();
            case RARITY -> BrowseState.pretty(def.rarity().name());
            case TYPE -> def.type() == MiniType.ARMOR_STAND ? "Armor Stands" : "Heads";
        };
    }

    /** Rank a rarity-view group name back to its ordinal; -1 for anything else. */
    private static int rarityRank(String groupName) {
        for (Rarity r : Rarity.values()) {
            if (BrowseState.pretty(r.name()).equals(groupName)) {
                return r.ordinal();
            }
        }
        return -1;
    }

    /** A group wears the highest rarity it contains — the reason to chase it. */
    private static Rarity headerRarity(List<MiniDef> group) {
        Rarity best = Rarity.COMMON;
        for (MiniDef def : group) {
            if (def.rarity().ordinal() > best.ordinal()) {
                best = def.rarity();
            }
        }
        return best;
    }

    @Override
    protected void build() {
        MiniService minis = plugin.miniService();
        BrowseState.Museum state = plugin.browseState().museum(player);
        List<Row> rows = rows(state);

        int pages = Math.max(1, (int) Math.ceil(rows.size() / (double) PAGE_SIZE));
        state.page = Math.max(0, Math.min(state.page, pages - 1));

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }

        if (rows.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7Nothing to show",
                    state.ownedOnly || state.rarity != null
                            ? "&7No Mini matches the current filters."
                            : "&7Add some under 'minis:' in config.yml."), null);
        }

        int start = state.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= rows.size()) {
                set(i, null, null);
                continue;
            }
            Row row = rows.get(idx);
            if (row.def() == null) {
                set(i, headerIcon(row), null);
            } else {
                MiniDef def = row.def();
                set(i, minis.icon(def), e ->
                        new MiniDetailMenu(plugin, player, def, () -> open(player)).open(player));
            }
        }

        if (state.page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                state.page--;
                refresh();
            });
        }
        set(46, Menus.icon(Material.BOOKSHELF, "&bView: &f" + state.view.label(),
                "&7Click to group by Series, Rarity or Type."), e -> {
            state.view = state.view.next();
            state.page = 0;
            refresh();
        });
        set(47, Menus.icon(Material.AMETHYST_SHARD, "&dRarity: &f" + state.rarityLabel(),
                "&7Click to cycle All → Common → … → Legendary."), e -> {
            state.cycleRarity();
            state.page = 0;
            refresh();
        });
        // RED_DYE, not GRAY_DYE, for the off state: slot 48 sits in a row filled with the grey
        // pane filler, and a grey dye on it is the dimmest thing on screen — for the default
        // state of a filter most players will never know is there.
        set(48, Menus.icon(state.ownedOnly ? Material.LIME_DYE : Material.RED_DYE,
                (state.ownedOnly ? "&aOwned only: ON" : "&7Owned only: OFF"),
                "&7Show only Minis you hold a copy of."), e -> {
            state.ownedOnly = !state.ownedOnly;
            state.page = 0;
            refresh();
        });
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        set(50, Menus.icon(Material.BOOK, "&dBrowse only",
                "&7Minis are printed from Cards at a Printer,",
                "&7or found in the wild, in crates, and as",
                "&7natural spawns. Click a Mini for details."), null);
        if ((state.page + 1) * PAGE_SIZE < rows.size()) {
            set(53, Menus.icon(Material.ARROW, "&eNext »",
                    "&8Page " + (state.page + 1) + " of " + pages), e -> {
                state.page++;
                refresh();
            });
        }
    }

    /**
     * A group header: the rarity's pane, the group name, and the player's progress.
     *
     * <p>The rarity is named in the lore as well as worn as a colour. The pane alone is the kind
     * of single-channel signal that fails quietly — it fails for anyone who cannot separate those
     * hues, and it failed outright while Common shipped as a light-grey pane the same colour as
     * the slot behind it.
     */
    private ItemStack headerIcon(Row row) {
        Material pane = plugin.miniService().style(row.headerRarity()).pane();
        boolean complete = row.total() > 0 && row.owned() >= row.total();
        return Menus.icon(pane, "&f&l" + row.header(),
                "&8" + BrowseState.pretty(row.headerRarity().name()),
                (complete ? "&a" : "&7") + row.owned() + " of " + row.total() + " collected",
                complete ? "&a✓ Complete" : "&8Keep collecting");
    }
}
