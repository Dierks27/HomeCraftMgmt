package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Card Binder GUI (Round 3a). Two views:
 * <ul>
 *   <li><b>Binder</b> — the cards you own (with counts); click to withdraw, or
 *       deposit every Card from your inventory at once.</li>
 *   <li><b>Album</b> — the full card set with owned cards lit and missing ones
 *       greyed, plus per-series set-completion.</li>
 * </ul>
 */
public final class BinderMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private boolean album;
    private int page;

    public BinderMenu(HomeCraftManagement plugin, Player player, boolean album) {
        super(plugin);
        this.player = player;
        this.album = album;
        init(54, Text.of(album ? "&5Card Album &8· &7Set tracker" : "&5Card Binder"));
    }

    @Override
    protected void build() {
        for (int i = PAGE_SIZE; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        Map<String, Integer> owned = plugin.binder().contents(player);

        if (album) {
            buildAlbum(owned);
        } else {
            buildOwned(owned);
        }

        // Nav row.
        set(48, Menus.icon(Material.HOPPER, "&aDeposit all Cards",
                "&7Sweep every Card in your inventory", "&7into the binder."), e -> {
            int moved = plugin.binder().depositAll(player);
            player.sendMessage(Text.of(moved > 0
                    ? "&aDeposited &f" + moved + "&a card(s) into your binder."
                    : "&7No loose Cards in your inventory to deposit."));
            refresh();
        });
        set(49, Menus.icon(album ? Material.CHEST : Material.KNOWLEDGE_BOOK,
                album ? "&eView: Album" : "&eView: Binder",
                "&7Click to switch to " + (album ? "your owned cards." : "the full set album.")), e -> {
            album = !album;
            page = 0;
            refresh();
        });
        int uniqueOwned = owned.size();
        int total = plugin.miniService().catalog().size();
        set(50, Menus.icon(Material.NETHER_STAR, "&6Collection",
                "&7Unique cards: &f" + uniqueOwned + " / " + total,
                "&7Total cards held: &f" + owned.values().stream().mapToInt(Integer::intValue).sum()), null);
        set(51, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private void buildOwned(Map<String, Integer> owned) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(owned.entrySet());
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = from + i;
            if (idx >= entries.size()) {
                set(i, null, null);
                continue;
            }
            Map.Entry<String, Integer> en = entries.get(idx);
            String id = en.getKey();
            int count = en.getValue();
            ItemStack icon = cardIcon(id, count);
            set(i, icon, e -> {
                int n = e.isShiftClick() ? count : 1;
                int taken = plugin.binder().withdraw(player, id, n);
                if (taken > 0) {
                    player.sendMessage(Text.of("&aWithdrew &f" + taken + "&a card(s)."));
                }
                refresh();
            });
        }
        pager(entries.size());
    }

    private void buildAlbum(Map<String, Integer> owned) {
        List<MiniDef> all = new ArrayList<>(plugin.miniService().catalog());
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = from + i;
            if (idx >= all.size()) {
                set(i, null, null);
                continue;
            }
            MiniDef def = all.get(idx);
            int count = owned.getOrDefault(def.id(), 0);
            if (count > 0) {
                set(i, cardIcon(def.id(), count), null);
            } else {
                set(i, Menus.icon(Material.GRAY_STAINED_GLASS_PANE, "&8??? &7(" + def.series() + ")",
                        "&8Missing — not yet collected."), null);
            }
        }
        pager(all.size());
    }

    private void pager(int size) {
        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        if ((page + 1) * PAGE_SIZE < size) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private ItemStack cardIcon(String id, int count) {
        ItemStack icon = plugin.miniService().cardFor(id);
        if (icon == null) {
            icon = Menus.icon(Material.PAPER, "&f" + id);
        }
        icon.setAmount(Math.max(1, Math.min(64, count)));
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.of("&8—"));
            lore.add(Text.of("&7In binder: &f" + count));
            if (!album) {
                lore.add(Text.of("&7Left-click &8withdraw 1  &7Shift &8all"));
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }
}
