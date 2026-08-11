package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.integration.HeadEntry;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniDraft;
import com.dierks.homecraft.mini.MiniIds;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Assigns shared metadata to a batch of selected web heads and imports them.
 * Series/category/rarity/type/cap/price/craftable are applied to the whole
 * batch at once (bulk); each head keeps its own name + texture. Import writes the
 * catalog to config and reloads live, so the Minis show as real rendered heads in
 * the Museum immediately. (Per-entry tweaks are a later refinement.)
 */
public final class ImportMetadataMenu extends Menu {

    private final Player player;
    private final List<HeadEntry> heads;
    private final Runnable onBack;
    private final MiniDraft meta; // name/id/texture unused — those come per-head

    public ImportMetadataMenu(HomeCraftManagement plugin, Player player, List<HeadEntry> heads, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.heads = heads;
        this.onBack = onBack;
        this.meta = plugin.miniService().blankDraft();
        init(54, Text.of("&2Import " + heads.size() + " head(s)"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }

        // Preview: the first head with the shared metadata applied.
        List<String> preview = new ArrayList<>();
        preview.add("&7Series: &f" + meta.series());
        preview.add("&7Type: &f" + meta.category());
        preview.add("&7Rarity: &f" + meta.rarity().name());
        preview.add("&7Cap: &f" + (meta.uncapped() ? "Unlimited" : Long.toString(meta.cap())));
        preview.add("&7Price: &f" + plugin.economy().format(Math.max(0, meta.price())));
        preview.add("&8Applies to all " + heads.size() + " selected");
        String sampleTexture = heads.isEmpty() ? "" : heads.get(0).texture();
        String sampleName = heads.isEmpty() ? "Preview" : heads.get(0).name();
        set(4, Heads.textured(sampleTexture, "&fPreview: &e" + sampleName, preview), null);

        set(10, Menus.icon(Material.BOOKSHELF, "&eSeries", "&f" + meta.series(), "&7Click to change"),
                e -> plugin.chatPrompts().prompt(player, "Enter the series name for the batch:", input -> {
                    meta.setSeries(input);
                    reopen();
                }));

        set(11, Menus.icon(Material.CHEST, "&eType / Category", "&f" + meta.category(),
                "&7Click to cycle configured types"), e -> {
            cycleCategory();
            refresh();
        });

        set(12, Menus.icon(plugin.miniService().style(meta.rarity()).pane(), "&eRarity",
                "&f" + meta.rarity().name(), "&7Click to cycle"), e -> {
            meta.setRarity(next(Rarity.values(), meta.rarity()));
            refresh();
        });

        set(13, Menus.icon(Material.ARMOR_STAND, "&eForm", "&f" + meta.type().name(),
                "&7HEAD or ARMOR_STAND"), e -> {
            meta.setType(next(MiniType.values(), meta.type()));
            refresh();
        });

        set(14, Menus.icon(meta.craftable() ? Material.CRAFTING_TABLE : Material.BARRIER,
                "&eCraftable", "&f" + (meta.craftable() ? "Yes" : "No"), "&7Click to toggle"), e -> {
            meta.setCraftable(!meta.craftable());
            refresh();
        });

        set(19, Menus.icon(Material.BARRIER, "&eMint cap",
                "&f" + (meta.uncapped() ? "Unlimited" : Long.toString(meta.cap())),
                "&7Click to set a number or 'unlimited'"),
                e -> plugin.chatPrompts().prompt(player, "Enter a mint cap number, or 'unlimited':", this::setCapThenReopen));

        set(20, Menus.icon(Material.GOLD_INGOT, "&ePrice",
                "&f" + plugin.economy().format(Math.max(0, meta.price())),
                "&7Click to set the mint price"),
                e -> plugin.chatPrompts().prompt(player, "Enter the mint price (a number):", this::setPriceThenReopen));

        set(21, Menus.icon(Material.NETHER_STAR, "&bSmart defaults",
                "&7Reset cap + price to the",
                "&7" + meta.rarity().name() + " rarity defaults"), e -> {
            plugin.miniService().applyRarityDefaults(meta);
            refresh();
        });

        set(45, Menus.icon(Material.ARROW, "&cBack (keep selection)"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });

        set(49, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Import (" + heads.size() + ")",
                "&7Adds them to the catalog with",
                "&7textures pulled automatically."), e -> doImport());
    }

    private void doImport() {
        if (heads.isEmpty()) {
            player.sendMessage(Text.of("&eNothing selected to import."));
            return;
        }
        List<MiniDef> list = plugin.miniService().catalogList();
        Set<String> taken = new HashSet<>();
        for (MiniDef d : list) {
            taken.add(d.id());
        }
        int added = 0;
        for (HeadEntry head : heads) {
            String id = MiniIds.unique(head.name(), taken);
            taken.add(id);
            list.add(new MiniDef(id, head.name(), meta.series(), meta.category(), meta.rarity(),
                    meta.type(), head.texture(), meta.cap(), meta.price(), meta.craftable()));
            added++;
        }
        plugin.miniService().saveCatalog(list);
        player.sendMessage(Text.of("&aImported &f" + added + " &aMini(s) into series &f" + meta.series() + "&a."));
        new MiniListMenu(plugin, player, null).open(player);
    }

    private void setCapThenReopen(String input) {
        String v = input.trim().toLowerCase();
        if (v.equals("unlimited") || v.equals("none") || v.equals("-1") || v.equals("infinite")) {
            meta.setCap(-1);
        } else {
            try {
                meta.setCap(Math.max(1, Long.parseLong(v)));
            } catch (NumberFormatException ex) {
                player.sendMessage(Text.of("&cNot a number — cap unchanged."));
            }
        }
        reopen();
    }

    private void setPriceThenReopen(String input) {
        try {
            meta.setPrice(Math.max(0, Double.parseDouble(input.trim())));
        } catch (NumberFormatException ex) {
            player.sendMessage(Text.of("&cNot a number — price unchanged."));
        }
        reopen();
    }

    private void cycleCategory() {
        List<String> cats = plugin.miniService().categories();
        if (cats.isEmpty()) {
            return;
        }
        int idx = cats.indexOf(meta.category());
        meta.setCategory(cats.get((idx + 1) % cats.size()));
    }

    private static <T> T next(T[] values, T current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private void reopen() {
        open(player);
    }
}
