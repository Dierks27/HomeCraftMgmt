package com.dierks.homecraft.gui.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.Pack;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Card Pack shop, opened from the PC's Card Packs site and from the Arcade — the same
 * screen, with Back returning to whichever opened it.
 *
 * <p>Each tile puts the pack and its price in its NAME (Bedrock shows lore only on
 * tap-and-hold); the lore says it holds one Card, the odds of each rarity, and how many of the
 * Minis in it the player is still missing. Clicking opens the pack's detail page, where it is
 * bought.
 */
public final class PackShopMenu extends Menu {

    /** Pack tiles, centred in rows 1–4. */
    static final int[] TILES = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final Player player;
    private final Runnable onBack;

    public PackShopMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(54, Text.of("&6Card Packs"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 9; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        set(0, wallet(plugin, player), null);
        set(8, help(), e -> new com.dierks.homecraft.gui.arcade.GuideMenu(plugin, player,
                com.dierks.homecraft.gui.arcade.GuideMenu.MINIS, this::reopen).open(player));

        List<Pack.PackDef> packs = plugin.packs().packs();
        if (packs.isEmpty()) {
            // PAPER, not BARRIER: an empty shelf is not an error.
            set(22, Menus.icon(Material.PAPER, "&7No packs yet",
                    "&7An admin can make some with &f/hcm packs&7."), null);
        }
        for (int i = 0; i < packs.size() && i < TILES.length; i++) {
            Pack.PackDef p = packs.get(i);
            set(TILES[i], tile(p), e -> new PackDetailMenu(plugin, player, p.id(), this::reopen).open(player));
        }

        set(49, Menus.icon(Material.BARRIER, onBack != null ? "&cBack" : "&cClose"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    /** Dollars and tokens side by side: packs are the one thing sold for both. */
    static ItemStack wallet(HomeCraftManagement plugin, Player player) {
        int tokens = plugin.tokens() == null ? 0 : plugin.tokens().balance(player.getUniqueId());
        return Menus.icon(Material.GOLD_INGOT, "&6" + plugin.economy().format(plugin.economy().balance(player))
                + " &7· &6" + tokens + " tokens", "&7What you have to spend.");
    }

    /** "How packs work", in four steps; click for the Minis page of How It Works. */
    static ItemStack help() {
        return Menus.icon(Material.BOOK, "&e? How packs work",
                "&71. Open a pack to get a Card.",
                "&72. Take the Card to a Printer.",
                "&73. Add filament and print it.",
                "&74. Your Mini is ready!",
                "&eClick for the guide");
    }

    /** "$100 or 50 tokens", "$300", "50 tokens" — or "Not for sale". */
    static String price(HomeCraftManagement plugin, Pack.PackDef p) {
        List<String> parts = new ArrayList<>();
        if (p.price() > 0) {
            parts.add(plugin.economy().format(p.price()));
        }
        if (p.priceTokens() > 0) {
            parts.add(p.priceTokens() + " tokens");
        }
        return parts.isEmpty() ? "Not for sale" : String.join(" or ", parts);
    }

    /** The rarity odds as lines: "Common 62%", in each rarity's colour. */
    static List<String> oddsLines(HomeCraftManagement plugin, Pack.PackDef p) {
        List<String> out = new ArrayList<>();
        Map<Rarity, Double> live = plugin.packs().liveOdds(p);
        for (Rarity r : Rarity.values()) {
            Double chance = live.get(r);
            if (chance != null && chance > 0) {
                out.add(plugin.miniService().rarityText(r) + " &f" + percent(chance));
            }
        }
        return out;
    }

    static String percent(double chance) {
        double pct = chance * 100;
        if (pct >= 1 || pct == 0) {
            return Math.round(pct) + "%";
        }
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }

    private ItemStack tile(Pack.PackDef p) {
        boolean soldOut = plugin.packs().soldOut(p);
        String name = Text.plain(p.displayName());
        if (soldOut) {
            return Menus.icon(Material.GRAY_DYE, "&7" + name + " &8- &cSold out",
                    "&7Every Card in this pack is gone.",
                    "&eClick to see what was in it");
        }
        List<String> lore = new ArrayList<>();
        lore.add("&f" + (p.cardCount() == 1 ? "1 Card inside" : p.cardCount() + " Cards inside"));
        if (p.usesOdds()) {
            lore.addAll(oddsLines(plugin, p));
        }
        int missing = plugin.packs().missing(player, p);
        lore.add(missing > 0
                ? "&dYou're missing " + missing + " of the Minis in this pack"
                : "&a✔ You have every Mini in this pack!");
        lore.add("&eClick to look inside");
        ItemStack icon = Menus.icon(Material.PAPER, "&b" + name + " &7- &6" + price(plugin, p),
                lore.toArray(new String[0]));
        return Menus.glint(icon, true);
    }

    private void reopen() {
        new PackShopMenu(plugin, player, onBack).open(player);
    }
}
