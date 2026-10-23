package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.arcade.Keepers;
import com.dierks.homecraft.arcade.PrizeItems;
import com.dierks.homecraft.arcade.TrailService;
import com.dierks.homecraft.arcade.looks.LookRules;
import com.dierks.homecraft.arcade.looks.LookService;
import com.dierks.homecraft.arcade.looks.LookService.CapWhere;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Heads;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * My Looks (SHOP-V2-SPEC §3.11, D13): every hat, cap and trail a player has, with its date, on one screen a child can
 * find her things on. One tap acts: a hat tile puts it on, a trail tile switches it on; slots 2 and 6 take the hat
 * off and switch the trail off (and back on: a stray tap is undone by one more, red team O6). The worn hat's, the
 * on trail's and an ended look's tile open the look screen ({@link LookMenu}: take it off, buy it again). Her
 * collector caps are listed too, with where each one is (red team O15).
 *
 * <pre>
 * row 0:  [2 on your head]  [4 My Looks]  [6 your trail]
 * row 1:  [9 Hats &amp; caps]  [10-17 hats]
 * row 2:  [18]             [19-26 hats]
 * row 3:  [27 Trails]      [28-35 trails]
 * row 4:  [36]             [37-44 trails]
 * row 5:  [45 prev]  [46 hide others]  [47 Get more looks]  [49 Back]  [51 Get more trails]  [53 next]
 * </pre>
 *
 * <p>Slot 46 is the hide-others switch (§5.6): other people's trails, Keeper marks and parties, shown or hidden for
 * this player only, one tap each way, and the screen stays open. It sits in the bottom row, away from the top row a
 * child taps first (red team O21).
 *
 * <p>16 hats and 16 trails a page; one page number pages both, and the arrows show when either has more. The facts
 * are in each tile's NAME (Bedrock shows lore only on a long press, D32), and while buy again is open the tile's stack
 * count is the days left (1-7), which both editions draw. A menu never shows a tagged item: each tile is an untagged
 * look.
 */
public final class MyLooksMenu extends Menu {

    static final int[] HAT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 17, 19, 20, 21, 22, 23, 24, 25, 26};
    static final int[] TRAIL_SLOTS = {28, 29, 30, 31, 32, 33, 34, 35, 37, 38, 39, 40, 41, 42, 43, 44};
    static final int HEAD = 2;
    static final int TRAIL = 6;
    static final int GET_LOOKS = 47;
    static final int GET_TRAILS = 51;
    /** Other people's trails and parties: shown or hidden, for this player only. */
    static final int HIDE_OTHERS = 46;

    private final Player player;
    private final Runnable back;
    private final int page;

    public MyLooksMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        this(plugin, player, back, 0);
    }

    MyLooksMenu(HomeCraftManagement plugin, Player player, Runnable back, int page) {
        super(plugin);
        this.player = player;
        this.back = back;
        this.page = Math.max(0, page);
        init(54, Text.of("&dMy Looks"));
    }

    // ---- what a tile is ---------------------------------------------------------------------

    /** Where a look stands, for its tile. */
    enum State {
        /** On the head, or the trail that's on. */
        WORN,
        /** Live, more than a week left. */
        LIVE,
        /** Live, buy again open (a week or less left). */
        OPEN,
        /** Ended in the last 30 days. */
        ENDED,
        /** Kept for good: a collector or a Keeper. */
        KEPT
    }

    /** What a tap on a tile does. */
    enum Tap { WEAR, SWITCH_ON, LOOK, CAP_ON, CAP_HINT }

    /** One tile: the row, its right (null for a collector cap, which is an item), whether it is a hat, its state. */
    record Entry(Prize prize, Look right, boolean hat, State state, CapWhere capWhere) {
        boolean collectorCap() {
            return hat && prize.collector() != null;
        }
    }

    /** A look's state now: worn or on, kept, live (open in its last week), or ended. */
    static State state(Look right, long now, boolean wornOrOn) {
        if (wornOrOn) {
            return State.WORN;
        }
        if (LookRules.kept(right, now)) {
            return State.KEPT;
        }
        if (!LookRules.live(right, now)) {
            return State.ENDED;
        }
        return right.expiresAt() - now <= LookRules.WINDOW_BEFORE ? State.OPEN : State.LIVE;
    }

    /**
     * One tap acts (red team O6): a hat that isn't worn goes on, a trail that isn't on switches on; the worn or on one,
     * or an ended one, opens the look screen. A collector cap goes on from the bag; anywhere else its tile says where.
     */
    static Tap tap(Entry e) {
        if (e.collectorCap()) {
            return e.capWhere() == CapWhere.BAG ? Tap.CAP_ON : Tap.CAP_HINT;
        }
        return switch (e.state()) {
            case WORN, ENDED -> Tap.LOOK;
            default -> e.hat() ? Tap.WEAR : Tap.SWITCH_ON;
        };
    }

    /** A tile's NAME (D32): the look's plain name, coloured and told by its state. */
    static String tileName(boolean hat, State s, String name, LocalDate lastDay, int daysLeft) {
        return switch (s) {
            case WORN -> "&a" + name + (hat ? " &a✔ on your head" : " &a✔ on");
            case LIVE -> "&f" + name + " &7- until " + LookRules.until(lastDay);
            case OPEN -> "&e" + name + " &e- " + LookRules.days(daysLeft) + " left";
            case ENDED -> "&7" + name + " &8- ended";
            case KEPT -> "&6" + name + " &7- yours for good";
        };
    }

    /** A collector cap's NAME: kept for good, and where it is (it is an item, never made again). */
    static String capName(String name, CapWhere where) {
        String at = switch (where) {
            case HEAD -> "on your head";
            case BAG -> "in your bag";
            case ENDER_CHEST -> "in your ender chest";
            case AWAY -> "not with you";
        };
        return "&6" + name + " &7- yours for good, " + at;
    }

    /** The stack count a tile is drawn with: the days left while buy again is open (1-7), else 1. */
    static int amount(State s, int daysLeft) {
        return s == State.OPEN ? Math.max(1, Math.min(7, daysLeft)) : 1;
    }

    /** Hats in order: worn, Keepers, collectors, rentals soonest to end, ended. */
    static final Comparator<Entry> HAT_ORDER = Comparator.comparingInt(MyLooksMenu::hatRank)
            .thenComparingLong(MyLooksMenu::endOrder);

    /** Trails in order: on, Royal, Keepers, collectors newest first, rentals soonest to end, ended. */
    static final Comparator<Entry> TRAIL_ORDER = Comparator.comparingInt(MyLooksMenu::trailRank)
            .thenComparingLong(MyLooksMenu::endOrder);

    private static int hatRank(Entry e) {
        if (e.state() == State.WORN || e.capWhere() == CapWhere.HEAD) {
            return 0;
        }
        if (e.prize().keeper()) {
            return 1;
        }
        if (e.prize().collector() != null) {
            return 2;
        }
        return e.state() == State.ENDED ? 4 : 3;
    }

    private static int trailRank(Entry e) {
        if (e.state() == State.WORN) {
            return 0;
        }
        if (e.prize().id().equalsIgnoreCase("keeper_trail_royal")) {
            return 1;
        }
        if (e.prize().keeper()) {
            return 2;
        }
        if (e.prize().collector() != null) {
            return 3;
        }
        return e.state() == State.ENDED ? 5 : 4;
    }

    /** Within a rank: collectors newest season first, rentals soonest to end, ended ones most recently ended first. */
    private static long endOrder(Entry e) {
        if (e.prize().collector() != null) {
            return -e.prize().collector().from().toEpochDay();
        }
        if (e.right() == null) {
            return 0;
        }
        return e.state() == State.ENDED ? -e.right().expiresAt() : e.right().expiresAt();
    }

    /** The hide-others switch's NAME (D32: the facts in the name): what it is now, and what a tap does. */
    static String hideName(boolean hiding) {
        return "&fOther people's trails and parties: " + (hiding ? "Hidden - tap to show" : "Shown - tap to hide");
    }

    /** Pages of 16 hats and 16 trails, one number for both; always at least one. */
    static int pages(int hats, int trails) {
        int most = Math.max(hats, trails);
        return Math.max(1, (most + HAT_SLOTS.length - 1) / HAT_SLOTS.length);
    }

    // ---- the screen ------------------------------------------------------------------------------

    private LookService looks() {
        return plugin.looks();
    }

    private ZoneId zone() {
        return plugin.clock().zone();
    }

    /** The hats she has: live and lately ended rentals, owned Keeper hats, owned collector caps. */
    List<Entry> hats(long now) {
        List<Entry> out = new ArrayList<>();
        Prize worn = looks().worn(player);
        for (Look l : looks().looks(player.getUniqueId())) {
            Prize p = plugin.config().arcade().prize(l.id());
            if (p == null || p.type() != PrizeType.HAT || !LookDao.HAT.equals(l.kind()) || p.collector() != null
                    || !LookRules.listed(l, now)) {
                continue;
            }
            boolean on = worn != null && worn.id().equalsIgnoreCase(p.id()) && LookRules.live(l, now);
            out.add(new Entry(p, l, true, state(l, now, on), null));
        }
        for (Prize cap : Collectors.ROWS) {
            if (cap.type() != PrizeType.HAT || !claimed(cap)) {
                continue;
            }
            CapWhere where = looks().capWhere(player, cap);
            out.add(new Entry(cap, null, true, State.KEPT, where));
        }
        out.sort(HAT_ORDER);
        return out;
    }

    /** "Owned" for a collector cap: its lifetime claim. A claim that can't be read lists nothing. */
    private boolean claimed(Prize cap) {
        try {
            return plugin.prizes().dao().purchases(player.getUniqueId(), cap.id(), "life") > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /** The trails she has: live, kept, or ended in the last 30 days. */
    List<Entry> trails(long now) {
        List<Entry> out = new ArrayList<>();
        for (Look l : looks().looks(player.getUniqueId())) {
            Prize p = plugin.config().arcade().prize(l.id());
            if (p == null || p.type() != PrizeType.TRAIL || !LookDao.TRAIL.equals(l.kind()) || !LookRules.listed(l, now)) {
                continue;
            }
            boolean on = l.enabled() && LookRules.live(l, now);
            out.add(new Entry(p, l, false, state(l, now, on), null));
        }
        out.sort(TRAIL_ORDER);
        return out;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        long now = plugin.clock().nowMillis();
        List<Entry> hats = hats(now);
        List<Entry> trails = trails(now);
        int pageCount = pages(hats.size(), trails.size());
        int p = Math.min(page, pageCount - 1);

        headTile(now);
        set(4, Menus.icon(Material.ARMOR_STAND, "&dMy Looks", "&7Your hats, caps and trails.",
                "&7Tap a hat to wear it,", "&7tap a trail to switch it on."), null);
        trailTile();

        set(9, Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, "&6Hats & caps", "&7Tap one to wear it."), null);
        set(27, Menus.icon(Material.PURPLE_STAINED_GLASS_PANE, "&dTrails", "&7Tap one to switch it on."), null);
        fill(HAT_SLOTS, hats, p, now, "&7No hats or caps yet");
        fill(TRAIL_SLOTS, trails, p, now, "&7No trails yet");

        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new MyLooksMenu(plugin, player, back, p - 1).open(player));
        }
        if (p < pageCount - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new MyLooksMenu(plugin, player, back, p + 1).open(player));
        }
        hideTile();
        set(GET_LOOKS, Menus.icon(Material.LEATHER_HELMET, "&eGet more looks", "&7Hats and caps",
                "&7at the Prize Counter."), e -> new PrizeCounterMenu(plugin, player, this::reopen, PrizeTab.LOOKS, 0)
                .open(player));
        set(GET_TRAILS, Menus.icon(Material.BLAZE_POWDER, "&eGet more trails", "&7Trails",
                "&7at the Prize Counter."), e -> new PrizeCounterMenu(plugin, player, this::reopen, PrizeTab.COSMETICS, 0)
                .open(player));
        // Slot 49 is the way out and never an arrow (NavAnchorTest).
        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private void fill(int[] slots, List<Entry> entries, int p, long now, String none) {
        if (entries.isEmpty()) {
            set(slots[0], Menus.icon(Material.GRAY_DYE, none, "&7Get one at the Prize Counter!"), null);
            return;
        }
        int from = p * slots.length;
        for (int i = 0; i < slots.length && from + i < entries.size(); i++) {
            Entry e = entries.get(from + i);
            set(slots[i], tile(e, now), click -> act(e));
        }
    }

    /** Slot 2: what is on her head, and one tap takes a key off. */
    private void headTile(long now) {
        Prize worn = looks().worn(player);
        if (worn != null) {
            Look right = looks().look(player.getUniqueId(), worn.id());
            String when = right == null ? "" : LookRules.kept(right, now) ? " (yours for good)"
                    : " (until " + LookRules.until(LookRules.lastDay(right.expiresAt(), zone())) + ")";
            ItemStack icon = look(worn, "&fOn your head: " + Text.plain(worn.display()) + when,
                    List.of("&eTap to take it off", "&7It stays in My Looks."));
            set(HEAD, icon, e -> {
                String why = looks().takeOff(player, worn.id());
                if (why != null) {
                    refuse(why);
                }
                refresh();
            });
            return;
        }
        // no key of hers on: a collector cap, or any other helmet, is still named (red-team finding BR-6)
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && !helmet.getType().isAir()) {
            Prize row = plugin.config().arcade().prize(com.dierks.homecraft.util.TokenPrizes.prizeId(helmet));
            boolean cap = row != null && row.collector() != null;
            String name = row != null ? Text.plain(row.display()) : itemName(helmet);
            List<String> lore = List.of("&7Your hats are below.");
            set(HEAD, cap ? look(row, headName(name, true), lore) : Menus.icon(helmet.getType(), headName(name, false),
                    lore.toArray(new String[0])), null);
            return;
        }
        set(HEAD, Menus.icon(Material.GLASS, "&7Nothing on your head", "&7Tap a hat below to wear it."), null);
    }

    /** Slot 2's NAME for a helmet that isn't a key of hers: a collector cap is hers for good. */
    static String headName(String name, boolean keptForGood) {
        return "&fOn your head: " + name + (keptForGood ? " &7(yours for good)" : "");
    }

    /** An item's shown name: its own, else its material's ("Iron Helmet"). */
    private static String itemName(ItemStack item) {
        try {
            var meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(meta.displayName());
            }
        } catch (RuntimeException ignored) {
            // the material's name then
        }
        String n = item.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : n.split(" ")) {
            if (!w.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return out.toString();
    }

    /** Slot 46: hide or show other people's trails, marks and parties, for her only; the screen stays open. */
    private void hideTile() {
        TrailService trails = plugin.trails();
        boolean hiding = trails.hidesOthers(player.getUniqueId());
        set(HIDE_OTHERS, Menus.icon(hiding ? Material.ENDER_PEARL : Material.ENDER_EYE, hideName(hiding),
                "&7Your own trail always shows to you."), e -> {
                    player.sendMessage(Text.of(trails.hideOthers(player, !hiding)));
                    refresh();
                });
    }

    /** Slot 6: the trail switch, off and back on (the one last on, as {@link TrailService#toggle} picks it). */
    private void trailTile() {
        TrailService trails = plugin.trails();
        TrailService.Owned on = trails.current(player);
        if (on != null) {
            set(TRAIL, Menus.glint(Menus.icon(Material.BLAZE_POWDER, "&dTrail: " + Text.plain(on.prize().display())
                    + " &aon - tap to turn it off"), true), e -> {
                        player.sendMessage(Text.of(trails.off(player)));
                        refresh();
                    });
            return;
        }
        TrailService.Owned last = trails.lastOn(player);
        if (last == null) {
            set(TRAIL, Menus.icon(Material.GRAY_DYE, "&7No trail yet", "&7Get one at the Prize Counter!"), null);
            return;
        }
        set(TRAIL, Menus.icon(Material.GUNPOWDER, "&7Trail: off - tap to turn "
                + Text.plain(last.prize().display()) + " back on"), e -> {
                    player.sendMessage(Text.of(trails.select(player, last.prize().id())));
                    refresh();
                });
    }

    private ItemStack tile(Entry e, long now) {
        String name = Text.plain(e.prize().display());
        List<String> lore = new ArrayList<>();
        for (String line : e.prize().description()) {
            lore.add("&7" + line);
        }
        int days = e.right() == null ? 0 : LookRules.daysLeft(e.right().expiresAt(), now);
        String title;
        if (e.collectorCap()) {
            title = capName(name, e.capWhere());
            lore.add(e.capWhere() == CapWhere.BAG ? "&eTap to wear it" : "&7Collectors are kept for good.");
        } else {
            LocalDate last = e.right() == null ? null : LookRules.lastDay(e.right().expiresAt(), zone());
            title = tileName(e.hat(), e.state(), name, last, days);
            lore.add(switch (tap(e)) {
                case WEAR -> "&eTap to wear it";
                case SWITCH_ON -> "&eTap to switch it on";
                default -> e.state() == State.ENDED ? "&eTap to buy it again" : "&eTap for more";
            });
        }
        ItemStack icon = look(e.prize(), title, lore);
        return Menus.count(icon, amount(e.state(), days));
    }

    /**
     * A look's picture, never a tagged item: a collector cap's look, a worn Keeper's look, a mob hat or a cap from its
     * row's material, an animal hat's textured head on Java (a plain head on Bedrock), else the row's icon (a
     * trail's, a Keeper trail's).
     */
    private ItemStack look(Prize p, String name, List<String> lore) {
        if (p.collector() != null && p.collector().look() != null) {
            return PrizeItems.capLook(p.collector().look(), name, lore);
        }
        if (p.keeper() && p.type() == PrizeType.HAT) {
            ItemStack keeper = PrizeItems.keeperLook(p, Keepers.variant(plugin.config().arcade().keepers(), p.id()),
                    name, lore);
            if (keeper != null) {
                return keeper; // the Keeper's own look, in the form the config picks
            }
        }
        ItemStack hat = PrizeItems.hatLook(p, name, lore);
        if (hat != null) {
            return hat;
        }
        if (p.type() == PrizeType.HAT && !p.keeper()) {
            String texture = !Bedrock.is(player) && p.texture() != null ? p.texture() : "";
            return Heads.textured(texture, name, lore);
        }
        Material m = p.icon() != null && p.icon().material() != null ? p.icon().material() : Material.PAPER;
        return Menus.icon(m, name, lore.toArray(new String[0]));
    }

    private void act(Entry e) {
        switch (tap(e)) {
            case WEAR -> {
                String why = looks().wear(player, e.prize().id());
                if (why != null) {
                    refuse(why);
                }
                refresh(); // the menu stays open: the tile now says it's on her head
            }
            case SWITCH_ON -> {
                player.closeInventory(); // so the burst is seen: Bedrock's chest screen covers the view
                player.sendMessage(Text.of(plugin.trails().select(player, e.prize().id())));
            }
            case LOOK -> new LookMenu(plugin, player, e.prize().id(), this::reopen).open(player);
            case CAP_ON -> {
                String why = looks().capOn(player, e.prize());
                if (why != null) {
                    refuse(why);
                }
                refresh();
            }
            case CAP_HINT -> {
                String why = looks().capOn(player, e.prize());
                player.sendMessage(Text.of("&7" + (why == null ? "It's on your head!" : why)));
            }
        }
    }

    private void refuse(String why) {
        player.sendMessage(Text.of("&c" + why));
        Sounds.refused(player);
    }

    private void reopen() {
        new MyLooksMenu(plugin, player, back, page).open(player);
    }
}
