package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService.Outcome;
import com.dierks.homecraft.arcade.Keepers;
import com.dierks.homecraft.arcade.PrizeItems;
import com.dierks.homecraft.arcade.PrizeService;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.arcade.TrailService;
import com.dierks.homecraft.arcade.looks.LookRules.Area;
import com.dierks.homecraft.arcade.looks.LookRules.Decision;
import com.dierks.homecraft.arcade.looks.LookRules.Due;
import com.dierks.homecraft.arcade.looks.LookRules.Found;
import com.dierks.homecraft.arcade.looks.LookRules.Notice;
import com.dierks.homecraft.arcade.looks.LookRules.Placement;
import com.dierks.homecraft.arcade.looks.LookRules.Verdict;
import com.dierks.homecraft.arcade.looks.LookRules.Window;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.integration.EconomySandbox;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.TokenPrizes;
import com.dierks.homecraft.util.Whereabouts;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The wardrobe (SHOP-V2-SPEC §3): rented hats, caps and trails, and the keys that wear them.
 *
 * <p>The right is a {@code cosmetics_owned} row ({@link LookDao}); a hat's item is only a key bound to its owner, so
 * a renewal makes every copy work again, a lost key comes back free from My Looks, and an offline player needs
 * nothing at the end. What lives here:
 * <ul>
 *   <li><b>The cache</b>: each online player's rows, loaded at join and again every minute, updated by every write
 *       made here. A failed read leaves the player "not loaded", and nothing of theirs is ever deleted then.</li>
 *   <li><b>Buying</b> ({@link #purchase}): every check, then the right and the tokens in ONE transaction (D30), so
 *       a second confirm finds the right already extended and is refused before any charge; then the key, the
 *       trail and the moment.</li>
 *   <li><b>Wearing</b> ({@link #wear}, {@link #takeOff}): "Wear it" moves a key from the bag, the off-hand or the
 *       ender chest to the head, or makes one; taking it off deletes the key (it is in My Looks).</li>
 *   <li><b>Sweeps</b> ({@link #sweep}): 2 ticks after a join or a world change, every minute, and at once after a
 *       buy, a present, a wear or an admin change. Each key's verdict is {@link LookRules#plan}'s; keys inside
 *       bundles and shulker boxes are read too.</li>
 *   <li><b>The end</b>: a worn key comes off, a trail stops; a kept trail comes on in its place, or a collector cap
 *       from the bag goes on (red team O7). The row stays, so My Looks lists it as ended for 30 days.</li>
 *   <li><b>Heads-up notices</b> ({@link LookRules#due}): a title and a chat line 7 days and 1 day before the end and
 *       at it, held in games and Games worlds, 100 ticks after a join, and behind another title of ours. A present's
 *       giver gets one line when it is a week from ending.</li>
 *   <li><b>Keepers</b> ({@link #buyKeeper}, §5.4): the claim, the rights kept for good and the tokens in one
 *       transaction; a worn Keeper's key is made again by {@link #wear} with its number, rebuilt in place when the
 *       config picks its other form, and while it is on the head its mark puffs ({@link #marks}, §5.3a).</li>
 *   <li><b>Admin</b>: a free test look, an end moved, the list, the preview stand.</li>
 * </ul>
 */
public final class LookService {

    /** Every minute: the end of a run, the sweep and the heads-up. */
    static final long TICK = 1_200L;
    /** A join's sweep waits for the session recovery (+1 tick) and Multiverse-Inventories' profile. */
    static final long JOIN_SWEEP_DELAY = 2L;
    /** A join's heads-up waits so an achievement or present title isn't drawn over (red team O20). */
    static final long JOIN_NOTICE_DELAY = 100L;
    /** How long a heads-up title (and the "Yours!" one) is on screen, all told. */
    static final long TITLE_MS = 3_750L;
    /** A preview stand stays this long; a trail's preview runs for the first part of it. */
    static final long PREVIEW_TICKS = 600L;
    static final long PREVIEW_TRAIL_TICKS = 200L;
    /** The nil UUID: a preview copy is bound to nobody, so every sweep removes it as "not yours". */
    static final UUID NOBODY = new UUID(0, 0);
    /** A Keeper is kept for good: 36,500 days, the collectors' figure (more than {@link LookRules#KEPT} left). */
    static final long KEPT_MS = 36_500L * LookRules.DAY;

    static final String NOT_SAVED = "That couldn't be saved — nothing charged. Tell an admin.";
    static final String PRICE_CHANGED = "The price changed — look again.";
    static final String NOTHING_MADE = "That prize couldn't be made — nothing charged.";

    private final HomeCraftManagement plugin;
    private final LookDao dao;
    /** Each loaded player's rows, by id. A player with no entry is "not loaded". */
    private final Map<UUID, Map<String, Look>> cache = new HashMap<>();
    /** When each player's heads-ups may show: after the join's wait, and not while a title of ours is up. */
    private final NoticeGate gate = new NoticeGate();
    /**
     * The {@code game_prefs} key of a giver's lines waiting for their next join (red-team finding WARD-1: never only
     * in memory, so a restart keeps them).
     */
    static final String GIVER_LINES_PREF = "arcade.looks.giver_lines";
    /** A giver's lines that couldn't be saved, still sent at their next join if the server stays up. */
    private final Map<UUID, List<String[]>> giverLines = new HashMap<>();
    /** The kept trail that came on when a rented one ended, for its ENDED line: player → ended id → name. */
    private final Map<UUID, Map<String, String>> cameOn = new HashMap<>();
    /** The preview stands standing now. */
    private final Set<UUID> previewStands = new HashSet<>();
    /** Players whose mark is read again next tick ({@link #marksSoon}): one task a player however many clicks. */
    private final Set<UUID> marksPending = new HashSet<>();
    private BukkitTask task;

    public LookService(HomeCraftManagement plugin, LookDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public LookDao dao() {
        return dao;
    }

    /** The every-minute task: each online player's end, sweep and heads-up. */
    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                try {
                    tick(p);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("The looks check for " + p.getName() + " failed: " + e.getMessage());
                }
            }
        }, TICK, TICK);
    }

    /** Stop the task and take down every preview stand. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(previewStands)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        previewStands.clear();
    }

    private ZoneId zone() {
        return plugin.clock().zone();
    }

    private long now() {
        return plugin.clock().nowMillis();
    }

    // ---- the cache -------------------------------------------------------------------------------

    /** Read a player's rows into the cache; a failed read leaves them "not loaded" (nothing of theirs is deleted). */
    public boolean reload(UUID player) {
        try {
            Map<String, Look> rows = new LinkedHashMap<>();
            for (Look l : dao.looks(player)) {
                rows.put(l.id(), l);
            }
            cache.put(player, rows);
            return true;
        } catch (SQLException e) {
            cache.remove(player);
            plugin.getLogger().warning("Could not read the looks of " + player + ": " + e.getMessage());
            return false;
        }
    }

    public boolean loaded(UUID player) {
        return cache.containsKey(player);
    }

    /** One right: from the cache for a loaded player, else read now (null on a failed read, or none). */
    public Look look(UUID player, String id) {
        Map<String, Look> rows = cache.get(player);
        if (rows != null) {
            for (Look l : rows.values()) {
                if (l.id().equalsIgnoreCase(id)) {
                    return l;
                }
            }
            return null;
        }
        try {
            return dao.look(player, id);
        } catch (SQLException e) {
            return null;
        }
    }

    /** Every right of a player (cached when loaded, else read now; empty on a failed read). */
    public List<Look> looks(UUID player) {
        Map<String, Look> rows = cache.get(player);
        if (rows != null) {
            return List.copyOf(rows.values());
        }
        try {
            return dao.looks(player);
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** A rented row's window for this player now (§3.4). */
    public Window window(UUID player, Prize p) {
        return LookRules.window(look(player, p.id()), now());
    }

    /** What this player's next buy of a rented row costs: the buy-again price while their window is open. */
    public int price(UUID player, Prize p) {
        return LookRules.price(p, window(player, p));
    }

    /** Whether a buy now would be a first buy (closes the counter) rather than a renewal (reopens it). */
    public boolean firstBuy(UUID player, Prize p) {
        return LookRules.firstBuy(look(player, p.id()), now());
    }

    void drop(UUID player) {
        cache.remove(player);
        gate.drop(player);
        cameOn.remove(player);
    }

    /** A title of ours went up on this player's screen (an achievement, a present): a heads-up waits behind it. */
    public void titleShown(Player player, long ms) {
        if (player != null) {
            gate.titleShown(player.getUniqueId(), now() + ms);
        }
    }

    // ---- joining and leaving (LookListener) -----------------------------------------------------

    /**
     * A join: the rows, then the sweep and the heads-ups a few ticks on. Each task acts only while this join's own
     * connection is open ({@code isConnected}): Paper answers {@code isOnline} by UUID, so after a quick rejoin a task
     * of the first join would act on the old Player and send the giver's waiting lines to a closed connection
     * (red-team finding TT1-STALE-REJOIN). The rejoin's own tasks do it instead.
     */
    void joined(Player player) {
        UUID id = player.getUniqueId();
        reload(id);
        long from = gate.joined(id, now(), JOIN_NOTICE_DELAY * 50);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isConnected()) {
                ends(player);
                sweep(player);
            }
        }, JOIN_SWEEP_DELAY);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isConnected()) {
                gate.joinWaitOver(id, from); // this task's delay is the wait (red-team finding TT-1)
                notices(player);
                List<String[]> lines = new ArrayList<>(takeGiverLines(id));
                lines.addAll(giverLines.getOrDefault(id, List.of()));
                giverLines.remove(id);
                for (String[] l : lines) {
                    sendGiverLine(player, l[2], l[0], l[1]);
                }
            }
        }, JOIN_NOTICE_DELAY + 1);
    }

    void changedWorld(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isConnected()) {
                sweep(player);
            }
        }, JOIN_SWEEP_DELAY);
    }

    void quit(Player player) {
        drop(player.getUniqueId());
    }

    /** The minute's work for one player: the rows again, a run that ended, the sweep, the heads-up. */
    public void tick(Player player) {
        reload(player.getUniqueId());
        ends(player);
        sweep(player);
        notices(player);
    }

    // ---- buying (§3.5) -----------------------------------------------------------------------------

    /** What one purchase's transaction did. */
    public enum Status { DONE, ALREADY, PRICE_CHANGED, NOT_PAID }

    /** One purchase's transaction: what it did, the row before it, what it cost, and the run it wrote. */
    public record Committed(Status status, Look before, int cost, LocalDate lastDay, long expiresAt) {
    }

    /** A charge inside the purchase's transaction: false when the balance is short. Joins the transaction. */
    @FunctionalInterface
    public interface Spend {
        boolean spend(UUID payer, int cost, String detail) throws SQLException;
    }

    /** Thrown inside the transaction when the charge is refused: the right written before it rolls back. */
    private static final class NotPaid extends RuntimeException {
        NotPaid() {
            super(null, null, false, false);
        }
    }

    /**
     * The purchase as ONE transaction (D30): the receiver's row read again inside the lock (a second confirm ends
     * here: ALREADY, nothing charged), the price checked against the one the screen showed (PRICE_CHANGED, nothing
     * charged), the right written (a renewal adds its days to the last day and keeps the run's start; a present
     * remembers who paid), and the tokens taken; a refused charge rolls the right back (NOT_PAID). Static, with the
     * charge passed in, so every one of those is pinned by a test on a real SQLite.
     *
     * @param shownCost    the price the tile or the confirm screen showed (below 0: none to compare)
     * @param receiverName for a present's ledger line ("Bunny Hat for ...")
     */
    public static Committed commit(Database db, LookDao dao, Spend spend, UUID payer, UUID receiver, Prize p,
                                   int shownCost, long now, ZoneId zone, String receiverName) throws SQLException {
        boolean present = !payer.equals(receiver);
        try {
            return db.transaction(c -> {
                Look before = dao.look(receiver, p.id());
                Window w = LookRules.window(before, now);
                if (w == Window.LIVE || w == Window.KEPT) {
                    return new Committed(Status.ALREADY, before, 0, null, 0);
                }
                int cost = LookRules.price(p, w);
                if (shownCost >= 0 && cost != shownCost) {
                    return new Committed(Status.PRICE_CHANGED, before, cost, null, 0);
                }
                LocalDate last = LookRules.newLastDay(before, Math.max(1, p.days()), now, zone);
                long end = LookRules.endOf(last, zone);
                dao.write(receiver, p.id(), kind(p), end, LookRules.since(before, now),
                        present ? payer.toString() : "");
                if (!spend.spend(payer, cost, detail(p, w, present, receiverName))) {
                    throw new NotPaid();
                }
                return new Committed(Status.DONE, before, cost, last, end);
            });
        } catch (NotPaid e) {
            return new Committed(Status.NOT_PAID, null, 0, null, 0);
        }
    }

    /** A rented row's {@code kind}: HAT for a hat or cap, TRAIL for a trail. */
    static String kind(Prize p) {
        return p.type() == PrizeType.HAT ? LookDao.HAT : LookDao.TRAIL;
    }

    /** The ledger line: "Bunny Hat", a buy again "Bunny Hat (again)", a present "Bunny Hat for <name>". */
    static String detail(Prize p, Window w, boolean present, String receiverName) {
        String name = Text.plain(p.display());
        if (present) {
            return name + " for " + (receiverName == null ? "someone" : receiverName);
        }
        return w == Window.OPEN ? name + " (again)" : name;
    }

    /**
     * Buy a rented look for yourself ({@code receiver} = the payer) or as a present. Checks in order, the first
     * failure the message and nothing charged: the payer may spend here; the row is on the shelf for them and not
     * locked; a present's receiver can have one ({@link PrizeService#giftRefusal}); the receiver's window (LIVE and
     * KEPT refused); the balance; a hat's key can be made. Then {@link #commit}, then the same tick the key (or the
     * trail), the cache and the feedback. A first buy for yourself closes the counter and plays its moment; a
     * renewal says so and the caller reopens the counter; a present closes the giver's screen and gives both their
     * moment, as a collector present does.
     *
     * @param shownCost the price the screen showed (below 0: none)
     */
    public Outcome purchase(Player payer, UUID receiverId, Prize p, int shownCost) {
        boolean present = !payer.getUniqueId().equals(receiverId);
        if (!plugin.sandbox().check(payer, "prize purchase " + p.id())) {
            return fail(EconomySandbox.reason());
        }
        PrizeService prizes = plugin.prizes();
        if (!p.rental() || !prizes.visibleTo(payer, p)) {
            return fail("That isn't for sale right now.");
        }
        String locked = prizes.lockedBecause(payer, p);
        if (locked != null) {
            return fail(locked + ".");
        }
        Player receiver = present ? Bukkit.getPlayer(receiverId) : payer;
        if (present) {
            String refused = PrizeService.giftRefusal(prizes.giftFacts(payer, receiverId, p, true));
            if (refused != null) {
                return fail(refused);
            }
            if (receiver == null) {
                return fail("That player isn't online.");
            }
        }
        long now = now();
        Look before;
        try {
            before = dao.look(receiverId, p.id());
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read " + receiverId + "'s look '" + p.id() + "': " + e.getMessage());
            return fail(NOT_SAVED);
        }
        Window w = LookRules.window(before, now);
        if (w == Window.LIVE || w == Window.KEPT) {
            return fail(present ? hasItLine(receiver.getName(), p, before) : LookRules.refusal(w, before, zone()));
        }
        int cost = LookRules.price(p, w);
        if (shownCost >= 0 && cost != shownCost) {
            return fail(PRICE_CHANGED);
        }
        int have = plugin.tokens().balance(payer.getUniqueId());
        if (have < cost) {
            return fail("You need " + (cost - have) + " more tokens.");
        }
        boolean hat = p.type() == PrizeType.HAT;
        LocalDate planned = LookRules.newLastDay(before, Math.max(1, p.days()), now, zone());
        ItemStack key = hat ? PrizeItems.lookKey(plugin, p, receiverId, planned, LookRules.endOf(planned, zone())) : null;
        if (hat && key == null) {
            return fail(NOTHING_MADE);
        }
        Committed r;
        try {
            r = commit(plugin.database(), dao,
                    (who, n, detail) -> plugin.tokens().spend(who, n, TokenService.Source.PRIZE, detail),
                    payer.getUniqueId(), receiverId, p, shownCost >= 0 ? shownCost : cost, now, zone(),
                    receiver.getName());
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not save " + payer.getName() + "'s buy of '" + p.id()
                    + "' (nothing charged): " + e.getMessage());
            return fail(NOT_SAVED);
        }
        switch (r.status()) {
            case ALREADY -> {
                return fail(present ? hasItLine(receiver.getName(), p, r.before())
                        : LookRules.refusal(LookRules.window(r.before(), now), r.before(), zone()));
            }
            case PRICE_CHANGED -> {
                return fail(PRICE_CHANGED);
            }
            case NOT_PAID -> {
                return fail("You need " + cost + " tokens.");
            }
            default -> {
            }
        }
        boolean first = LookRules.firstBuy(r.before(), now);
        String until = LookRules.until(r.lastDay());
        String where;
        if (hat) {
            PrizeItems.stampKey(key, p, receiverId, r.lastDay(), r.expiresAt());
            where = handOver(receiver, p, key, present);
        } else {
            where = first ? "Your trail is on!" : null;
        }
        reload(receiverId);
        if (!present && first) {
            payer.closeInventory(); // its moment where it can be seen: on Bedrock the chest screen covers the view
        }
        if (!hat) {
            if (present) {
                PrizeService.closeOurMenuFor(receiver); // the burst is part of the moment
            }
            if (first) {
                plugin.trails().select(receiver, p.id());
            } else {
                plugin.trails().load(receiver);
            }
        }
        sweep(receiver);
        plugin.getLogger().info(payer.getName() + (present ? " gave " + receiver.getName() + " " : " bought ") + p.id()
                + " until " + r.lastDay() + " (" + r.cost() + " tokens" + (first ? "" : ", again") + ")");
        String name = Text.plain(p.display());
        if (present) {
            payer.closeInventory();
            payer.sendMessage(Text.of("&a✔ You gave the " + name + " to " + receiver.getName() + "! &7(until "
                    + until + ")"));
            Sounds.paid(payer);
            plugin.prizes().presentMoment(receiver, payer.getName(), p,
                    where == null ? "It's yours until " + until + "!" : where, hat);
            titleShown(receiver, TITLE_MS);
        } else if (first) {
            yours(payer, p); // the counter closed above (red team O19)
            payer.sendMessage(Text.of("&a✔ " + name + " is yours until " + until + "! " + (where == null ? "" : where)));
            Sounds.paid(payer);
        } else {
            payer.sendMessage(Text.of("&a✔ " + name + ": yours until " + until + " now!"));
            Sounds.paid(payer);
        }
        return new Outcome(true, null, PrizeService.icon(p), p.display() + " &7(until " + until + ")", true, false);
    }

    /** "<name> has the Dragon Hat until 18 Jan." (or for good): a present refused because they have it. */
    private String hasItLine(String receiver, Prize p, Look row) {
        String item = Text.plain(p.display());
        if (row == null || LookRules.kept(row, now())) {
            return receiver + " already has the " + item + "!";
        }
        return receiver + " has the " + item + " until " + LookRules.until(LookRules.lastDay(row.expiresAt(), zone()))
                + ".";
    }

    /** A first buy's moment, where a first-person camera sees it: "Yours!", a burst in view. */
    private void yours(Player player, Prize p) {
        try {
            player.showTitle(Title.title(Text.of("&aYours!"), Text.of("&f" + Text.plain(p.display())),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500))));
            titleShown(player, TITLE_MS);
            Fireworks.burstInView(player);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    private static Outcome fail(String why) {
        return new Outcome(false, why, null, null, false, false);
    }

    // ---- the key on the player (§3.5, §3.11) ----------------------------------------------------------

    /**
     * Hand a bought key over: a key of this look already on them (the head, the off-hand, the bag, the ender
     * chest) has its name refreshed where it is; otherwise it goes where {@link LookRules#placement} says. Never the
     * ground.
     *
     * @return where it is, for the chat line
     */
    String handOver(Player r, Prize p, ItemStack key, boolean present) {
        UUID owner = r.getUniqueId();
        PlayerInventory inv = r.getInventory();
        for (Slot s : slots(r)) {
            ItemStack there = s.get(r);
            if (keyOf(there, owner, p.id())) {
                if (!p.keeper()) { // a Keeper's key has no end to write: the one there stays as it is
                    PrizeItems.stampKey(there, p, owner, lastDayOf(key), endOf(key));
                    s.set(r, there);
                }
                return switch (s.area()) {
                    case HEAD -> "It's on your head!";
                    case ENDER_CHEST -> "It's in your ender chest.";
                    default -> "It's in your bag.";
                };
            }
        }
        ItemStack head = inv.getHelmet();
        boolean empty = head == null || head.getType().isAir();
        boolean headKey = !empty && PrizeService.bound(head);
        boolean cosmetic = !empty && !headKey && PrizeItems.kindOf(head) == PrizeType.HAT;
        boolean binding = !empty && head.containsEnchantment(Enchantment.BINDING_CURSE);
        int free = inv.firstEmpty();
        Placement place = LookRules.placement(empty, headKey, cosmetic, binding, free >= 0, present);
        ItemStack one = key.clone();
        one.setAmount(1);
        switch (place) {
            case HEAD, HEAD_REPLACING_KEY -> inv.setHelmet(one);
            case HEAD_SWAPPING -> {
                inv.setItem(free, head.clone());
                inv.setHelmet(one);
            }
            case BAG -> inv.setItem(free, one);
            case NOWHERE -> {
            }
        }
        return LookRules.where(place);
    }

    /**
     * "Wear it" (My Looks, the Keepers tab): the right must be live; a key of this look in the bag, the off-hand or
     * the ender chest moves to the head, else a new one is made. What was on the head: a key is deleted; anything
     * else goes into a free bag slot (the key's own, when it came from the bag), or the wear is refused. Only in an
     * economy world and home from every game.
     *
     * @return null when it is on, else why not
     */
    public String wear(Player player, String id) {
        if (!plugin.sandbox().allowed(player.getWorld()) || !Whereabouts.homeFromGames(plugin, player)) {
            return "You can't change your looks here.";
        }
        UUID owner = player.getUniqueId();
        Look right = look(owner, id);
        Prize p = plugin.config().arcade().prize(id);
        if (right == null || !LookRules.live(right, now()) || p == null || p.type() != PrizeType.HAT) {
            return "That look has ended. Buy it again at the Prize Counter.";
        }
        List<Found> mine = new ArrayList<>();
        Map<Found, Slot> where = new HashMap<>();
        for (Slot s : slots(player)) {
            ItemStack there = s.get(player);
            if (keyOf(there, owner, id)) {
                Found f = new Found(s.area(), s.slot(), List.of(), keyFacts(there));
                mine.add(f);
                where.put(f, s);
            }
        }
        Found from = LookRules.wearFrom(mine);
        if (from != null && from.worn()) {
            return null; // on already
        }
        PlayerInventory inv = player.getInventory();
        ItemStack key;
        Slot source = from == null ? null : where.get(from);
        if (source != null) {
            key = source.get(player).clone(); // taken before its slot is emptied
        } else {
            key = makeKey(p, owner, right);
            if (key == null) {
                return "That look couldn't be made. Tell an admin.";
            }
        }
        ItemStack old = inv.getHelmet();
        boolean hasOld = old != null && !old.getType().isAir();
        if (hasOld && old.containsEnchantment(Enchantment.BINDING_CURSE) && !PrizeService.bound(old)) {
            return "What's on your head can't come off.";
        }
        if (hasOld && !PrizeService.bound(old)) {
            int free = source != null && source.area() == Area.BAG ? source.slot() : inv.firstEmpty();
            if (free < 0) {
                return "Make room in your bag first: what's on your head goes there.";
            }
            if (source != null) {
                source.set(player, null);
            }
            inv.setItem(free, old.clone());
        } else if (source != null) {
            source.set(player, null);
        }
        ItemStack one = key.clone();
        one.setAmount(1);
        inv.setHelmet(one); // a bound key that was there is gone: "Wear it" makes it again
        sweep(player);
        Sounds.received(player);
        return null;
    }

    /** A key for {@code right}: a rental's {@link PrizeItems#lookKey}; a Keeper's {@link PrizeItems#keeperItem}, its number kept. */
    private ItemStack makeKey(Prize p, UUID owner, Look right) {
        if (p.keeper()) {
            return PrizeItems.keeperItem(plugin, p, owner, storedNumber(p, owner));
        }
        return PrizeItems.lookKey(plugin, p, owner, LookRules.lastDay(right.expiresAt(), zone()), right.expiresAt());
    }

    /**
     * Take a look off: the helmet key of that look is deleted (it is in My Looks; nothing to put away). A hat sold
     * before 0.38, of the same id, is never touched ({@link LookRules#isKeyOf}).
     *
     * @return null when it came off, else why not
     */
    public String takeOff(Player player, String id) {
        ItemStack head = player.getInventory().getHelmet();
        if (!keyOf(head, player.getUniqueId(), id)) {
            return "You aren't wearing it.";
        }
        player.getInventory().setHelmet(null);
        marks(player);
        Sounds.received(player);
        return null;
    }

    /**
     * My Looks' collector cap tile: put the cap on from the bag, the old helmet as a buy would ({@link
     * LookRules#placement}: a key on the head is deleted, a cosmetic goes into the cap's own slot, real armour too).
     *
     * @return null when it is on, else a hint saying where it is
     */
    public String capOn(Player player, Prize cap) {
        if (!plugin.sandbox().allowed(player.getWorld()) || !Whereabouts.homeFromGames(plugin, player)) {
            return "You can't change your looks here.";
        }
        PlayerInventory inv = player.getInventory();
        ItemStack head = inv.getHelmet();
        if (cap.id().equalsIgnoreCase(TokenPrizes.prizeId(head))) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (ItemStack it : inv.getStorageContents()) {
            ids.add(TokenPrizes.prizeId(it));
        }
        // the bag first, then the off-hand: everywhere capWhere calls "in your bag" (red-team finding BR-5)
        int slot = LookRules.capSlot(cap.id(), ids, TokenPrizes.prizeId(inv.getItemInOffHand()));
        if (slot >= 0) {
            ItemStack it = inv.getItem(slot).clone();
            boolean empty = head == null || head.getType().isAir();
            if (!empty && head.containsEnchantment(Enchantment.BINDING_CURSE)) {
                return "What's on your head can't come off.";
            }
            inv.setItem(slot, empty || PrizeService.bound(head) ? null : head.clone());
            inv.setHelmet(it);
            Sounds.received(player);
            return null;
        }
        return capWhere(player, cap) == CapWhere.ENDER_CHEST ? "It's in your ender chest - take it out first."
                : "It isn't with you.";
    }

    /** Where a collector cap is, for My Looks: on the head, in the bag, in the ender chest, or not with you. */
    public enum CapWhere { HEAD, BAG, ENDER_CHEST, AWAY }

    public CapWhere capWhere(Player player, Prize cap) {
        PlayerInventory inv = player.getInventory();
        if (cap.id().equalsIgnoreCase(TokenPrizes.prizeId(inv.getHelmet()))) {
            return CapWhere.HEAD;
        }
        for (ItemStack it : inv.getStorageContents()) {
            if (cap.id().equalsIgnoreCase(TokenPrizes.prizeId(it))) {
                return CapWhere.BAG;
            }
        }
        if (cap.id().equalsIgnoreCase(TokenPrizes.prizeId(inv.getItemInOffHand()))) {
            return CapWhere.BAG;
        }
        for (ItemStack it : player.getEnderChest().getContents()) {
            if (cap.id().equalsIgnoreCase(TokenPrizes.prizeId(it))) {
                return CapWhere.ENDER_CHEST;
            }
        }
        return CapWhere.AWAY;
    }

    /**
     * Why a bound key in the hand may not be worn (a key works only on its owner, and only while the right is live),
     * or null when it may. Only once the rights are loaded: never on an unknown answer. The caller takes the key
     * away, as a sweep would.
     */
    public String keyRefusal(Player player, ItemStack key) {
        LookRules.Key k = keyFacts(key);
        UUID owner = player.getUniqueId();
        if (k == null || !loaded(owner)) {
            return null;
        }
        LookRules.Judged j = LookRules.verdict(true, owner, k.boundTo(), look(owner, k.id()), now(),
                Keepers.reserved(k.id()), k.itemEnd(), k.variant(), null);
        if (j.verdict() != Verdict.REMOVE) {
            return null;
        }
        plugin.getLogger().info("Removed " + k.id() + " key (" + j.reason() + ") from " + player.getName());
        return "not yours".equals(j.reason()) ? "That isn't yours, so it went back to its owner."
                : "That look has ended. Buy it again in My Looks.";
    }

    /** The rented or Keeper look worn now (a key on the head), or null. */
    public Prize worn(Player player) {
        ItemStack head = player.getInventory().getHelmet();
        UUID to = PrizeService.boundTo(head);
        if (to == null || !to.equals(player.getUniqueId())) {
            return null;
        }
        return plugin.config().arcade().prize(itemId(head));
    }

    // ---- sweeps (§3.8) -----------------------------------------------------------------------------

    /** One place a sweep reads, and how to read and write it. */
    record Slot(Area area, int slot) {
        ItemStack get(Player p) {
            PlayerInventory inv = p.getInventory();
            return switch (area) {
                case HEAD -> inv.getHelmet();
                case BAG -> inv.getItem(slot);
                case ARMOUR -> inv.getItem(ARMOUR_SLOTS[slot]);
                case OFF_HAND -> inv.getItemInOffHand();
                case ENDER_CHEST -> p.getEnderChest().getItem(slot);
            };
        }

        void set(Player p, ItemStack item) {
            PlayerInventory inv = p.getInventory();
            switch (area) {
                case HEAD -> inv.setHelmet(item);
                case BAG -> inv.setItem(slot, item);
                case ARMOUR -> inv.setItem(ARMOUR_SLOTS[slot], item);
                case OFF_HAND -> inv.setItemInOffHand(item);
                case ENDER_CHEST -> p.getEnderChest().setItem(slot, item);
            }
        }
    }

    private static final EquipmentSlot[] ARMOUR_SLOTS = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST};

    /** Every place a sweep reads, in its order: the head, the bag, the other armour, the off-hand, the ender chest. */
    private static List<Slot> slots(Player p) {
        List<Slot> out = new ArrayList<>();
        out.add(new Slot(Area.HEAD, 0));
        for (int i = 0; i < 36; i++) {
            out.add(new Slot(Area.BAG, i));
        }
        for (int i = 0; i < ARMOUR_SLOTS.length; i++) {
            out.add(new Slot(Area.ARMOUR, i));
        }
        out.add(new Slot(Area.OFF_HAND, 0));
        Inventory ender = p.getEnderChest();
        for (int i = 0; i < ender.getSize(); i++) {
            out.add(new Slot(Area.ENDER_CHEST, i));
        }
        return out;
    }

    /**
     * Sweep one player's keys: each one's verdict ({@link LookRules#plan}), the head, the bag, the armour, the
     * off-hand, the ender chest and what is inside the bundles and shulker boxes there. A REMOVE is deleted with one
     * INFO line; a key inside a container is taken out of it (the container rewritten in place); a REFRESH rewrites
     * the key's name in place. Skipped in a world game, on the way back from one, and while dead: a game's saved
     * snapshot is never touched, and its restore brings keys back for the next sweep to judge.
     */
    public void sweep(Player player) {
        sweepKeys(player);
        marks(player);
    }

    private void sweepKeys(Player player) {
        if (!LookRules.sweeps(Whereabouts.inGameSession(plugin, player), Whereabouts.homeFromGames(plugin, player),
                player.isDead())) {
            return;
        }
        UUID owner = player.getUniqueId();
        long now = now();
        List<Found> found = new ArrayList<>();
        Map<String, Slot> slotOf = new HashMap<>();
        for (Slot s : slots(player)) {
            List<Found> here = LookRules.find(s.area(), s.slot(), s.get(player), LookService::keyFacts,
                    LookService::contents);
            if (!here.isEmpty()) {
                found.addAll(here);
                slotOf.put(s.area() + ":" + s.slot(), s);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        Map<String, Look> rights = cache.get(owner);
        List<Decision> plan = LookRules.plan(rights != null, owner, rights, now, found, this::configuredVariant);
        Map<Slot, List<List<Integer>>> nested = new HashMap<>();
        boolean wornEnded = false;
        for (Decision d : plan) {
            Found f = d.found();
            Slot s = slotOf.get(f.area() + ":" + f.slot());
            if (d.verdict() == Verdict.REMOVE) {
                plugin.getLogger().info("Removed " + f.key().id() + " key (" + d.reason() + ") from " + player.getName());
                if (f.nested()) {
                    nested.computeIfAbsent(s, k -> new ArrayList<>()).add(f.path());
                } else {
                    s.set(player, null);
                    wornEnded |= f.worn() && "ended".equals(d.reason());
                }
            } else if (d.verdict() == Verdict.REFRESH && !f.nested()) {
                refresh(player, s, rights);
            }
        }
        for (Map.Entry<Slot, List<List<Integer>>> e : nested.entrySet()) {
            ItemStack container = e.getKey().get(player);
            ItemStack cleaned = without(container, e.getValue(), 0);
            if (cleaned != null) {
                e.getKey().set(player, cleaned);
            }
        }
        if (wornEnded) {
            capFallback(player);
        }
    }

    /**
     * Rewrite a rental key's name and end for its right's run, in place; a Keeper's key is rebuilt in the form the
     * config picks (§5.2's look switch), its name, lore, number and binding kept.
     */
    private void refresh(Player player, Slot s, Map<String, Look> rights) {
        ItemStack key = s.get(player);
        String id = itemId(key);
        Prize p = plugin.config().arcade().prize(id);
        Look right = rights == null || id == null ? null : rights.get(id);
        if (p == null || right == null) {
            return;
        }
        if (p.keeper()) {
            Keepers.Variant v = Keepers.variant(plugin.config().arcade().keepers(), p.id());
            if (PrizeItems.restyleKeeper(key, p, v, plugin.getLogger())) {
                s.set(player, key);
                plugin.getLogger().info("Rebuilt " + p.id() + " key in its " + v.key() + " look for "
                        + player.getName());
            }
            return;
        }
        PrizeItems.stampKey(key, p, player.getUniqueId(), LookRules.lastDay(right.expiresAt(), zone()),
                right.expiresAt());
        s.set(player, key);
    }

    /** The form the config picks for a worn Keeper's key ({@code default} / {@code fallback}), else null. */
    private String configuredVariant(String id) {
        Prize p = plugin.config().arcade().prize(id);
        return p != null && p.keeper() && p.type() == PrizeType.HAT
                ? Keepers.variant(plugin.config().arcade().keepers(), p.id()).key() : null;
    }

    /**
     * A worn Keeper's mark (§5.3a), set on the trail engine: while the player's own key of a live Keeper hat is on
     * their head, its mark (the Crown's in the form its Royal Trail is set to); otherwise none. Called after every
     * sweep, a wear and a take-off; the engine also checks the head before each puff.
     */
    /**
     * {@link #marks} a tick from now, once the slot a click or a drag in the player's own inventory moved has settled
     * (a Keeper key dragged onto or off the head): the mark starts or stops at once instead of at the next sweep.
     */
    public void marksSoon(Player player) {
        if (plugin.trails() == null || !marksPending.add(player.getUniqueId())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            marksPending.remove(player.getUniqueId());
            if (player.isOnline()) {
                marks(player);
            }
        });
    }

    public void marks(Player player) {
        if (plugin.trails() == null) {
            return;
        }
        UUID owner = player.getUniqueId();
        LookRules.Key k = keyFacts(player.getInventory().getHelmet());
        TrailService.Mark mark = null;
        if (k != null && Keepers.reserved(k.id()) && owner.equals(k.boundTo()) && loaded(owner)
                && LookRules.live(look(owner, k.id()), now())) {
            TrailService.Step step = Keepers.mark(k.id(),
                    Keepers.variant(plugin.config().arcade().keepers(), Keepers.ROYAL));
            if (step != null) {
                mark = new TrailService.Mark(k.id(), step);
            }
        }
        plugin.trails().mark(owner, mark);
    }

    /**
     * When a worn key came off at its end and the head is empty, the newest collector cap in the bag goes on (its
     * move frees the bag slot), so she isn't left with nothing (red team O7).
     */
    private void capFallback(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack head = inv.getHelmet();
        if (head != null && !head.getType().isAir()) {
            return;
        }
        ItemStack[] bag = inv.getStorageContents();
        LocalDate[] starts = new LocalDate[bag.length];
        for (int i = 0; i < bag.length; i++) {
            Prize p = plugin.config().arcade().prize(TokenPrizes.prizeId(bag[i]));
            starts[i] = p != null && p.collector() != null && p.type() == PrizeType.HAT ? p.collector().from() : null;
        }
        int best = LookRules.fallbackCap(starts);
        if (best >= 0) {
            ItemStack cap = bag[best].clone();
            inv.setItem(best, null);
            inv.setHelmet(cap);
        }
    }

    /**
     * A run that ended while its trail was the one on (§3.9, red team O7): the newest kept trail (a collector or a
     * Keeper, by its start) comes on, with no burst, and the ENDED line will say so. Then the trail engine reloads,
     * so an ended trail stops drawing.
     */
    private void ends(Player player) {
        UUID owner = player.getUniqueId();
        Map<String, Look> rows = cache.get(owner);
        if (rows == null || plugin.trails() == null) {
            return;
        }
        long now = now();
        Look kept = LookRules.fallbackTrail(rows.values(), now, id -> plugin.config().arcade().prize(id) != null);
        if (kept != null) {
            List<Look> ended = LookRules.endedOn(rows.values(), now);
            plugin.trails().select(player, kept.id(), false);
            Prize k = plugin.config().arcade().prize(kept.id());
            for (Look e : ended) {
                cameOn.computeIfAbsent(owner, x -> new HashMap<>()).put(e.id(), Text.plain(k.display()));
            }
            reload(owner);
        }
        plugin.trails().load(player);
    }

    /** What a sweep reads off an item: a key (it carries {@code hcm:bound_to}), or null. The data view only. */
    static LookRules.Key keyFacts(ItemStack item) {
        if (item == null || Keys.BOUND_TO == null || item.getType().isAir()) {
            return null;
        }
        try {
            PersistentDataContainerView pdc = item.getPersistentDataContainer();
            if (!pdc.has(Keys.BOUND_TO, PersistentDataType.STRING)) {
                return null;
            }
            String id = pdc.get(Keys.TOKEN_PRIZE, PersistentDataType.STRING);
            Long end = Keys.LOOK_END == null ? null : pdc.get(Keys.LOOK_END, PersistentDataType.LONG);
            String variant = Keys.LOOK_VARIANT == null ? null : pdc.get(Keys.LOOK_VARIANT, PersistentDataType.STRING);
            return new LookRules.Key(id == null ? "" : id, PrizeService.boundTo(item), end == null ? 0 : end, variant);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@link LookRules#isKeyOf} for a real item. */
    static boolean keyOf(ItemStack item, UUID holder, String id) {
        LookRules.Key k = keyFacts(item);
        return k != null && LookRules.isKeyOf(k.boundTo(), k.id(), holder, id);
    }

    /** Whether {@code player} wears their own key of look {@code id} on their head (a mark's test, every puff). */
    public static boolean wearing(Player player, String id) {
        return player != null && keyOf(player.getInventory().getHelmet(), player.getUniqueId(), id);
    }

    private static String itemId(ItemStack item) {
        LookRules.Key k = keyFacts(item);
        return k == null ? null : k.id();
    }

    private LocalDate lastDayOf(ItemStack key) {
        return LookRules.lastDay(endOf(key), zone());
    }

    private static long endOf(ItemStack key) {
        LookRules.Key k = keyFacts(key);
        return k == null ? 0 : k.itemEnd();
    }

    /**
     * A container's slots by index (null for an empty one): a shulker box's (any block-state item with an inventory)
     * or a bundle's. Empty for an item that can't be read, or holds something past {@link TokenPrizes#MAX_DEPTH}:
     * the fail-closed reader of D36, so an unknown answer deletes nothing. Most items aren't containers, so the
     * material goes first.
     */
    static Optional<List<ItemStack>> contents(ItemStack item, int depth) {
        if (item == null || !container(item.getType())) {
            return Optional.of(List.of());
        }
        try {
            ItemMeta meta = item.getItemMeta();
            List<ItemStack> out = new ArrayList<>();
            if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof org.bukkit.inventory.InventoryHolder holder) {
                out.addAll(java.util.Arrays.asList(holder.getInventory().getContents()));
            } else if (meta instanceof BundleMeta bundle) {
                out.addAll(bundle.getItems());
            }
            boolean any = out.stream().anyMatch(i -> i != null && !i.getType().isAir());
            return depth > TokenPrizes.MAX_DEPTH && any ? Optional.empty() : Optional.of(out);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static boolean container(Material m) {
        String n = m.name();
        return n.endsWith("SHULKER_BOX") || n.endsWith("BUNDLE");
    }

    /**
     * {@code container} with the items at {@code paths} taken out (each path the slots inside it, then inside what
     * is there, and so on), or null when it can't be rewritten (it is left as it was).
     */
    static ItemStack without(ItemStack container, List<List<Integer>> paths, int depth) {
        Optional<List<ItemStack>> read = contents(container, depth);
        if (read.isEmpty()) {
            return null;
        }
        List<ItemStack> slots = new ArrayList<>(read.get());
        Map<Integer, List<List<Integer>>> deeper = new HashMap<>();
        Set<Integer> gone = new HashSet<>();
        for (List<Integer> path : paths) {
            if (path.isEmpty() || path.get(0) >= slots.size()) {
                continue;
            }
            if (path.size() == 1) {
                gone.add(path.get(0));
            } else {
                deeper.computeIfAbsent(path.get(0), k -> new ArrayList<>()).add(path.subList(1, path.size()));
            }
        }
        for (Map.Entry<Integer, List<List<Integer>>> e : deeper.entrySet()) {
            ItemStack inner = without(slots.get(e.getKey()), e.getValue(), depth + 1);
            if (inner != null) {
                slots.set(e.getKey(), inner);
            }
        }
        try {
            ItemStack out = container.clone();
            ItemMeta meta = out.getItemMeta();
            if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
                for (int i = 0; i < slots.size(); i++) {
                    box.getInventory().setItem(i, gone.contains(i) ? null : slots.get(i));
                }
                bsm.setBlockState(box);
                out.setItemMeta(bsm);
                return out;
            }
            if (meta instanceof BundleMeta bundle) {
                List<ItemStack> kept = new ArrayList<>();
                for (int i = 0; i < slots.size(); i++) {
                    if (!gone.contains(i) && slots.get(i) != null) {
                        kept.add(slots.get(i));
                    }
                }
                bundle.setItems(kept);
                out.setItemMeta(bundle);
                return out;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    // ---- heads-up notices (§3.10) ---------------------------------------------------------------

    /**
     * Send the heads-ups due now, when they may be shown: not in a game, a Games world or on the death screen, not
     * behind a chest screen on Bedrock, not in the first 100 ticks after a join, and not while another title of ours
     * is up. Each one's stage is written first and the notice sent only when that worked, so a held or unsaved notice
     * is still due next minute.
     */
    public void notices(Player player) {
        UUID owner = player.getUniqueId();
        long now = now();
        boolean allowed = LookRules.noticesAllowed(Whereabouts.inGameSession(plugin, player),
                Whereabouts.homeFromGames(plugin, player), Whereabouts.gamesWorld(plugin, player.getWorld()),
                player.isDead(), LookRules.screenFree(Bedrock.is(player),
                        player.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING))
                && gate.allows(owner, now);
        Map<String, Look> rows = cache.get(owner);
        if (rows == null) {
            return;
        }
        List<Due> due = LookRules.dueNow(allowed, List.copyOf(rows.values()), now);
        List<Due> sent = new ArrayList<>();
        for (Due d : due) {
            try {
                dao.notice(owner, d.row().id(), LookRules.stage(d.notice()), d.row().expiresAt());
                sent.add(d);
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not save a heads-up for " + player.getName() + ": " + e.getMessage());
            }
        }
        if (sent.isEmpty()) {
            return;
        }
        reload(owner);
        ZoneId zone = zone();
        String[] title = LookRules.titleFor(sent, now, this::name);
        if (title != null) {
            try {
                player.showTitle(Title.title(Text.of(title[0]), Text.of(title[1]),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(3000), Duration.ofMillis(500))));
                titleShown(player, TITLE_MS);
            } catch (RuntimeException ignored) {
                // cosmetic: the chat lines still say it
            }
        }
        Sounds.received(player);
        boolean bedrock = Bedrock.is(player);
        for (Due d : sent) {
            Look row = d.row();
            Prize p = plugin.config().arcade().prize(row.id());
            boolean shortRun = LookRules.shortRun(row.since(), row.expiresAt());
            // a quiet end offers no price and no link: a crate's day, or a 0.37 row (red team O10, finding F1)
            int price = !LookRules.quietEnd(row.since(), row.expiresAt()) && p != null && p.rental()
                    && plugin.prizes().visible(p) ? p.renewPrice() : -1;
            Map<String, String> on = cameOn.get(owner);
            String line = LookRules.line(d.notice(), name(row.id()), LookRules.lastDay(row.expiresAt(), zone), shortRun,
                    price, LookRules.windowLastDay(row.expiresAt(), zone), on == null ? null : on.remove(row.id()));
            Component c = Text.of(line);
            if (!bedrock && price >= 0) {
                c = c.append(Text.of(" &a[Buy again]").clickEvent(ClickEvent.runCommand("/hcm looks renew " + row.id())));
            }
            player.sendMessage(c);
            if (LookRules.giverHears(d) && row.givenBy() != null && !row.givenBy().isBlank()) {
                giverHeadsUp(row, player.getName(), LookRules.lastDay(row.expiresAt(), zone), now);
            }
        }
    }

    /**
     * A present a week from its end (or nearer, when the receiver didn't come by that week): its giver's one line, now
     * or at their next join (red team O9, red-team finding F1-GIVER-SKIPPED).
     */
    private void giverHeadsUp(Look row, String receiver, LocalDate lastDay, long now) {
        UUID giver;
        try {
            giver = UUID.fromString(row.givenBy().trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        String line = LookRules.giverLine(name(row.id()), receiver, lastDay, row.expiresAt() <= now);
        Player g = Bukkit.getPlayer(giver);
        if (g != null && g.isOnline()) {
            sendGiverLine(g, line, row.id(), receiver);
            return;
        }
        // the receiver's stage is saved already, so this line is never worked out again: it waits in game_prefs
        try {
            PrizeDao prefs = plugin.prizes().dao();
            prefs.setPref(giver, GIVER_LINES_PREF, LookRules.withGiverLine(prefs.pref(giver, GIVER_LINES_PREF),
                    row.id(), receiver, line));
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not keep a present's line for its giver: " + e.getMessage());
            giverLines.computeIfAbsent(giver, k -> new ArrayList<>()).add(new String[] {row.id(), receiver, line});
        }
    }

    /** A giver's lines waiting in {@code game_prefs}, taken (the key is cleared): {@code [id, receiver, line]}. */
    private List<String[]> takeGiverLines(UUID giver) {
        try {
            PrizeDao prefs = plugin.prizes().dao();
            String stored = prefs.pref(giver, GIVER_LINES_PREF);
            if (stored == null) {
                return List.of();
            }
            prefs.setPref(giver, GIVER_LINES_PREF, null);
            return LookRules.giverLines(stored);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read a giver's waiting lines: " + e.getMessage());
            return List.of();
        }
    }

    private void sendGiverLine(Player giver, String line, String id, String receiver) {
        Component c = Text.of(line);
        if (!Bedrock.is(giver)) {
            c = c.append(Text.of(" &a[Give it again]")
                    .clickEvent(ClickEvent.runCommand("/hcm looks gift " + id + " " + receiver)));
        }
        giver.sendMessage(c);
    }

    /** A row's plain name ("Lime Cap"), or its id when the row is gone. */
    private String name(String id) {
        Prize p = plugin.config().arcade().prize(id);
        return p == null ? id : Text.plain(p.display());
    }

    // ---- admin (§3.14, §3.16) --------------------------------------------------------------------

    /**
     * {@code /hcm looks give <player> <id> [days]}: a right free, the key handed over as a present's is, with the
     * admin as its giver (so the giver's line can be tried on a free test look). A rental runs the row's days, or
     * {@code days}; a Keeper is kept for good, its claim written ({@link #grantKeeper}). The present's line says where
     * the key went, as a bought present's does (red-team finding BR-4: on her head, in her bag, or in My Looks when
     * neither had room), and {@code told} gets that line for the admin's confirmation. From the console
     * ({@code adminId} null) it is no present: no moment and no giver, the plain line (red-team finding CMD-6).
     *
     * @param told gets the line the receiver read, once it was given (may be null)
     * @return null when it was given, else why not
     */
    public String give(UUID adminId, String adminName, Player target, Prize p, Integer days,
                       java.util.function.Consumer<String> told) {
        if (!p.rental() && !p.keeper()) {
            return "That isn't a rented look or a Keeper.";
        }
        if (!Whereabouts.homeFromGames(plugin, target)) {
            return target.getName() + " is in a game. Try again when they're back.";
        }
        if (target.isDead()) {
            return target.getName() + " is respawning. Try again in a moment.";
        }
        UUID to = target.getUniqueId();
        boolean present = LookRules.givenAsPresent(adminId, to);
        String giver = present ? adminId.toString() : "";
        if (p.keeper()) {
            if (p.extra().keeper().companion()) {
                return "That comes with the Legend Crown: give the Crown.";
            }
            String where = grantKeeper(target, p, giver, !to.equals(adminId));
            if (where == null) {
                return "Couldn't save that - see the console.";
            }
            plugin.getLogger().info("Admin " + adminName + " gave " + target.getName() + " the Keeper " + p.id());
            if (present) {
                plugin.prizes().presentMoment(target, adminName, p, where, p.type() == PrizeType.HAT);
                titleShown(target, TITLE_MS);
            } else if (adminId == null) {
                target.sendMessage(Text.of(LookRules.plainGivenLine(p.display(), "It's yours for good! " + where)));
                Sounds.received(target);
            }
            if (told != null) {
                told.accept(where);
            }
            return null;
        }
        long now = now();
        ZoneId zone = zone();
        Look before;
        LocalDate last;
        long end;
        try {
            before = dao.look(to, p.id());
            last = LookRules.newLastDay(before, days != null ? days : Math.max(1, p.days()), now, zone);
            end = LookRules.endOf(last, zone);
            dao.write(to, p.id(), kind(p), end, LookRules.since(before, now), giver);
        } catch (SQLException e) {
            return "Couldn't save that: " + e.getMessage();
        }
        reload(to);
        boolean first = LookRules.firstBuy(before, now);
        String placed = null;
        if (p.type() == PrizeType.HAT) {
            ItemStack key = PrizeItems.lookKey(plugin, p, to, last, end);
            placed = key == null ? LookRules.where(LookRules.Placement.NOWHERE) : handOver(target, p, key, true);
        } else if (p.type() == PrizeType.TRAIL && first) {
            plugin.trails().select(target, p.id());
        }
        plugin.trails().load(target);
        tick(target);
        plugin.getLogger().info("Admin " + adminName + " gave " + target.getName() + " the look " + p.id() + " until "
                + last);
        String line = LookRules.givenLine(LookRules.until(last), placed);
        if (present) {
            plugin.prizes().presentMoment(target, adminName, p, line, p.type() == PrizeType.HAT);
            titleShown(target, TITLE_MS);
        } else if (adminId == null) {
            target.sendMessage(Text.of(LookRules.plainGivenLine(p.display(), line)));
            Sounds.received(target);
        }
        if (told != null) {
            told.accept(line);
        }
        return null;
    }

    // ---- Keepers (§5.4) ------------------------------------------------------------------------------

    /** The {@code arcade_state} counter of a numbered Keeper's "No. N" (the Legend Crown's). */
    public static String serialKey(String id) {
        return "keeper_serial:" + id;
    }

    /** The {@code arcade_state} key of one player's number for a numbered Keeper, so a key made again keeps it. */
    public static String numberKey(String id, UUID player) {
        return "keeper_no:" + id + ":" + player;
    }

    /** Whether a Keeper carries a number ("No. N"): the Legend Crown. */
    static boolean numbered(Prize p) {
        return p != null && Keepers.CROWN.equalsIgnoreCase(p.id());
    }

    /** What a Keeper's transaction did, whether it wrote the claim (false: it was there), and the Crown's number. */
    public record KeeperCommitted(Status status, boolean claimed, long number) {
    }

    /**
     * A Keeper as ONE transaction (§5.4, D30): the claim ({@code prize_purchases 'life'}; already there = ALREADY,
     * nothing charged), the rights kept for good (a hat's HAT row, a trail's TRAIL row, and for the Crown its Royal
     * Trail too), the Crown's number (taken once: one made again, or given back, keeps the number it had) and the
     * tokens (a refused charge rolls all of it back: NOT_PAID). With no {@code spend} it is free (an admin's give-back,
     * {@code /hcm give prize} or {@code /hcm looks give}): it goes ahead whether or not the claim was there, so a
     * give-back never counts twice. Static, so the double confirm and the Crown's two rows are pinned by a test.
     */
    public static KeeperCommitted commitKeeper(Database db, LookDao looks, PrizeDao prizes, Spend spend, UUID player,
                                               Prize p, int cost, long now, String givenBy) throws SQLException {
        try {
            return db.transaction(c -> {
                boolean claimed = prizes.claimOnce(player, p.id());
                if (!claimed && spend != null) {
                    return new KeeperCommitted(Status.ALREADY, false, 0);
                }
                long end = now + KEPT_MS;
                writeKept(looks, player, p.id(), kind(p), end, now, givenBy);
                String with = p.extra().keeper() == null ? null : p.extra().keeper().withTrail();
                if (with != null) {
                    writeKept(looks, player, with, LookDao.TRAIL, end, now, givenBy);
                }
                long number = numbered(p) ? number(prizes, p.id(), player) : 0;
                if (spend != null && !spend.spend(player, cost, Text.plain(p.display()))) {
                    throw new NotPaid();
                }
                return new KeeperCommitted(Status.DONE, claimed, number);
            });
        } catch (NotPaid e) {
            return new KeeperCommitted(Status.NOT_PAID, false, 0);
        }
    }

    /** One right kept for good: a row already kept keeps its start and end; anything else starts a run now. */
    private static void writeKept(LookDao looks, UUID player, String id, String kind, long end, long now,
                                  String givenBy) throws SQLException {
        Look before = looks.look(player, id);
        if (LookRules.kept(before, now)) {
            return;
        }
        looks.write(player, id, kind, end, now, givenBy == null ? "" : givenBy);
    }

    /** A player's number for a numbered Keeper: theirs when they have one, else the next, written now. */
    static long number(PrizeDao prizes, String id, UUID player) throws SQLException {
        long have = prizes.state(numberKey(id, player), 0);
        if (have > 0) {
            return have;
        }
        long next = prizes.state(serialKey(id), 0) + 1;
        prizes.setState(serialKey(id), next);
        prizes.setState(numberKey(id, player), next);
        return next;
    }

    /** The number a player has for a numbered Keeper, or 0 (none, or not numbered, or a read that failed). */
    private long storedNumber(Prize p, UUID player) {
        if (!numbered(p)) {
            return 0;
        }
        try {
            return plugin.prizes().dao().state(numberKey(p.id(), player), 0);
        } catch (SQLException e) {
            return 0; // the key is made without its "No. N" rather than not at all
        }
    }

    /**
     * Buy a Keeper (§5.4): every check first, the first failure the message and nothing charged (the payer may spend
     * here; it is on sale; they don't have it yet; the price the screen showed; the balance; a hat's key can be made),
     * then {@link #commitKeeper}, then the same tick the key (on the head if empty or a cosmetic, else the bag, else
     * My Looks) or the trail switched on (the Crown's Royal Trail too), its mark, and the moment: a Keeper is only ever
     * a first buy, so the counter closes for its "Yours!".
     */
    public Outcome buyKeeper(Player player, Prize p, int shownCost) {
        if (!plugin.sandbox().check(player, "prize purchase " + p.id())) {
            return fail(EconomySandbox.reason());
        }
        PrizeService prizes = plugin.prizes();
        if (!p.keeper() || !prizes.visibleTo(player, p)) {
            return fail("That isn't for sale right now.");
        }
        UUID id = player.getUniqueId();
        if (prizes.owned(player, p)) {
            return fail("You already have that.");
        }
        int cost = p.costTokens();
        if (shownCost >= 0 && cost != shownCost) {
            return fail(PRICE_CHANGED);
        }
        int have = plugin.tokens().balance(id);
        if (have < cost) {
            return fail("You need " + (cost - have) + " more tokens.");
        }
        if (p.type() == PrizeType.HAT && PrizeItems.keeperItem(plugin, p, id, 0) == null) {
            return fail(NOTHING_MADE);
        }
        KeeperCommitted r;
        try {
            r = commitKeeper(plugin.database(), dao, prizes.dao(),
                    (who, n, detail) -> plugin.tokens().spend(who, n, TokenService.Source.PRIZE, detail),
                    id, p, cost, now(), "");
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not save " + player.getName() + "'s buy of '" + p.id()
                    + "' (nothing charged): " + e.getMessage());
            return fail(NOT_SAVED);
        }
        switch (r.status()) {
            case ALREADY -> {
                return fail("You already have that.");
            }
            case NOT_PAID -> {
                return fail("You need " + cost + " tokens.");
            }
            default -> {
            }
        }
        reload(id);
        player.closeInventory(); // its moment where it can be seen: on Bedrock the chest screen covers the view
        String where = handOverKeeper(player, p, r.number(), false);
        plugin.getLogger().info(player.getName() + " bought the Keeper " + p.id() + " (" + cost + " tokens"
                + (r.number() > 0 ? ", No. " + r.number() : "") + ")");
        yours(player, p);
        player.sendMessage(Text.of("&a✔ " + Text.plain(p.display()) + " is yours for good!"
                + (r.number() > 0 ? " &7No. " + r.number() + "." : "") + " &a" + where));
        Sounds.paid(player);
        return new Outcome(true, null, PrizeService.icon(p), p.display() + " &7(yours for good)", true, false);
    }

    /**
     * A Keeper given free (an admin's give-back or test give): {@link #commitKeeper} with no charge, then the hand-over
     * as a present's (an old helmet is swapped into the bag). {@code givenBy} is the admin's UUID ('' for none).
     *
     * @return where it went, for the chat line; null when it couldn't be saved
     */
    String grantKeeper(Player target, Prize p, String givenBy, boolean present) {
        UUID to = target.getUniqueId();
        KeeperCommitted r;
        try {
            r = commitKeeper(plugin.database(), dao, plugin.prizes().dao(), null, to, p, 0, now(), givenBy);
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not give the Keeper '" + p.id() + "' to " + target.getName() + ": "
                    + e.getMessage());
            return null;
        }
        reload(to);
        return handOverKeeper(target, p, r.number(), present);
    }

    /**
     * {@code /hcm give prize keeper_* [player]} (§5.4): a Keeper given back free, for an admin restore. It claims the row
     * (or finds it there) and writes its rights; a hat's key goes as a present's does (never the ground), a trail is
     * switched on. Given by a player to someone else it is a present, with its moment.
     */
    public Outcome restoreKeeper(Player target, Prize p, String fromName) {
        if (!Whereabouts.homeFromGames(plugin, target)) {
            return fail(target.getName() + " is in a game. Try again when they're back.");
        }
        if (target.isDead()) {
            return fail(target.getName() + " is respawning. Try again in a moment.");
        }
        boolean present = fromName != null && !fromName.isBlank() && !fromName.equalsIgnoreCase(target.getName());
        boolean had = plugin.prizes().owned(target, p);
        String where = grantKeeper(target, p, "", present);
        if (where == null) {
            return fail(NOT_SAVED);
        }
        if (present) {
            plugin.prizes().presentMoment(target, fromName, p, where, p.type() == PrizeType.HAT);
            titleShown(target, TITLE_MS);
        } else {
            target.sendMessage(Text.of("&a✔ " + p.display() + " &ais yours" + (had ? " again! &7" : "! &7") + where));
            Sounds.received(target);
        }
        return new Outcome(true, null, PrizeService.icon(p), p.display() + " &7(yours for good)", true, false);
    }

    /**
     * After a Keeper's commit, the same tick: a hat's key goes where a buy's (or a present's) does ({@link #handOver}),
     * and the Crown's Royal Trail switches on with it; a trail switches on with its burst. Then the sweep (one key per
     * look, and the mark).
     *
     * @return where it is, for the chat line
     */
    private String handOverKeeper(Player r, Prize p, long number, boolean present) {
        String where;
        if (p.type() == PrizeType.HAT) {
            ItemStack key = PrizeItems.keeperItem(plugin, p, r.getUniqueId(), number);
            where = key == null ? LookRules.where(Placement.NOWHERE) : handOver(r, p, key, present);
            String with = p.extra().keeper().withTrail();
            if (with != null) {
                plugin.trails().select(r, with, false);
            }
        } else {
            if (present) {
                PrizeService.closeOurMenuFor(r); // the burst is part of the moment
            }
            plugin.trails().select(r, p.id());
            where = "Your trail is on!";
        }
        plugin.trails().load(r);
        sweep(r);
        return where;
    }

    /**
     * {@code /hcm looks end <player> <id> [days]}: with no days, the run ends now; with days (a decimal, 0.5 is
     * half a day), its end moves to now plus that, so the heads-ups can be tried. A short test look's run would be too
     * short for the 7-day and 1-day ones ({@link LookRules#due} wants a run longer than the time left), so the run's
     * start is moved back to at least 30 days before the new end. A collector or a Keeper is kept for good and never
     * ended here. The key goes at the sweep this runs at once.
     *
     * @return null when it moved, else why not
     */
    public String end(UUID player, String id, Double days) {
        Look row;
        try {
            row = dao.look(player, id);
        } catch (SQLException e) {
            return "Couldn't read that: " + e.getMessage();
        }
        if (row == null) {
            return "They don't have that look.";
        }
        Prize p = plugin.config().arcade().prize(id);
        if (LookRules.kept(row, now()) || (p != null && !p.rental())) {
            return "That one is kept for good.";
        }
        long now = now();
        try {
            if (days == null) {
                dao.end(player, row.id(), now);
            } else {
                long at = now + Math.round(days * LookRules.DAY);
                long since = row.since() > 0 ? Math.min(row.since(), at - LookRules.WINDOW_AFTER) : row.since();
                dao.write(player, row.id(), row.kind(), at, since, row.givenBy());
            }
        } catch (SQLException e) {
            return "Couldn't save that: " + e.getMessage();
        }
        reload(player);
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            gate.open(player);
            tick(online);
        }
        return null;
    }

    /** {@code /hcm looks list <player>}: one line per row (id, kind, until / ended / for good, the last heads-up). */
    public List<String> list(UUID player) {
        List<String> out = new ArrayList<>();
        long now = now();
        try {
            for (Look l : dao.looks(player)) {
                out.add(listLine(l, now, zone()));
            }
        } catch (SQLException e) {
            out.add("&cCouldn't read their looks: " + e.getMessage());
        }
        return out;
    }

    /** One {@code /hcm looks list} line. */
    public static String listLine(Look l, long now, ZoneId zone) {
        String when = LookRules.kept(l, now) ? "&6for good"
                : l.expiresAt() > now ? "&auntil " + LookRules.until(LookRules.lastDay(l.expiresAt(), zone))
                : "&7ended " + LookRules.until(LookRules.lastDay(l.expiresAt(), zone));
        String notice = switch (l.noticeStage()) {
            case 7 -> "7-day";
            case 1 -> "1-day";
            case -1 -> "ended";
            default -> "none";
        };
        if (l.noticeStage() != 0 && l.noticeFor() != l.expiresAt()) {
            notice += " (for an older end)";
        }
        return "&f" + l.id() + " &7" + l.kind().toLowerCase(Locale.ROOT) + " " + when
                + (l.enabled() ? " &d(on)" : "") + " &8- last heads-up: " + notice
                + (l.givenBy() == null || l.givenBy().isBlank() ? "" : " &8- a present");
    }

    /**
     * {@code /hcm looks preview <id>} (§3.16): a non-persistent, invulnerable armor stand two blocks in front of the
     * admin, for 30 seconds, wearing a copy of the look tagged as a stray key ({@code look_preview}, bound to nobody,
     * {@code hcm:token_prize} = the id), or for a trail its puff around the stand for 10 seconds. Its slots are
     * locked, and it claims and grants nothing, so the daughter's screen can check any look.
     *
     * @return null when it stands, else why not
     */
    public String preview(Player admin, Prize p) {
        ItemStack copy = null;
        if (p.type() == PrizeType.HAT) {
            copy = previewCopy(p);
            if (copy == null) {
                return "That look can't be shown on a stand yet.";
            }
        } else if (p.type() != PrizeType.TRAIL) {
            return "Only a hat, a cap or a trail can be previewed.";
        }
        Location eye = admin.getLocation();
        org.bukkit.util.Vector ahead = eye.getDirection().setY(0);
        if (ahead.lengthSquared() < 1.0E-4) {
            ahead = new org.bukkit.util.Vector(0, 0, 1);
        }
        Location at = eye.clone().add(ahead.normalize().multiply(2));
        at.setYaw(eye.getYaw() + 180);
        at.setPitch(0);
        ItemStack worn = copy;
        ArmorStand stand = admin.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setPersistent(false); // a hard stop can't save it into the chunk (red team D-m17)
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setMarker(false);
            s.setBasePlate(false);
            s.setCanPickupItems(false);
            s.addDisabledSlots(EquipmentSlot.values());
            s.getPersistentDataContainer().set(Keys.LOOK_PREVIEW, PersistentDataType.BYTE, (byte) 1);
            s.customName(Text.of("&7Preview: " + p.display()));
            s.setCustomNameVisible(true);
            if (worn != null) {
                s.getEquipment().setHelmet(worn);
            }
        });
        previewStands.add(stand.getUniqueId());
        if (p.type() == PrizeType.TRAIL) {
            // the trail's pattern in the form the config picks, at its own pace (a Keeper's rotates its steps)
            long every = p.keeper() && Keepers.pattern(p.id(), Keepers.Variant.DEFAULT) != null
                    ? Math.max(1, Keepers.pattern(p.id(), Keepers.Variant.DEFAULT).puffMs() / 50) : 3L;
            BukkitTask[] puffs = new BukkitTask[1];
            long[] left = {PREVIEW_TRAIL_TICKS};
            int[] step = {0};
            puffs[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                left[0] -= every;
                if (left[0] < 0 || !stand.isValid() || !plugin.trails().puffAt(stand.getLocation(), p, step[0]++)) {
                    puffs[0].cancel();
                }
            }, 1L, every);
        } else if (p.keeper() && plugin.config().arcade().keepers().marks()) {
            // a worn Keeper's mark over the stand, at the mark's own pace (§5.3a)
            TrailService.Step mark = Keepers.mark(p.id(),
                    Keepers.variant(plugin.config().arcade().keepers(), Keepers.ROYAL));
            long every = Keepers.MARK_MS / 50;
            BukkitTask[] puffs = new BukkitTask[1];
            long[] left = {PREVIEW_TRAIL_TICKS};
            puffs[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                left[0] -= every;
                if (left[0] < 0 || !stand.isValid() || !plugin.trails().puffAt(stand.getLocation(), mark)) {
                    puffs[0].cancel();
                }
            }, 1L, every);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            stand.remove();
            previewStands.remove(stand.getUniqueId());
        }, PREVIEW_TICKS);
        return null;
    }

    /** A preview stand's copy: the look, bound to nobody and marked a preview, so any sweep or guard removes it. */
    private ItemStack previewCopy(Prize p) {
        ItemStack copy;
        LocalDate last = LookRules.today(now(), zone()).plusDays(Math.max(1, p.days()));
        if (p.collector() != null) {
            copy = PrizeItems.collectorCap(plugin, p, null);
        } else if (p.keeper()) {
            copy = PrizeItems.keeperItem(plugin, p, NOBODY, 0); // in the form the config picks now
        } else {
            copy = PrizeItems.lookKey(plugin, p, NOBODY, last, LookRules.endOf(last, zone()));
        }
        if (copy == null) {
            return null;
        }
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return null;
        }
        meta.getPersistentDataContainer().set(Keys.LOOK_PREVIEW, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(Keys.BOUND_TO, PersistentDataType.STRING, NOBODY.toString());
        copy.setItemMeta(meta);
        if (p.keeper()) {
            // the Keeper's components again after the meta's round trip, in the form the config picks now
            PrizeItems.restyleKeeper(copy, p, Keepers.variant(plugin.config().arcade().keepers(), p.id()),
                    plugin.getLogger());
        } else if (p.collector() == null) {
            PrizeItems.stampKey(copy, p, NOBODY, last, LookRules.endOf(last, zone())); // the cap's look again
        }
        return copy;
    }

    /** The online players a look list or the menus may name, for the account move's "in a game" check. */
    public boolean inGame(UUID player) {
        Player p = Bukkit.getPlayer(player);
        return p != null && (Whereabouts.inGameSession(plugin, p) || !Whereabouts.homeFromGames(plugin, p));
    }

    /** After an account move: both players' looks read again, their trails reloaded. */
    public void moved(Collection<UUID> players) {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                reload(id);
                plugin.trails().load(p);
                sweep(p);
            } else {
                cache.remove(id);
            }
        }
    }
}
