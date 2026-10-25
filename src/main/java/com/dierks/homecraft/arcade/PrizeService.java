package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService.Outcome;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.integration.EconomySandbox;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Keys;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import com.dierks.homecraft.util.Whereabouts;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The Prize Counter (§3.9): fixed-price token purchases, sorted into tabs, with per-day /
 * per-week / lifetime limits and "you already own this".
 *
 * <p>The order of a purchase is the one the counter has always had, and it matters: every check
 * first, then build the item (so a prize that cannot be produced costs nothing), then charge,
 * then record — so the ledger line and the limit tick only for something that exists. A row with a
 * limit, a price list, a row it needs first, or a command, a trophy or a party (D30, SHOP-V2-SPEC §5.9)
 * is charged, counted and numbered in ONE database transaction, so a failed write charges nothing and a
 * second confirm finds the limit used; a trophy that doesn't fit the bag goes to the Mailbox in it, never
 * the ground. A golf ball is a claim and a charge in one transaction too, with no item at all.
 *
 * <p>Nothing here touches a dollar. Every physical prize carries {@code hcm:token_prize}, which
 * every money surface refuses, and no row type may reach a money flow (no Courier extras, no
 * shipping, no market limits, no printer fees): §11 #9.
 *
 * <p>A collector ({@link Collectors}) goes its own way: sold only in its season, never from a crate,
 * and claimed BEFORE the charge ({@link PrizeDao#claimOnce}), so a second confirm or a failed write
 * can never sell a second one. A collector is never dropped on the ground: with no room it is
 * refused, or refunded and unclaimed.
 *
 * <p>A rented look (0.38, {@link Prize#rental}: a hat, a cap or a trail for its days) goes its own way too, to the
 * wardrobe ({@link com.dierks.homecraft.arcade.looks.LookService#purchase}): the right and the tokens in one
 * transaction, its price the buy-again price while the window is open. Presents cover rented looks and golf balls
 * as well as the collectors, never a Keeper.
 */
public final class PrizeService {

    /**
     * The Arcade Trophy's serial, in {@code arcade_state}; every other trophy row has its own
     * ({@link #trophySerialKey}).
     */
    private static final String TROPHY_SERIAL = "trophy_serial";
    /** The row whose serial is {@link #TROPHY_SERIAL}, as it always was. */
    private static final String ARCADE_TROPHY = "arcade_trophy";
    /** A trophy that didn't fit the bag, in the Mailbox ({@code deliveries.source}). */
    static final String MAILBOX_SOURCE = "PRIZE";
    /** At most one of a player's announcements (a trophy, a party) a minute (A7's "never spam"). */
    static final long ANNOUNCE_GAP_MS = 60_000;

    /** {@link #wearOrGive}'s mode: a present (an old helmet is swapped into the bag) or a buy (it never is). */
    public static final boolean GIFT = true;
    public static final boolean BUY = false;

    private static final String NOTHING_MADE = "That prize couldn't be made — nothing charged.";
    private static final String NOT_SAVED = "That couldn't be saved — nothing charged. Tell an admin.";
    private static final String NO_TRAIL = "Couldn't add that trail — nothing charged. Tell an admin.";
    private static final String NOT_A_COLLECTOR = "That isn't a collector.";
    private static final String NOT_RUN = "That didn't work — nothing charged. Tell an admin.";
    /** The tile or the confirm screen showed another price than a buy would charge now (the Arcade Trophy's). */
    static final String PRICE_CHANGED = "The price changed — look again.";
    /** One party at a time on the server ({@link PartyService}). */
    static final String PARTY_ON = "A party is on right now - try again in a moment.";
    /** Make it Day where there is no day or night (the Nether, the End): refused before any charge. */
    static final String NO_CLOCK = "There's no day or night here!";
    /**
     * Why a collector trail in {@link #trailsNotHere} isn't given back. It names no version, so it is true for
     * every collector trail: the Autumn Leaves 2026's particle is new in the 26.3 API, and a later one may be
     * newer still. The collector self-test's line says the same.
     */
    private static final String TRAIL_NOT_HERE = "That trail can't show on this server yet.";

    /** Where a collector cap goes: on the head, on the head with the old helmet into the bag, or into the bag. */
    public enum CapPlacement { HEAD, SWAP, BAG }

    private final HomeCraftManagement plugin;
    private final PrizeDao dao;
    /**
     * The trail rows this server can't show (final audit F7; P2-lite, SHOP-V2-SPEC §5.11): on 26.2 the Autumn
     * Leaves trail's particle doesn't exist, and a config trail may name a particle that needs data, so such a row
     * is not sold or given rather than charged for and never drawn. Worked out again whenever the rows are
     * (a reload); a config row's reason is one WARN each time, a collector's is the collector self-test's. Owned
     * rows keep loading, so one shows after an upgrade.
     */
    private Set<String> trailsNotHere;
    /** The rows {@link #trailsNotHere} was worked out for. */
    private List<Prize> notHereFor;
    /** When each player's last announcement went out ({@link #announce}). */
    private final Map<UUID, Long> lastAnnounce = new HashMap<>();

    public PrizeService(HomeCraftManagement plugin, PrizeDao dao) {
        this.plugin = plugin;
        this.dao = dao;
        this.trailsNotHere = trailsNotHere(Collectors.ROWS, TrailService::particleUsable);
    }

    public PrizeDao dao() {
        return dao;
    }

    // ---- bound keys ------------------------------------------------------------------------

    /**
     * Whether an item is a key bound to one player (SHOP-V2-SPEC §3.6, D29): it carries {@code hcm:bound_to} (a
     * rented hat or cap), or its prize id is a Keeper's ({@code keeper_...}). Such an item stays with its owner:
     * the one test the guard, the trade window and presents use. It reads the data view, never the meta, because
     * it runs on every click, interact and item spawn; a hat sold before 0.38 has neither and is never bound.
     */
    public static boolean bound(ItemStack item) {
        PersistentDataContainerView pdc = view(item);
        if (pdc == null) {
            return false;
        }
        try {
            return bound(pdc.get(Keys.BOUND_TO, PersistentDataType.STRING),
                    Keys.TOKEN_PRIZE == null ? null : pdc.get(Keys.TOKEN_PRIZE, PersistentDataType.STRING));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The UUID an item is bound to ({@code hcm:bound_to}), or null: none, or not a UUID. Reads the data view. */
    public static UUID boundTo(ItemStack item) {
        PersistentDataContainerView pdc = view(item);
        if (pdc == null) {
            return null;
        }
        try {
            return boundTo(pdc.get(Keys.BOUND_TO, PersistentDataType.STRING));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@link #bound(ItemStack)} on the two values it reads: {@code hcm:bound_to} and the prize id (either may be null). */
    static boolean bound(String boundTo, String prizeId) {
        return (boundTo != null && !boundTo.isBlank()) || Keepers.reserved(prizeId);
    }

    /** {@link #boundTo(ItemStack)} on the value it reads: the UUID, or null when it is absent or not a UUID. */
    static UUID boundTo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An item's read-only data view, or null for nothing, air or before the keys exist. */
    private static PersistentDataContainerView view(ItemStack item) {
        if (item == null || Keys.BOUND_TO == null || item.getType().isAir()) {
            return null;
        }
        try {
            return item.getPersistentDataContainer();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- what a player sees ----------------------------------------------------------

    /**
     * Whether a row appears at all: enabled, its plugins present, and on the shelf by {@link #shelved}'s rules (a
     * textured hat with no texture, hunt gear with no wild hunt and a trail this server can't show are hidden; a
     * collector only in its season).
     */
    public boolean visible(Prize p) {
        if (!p.enabled()) {
            return false;
        }
        for (String name : p.requiresPlugins()) {
            if (Bukkit.getPluginManager().getPlugin(name) == null) {
                return false;
            }
        }
        return shelved(p, shelf());
    }

    /**
     * What decides whether a row is on the shelf, read once per question: {@code arcade.collectors.on_sale}, the
     * local date, the trail rows this server can't show ({@link #trailsNotHere}), and whether a wild hunt is set
     * (a {@code NATURAL_SPAWN} loot source).
     */
    record Shelf(boolean collectorsOnSale, LocalDate today, Set<String> notHere, boolean wildHunt,
                 boolean keepersOnSale) {
        /** A shelf with the Keepers on sale ({@code arcade.keepers.on_sale}'s default). */
        Shelf(boolean collectorsOnSale, LocalDate today, Set<String> notHere, boolean wildHunt) {
            this(collectorsOnSale, today, notHere, wildHunt, true);
        }
    }

    private Shelf shelf() {
        Loot.MiniLoot loot = plugin.config().miniLoot();
        return new Shelf(plugin.config().arcade().collectorsOnSale(), plugin.clock().today(), notHere(),
                loot != null && !loot.sourcesFor(Loot.Trigger.NATURAL_SPAWN).isEmpty(),
                plugin.config().arcade().keepers().onSale());
    }

    /**
     * {@link #visible(Prize)} past the plugins: enabled, and
     * <ul>
     *   <li>a collector only while collectors are on sale and today is in its season, and a collector trail only
     *       on a server that can show it ({@link #offered}); it is a dyed helmet, not a head, so the texture rule
     *       is not its;</li>
     *   <li>no trail whose particle this server can't show (P2-lite: any trail row, not only a collector);</li>
     *   <li>a hat needs a texture to show (one with none is a Steve head, which is not a prize), unless it names
     *       its {@code material}: the game's own Creeper or Dragon head, or a cap (P3);</li>
     *   <li>the Mini Radar and Lure only while a wild hunt is set, so nobody buys a radar with nothing to find
     *       (P9; it also takes the radar out of the crate's pool);</li>
     *   <li>a Keeper while {@code arcade.keepers.on_sale} (an owned one is drawn on the Keepers tab anyway), never a
     *       companion (the Royal Trail comes with the Crown and is never a tile), and a Keeper trail only on a server
     *       that can show it; a worn Keeper is a trimmed helmet, so the texture rule is not its (§5.4).</li>
     * </ul>
     * Pure, so the shelf's rules are pinned by a test.
     */
    static boolean shelved(Prize p, Shelf s) {
        if (!p.enabled()) {
            return false;
        }
        if (p.collector() != null) {
            return offered(p, s.collectorsOnSale(), s.today(), s.notHere());
        }
        if (p.keeper()) {
            return s.keepersOnSale() && !p.extra().keeper().companion() && !s.notHere().contains(p.id());
        }
        if (p.type() == PrizeType.TRAIL && s.notHere().contains(p.id())) {
            return false;
        }
        if (p.type() == PrizeType.HAT && p.extra().hatMaterial() == null
                && (p.texture() == null || p.texture().isBlank())) {
            return false;
        }
        return (p.type() != PrizeType.RADAR && p.type() != PrizeType.LURE) || s.wildHunt();
    }

    /** {@link #trailsNotHere} for the rows loaded now, worked out again (with its WARNs) after a reload. */
    private Set<String> notHere() {
        List<Prize> rows = plugin.config().arcade().prizes();
        if (rows != notHereFor) {
            trailsNotHere = trailsNotHere(rows, TrailService::particleUsable, plugin.getLogger()::warning);
            notHereFor = rows;
        }
        return trailsNotHere;
    }

    /**
     * A collector's season gate: {@code arcade.collectors.on_sale} and today's local date inside its
     * season (both ends included). Out of season it is hidden, can't be bought and can't be given;
     * the ones players own keep working.
     */
    static boolean onSale(Prize p, boolean collectorsOnSale, LocalDate today) {
        return p.collector() != null && collectorsOnSale && p.collector().inSeason(today);
    }

    /**
     * Whether a collector is shown, sold and given now: {@link #onSale}, and not one of {@code notHere}, the
     * collector trails this server can't show ({@link #trailsNotHere}). Hidden, it has no tile, a buy or a
     * present is refused ("That isn't for sale right now."), and "Give a present" doesn't offer it.
     */
    static boolean offered(Prize p, boolean collectorsOnSale, LocalDate today, Set<String> notHere) {
        return onSale(p, collectorsOnSale, today) && !notHere.contains(p.id());
    }

    /**
     * The ids of the trail rows among {@code rows} whose particle {@code usable} refuses
     * ({@link TrailService#particleUsable}): the Autumn Leaves trail on 26.2, none on 26.3, and any config trail
     * naming a particle that needs data (P2-lite).
     */
    static Set<String> trailsNotHere(List<Prize> rows, Predicate<String> usable) {
        return trailsNotHere(rows, usable, null);
    }

    /**
     * {@link #trailsNotHere(List, Predicate)}, with one line to {@code warn} for each config row it hides (a
     * collector's is the collector self-test's to say); {@code warn} may be null.
     */
    static Set<String> trailsNotHere(List<Prize> rows, Predicate<String> usable, Consumer<String> warn) {
        Set<String> out = new HashSet<>();
        for (Prize p : rows) {
            if (p.type() == PrizeType.TRAIL && !usable.test(p.particle())) {
                out.add(p.id());
                if (warn != null && p.collector() == null && !p.keeper()) { // the self-tests speak for their own
                    warn.accept("Arcade prize '" + p.id() + "' is a trail of " + p.particle() + ", which can't show on"
                            + " this server (it isn't a particle here, or it needs data) - the row is hidden. Pick a"
                            + " particle such as HEART or END_ROD.");
                }
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Why {@link #restore} won't give {@code p} back at all, before it looks at the player: not a collector, or a
     * collector trail this server can't show ({@code notHere}, {@link #trailsNotHere}). Null when it may go on.
     */
    static String restoreRefusal(Prize p, Set<String> notHere) {
        if (p == null || (p.collector() == null && !p.keeper())) {
            return NOT_A_COLLECTOR;
        }
        if (p.keeper() && p.extra().keeper().companion()) {
            return "That comes with the Legend Crown: give the Crown.";
        }
        return notHere.contains(p.id()) ? TRAIL_NOT_HERE : null;
    }

    /** The rows a player sees on one tab. */
    public List<Prize> visible(PluginConfig.PrizeTab tab) {
        List<Prize> out = new ArrayList<>();
        for (Prize p : plugin.config().arcade().prizes(tab)) {
            if (visible(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The rows this player sees on one tab: {@link #visible(Prize)}, and a +1 Home row only for
     * someone it can add to (not unlimited, a base under 20, Essentials and LuckPerms present).
     */
    public List<Prize> visible(Player player, PluginConfig.PrizeTab tab) {
        List<Prize> out = new ArrayList<>();
        for (Prize p : visible(tab)) {
            if (visibleTo(player, p)) {
                out.add(p);
            }
        }
        return out;
    }

    public boolean visibleTo(Player player, Prize p) {
        if (!visible(p)) {
            return false;
        }
        return p.type() != PrizeType.HOME_SLOT || (plugin.homes() != null && plugin.homes().offered(player));
    }

    /**
     * What this player's next one costs: a +1 Home gets dearer with each one bought, and a rented look costs its
     * buy-again price while their window is open (SHOP-V2-SPEC §3.4).
     */
    public int price(UUID player, Prize p) {
        if (p.rental() && plugin.looks() != null) {
            return plugin.looks().price(player, p);
        }
        if (p.costs() == null || p.costs().isEmpty()) {
            return p.costTokens();
        }
        try {
            return p.priceAfter(dao.purchases(player, p.id(), "life"));
        } catch (SQLException e) {
            return p.priceAfter(Integer.MAX_VALUE);
        }
    }

    /**
     * True if the player already has this for good: the permission it grants, or a lifetime
     * limit already used up.
     */
    public boolean owned(Player player, Prize p) {
        if (p.ownedIfPermission() != null && player.hasPermission(p.ownedIfPermission())) {
            return true;
        }
        return p.limit() != null && p.limit().per() == LimitPer.LIFETIME && left(player.getUniqueId(), p) <= 0;
    }

    /** Buys left this period; {@link Integer#MAX_VALUE} when the row has no limit. */
    public int left(UUID player, Prize p) {
        if (p.type() == PrizeType.PITY) {
            return plugin.arcade().pityLeft(player); // one weekly limit, however the exchange is reached
        }
        if (p.limit() == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return Math.max(0, p.limit().count() - dao.purchases(player, p.id(), periodKey(p.limit().per())));
        } catch (SQLException e) {
            return 0; // unknown is not "available"
        }
    }

    /** "1 left today", "0 left this week", "1 left this month", or null when the row has no limit. */
    public String limitText(UUID player, Prize p) {
        if (p.type() == PrizeType.PITY) {
            return limitLine(left(player, p), LimitPer.WEEK);
        }
        if (p.limit() == null) {
            return null;
        }
        return limitLine(left(player, p), p.limit().per());
    }

    /** "1 left today" (and this week, this month), or null for a lifetime limit, which a tile says as "owned". */
    static String limitLine(int left, LimitPer per) {
        return per == null || per == LimitPer.LIFETIME ? null : left + " left " + periodWords(per);
    }

    /** A limit period in words: "today", "this week", "this month", and "for good" for a lifetime one. */
    public static String periodWords(LimitPer per) {
        return switch (per) {
            case DAY -> "today";
            case WEEK -> "this week";
            case MONTH -> "this month";
            case LIFETIME -> "for good";
        };
    }

    /** Why a row cannot be bought yet because it needs another one first, or null. */
    public String lockedBecause(Player player, Prize p) {
        if (p.requiresPrize() == null) {
            return null;
        }
        Prize needed = plugin.config().arcade().prize(p.requiresPrize());
        if (needed == null) {
            return null;
        }
        if (owned(player, needed)) {
            return null;
        }
        try {
            if (dao.everBought(player.getUniqueId(), needed.id())) {
                return null;
            }
        } catch (SQLException ignored) {
            // fall through to locked
        }
        return "Get " + Text.plain(needed.display()) + " first";
    }

    /** The limit period key, on the players' local calendar. */
    public String periodKey(LimitPer per) {
        return switch (per) {
            case DAY -> "d" + plugin.clock().dayKey();
            case WEEK -> "w" + plugin.clock().weekKey(weekStart());
            case MONTH -> monthKey(plugin.clock().today());
            case LIFETIME -> "life";
        };
    }

    /** A MONTH limit's period: {@code m2026-11}, the local calendar month of {@code today}. */
    static String monthKey(LocalDate today) {
        return "m" + java.time.YearMonth.from(today);
    }

    private java.time.DayOfWeek weekStart() {
        PluginConfig.Quests q = plugin.config().quests();
        return q == null || q.weekStartsOn() == null ? java.time.DayOfWeek.MONDAY : q.weekStartsOn();
    }

    /**
     * Count one buy against a row's limit (no-op for a row that counts nothing, {@link #counts}). It throws, so a
     * buy can count inside its charge's transaction and charge nothing when the count can't be written (D30).
     */
    public void recordPurchase(UUID player, Prize p) throws SQLException {
        if (counts(p)) {
            dao.addPurchase(player, p.id(), purchaseKey(p));
        }
    }

    /** Whether a buy of {@code p} is counted: it has a limit, or is a command, or another row needs it first. */
    static boolean counts(Prize p) {
        return p.limit() != null || p.type() == PrizeType.COMMAND || p.requiresPrize() != null;
    }

    /** The period a buy of {@code p} counts in: its limit's, else {@code life}. */
    private String purchaseKey(Prize p) {
        return p.limit() != null ? periodKey(p.limit().per()) : "life";
    }

    // ---- buying ------------------------------------------------------------------------

    /**
     * Buy a row. {@code color} is the buyer's pick for a filament row that lets them choose.
     * PITY runs the pity exchange; TRADE_IN and QUEST_REROLL are screens, not purchases, and are
     * refused here (their menus call their own paths).
     */
    public Outcome buy(Player player, Prize p, DyeColor color) {
        return buy(player, p, color, -1);
    }

    /**
     * {@link #buy(Player, Prize, DyeColor)} at the price a confirm screen or tile showed ({@code shownCost}; -1 =
     * none shown): when the price has moved since (the Arcade Trophy's, after a first confirm went through) the buy
     * is refused with nothing charged, so a second tap on the same screen can never buy a second one dearer. A
     * rented look goes to the wardrobe ({@code LookService.purchase}), which makes the same check inside its own
     * transaction ("The price changed — look again.").
     */
    public Outcome buy(Player player, Prize p, DyeColor color, int shownCost) {
        if (p.rental() && plugin.looks() != null) {
            return plugin.looks().purchase(player, player.getUniqueId(), p, shownCost);
        }
        if (p.keeper()) {
            // one each, for good: the claim, the rights and the tokens in one transaction (§5.4)
            return plugin.looks() != null ? plugin.looks().buyKeeper(player, p, shownCost)
                    : Outcome.fail("That isn't for sale right now.");
        }
        if (!plugin.sandbox().check(player, "prize purchase " + p.id())) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        if (!visibleTo(player, p)) {
            return Outcome.fail("That isn't for sale right now.");
        }
        String locked = lockedBecause(player, p);
        if (locked != null) {
            return Outcome.fail(locked + ".");
        }
        if (owned(player, p)) {
            return Outcome.fail("You already have that.");
        }
        UUID id = player.getUniqueId();
        if (left(id, p) <= 0) {
            return Outcome.fail(usedUp(p));
        }
        int have = plugin.tokens().balance(id);
        int cost = price(id, p);
        if (shownCost >= 0 && cost != shownCost) {
            return Outcome.fail(PRICE_CHANGED);
        }
        if (have < cost) {
            return Outcome.fail("You need " + (cost - have) + " more tokens.");
        }
        if (p.collector() != null) {
            return buyCollector(player, p, cost); // claims its own row: no recordPurchase after it
        }
        Outcome out = switch (p.type()) {
            case PITY -> plugin.arcade().pity(player);
            case TRADE_IN, QUEST_REROLL -> Outcome.fail("Open that from the Prize Counter.");
            case BALL -> buyBall(player, p, cost);
            // a counted row is counted inside its charge's transaction (D30): nothing to record after it
            default -> deliver(player, p, color, true, 0, cost);
        };
        if (out.ok() && p.type() != PrizeType.PITY) { // the pity exchange plays its own reveal
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        }
        return out;
    }

    /** "You've had all of those for today." (this week, this month, for good) */
    private static String usedUp(Prize p) {
        LimitPer per = p.type() == PrizeType.PITY || p.limit() == null ? LimitPer.WEEK : p.limit().per();
        return "You've had all of those " + (per == LimitPer.LIFETIME ? "" : "for ") + periodWords(per) + ".";
    }

    /**
     * Quest Reroll: swap one of today's unfinished dailies. Checks everything and finds the
     * replacement BEFORE charging, and refunds if the swap then fails, so a reroll with nothing
     * to swap to costs nothing.
     */
    public Outcome rerollQuest(Player player, Prize p, String questId) {
        if (!plugin.sandbox().check(player, "quest reroll")) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        UUID id = player.getUniqueId();
        if (!visible(p) || p.type() != PrizeType.QUEST_REROLL) {
            return Outcome.fail("That isn't for sale right now.");
        }
        if (left(id, p) <= 0) {
            return Outcome.fail("You've already swapped a quest today.");
        }
        int have = plugin.tokens().balance(id);
        if (have < p.costTokens()) {
            return Outcome.fail("You need " + (p.costTokens() - have) + " more tokens.");
        }
        PluginConfig.Quest old = plugin.config().quests().byId(PluginConfig.QuestPeriod.DAILY, questId);
        if (old == null || plugin.quests().rerollCandidate(player, old) == null) {
            return Outcome.fail("There's no other quest to swap in. Nothing was charged.");
        }
        if (!plugin.tokens().spend(id, p.costTokens(), TokenService.Source.PRIZE, p.display())) {
            return Outcome.fail("You need " + p.costTokens() + " tokens.");
        }
        PluginConfig.Quest next = plugin.quests().reroll(player, questId);
        if (next == null) {
            plugin.tokens().grant(id, p.costTokens(), TokenService.Source.REFUND, p.display());
            return Outcome.fail("That quest couldn't be swapped. Nothing was charged.");
        }
        try {
            recordPurchase(id, p);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not record a prize purchase: " + e.getMessage());
        }
        return Outcome.won(com.dierks.homecraft.gui.Menus.icon(Material.WRITABLE_BOOK, "&f" + next.display()),
                "&f" + next.display());
    }

    /**
     * Give a row free, as a crate prize: no charge and no limit. A filament row with no colour of
     * its own gets a random one; a trail gets {@code daysOverride} days when that is above 0.
     */
    public Outcome grant(Player player, Prize p, int daysOverride) {
        if (p.collector() != null) {
            return restore(player, p, null); // never from a crate (grantable), so only a give-back reaches here
        }
        return deliver(player, p, null, false, daysOverride, -1);
    }

    /** Whether a row can be handed out by a crate at all right now. */
    public boolean grantable(Prize p) {
        if (p.collector() != null || p.keeper()) {
            return false; // checked first: a crate never gives a collector, in season or not, nor a Keeper
        }
        return visible(p) && crateable(p);
    }

    /**
     * {@link #grantable(Prize)} past the plugins: never a collector, on the shelf ({@link #shelved}: no hunt gear
     * without a wild hunt), and a kind a crate may give ({@link #crateable}: never a hat). What a crate's pool and
     * its published value read, through the instance form.
     */
    static boolean grantable(Prize p, Shelf s) {
        return p.collector() == null && !p.keeper() && shelved(p, s) && crateable(p);
    }

    /**
     * The kinds of row a crate may hand out. Never a collector: a crate's "any trail" pool would pay
     * the kept-for-good trail for a day, and a crated collector would bypass its one-each claim. Never a
     * hat (D11): a hat is bought for its days, and a known-price look must not sit behind a game of chance
     * (an owner's crate still naming the old hat row pays nothing from it). Never a golf ball or a party. Never a
     * Keeper: one each, for good, bought and never won (this also keeps the Keeper trails out of the crate's trail
     * pool).
     */
    static boolean crateable(Prize p) {
        if (p.collector() != null || p.keeper()) {
            return false;
        }
        return switch (p.type()) {
            case BOOST, RADAR, LURE, FIREWORK, TRAIL, TROPHY, FILAMENT, BLOCK, PACK -> true;
            default -> false; // hats, balls, parties, perks, pity, trade-in and rerolls are bought, never won
        };
    }

    /**
     * Build, (optionally) charge, and hand over. The item is built BEFORE the charge, so a prize
     * that cannot be produced right now costs nothing, and everything that can refuse (a party already
     * on, Make it Day in daylight) refuses before it too. {@code shown} is the price the buyer was shown
     * (-1 = none). A row that counts, or a trophy, goes through {@link #deliverAsOne}.
     */
    private Outcome deliver(Player player, Prize p, DyeColor color, boolean charge, int daysOverride, int shown) {
        UUID id = player.getUniqueId();
        int cost = price(id, p);
        ItemStack item = null;
        String label = p.display();
        long trophyNumber = 0;
        switch (p.type()) {
            case BOOST, RADAR, LURE, FIREWORK, HAT, REMOTE -> {
                if (p.type() == PrizeType.HAT && (p.rental() || p.keeper())) {
                    // a rented hat or a Keeper is a key from the wardrobe (LookService), never a loose hat
                    return Outcome.fail(NOTHING_MADE);
                }
                item = PrizeItems.build(plugin, p);
                if (item == null) {
                    return Outcome.fail(NOTHING_MADE);
                }
            }
            case TROPHY -> {
                try {
                    trophyNumber = dao.state(trophySerialKey(p.id()), 0) + 1;
                } catch (SQLException e) {
                    return Outcome.fail("The trophy shelf is stuck — nothing charged. Tell an admin.");
                }
                item = PrizeItems.trophy(plugin, p, trophyNumber, player.getName());
                label = "&6" + Text.plain(PrizeItems.trophyName(p, trophyNumber, plugin.clock().today()));
            }
            case FILAMENT -> {
                DyeColor dye = p.color() != null ? p.color() : color;
                if (dye == null && !charge) {
                    DyeColor[] all = DyeColor.values();
                    dye = all[ThreadLocalRandom.current().nextInt(all.length)];
                }
                if (dye == null) {
                    return Outcome.fail("Pick a filament colour first.");
                }
                item = plugin.miniService().filamentItems().filament(dye, p.amount());
                label = "&f" + p.amount() + " " + pretty(dye.name()) + " Filament";
            }
            case BLOCK -> {
                com.dierks.homecraft.block.CustomBlockType type;
                try {
                    type = com.dierks.homecraft.block.CustomBlockType.valueOf(p.blockKey());
                } catch (IllegalArgumentException e) {
                    return Outcome.fail("That prize isn't set up right — tell an admin.");
                }
                item = plugin.items().of(type);
                if (item == null) {
                    return Outcome.fail("That prize isn't available right now — nothing charged.");
                }
            }
            case PACK -> {
                var pack = plugin.packs() == null ? null : plugin.packs().pack(p.packId());
                if (pack == null) {
                    return Outcome.fail("That pack isn't sold any more — nothing charged.");
                }
                if (plugin.packs().soldOut(pack)) {
                    return Outcome.fail("That pack is sold out — nothing charged.");
                }
                // Bought here, it carries its price, so a pack that comes up short pays back.
                item = plugin.packs().packItem(p.packId(), charge
                        ? new com.dierks.homecraft.mini.PackItems.Paid(
                        com.dierks.homecraft.mini.PackItems.Currency.TOKENS, cost) : null);
            }
            case TRAIL -> {
                // nothing to build: applied after the charge
            }
            case COMMAND -> {
                // Make it Day at noon, Sunny Day in sunshine: refused before any charge, in the buyer's world.
                String pointless = onlyIfRefusal(p.extra().onlyIf(), player.getWorld());
                if (pointless != null) {
                    return Outcome.fail(pointless);
                }
            }
            case HOME_SLOT -> {
                // Everything that could refuse is checked here, before the charge.
                String refused = plugin.homes() == null ? "That isn't for sale right now."
                        : plugin.homes().refuseReason(player);
                if (refused != null) {
                    return Outcome.fail(refused);
                }
            }
            case PARTY -> {
                if (plugin.party() == null || plugin.party().running()) {
                    return Outcome.fail(PARTY_ON);
                }
            }
            default -> {
                return Outcome.fail("That prize can't be given like this.");
            }
        }
        if (charge && shown >= 0 && cost != shown) {
            return Outcome.fail(PRICE_CHANGED);
        }
        if (asOne(p, charge)) {
            return deliverAsOne(player, p, item, label, cost, charge, trophyNumber, daysOverride);
        }
        if (charge && !plugin.tokens().spend(id, cost, TokenService.Source.PRIZE, p.display())) {
            return Outcome.fail("You need " + cost + " tokens.");
        }
        switch (p.type()) {
            case TRAIL -> {
                int days = daysOverride > 0 ? daysOverride : p.days();
                if (!plugin.trails().grant(player, p, days)) {
                    if (charge) {
                        plugin.tokens().grant(id, cost, TokenService.Source.REFUND, p.display());
                    }
                    return Outcome.fail(NO_TRAIL);
                }
                label = trailLabel(p, days);
                item = icon(p);
            }
            default -> {
                giveOrDrop(player, item);
                remoteHandedOver(player, p);
            }
        }
        return Outcome.won(item.clone(), label);
    }

    /**
     * Whether a buy goes through {@link #deliverAsOne}: a counted row ({@link #counts}), a price list, a party, or
     * any trophy (its number and, with a full bag, its Mailbox row are written in the transaction, a crate's too).
     * A row whose hand-over lives only there ({@link #handedOverAsOne}) goes there free too, so an admin's
     * {@code /hcm give prize} of a command row runs its command as it always did.
     */
    static boolean asOne(Prize p, boolean charge) {
        if (p.type() == PrizeType.TROPHY || handedOverAsOne(p.type())) {
            return true;
        }
        return charge && (counts(p) || (p.costs() != null && !p.costs().isEmpty()));
    }

    /**
     * The kinds that build no item before the charge and are handed over only in {@link #deliverAsOne}: a command
     * (and its after-check), a party, an extra home. The plain path after it hands over an item or a trail only.
     */
    static boolean handedOverAsOne(PrizeType type) {
        return type == PrizeType.COMMAND || type == PrizeType.PARTY || type == PrizeType.HOME_SLOT;
    }

    /**
     * A buy as ONE database transaction (D30, §5.9; red team D-m11, D-m12): the limit read again inside the lock
     * (a second confirm ends here, nothing charged), the purchase counted, a trophy's number taken, the tokens
     * spent, and a trophy that doesn't fit the bag queued to the Mailbox, all or none. A failed write charges
     * nothing and gives nothing. Then, the same tick, the hand-over: a trail, a command (whose failure, or a
     * Make it Day that left it night, refunds and uncounts it in a second transaction), the extra home, the
     * party, the trophy into the slot found free before.
     */
    private Outcome deliverAsOne(Player player, Prize p, ItemStack item, String label, int cost, boolean charge,
                                 long trophyNumber, int daysOverride) {
        UUID id = player.getUniqueId();
        boolean trophy = p.type() == PrizeType.TROPHY;
        PlayerInventory inv = player.getInventory();
        int slot = trophy ? inv.firstEmpty() : -1;
        boolean mail = trophy && slot < 0;
        boolean counted = charge && counts(p);
        String key = purchaseKey(p);
        String trophyName = trophy ? Text.plain(label) : null;
        String refused = commitLimited(plugin.database(), new LimitSteps(
                        () -> recheck(id, p, cost, charge),
                        () -> {
                            if (counted) {
                                dao.addPurchase(id, p.id(), key);
                            }
                        },
                        () -> {
                            if (trophy) {
                                takeSerial(trophySerialKey(p.id()), trophyNumber);
                            }
                        },
                        () -> !charge || plugin.tokens().spend(id, cost, TokenService.Source.PRIZE, p.display()),
                        () -> {
                            if (mail) {
                                plugin.deliveries().queueReady(id, item, trophyName, MAILBOX_SOURCE);
                            }
                        }),
                "You need " + cost + " tokens.",
                e -> plugin.getLogger().warning("Could not save " + player.getName() + "'s buy of '" + p.id()
                        + "' (nothing charged): " + e.getMessage()));
        if (refused != null) {
            return Outcome.fail(refused);
        }
        int paid = charge ? cost : 0;
        ItemStack shown = item;
        String said = label;
        switch (p.type()) {
            case TRAIL -> {
                int days = daysOverride > 0 ? daysOverride : p.days();
                if (!plugin.trails().grant(player, p, days)) {
                    undo(id, p, paid, counted, key);
                    return Outcome.fail(NO_TRAIL);
                }
                said = trailLabel(p, days);
                shown = icon(p);
            }
            case COMMAND -> {
                // dispatchCommand's true only means the command was found (red team D-m18): a Make it Day that
                // left it night did nothing, so it is refunded like a command that failed.
                if (!runCommands(player, p) || didNothing(p.extra().onlyIf(), player.getWorld())) {
                    undo(id, p, paid, counted, key);
                    return Outcome.fail(NOT_RUN);
                }
                shown = icon(p);
            }
            case HOME_SLOT -> {
                int extra = counted ? 0 : 1; // a counted slot is in the total already; a free one only now, as before
                plugin.homes().refresh(player, extra);
                said = "&a+1 Home &7(you have " + (plugin.homes().total(player) + extra) + " now)";
                shown = icon(p);
            }
            case PARTY -> {
                if (!plugin.party().start(player, p.extra().seconds())) {
                    undo(id, p, paid, counted, key);
                    return Outcome.fail(PARTY_ON);
                }
                shown = icon(p);
            }
            case TROPHY -> {
                if (mail) {
                    player.sendMessage(Text.of("&e✉ Your bag was full - your " + trophyName + " is in your Mailbox."));
                } else {
                    putInSlot(player, slot, item, trophyName);
                }
                announce(player, "&6✦ &e" + player.getName() + " &6got the " + trophyName + "!");
            }
            default -> {
                giveOrDrop(player, item); // a counted boost, as before
                remoteHandedOver(player, p);
            }
        }
        return Outcome.won(shown.clone(), said);
    }

    /**
     * What {@link #deliverAsOne} reads again inside its transaction: the limit (a second confirm ends here), the
     * price (the Arcade Trophy's rises with each one), and a party already on. Null = go on.
     */
    private String recheck(UUID id, Prize p, int cost, boolean charge) {
        if (!charge) {
            return null; // a crate's prize ignores limits and prices
        }
        if (left(id, p) <= 0) {
            return usedUp(p);
        }
        if (price(id, p) != cost) {
            return PRICE_CHANGED;
        }
        return p.type() == PrizeType.PARTY && plugin.party().running() ? PARTY_ON : null;
    }

    /** "Heart Trail (30 days)", as a trail's buy has always said it. */
    private static String trailLabel(Prize p, int days) {
        return p.display() + " &7(" + days + (days == 1 ? " day)" : " days)");
    }

    /**
     * A trophy into the bag slot found free before its transaction (the same tick, so it still is); if anything
     * took the slot meanwhile, into the bag, and what doesn't fit to the Mailbox. Never the ground.
     */
    private void putInSlot(Player player, int slot, ItemStack item, String name) {
        PlayerInventory inv = player.getInventory();
        ItemStack there = inv.getItem(slot);
        if (there == null || there.getType().isAir()) {
            inv.setItem(slot, item.clone());
            return;
        }
        for (ItemStack rest : inv.addItem(item.clone()).values()) {
            try {
                plugin.deliveries().queueReady(player.getUniqueId(), rest, name, MAILBOX_SOURCE);
                player.sendMessage(Text.of("&e✉ Your bag was full - your " + name + " is in your Mailbox."));
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not mail " + player.getName() + "'s " + name + " (their bag filled up"
                        + " during the buy), so it was dropped at their feet: " + e.getMessage());
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    /** A read inside {@link #commitLimited}'s transaction: why the buy can't go on now, or null. */
    @FunctionalInterface
    interface Recheck {
        String why() throws SQLException;
    }

    /** A write inside {@link #commitLimited}'s transaction. */
    @FunctionalInterface
    interface Write {
        void run() throws SQLException;
    }

    /**
     * A counted buy's steps, run in this order in one transaction: read the limit and the price again (null = go
     * on), count it, take the trophy's number, charge (true when paid, or free), and queue the Mailbox row.
     */
    record LimitSteps(Recheck recheck, Write record, Write serial, BooleanSupplier charge, Write mailbox) {
    }

    /** Thrown inside the transaction when the charge is refused: everything before it rolls back. */
    private static final class NotPaid extends RuntimeException {
        NotPaid() {
            super(null, null, false, false);
        }
    }

    /**
     * {@link LimitSteps} as ONE transaction (D30): the limit read again inside the lock, so a second confirm ends
     * there, then the count, the trophy's number, the charge and a full bag's Mailbox row, together or not at
     * all. Static, with the steps passed in, so the double-confirm and failed-write paths are pinned by a test
     * on a real SQLite.
     *
     * @param notSaved gets the error when a write failed (nothing was charged)
     * @return null when it committed, else what to tell the buyer (nothing charged)
     */
    static String commitLimited(Database db, LimitSteps steps, String cantPay, Consumer<Exception> notSaved) {
        try {
            return db.transaction(c -> {
                String why = steps.recheck().why();
                if (why != null) {
                    return why;
                }
                steps.record().run();
                steps.serial().run();
                if (!steps.charge().getAsBoolean()) {
                    throw new NotPaid();
                }
                steps.mailbox().run();
                return null;
            });
        } catch (NotPaid e) {
            return cantPay;
        } catch (SQLException | RuntimeException e) {
            if (notSaved != null) {
                notSaved.accept(e);
            }
            return NOT_SAVED;
        }
    }

    /**
     * A golf ball bought for yourself (P4, §5.10): no item, only the right. The claim (its {@code 'life'} row,
     * which is what "owned" and the ball choice read) and the charge in one transaction, so a double confirm on
     * the 250 or 600 ball is charged once ({@code You already have that.}).
     */
    private Outcome buyBall(Player player, Prize p, int cost) {
        UUID id = player.getUniqueId();
        String refused = claimAndCharge(plugin.database(), () -> dao.claimOnce(id, p.id()),
                () -> plugin.tokens().spend(id, cost, TokenService.Source.PRIZE, p.display()),
                "You already have that.", "You need " + cost + " tokens.",
                e -> plugin.getLogger().warning("Could not save " + player.getName() + "'s golf ball '" + p.id()
                        + "' (nothing charged): " + e.getMessage()));
        if (refused != null) {
            return Outcome.fail(refused);
        }
        player.sendMessage(Text.of("&7Pick it on &e\"Pick your ball\" &7when you play golf."));
        return Outcome.won(icon(p), p.display() + " &7(yours for good)");
    }

    /**
     * A claim and a charge in one transaction: false from the claim (already theirs) or a refused charge leave
     * nothing written. Static, so the double confirm is pinned by a test.
     */
    static String claimAndCharge(Database db, Claim claim, BooleanSupplier charge, String alreadyHas, String cantPay,
                                 Consumer<SQLException> notSaved) {
        try {
            return db.transaction(c -> {
                if (!claim.once()) {
                    return alreadyHas;
                }
                if (!charge.getAsBoolean()) {
                    throw new NotPaid();
                }
                return null;
            });
        } catch (NotPaid e) {
            return cantPay;
        } catch (SQLException e) {
            if (notSaved != null) {
                notSaved.accept(e);
            }
            return NOT_SAVED;
        }
    }

    /**
     * A command row's {@code only_if} (§5.11): why it would do nothing now, or null. NIGHT refuses in daylight
     * ({@code It's already day!}), RAIN when it isn't raining. Read before any charge, and again after the
     * commands ran, when null means they worked.
     */
    static String onlyIfRefusal(OnlyIf when, boolean day, boolean storm) {
        return switch (when == null ? OnlyIf.ANY : when) {
            case NIGHT -> day ? "It's already day!" : null;
            case RAIN -> storm ? null : "It isn't raining!";
            case ANY -> null;
        };
    }

    private static String onlyIfRefusal(OnlyIf when, World world) {
        if (when == null || when == OnlyIf.ANY || world == null) {
            return null;
        }
        if (when == OnlyIf.NIGHT && world.isFixedTime()) {
            return NO_CLOCK; // the Nether and the End: no night to end, so nothing to sell
        }
        return onlyIfRefusal(when, dayOnClock(world.getTime()), world.hasStorm());
    }

    /**
     * The overworld clock's first and last dark tick in clear weather (26.3's {@code timeline/day.json}: the sky
     * light under 12 from 12523 to 23477). {@code time set day} sets 1000.
     */
    static final long DUSK = 12_523;
    static final long DAWN = 23_477;

    /**
     * Whether a world's clock ({@code World#getTime}, 0-23999) says day (red-team finding ECON-1). Judged on the
     * clock, never on {@code World#isDayTime}: that reads a sky value the server works out again only on its next
     * tick, so right after {@code time set day} it still said night and every Make it Day was refunded. The clock
     * moves the moment the command runs, and the check before the charge reads the same thing, so a thunderstorm at
     * noon is day too.
     */
    static boolean dayOnClock(long time) {
        long t = Math.floorMod(time, 24_000L);
        return t < DUSK || t > DAWN;
    }

    /**
     * After a command row's commands ran: true when its {@code only_if} still doesn't hold (still night after
     * Make it Day, still raining after Sunny Day), so it did nothing and is refunded. Never for a row without one.
     */
    static boolean didNothing(OnlyIf when, boolean day, boolean storm) {
        return when != null && when != OnlyIf.ANY && onlyIfRefusal(when, day, storm) == null;
    }

    private static boolean didNothing(OnlyIf when, World world) {
        return world != null && didNothing(when, dayOnClock(world.getTime()), world.hasStorm());
    }

    /**
     * Undo a committed buy whose hand-over failed: the refund (one ledger line) and the count taken back, in a
     * second transaction, as the refund always was.
     */
    private void undo(UUID player, Prize p, int cost, boolean counted, String key) {
        try {
            undo(plugin.database(), () -> {
                if (counted) {
                    dao.removePurchase(player, p.id(), key);
                }
            }, () -> {
                if (cost > 0 && plugin.tokens().grant(player, cost, TokenService.Source.REFUND, p.display()) < 0) {
                    throw new SQLException("the refund could not be written");
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not refund " + cost + " tokens for '" + p.id() + "' to " + player
                    + " after its hand-over failed: " + e.getMessage());
        }
    }

    /** {@link #undo(UUID, Prize, int, boolean, String)}'s two writes, in one transaction: both or neither. */
    static void undo(Database db, Write uncount, Write refund) throws SQLException {
        db.transaction(c -> {
            uncount.run();
            refund.run();
            return null;
        });
    }

    /** The {@code arcade_state} key of a trophy row's serial: the Arcade Trophy's as it always was, else per row. */
    static String trophySerialKey(String prizeId) {
        return ARCADE_TROPHY.equalsIgnoreCase(prizeId) ? TROPHY_SERIAL : TROPHY_SERIAL + ":" + prizeId;
    }

    /** Write a trophy's number inside the buy's transaction; it must still be the next one. */
    private void takeSerial(String key, long number) throws SQLException {
        long had = dao.state(key, 0);
        if (had + 1 != number) {
            throw new SQLException("trophy number " + number + " was taken meanwhile (" + key + " is " + had + ")");
        }
        dao.setState(key, number);
    }

    /** Whether a player's announcement may go out now: none yet, or the last a minute or more ago (A7). */
    static boolean announceDue(Long last, long now) {
        return last == null || now - last >= ANNOUNCE_GAP_MS;
    }

    /**
     * Tell the server something a player did (a trophy, a party): at most one of theirs a minute, so a row of
     * buys is never a row of broadcasts (D24, A7's "never spam"). The buyer's own message is the caller's.
     */
    public void announce(Player player, String line) {
        long now = System.currentTimeMillis();
        if (announceDue(lastAnnounce.get(player.getUniqueId()), now)) {
            lastAnnounce.put(player.getUniqueId(), now);
            Bukkit.broadcast(Text.of(line));
        }
    }

    // ---- collectors ----------------------------------------------------------------------

    /**
     * A collector bought for yourself (its own tile): the same claim → charge → hand-over order as a
     * present, so a failed write can't sell a second one. A cap goes on the head only when the head is
     * empty, otherwise into the bag: a buy never takes off someone's real helmet. A trail is switched on.
     */
    private Outcome buyCollector(Player player, Prize p, int cost) {
        UUID id = player.getUniqueId();
        ItemStack cap = null;
        long serial = 0;
        if (p.type() == PrizeType.HAT) {
            serial = nextSerial(p);
            cap = PrizeItems.collectorCap(plugin, p, null, serial);
            if (cap == null) {
                return Outcome.fail(NOTHING_MADE);
            }
            if (!capRoom(player)) {
                return Outcome.fail("Make room in your bag first — nothing charged.");
            }
        } else if (p.type() != PrizeType.TRAIL) {
            return Outcome.fail("That prize can't be given like this.");
        }
        ItemStack item = cap;
        String refused = claimChargeHandOver(new Steps(
                        () -> dao.claimOnce(id, p.id()),
                        () -> plugin.tokens().spend(id, cost, TokenService.Source.PRIZE, p.display()),
                        () -> item != null ? wearOrGive(player, item, BUY) != null : plugin.trails().grant(player, p, p.days()),
                        () -> refund(id, cost, p),
                        () -> unclaim(id, p)),
                "You already have that.", "You need " + cost + " tokens.",
                cap != null ? "Make room in your bag first — nothing charged." : NO_TRAIL,
                e -> plugin.getLogger().warning("Could not claim collector '" + p.id() + "' for " + player.getName()
                        + ": " + e.getMessage()));
        if (refused != null) {
            return Outcome.fail(refused);
        }
        saveSerial(p, serial);
        if (cap == null) {
            plugin.trails().select(player, p.id());
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        return cap != null ? Outcome.won(cap.clone(), p.display())
                : Outcome.won(icon(p), p.display() + " &7(yours for good)");
    }

    /**
     * Everything a present is checked against, in {@link #giftRefusal}'s order. The receiver is read
     * at confirm time (by UUID), never from a picker's snapshot.
     *
     * @param giverMaySpend     the economy runs in the giver's world
     * @param keeper            the row is a Keeper: never a present
     * @param giftingOn         {@code arcade.collectors.gifting} (every present)
     * @param giftable          the row can be a present: a collector marked giftable, a rented look, a golf ball
     * @param onSale            the row is visible: collectors on sale, and in season
     * @param receiverOnline    the receiver is online
     * @param giverCanSee       the giver can see them (a vanished admin is "not online")
     * @param self              the receiver is the giver
     * @param receiverEconomyWorld the economy runs in the receiver's world
     * @param receiverGamesWorld   the receiver is in a Games world
     * @param receiverInGame    the receiver is in a game, or not yet home from one (or on the death
     *                          screen: a present is never handed to someone respawning)
     * @param receiverHasIt     the receiver already has it: a collector or a ball owned (the lifetime limit used), a
     *                          rented look's window LIVE or KEPT
     * @param until             a rented look the receiver has: its last day ("18 Jan"), else null
     * @param cap               the present is a collector cap (a trail, a ball and a rented hat need no room)
     * @param headEmpty         the receiver's helmet slot is empty
     * @param bagHasRoom        the receiver has a free bag slot
     */
    public record GiftFacts(boolean giverMaySpend, boolean keeper, boolean giftingOn, boolean giftable,
                            boolean onSale, boolean receiverOnline, boolean giverCanSee, boolean self,
                            boolean receiverEconomyWorld, boolean receiverGamesWorld, boolean receiverInGame,
                            boolean receiverHasIt, String until, boolean cap, boolean headEmpty, boolean bagHasRoom,
                            int balance, int cost, String receiverName, String item) {
    }

    /**
     * Why this present can't be given, or null if it can: the first failure in D8's order, before any
     * token moves. Pure, so the order is pinned by a test.
     */
    public static String giftRefusal(GiftFacts f) {
        String name = f.receiverName() == null || f.receiverName().isBlank() ? "That player" : f.receiverName();
        if (!f.giverMaySpend()) {
            return EconomySandbox.reason();
        }
        if (f.keeper()) {
            return "Keepers can't be given.";
        }
        if (!f.giftingOn() || !f.giftable() || !f.onSale()) {
            return "That isn't for sale right now.";
        }
        if (!f.receiverOnline() || !f.giverCanSee()) {
            return name + " isn't online.";
        }
        if (f.self()) {
            return "That's you! Tap the " + f.item() + " itself to buy one for yourself.";
        }
        if (!f.receiverEconomyWorld() || f.receiverGamesWorld() || f.receiverInGame()) {
            // A world game restores the whole inventory, armour too: a present given in one is lost.
            return name + " can't get presents right now (they're in a game or another world).";
        }
        if (f.receiverHasIt()) {
            return f.until() != null ? name + " has the " + f.item() + " until " + f.until() + "."
                    : name + " already has the " + f.item() + "!";
        }
        if (f.cap() && !f.headEmpty() && !f.bagHasRoom()) {
            return name + "'s bag is full.";
        }
        if (f.balance() < f.cost()) {
            return "You need " + (f.cost() - f.balance()) + " more tokens.";
        }
        return null;
    }

    /**
     * The facts for one present, read now. Public so a picker can say why someone can't get one
     * ("has it", "bag full", "not here") by the same rules {@link #gift} enforces.
     */
    public GiftFacts giftFacts(Player giver, UUID receiverId, Prize p, boolean giverMaySpend) {
        Player r = receiverId == null ? null : Bukkit.getPlayer(receiverId);
        boolean online = r != null && r.isOnline();
        String name = online ? r.getName() : offlineName(receiverId);
        PlayerInventory inv = online ? r.getInventory() : null;
        ItemStack helmet = inv == null ? null : inv.getHelmet();
        boolean rental = p.rental() && plugin.looks() != null;
        boolean hasIt;
        String until = null;
        int cost;
        if (rental && receiverId != null) {
            // A rented look is the receiver's run: has it while their window is LIVE or KEPT, and the price is
            // their buy-again price while it is open (§3.13).
            var row = plugin.looks().look(receiverId, p.id());
            var w = com.dierks.homecraft.arcade.looks.LookRules.window(row, plugin.clock().nowMillis());
            hasIt = w == com.dierks.homecraft.arcade.looks.LookRules.Window.LIVE
                    || w == com.dierks.homecraft.arcade.looks.LookRules.Window.KEPT;
            if (w == com.dierks.homecraft.arcade.looks.LookRules.Window.LIVE) {
                until = com.dierks.homecraft.arcade.looks.LookRules.until(
                        com.dierks.homecraft.arcade.looks.LookRules.lastDay(row.expiresAt(), plugin.clock().zone()));
            }
            cost = com.dierks.homecraft.arcade.looks.LookRules.price(p, w);
        } else {
            hasIt = online && (owned(r, p) || left(r.getUniqueId(), p) <= 0);
            cost = price(giver.getUniqueId(), p);
        }
        return new GiftFacts(giverMaySpend, p.keeper(), plugin.config().arcade().collectorsGifting(), giftable(p),
                visible(p), online, online && giver.canSee(r),
                receiverId != null && receiverId.equals(giver.getUniqueId()),
                online && plugin.sandbox().allowed(r.getWorld()), online && gamesWorld(r.getWorld()),
                online && (!homeFromGames(r) || r.isDead()), online && hasIt, online ? until : null,
                p.collector() != null && p.type() == PrizeType.HAT, helmet == null || helmet.getType().isAir(),
                inv != null && inv.firstEmpty() != -1,
                plugin.tokens().balance(giver.getUniqueId()), cost, name, Text.plain(p.display()));
    }

    /**
     * Whether a row can be a present at all (§3.13): a collector marked giftable, a rented look (a hat, a cap or a
     * trail: bound to its owner, so this is the only way to give one), or a golf ball. Never a Keeper, a perk, a
     * trophy or a boost.
     */
    public static boolean giftable(Prize p) {
        if (p == null || p.keeper()) {
            return false;
        }
        if (p.collector() != null) {
            return p.collector().giftable();
        }
        return p.rental() || p.type() == PrizeType.BALL;
    }

    /**
     * Give a collector to someone online (D8): every check first ({@link #giftRefusal}), then build,
     * claim it for the receiver, charge the giver, and hand it over — each step undone if a later one
     * fails, and never onto the ground. On success the giver's menu is closed (a buy reopens the
     * counter; a present must not hide the moment) and both players get their feedback here, so the
     * caller only shows a failure's message and plays {@link Sounds#refused}, as for {@link #buy}.
     */
    public Outcome gift(Player giver, UUID receiverId, Prize p) {
        return gift(giver, receiverId, p, -1);
    }

    /**
     * {@link #gift(Player, UUID, Prize)} at the price the confirm showed ({@code shownCost}, -1 = none). A rented look
     * goes to the wardrobe ({@code LookService.purchase}, the receiver's run, the giver's tokens, one transaction);
     * a golf ball is claimed for the receiver and charged to the giver in one transaction ({@link #giftBall}); a
     * collector as before. Never a Keeper.
     */
    public Outcome gift(Player giver, UUID receiverId, Prize p, int shownCost) {
        if (p.keeper()) {
            return Outcome.fail("Keepers can't be given.");
        }
        if (p.rental() && plugin.looks() != null) {
            return plugin.looks().purchase(giver, receiverId, p, shownCost);
        }
        if (!plugin.sandbox().check(giver, "prize gift " + p.id())) {
            return Outcome.fail(EconomySandbox.reason());
        }
        if (p.type() == PrizeType.BALL) {
            return giftBall(giver, receiverId, p);
        }
        GiftFacts f = giftFacts(giver, receiverId, p, true);
        String refused = giftRefusal(f);
        if (refused != null) {
            return Outcome.fail(refused);
        }
        Player r = Bukkit.getPlayer(receiverId);
        if (r == null) {
            return Outcome.fail(f.receiverName() + " isn't online.");
        }
        UUID from = giver.getUniqueId();
        UUID to = r.getUniqueId();
        String item = f.item();
        int cost = f.cost();
        ItemStack cap = null;
        long serial = 0;
        if (p.type() == PrizeType.HAT) {
            serial = nextSerial(p);
            cap = PrizeItems.collectorCap(plugin, p, giver.getName(), serial);
            if (cap == null) {
                return Outcome.fail(NOTHING_MADE);
            }
        }
        ItemStack made = cap;
        CapPlacement[] placed = new CapPlacement[1];
        String refusedLate = claimChargeHandOver(new Steps(
                        () -> dao.claimOnce(to, p.id()),
                        () -> plugin.tokens().spend(from, cost, TokenService.Source.PRIZE, item + " for " + r.getName()),
                        () -> made != null ? (placed[0] = wearOrGive(r, made, GIFT)) != null
                                : plugin.trails().grant(r, p, p.days()),
                        () -> refund(from, cost, p),
                        () -> unclaim(to, p)),
                r.getName() + " already has the " + item + "!", "You need " + cost + " tokens.",
                cap != null ? r.getName() + "'s bag is full — nothing charged." : NO_TRAIL,
                e -> plugin.getLogger().warning("Could not claim collector '" + p.id() + "' for " + r.getName()
                        + ": " + e.getMessage()));
        if (refusedLate != null) {
            return Outcome.fail(refusedLate);
        }
        saveSerial(p, serial);
        closeOurMenu(r);
        if (cap == null) {
            plugin.trails().select(r, p.id());
        }
        plugin.getLogger().info(giver.getName() + " gave " + p.id() + " to " + r.getName() + " (" + cost + " tokens)");
        giver.closeInventory();
        giver.sendMessage(Text.of("&a✔ You gave the " + item + " to " + r.getName() + "!"));
        Sounds.paid(giver);
        presentMoment(r, giver.getName(), p, placed[0]);
        return Outcome.won(cap != null ? cap.clone() : icon(p), p.display());
    }

    /**
     * A golf ball as a present (§3.13, §5.10): every check ({@link #giftRefusal}), then the receiver's claim (its
     * {@code 'life'} row, what "owned" and the ball choice read) and the giver's charge in ONE transaction, so a
     * double confirm on the 250 or 600 ball is charged once. No item: nothing can fail to fit.
     */
    private Outcome giftBall(Player giver, UUID receiverId, Prize p) {
        GiftFacts f = giftFacts(giver, receiverId, p, true);
        String refused = giftRefusal(f);
        Player r = Bukkit.getPlayer(receiverId);
        if (refused == null && r == null) {
            refused = f.receiverName() + " isn't online.";
        }
        if (refused != null) {
            return Outcome.fail(refused);
        }
        UUID from = giver.getUniqueId();
        int cost = f.cost();
        String item = f.item();
        String late = claimAndCharge(plugin.database(), () -> dao.claimOnce(r.getUniqueId(), p.id()),
                () -> plugin.tokens().spend(from, cost, TokenService.Source.PRIZE, item + " for " + r.getName()),
                r.getName() + " already has the " + item + "!", "You need " + cost + " tokens.",
                e -> plugin.getLogger().warning("Could not save " + giver.getName() + "'s golf ball '" + p.id()
                        + "' for " + r.getName() + " (nothing charged): " + e.getMessage()));
        if (late != null) {
            return Outcome.fail(late);
        }
        plugin.getLogger().info(giver.getName() + " gave " + p.id() + " to " + r.getName() + " (" + cost + " tokens)");
        giver.closeInventory();
        giver.sendMessage(Text.of("&a✔ You gave the " + item + " to " + r.getName() + "!"));
        Sounds.paid(giver);
        presentMoment(r, giver.getName(), p, "Pick it on \"Pick your ball\" when you play golf.", false);
        return Outcome.won(icon(p), p.display());
    }

    /** A claim that may fail to be written. */
    @FunctionalInterface
    interface Claim {
        boolean once() throws SQLException;
    }

    /**
     * A collector hand-over's steps: claim the receiver's row (true when this call wrote it), charge
     * the payer (true when paid; true for free), hand it over (true when it arrived), and the two undos.
     */
    record Steps(Claim claim, java.util.function.BooleanSupplier charge, java.util.function.BooleanSupplier handOver,
                 Runnable refund, Runnable unclaim) {
    }

    /**
     * A collector's claim → charge → hand-over (D8), each undone if a later one fails: a failed charge
     * unclaims; a failed hand-over refunds, then unclaims. The claim comes first and is the one lifetime
     * gate, so a second confirm (ConfirmMenu has no double-click guard) finds the row and stops before
     * any charge, and a failed write can't sell a second one. Static, with the steps passed in, so the
     * double-click and failed-charge paths are pinned by a test.
     *
     * @param notSaved gets the error when the claim couldn't be written
     * @return null when it was handed over, else what to tell the payer (nothing charged)
     */
    static String claimChargeHandOver(Steps steps, String alreadyHas, String cantPay, String cantHandOver,
                                      java.util.function.Consumer<SQLException> notSaved) {
        try {
            if (!steps.claim().once()) {
                return alreadyHas;
            }
        } catch (SQLException e) {
            if (notSaved != null) {
                notSaved.accept(e);
            }
            return NOT_SAVED;
        }
        if (!steps.charge().getAsBoolean()) {
            steps.unclaim().run();
            return cantPay;
        }
        if (!steps.handOver().getAsBoolean()) {
            steps.refund().run();
            steps.unclaim().run();
            return cantHandOver;
        }
        return null;
    }

    /**
     * Give a collector back free ({@code /hcm give prize}): out of season, whatever {@code owned}
     * says, because restoring is the point. It claims the row and goes ahead whether or not one was
     * already there, so a restore never double-counts. The target must be home from every game (a
     * game's inventory restore would wipe it), and a cap needs room by the placement rule: nothing
     * goes on the ground. With {@code fromName} set to someone other than the target, it is a present:
     * "A gift from" in the lore, the swap, and the whole moment. Logging who gave it is the caller's.
     * A collector trail this server can't show is refused ({@link #restoreRefusal}). A Keeper is given back the same
     * way, free, by the wardrobe ({@code LookService.restoreKeeper}: its claim, its rows, its key or its trail).
     */
    public Outcome restore(Player target, Prize p, String fromName) {
        String refused = restoreRefusal(p, notHere());
        if (refused != null) {
            return Outcome.fail(refused);
        }
        if (p.keeper()) {
            // a Keeper given back free (/hcm give prize keeper_*): its claim, its rights and its key (§5.4)
            return plugin.looks() != null ? plugin.looks().restoreKeeper(target, p, fromName)
                    : Outcome.fail("That prize can't be given like this.");
        }
        if (!homeFromGames(target)) {
            return Outcome.fail(target.getName() + " is in a game. Try again when they're back.");
        }
        if (target.isDead()) {
            return Outcome.fail(target.getName() + " is respawning. Try again in a moment.");
        }
        boolean present = fromName != null && !fromName.isBlank() && !fromName.equalsIgnoreCase(target.getName());
        ItemStack cap = null;
        long serial = 0;
        if (p.type() == PrizeType.HAT) {
            serial = nextSerial(p);
            cap = PrizeItems.collectorCap(plugin, p, present ? fromName : null, serial);
            if (cap == null) {
                return Outcome.fail(NOTHING_MADE);
            }
            if (!capRoom(target)) {
                return Outcome.fail(target.getName() + "'s bag is full.");
            }
        } else if (p.type() != PrizeType.TRAIL) {
            return Outcome.fail("That prize can't be given like this.");
        }
        UUID to = target.getUniqueId();
        boolean claimed = false;
        try {
            claimed = dao.claimOnce(to, p.id());
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not claim collector '" + p.id() + "' for " + target.getName()
                    + " (giving it back anyway): " + e.getMessage());
        }
        CapPlacement placed = null;
        if (cap != null) {
            placed = wearOrGive(target, cap, present);
            if (placed == null) {
                if (claimed) {
                    unclaim(to, p);
                }
                return Outcome.fail(target.getName() + "'s bag is full.");
            }
            saveSerial(p, serial);
        } else {
            if (!plugin.trails().grant(target, p, p.days())) {
                if (claimed) {
                    unclaim(to, p);
                }
                return Outcome.fail(NO_TRAIL);
            }
            if (present) {
                closeOurMenu(target);
            }
            plugin.trails().select(target, p.id());
        }
        if (present) {
            presentMoment(target, fromName, p, placed);
        } else {
            target.sendMessage(Text.of("&a✔ " + p.display() + " &ais yours" + (claimed ? "! &7" : " again! &7")
                    + where(placed)));
            Sounds.received(target);
        }
        return cap != null ? Outcome.won(cap.clone(), p.display())
                : Outcome.won(icon(p), p.display() + " &7(yours for good)");
    }

    /**
     * Where a collector cap goes (kid-bedrock #6): on an empty head; for a present, onto the head with
     * the old helmet swapped into the bag, so she sees the present rather than a cap lost in her bag;
     * into the bag when the old helmet has Curse of Binding, or when it's a buy and the head isn't empty.
     */
    public static CapPlacement capPlacement(boolean headEmpty, boolean headBinding, boolean gift) {
        if (headEmpty) {
            return CapPlacement.HEAD;
        }
        return gift && !headBinding ? CapPlacement.SWAP : CapPlacement.BAG;
    }

    /**
     * Put a collector cap where {@link #capPlacement} says. A swap or a bag needs one free bag slot,
     * and the item goes into that exact slot, so nothing is ever split, dropped or lost.
     *
     * @return where it went, or null (nothing moved) when there was no free slot — the caller refunds
     */
    public static CapPlacement wearOrGive(Player player, ItemStack cap, boolean gift) {
        PlayerInventory inv = player.getInventory();
        ItemStack old = inv.getHelmet();
        boolean empty = old == null || old.getType().isAir();
        CapPlacement place = capPlacement(empty, !empty && old.containsEnchantment(Enchantment.BINDING_CURSE), gift);
        ItemStack one = cap.clone();
        one.setAmount(1);
        if (place == CapPlacement.HEAD) {
            inv.setHelmet(one);
            return place;
        }
        int free = inv.firstEmpty();
        if (free < 0) {
            return null;
        }
        if (place == CapPlacement.SWAP) {
            inv.setItem(free, old.clone());
            inv.setHelmet(one);
        } else {
            inv.setItem(free, one);
        }
        return place;
    }

    /**
     * The receiver's moment, where a first-person camera can see it: the title, a fanfare, a harmless
     * firework bursting right in front of them, hearts for a cap (a trail shows itself), and a line
     * saying where it went. One of our menus left open on their screen is closed first: on a Bedrock
     * window a chest screen covers the whole view, and the moment would play behind it. A game screen
     * stays open, so the game goes on ({@link #hidesTheMoment}).
     */
    private void presentMoment(Player r, String fromName, Prize p, CapPlacement placed) {
        presentMoment(r, fromName, p, where(placed), placed != null);
    }

    /**
     * {@link #presentMoment(Player, String, Prize, CapPlacement)} for any present: {@code where} is the chat line's
     * end ("It's on your head!", "Your trail is on!"), {@code hearts} the hearts for something worn. The wardrobe's
     * presents (a rented look, a free test look) use it too.
     */
    public void presentMoment(Player r, String fromName, Prize p, String where, boolean hearts) {
        closeOurMenu(r);
        String item = Text.plain(p.display());
        try {
            r.showTitle(Title.title(Text.of("&6A present!"), Text.of("&e" + fromName + " gave you the " + item),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))));
            r.playSound(r.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            Sounds.won(r);
            Fireworks.burstInView(r);
            if (plugin.looks() != null) {
                plugin.looks().titleShown(r, 3_250); // a heads-up waits behind it (red team O20)
            }
            if (hearts) {
                Location eye = r.getEyeLocation();
                r.spawnParticle(Particle.HEART, eye.clone().add(eye.getDirection().multiply(1.5)), 10, 0.4, 0.3, 0.4, 0);
            }
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        r.sendMessage(Text.of("&6✦ &e" + fromName + " gave you the " + item + "! &a" + where));
    }

    /** {@link #closeOurMenu} for the wardrobe: a trail present's burst plays where it can be seen. */
    public static void closeOurMenuFor(Player r) {
        closeOurMenu(r);
    }

    /**
     * Close the receiver's screen before their present's moment (review BK-1) when it is one of our
     * menus other than a Games screen or a trade's, which holds nothing of theirs (the Prize Counter, the
     * Arcade, a crate). Every Games screen is left open, the Games menu, high scores, Take a break and the
     * lobbies as well as the play screens whose close would end a live match or a scored try (final audit
     * F2), and so are the trade window and the "Trade?" screen.
     * Any other screen (a chest, another plugin's) is left open too, and the moment plays over it. A
     * trail present calls this before its select, whose "trail is on" burst is part of the moment.
     */
    private static void closeOurMenu(Player r) {
        try {
            if (hidesTheMoment(r.getOpenInventory().getTopInventory().getHolder(false))) {
                r.closeInventory();
            }
        } catch (RuntimeException ignored) {
            // cosmetic: the moment still plays
        }
    }

    /**
     * True for a screen of ours ({@link com.dierks.homecraft.gui.Menu}), which a present may close, but
     * never a Games screen ({@link com.dierks.homecraft.gui.games.GameMenu}, the base of all of them, so
     * the Games menu, high scores, Take a break and the lobbies stay open too): closing a play screen ends
     * a friend match for both players, or a Daily board's scored try with no score (final audit F2). Nor a
     * trade window or the "Trade?" screen (red-team finding BR-3): closing the window cancels the trade and
     * blames the receiver, and the "Trade?" screen is shown only once.
     */
    static boolean hidesTheMoment(org.bukkit.inventory.InventoryHolder holder) {
        return holder instanceof com.dierks.homecraft.gui.Menu
                && !(holder instanceof com.dierks.homecraft.gui.games.GameMenu)
                && !(holder instanceof com.dierks.homecraft.trade.TradeService.Window)
                && !(holder instanceof com.dierks.homecraft.trade.TradeService.AskScreen);
    }

    /** "It's on your head!" and the rest, for the chat line after a collector is handed over. */
    private static String where(CapPlacement placed) {
        if (placed == null) {
            return "Your trail is on!";
        }
        return switch (placed) {
            case HEAD -> "It's on your head!";
            case SWAP -> "It's on your head! Your old helmet is in your bag.";
            case BAG -> "It's in your bag.";
        };
    }

    /** Room for a collector cap: an empty head, or a free bag slot (for the cap, or the old helmet). */
    private static boolean capRoom(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        return helmet == null || helmet.getType().isAir() || player.getInventory().firstEmpty() != -1;
    }

    /** Home from every game: no session, nothing on the way, no saved-state row sending them back. */
    private boolean homeFromGames(Player player) {
        return Whereabouts.homeFromGames(plugin, player);
    }

    /** A Games world (case-insensitive): checked on its own, because an empty economy list means everywhere. */
    private boolean gamesWorld(World world) {
        return Whereabouts.gamesWorld(plugin, world);
    }

    private void unclaim(UUID player, Prize p) {
        try {
            dao.unclaim(player, p.id());
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not undo the claim on collector '" + p.id() + "' for " + player
                    + " — nothing was charged, but it now reads as theirs; /hcm give prize " + p.id()
                    + " gives it to them: " + e.getMessage());
        }
    }

    /** The {@code arcade_state} key of a collector cap's serial number ("No. n", D7). */
    static String serialKey(String prizeId) {
        return "collector:" + prizeId;
    }

    /**
     * The next serial number for a collector cap, read before the build so the cap carries it (D7, the
     * trophy's pattern), or 0 for none: a trail has no item to carry one, and a number that can't be
     * read is left off rather than refusing the cap. {@link #saveSerial} writes it after the hand-over,
     * so a refusal uses up no number.
     */
    private long nextSerial(Prize p) {
        if (p.type() != PrizeType.HAT) {
            return 0;
        }
        try {
            return dao.state(serialKey(p.id()), 0) + 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read the serial of collector '" + p.id()
                    + "' (the cap is made without one): " + e.getMessage());
            return 0;
        }
    }

    private void saveSerial(Prize p, long serial) {
        if (serial <= 0) {
            return;
        }
        try {
            dao.setState(serialKey(p.id()), serial);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not save the serial of collector '" + p.id() + "': " + e.getMessage());
        }
    }

    private void refund(UUID player, int cost, Prize p) {
        plugin.tokens().grant(player, cost, TokenService.Source.REFUND, p.display());
    }

    private static String offlineName(UUID id) {
        if (id == null) {
            return null;
        }
        try {
            return Bukkit.getOfflinePlayer(id).getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Run a COMMAND row's console commands; false if any threw or was not handled. */
    private boolean runCommands(Player player, Prize p) {
        for (String raw : p.commands()) {
            String cmd = raw.replace("%player%", player.getName()).replace("%uuid%", player.getUniqueId().toString());
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                if (!ok) {
                    // Unknown or unhandled: the perk was not given, so the purchase must not stand.
                    plugin.getLogger().warning("Prize '" + p.id() + "' for " + player.getName() + ": '" + cmd
                            + "' was not handled by the server — refunding. Check the command in arcade.prizes.");
                    return false;
                }
                plugin.getLogger().info("Prize '" + p.id() + "' for " + player.getName() + ": ran '" + cmd + "'");
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Prize '" + p.id() + "' command '" + cmd + "' failed: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /** A display icon for a row: its material, named. For reveals and menus. */
    public static ItemStack icon(Prize p) {
        return com.dierks.homecraft.gui.Menus.icon(p.icon().material() != null ? p.icon().material() : Material.PAPER,
                p.display());
    }

    /**
     * A Firework Remote just handed over shows its holder's set at once (review REMOTE-LORE-4): an item put in the bag
     * fires no held-slot event, so one landing in the selected slot kept its counter look, without its "(N linked)"
     * and its launchers' lines, until the player switched away and back.
     */
    private void remoteHandedOver(Player player, Prize p) {
        if (p.type() == PrizeType.REMOTE && plugin.remotes() != null) {
            plugin.remotes().refreshRemotes(player);
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item.clone()).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    private static String pretty(String enumName) {
        String n = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
