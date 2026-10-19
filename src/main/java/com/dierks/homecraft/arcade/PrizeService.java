package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService.Outcome;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.integration.EconomySandbox;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.util.Fireworks;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
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

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Prize Counter (§3.9): fixed-price token purchases, sorted into tabs, with per-day /
 * per-week / lifetime limits and "you already own this".
 *
 * <p>The order of a purchase is the one the counter has always had, and it matters: every check
 * first, then build the item (so a prize that cannot be produced costs nothing), then charge,
 * then record — so the ledger line and the limit tick only for something that exists.
 *
 * <p>Nothing here touches a dollar. Every physical prize carries {@code hcm:token_prize}, which
 * every money surface refuses, and no row type may reach a money flow (no Courier extras, no
 * shipping, no market limits, no printer fees): §11 #9.
 *
 * <p>A collector ({@link Collectors}) goes its own way: sold only in its season, never from a crate,
 * and claimed BEFORE the charge ({@link PrizeDao#claimOnce}), so a second confirm or a failed write
 * can never sell a second one. A collector is never dropped on the ground: with no room it is
 * refused, or refunded and unclaimed.
 */
public final class PrizeService {

    /** The Arcade trophy serial, in {@code arcade_state}. */
    private static final String TROPHY_SERIAL = "trophy_serial";

    /** {@link #wearOrGive}'s mode: a present (an old helmet is swapped into the bag) or a buy (it never is). */
    public static final boolean GIFT = true;
    public static final boolean BUY = false;

    private static final String NOTHING_MADE = "That prize couldn't be made — nothing charged.";
    private static final String NOT_SAVED = "That couldn't be saved — nothing charged. Tell an admin.";
    private static final String NO_TRAIL = "Couldn't add that trail — nothing charged. Tell an admin.";
    private static final String TRAIL_NOT_HERE = "That trail needs Paper 26.3.";

    /** Where a collector cap goes: on the head, on the head with the old helmet into the bag, or into the bag. */
    public enum CapPlacement { HEAD, SWAP, BAG }

    private final HomeCraftManagement plugin;
    private final PrizeDao dao;
    /**
     * The collector trails this server can't show, found once at enable (final audit F7): on 26.2 the Autumn
     * Leaves trail's particle doesn't exist, so it is not sold or given there rather than charged for and never
     * drawn. The collector self-test logs why, once. Owned rows keep loading, so one shows after an upgrade.
     */
    private final Set<String> trailsNotHere;

    public PrizeService(HomeCraftManagement plugin, PrizeDao dao) {
        this.plugin = plugin;
        this.dao = dao;
        this.trailsNotHere = trailsNotHere(Collectors.ROWS, TrailService::particleUsable);
    }

    public PrizeDao dao() {
        return dao;
    }

    // ---- what a player sees ----------------------------------------------------------

    /**
     * Whether a row appears at all: enabled, its plugins present, and — for a hat — a texture to
     * show (a hat with no texture is a Steve head, which is not a prize). A collector appears only
     * while collectors are on sale and today (local) is inside its season, and a collector trail only
     * on a server that can show it ({@link #offered}); it is a dyed helmet, not a head, so the texture
     * rule is not its.
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
        if (p.collector() != null) {
            return offered(p, plugin.config().arcade().collectorsOnSale(), plugin.clock().today(), trailsNotHere);
        }
        if (p.type() == PrizeType.HAT && (p.texture() == null || p.texture().isBlank())) {
            return false;
        }
        return true;
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
     * The ids of the collector trails among {@code rows} whose particle {@code usable} refuses
     * ({@link TrailService#particleUsable}): the Autumn Leaves trail on 26.2, none on 26.3.
     */
    static Set<String> trailsNotHere(List<Prize> rows, java.util.function.Predicate<String> usable) {
        Set<String> out = new HashSet<>();
        for (Prize p : rows) {
            if (p.collector() != null && p.type() == PrizeType.TRAIL && !usable.test(p.particle())) {
                out.add(p.id());
            }
        }
        return Set.copyOf(out);
    }

    /** Why {@link #restore} won't give {@code p} back on this server ({@code notHere}), or null. */
    static String notHereRefusal(Prize p, Set<String> notHere) {
        return p != null && notHere.contains(p.id()) ? TRAIL_NOT_HERE : null;
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

    /** What this player's next one costs: a +1 Home gets dearer with each one bought. */
    public int price(UUID player, Prize p) {
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

    /** "1 left today", "0 left this week", or null when the row has no limit. */
    public String limitText(UUID player, Prize p) {
        if (p.type() == PrizeType.PITY) {
            return left(player, p) + " left this week";
        }
        if (p.limit() == null || p.limit().per() == LimitPer.LIFETIME) {
            return null;
        }
        int left = left(player, p);
        return left + " left " + (p.limit().per() == LimitPer.DAY ? "today" : "this week");
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
            case LIFETIME -> "life";
        };
    }

    private java.time.DayOfWeek weekStart() {
        PluginConfig.Quests q = plugin.config().quests();
        return q == null || q.weekStartsOn() == null ? java.time.DayOfWeek.MONDAY : q.weekStartsOn();
    }

    /** Count one buy against a row's limit (no-op without a limit). */
    public void recordPurchase(UUID player, Prize p) {
        if (p.limit() == null && p.type() != PrizeType.COMMAND && p.requiresPrize() == null) {
            return;
        }
        try {
            dao.addPurchase(player, p.id(), p.limit() != null ? periodKey(p.limit().per()) : "life");
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not record a prize purchase: " + e.getMessage());
        }
    }

    // ---- buying ------------------------------------------------------------------------

    /**
     * Buy a row. {@code color} is the buyer's pick for a filament row that lets them choose.
     * PITY runs the pity exchange; TRADE_IN and QUEST_REROLL are screens, not purchases, and are
     * refused here (their menus call their own paths).
     */
    public Outcome buy(Player player, Prize p, DyeColor color) {
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
            LimitPer per = p.type() == PrizeType.PITY ? LimitPer.WEEK : p.limit().per();
            return Outcome.fail("You've had all of those for " + (per == LimitPer.DAY ? "today"
                    : per == LimitPer.WEEK ? "this week" : "good") + ".");
        }
        int have = plugin.tokens().balance(id);
        int cost = price(id, p);
        if (have < cost) {
            return Outcome.fail("You need " + (cost - have) + " more tokens.");
        }
        if (p.collector() != null) {
            return buyCollector(player, p, cost); // claims its own row: no recordPurchase after it
        }
        Outcome out = switch (p.type()) {
            case PITY -> plugin.arcade().pity(player);
            case TRADE_IN, QUEST_REROLL -> Outcome.fail("Open that from the Prize Counter.");
            default -> deliver(player, p, color, true, 0);
        };
        if (out.ok()) {
            if (p.type() != PrizeType.PITY) { // the pity exchange counts its own week
                recordPurchase(id, p);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
            }
        }
        return out;
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
        recordPurchase(id, p);
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
        return deliver(player, p, null, false, daysOverride);
    }

    /** Whether a row can be handed out by a crate at all right now. */
    public boolean grantable(Prize p) {
        if (p.collector() != null) {
            return false; // checked first: a crate never gives a collector, in season or not
        }
        return visible(p) && crateable(p);
    }

    /**
     * The kinds of row a crate may hand out. Never a collector: a crate's "any trail" pool would pay
     * the kept-for-good trail for a day, and a crated collector would bypass its one-each claim.
     */
    static boolean crateable(Prize p) {
        if (p.collector() != null) {
            return false;
        }
        return switch (p.type()) {
            case BOOST, RADAR, LURE, FIREWORK, TRAIL, HAT, TROPHY, FILAMENT, BLOCK, PACK -> true;
            default -> false; // perks, pity, trade-in and rerolls are bought, never won
        };
    }

    /**
     * Build, (optionally) charge, and hand over. The item is built BEFORE the charge, so a prize
     * that cannot be produced right now costs nothing.
     */
    private Outcome deliver(Player player, Prize p, DyeColor color, boolean charge, int daysOverride) {
        UUID id = player.getUniqueId();
        int cost = price(id, p);
        ItemStack item = null;
        String label = p.display();
        long trophyNumber = 0;
        switch (p.type()) {
            case BOOST, RADAR, LURE, FIREWORK, HAT -> item = PrizeItems.build(plugin, p);
            case TROPHY -> {
                try {
                    trophyNumber = dao.state(TROPHY_SERIAL, 0) + 1;
                } catch (SQLException e) {
                    return Outcome.fail("The trophy shelf is stuck — nothing charged. Tell an admin.");
                }
                item = PrizeItems.trophy(plugin, p, trophyNumber, player.getName());
                label = "&6Arcade Trophy #" + trophyNumber;
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
            case TRAIL, COMMAND -> {
                // nothing to build: applied after the charge
            }
            case HOME_SLOT -> {
                // Everything that could refuse is checked here, before the charge.
                String refused = plugin.homes() == null ? "That isn't for sale right now."
                        : plugin.homes().refuseReason(player);
                if (refused != null) {
                    return Outcome.fail(refused);
                }
            }
            default -> {
                return Outcome.fail("That prize can't be given like this.");
            }
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
                    return Outcome.fail("Couldn't add that trail — nothing charged. Tell an admin.");
                }
                label = p.display() + " &7(" + days + (days == 1 ? " day)" : " days)");
                item = icon(p);
            }
            case COMMAND -> {
                if (!runCommands(player, p)) {
                    if (charge) {
                        plugin.tokens().grant(id, cost, TokenService.Source.REFUND, p.display());
                    }
                    return Outcome.fail("That didn't work — nothing charged. Tell an admin.");
                }
                item = icon(p);
            }
            case HOME_SLOT -> {
                int now = plugin.homes().total(player) + 1;
                plugin.homes().refresh(player, 1); // this slot is recorded right after
                label = "&a+1 Home &7(you have " + now + " now)";
                item = icon(p);
            }
            case TROPHY -> {
                try {
                    dao.setState(TROPHY_SERIAL, trophyNumber);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not save the trophy serial: " + e.getMessage());
                }
                giveOrDrop(player, item);
                Bukkit.broadcast(Text.of("&6✦ &e" + player.getName() + " &6won Arcade Trophy #" + trophyNumber + "!"));
            }
            default -> giveOrDrop(player, item);
        }
        return Outcome.won(item.clone(), label);
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
     * @param giftingOn         {@code arcade.collectors.gifting}
     * @param giftableCollector the row is a collector marked giftable
     * @param onSale            the row is visible: collectors on sale, and in season
     * @param receiverOnline    the receiver is online
     * @param giverCanSee       the giver can see them (a vanished admin is "not online")
     * @param self              the receiver is the giver
     * @param receiverEconomyWorld the economy runs in the receiver's world
     * @param receiverGamesWorld   the receiver is in a Games world
     * @param receiverInGame    the receiver is in a game, or not yet home from one (or on the death
     *                          screen: a present is never handed to someone respawning)
     * @param receiverHasIt     the receiver already has it (owned, or the lifetime limit used)
     * @param cap               the present is a cap (a trail needs no room)
     * @param headEmpty         the receiver's helmet slot is empty
     * @param bagHasRoom        the receiver has a free bag slot
     */
    public record GiftFacts(boolean giverMaySpend, boolean giftingOn, boolean giftableCollector, boolean onSale,
                            boolean receiverOnline, boolean giverCanSee, boolean self,
                            boolean receiverEconomyWorld, boolean receiverGamesWorld, boolean receiverInGame,
                            boolean receiverHasIt, boolean cap, boolean headEmpty, boolean bagHasRoom,
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
        if (!f.giftingOn() || !f.giftableCollector() || !f.onSale()) {
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
            return name + " already has the " + f.item() + "!";
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
        boolean giftable = p.collector() != null && p.collector().giftable();
        PlayerInventory inv = online ? r.getInventory() : null;
        ItemStack helmet = inv == null ? null : inv.getHelmet();
        return new GiftFacts(giverMaySpend, plugin.config().arcade().collectorsGifting(), giftable, visible(p),
                online, online && giver.canSee(r), receiverId != null && receiverId.equals(giver.getUniqueId()),
                online && plugin.sandbox().allowed(r.getWorld()), online && gamesWorld(r.getWorld()),
                online && (!homeFromGames(r) || r.isDead()), online && (owned(r, p) || left(r.getUniqueId(), p) <= 0),
                p.type() == PrizeType.HAT, helmet == null || helmet.getType().isAir(),
                inv != null && inv.firstEmpty() != -1,
                plugin.tokens().balance(giver.getUniqueId()), price(giver.getUniqueId(), p), name,
                Text.plain(p.display()));
    }

    /**
     * Give a collector to someone online (D8): every check first ({@link #giftRefusal}), then build,
     * claim it for the receiver, charge the giver, and hand it over — each step undone if a later one
     * fails, and never onto the ground. On success the giver's menu is closed (a buy reopens the
     * counter; a present must not hide the moment) and both players get their feedback here, so the
     * caller only shows a failure's message and plays {@link Sounds#refused}, as for {@link #buy}.
     */
    public Outcome gift(Player giver, UUID receiverId, Prize p) {
        if (!plugin.sandbox().check(giver, "prize gift " + p.id())) {
            return Outcome.fail(EconomySandbox.reason());
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
     * A collector trail this server can't show is refused ({@link #notHereRefusal}).
     */
    public Outcome restore(Player target, Prize p, String fromName) {
        if (p == null || p.collector() == null) {
            return Outcome.fail("That isn't a collector.");
        }
        String notHere = notHereRefusal(p, trailsNotHere);
        if (notHere != null) {
            return Outcome.fail(notHere);
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
        closeOurMenu(r);
        String item = Text.plain(p.display());
        try {
            r.showTitle(Title.title(Text.of("&6A present!"), Text.of("&e" + fromName + " gave you the " + item),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))));
            r.playSound(r.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            Sounds.won(r);
            Fireworks.burstInView(r);
            if (placed != null) {
                Location eye = r.getEyeLocation();
                r.spawnParticle(Particle.HEART, eye.clone().add(eye.getDirection().multiply(1.5)), 10, 0.4, 0.3, 0.4, 0);
            }
        } catch (RuntimeException ignored) {
            // cosmetic
        }
        r.sendMessage(Text.of("&6✦ &e" + fromName + " gave you the " + item + "! &a" + where(placed)));
    }

    /**
     * Close the receiver's screen before their present's moment (review BK-1) when it is one of our
     * menus other than a game screen (a live match or scored try would end), which holds nothing of
     * theirs. Any other screen (a chest, another plugin's, a game's) is left open, and the moment plays
     * over it. A trail present calls this before its select, whose "trail is on" burst is part of the moment.
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
     * never a game screen ({@link com.dierks.homecraft.gui.games.GameMenu}): closing one ends a friend
     * match for both players, or a Daily board's scored try with no score (final audit F2).
     */
    static boolean hidesTheMoment(org.bukkit.inventory.InventoryHolder holder) {
        return holder instanceof com.dierks.homecraft.gui.Menu
                && !(holder instanceof com.dierks.homecraft.gui.games.GameMenu);
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
        var games = plugin.games();
        return games == null || (games.sessions().session(player) == null && games.sessions().home(player));
    }

    /** A Games world (case-insensitive): checked on its own, because an empty economy list means everywhere. */
    private boolean gamesWorld(World world) {
        if (world == null || plugin.config().games() == null) {
            return false;
        }
        for (String name : plugin.config().games().common().worlds()) {
            if (name.equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
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

    private static void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item.clone()).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    private static String pretty(String enumName) {
        String n = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
