package com.dierks.homecraft.gui.admin;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Rename any GUI section title (and the store branding) without touching
 * config.yml by hand — every label is config-driven (§2.2). Titles accept
 * '&amp;'-colour codes; the store title also accepts {@code {store}} / {@code {url}}
 * tokens. Edits write config + reload live.
 */
public final class SectionTitlesMenu extends Menu {

    private final Player player;
    private final Runnable onBack;

    public SectionTitlesMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(27, Text.of("&6Section Titles"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }

        PluginConfig.MenuTitles t = plugin.config().menuTitles();
        PluginConfig.Store store = plugin.config().store();

        set(10, titleIcon(Material.OAK_SIGN, "Admin Studio title", t.admin()),
                e -> prompt("menus.admin_title", "Enter the Admin Studio title:"));
        set(11, titleIcon(Material.PLAYER_HEAD, "Museum title", t.museum()),
                e -> prompt("menus.museum_title", "Enter the Museum title:"));
        set(12, titleIcon(Material.EMERALD, "Market title", t.market()),
                e -> prompt("menus.market_title", "Enter the Market title:"));
        set(13, titleIcon(Material.CHEST, "Store title", t.storeFormat(),
                "&8Tokens: {store}, {url}"),
                e -> prompt("menus.store_title", "Enter the Store title (use {store} and {url}):"));

        set(15, titleIcon(Material.NAME_TAG, "Store name", store.name()),
                e -> prompt("store.name", "Enter the store name (e.g. Crate):"));
        set(16, titleIcon(Material.COMPASS, "Store URL (display only)", store.displayUrl()),
                e -> prompt("store.display_url", "Enter the store display URL (e.g. www.Crate.com):"));

        set(22, Menus.icon(Material.ARROW, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private org.bukkit.inventory.ItemStack titleIcon(Material mat, String label, String value, String... extra) {
        String[] lore = new String[2 + extra.length];
        lore[0] = "&f" + value;
        lore[1] = "&7Click to change";
        System.arraycopy(extra, 0, lore, 2, extra.length);
        return Menus.icon(mat, "&e" + label, lore);
    }

    private void prompt(String path, String question) {
        plugin.chatPrompts().prompt(player, question, input -> {
            plugin.getConfig().set(path, input);
            plugin.saveConfig();
            plugin.config().load();
            player.sendMessage(Text.of("&aUpdated. &7(" + path + ")"));
            new SectionTitlesMenu(plugin, player, onBack).open(player);
        });
    }
}
