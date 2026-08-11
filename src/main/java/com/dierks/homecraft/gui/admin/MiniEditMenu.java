package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniDraft;
import com.dierks.homecraft.mini.MiniIds;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The Mini metadata form — the shared editor for both "Add Mini" and editing an
 * existing catalog entry. Free-text fields (name, series, cap, price, texture)
 * prompt for a chat line; enumerated fields (category, rarity, type, craftable)
 * cycle on click. A live rendered preview sits at the top. Saving writes the
 * whole catalog to config and reloads live.
 */
public final class MiniEditMenu extends Menu {

    private final Player player;
    private final MiniDraft draft;
    private final boolean existing;
    private final Runnable onBack;

    public MiniEditMenu(HomeCraftManagement plugin, Player player, MiniDraft draft,
                        boolean existing, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.draft = draft;
        this.existing = existing;
        this.onBack = onBack;
        init(54, Text.of(existing ? "&4Edit Mini" : "&2Add Mini"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }

        // Live preview of the Mini as it will render.
        set(4, plugin.miniService().icon(draft.toDef()), null);

        set(10, Menus.icon(Material.NAME_TAG, "&eName", "&f" + draft.name(), "&7Click to change"),
                e -> promptText("Enter the Mini's display name:", draft::setName));

        set(11, Menus.icon(Material.BOOKSHELF, "&eSeries", "&f" + draft.series(), "&7Click to change"),
                e -> promptText("Enter the series name:", draft::setSeries));

        set(12, Menus.icon(Material.CHEST, "&eType / Category", "&f" + draft.category(),
                "&7Left-click: cycle", "&7Right-click: type a new one"), e -> {
            if (e.getClick().isRightClick()) {
                promptText("Type a new category name:", v -> {
                    String cat = v.trim();
                    if (!cat.isEmpty()) {
                        plugin.miniService().addCategory(cat);
                        draft.setCategory(cat);
                    }
                });
            } else {
                cycleCategory();
                refresh();
            }
        });

        set(14, Menus.icon(rarityPane(), "&eRarity", "&f" + draft.rarity().name(),
                "&7Click to cycle", "&8Colour + glint follow the tier"), e -> {
            draft.setRarity(next(Rarity.values(), draft.rarity()));
            refresh();
        });

        set(15, Menus.icon(Material.ARMOR_STAND, "&eForm", "&f" + draft.type().name(),
                "&7HEAD or ARMOR_STAND"), e -> {
            draft.setType(next(MiniType.values(), draft.type()));
            refresh();
        });

        set(16, Menus.icon(draft.craftable() ? Material.CRAFTING_TABLE : Material.BARRIER,
                "&eCraftable", "&f" + (draft.craftable() ? "Yes" : "No"),
                "&7Click to toggle", "&8Recipe wiring lands in a later pass"), e -> {
            draft.setCraftable(!draft.craftable());
            refresh();
        });

        set(19, Menus.icon(Material.BARRIER, "&eMint cap",
                "&f" + (draft.uncapped() ? "Unlimited" : Long.toString(draft.cap())),
                "&7Click to set a number or 'unlimited'"),
                e -> promptText("Enter a mint cap number, or 'unlimited':", this::setCap));

        set(20, Menus.icon(Material.GOLD_INGOT, "&ePrice",
                "&f" + plugin.economy().format(Math.max(0, draft.price())),
                "&7Click to set the mint price"),
                e -> promptText("Enter the mint price (a number):", this::setPrice));

        set(21, Menus.icon(Material.NETHER_STAR, "&bSmart defaults",
                "&7Reset cap + price to the",
                "&7" + draft.rarity().name() + " rarity defaults"), e -> {
            plugin.miniService().applyRarityDefaults(draft);
            refresh();
        });

        boolean hasTexture = draft.texture() != null && !draft.texture().isBlank();
        set(23, Menus.icon(Material.PLAYER_HEAD, "&eTexture",
                hasTexture ? "&aSet" : "&7None (plain head)",
                "&7Click to paste a Base64 value",
                "&8(or import from the web instead)"),
                e -> promptText("Paste the head texture Base64 value:", draft::setTexture));

        if (draft.type() == MiniType.ARMOR_STAND) {
            boolean captured = draft.id() != null && plugin.miniService().stand(draft.id()) != null;
            set(25, Menus.icon(Material.ARMOR_STAND, "&eArmor-stand pose",
                    captured ? "&aCaptured ✓" : "&7Not captured yet",
                    "&7Pose a real armor stand in the world,",
                    "&7then run &f/hcm mini capturestand " + (draft.id() == null ? "<id>" : draft.id()),
                    "&8(save first to get the id)"), null);
        }

        set(45, Menus.icon(Material.ARROW, "&cBack (discard)"), e -> back());

        set(49, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Save",
                "&7Write to config + reload live"), e -> save());

        if (existing) {
            set(53, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c&l✗ Delete",
                    "&7Remove this Mini from the catalog",
                    "&8Mint history in the DB is kept"), e -> delete());
        }
    }

    // ---- field editors ----------------------------------------------------

    private void promptText(String question, java.util.function.Consumer<String> apply) {
        plugin.chatPrompts().prompt(player, question, input -> {
            apply.accept(input);
            reopen();
        });
    }

    private void setCap(String input) {
        String v = input.trim().toLowerCase();
        if (v.equals("unlimited") || v.equals("none") || v.equals("-1") || v.equals("infinite")) {
            draft.setCap(-1);
            return;
        }
        try {
            draft.setCap(Math.max(1, Long.parseLong(v)));
        } catch (NumberFormatException ex) {
            player.sendMessage(Text.of("&cNot a number — cap unchanged."));
        }
    }

    private void setPrice(String input) {
        try {
            draft.setPrice(Math.max(0, Double.parseDouble(input.trim())));
        } catch (NumberFormatException ex) {
            player.sendMessage(Text.of("&cNot a number — price unchanged."));
        }
    }

    private void cycleCategory() {
        List<String> cats = plugin.miniService().categories();
        if (cats.isEmpty()) {
            return;
        }
        int idx = cats.indexOf(draft.category());
        draft.setCategory(cats.get((idx + 1) % cats.size()));
    }

    private Material rarityPane() {
        return plugin.miniService().style(draft.rarity()).pane();
    }

    private static <T> T next(T[] values, T current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    // ---- persistence ------------------------------------------------------

    private void save() {
        if (draft.name() == null || draft.name().isBlank()) {
            player.sendMessage(Text.of("&cGive the Mini a name first."));
            return;
        }
        if (draft.id() == null || draft.id().isBlank()) {
            draft.setId(MiniIds.unique(draft.name(), plugin.miniService().ids()));
        }
        MiniDef def = draft.toDef();

        List<MiniDef> list = plugin.miniService().catalogList();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(def.id())) {
                list.set(i, def);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(def);
        }
        plugin.miniService().saveCatalog(list);
        player.sendMessage(Text.of("&aSaved &f" + def.name() + " &7(" + def.id() + ")."));
        back();
    }

    private void delete() {
        List<MiniDef> list = plugin.miniService().catalogList();
        list.removeIf(d -> d.id().equals(draft.id()));
        plugin.miniService().saveCatalog(list);
        player.sendMessage(Text.of("&eRemoved &f" + draft.name() + " &7from the catalog."));
        back();
    }

    private void back() {
        // Always land back in the catalog list; its own Back returns to the parent.
        new MiniListMenu(plugin, player, onBack).open(player);
    }

    private void reopen() {
        new MiniEditMenu(plugin, player, draft, existing, onBack).open(player);
    }
}
