package com.dierks.homecraft.gui.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ScoresMenu;
import com.dierks.homecraft.trade.ItemFacts;
import com.dierks.homecraft.trade.TradeService;
import com.dierks.homecraft.trade.TradeService.Tap;
import com.dierks.homecraft.trade.TradeSession;
import com.dierks.homecraft.trade.TradeSession.Entry;
import com.dierks.homecraft.trade.TradeSession.Side;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The trade window (SHOP-V2-SPEC §4.3, 54 slots), one per player over one shared {@link TradeSession}, so each
 * player sees their own side on the left:
 *
 * <pre>
 *   row 0:  [0 You] [1] [2 "Your offer"] [3] [4 STATUS] [5] [6 "&lt;name&gt;'s offer"] [7] [8 Them]
 *   row 1:  [9][10][11][12]   [13 |]   [14][15][16][17]
 *   row 2:  [18][19][20][21]  [22 |]   [23][24][25][26]
 *   row 3:  [27][28][29][30]  [31 |]   [32][33][34][35]
 *   row 4:  [36][37][38][39]  [40 |]   [41][42][43][44]
 *   row 5:  [45 Cancel] [46 -$] [47 Your $] [48 +$] [49 YOUR READY] [50 |] [51 Their $] [52 THEIR READY] [53 How]
 * </pre>
 *
 * <p><b>The table shows ghosts, never the real stack</b> ({@link ItemFacts#ghost}): a copy with every one of our
 * keys taken off, built empty for a container, saying where the real one is. The real stack stays in its owner's
 * bag until the swap.
 *
 * <p><b>Taps</b> ({@link #handlesRawClicks}: {@code MenuListener} has already cancelled each one, so nothing ever
 * moves by vanilla cursor handling): on a stack in your bag, the whole stack goes on (Java right click: one more;
 * Bedrock: one more when it is on already); on your ghost, it comes off (Java right click: one fewer; Bedrock: half
 * back, so she can give 3 of her 20 candy apples); on theirs, a hint. Every other click type is ignored, and one tap
 * a tick counts (Bedrock sends doubles). Armour and the off-hand aren't in the bag view, so they can't be offered
 * (the How tile says so). Every fact is in a tile's NAME: Bedrock shows lore only on a long press.
 */
public final class TradeMenu extends Menu implements TradeService.Window {

    /** Your table: 16 slots, a 4 x 4 grid on the left. */
    static final int[] MINE = {9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39};
    /** Their table, on the right. */
    static final int[] THEIRS = {14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44};
    /** The black line between the tables. */
    static final int[] DIVIDERS = {13, 22, 31, 40, 50};
    static final int YOU = 0;
    static final int YOUR_LABEL = 2;
    static final int STATUS = 4;
    static final int THEIR_LABEL = 6;
    static final int THEM = 8;
    static final int CANCEL = 45;
    static final int LESS = 46;
    static final int YOUR_MONEY = 47;
    static final int MORE = 48;
    static final int YOUR_READY = 49;
    static final int THEIR_MONEY = 51;
    static final int THEIR_READY = 52;
    static final int HOW = 53;
    /** Every button and label, for the layout test. */
    static final int[] BUTTONS = {YOU, YOUR_LABEL, STATUS, THEIR_LABEL, THEM, CANCEL, LESS, YOUR_MONEY, MORE,
            YOUR_READY, THEIR_MONEY, THEIR_READY, HOW};
    /** The money step: $1 → $10 → $100 → $1,000 → $1. */
    static final int[] STEPS = {1, 10, 100, 1000};

    private final TradeService.Trade trade;
    private final Side me;
    private final Player viewer;
    /** What a close does: the service's {@code closed} (a test's own for a window with no inventory). */
    private final java.util.function.Consumer<Player> closer;
    private int step = 1;
    private String hint;
    private long hintUntil;
    private int lastTapTick = Integer.MIN_VALUE;

    public TradeMenu(HomeCraftManagement plugin, TradeService.Trade trade, Side me) {
        super(plugin);
        this.trade = trade;
        this.me = me;
        this.viewer = trade.player(me);
        this.closer = p -> trade.service().closed(trade, p);
        init(54, Text.of("&5Trade with " + trade.name(me.other())));
    }

    /**
     * A window with no inventory of its own and only its close wired ({@code MenusBeforeInventoriesSaveTest}): the
     * shutdown path a trade window takes is every menu's ({@link Menu#closeAll}), and its close cancels the trade.
     */
    public TradeMenu(Side me, Player viewer, java.util.function.Consumer<Player> closer) {
        super(null);
        this.trade = null;
        this.me = me;
        this.viewer = viewer;
        this.closer = closer;
    }

    // ---- the service's window -------------------------------------------------------------------------

    @Override
    public void open() {
        if (viewer != null) {
            open(viewer);
        }
    }

    @Override
    public boolean showing() {
        return isOpenFor(viewer);
    }

    @Override
    public void repaint() {
        refresh();
    }

    @Override
    public void close() {
        if (isOpenFor(viewer)) {
            closeNow(viewer);
        }
    }

    @Override
    public void hint(String line) {
        hint = line;
        hintUntil = System.currentTimeMillis() + 3_000L;
        refresh();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isOpenFor(viewer) && System.currentTimeMillis() >= hintUntil) {
                refresh();
            }
        }, 61L);
    }

    @Override
    protected void onClose(Player player) {
        closer.accept(player);
    }

    // ---- painting ---------------------------------------------------------------------------------------

    @Override
    protected void build() {
        TradeSession<ItemStack> s = trade.session();
        long now = System.currentTimeMillis();
        boolean bedrock = Bedrock.is(viewer);
        String them = trade.name(me.other());
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int d : DIVIDERS) {
            set(d, Menus.icon(Material.BLACK_STAINED_GLASS_PANE, " "), null);
        }
        set(YOU, person(viewer, "&aYou", bedrock), null);
        Player other = trade.player(me.other());
        set(THEM, person(other, "&b" + them, bedrock), null);
        set(YOUR_LABEL, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&aYour offer"), null);
        set(THEIR_LABEL, Menus.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b" + them + "'s offer"), null);

        // the tables
        int i = 0;
        for (Map.Entry<Integer, Entry<ItemStack>> e : s.offer(me).entrySet()) {
            int bagSlot = e.getKey();
            set(MINE[i++], ghost(e.getValue(), viewer == null ? "your" : viewer.getName()),
                    ev -> tapGhost(ev, bagSlot));
        }
        i = 0;
        for (Entry<ItemStack> e : s.offer(me.other()).values()) {
            set(THEIRS[i++], ghost(e, them), ev -> trade.service().tapTheirs(trade, me, viewer));
        }

        set(STATUS, status(s, now, them), null);
        set(CANCEL, Menus.icon(Material.BARRIER, "&cCancel trade", "&7Nothing changes hands."),
                ev -> viewer.closeInventory());
        money(s);
        int mail = trade.service().toMailbox(trade, me);
        set(YOUR_READY, readyTile(s.ready(me), s.locked(now), s.armed(me, now), mail, true), ev -> {
            trade.service().tapReady(trade, me, viewer);
        });
        set(THEIR_READY, readyTile(s.ready(me.other()), s.locked(now), false, 0, false), null);
        set(HOW, how(bedrock), null);
    }

    /** A player's tile: a plain paper on Bedrock (a head there may draw plain), their head on Java. */
    private static ItemStack person(Player p, String name, boolean bedrock) {
        return bedrock || p == null ? Menus.icon(Material.PAPER, name) : ScoresMenu.playerHead(p, name);
    }

    private ItemStack ghost(Entry<ItemStack> e, String owner) {
        return ItemFacts.ghost(e.snapshot(), e.amount(), owner, ItemFacts.of(e.snapshot()));
    }

    private ItemStack status(TradeSession<ItemStack> s, long now, String them) {
        if (hint != null && now < hintUntil) {
            return Menus.icon(Material.BARRIER, hint.startsWith("&") ? hint : "&c" + hint);
        }
        if (s.phase() == TradeSession.Phase.COUNTDOWN) {
            int left = s.secondsLeft(now);
            return Menus.count(Menus.icon(Material.CLOCK, countdownName(left), "&7Both ready..."), left);
        }
        if (s.armed(me, now)) {
            List<Entry<ItemStack>> mine = new ArrayList<>(s.offer(me).values());
            String first = mine.isEmpty() ? plugin.economy().format(s.money(me))
                    : (mine.get(0).amount() > 1 ? mine.get(0).amount() + " " : "")
                    + ItemFacts.label(mine.get(0).snapshot());
            int more = mine.size() - 1 + (mine.isEmpty() || s.money(me) <= 0 ? 0 : 1);
            String name = goingName(them, first, mine.isEmpty() ? 0 : more);
            if (mine.isEmpty()) {
                return Menus.icon(Material.GOLD_INGOT, name);
            }
            ItemStack g = ghost(mine.get(0), viewer.getName());
            var meta = g.getItemMeta();
            if (meta != null) {
                meta.displayName(Text.of(name));
                g.setItemMeta(meta);
            }
            return g;
        }
        if (s.ready(me) && !s.ready(me.other())) {
            return Menus.icon(Material.CLOCK, "&eReady - waiting for " + them);
        }
        if (s.ready(me.other()) && !s.ready(me)) {
            return Menus.icon(Material.CLOCK, "&e" + them + " is ready - tap Ready when happy");
        }
        return Menus.icon(Material.CLOCK, "&7Put things on the table");
    }

    /** The money row: your amount and its steps, theirs to look at; greyed with no Vault, or with a prize (T3). */
    private void money(TradeSession<ItemStack> s) {
        String them = trade.name(me.other());
        set(THEIR_MONEY, Menus.icon(Material.GOLD_INGOT,
                "&6" + them + " gives " + plugin.economy().format(s.money(me.other()))), null);
        if (!trade.service().moneyOn()) {
            set(YOUR_MONEY, Menus.icon(Material.GRAY_DYE, "&7No money here"), null);
            return;
        }
        if (trade.service().prizeOnTable(trade)) {
            set(YOUR_MONEY, Menus.icon(Material.GRAY_DYE, "&7No money with Arcade prizes",
                    "&7Arcade prizes can't be sold."), null);
            return;
        }
        int dollars = STEPS[step];
        set(LESS, Menus.icon(Material.RED_DYE, "&c- $" + format(dollars)),
                ev -> trade.service().tapMoney(trade, me, viewer, -dollars));
        set(YOUR_MONEY, Menus.icon(Material.GOLD_INGOT, "&6You give " + plugin.economy().format(s.money(me)),
                "&7Tap to change the step (now $" + format(dollars) + ").",
                "&7You have " + plugin.economy().format(plugin.economy().balance(viewer)) + "."), ev -> {
            step = (step + 1) % STEPS.length;
            refresh();
        });
        set(MORE, Menus.icon(Material.LIME_DYE, "&a+ $" + format(dollars)),
                ev -> trade.service().tapMoney(trade, me, viewer, dollars));
    }

    private static ItemStack readyTile(boolean ready, boolean locked, boolean armed, int toMailbox, boolean mine) {
        Material m = locked && !ready ? Material.GRAY_CONCRETE : armed ? Material.YELLOW_CONCRETE
                : ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        String name = mine ? readyName(ready, locked, armed, toMailbox) : theirReadyName(ready);
        return Menus.icon(m, name);
    }

    private static ItemStack how(boolean bedrock) {
        if (bedrock) {
            return Menus.icon(Material.BOOK, howName(true),
                    "&7Tap a thing on your side to take half of it back.", "&7Both tap Ready, then 3, 2, 1.");
        }
        return Menus.icon(Material.BOOK, howName(false),
                "&7Click your things to put them on the table;", "&7right-click for one more.",
                "&7Click them on the table to take them back;", "&7right-click for one fewer.",
                "&7Both press Ready, then 3, 2, 1.",
                "&7Armour and the off-hand: put them in your bag first.");
    }

    // ---- the names (pure, pinned by TradeMenuLayoutTest) -------------------------------------------------

    /**
     * Your Ready tile's NAME (§4.3): in the change lock "Look again...", a give-away's first tap "Give it away? Tap
     * again", else ready or not, with "(N to Mailbox)" when something you'd get won't fit.
     */
    static String readyName(boolean ready, boolean locked, boolean armed, int toMailbox) {
        String mail = toMailbox > 0 ? " &e(" + toMailbox + " to Mailbox)" : "";
        if (locked && !ready) {
            return "&7Look again...";
        }
        if (armed) {
            return "&eGive it away? Tap again";
        }
        return (ready ? "&a&lREADY ✓" : "&cNot ready - tap when happy") + mail;
    }

    /**
     * The How tile's NAME. On Bedrock the name is what she sees (the lore needs a long press), and the book itself does
     * nothing, so it points at her things on the table, where a tap takes half back (red-team finding DJ-6).
     */
    static String howName(boolean bedrock) {
        return bedrock ? "&eTap your things to add. Tap them on the table to take half back." : "&eHow to trade";
    }

    /** Their Ready tile's NAME. */
    static String theirReadyName(boolean ready) {
        return ready ? "&a&lREADY ✓" : "&cNot ready yet";
    }

    /** The status tile during the countdown. */
    static String countdownName(int secondsLeft) {
        return "&eTrading in " + secondsLeft + "...";
    }

    /** The status tile while a give-away waits for its second tap: the first stack leaving. */
    static String goingName(String to, String first, int more) {
        return "&eGoing to " + to + ": " + first + (more > 0 ? " (+" + more + " more)" : "");
    }

    /** "1,000". */
    static String format(int dollars) {
        return String.format(java.util.Locale.ROOT, "%,d", dollars);
    }

    // ---- taps -------------------------------------------------------------------------------------------

    /** A tap on a stack in your bag: what it does (§4.3). Every click type but these four is ignored. */
    static Tap bagTap(ClickType click, boolean bedrock, boolean onTable) {
        if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
            return bedrock && onTable ? Tap.ONE_MORE : Tap.WHOLE;
        }
        if (!bedrock && (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT)) {
            return Tap.ONE_MORE;
        }
        return Tap.NONE;
    }

    /** A tap on your own ghost: off (Java), half back (Bedrock), one fewer (a Java right click). */
    static Tap ghostTap(ClickType click, boolean bedrock) {
        if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
            return bedrock ? Tap.HALVE : Tap.TAKE_OFF;
        }
        if (!bedrock && (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT)) {
            return Tap.ONE_FEWER;
        }
        return Tap.NONE;
    }

    /** A button takes a plain left or right click, nothing else (no number keys, no drops, no double clicks). */
    static boolean buttonTap(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.SHIFT_LEFT || click == ClickType.RIGHT
                || click == ClickType.SHIFT_RIGHT;
    }

    @Override
    protected boolean handlesRawClicks() {
        return true;
    }

    @Override
    protected void onRawClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null || !buttonTap(event.getClick())) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        if (tick == lastTapTick) {
            return; // one tap a tick (Bedrock sends doubles)
        }
        lastTapTick = tick;
        boolean bedrock = Bedrock.is(viewer);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            int slot = event.getSlot();
            if (slot < 0 || slot > 35) {
                return; // the bag's 36 slots only: armour and the off-hand aren't in this view
            }
            Tap tap = bagTap(event.getClick(), bedrock, trade.session().entry(me, slot) != null);
            trade.service().tapBag(trade, me, viewer, slot, tap);
            return;
        }
        super.onRawClick(event); // the tile's own handler
    }

    private void tapGhost(InventoryClickEvent event, int bagSlot) {
        trade.service().tapGhost(trade, me, viewer, bagSlot, ghostTap(event.getClick(), Bedrock.is(viewer)));
    }
}
