package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The PC's root screen — the <b>Site launcher</b> (§2.2).
 *
 * <p>The PC is not the store; it is the server's browser, and each feature is a Site you
 * open on it. That distinction is the spine the project hangs on, and until now it existed
 * only on paper: right-clicking a PC opened the Store directly, and the Store's navigation
 * row had quietly become the hub instead. That row is nine slots wide and every one was
 * taken, so the next Site had nowhere to go — the Courier module was the change that would
 * have had to break something to land.
 *
 * <p>So this is deliberately a plain grid of destinations and nothing else. Adding a Site
 * is adding a row to {@link #sites()}; nothing has to be rearranged to make room, which is
 * the whole reason §2.2 asks for a launcher rather than a shop front with links on it.
 */
public final class SiteLauncherMenu extends Menu {

    /**
     * Where the Site tiles sit: the middle two rows, centred.
     *
     * <p>Each Site wears the icon it already wears everywhere else — Instant Market is an
     * emerald here and in the Store, the Museum is a player head in both — because an item
     * that means one thing in one menu and another thing next door is the defect
     * {@code DepartmentIconsTest} exists to catch. The store's own compass is the one new
     * icon, chosen to collide with neither the menu chrome nor a department tab.
     */
    private static final int[] SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

    /** One destination on the launcher. */
    private record Site(Material icon, String name, String blurb, Consumer<Player> open) {
    }

    private final Player player;

    public SiteLauncherMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        PluginConfig.Store store = plugin.config().store();
        String title = plugin.config().menuTitles().pcFormat()
                .replace("{store}", store.name())
                .replace("{url}", store.displayUrl());
        init(45, Text.of(title));
    }

    @Override
    protected void build() {
        set(4, Menus.icon(Material.GOLD_INGOT, "&6Your balance",
                Menus.money(plugin, plugin.economy().balance(player)),
                "&8—",
                "&8Press Esc to step away from the PC."), null);

        List<Site> sites = sites();
        for (int i = 0; i < SLOTS.length && i < sites.size(); i++) {
            Site site = sites.get(i);
            set(SLOTS[i], Menus.icon(site.icon(), site.name(), "&7" + site.blurb(),
                    "&8—", "&eClick to open"), e -> site.open().accept(player));
        }
    }

    /**
     * The Sites this PC can open, in the order they appear. Adding one is adding a row.
     *
     * <p>The Crate store leads because it is the one every player needs first; the rest are
     * in the order a player meets them.
     */
    private List<Site> sites() {
        PluginConfig.Store store = plugin.config().store();
        List<Site> out = new ArrayList<>();
        out.add(new Site(Material.COMPASS, "&6" + store.name(),
                "Browse the catalogue and order with shipping.",
                p -> new StoreMenu(plugin, p).open(p)));
        out.add(new Site(Material.EMERALD, "&aInstant Market",
                "Buy and sell now at market price — no shipping.",
                p -> new MarketMenu(plugin, p, this::reopen).open(p)));
        out.add(new Site(Material.CHEST, "&6Marketplace",
                "What other players list in their Pallets.",
                p -> new com.dierks.homecraft.gui.marketplace.MarketplaceMenu(plugin, p, this::reopen).open(p)));
        out.add(new Site(Material.PAPER, "&bCard Packs",
                "Buy booster packs and open them for Cards.",
                p -> new com.dierks.homecraft.gui.mini.PackShopMenu(plugin, p, this::reopen).open(p)));
        out.add(new Site(Material.PLAYER_HEAD, "&5Mini Museum",
                "Every collectible, and what you have.",
                p -> new MuseumMenu(plugin, p, this::reopen).open(p)));
        out.add(new Site(Material.CHEST_MINECART, "&eMailbox & Orders",
                "Track deliveries and collect what has arrived.",
                p -> new MailboxMenu(plugin, p, this::reopen).open(p)));
        if (plugin.config().courier().enabled()) {
            out.add(new Site(Material.FILLED_MAP, "&2Courier",
                    "Take a delivery run and get paid for the trip.",
                    p -> new com.dierks.homecraft.gui.courier.JobBoardMenu(plugin, p, this::reopen).open(p)));
        }
        return out;
    }

    private void reopen() {
        new SiteLauncherMenu(plugin, player).open(player);
    }

    /** Open the launcher for a player — the one entry point a PC (or a test) uses. */
    public static void open(HomeCraftManagement plugin, Player player) {
        new SiteLauncherMenu(plugin, player).open(player);
    }
}
