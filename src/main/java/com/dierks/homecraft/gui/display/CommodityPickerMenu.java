package com.dierks.homecraft.gui.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.display.DisplayService;
import com.dierks.homecraft.display.Trend;
import com.dierks.homecraft.gui.MarketLabels;
import com.dierks.homecraft.gui.MarketNewsMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pick a market commodity by clicking its icon — the GUI-first binding step for
 * every economy display (sign, hologram, map-TV). Reuses the loot-editor picker
 * pattern (visual list, never a typed item name). Each icon shows the live price
 * and trend so the admin binds by sight.
 *
 * <p>Holograms and TVs can also show the Market News board: built with {@code offerNews}, and
 * while the live market runs, the first tile (slot 0 of page 1) is a BELL that picks
 * {@link DisplayService#NEWS_ID}. With the live market off or paused the picker is exactly 0.32's
 * (every commodity in its old slot). Signs never offer it — a sign is too small for a headline.
 */
public final class CommodityPickerMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final String title;
    private final Consumer<String> onPick;
    private final Runnable onBack;
    private final boolean offerNews;
    private int page;

    public CommodityPickerMenu(HomeCraftManagement plugin, Player player, String title,
                               Consumer<String> onPick, Runnable onBack) {
        this(plugin, player, title, onPick, onBack, false);
    }

    /**
     * @param offerNews put the Market News board ({@link DisplayService#NEWS_ID}) first while the
     *                  live market runs, for the hologram and TV binders
     */
    public CommodityPickerMenu(HomeCraftManagement plugin, Player player, String title,
                               Consumer<String> onPick, Runnable onBack, boolean offerNews) {
        super(plugin);
        this.player = player;
        this.title = title;
        this.onPick = onPick;
        this.onBack = onBack;
        this.offerNews = offerNews;
        init(54, Text.of(title));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        List<MarketItem> items = new ArrayList<>(plugin.market().catalog());
        // The news board, when offered and the live market runs, takes the first tile and every
        // commodity moves up one. Checked on every build, so a pause shows the 0.32 picker.
        int lead = offerNews && MarketNewsMenu.live(plugin) != null ? 1 : 0;
        int entries = items.size() + lead;
        int pages = Math.max(1, (int) Math.ceil(entries / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= entries) {
                set(i, null, null);
                continue;
            }
            if (idx < lead) {
                set(i, Menus.icon(Material.BELL, MarketLabels.NEWS_BUTTON,
                        "&7The newest market headline and",
                        "&7what is HOT or on sale right now.",
                        "&8—",
                        "&eClick to bind this display"), e -> onPick.accept(DisplayService.NEWS_ID));
                continue;
            }
            MarketItem item = items.get(idx - lead);
            double change = plugin.market().change24h(item.id());
            set(i, Menus.icon(item.material(), item.label(),
                    "&7Price: &6" + plugin.economy().format(plugin.market().price(item.id())),
                    "&7Trend: " + Trend.color(change) + Trend.label(change),
                    "&8—",
                    "&eClick to bind this display"), e -> onPick.accept(item.id()));
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        set(49, Menus.icon(Material.BARRIER, "&cCancel"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        if ((page + 1) * PAGE_SIZE < entries) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }
}
