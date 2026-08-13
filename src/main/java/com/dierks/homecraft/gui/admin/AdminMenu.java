package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Admin Studio root (`/hcm admin`). A launcher for the Mini catalog tools:
 * manage existing Minis, add one by hand, import from the web head library, and
 * rename the config-driven section titles. Trading/drop tools (Vending Machine,
 * Auction House, Wild Drops) are the next pass and show here as a disabled stub.
 */
public final class AdminMenu extends Menu {

    private final Player player;

    public AdminMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(27, Text.of(plugin.config().menuTitles().admin()));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }

        set(10, Menus.icon(Material.BOOK, "&eManage Minis",
                "&7Edit, re-price, or remove any Mini",
                "&7in the catalog.",
                "&8" + plugin.miniService().catalog().size() + " in catalog"),
                e -> new MiniListMenu(plugin, player, this::reopen).open(player));

        set(11, Menus.icon(Material.NAME_TAG, "&aAdd Mini (manual)",
                "&7Create a Mini by hand — paste a",
                "&7texture value + assign metadata.",
                "&8Fallback when not importing."),
                e -> new MiniEditMenu(plugin, player,
                        plugin.miniService().blankDraft(), false, this::reopen).open(player));

        set(13, Menus.icon(Material.COMPASS, "&bImport from Web",
                "&7Search minecraft-heads.com, pick",
                "&7heads, and import with textures",
                "&7pulled automatically.",
                "&8The centerpiece."),
                e -> new WebImportMenu(plugin, player, this::reopen).open(player));

        set(15, Menus.icon(Material.OAK_SIGN, "&6Section Titles",
                "&7Rename GUI titles (Museum, Market,",
                "&7Store, Admin) — all config-driven."),
                e -> new SectionTitlesMenu(plugin, player, this::reopen).open(player));

        set(16, Menus.icon(Material.LEVER, "&dReload Config",
                "&7Re-read config.yml + recipes + market."),
                e -> {
                    plugin.reloadAll();
                    player.sendMessage(Text.of("&aConfiguration reloaded."));
                    refresh();
                });

        set(20, Menus.icon(Material.FLOWER_BANNER_PATTERN, "&eCategories",
                "&7Create / rename / remove your own",
                "&7Mini Type categories."),
                e -> new CategoriesMenu(plugin, player, this::reopen).open(player));

        set(24, Menus.icon(Material.TRIPWIRE_HOOK, "&5Drops / Loot",
                "&7Manage Wild-Drop loot lists & sources."),
                e -> new LootAdminMenu(plugin, player, this::reopen).open(player));

        set(25, Menus.icon(Material.PAPER, "&5Card Packs",
                "&7Build & edit buyable Card packs",
                "&7(price, count, weighted pool).",
                "&8" + plugin.packs().packs().size() + " pack(s)"),
                e -> new PacksAdminMenu(plugin, player, this::reopen).open(player));

        set(22, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());

        // Trading blocks are obtained via /hcm give <vending|display|auction>.
        set(26, Menus.icon(Material.GRAY_DYE, "&8Armor-stand Minis",
                "&8The second Mini form (posed stands)",
                "&8lands in the next micro-pass."), null);
    }

    private void reopen() {
        new AdminMenu(plugin, player).open(player);
    }
}
