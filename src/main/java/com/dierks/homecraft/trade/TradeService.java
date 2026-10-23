package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.storage.TradeDao;
import com.dierks.homecraft.trade.TradeSession.Cause;
import com.dierks.homecraft.trade.TradeSession.Entry;
import com.dierks.homecraft.trade.TradeSession.Side;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Items;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.Whereabouts;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * The trade window (SHOP-V2-SPEC §4, OA23, D21): two players, one shared {@link TradeSession}, one window each.
 * This class keeps the open trades, runs their clock (the 10-tick sync, the countdown and its check each second, the
 * swap at 0), cancels them cleanly for every reason one ends, and does the swap on the live server through
 * {@link TradeSwap}.
 *
 * <p><b>Nothing leaves a bag before both are Ready and the countdown ends.</b> A table holds references to bag slots
 * ({@link TradeSession.Entry}); the windows show ghosts; the real stacks move only inside {@link TradeSwap#run}, in one
 * tick. So a close, a quit, a death, a teleport, a world or game-mode change or a server stop just cancels: there is
 * nothing to give back, and nothing was persisted.
 *
 * <p><b>Money and an Arcade prize never go in one trade</b> (T3): checked at every add, at every money tap, at Ready
 * and on the real stacks at the swap. Tokens have no field at all.
 */
public final class TradeService implements TradeListener.Trades {

    /** Who may trade at all ({@code plugin.yml}, default true). */
    public static final String PERMISSION = "hcm.trade.use";
    /** How often, in ticks, an open trade re-reads the bag slots its tables point at (§4.4). */
    static final int SYNC_TICKS = 10;
    /** How long a hint stays on a window's status tile. */
    static final long HINT_MS = 3_000L;
    /** The {@code game_prefs} key for whether a player takes trade requests (default on; {@code /trade on|off}). */
    public static final String PREF = "trade.requests";
    /** How often a waiting request reminds its target, in seconds. */
    static final int REMIND_SECONDS = 10;

    /** One trade window, as the service sees it: the menu, or a test's stand-in. */
    public interface Window {
        /** Open it for its player. */
        void open();

        /** Its player is looking at it right now ({@code Menu.isOpenFor}). */
        boolean showing();

        /** Paint it again (the session changed, a second went by, a lock or a question ran out). */
        void repaint();

        /** Close it (a no-op once it isn't showing). */
        void close();

        /** Show a short line on its status tile (a refusal's words), for a few seconds. */
        void hint(String line);
    }

    /**
     * The Bedrock "Trade?" screen as the service knows it: the request it asks about, so the screen closes on its own
     * when that request runs out, is taken back or is called off (a Yes on a gone request only says so).
     */
    public interface AskScreen {
        /** The {@link TradeRequests.Request#id()} it asks about. */
        long requestId();
    }

    /** Whether an open view whose top belongs to {@code holder} is the "Trade?" screen for request {@code id}. */
    static boolean asksAbout(Object holder, long id) {
        return holder instanceof AskScreen s && s.requestId() == id;
    }

    /** One open trade: the session, both players, both windows, and the clock's bookkeeping. */
    public final class Trade {
        private final TradeSession<ItemStack> session;
        private final String nameA;
        private final String nameB;
        private final String world;
        private final long openedAt;
        private Window windowA;
        private Window windowB;
        private int ticks;
        private int shownSeconds = -1;
        private String paintedKey = "";

        Trade(Player a, Player b, long now) {
            PluginConfig.Trade cfg = plugin.config().trade();
            this.session = new TradeSession<>(a.getUniqueId(), b.getUniqueId(), cfg.changeLockMs(),
                    cfg.countdownSeconds());
            this.nameA = a.getName();
            this.nameB = b.getName();
            this.world = a.getWorld().getName();
            this.openedAt = now;
        }

        public TradeSession<ItemStack> session() {
            return session;
        }

        public TradeService service() {
            return TradeService.this;
        }

        public String name(Side s) {
            return s == Side.A ? nameA : nameB;
        }

        /** The player on this side, if they are still online. */
        public Player player(Side s) {
            Player p = Bukkit.getPlayer(session.player(s));
            return p != null && p.isOnline() ? p : null;
        }

        public Window window(Side s) {
            return s == Side.A ? windowA : windowB;
        }

        /** Both windows are showing. */
        public boolean bothShowing() {
            return windowA != null && windowB != null && windowA.showing() && windowB.showing();
        }
    }

    private final HomeCraftManagement plugin;
    private final TradeDao dao;
    private final TradeStore store;
    private final PrizeDao prizes;
    /** Each trading player → their trade (both players map to the same one). */
    private final Map<UUID, Trade> trades = new HashMap<>();
    private final TradeRequests requests = new TradeRequests();
    private final GamesDao prefs;
    /** Requests whose "Trade?" screen has been shown (once each: Esc is not an answer, and it never pops again). */
    private final Set<Long> screenShown = new HashSet<>();
    /** Builds each player's trade window (the menus). */
    private BiFunction<Trade, Side, Window> windows;
    /** Opens the Bedrock "Trade?" screen. */
    private BiConsumer<Player, TradeRequests.Request> askScreen;
    private BukkitTask task;
    private int clock;
    /** The one WARN for a server whose player files aren't where a save is checked. */
    private boolean saveUnchecked;

    public TradeService(HomeCraftManagement plugin, TradeDao dao, DeliveryDao deliveries, PrizeDao prizes,
                        GamesDao prefs) {
        this.plugin = plugin;
        this.dao = dao;
        this.prizes = prizes;
        this.prefs = prefs;
        this.store = new TradeStore(plugin.database(), dao, plugin.deliveries(), deliveries,
                new TradeStore.MiniOwners() {
                    @Override
                    public List<UUID> move(List<com.dierks.homecraft.mini.MiniService.MiniMove> moves, long now)
                            throws SQLException {
                        return plugin.miniService().tradeOwners(moves, now);
                    }

                    @Override
                    public boolean back(String uid, UUID before, UUID receiver, long soldAt) throws SQLException {
                        return plugin.miniService().tradeBack(uid, before, receiver, soldAt);
                    }
                }, prizes);
    }

    public TradeStore store() {
        return store;
    }

    /** The screens: each player's trade window, and the Bedrock "Trade?" screen. */
    public void screens(BiFunction<Trade, Side, Window> windows, BiConsumer<Player, TradeRequests.Request> askScreen) {
        this.windows = windows;
        this.askScreen = askScreen;
    }

    public TradeRequests requests() {
        return requests;
    }

    public PluginConfig.Trade settings() {
        return plugin.config().trade();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    // ---- the life of the service ----------------------------------------------------------------------

    /**
     * The JVM-wide flag a server start sets once its check has run, so a plugin reload (a new class loader, the same
     * JVM, players online with live bags) is told from a server start (red-team finding R4FA-4).
     */
    static final String BOOT_CHECKED = "homecraft.trades.bootChecked";

    /**
     * At enable: where the bags of each trade that didn't finish are (read from their marks before anyone joins, at a
     * server start only; red-team findings TCS-2, TCS-3, R4FA-4; a finished trade is never read: red-team findings
     * AW-1, AW-2), one WARN for each that waits (§4.6; nothing is settled on its own: red-team finding TCR4-WORTH-1);
     * then the log is pruned ({@code trade.log_keep_days}; a PENDING row never is, nor any row after the oldest one:
     * red-team finding SETTLE-PRUNE-3), after the check, so a server that was down past it still reads the trades just
     * before it stopped (red-team findings TCR4-6, NT-2); and the clock.
     */
    public void start() {
        boolean boot = System.getProperty(BOOT_CHECKED) == null;
        System.setProperty(BOOT_CHECKED, "true"); // first: a reload never reads live bags, even after a failed start
        int keep = settings().logKeepDays();
        try {
            store.check(plugin.getLogger()::warning, marks(), boot, this::balanceNow);
            if (keep > 0) {
                int gone = dao.prune(now() - keep * 86_400_000L);
                if (gone > 0) {
                    plugin.getLogger().info("Trades: pruned " + gone + " log row(s) older than " + keep + " days.");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Trades: could not read the trade log at start: " + e.getMessage());
        }
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    /** At disable: every trade is cancelled ({@code Menu.closeAll} already closed the windows), the clock stops. */
    public void stop() {
        for (Trade t : open()) {
            cancel(t, Cause.STOP, null);
        }
        requests.clear();
        screenShown.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---- open trades --------------------------------------------------------------------------------------

    /** The trade this player is in, or null. */
    public Trade tradeOf(UUID player) {
        return trades.get(player);
    }

    public boolean trading(UUID player) {
        return trades.containsKey(player);
    }

    /** Every open trade, once each. */
    private List<Trade> open() {
        return new ArrayList<>(new LinkedHashSet<>(trades.values()));
    }

    /**
     * Start a trade between {@code a} (who asked) and {@code b} (who said yes), both already checked, and open both
     * windows in this same tick. If either didn't open (another plugin cancelled it), the trade is cancelled and both
     * are told.
     *
     * @param windows builds each player's window
     */
    public Trade begin(Player a, Player b, BiFunction<Trade, Side, Window> windows) {
        Trade t = new Trade(a, b, now());
        trades.put(a.getUniqueId(), t);
        trades.put(b.getUniqueId(), t);
        t.windowA = windows.apply(t, Side.A);
        t.windowB = windows.apply(t, Side.B);
        t.windowA.open();
        t.windowB.open();
        if (!t.bothShowing()) {
            cancel(t, Cause.NOT_OPENED, null);
            String line = "&cThe trade window couldn't open - nothing changed hands.";
            a.sendMessage(Text.of(line));
            b.sendMessage(Text.of(line));
        }
        return t;
    }

    /**
     * Call a trade off (§4.5): only before it swaps. Nothing has left a bag, so nothing is given back; the other
     * player is told who ended it and why, and both windows close. A trade that had something on the table is
     * logged CANCELLED with its summaries only.
     *
     * @param by the player whose close, quit, death, teleport or change it was (null: the server)
     */
    public void cancel(Trade t, Cause cause, Player by) {
        if (t == null || !t.session.cancel(cause)) {
            return;
        }
        forget(t);
        Side bySide = by == null ? null : t.session.side(by.getUniqueId());
        String who = bySide == null ? null : t.name(bySide);
        logCancelled(t, who == null ? cause.words() : who + " " + cause.words());
        for (Side s : Side.values()) {
            Player p = t.player(s);
            if (p == null) {
                continue;
            }
            if (cause == Cause.STOP) {
                p.sendMessage(Text.of("&7The trade stopped with the server - nothing changed hands."));
            } else if (cause != Cause.NOT_OPENED) {
                p.sendMessage(Text.of(s == bySide ? "&7Trade cancelled - nothing changed hands."
                        : "&e" + (who == null ? "The trade" : who) + " &7" + (who == null ? "was cancelled"
                        : cause.words()) + " - nothing changed hands."));
            }
            if (s != bySide || (cause != Cause.CLOSED && cause != Cause.QUIT && cause != Cause.KICKED)) {
                closeLater(t.window(s), cause);
            }
        }
    }

    /** Close a window on the next tick (never inside another close), or now at a stop. */
    private void closeLater(Window w, Cause cause) {
        if (w == null) {
            return;
        }
        if (cause == Cause.STOP || !plugin.isEnabled()) {
            w.close();
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (w.showing()) {
                w.close();
            }
        });
    }

    private void forget(Trade t) {
        trades.remove(t.session.player(Side.A), t);
        trades.remove(t.session.player(Side.B), t);
    }

    /** A window closed (Esc, Cancel, another screen): the trade is cancelled, unless it is swapping or over. */
    public void closed(Trade t, Player player) {
        if (t != null && t.session.active()) {
            cancel(t, Cause.CLOSED, player);
        }
    }

    /** {@code player} quit, died, teleported away, changed world or game mode: their trade (if any) is cancelled. */
    @Override
    public void ended(Player player, Cause cause, String detail) {
        Trade t = trades.get(player.getUniqueId());
        if (t != null) {
            if (detail != null) {
                plugin.getLogger().info("Trade between " + t.nameA + " and " + t.nameB + " cancelled: "
                        + player.getName() + " " + cause.words() + " (" + detail + "). Nothing moved.");
            }
            cancel(t, cause, player);
        }
    }

    @Override
    public boolean on() {
        return settings().enabled();
    }

    private void logCancelled(Trade t, String reason) {
        if (t.session.empty()) {
            return; // nothing was on the table: nothing to settle a "where did it go?" with
        }
        try {
            store.cancelled(new TradeDao.Trade(0, t.session.player(Side.A), t.nameA, t.session.player(Side.B),
                    t.nameB, t.world, t.openedAt, now(), TradeDao.CANCELLED, reason, "", "",
                    tableSummary(t.session, Side.A), tableSummary(t.session, Side.B), t.session.money(Side.A),
                    t.session.money(Side.B), 0));
        } catch (SQLException e) {
            plugin.getLogger().warning("Trades: could not log a cancelled trade: " + e.getMessage());
        }
    }

    /** "3 Diamond, Creeper Mini" for one side's table, from its snapshots. */
    static String tableSummary(TradeSession<ItemStack> s, Side side) {
        List<String> parts = new ArrayList<>();
        for (Entry<ItemStack> e : s.offer(side).values()) {
            String name = ItemFacts.label(e.snapshot());
            parts.add(e.amount() > 1 ? e.amount() + " " + name : name);
        }
        return String.join(", ", parts);
    }

    // ---- the clock ------------------------------------------------------------------------------------------

    /** Every tick: each open trade's sync, countdown and repaint; once a second, the requests. */
    void tick() {
        long now = now();
        if (++clock % 20 == 0) {
            try {
                requestTick(now);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Trade requests hit an error.", e);
            }
        }
        for (Trade t : open()) {
            try {
                tick(t, now);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Trade between " + t.nameA + " and " + t.nameB
                        + " hit an error, so it was called off (nothing moved).", e);
                cancel(t, Cause.STOP, null);
            }
        }
    }

    private void tick(Trade t, long now) {
        TradeSession<ItemStack> s = t.session;
        if (!s.active()) {
            return;
        }
        t.ticks++;
        if (s.phase() == TradeSession.Phase.OPEN && t.ticks % SYNC_TICKS == 0) {
            sync(t, now);
        }
        if (s.phase() == TradeSession.Phase.COUNTDOWN) {
            int secs = s.secondsLeft(now);
            if (secs != t.shownSeconds) {
                t.shownSeconds = secs;
                if (secs > 0) {
                    countdownTick(t, now);
                } else {
                    swap(t, now);
                    return;
                }
            }
        } else {
            t.shownSeconds = -1;
        }
        String key = s.version() + "|" + s.locked(now) + "|" + s.armed(Side.A, now) + "|" + s.armed(Side.B, now)
                + "|" + t.shownSeconds;
        if (!key.equals(t.paintedKey)) {
            t.paintedKey = key;
            repaint(t);
        }
    }

    /** One second of the countdown: the check again (a failure is a change), a tick sound, a repaint. */
    private void countdownTick(Trade t, long now) {
        TradeRules.Failure f = verifyNow(t);
        if (f != null) {
            t.session.changed(now);
            tellBoth(t, f.message());
            if (f.warn() != null) {
                plugin.getLogger().warning(f.warn());
            }
            return;
        }
        for (Side s : Side.values()) {
            Player p = t.player(s);
            if (p != null) {
                try {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.2f);
                } catch (RuntimeException ignored) {
                    // cosmetic
                }
            }
        }
    }

    /**
     * The 10-tick sync (§4.4): each referenced bag slot is read again; an amount that fell is clamped, a stack that
     * is gone (or is something else now) comes off the table. Each is a change.
     */
    private void sync(Trade t, long now) {
        for (Side side : Side.values()) {
            Player p = t.player(side);
            if (p == null) {
                continue;
            }
            PlayerInventory inv = p.getInventory();
            for (Map.Entry<Integer, Entry<ItemStack>> e : new ArrayList<>(t.session.offer(side).entrySet())) {
                ItemStack real = inv.getItem(e.getKey());
                Entry<ItemStack> entry = e.getValue();
                boolean same = real != null && !real.getType().isAir() && real.isSimilar(entry.snapshot());
                Synced r = synced(ItemFacts.label(entry.snapshot()), entry.amount(), same,
                        same ? real.getAmount() : 0);
                if (r == null) {
                    continue;
                }
                if (r.amount() > 0) {
                    t.session.setAmount(side, e.getKey(), r.amount(), now);
                } else {
                    t.session.remove(side, e.getKey(), now);
                }
                p.sendMessage(Text.of(r.line()));
            }
        }
    }

    /** The sync's answer for one entry: what stays on the table (0: it comes off), and what its player is told. */
    record Synced(int amount, String line) {
    }

    /**
     * One entry against its slot (§4.4): null when the slot still holds at least the entry; the slot's amount when it
     * holds fewer (still on the table, lowered: red-team finding TW-SYNC-1); 0 when it is gone or something else.
     */
    static Synced synced(String name, int entry, boolean same, int real) {
        if (!same || real <= 0) {
            return new Synced(0, "&7Your " + name + " changed - it's off the table.");
        }
        if (real < entry) {
            return new Synced(real, "&7Your " + name + " changed - " + real + " on the table now.");
        }
        return null;
    }

    void repaint(Trade t) {
        for (Side s : Side.values()) {
            Window w = t.window(s);
            if (w != null && w.showing()) {
                w.repaint();
            }
        }
    }

    private void tellBoth(Trade t, String line) {
        for (Side s : Side.values()) {
            Player p = t.player(s);
            if (p != null) {
                p.sendMessage(Text.of(line.startsWith("&") ? line : "&c" + line));
                Sounds.refused(p);
            }
        }
    }

    // ---- the check (§4.6 step 1) -------------------------------------------------------------------------

    /** Where a player is, as the trade window asks it (§4.2 (1)). */
    TradeRules.Where where(Player p) {
        return new TradeRules.Where(plugin.sandbox().allowed(p.getWorld()),
                Whereabouts.gamesWorld(plugin, p.getWorld()), Whereabouts.homeFromGames(plugin, p), !p.isDead(),
                p.getGameMode());
    }

    /** The collector claims for what {@code giver} gives {@code receiver}, read from the database (fail closed). */
    TradeRules.Claims claims(UUID giver, UUID receiver) {
        Map<String, TradeRules.Claim> seen = new HashMap<>();
        return id -> seen.computeIfAbsent(id, k -> {
            try {
                return new TradeRules.Claim(prizes.purchases(giver, k, "life") > 0,
                        prizes.purchases(receiver, k, "life") > 0);
            } catch (SQLException e) {
                plugin.getLogger().warning("Trades: could not read a collector claim: " + e.getMessage());
                return TradeRules.Claim.NONE; // refused: ask an admin
            }
        });
    }

    /** The refusal context for what {@code side} puts on the table. */
    TradeRules.Context context(Trade t, Side side) {
        return new TradeRules.Context(settings().refuse(), t.name(side.other()),
                claims(t.session.player(side), t.session.player(side.other())));
    }

    /** Vault is there and {@code trade.money} is on. */
    public boolean moneyOn() {
        return settings().money() && plugin.economy().isEnabled();
    }

    /** The check, read now on both real bags. Null when the swap may go ahead. */
    TradeRules.Failure verifyNow(Trade t) {
        List<TradeRules.SideNow> sides = new ArrayList<>();
        Set<String> uids = new LinkedHashSet<>();
        for (Side s : Side.values()) {
            Player p = t.player(s);
            Window w = t.window(s);
            if (p == null) {
                sides.add(new TradeRules.SideNow(t.session.player(s), t.name(s), false, false, null, null, List.of(),
                        0, true, id -> TradeRules.Claim.NONE));
                continue;
            }
            PlayerInventory inv = p.getInventory();
            List<TradeRules.Held> held = TradeSwap.held(t.session.offer(s), inv::getItem, ITEMS);
            for (TradeRules.Held h : held) {
                if (h.real() != null) {
                    for (ItemFacts m : h.real().minis()) {
                        uids.add(m.miniUid());
                    }
                }
            }
            double money = t.session.money(s);
            sides.add(new TradeRules.SideNow(p.getUniqueId(), t.name(s), true, w != null && w.showing(),
                    p.getWorld().getName(), where(p), held, money, money <= 0 || plugin.economy().has(p, money),
                    claims(t.session.player(s), t.session.player(s.other()))));
        }
        Set<String> listed;
        try {
            listed = dao.listedUids(uids);
        } catch (SQLException e) {
            plugin.getLogger().warning("Trades: could not read the shops' listings: " + e.getMessage());
            return new TradeRules.Failure("That didn't work - nothing changed hands.", null);
        }
        return TradeRules.verify(sides.get(0), sides.get(1), moneyOn(), settings().refuse(), listed);
    }

    // ---- the window's taps (§4.3, §4.4, §4.10) ------------------------------------------------------------

    /**
     * What a tap does (§4.3). Java: a left click (or shift-left) on a bag stack puts the whole stack on the table, a
     * right click one more; on your ghost a left click takes it off, a right click one fewer. Bedrock (a tap arrives
     * as a left click, so a right click never comes; red team O13): a tap on a bag stack puts the whole stack on, or
     * one more when it is on the table already; a tap on your ghost halves it.
     */
    public enum Tap { WHOLE, ONE_MORE, TAKE_OFF, ONE_FEWER, HALVE, NONE }

    /** A tap on bag slot {@code slot} (0-35, the player's storage). */
    public void tapBag(Trade t, Side side, Player p, int slot, Tap tap) {
        TradeSession<ItemStack> s = t.session;
        if (!s.active() || tap == Tap.NONE) {
            return;
        }
        long now = now();
        ItemStack real = p.getInventory().getItem(slot);
        if (real == null || real.getType().isAir()) {
            return;
        }
        Entry<ItemStack> cur = s.entry(side, slot);
        if (cur != null && !real.isSimilar(cur.snapshot())) {
            s.remove(side, slot, now); // the slot holds something else now: start over with it
            cur = null;
        }
        if (cur == null) {
            String why = addRefusal(t, side, real);
            if (why != null) {
                if (!why.isEmpty()) {
                    hint(t, side, p, why);
                }
                return;
            }
        }
        int have = real.getAmount();
        int amount = amountAfter(tap, cur == null ? 0 : cur.amount(), have);
        if (cur != null && amount == cur.amount()) {
            hint(t, side, p, "&7That's all of it on the table.");
            return;
        }
        if (cur == null && s.offer(side).size() >= TradeSession.TABLE) {
            hint(t, side, p, "&7The table is full - take something off first.");
            return;
        }
        if (s.put(side, slot, real.clone(), amount, now)) {
            repaint(t);
        }
    }

    /** A tap on the other player's ghost: only a hint (it's theirs to change). */
    public void tapTheirs(Trade t, Side side, Player p) {
        hint(t, side, p, "&7That's " + t.name(side.other()) + "'s - only they can change it.");
    }

    /** A tap on your own ghost (the table entry from bag slot {@code slot}). */
    public void tapGhost(Trade t, Side side, Player p, int slot, Tap tap) {
        TradeSession<ItemStack> s = t.session;
        Entry<ItemStack> cur = s.entry(side, slot);
        if (!s.active() || cur == null) {
            return;
        }
        int next = amountAfter(tap, cur.amount(), cur.amount());
        if (next != cur.amount() && s.setAmount(side, slot, next, now())) {
            repaint(t);
        }
    }

    /**
     * How many are on the table after a tap (§4.3), pure: the whole stack, one more (up to what the slot holds), off,
     * one fewer, or half (rounding down, so 1 comes off). 0 means off the table.
     *
     * @param current how many are on the table now (0: none)
     * @param have    how many the bag slot holds
     */
    public static int amountAfter(Tap tap, int current, int have) {
        return switch (tap) {
            case WHOLE -> have;
            case ONE_MORE -> Math.min(have, current + 1);
            case TAKE_OFF -> 0;
            case ONE_FEWER -> Math.max(0, current - 1);
            case HALVE -> current / 2;
            case NONE -> current;
        };
    }

    /**
     * Why this stack can't go on {@code side}'s table now (§4.9, T3, one uid once), or null. An empty string is a
     * silent refusal (a ghost).
     */
    String addRefusal(Trade t, Side side, ItemStack real) {
        ItemFacts f = ItemFacts.of(real);
        TradeRules.Refusal r = TradeRules.refusal(f, context(t, side));
        if (r != null) {
            return r.message();
        }
        if (f != null && f.carriesPrize() && (t.session.money(Side.A) > 0 || t.session.money(Side.B) > 0)) {
            return "&c" + TradeRules.NO_MONEY_WITH_PRIZES;
        }
        Set<String> onTable = new HashSet<>();
        for (Side s : Side.values()) {
            for (Entry<ItemStack> e : t.session.offer(s).values()) {
                ItemFacts g = ItemFacts.of(e.snapshot());
                if (g != null) {
                    for (ItemFacts m : g.minis()) {
                        onTable.add(m.miniUid());
                    }
                }
            }
        }
        if (f != null) {
            for (ItemFacts m : f.minis()) {
                if (onTable.contains(m.miniUid())) {
                    return "&7" + TradeRules.MINI_TWICE;
                }
            }
        }
        return null;
    }

    /** Whether either table carries an Arcade prize (T3), read from the snapshots. */
    public boolean prizeOnTable(Trade t) {
        List<ItemFacts> a = new ArrayList<>();
        List<ItemFacts> b = new ArrayList<>();
        for (Entry<ItemStack> e : t.session.offer(Side.A).values()) {
            a.add(ItemFacts.of(e.snapshot()));
        }
        for (Entry<ItemStack> e : t.session.offer(Side.B).values()) {
            b.add(ItemFacts.of(e.snapshot()));
        }
        return TradeRules.prizeOnTable(a, b);
    }

    /** Something on a table can't be traded now (a claim changed, T3): its words, or null. */
    String tableProblem(Trade t) {
        for (Side s : Side.values()) {
            TradeRules.Context ctx = context(t, s);
            for (Entry<ItemStack> e : t.session.offer(s).values()) {
                TradeRules.Refusal r = TradeRules.refusal(ItemFacts.of(e.snapshot()), ctx);
                if (r != null) {
                    return r.silent() ? TradeRules.LOCKED : r.message();
                }
            }
        }
        if (prizeOnTable(t) && (t.session.money(Side.A) > 0 || t.session.money(Side.B) > 0)) {
            return "&c" + TradeRules.NO_MONEY_WITH_PRIZES;
        }
        return null;
    }

    /**
     * A tap on the money buttons (§4.3, §4.10): {@code delta} dollars more or less, never below 0 and capped at the
     * player's balance. Refused with no Vault, with {@code trade.money} off, and while a prize is on either table
     * (T3).
     */
    public void tapMoney(Trade t, Side side, Player p, double delta) {
        if (!t.session.active()) {
            return;
        }
        if (!moneyOn()) {
            hint(t, side, p, "&7No money here.");
            return;
        }
        if (prizeOnTable(t)) {
            hint(t, side, p, "&c" + TradeRules.NO_MONEY_WITH_PRIZES);
            return;
        }
        double cur = t.session.money(side);
        double next = Math.max(0, Math.min(cur + delta, plugin.economy().balance(p)));
        if (next == cur) {
            hint(t, side, p, delta > 0 ? "&7That's all your money." : "&7No money on the table.");
            return;
        }
        if (t.session.setMoney(side, next, now())) {
            repaint(t);
        }
    }

    /** A tap on your Ready (§4.4): the session decides; a refusal says why on the status tile. */
    public void tapReady(Trade t, Side side, Player p) {
        String problem = t.session.active() ? tableProblem(t) : null;
        Window other = t.window(side.other());
        TradeSession.Ready r = t.session.tapReady(side, now(), problem != null, other != null && other.showing());
        switch (r) {
            case LOCKED -> hint(t, side, p, "&7Look again - something just changed.");
            case EMPTY -> hint(t, side, p, "&7Put something on the table first.");
            case PROBLEM -> hint(t, side, p, problem);
            case OTHER_GONE -> hint(t, side, p, "&7" + t.name(side.other()) + "'s window isn't open.");
            default -> {
            }
        }
        repaint(t);
    }

    /** How many stacks {@code side} would get in their Mailbox if the swap ran now (the Ready tile's note). */
    public int toMailbox(Trade t, Side side) {
        Player p = t.player(side);
        if (p == null) {
            return 0;
        }
        List<ItemStack> incoming = new ArrayList<>();
        for (Entry<ItemStack> e : t.session.offer(side.other()).values()) {
            incoming.add(ITEMS.withAmount(e.snapshot(), e.amount()));
        }
        return TradeRoom.plan(new ArrayList<>(Arrays.asList(p.getInventory().getStorageContents())),
                TradeSwap.amounts(t.session.offer(side)), incoming, ITEMS).overflowStacks();
    }

    private void hint(Trade t, Side side, Player p, String line) {
        Window w = t.window(side);
        if (w != null) {
            w.hint(line);
        }
        try {
            p.sendActionBar(Text.of(line.startsWith("&") ? line : "&c" + line));
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        Sounds.refused(p);
    }

    /**
     * Why the picker can't ask this player now, for their tile ({@code not here}, {@code busy}, {@code trades off}),
     * or null when their tile is green. The asker's own checks are the ask's to refuse, when they tap.
     */
    public String pickerNote(Player viewer, Player target) {
        long now = now();
        boolean here = target.getWorld().equals(viewer.getWorld()) && where(target).ok()
                && (settings().maxDistance() <= 0
                || viewer.getLocation().distance(target.getLocation()) <= settings().maxDistance());
        boolean busy = trading(target.getUniqueId())
                || requests.pendingWithOther(target.getUniqueId(), viewer.getUniqueId(), now)
                || requests.coolingDown(viewer.getUniqueId(), target.getUniqueId(), now);
        return pickerNote(here, target.hasPermission(PERMISSION) && takesTrades(target.getUniqueId()), busy);
    }

    /** {@link #pickerNote(Player, Player)}'s order, pure. */
    public static String pickerNote(boolean here, boolean takesTrades, boolean busy) {
        if (!here) {
            return "not here";
        }
        if (!takesTrades) {
            return "trades off";
        }
        return busy ? "busy" : null;
    }

    // ---- asking (§4.2) -----------------------------------------------------------------------------------

    /** Whether the player takes trade requests ({@code game_prefs} {@value #PREF}, default on). */
    public boolean takesTrades(UUID player) {
        try {
            String v = prefs.pref(player, PREF);
            return v == null || !v.equalsIgnoreCase("off");
        } catch (SQLException e) {
            plugin.getLogger().warning("Trades: could not read a player's trade setting: " + e.getMessage());
            return false;
        }
    }

    /** {@code /trade on|off} and the PC's Trade site tile. */
    public void setTakesTrades(UUID player, boolean on) {
        try {
            prefs.setPref(player, PREF, on ? "on" : "off");
        } catch (SQLException e) {
            plugin.getLogger().warning("Trades: could not save a player's trade setting: " + e.getMessage());
        }
    }

    /**
     * The ask check (§4.2) for {@code asker} asking {@code target}: the first failure, in words for the asker, or
     * null. A target without {@code hcm.trade.use} doesn't take trades. {@code accepting} is the same check at a yes:
     * the request itself, its cooldown, the minute's count and the target's switch (they are answering) don't count
     * then.
     */
    public String askRefusal(Player asker, Player target, boolean accepting) {
        long now = now();
        boolean online = target != null && target.isOnline();
        boolean same = online && target.getWorld().equals(asker.getWorld());
        UUID a = asker.getUniqueId();
        UUID b = target == null ? null : target.getUniqueId();
        return TradeRules.askRefusal(new TradeRules.Ask(settings().enabled(), where(asker), online,
                online && asker.canSee(target), b != null && b.equals(a), online && target.hasMetadata("NPC"),
                target == null ? "" : target.getName(), same,
                same ? asker.getLocation().distance(target.getLocation()) : Double.MAX_VALUE, settings().maxDistance(),
                online ? where(target) : null, accepting || (b != null && target.hasPermission(PERMISSION)
                        && takesTrades(b)), trading(a),
                b != null && requests.pendingWithOther(a, b, now), b != null && trading(b),
                b != null && requests.pendingWithOther(b, a, now),
                !accepting && b != null && requests.coolingDown(a, b, now),
                accepting ? 0 : requests.sentLastMinute(a, now)));
    }

    /**
     * {@code /trade <name>}, the picker's tile and the crouch gesture: ask, or say yes when {@code target} asked
     * first (crouch-clicking back at someone who asked you accepts).
     */
    @Override
    public void gesture(Player asker, Player target) {
        ask(asker, target);
    }

    /** Ask {@code target} to trade (§4.2), or accept their request if they asked first. */
    public void ask(Player asker, Player target) {
        long now = now();
        if (target != null) {
            TradeRequests.Request in = requests.incoming(asker.getUniqueId(), now);
            if (in != null && in.from().equals(target.getUniqueId())) {
                accept(asker, in.fromName());
                return;
            }
            TradeRequests.Request out = requests.outgoing(asker.getUniqueId(), now);
            if (out != null && out.to().equals(target.getUniqueId())) {
                refuse(asker, "You already asked " + target.getName() + " - wait for an answer.");
                return;
            }
        }
        String why = askRefusal(asker, target, false);
        if (why != null) {
            refuse(asker, why);
            return;
        }
        int secs = settings().requestSeconds();
        TradeRequests.Request r = requests.send(asker.getUniqueId(), asker.getName(), target.getUniqueId(),
                target.getName(), now, secs);
        asker.sendMessage(Text.of("&7Asked &e" + target.getName() + " &7to trade. &8(" + typed()
                + " cancel takes it back)"));
        offer(target, r, secs);
    }

    /** The request in the target's chat (and on the Bedrock "Trade?" screen when it can open now). */
    private void offer(Player to, TradeRequests.Request r, int secs) {
        to.sendMessage(Text.of("&e" + r.fromName() + " &7wants to trade with you. &8(" + secs + " s)"));
        if (Bedrock.is(to)) {
            if (screenOpens(to)) {
                showScreen(to, r);
            } else {
                String typed = typed();
                to.sendMessage(Text.of("&7Type &e" + typed + " accept &7to trade, or &e" + typed + " deny&7."));
            }
        } else {
            String name = r.fromName();
            String typed = typed();
            to.sendMessage(Text.of("&a[Trade]").clickEvent(ClickEvent.runCommand(TradeRules.click("accept", name)))
                    .hoverEvent(HoverEvent.showText(Text.of("&7" + typed + " accept " + name)))
                    .append(Text.of("  "))
                    .append(Text.of("&c[No thanks]").clickEvent(ClickEvent.runCommand(TradeRules.click("deny", name)))
                            .hoverEvent(HoverEvent.showText(Text.of("&7" + typed + " deny " + name)))));
        }
        try {
            Sounds.received(to);
        } catch (RuntimeException | LinkageError ignored) {
            // cosmetic
        }
    }

    /**
     * The trade command as a player types it here: {@code /trade}, or {@code /hcm trade} when another plugin owns the
     * bare label (red-team finding CMD-4). Read each time, so it is right whichever plugin registered first.
     */
    public String typed() {
        boolean ours;
        try {
            org.bukkit.command.Command c = plugin.getServer().getCommandMap().getCommand("trade");
            ours = c == null || c == plugin.getCommand("trade");
        } catch (RuntimeException | LinkageError e) {
            ours = true;
        }
        return TradeRules.typed(ours);
    }

    /**
     * Whether the "Trade?" screen may open for her now (§4.2, red team D-m10, O12): no container of any kind open
     * (her view's top is her own crafting grid), an empty cursor, and not in a game.
     */
    private boolean screenOpens(Player p) {
        ItemStack cursor = p.getItemOnCursor();
        return TradeRules.askScreenOpens(p.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING,
                cursor == null || cursor.getType().isAir(), Whereabouts.inGameSession(plugin, p));
    }

    /**
     * Whether a trade window may open for this player now (red-team finding TW-1): nothing open but their own
     * crafting grid or the "Trade?" screen, and an empty cursor.
     */
    private boolean windowOpens(Player p) {
        ItemStack cursor = p.getItemOnCursor();
        var top = p.getOpenInventory().getTopInventory();
        return TradeRules.windowOpens(top.getType() == InventoryType.CRAFTING, top.getHolder(false) instanceof AskScreen,
                cursor == null || cursor.getType().isAir());
    }

    private void showScreen(Player to, TradeRequests.Request r) {
        if (askScreen != null && screenShown.add(r.id())) {
            askScreen.accept(to, r);
        }
    }

    /**
     * {@code /trade accept [name]}, the "Trade?" screen's Yes, a crouch-click back: the request goes at once (a
     * double accept runs once), every check runs again for both, then both windows open in the same tick. When one
     * of them has something open, the yes waits instead ({@link #waitsForFree}).
     */
    public boolean accept(Player to, String fromName) {
        long now = now();
        UUID from = null;
        if (fromName != null && !fromName.isBlank()) {
            TradeRequests.Request in = requests.incoming(to.getUniqueId(), now);
            if (in == null || !in.fromName().equalsIgnoreCase(fromName)) {
                refuse(to, "There's no trade request from " + fromName + " waiting for you.");
                return false;
            }
            from = in.from();
        }
        if (waitsForFree(to, from, now)) {
            return true;
        }
        TradeRequests.Request r = requests.accept(to.getUniqueId(), from, now);
        if (r == null) {
            refuse(to, "There's no trade request waiting for you.");
            return false;
        }
        Player asker = Bukkit.getPlayer(r.from());
        if (asker == null || !asker.isOnline()) {
            refuse(to, r.fromName() + " isn't online any more.");
            return false;
        }
        String why = askRefusal(to, asker, true);
        if (why != null) {
            refuse(to, why);
            asker.sendMessage(Text.of("&7The trade with &e" + to.getName() + " &7couldn't start right now."));
            return false;
        }
        if (windows == null) {
            refuse(to, "Trading isn't ready yet - try again in a moment.");
            return false;
        }
        begin(asker, to, windows);
        return true;
    }

    /**
     * A yes while a window can't open over what one of them has open (red-team finding TW-1, D-m10: a chest, a
     * crafting table, a carried stack, which would close and drop when the bag is full): the request stays with the
     * yes on it, both are told once, the "Trade?" screen closes, and {@link #requestTick} starts the trade the second
     * both are free. True when it waits; false to go on (both are free, or the accept refuses, and says why).
     */
    private boolean waitsForFree(Player to, UUID from, long now) {
        TradeRequests.Request in = requests.incoming(to.getUniqueId(), now);
        if (in == null || (from != null && !in.from().equals(from)) || windows == null) {
            return false;
        }
        Player asker = Bukkit.getPlayer(in.from());
        if (asker == null || !asker.isOnline()) {
            return false;
        }
        boolean askerFree = windowOpens(asker);
        boolean toFree = windowOpens(to);
        if ((askerFree && toFree) || askRefusal(to, asker, true) != null
                || requests.sayYes(to.getUniqueId(), in.from(), now) == null) {
            return false;
        }
        if (!in.yes()) {
            to.sendMessage(Text.of(toFree ? "&a✓ &7The trade starts when &e" + asker.getName()
                    + " &7closes what they have open." : "&a✓ &7Close what you have open and the trade starts."));
            if (!askerFree) {
                asker.sendMessage(Text.of("&e" + to.getName() + " &asaid yes! &7Close what you have open and the "
                        + "trade starts."));
            }
        }
        closeAskScreen(in);
        return true;
    }

    /** {@code /trade deny [name]} and the screen's No thanks: the request goes, the pair cools down. */
    public boolean deny(Player to, String fromName) {
        long now = now();
        UUID from = null;
        if (fromName != null && !fromName.isBlank()) {
            TradeRequests.Request in = requests.incoming(to.getUniqueId(), now);
            from = in != null && in.fromName().equalsIgnoreCase(fromName) ? in.from() : new UUID(0, 0);
        }
        TradeRequests.Request r = requests.deny(to.getUniqueId(), from, now);
        if (r == null) {
            refuse(to, "There's no trade request waiting for you.");
            return false;
        }
        to.sendMessage(Text.of("&7No trade."));
        tell(r.from(), "&e" + to.getName() + " &7said no thanks.");
        closeAskScreen(r);
        return true;
    }

    /** {@code /trade cancel}: the asker takes their request back (the pair cools down), or leaves their trade. */
    public boolean withdraw(Player from) {
        Trade t = trades.get(from.getUniqueId());
        if (t != null) {
            Window w = t.window(t.session.side(from.getUniqueId()));
            if (w != null && w.showing()) {
                w.close(); // its close cancels the trade, as Esc does
            } else {
                cancel(t, Cause.CLOSED, null);
            }
            return true;
        }
        TradeRequests.Request r = requests.withdraw(from.getUniqueId(), now());
        if (r == null) {
            refuse(from, "You have no trade request out.");
            return false;
        }
        from.sendMessage(Text.of("&7Trade request taken back."));
        tell(r.to(), "&e" + from.getName() + " &7called off the trade request.");
        closeAskScreen(r);
        return true;
    }

    /** Every request from or to {@code player} goes (a quit, a world change, a death, a game). */
    @Override
    public void dropRequests(Player player, String why) {
        for (TradeRequests.Request r : requests.dropAll(player.getUniqueId())) {
            UUID other = r.from().equals(player.getUniqueId()) ? r.to() : r.from();
            tell(other, "&7The trade request " + (r.from().equals(player.getUniqueId()) ? "from " : "to ")
                    + player.getName() + " was called off" + (why == null ? "." : " (" + why + ")."));
            closeAskScreen(r);
        }
    }

    /**
     * Once a second: lapsed requests, a game start, a yes waiting for both to be free (it starts the trade the second
     * they are), the asker's countdown, the target's reminder.
     */
    private void requestTick(long now) {
        for (TradeRequests.Request r : requests.expire(now)) {
            if (r.yes()) {
                tell(r.to(), "&7The trade with " + r.fromName() + " didn't start in time - nothing changed hands.");
                tell(r.from(), "&7The trade with " + r.toName() + " didn't start in time - nothing changed hands.");
            } else {
                tell(r.to(), "&7The trade request from " + r.fromName() + " ran out.");
                tell(r.from(), "&e" + r.toName() + " &7didn't answer.");
            }
            screenShown.remove(r.id());
            closeAskScreen(r);
        }
        for (TradeRequests.Request r : requests.all()) {
            Player from = Bukkit.getPlayer(r.from());
            Player to = Bukkit.getPlayer(r.to());
            if (from == null || to == null) {
                continue; // the quit handler drops it
            }
            if (Whereabouts.inGameSession(plugin, from) || Whereabouts.inGameSession(plugin, to)) {
                dropRequests(Whereabouts.inGameSession(plugin, from) ? from : to, "a game started");
                continue;
            }
            int left = r.secondsLeft(now);
            if (r.yes()) {
                boolean fromFree = windowOpens(from);
                boolean toFree = windowOpens(to);
                if (fromFree && toFree) {
                    accept(to, r.fromName()); // every check again, then both windows
                    continue;
                }
                try {
                    (fromFree ? to : from).sendActionBar(Text.of("&eClose what you have open to trade with "
                            + (fromFree ? r.fromName() : r.toName()) + " &8(" + left + ")"));
                } catch (RuntimeException ignored) {
                    // cosmetic
                }
                continue;
            }
            try {
                from.sendActionBar(Text.of("&7Waiting for " + r.toName() + "... (" + left + ")"));
            } catch (RuntimeException ignored) {
                // cosmetic
            }
            if (Bedrock.is(to) && !screenShown.contains(r.id()) && screenOpens(to)) {
                showScreen(to, r);
                continue;
            }
            long age = (now - r.at()) / 1000;
            if (age > 0 && age % REMIND_SECONDS == 0) {
                try {
                    to.sendActionBar(Text.of("&e" + r.fromName() + " &7wants to trade &8(" + left + ")"));
                } catch (RuntimeException ignored) {
                    // cosmetic
                }
            }
        }
        screenShown.removeIf(id -> requests.all().stream().noneMatch(r -> r.id() == id));
    }

    /** Close the target's "Trade?" screen if it is still the one for this request (it went: no answer is left). */
    private void closeAskScreen(TradeRequests.Request r) {
        Player p = Bukkit.getPlayer(r.to());
        try {
            if (p != null && p.isOnline() && asksAbout(p.getOpenInventory().getTopInventory().getHolder(false), r.id())) {
                p.closeInventory();
            }
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not close the trade question for " + r.toName() + ": " + e.getMessage());
        }
    }

    private void tell(UUID player, String line) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            p.sendMessage(Text.of(line));
        }
    }

    private static void refuse(Player p, String line) {
        p.sendMessage(Text.of(line.startsWith("&") ? line : "&c" + line));
        Sounds.refused(p);
    }

    // ---- the swap (§4.6) ---------------------------------------------------------------------------------

    /** Bukkit's items, as the swap and the checks read them. */
    static final TradeSwap.Items<ItemStack> ITEMS = new TradeSwap.Items<>() {
        @Override
        public int amount(ItemStack item) {
            return item == null ? 0 : item.getAmount();
        }

        @Override
        public boolean similar(ItemStack a, ItemStack b) {
            return a != null && b != null && a.isSimilar(b);
        }

        @Override
        public int maxStack(ItemStack item) {
            // the item's own limit (its max_stack_size component) and a player inventory's
            return Math.max(1, Math.min(item.getMaxStackSize(), 99));
        }

        @Override
        public ItemStack withAmount(ItemStack item, int amount) {
            ItemStack c = item.clone();
            c.setAmount(amount);
            return c;
        }

        @Override
        public String encode(ItemStack item) {
            return Items.toBase64(item);
        }

        @Override
        public ItemFacts facts(ItemStack item) {
            return ItemFacts.of(item);
        }
    };

    /** The console, as the swap writes to it. */
    private TradeSwap.Log log() {
        return new TradeSwap.Log() {
            @Override
            public void info(String line) {
                plugin.getLogger().info(line);
            }

            @Override
            public void warn(String line) {
                plugin.getLogger().warning(line);
            }

            @Override
            public void severe(String line) {
                plugin.getLogger().severe(line);
            }
        };
    }

    // ---- a trade that didn't finish (red-team findings TCS-2, TCS-3) -----------------------------------------

    /**
     * Each player's mark ({@code hcm:trade_seen}, {@code hcm:trade_seen_at}): the live data while they are online,
     * their saved file while they aren't ({@code OfflinePlayer.getPersistentDataContainer}, a read-only view of it).
     * An offline player the server has no saved data for (a missing or unreadable file, the wrong world folder) can't
     * be read, rather than reading as a bag from before every trade (red-team finding TCR-FA-1); one with no mark
     * carries when its file was written, so a file written since a trade (a reset player, a fresh world) is never
     * taken for a bag from before it, and live data with no mark can't say (red-team finding R4FA-1).
     */
    public TradeStore.Marks marks() {
        return player -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(player);
            var pdc = op.getPersistentDataContainer();
            return TradeStore.mark(pdc.get(Keys.TRADE_SEEN, PersistentDataType.LONG),
                    pdc.get(Keys.TRADE_SEEN_AT, PersistentDataType.LONG), () -> savedAt(op));
        };
    }

    /**
     * When an offline player's data was saved (its file's time), {@link Long#MAX_VALUE} when that can't be said (an
     * online player's live data, or a file kept somewhere this can't see), or null when there is no saved data.
     */
    private Long savedAt(OfflinePlayer op) {
        if (op.isOnline()) {
            return Long.MAX_VALUE;
        }
        SaveCheck.Stamp stamp = SaveCheck.stamp(playerFile(op.getUniqueId()));
        if (stamp != null) {
            return stamp.modified().toMillis();
        }
        return op.hasPlayedBefore() ? Long.MAX_VALUE : null;
    }

    /**
     * A player's file, where the server keeps it: {@code <level>/players/data/<uuid>.dat}, or {@code playerdata} on
     * an older layout (it may not be there).
     */
    private Path playerFile(UUID player) {
        Path level = plugin.getServer().getLevelDirectory();
        Path dir = level.resolve("players").resolve("data");
        if (!Files.isDirectory(dir) && Files.isDirectory(level.resolve("playerdata"))) {
            dir = level.resolve("playerdata");
        }
        return dir.resolve(player + ".dat");
    }

    /** A player's balance through Vault now, or null when it can't say (red-team finding TCR4-3). */
    private Double balanceNow(UUID player) {
        try {
            return plugin.economy() != null && plugin.economy().isEnabled()
                    ? plugin.economy().balance(Bukkit.getOfflinePlayer(player)) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Save a player and throw when it didn't reach the disk (red-team finding TCR-FA-3: vanilla only logs a failed
     * save). The file is the server's own ({@link #playerFile}); a server that keeps it elsewhere can't be checked,
     * and says so once.
     */
    void save(Player player) {
        Path file = playerFile(player.getUniqueId());
        Boolean reached = SaveCheck.reached(file, player::saveData);
        if (reached == null) {
            if (!saveUnchecked) {
                saveUnchecked = true;
                plugin.getLogger().warning("Trades: there are no player files in " + file.getParent() + ", so a"
                        + " trade can't check that a bag save reached the disk.");
            }
        } else if (!reached) {
            throw new IllegalStateException("the save didn't reach the disk (the server's \"Failed to save player"
                    + " data\" line says why)");
        }
    }

    /**
     * {@code /hcm trades settle <id> release|void [<player>] [confirm]} by {@code by}: what the store allows for where
     * the bags are ({@link TradeStore#release}, {@link TradeStore#voidTrade}). {@code player} names the one side whose
     * bag holds the trade, whose own offer goes to their Mailbox; {@code confirm} goes against the start's read.
     *
     * @return the lines for the admin
     */
    public List<String> settle(long id, boolean release, String player, boolean confirm, String by)
            throws SQLException {
        String how = release ? "release" : "void";
        TradeDao.Trade t = dao.byId(id).orElse(null);
        Side side = null;
        if (player != null && t != null) {
            side = player.equalsIgnoreCase(t.aName()) ? Side.A : player.equalsIgnoreCase(t.bName()) ? Side.B : null;
            if (side == null) {
                return List.of("&c" + player + " isn't in trade #" + id + " (" + t.aName() + " and " + t.bName()
                        + " are).");
            }
        }
        TradeStore.Settled r = release ? store.release(id, marks(), confirm)
                : store.voidTrade(id, marks(), side, confirm);
        String with = (side == null ? "" : " " + (side == Side.A ? t.aName() : t.bName()))
                + (confirm ? " confirm" : "");
        switch (r.outcome()) {
            case NOT_PENDING -> {
                return List.of("&cTrade #" + id + " isn't waiting to be settled (only a PENDING trade is).");
            }
            case REFUSED -> {
                return List.of("&cNot " + how + with + ": &7" + r.why() + ".");
            }
            case RELEASED -> {
                plugin.getLogger().info("Trade #" + id + " settled by " + by + ": release" + with + " (" + r.rows()
                        + " Mailbox row(s)).");
                List<String> out = new ArrayList<>();
                out.add("&aTrade #" + id + " is done: " + r.rows() + " Mailbox row(s) are ready to collect.");
                money(id, out);
                return out;
            }
            default -> {
                plugin.getLogger().info("Trade #" + id + " settled by " + by + ": void" + with + " (" + r.rows()
                        + " Mailbox row(s) deleted, " + r.returned() + " stack(s) back).");
                List<String> out = new ArrayList<>();
                out.add("&aTrade #" + id + " is void: " + r.rows() + " held Mailbox row(s) deleted"
                        + (side == null ? "." : ", and " + r.returned() + " stack(s) of what "
                        + (side == Side.A ? t.aName() : t.bName()) + " gave are in their Mailbox."));
                for (String note : r.notes()) {
                    plugin.getLogger().warning("Trade #" + id + ": " + note + ".");
                    out.add("&e" + note + ".");
                }
                money(id, out);
                return out;
            }
        }
    }

    /**
     * A settle moves no money back (Vault saves balances on its own schedule): the admin is told what each paid, and
     * each balance just before the swap and now (red-team finding TCR4-3).
     */
    private void money(long id, List<String> out) throws SQLException {
        TradeDao.Trade t = dao.byId(id).orElse(null);
        String line = t == null ? "" : moneyLine(t);
        if (!line.isEmpty()) {
            out.add("&7" + line);
        }
    }

    /** What a trade's money did, for a settle and {@code /hcm trades show} ("" when no money moved). */
    public String moneyLine(TradeDao.Trade t) throws SQLException {
        String m = TradeStore.money(t, dao.before(t.id()), this::balanceNow);
        return m.isEmpty() ? "" : "Money" + m.substring("; money".length()) + ".";
    }

    /** The countdown is over: the swap, once, in this tick. */
    void swap(Trade t, long now) {
        Player a = t.player(Side.A);
        Player b = t.player(Side.B);
        if (a == null || b == null) {
            // the quit handler cancels first; this is only a backstop
            cancel(t, Cause.QUIT, a == null ? null : a);
            return;
        }
        Map<Side, Player> players = new LinkedHashMap<>();
        players.put(Side.A, a);
        players.put(Side.B, b);
        TradeSwap.Bags<ItemStack> bags = new TradeSwap.Bags<>() {
            @Override
            public ItemStack get(Side s, int slot) {
                ItemStack it = players.get(s).getInventory().getItem(slot);
                return it == null || it.getType().isAir() ? null : it;
            }

            @Override
            public void set(Side s, int slot, ItemStack item) {
                players.get(s).getInventory().setItem(slot, item);
            }

            @Override
            public List<ItemStack> storage(Side s) {
                return new ArrayList<>(Arrays.asList(players.get(s).getInventory().getStorageContents()));
            }

            @Override
            public int add(Side s, ItemStack item) {
                int left = 0;
                // a copy: addItem changes the amount of the stack it is handed as it merges it
                for (ItemStack rest : players.get(s).getInventory().addItem(item.clone()).values()) {
                    left += rest.getAmount();
                }
                return left;
            }

            @Override
            public void save(Side s) {
                TradeService.this.save(players.get(s));
            }

            @Override
            public void mark(Side s, long id, long at) {
                PersistentDataContainer pdc = players.get(s).getPersistentDataContainer();
                pdc.set(Keys.TRADE_SEEN, PersistentDataType.LONG, id);
                pdc.set(Keys.TRADE_SEEN_AT, PersistentDataType.LONG, at);
            }
        };
        TradeSwap.Money money = new TradeSwap.Money() {
            @Override
            public boolean withdraw(Side s, double amount) {
                return plugin.economy().withdraw(players.get(s), amount);
            }

            @Override
            public boolean deposit(Side s, double amount) {
                return plugin.economy().deposit(players.get(s), amount);
            }

            @Override
            public Double balance(Side s) {
                return plugin.economy().isEnabled() ? plugin.economy().balance(players.get(s)) : null;
            }
        };
        TradeSwap.Result<ItemStack> r = new TradeSwap<>(bags, ITEMS, money, store, log()).run(t.session,
                new TradeSwap.Party(a.getUniqueId(), t.nameA), new TradeSwap.Party(b.getUniqueId(), t.nameB), t.world,
                t.openedAt, now, () -> verifyNow(t), claims(a.getUniqueId(), b.getUniqueId()),
                claims(b.getUniqueId(), a.getUniqueId()));
        switch (r.status()) {
            case NOT_NOW -> {
            }
            case FAILED -> {
                if (r.warn() != null) {
                    plugin.getLogger().warning(r.warn());
                }
                for (Side s : Side.values()) {
                    Player p = players.get(s);
                    String line = r.told().get(s);
                    p.sendMessage(Text.of(line.startsWith("&") ? line : "&c" + line));
                    Sounds.refused(p);
                }
                repaint(t);
            }
            case DONE -> finished(t, r, players);
        }
    }

    /** After a swap: both windows close, both players get their moment and the summary. */
    private void finished(Trade t, TradeSwap.Result<ItemStack> r, Map<Side, Player> players) {
        forget(t);
        for (Side s : Side.values()) {
            Window w = t.window(s);
            if (w != null) {
                w.close(); // SWAPPING (now DONE) makes the close a no-op for the session
            }
        }
        for (Side s : Side.values()) {
            Player p = players.get(s);
            List<ItemStack> got = r.got().getOrDefault(s, List.of());
            // what really moved (red-team finding NT-1): a payment whose deposit failed isn't named as paid
            double cash = r.paid().getOrDefault(s.other(), 0.0);
            double gave = r.paid().getOrDefault(s, 0.0);
            String gotText = gotText(got, cash > 0 ? plugin.economy().format(cash) : null);
            String gaveText = gotText(r.got().getOrDefault(s.other(), List.of()),
                    gave > 0 ? plugin.economy().format(gave) : null);
            try {
                p.showTitle(Title.title(Text.of("&aTraded!"), Text.of("&eYou got " + shortText(got, cash)),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600))));
            } catch (RuntimeException ignored) {
                // cosmetic
            }
            Sounds.paid(p);
            p.sendMessage(Text.of("&aTraded with " + t.name(s.other()) + "! &7You got: &f"
                    + (gotText.isEmpty() ? "nothing" : gotText) + "&7. You gave: &f"
                    + (gaveText.isEmpty() ? "nothing" : gaveText) + "&7."));
            String mail = mailLine(r, s);
            if (!mail.isEmpty()) {
                p.sendMessage(Text.of(mail));
            }
            String back = moneyBackLine(t.session.money(s), r.paid().getOrDefault(s, 0.0),
                    r.stuck().getOrDefault(s, 0.0), t.name(s.other()), r.id(), plugin.economy()::format);
            if (!back.isEmpty()) {
                p.sendMessage(Text.of(back));
            }
            int lost = r.unwritten().getOrDefault(s, 0);
            if (lost > 0) {
                p.sendMessage(Text.of("&c" + lost + (lost == 1 ? " thing" : " things") + " from the trade couldn't"
                        + " be sent to your Mailbox - an admin can find it under trade #" + r.id() + "."));
            }
            boolean minis = false;
            for (ItemStack g : got) {
                ItemFacts f = ItemFacts.of(g);
                minis |= f != null && !f.minis().isEmpty();
            }
            if (minis && plugin.achievements() != null) {
                plugin.achievements().onMinisReceived(p);
            }
        }
    }

    /**
     * What a payer reads when the money they put on the table didn't reach the other player (red-team finding NT-1):
     * it came back to them, or, when that failed too, they ask an admin, who finds it under the trade's number. ""
     * when it went through (or there was none).
     *
     * @param offered what they put on the table; {@code paid} what reached the other; {@code stuck} what neither
     *                reached them nor came back
     */
    static String moneyBackLine(double offered, double paid, double stuck, String other, long id,
                                java.util.function.DoubleFunction<String> format) {
        if (offered <= 0 || paid > 0) {
            return "";
        }
        if (stuck > 0) {
            return "&cThe " + format.apply(stuck) + " you paid didn't reach " + other + " and couldn't be paid back to"
                    + " you - ask an admin (trade #" + id + ").";
        }
        return "&eThe " + format.apply(offered) + " didn't reach " + other + ", so it went back to you.";
    }

    /**
     * The Mailbox line after a swap, or "" when nothing went there: only the rows really written count, and while
     * they are still HELD (the trade stays PENDING until an admin settles it) it says they are on their way, not
     * there (red-team finding TCS-5).
     */
    static String mailLine(TradeSwap.Result<?> r, Side s) {
        int mail = r.mailbox().getOrDefault(s, 0);
        if (mail <= 0) {
            return "";
        }
        String things = "&e✉ " + mail + (mail == 1 ? " thing" : " things") + " from the trade ";
        return r.held() ? things + "will reach your Mailbox once the trade is checked."
                : things + (mail == 1 ? "is" : "are") + " in your Mailbox (PC or Mailbox).";
    }

    /** "3 Diamond, Creeper Mini and $50.00". */
    static String gotText(List<ItemStack> got, String cash) {
        List<String> parts = new ArrayList<>();
        for (ItemStack g : got) {
            String name = ItemFacts.label(g);
            parts.add(g.getAmount() > 1 ? g.getAmount() + " " + name : name);
        }
        if (cash != null) {
            parts.add(cash);
        }
        return String.join(", ", parts);
    }

    /** The title's line: the first thing and how many more ("3 Diamond (+2 more)"). */
    private String shortText(List<ItemStack> got, double cash) {
        List<String> parts = new ArrayList<>();
        for (ItemStack g : got) {
            String name = ItemFacts.label(g);
            parts.add(g.getAmount() > 1 ? g.getAmount() + " " + name : name);
        }
        if (cash > 0) {
            parts.add(plugin.economy().format(cash));
        }
        if (parts.isEmpty()) {
            return "nothing";
        }
        return parts.get(0) + (parts.size() > 1 ? " (+" + (parts.size() - 1) + " more)" : "");
    }
}
