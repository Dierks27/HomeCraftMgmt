package com.dierks.homecraft.gui;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.OrderMath;
import com.dierks.homecraft.market.PricingEngine;
import com.dierks.homecraft.market.sim.Badge;
import com.dierks.homecraft.market.sim.EventKind;
import com.dierks.homecraft.market.sim.Headlines;
import com.dierks.homecraft.market.sim.ItemParams;
import com.dierks.homecraft.market.sim.ItemStatus;
import com.dierks.homecraft.market.sim.MarketEvent;
import com.dierks.homecraft.market.sim.MarketNewsService;
import com.dierks.homecraft.market.sim.MarketSimService;
import com.dierks.homecraft.market.sim.RealSymbol;
import com.dierks.homecraft.market.sim.Season;
import com.dierks.homecraft.market.sim.SimSettings;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.web.NewsFeed;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Crate Market News (spec §7.2): what is HOT or on sale right now, the nine newest headlines,
 * and the player's own "news in chat" switch. Opened from the Sell screen's slot 50.
 *
 * <pre>
 *  4  BELL header — the "Right now" lines and the season
 *  8  mute toggle — News in chat: ON / OFF
 *  9–17  the 9 newest news entries (item icon; BELL for a season or real-world move)
 * 22  Back
 * </pre>
 *
 * Clicking an UP or HOT entry opens the sell quantity for that item; a DOWN or DEAL entry opens
 * the store's order quantity. Nothing here is shown before players were told: a HOT or DEAL
 * still in its silent ramp is left out ({@link NewsFeed#visible}).
 *
 * <p><b>Shared reads.</b> The static helpers at the bottom are how every surface (Store and Sell
 * tiles, the admin menus, signs/holograms/TVs and PlaceholderAPI) reads the live market. Each one
 * gives the "nothing to show" answer while the market is off, paused or was never built, so those
 * surfaces then look exactly as they did before the live market existed.
 */
public final class MarketNewsMenu extends Menu {

    private static final int SIZE = 27;
    private static final int HEADER_SLOT = 4;
    private static final int MUTE_SLOT = 8;
    private static final int FIRST_ENTRY = 9;
    private static final int ENTRIES = 9;
    private static final int BACK_SLOT = 22;
    /** Extra rows asked of the service, so dropping a ramping HOT/DEAL still leaves a full page. */
    private static final int SLACK = 6;
    /** The badges a "Right now" line lists, in the order it lists them. */
    private static final Badge[] RIGHT_NOW = {Badge.HOT, Badge.UP, Badge.DEAL, Badge.DOWN};

    private final Player player;
    private final Runnable onBack;

    private MarketNewsMenu(HomeCraftManagement plugin, Player player, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.onBack = onBack;
        init(SIZE, Text.of("&6Crate Market News"));
    }

    /** Open the news menu; Back runs {@code onBack} (or closes the menu when it is null). */
    public static void open(HomeCraftManagement plugin, Player player, Runnable onBack) {
        new MarketNewsMenu(plugin, player, onBack).open(player);
    }

    @Override
    protected void build() {
        for (int i = 0; i < SIZE; i++) {
            set(i, Menus.FILLER, null);
        }
        long now = System.currentTimeMillis();

        set(HEADER_SLOT, Menus.icon(Material.BELL, MarketLabels.NEWS_MENU_HEADER,
                headerLore(now).toArray(new String[0])), null);

        MarketNewsService news = plugin.marketNews();
        if (news != null) {
            boolean muted = news.muted(player.getUniqueId());
            set(MUTE_SLOT, Menus.icon(muted ? Material.GRAY_DYE : Material.LIME_DYE, MarketLabels.muteToggle(!muted),
                    muted ? "&7Market news stays out of your chat." : "&7Market news shows up in your chat.",
                    "&8—",
                    "&eClick to turn it " + (muted ? "on" : "off")), e -> {
                news.setMuted(player.getUniqueId(), !muted);
                refresh();
            });
        }

        List<MarketEvent> entries = news(plugin, ENTRIES);
        if (entries.isEmpty()) {
            set(FIRST_ENTRY + ENTRIES / 2, Menus.icon(Material.PAPER, "&7" + MarketLabels.CALM,
                    "&7Check back later for news."), null);
        }
        for (int i = 0; i < entries.size() && i < ENTRIES; i++) {
            MarketEvent event = entries.get(i);
            EventKind kind = event.kind();
            boolean sells = kind == EventKind.UP || kind == EventKind.HOT;
            boolean orders = kind == EventKind.DOWN || kind == EventKind.DEAL;
            set(FIRST_ENTRY + i, entryIcon(event, now), sells || orders ? e -> {
                MarketItem item = event.itemId() == null ? null : plugin.market().item(event.itemId());
                if (item == null) {
                    player.sendMessage(Text.of("&cCrate does not trade that item any more."));
                    return;
                }
                Runnable back = () -> open(plugin, player, onBack);
                if (sells) {
                    MarketMenu.openSellFor(plugin, player, item, back);
                } else {
                    StoreMenu.openOrderFor(plugin, player, item, back);
                }
            } : null);
        }

        set(BACK_SLOT, Menus.icon(Material.BARRIER, "&cBack"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    /** Slot 4's lore: one "Right now" line per badged item, then the season; calm when none. */
    private List<String> headerLore(long now) {
        List<String> lines = rightNow(plugin, now);
        List<String> lore = new ArrayList<>();
        if (lines.isEmpty()) {
            lore.add("&7" + MarketLabels.CALM);
        } else {
            lore.add("&7Right now:");
            lore.addAll(lines);
        }
        return lore;
    }

    /** One news entry: the item (or a BELL), its headline, age, prices and where a click goes. */
    private org.bukkit.inventory.ItemStack entryIcon(MarketEvent e, long now) {
        Material icon = Material.BELL;
        if (e.kind() != EventKind.SEASON && e.kind() != EventKind.REAL && e.itemId() != null) {
            MarketItem item = plugin.market().item(e.itemId());
            if (item != null) {
                icon = item.material();
            }
        }
        List<String> lore = MarketLabels.newsEntryLore(e, age(e, now),
                price(e.priceBefore()), price(e.priceAfter()), e.active(now));
        return Menus.icon(icon, MarketLabels.newsEntryName(headline(plugin, e)), lore.toArray(new String[0]));
    }

    /** A stored price, formatted; blank when the event has none (seasons, WANTED, real moves). */
    private String price(double amount) {
        return Double.isFinite(amount) && amount > 0 ? plugin.economy().format(amount) : "";
    }

    // ---- shared reads for every live-market surface -------------------------------------------

    /**
     * The live market while it runs (enabled and not paused), else {@code null}. Every surface
     * reads the sim through this, so with it off they show exactly what they did before it existed.
     */
    public static MarketSimService live(HomeCraftManagement plugin) {
        MarketSimService sim = plugin.marketSim();
        return guard(plugin, () -> sim != null && sim.active() ? sim : null, null);
    }

    /** {@code id}'s badge and mood while the market runs; {@code null} when it is off or the id is unknown. */
    public static ItemStatus status(HomeCraftManagement plugin, String id) {
        MarketSimService sim = live(plugin);
        return sim == null || id == null ? null : guard(plugin, () -> sim.status(id), null);
    }

    /** The badge a status shows; {@link Badge#NONE} for none (or no status). */
    public static Badge badge(ItemStatus status) {
        return status == null ? Badge.NONE : status.badge();
    }

    /**
     * Time left on a status's badge in words ({@code about a day}), or {@code ""} when it has no
     * end ({@code WANTED}, no badge) or the end has passed.
     */
    public static String left(ItemStatus status, long now) {
        return status == null || status.endsAt() <= now ? "" : Headlines.left(status.endsAt() - now);
    }

    /**
     * The usual price the way a tile quotes it: the ask on the Store, the bid on the Sell screen.
     * "Usually $X" then compares like with like — the tile's price over it is exactly the mood.
     */
    public static double usualQuote(HomeCraftManagement plugin, MarketItem item, double usual, boolean sell) {
        PluginConfig.Market m = plugin.config().market();
        PricingEngine engine = new PricingEngine(m.elasticity(), m.inertia(), m.spread());
        return sell ? OrderMath.bid(engine, item, usual) : OrderMath.ask(engine, item, usual);
    }

    /**
     * The newest news players may already know about, newest first: at most {@code limit} from the
     * last 7 days, never a HOT/DEAL still in its silent ramp, never a real-world move too small to
     * have a headline. Empty while the market is off.
     */
    public static List<MarketEvent> news(HomeCraftManagement plugin, int limit) {
        MarketSimService sim = live(plugin);
        if (sim == null || limit <= 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        long since = now - NewsFeed.NEWS_WINDOW_MS;
        List<MarketEvent> raw = guard(plugin, () -> sim.recentNews(limit + SLACK, since), List.of());
        List<MarketEvent> out = new ArrayList<>();
        for (MarketEvent e : raw) {
            if (e == null || !NewsFeed.visible(e, now)) {
                continue;
            }
            long t = NewsFeed.newsTime(e);
            if (t < since || t > now) {
                continue;
            }
            if (e.kind() == EventKind.REAL && MarketLabels.plain(e.headline()).isEmpty()) {
                continue;
            }
            out.add(e);
        }
        // Newest news first: a HOT/DEAL became news at the end of its ramp, not when it started.
        out.sort(Comparator.comparingLong((MarketEvent e) -> NewsFeed.newsTime(e))
                .thenComparingLong(MarketEvent::id).reversed());
        return out.size() <= limit ? out : List.copyOf(out.subList(0, limit));
    }

    /** The plain names of the items showing {@code badge} right now, sorted by name; empty while off. */
    public static List<String> names(HomeCraftManagement plugin, Badge badge) {
        MarketSimService sim = live(plugin);
        if (sim == null) {
            return List.of();
        }
        return guard(plugin, () -> {
            List<String> out = new ArrayList<>();
            for (String id : sim.activeIds(badge)) {
                out.add(sim.displayName(id));
            }
            return out;
        }, List.of());
    }

    /** The name of the strongest season running now ({@code Harvest Time}); {@code ""} for none. */
    public static String seasonName(HomeCraftManagement plugin) {
        MarketSimService sim = live(plugin);
        if (sim == null) {
            return "";
        }
        return guard(plugin, () -> sim.season().map(a -> a.season().name()).orElse(""), "");
    }

    /**
     * The "Right now" entries: every HOT, UP, DEAL and DOWN item with its time left, then the
     * season. Empty while the market is off or quiet.
     */
    public static List<String> rightNow(HomeCraftManagement plugin, long now) {
        MarketSimService sim = live(plugin);
        if (sim == null) {
            return List.of();
        }
        return guard(plugin, () -> {
            List<String> out = new ArrayList<>();
            for (Badge badge : RIGHT_NOW) {
                for (String id : sim.activeIds(badge)) {
                    ItemStatus st = sim.status(id);
                    if (st == null || st.badge() != badge) {
                        continue;
                    }
                    String part = MarketLabels.rightNowPart(badge, sim.displayName(id), left(st, now));
                    if (!part.isEmpty()) {
                        out.add(part);
                    }
                }
            }
            sim.season().ifPresent(a -> {
                long ends = a.endsAt(plugin.clock().zone());
                String part = MarketLabels.rightNowSeason(a.season().name(),
                        ends > now ? Headlines.left(ends - now) : "");
                if (!part.isEmpty()) {
                    out.add(part);
                }
            });
            return out;
        }, List.of());
    }

    /** How long ago {@code e} became news, in words ({@code 3h ago}). */
    public static String age(MarketEvent e, long now) {
        return Headlines.ago(Math.max(0L, now - NewsFeed.newsTime(e)));
    }

    /** {@code e}'s headline, or its one-line summary (without colours) when it has none. */
    public static String headline(HomeCraftManagement plugin, MarketEvent e) {
        String h = MarketLabels.plain(e.headline());
        return h.isEmpty() ? MarketLabels.plain(MarketLabels.summary(e, subject(plugin, e))) : h;
    }

    /** {@code e} in one line with its age: {@code &a▲ &fWheat went up 22% &8(3h ago)}. */
    public static String summaryWithAge(HomeCraftManagement plugin, MarketEvent e, long now) {
        return MarketLabels.withAge(MarketLabels.summary(e, subject(plugin, e)), age(e, now));
    }

    /**
     * The word {@link MarketLabels#summary} needs for {@code e}: the item's name (UP/DOWN/HOT/DEAL),
     * its plural news name (WANTED), the season's name (SEASON) or the real-world name (REAL).
     */
    public static String subject(HomeCraftManagement plugin, MarketEvent e) {
        SimSettings settings = plugin.config().marketSim().settings();
        String id = e.itemId();
        switch (e.kind()) {
            case SEASON -> {
                String seasonId = e.tag() == null ? "" : e.tag().split(":", 2)[0];
                for (Season s : settings.seasons().list()) {
                    if (s.id().equalsIgnoreCase(seasonId)) {
                        return s.name();
                    }
                }
                return seasonId;
            }
            case REAL -> {
                for (RealSymbol r : settings.real().symbols()) {
                    if (r.item().equalsIgnoreCase(id == null ? "" : id) && !r.name().isBlank()) {
                        return r.name();
                    }
                }
                return itemName(plugin, id);
            }
            case WANTED -> {
                MarketItem item = id == null ? null : plugin.market().item(id);
                if (item == null) {
                    return itemName(plugin, id);
                }
                return ItemParams.of(item, plugin.config().marketSim().override(id), settings).plural();
            }
            default -> {
                return itemName(plugin, id);
            }
        }
    }

    /** An item's plain name as headlines use it; its id when the market no longer knows it. */
    private static String itemName(HomeCraftManagement plugin, String id) {
        if (id == null) {
            return "";
        }
        MarketSimService sim = plugin.marketSim();
        if (sim != null) {
            String name = guard(plugin, () -> sim.displayName(id), null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        MarketItem item = plugin.market().item(id);
        return item != null ? Headlines.name(item.label()) : id;
    }

    /**
     * Run a read against the sim. The sim promises cheap snapshot reads that never throw; if one
     * does anyway, the surface shows its quiet form (and the console says why) rather than a menu
     * failing to open or a display failing to paint.
     */
    private static <T> T guard(HomeCraftManagement plugin, Supplier<T> read, T fallback) {
        try {
            T value = read.get();
            return value != null ? value : fallback;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Live market read failed: " + ex);
            return fallback;
        }
    }
}
