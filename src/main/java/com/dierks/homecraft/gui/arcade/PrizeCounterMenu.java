package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.arcade.Keepers;
import com.dierks.homecraft.arcade.PrizeItems;
import com.dierks.homecraft.arcade.looks.LookRules;
import com.dierks.homecraft.arcade.looks.LookRules.Window;
import com.dierks.homecraft.config.PluginConfig.Collector;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.LookDao.Look;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Prize Counter (§3.9): the known-outcome half of the Arcade, one screen per tab.
 *
 * <p>Every tile puts the important words in its NAME — what it is and what it costs — because
 * Bedrock shows lore only on tap-and-hold. The lore says whether you can have it: "Need 12 more",
 * "1 left today", "You have this!". Expensive rows show even when you cannot afford them, with a
 * progress bar ("180 / 400"), because something to save up for is the point of a sink. Anything
 * costing {@value #CONFIRM_AT}+ tokens asks "are you sure?" first, because children misclick.
 *
 * <p>A collector's tile is a plain buy for yourself, like every other tile, so a child tapping her
 * own cap to look at it can never reach a present. Presents have their own entry, "Give a present"
 * at slot {@value #GIFT_SLOT} of the Cosmetics and Looks tabs ({@link GiftPickerMenu}), shown only while one can be
 * given.
 *
 * <p>The Looks tab (0.38) holds the hats, the caps (the collectors' first) and the golf balls. A hat or cap that
 * names its {@code material} is drawn as itself, the game's own head or the dyed cap, on both editions
 * ({@link PrizeItems#hatLook}); a golf ball is its block, and one you have says so in its name.
 *
 * <p>A rented look's tile (0.38, SHOP-V2-SPEC §3.12) says where its buyer stands: the price and "Yours for 90 days."
 * before a first buy, "Yours until 18 Jan" while it runs (a tap opens the look screen, {@link LookMenu}), "Buy again: 120
 * tokens (6 days left)" or "(ended)" while the window is open, and "Yours for good" when it is kept.
 *
 * <p>Row 0 is the balance (slot 0) and the eight tabs (1-8, {@link #TAB_SLOTS}); the Keepers tab is drawn only
 * while there are Keepers. The bottom row holds the page arrows (45, 53), the way out (49) and the present (47).
 *
 * <p>The Keepers tab (0.38, SHOP-V2-SPEC §5.1) is a shelf, not the grid: the Legend Crown on top (13), the three
 * keeper trails (20, 22, 24), the two keeper hats (30, 32) and My Looks (40). One you don't have says its price and
 * buys through the confirm ("Kept for good. You can't give it away."); one you have says "Yours! Tap to wear it", and
 * a tap wears it (a hat, made again when it was lost) or switches it on (a trail).
 */
public final class PrizeCounterMenu extends Menu {

    /** At or above this price a purchase goes through a confirm screen. */
    static final int CONFIRM_AT = 100;
    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;
    /** The tabs, in {@link PrizeTab} order: row 0 after the balance. */
    static final int[] TAB_SLOTS = {1, 2, 3, 4, 5, 6, 7, 8};
    /** The "Give a present" tile: the bottom row, between the Previous arrow (45) and the way out (49). */
    static final int GIFT_SLOT = 47;
    /** The Keepers shelf (§5.1): each Keeper's slot. The Royal Trail comes with the Crown and has none. */
    static final Map<String, Integer> KEEPER_SLOTS = Map.of(Keepers.CROWN, 13, Keepers.AURORA, 20,
            Keepers.STARDUST, 22, Keepers.FROST, 24, Keepers.WIZARD, 30, Keepers.KNIGHT, 32);
    /** The Keepers shelf's way to My Looks. */
    static final int KEEPER_MY_LOOKS = 40;
    /** "31 Oct": the last day a collector is on sale. */
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final Player player;
    private final Runnable back;
    private final PrizeTab tab;
    private final int page;
    /** The holiday's theme tonight, read once a build (0.38.2): a long firework show's tile says so. */
    private com.dierks.homecraft.arcade.HolidayShows.Theme theme;

    public PrizeCounterMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        this(plugin, player, back, PrizeTab.BOOSTS, 0);
    }

    public PrizeCounterMenu(HomeCraftManagement plugin, Player player, Runnable back, PrizeTab tab, int page) {
        super(plugin);
        this.player = player;
        this.back = back;
        this.tab = tab == null ? PrizeTab.BOOSTS : tab;
        this.page = Math.max(0, page);
        init(54, Text.of("&5&lPrize Counter &8· &5" + this.tab.label()));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 9; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        int tokens = plugin.tokens().balance(player.getUniqueId());
        theme = com.dierks.homecraft.arcade.FireworkShows.themeToday(plugin);
        set(0, Menus.icon(Material.SUNFLOWER, "&eYou have &6" + tokens + " tokens",
                "&7Everything here has a fixed price.",
                "&7No luck needed — you see what you get."), null);

        PrizeTab[] tabs = PrizeTab.values();
        for (int i = 0; i < tabs.length && i < TAB_SLOTS.length; i++) {
            PrizeTab t = tabs[i];
            if (!tabShown(t, plugin.config().arcade())) {
                continue;
            }
            boolean current = t == tab;
            set(TAB_SLOTS[i], tabIcon(plugin, player, t, current),
                    current ? null : e -> new PrizeCounterMenu(plugin, player, back, t, 0).open(player));
        }
        if (giftTab(tab) && GiftPickerMenu.entryShown(plugin)) {
            set(GIFT_SLOT, Menus.icon(Material.CAKE, "&dGive a present", giftEntryLore()),
                    e -> new GiftPickerMenu(plugin, player, this::reopen).open(player));
        }

        if (tab == PrizeTab.KEEPERS) {
            keeperShelf(tokens);
            setWayOut();
            return;
        }
        List<Prize> rows = plugin.prizes().visible(player, tab);
        if (rows.isEmpty()) {
            set(22, Menus.icon(Material.BARRIER, "&7Nothing here yet", "&8Check back soon!"), null);
        }
        int from = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE && from + i < rows.size(); i++) {
            Prize p = rows.get(from + i);
            set(GRID_START + i, tile(p, tokens), e -> click(p));
        }
        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new PrizeCounterMenu(plugin, player, back, tab, page - 1).open(player));
        }
        if (from + GRID_SIZE < rows.size()) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new PrizeCounterMenu(plugin, player, back, tab, page + 1).open(player));
        }
        setWayOut();
    }

    /** Slot 49 is the way out and never an arrow (NavAnchorTest). */
    private void setWayOut() {
        if (back != null) {
            set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> back.run());
        } else {
            set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        }
    }

    // ---- the Keepers shelf (SHOP-V2-SPEC §5.1) -------------------------------------------------------

    /**
     * A Keeper tile's NAME (D32: the facts in the name): its price while you don't have it; once you do, "Yours! Tap to
     * wear it", or "✔ Wearing it" while it is the hat on your head or the trail that's on.
     */
    static String keeperName(String display, boolean owned, boolean on, int price) {
        if (!owned) {
            return display + " &7- &6" + price + " tokens";
        }
        return display + (on ? " &7- &a✔ Wearing it" : " &7- &aYours! Tap to wear it");
    }

    /** The shelf: each Keeper you have, or that is on sale; My Looks below them. */
    private void keeperShelf(int tokens) {
        boolean any = false;
        for (Prize p : plugin.config().arcade().prizes(PrizeTab.KEEPERS)) {
            Integer slot = KEEPER_SLOTS.get(p.id());
            if (slot == null) {
                continue; // the Royal Trail: it comes with the Crown
            }
            boolean owned = plugin.prizes().owned(player, p);
            if (!owned && !plugin.prizes().visibleTo(player, p)) {
                continue; // not on sale (arcade.keepers.on_sale), and not theirs
            }
            any = true;
            set(slot, keeperTile(p, owned, tokens), e -> keeperClick(p, owned));
        }
        if (!any) {
            set(22, Menus.icon(Material.BARRIER, "&7Nothing here yet", "&8Check back soon!"), null);
        }
        set(KEEPER_MY_LOOKS, Menus.icon(Material.ARMOR_STAND, "&dMy Looks", "&7Your hats, caps and trails."),
                e -> new MyLooksMenu(plugin, player, this::reopen).open(player));
    }

    /** Whether a Keeper you have is on now: the hat on your head, or the trail that's on. */
    private boolean keeperOn(Prize p) {
        if (p.type() == PrizeType.HAT) {
            return com.dierks.homecraft.arcade.looks.LookService.wearing(player, p.id());
        }
        var on = plugin.trails().current(player);
        return on != null && on.prize().id().equalsIgnoreCase(p.id());
    }

    private ItemStack keeperTile(Prize p, boolean owned, int tokens) {
        int price = p.costTokens();
        boolean on = owned && keeperOn(p);
        String name = keeperName(p.display(), owned, on, price);
        List<String> lore = new ArrayList<>();
        for (String line : p.description()) {
            lore.add("&7" + line);
        }
        lore.addAll(PrizeItems.keeperLines());
        if (!owned) {
            if (tokens < price) {
                lore.add("&cNeed " + (price - tokens) + " more tokens");
                lore.add("&7" + tokens + " / " + price + " " + bar(tokens, price));
            } else {
                lore.add("&eClick to buy");
            }
        }
        ItemStack look = PrizeItems.keeperLook(p, Keepers.variant(plugin.config().arcade().keepers(), p.id()), name,
                lore);
        if (look != null) {
            return look;
        }
        return Menus.glint(Menus.icon(p.icon().material(), name, lore.toArray(new String[0])), on);
    }

    /**
     * A Keeper tile's tap: one you have is worn ({@code LookService.wear}, made again when it was lost; the screen
     * repaints) or switched on (the screen closes so the burst is seen); one you don't goes through the confirm.
     */
    private void keeperClick(Prize p, boolean owned) {
        if (owned) {
            if (p.type() == PrizeType.HAT) {
                String why = plugin.looks() == null ? "You can't change your looks here." : plugin.looks().wear(player, p.id());
                if (why != null) {
                    player.sendMessage(Text.of("&c" + why));
                    Sounds.refused(player);
                }
                reopen();
            } else {
                player.closeInventory(); // so the burst is seen: Bedrock's chest screen covers the view
                player.sendMessage(Text.of(plugin.trails().select(player, p.id())));
            }
            return;
        }
        int price = p.costTokens();
        new ConfirmMenu(plugin, "&5Buy " + Text.plain(p.display()) + "?",
                keeperTile(p, false, plugin.tokens().balance(player.getUniqueId())),
                List.of("&7Costs &6" + price + " tokens&7.",
                        "&7You have &6" + plugin.tokens().balance(player.getUniqueId()) + "&7.",
                        "&7Kept for good. You can't give it away."),
                "&7Click to buy it.", () -> {
                    var r = plugin.prizes().buy(player, p, null, price);
                    if (!r.ok()) {
                        player.sendMessage(Text.of("&c" + r.error()));
                        Sounds.refused(player);
                        reopen();
                    } // bought: the counter closed for its moment (a Keeper is always a first buy)
                }, this::reopen).open(player);
    }

    /** The tabs that show "Give a present" (slot {@value #GIFT_SLOT}): where the presents are, Cosmetics and Looks. */
    static boolean giftTab(PrizeTab t) {
        return t == PrizeTab.COSMETICS || t == PrizeTab.LOOKS;
    }

    /**
     * Whether a tab's button is drawn, here and on the hub: every tab, except Keepers while there are none (the
     * catalogue is empty until the Keepers ship), so a child never taps into an empty shelf.
     */
    static boolean tabShown(PrizeTab t, com.dierks.homecraft.config.PluginConfig.Arcade arcade) {
        return t != PrizeTab.KEEPERS || (arcade != null && !arcade.prizes(PrizeTab.KEEPERS).isEmpty());
    }

    /** One tab's button, shared with the hub's Prize Counter row. Icon key: the tab's name. */
    static ItemStack tabIcon(HomeCraftManagement plugin, Player player, PrizeTab t, boolean current) {
        String blurb = switch (t) {
            case BOOSTS -> "Run fast, mine fast, see in the dark.";
            case HUNT -> "Radar and Lure for wild Minis.";
            case COSMETICS -> "Fireworks, trails and parties.";
            case LOOKS -> "Hats, caps and golf balls.";
            case PERKS -> "Extra homes and more.";
            case TROPHIES -> "Something big to save up for.";
            case MINIS -> "A Rare Card, filament, trade-ins.";
            case KEEPERS -> "Kept for good. One of each.";
        };
        return Menus.glint(ArcadeIcons.of(plugin, player, t.name().toLowerCase(java.util.Locale.ROOT),
                tabMaterial(t), (current ? "&a&l" : "&e") + t.label(), "&7" + blurb,
                current ? "&7You're here." : "&eClick to look"), current);
    }

    private static Material tabMaterial(PrizeTab t) {
        return switch (t) {
            case BOOSTS -> Material.SUGAR;
            case HUNT -> Material.COMPASS;
            case COSMETICS -> Material.FIREWORK_ROCKET;
            case LOOKS -> Material.LEATHER_HELMET;
            case PERKS -> Material.NAME_TAG;
            case TROPHIES -> Material.GOLD_BLOCK;
            case MINIS -> Material.AMETHYST_SHARD;
            case KEEPERS -> Material.NETHER_STAR;
        };
    }

    /** One row's tile: its name and price, then whether and why you can or can't have it. */
    private ItemStack tile(Prize p, int tokens) {
        if (p.rental() && plugin.looks() != null) {
            return rentalTile(p, tokens);
        }
        boolean owned = plugin.prizes().owned(player, p);
        String locked = plugin.prizes().lockedBecause(player, p);
        int left = plugin.prizes().left(player.getUniqueId(), p);
        boolean free = p.type() == PrizeType.TRADE_IN;
        int cost = plugin.prizes().price(player.getUniqueId(), p);
        boolean afford = free || tokens >= cost;

        String name = free ? p.display() : p.display() + " &7- &6" + cost + " tokens";
        if (p.type() == PrizeType.BALL && owned) {
            name = p.display() + " &7- &aYours for good"; // the facts in the NAME: Bedrock shows lore on a long press
        }
        List<String> lore = new ArrayList<>();
        for (String line : p.description()) {
            lore.add("&7" + line);
        }
        if (theme != null && com.dierks.homecraft.arcade.FireworkShow.themed(p)) {
            lore.add("&6" + theme.name() + " colours tonight!"); // a holiday window: its long shows are themed
        }
        if (p.collector() != null) {
            lore.addAll(collectorLines(p.collector()));
        }
        if (p.type() == PrizeType.HOME_SLOT && plugin.homes() != null) {
            // The result, for this viewer: what they have and what they'd have.
            int have = plugin.homes().total(player);
            int max = p.limit() != null ? p.limit().count() : 0;
            int bought = plugin.homes().bonus(player.getUniqueId());
            name = owned ? p.display() + " &7- &aOwned (" + bought + " of " + max + ")"
                    : p.display() + " &7(you'll have " + (have + 1) + ") &7- &6" + cost + " tokens";
            lore.add("&7You have &f" + have + (have == 1 ? " home" : " homes") + " &7now.");
        }
        String limit = plugin.prizes().limitText(player.getUniqueId(), p);
        boolean available = true;
        if (owned) {
            lore.add(p.type() == PrizeType.HOME_SLOT ? "&a✔ You've got every extra home there is!" : "&a✔ You have this!");
            available = false;
        } else if (locked != null) {
            lore.add("&7" + locked);
            available = false;
        } else if (left <= 0) {
            lore.add("&7None left " + com.dierks.homecraft.arcade.PrizeService.periodWords(p.limit() != null
                    ? p.limit().per() : com.dierks.homecraft.config.PluginConfig.LimitPer.WEEK) + ".");
            available = false;
        } else if (p.type() == PrizeType.PARTY && plugin.party() != null && plugin.party().running()) {
            lore.add("&dA party is on right now!");
            lore.add("&7Try again in a moment.");
            available = false;
        } else if (!afford) {
            lore.add("&cNeed " + (cost - tokens) + " more tokens");
            if (cost >= CONFIRM_AT) {
                lore.add("&7" + tokens + " / " + cost + " " + bar(tokens, cost));
            }
        } else {
            lore.add(free ? "&eClick to open" : "&eClick to buy");
        }
        if (limit != null && !owned) {
            lore.add("&e" + limit);
        }
        if (p.type() == PrizeType.BALL) {
            lore.add("&7Pick it on \"Pick your ball\" at golf.");
        }
        if (p.collector() != null && p.collector().look() != null) {
            // A collector cap is a dyed helmet, not a head, so it is the same picture on both editions.
            // Its untagged look, named before the look goes on, and no glint: one would hide the dye
            // and the trim, which are the point (a menu never shows a tagged item).
            return PrizeItems.capLook(p.collector().look(), name, lore);
        }
        ItemStack look = PrizeItems.hatLook(p, name, lore);
        if (look != null) {
            // A mob head or a cap from config (P3): the look itself on both editions, untagged. A cap gets no
            // glint, for the collector cap's reason; the game's own heads may have one.
            return p.extra().hatMaterial() == Material.LEATHER_HELMET ? look : Menus.glint(look, available && afford);
        }
        // A head only for Java players: Bedrock shows an unregistered custom head as a plain one.
        boolean heads = !com.dierks.homecraft.util.Bedrock.is(player);
        String texture = !p.icon().texture().isBlank() ? p.icon().texture()
                : (p.type() == PrizeType.HAT || p.type() == PrizeType.TROPHY) && p.texture() != null ? p.texture() : "";
        ItemStack icon = heads && !texture.isBlank() ? Heads.base(texture) : new ItemStack(p.icon().material());
        var meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            List<net.kyori.adventure.text.Component> l = new ArrayList<>();
            for (String line : lore) {
                l.add(Text.of(line));
            }
            meta.lore(l);
            icon.setItemMeta(meta);
        }
        return Menus.glint(icon, available && afford);
    }

    /**
     * The "Give a present" tile's two lines. They name no season, so they read true for whichever
     * collectors are on sale, and since 0.38 they cover the rented looks and the golf balls too (§3.13).
     */
    static String[] giftEntryLore() {
        return new String[] {"&7Buy a look, a trail or a ball", "&7for someone who's online."};
    }

    /**
     * A collector's lines after its description: the year it belongs to, that it is kept, and the last
     * day it is sold ("On sale until 31 Oct"). The year is in the name as well, for Bedrock's sake.
     */
    static List<String> collectorLines(Collector c) {
        List<String> out = new ArrayList<>();
        out.add(Collectors.collectorLine(c)); // as on the cap's own lore (PrizeItems.collectorLore)
        out.add("&aYours for good!");
        if (c.to() != null) {
            out.add("&7On sale until " + UNTIL.format(c.to()));
        }
        return out;
    }

    private static String bar(int have, int need) {
        int cells = 10;
        int filled = need <= 0 ? cells : (int) Math.min(cells, Math.floor((double) have / need * cells));
        StringBuilder sb = new StringBuilder("&8[");
        for (int i = 0; i < cells; i++) {
            sb.append(i < filled ? "&a|" : "&7|");
        }
        return sb.append("&8]").toString();
    }

    private void click(Prize p) {
        if (p.rental() && plugin.looks() != null) {
            clickLook(p);
            return;
        }
        switch (p.type()) {
            case TRADE_IN -> new TradeInMenu(plugin, player, this::reopen).open(player);
            case QUEST_REROLL -> new QuestRerollMenu(plugin, player, p, this::reopen).open(player);
            default -> {
                if (p.choosesColor()) {
                    new FilamentColorMenu(plugin, player, p, this::reopen).open(player);
                } else if (plugin.prizes().price(player.getUniqueId(), p) >= CONFIRM_AT) {
                    // The price the screen shows is the one a confirm pays: a second tap after the Arcade Trophy's
                    // price rose is refused, nothing charged (D30).
                    int shown = plugin.prizes().price(player.getUniqueId(), p);
                    new ConfirmMenu(plugin, "&5Buy " + Text.plain(p.display()) + "?",
                            tile(p, plugin.tokens().balance(player.getUniqueId())),
                            List.of("&7Costs &6" + shown + " tokens&7.",
                                    "&7You have &6" + plugin.tokens().balance(player.getUniqueId()) + "&7."),
                            "&7Click to buy it.", () -> buy(p, shown), this::reopen).open(player);
                } else {
                    buy(p, -1);
                }
            }
        }
    }

    private void buy(Prize p, int shown) {
        var r = plugin.prizes().buy(player, p, null, shown);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            Sounds.refused(player);
            reopen();
            return;
        }
        if (p.type() == PrizeType.PITY) {
            new RevealMenu(plugin, player, r, this::reopen).open(player);
            return;
        }
        player.sendMessage(Text.of("&a✔ You got " + r.label() + "&a!"));
        Sounds.paid(player);
        reopen();
    }

    private void reopen() {
        new PrizeCounterMenu(plugin, player, back, tab, page).open(player);
    }

    // ---- rented looks (SHOP-V2-SPEC §3.12) ------------------------------------------------------

    /**
     * A rented look's tile name (D32: the facts in the NAME): its price before a first buy, its last day while it runs,
     * the buy-again price and the days left (or "ended") while the window is open, "Yours for good" when kept.
     */
    static String rentalName(String display, Window w, int price, java.time.LocalDate lastDay, int daysLeft) {
        return switch (w) {
            case NEW -> display + " &7- &6" + price + " tokens";
            case LIVE -> display + " &7- &aYours until " + LookRules.until(lastDay);
            case OPEN -> display + " &7- &eBuy again: " + price + " tokens ("
                    + (daysLeft > 0 ? LookRules.days(daysLeft) + " left" : "ended") + ")";
            case KEPT -> display + " &7- &aYours for good";
        };
    }

    private ItemStack rentalTile(Prize p, int tokens) {
        long now = plugin.clock().nowMillis();
        Look row = plugin.looks().look(player.getUniqueId(), p.id());
        Window w = LookRules.window(row, now);
        int price = LookRules.price(p, w);
        java.time.LocalDate last = row == null ? null : LookRules.lastDay(row.expiresAt(), plugin.clock().zone());
        int days = row == null ? 0 : LookRules.daysLeft(row.expiresAt(), now);
        String name = rentalName(p.display(), w, price, last, days);
        List<String> lore = new ArrayList<>();
        for (String line : p.description()) {
            lore.add("&7" + line);
        }
        boolean buyable = w == Window.NEW || w == Window.OPEN;
        if (w == Window.NEW) {
            firstBuyLines(lore, p.days());
        } else if (w == Window.LIVE) {
            lore.add("&7You can buy it again from "
                    + LookRules.until(LookRules.opensOn(row.expiresAt(), plugin.clock().zone())) + ".");
            lore.add("&eClick to look");
        } else if (w == Window.KEPT) {
            lore.add("&eClick to look");
        }
        if (buyable) {
            if (tokens < price) {
                lore.add("&cNeed " + (price - tokens) + " more tokens");
                if (price >= CONFIRM_AT) {
                    lore.add("&7" + tokens + " / " + price + " " + bar(tokens, price));
                }
            } else {
                lore.add(w == Window.OPEN ? "&eClick to buy it again" : "&eClick to buy");
            }
        }
        ItemStack look = PrizeItems.hatLook(p, name, lore);
        if (look != null) {
            // A cap gets no glint (it would hide the dye); the game's own heads may have one.
            return p.extra().hatMaterial() == Material.LEATHER_HELMET ? look
                    : Menus.glint(look, buyable && tokens >= price);
        }
        boolean heads = !com.dierks.homecraft.util.Bedrock.is(player);
        String texture = !p.icon().texture().isBlank() ? p.icon().texture()
                : p.type() == PrizeType.HAT && p.texture() != null ? p.texture() : "";
        ItemStack icon = heads && !texture.isBlank() ? Heads.textured(texture, name, lore)
                : Menus.icon(p.icon().material(), name, lore.toArray(new String[0]));
        return Menus.glint(icon, buyable && tokens >= price);
    }

    /**
     * A rented tile's two lines before a first buy (§3.12), after the row's own: how long it lasts, and that it can be
     * bought again. The 0.38 hat and cap rows already say "Yours for 90 days.", so that line is not said twice.
     */
    static void firstBuyLines(List<String> lore, int days) {
        String lasts = "&7Yours for " + LookRules.days(Math.max(1, days)) + ".";
        if (!lore.contains(lasts)) {
            lore.add(lasts);
        }
        lore.add("&7Then you can buy it again.");
    }

    /** A rented tile's tap: buy (or buy again) through the confirm at 100+, or the look screen while it runs. */
    private void clickLook(Prize p) {
        Window w = plugin.looks().window(player.getUniqueId(), p);
        if (w == Window.LIVE || w == Window.KEPT) {
            new LookMenu(plugin, player, p.id(), this::reopen).open(player);
            return;
        }
        int price = LookRules.price(p, w);
        if (price >= CONFIRM_AT) {
            new ConfirmMenu(plugin, "&5Buy " + Text.plain(p.display()) + (w == Window.OPEN ? " again?" : "?"),
                    rentalTile(p, plugin.tokens().balance(player.getUniqueId())),
                    List.of("&7Costs &6" + price + " tokens&7.",
                            "&7You have &6" + plugin.tokens().balance(player.getUniqueId()) + "&7."),
                    "&7Click to buy it.", () -> buyLook(p, price), this::reopen).open(player);
        } else {
            buyLook(p, price);
        }
    }

    /**
     * Buy a rented look at the price its tile showed. The wardrobe says how it went: a first buy closes the counter
     * and plays its moment (on Bedrock the chest screen would hide it, red team O19); a renewal reopens it.
     */
    private void buyLook(Prize p, int shown) {
        boolean first = plugin.looks().firstBuy(player.getUniqueId(), p);
        var r = plugin.prizes().buy(player, p, null, shown);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
            Sounds.refused(player);
            reopen();
            return;
        }
        if (!first) {
            reopen();
        }
    }
}
