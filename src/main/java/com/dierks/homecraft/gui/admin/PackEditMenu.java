package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.mini.PackRevealMenu;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.PackService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.dierks.homecraft.mini.Rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Edit one Card Pack by clicking: rename it, step its dollar and token prices and its
 * Cards-per-open, set a tag, tune its rarity odds, or build a hand-picked pool. Everything
 * persists to config live.
 *
 * <p>A pack with an empty pool rolls by rarity odds (every Mini of the rolled rarity, or only
 * those with the tag); adding a Card to the pool switches it to the hand-picked list, and
 * "Use rarity odds" clears the pool to switch back.
 */
public final class PackEditMenu extends Menu {

    /** Pool cards fill slots 18..44 (27 max shown). */
    private static final int POOL_START = 18;
    private static final int POOL_END = 45;
    /** One odds tile per rarity, COMMON first. */
    private static final int ODDS_START = 10;

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

    /** A pack deleted in the meantime: go back rather than open an empty screen. */
    @Override
    public void open(Player viewer) {
        if (plugin.packs().pack(packId) == null) {
            back();
            return;
        }
        super.open(viewer);
    }

    @Override
    protected void build() {
        Pack.PackDef pack = plugin.packs().pack(packId);
        if (pack == null) {
            return;
        }
        for (int i = 0; i < 18; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = POOL_END; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }

        set(0, Menus.icon(Material.PAPER, "&b" + pack.displayName(),
                "&7id: &f" + pack.id(),
                "&7Price: &6" + priceText(pack),
                "&7Cards per open: &f" + pack.cardCount(),
                pack.usesPool() ? "&7Uses a hand-picked pool: &f" + pack.pool().size() + " Card(s)"
                        : "&7Uses rarity odds" + (pack.hasTag() ? " &7(tag &f" + pack.tag() + "&7)" : ""),
                plugin.packs().soldOut(pack) ? "&cSold out right now" : "&aCan be opened"), null);

        set(1, Menus.icon(Material.NAME_TAG, "&eRename", "&7Click to type a new display name"),
                e -> plugin.chatPrompts().prompt(player, "New display name for this pack:", this::rename));

        set(2, Menus.icon(Material.GOLD_INGOT, "&7Dollars: &6" + plugin.economy().format(pack.price()),
                "&7Left &8+10  &7Right &8-10",
                "&7Shift-left &8+100  &7Shift-right &8-100",
                "&80 = not sold for dollars"), e -> {
            double step = e.isShiftClick() ? 100 : 10;
            update(pack.withPrice(Math.max(0, pack.price() + (e.getClick().isRightClick() ? -step : step))));
        });

        set(3, Menus.icon(Material.SUNFLOWER, "&7Tokens: &6" + pack.priceTokens(),
                "&7Left &8+5  &7Right &8-5",
                "&7Shift-left &8+50  &7Shift-right &8-50",
                "&80 = not sold for tokens"), e -> {
            int step = e.isShiftClick() ? 50 : 5;
            update(pack.withPriceTokens(Math.max(0, pack.priceTokens() + (e.getClick().isRightClick() ? -step : step))));
        });

        set(4, Menus.icon(Material.PAPER, "&bCards per open: &f" + pack.cardCount(),
                "&7Left &8+1  &7Right &8-1"), e ->
                update(pack.withCardCount(Math.max(1, pack.cardCount() + (e.getClick().isRightClick() ? -1 : 1)))));

        set(5, Menus.icon(Material.OAK_SIGN, "&7Tag: &f" + (pack.hasTag() ? pack.tag() : "(every Mini)"),
                "&7Only Minis with this tag come out.",
                "&7Click to type one; type &fnone &7to clear."),
                e -> plugin.chatPrompts().prompt(player, "Tag for this pack (or 'none' for every Mini):", input -> {
                    String t = input == null ? "" : input.trim();
                    Pack.PackDef cur = plugin.packs().pack(packId);
                    if (cur != null) {
                        plugin.packs().upsert(cur.withTag(t.equalsIgnoreCase("none") ? "" : t));
                    }
                    reopen();
                }));

        if (pack.usesPool()) {
            set(7, Menus.icon(Material.COMPARATOR, "&eUse rarity odds",
                    "&7Clears the hand-picked pool.",
                    "&7The pack then rolls by the odds below."), e -> update(pack.withPool(List.of())));
        }

        set(8, Menus.icon(Material.ENDER_EYE, "&dTest Open",
                "&7Open this pack now (free).",
                "&8For testing without buying one."), e -> {
            PackService.OpenResult r = plugin.packs().open(player, packId, null, 0);
            if (!r.ok()) {
                player.sendMessage(Text.of("&c" + r.error()));
                return;
            }
            new PackRevealMenu(plugin, player, pack.displayName(), r.cardIds(), this::reopen).open(player);
        });

        set(9, Menus.icon(Material.BOOK, "&fRarity odds",
                pack.usesPool() ? "&8Not used while the pool has Cards." : "&7Relative weights per rarity."), null);
        Map<Rarity, Double> live = plugin.packs().liveOdds(pack);
        double total = 0;
        for (Rarity r : Rarity.values()) {
            total += pack.odds(r);
        }
        for (Rarity r : Rarity.values()) {
            double w = pack.odds(r);
            double pct = total > 0 ? w / total * 100 : 0;
            List<String> lore = new ArrayList<>();
            lore.add("&7Weight: &f" + (long) w + " &8(" + String.format(Locale.ROOT, "%.1f", pct) + "%)");
            if (!pack.usesPool() && w > 0 && !live.containsKey(r)) {
                lore.add("&cNone left to give — skipped");
            }
            lore.add("&7Left &8+1  &7Right &8-1");
            lore.add("&7Shift &8±10");
            Material pane = plugin.miniService().style(r).pane();
            set(ODDS_START + r.ordinal(), Menus.icon(pane, plugin.miniService().rarityText(r) + " &7" + (long) w,
                    lore.toArray(new String[0])), e -> {
                double step = e.isShiftClick() ? 10 : 1;
                update(pack.withOdds(r, Math.max(0, w + (e.getClick().isRightClick() ? -step : step))));
            });
        }

        double poolTotal = pack.totalWeight();
        List<Pack.PackEntry> pool = pack.pool();
        for (int i = 0; i < pool.size() && POOL_START + i < POOL_END; i++) {
            Pack.PackEntry entry = pool.get(i);
            set(POOL_START + i, entryIcon(entry, poolTotal), e -> {
                if (e.isShiftClick()) {
                    changeEntry(entry.miniId(), 0, true);
                } else if (e.getClick().isRightClick()) {
                    changeEntry(entry.miniId(), entry.weight() - 1, false);
                } else {
                    changeEntry(entry.miniId(), entry.weight() + 1, false);
                }
            });
        }

        set(48, Menus.icon(Material.LIME_DYE, "&a+ Add Card",
                "&7Pick a Card for a hand-picked pool.",
                "&8A pool replaces the rarity odds."),
                e -> new MiniPickerMenu(plugin, player, miniId -> {
                    changeEntry(miniId, 1, false);
                    reopen();
                }, this::reopen).open(player));
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
    }

    private String priceText(Pack.PackDef p) {
        List<String> parts = new ArrayList<>();
        if (p.price() > 0) {
            parts.add(plugin.economy().format(p.price()));
        }
        if (p.priceTokens() > 0) {
            parts.add(p.priceTokens() + " tokens");
        }
        return parts.isEmpty() ? "not for sale" : String.join(" or ", parts);
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
                    + " &8(" + String.format(Locale.ROOT, "%.1f", pct) + "%)"));
            lore.add(Text.of("&7Left-click &8+1  &7Right-click &8-1"));
            lore.add(Text.of("&7Shift-click &8remove"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void update(Pack.PackDef next) {
        plugin.packs().upsert(next);
        refresh();
    }

    private void rename(String name) {
        String trimmed = name == null ? "" : name.trim();
        Pack.PackDef pack = plugin.packs().pack(packId);
        if (pack != null && !trimmed.isEmpty()) {
            plugin.packs().upsert(pack.withDisplayName(trimmed));
        }
        reopen();
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
        update(pack.withPool(pool));
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
