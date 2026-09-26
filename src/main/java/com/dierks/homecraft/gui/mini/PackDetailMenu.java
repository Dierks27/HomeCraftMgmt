package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.PackItems.Currency;
import com.dierks.homecraft.mini.PackService;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One pack, opened up: every Mini that can come out of it, each marked ✓ (you have it),
 * missing, or sold out, with Buy buttons for dollars and for tokens.
 *
 * <p>The status is in each tile's NAME, not only its lore, because Bedrock shows lore only on
 * tap-and-hold — "Piggy ✓" reads at a glance on every client.
 */
public final class PackDetailMenu extends Menu {

    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;

    private final Player player;
    private final String packId;
    private final Runnable onBack;
    private final int page;

    public PackDetailMenu(HomeCraftManagement plugin, Player player, String packId, Runnable onBack) {
        this(plugin, player, packId, onBack, 0);
    }

    public PackDetailMenu(HomeCraftManagement plugin, Player player, String packId, Runnable onBack, int page) {
        super(plugin);
        this.player = player;
        this.packId = packId;
        this.onBack = onBack;
        this.page = Math.max(0, page);
        Pack.PackDef def = plugin.packs().pack(packId);
        init(54, Text.of("&6" + (def == null ? "Card Pack" : Text.plain(def.displayName()))));
    }

    /** A pack deleted while someone was browsing: go back rather than open an empty screen. */
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
        Pack.PackDef p = plugin.packs().pack(packId);
        if (p == null) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        set(0, PackShopMenu.wallet(plugin, player), null);
        set(8, PackShopMenu.help(), null);

        boolean soldOut = plugin.packs().soldOut(p);
        List<String> summary = new ArrayList<>();
        summary.add("&f" + (p.cardCount() == 1 ? "1 Card inside" : p.cardCount() + " Cards inside"));
        if (p.usesOdds()) {
            summary.addAll(PackShopMenu.oddsLines(plugin, p));
        }
        summary.add("&7Print the Card at a Printer to make the Mini.");
        set(4, Menus.glint(Menus.icon(Material.PAPER, "&b" + Text.plain(p.displayName()) + " &7- &6"
                + PackShopMenu.price(plugin, p), summary.toArray(new String[0])), !soldOut), null);

        List<PackService.Possible> all = plugin.packs().possible(p);
        Set<String> owned = plugin.miniService().ownedIds(player.getUniqueId());
        Map<Rarity, Double> live = plugin.packs().liveOdds(p);
        Map<Rarity, Integer> liveCount = new java.util.EnumMap<>(Rarity.class);
        for (PackService.Possible pos : all) {
            if (!pos.soldOut()) {
                liveCount.merge(pos.def().rarity(), 1, Integer::sum);
            }
        }
        int from = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE && from + i < all.size(); i++) {
            PackService.Possible pos = all.get(from + i);
            set(GRID_START + i, tile(pos, owned.contains(pos.def().id()), live, liveCount), null);
        }
        if (all.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7Nothing in this pack yet"), null);
        }
        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new PackDetailMenu(plugin, player, packId, onBack, page - 1).open(player));
        }
        if (from + GRID_SIZE < all.size()) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new PackDetailMenu(plugin, player, packId, onBack, page + 1).open(player));
        }

        if (soldOut) {
            set(48, Menus.icon(Material.GRAY_DYE, "&cSold out", "&7Every Card in this pack is gone."), null);
        } else {
            if (p.price() > 0) {
                set(47, Menus.glint(Menus.icon(Material.GOLD_INGOT,
                        "&aBuy for &6" + plugin.economy().format(p.price()),
                        "&7You get a sealed pack.", "&7Right-click it to open."), true), e -> buy(Currency.MONEY));
            }
            if (p.priceTokens() > 0) {
                int have = plugin.tokens() == null ? 0 : plugin.tokens().balance(player.getUniqueId());
                set(51, Menus.glint(Menus.icon(Material.SUNFLOWER,
                        "&aBuy for &6" + p.priceTokens() + " tokens",
                        have >= p.priceTokens() ? "&7You get a sealed pack." : "&cNeed " + (p.priceTokens() - have)
                                + " more tokens",
                        "&7Right-click it to open."), have >= p.priceTokens()), e -> buy(Currency.TOKENS));
            }
        }
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back());
    }

    /** A Mini in this pack: its name and whether you have it, in the name. */
    private ItemStack tile(PackService.Possible pos, boolean have, Map<Rarity, Double> live,
                           Map<Rarity, Integer> liveCount) {
        MiniDef def = pos.def();
        String rarityText = plugin.miniService().rarityText(def.rarity());
        String colour = rarityText.substring(0, Math.max(0, rarityText.length() - def.rarity().display().length()));
        List<String> lore = new ArrayList<>();
        lore.add(rarityText + " &7Mini");
        if (pos.soldOut()) {
            lore.add("&cNo Cards left for this one.");
            return Menus.icon(Material.GRAY_DYE, "&7" + def.name() + " &c(sold out)", lore.toArray(new String[0]));
        }
        lore.add(have ? "&a✔ You have this Mini." : "&7You don't have this one yet.");
        Double rarityChance = live.get(def.rarity());
        Integer n = liveCount.get(def.rarity());
        if (rarityChance != null && n != null && n > 0) {
            lore.add("&7Chance per Card: &f" + PackShopMenu.percent(rarityChance / n));
        }
        ItemStack icon = plugin.miniService().cardFor(def.id());
        if (icon == null) {
            icon = new ItemStack(Material.PAPER);
        }
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(colour + def.name() + (have ? " &a✔" : " &7(missing)")));
            List<net.kyori.adventure.text.Component> l = new ArrayList<>();
            for (String line : lore) {
                l.add(Text.of(line));
            }
            meta.lore(l);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void buy(Currency currency) {
        PackService.BuyResult r = plugin.packs().buy(player, packId, currency);
        Pack.PackDef p = plugin.packs().pack(packId);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            Sounds.refused(player);
            refresh();
            return;
        }
        player.sendMessage(Text.of("&aYou bought " + (p == null ? "a Card Pack" : Text.plain(p.displayName()))
                + "! &7Right-click it to open."));
        Sounds.paid(player);
        refresh();
    }

    private void back() {
        if (onBack != null) {
            onBack.run();
        } else {
            player.closeInventory();
        }
    }
}
