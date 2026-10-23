package com.dierks.homecraft.trade;

import com.dierks.homecraft.mini.MiniService;
import org.bukkit.GameMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The trade window's rules (SHOP-V2-SPEC §4.2, §4.6 step 1, §4.9, §4.10, D41), pure: who may ask whom, what can't
 * go on a table, the money rule (T3) and the swap's last check. The first failure is the message, so the order of
 * every list here is the order the spec gives, and {@code TradeRulesTest} walks it.
 *
 * <p>Nothing here reads a player, an item or the database: the service and the menus gather the facts
 * ({@link ItemFacts}, {@link Where}, the collector claims) and these say yes or no.
 */
public final class TradeRules {

    private TradeRules() {
    }

    // ---- the words (the NAME of the refusal hint, §4.9) -------------------------------------------

    /** Row 0: past the depth limit, or unreadable (D36). */
    public static final String TOO_DEEP = "&7Too many bags inside bags - take some out first.";
    /** Row 1. */
    public static final String KIT = "&7Game items stay in the game.";
    /** Row 3. */
    public static final String RENTED = "&7Rented looks can't be traded.";
    /** Row 4. */
    public static final String PACKAGE = "&7Deliver the package first.";
    /** Row 5. */
    public static final String DISPLAY_COPY = "&7Display copies can't be traded.";
    /** Row 6 (T5). */
    public static final String BAG = "&7Your bag stays with you.";
    /** Row 7, and a ghost found in a bag at the swap. */
    public static final String LOCKED = "&7That can't be traded.";
    /** Row 8, neither side holds the cap's claim (D41). */
    public static final String NO_CLAIM = "&7This cap can't be traded - ask an admin.";
    /** T3 (§4.10): money and an Arcade prize never go in one trade. */
    public static final String NO_MONEY_WITH_PRIZES = "Take the money off first - Arcade prizes can't be sold.";
    /** Two copies of one Mini uid on the tables (§4.8). */
    public static final String MINI_TWICE = "That Mini is already on the table.";

    /** Row 2: a Keeper. */
    static String keeper(String item) {
        return "&7" + item + " is yours for good - it can't be traded.";
    }

    /** Row 8, the receiver already holds the cap's claim and so does the giver (T4, D41). */
    static String hasIt(String receiver, String item) {
        return "&7" + receiver + " already has the " + item + "!";
    }

    /** Check (4) of §4.2, and the swap's "both still pass (1)". */
    static String cantTradeNow(String name) {
        return name + " can't trade right now (they're in a game or another world).";
    }

    // ---- where a player is (§4.2 (1), §4.13) ------------------------------------------------------

    /**
     * Where one player stands, as the trade window asks it.
     *
     * @param economyWorld  {@code EconomySandbox.allowed} for their world
     * @param gamesWorld    a Games world (checked on its own: an empty economy list means everywhere)
     * @param homeFromGames no world game session and nothing sending them back ({@code Whereabouts})
     * @param alive         not dead
     * @param mode          their game mode: only Survival or Adventure may trade (a Creative client can clone any
     *                      stack, a Mini's uid included; red team D-m2)
     */
    public record Where(boolean economyWorld, boolean gamesWorld, boolean homeFromGames, boolean alive,
                        GameMode mode) {

        /** Why this player can't trade where they are, or null when they can. */
        public String refusal() {
            if (!economyWorld) {
                return "You can't trade in this world.";
            }
            if (gamesWorld) {
                return "You can't trade in a Games world.";
            }
            if (!homeFromGames) {
                return "You can't trade while you're in a game.";
            }
            if (!alive) {
                return "You can't trade right now.";
            }
            if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
                return "You can't trade in Creative or Spectator mode.";
            }
            return null;
        }

        public boolean ok() {
            return refusal() == null;
        }
    }

    // ---- asking (§4.2) ------------------------------------------------------------------------------

    /** Requests a player may send in a minute (red team D-m10). */
    public static final int ASKS_PER_MINUTE = 3;

    /**
     * Everything the ask check reads (§4.2), in its order.
     *
     * @param tradingOn       {@code trade.enabled}
     * @param asker           where the asker is
     * @param targetOnline    the target is online
     * @param targetSeen      the asker can see them ({@code canSee}: a vanished admin is not there)
     * @param self            the target is the asker
     * @param npc             the target carries {@code NPC} metadata (Citizens)
     * @param targetName      the target's name, for the messages
     * @param sameWorld       both in one world
     * @param distance        blocks apart
     * @param maxDistance     {@code trade.max_distance} (0 = anywhere in the world)
     * @param target          where the target is
     * @param takesTrades     the target's {@code trade.requests} (default on)
     * @param askerTrading    the asker is in a trade
     * @param askerPending    the asker has a request out, or one waiting for them, with someone else
     * @param targetTrading   the target is in a trade
     * @param targetPending   the target has a request out, or one waiting for them, with someone else
     * @param coolingDown     the pair is inside its cooldown after a no, a timeout or the asker calling it off
     * @param askedLastMinute requests the asker sent in the last minute
     */
    public record Ask(boolean tradingOn, Where asker, boolean targetOnline, boolean targetSeen, boolean self,
                      boolean npc, String targetName, boolean sameWorld, double distance, int maxDistance,
                      Where target, boolean takesTrades, boolean askerTrading, boolean askerPending,
                      boolean targetTrading, boolean targetPending, boolean coolingDown, int askedLastMinute) {
    }

    /** Why this request can't be sent (the first failure of §4.2's seven checks), or null when it can. */
    public static String askRefusal(Ask f) {
        // (1) trading on, and the asker may trade where they are
        if (!f.tradingOn()) {
            return "Trading is switched off.";
        }
        String here = f.asker() == null ? "You can't trade right now." : f.asker().refusal();
        if (here != null) {
            return here;
        }
        // (2) the target: online, seen, not the asker, a real player
        if (!f.targetOnline() || !f.targetSeen()) {
            return "That player isn't online.";
        }
        if (f.self()) {
            return "You can't trade with yourself.";
        }
        if (f.npc()) {
            return "You can only trade with players.";
        }
        String name = f.targetName();
        // (3) the same world, close enough
        if (!f.sameWorld()) {
            return name + " is in another world.";
        }
        if (f.maxDistance() > 0 && f.distance() > f.maxDistance()) {
            return name + " is too far away - come within " + f.maxDistance() + " blocks.";
        }
        // (4) the target may trade where they are
        if (f.target() == null || !f.target().ok()) {
            return cantTradeNow(name);
        }
        // (5) the target takes trades
        if (!f.takesTrades()) {
            return name + " isn't taking trades right now.";
        }
        // (6) nobody busy, the pair not cooling down
        if (f.askerTrading()) {
            return "You're already trading.";
        }
        if (f.askerPending()) {
            return "You already have a trade request waiting - answer it or cancel it first.";
        }
        if (f.targetTrading() || f.targetPending()) {
            return name + " is busy - try again in a moment.";
        }
        if (f.coolingDown()) {
            return "Wait a moment before asking " + name + " again.";
        }
        // (7) three requests a minute
        if (f.askedLastMinute() >= ASKS_PER_MINUTE) {
            return "That's a lot of requests - wait a minute.";
        }
        return null;
    }

    /**
     * The crouch gesture (§4.2, T6): crouching, the main hand, an empty main hand, at a real player. Anything held
     * would also fire on the same click (a boost, a firework, a hat, a Mini), so the hand must be empty.
     */
    public static boolean gestureAsks(boolean sneaking, boolean mainHand, boolean handEmpty, boolean targetPlayer,
                                      boolean npc) {
        return sneaking && mainHand && handEmpty && targetPlayer && !npc;
    }

    /**
     * The command a request's [Trade] or [No thanks] click runs: this plugin's own prefixed label, which reaches it
     * whoever owns the bare {@code /trade} (red-team finding CMD-4: on a server where another plugin owns it, the click
     * ran that plugin's command, no window opened and the pair went on its cooldown).
     */
    public static String click(String verb, String name) {
        return "/homecraftmanagement:trade " + verb + " " + name;
    }

    /**
     * How a hint names the trade command a player can type: {@code /trade}, or {@code /hcm trade} where another plugin
     * owns the bare {@code /trade} label ({@code ownsLabel} false; red-team finding CMD-4).
     */
    public static String typed(boolean ownsLabel) {
        return ownsLabel ? "/trade" : "/hcm trade";
    }

    /**
     * Whether the Bedrock "Trade?" screen opens now (§4.2, red team D-m10, O12): only with no container of any kind
     * open (her open view's top inventory is her own crafting grid), an empty cursor, and not in a game. Opening a
     * screen over a chest or a crafting table would close it and drop a carried stack.
     */
    public static boolean askScreenOpens(boolean noContainerOpen, boolean cursorEmpty, boolean inGame) {
        return noContainerOpen && cursorEmpty && !inGame;
    }

    /**
     * Whether a trade window may open over what a player has open now (red-team finding TW-1, the same rule as the
     * "Trade?" screen's): no container of any kind open, or only the "Trade?" screen (it holds nothing), and an empty
     * cursor. Otherwise a yes waits, and the windows open the second both players are free: opening one over a chest
     * or a crafting table would close it and drop a carried stack or the grid when the bag is full.
     */
    public static boolean windowOpens(boolean noContainerOpen, boolean onAskScreen, boolean cursorEmpty) {
        return (noContainerOpen || onAskScreen) && cursorEmpty;
    }

    // ---- what can't be traded (§4.9) -----------------------------------------------------------------

    /**
     * One refusal: its row in §4.9's table and the hint's NAME. A ghost (row 9) is refused silently: its
     * message is empty.
     */
    public record Refusal(int row, String message) {

        public boolean silent() {
            return message == null || message.isEmpty();
        }
    }

    /**
     * {@code trade.refuse_items}, read: material names (upper case) and data keys (lower case,
     * {@code namespace:key}, written {@code pdc:<namespace>:<key>} in the config).
     */
    public record Refuse(Set<String> materials, Set<String> keys) {

        public static final Refuse NONE = new Refuse(Set.of(), Set.of());

        public Refuse {
            materials = materials == null ? Set.of() : Set.copyOf(materials);
            keys = keys == null ? Set.of() : Set.copyOf(keys);
        }

        /** Read the config list; an entry that is neither shape is one WARN and left out. */
        public static Refuse parse(List<String> raw, Consumer<String> warn) {
            Set<String> materials = new LinkedHashSet<>();
            Set<String> keys = new LinkedHashSet<>();
            for (String entry : raw == null ? List.<String>of() : raw) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String e = entry.trim();
                if (e.toLowerCase(Locale.ROOT).startsWith("pdc:")) {
                    String key = e.substring(4).trim().toLowerCase(Locale.ROOT);
                    int colon = key.indexOf(':');
                    if (colon > 0 && colon < key.length() - 1) {
                        keys.add(key);
                        continue;
                    }
                } else if (e.matches("[A-Za-z0-9_]+")) {
                    materials.add(e.toUpperCase(Locale.ROOT));
                    continue;
                }
                if (warn != null) {
                    warn.accept("trade.refuse_items: '" + entry + "' is not a material name or pdc:<namespace>:<key>"
                            + " - left out.");
                }
            }
            return new Refuse(materials, keys);
        }

        boolean refuses(ItemFacts f) {
            if (materials.contains(f.material().toUpperCase(Locale.ROOT))) {
                return true;
            }
            for (String k : f.keys()) {
                if (keys.contains(k.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The two claims a collector cap's rule reads (D41): the giver's and the receiver's {@code 'life'} rows. */
    public record Claim(boolean giver, boolean receiver) {

        public static final Claim NONE = new Claim(false, false);
    }

    /** The claims for one collector id, for the side giving it. */
    @FunctionalInterface
    public interface Claims {
        Claim of(String collectorId);
    }

    /**
     * What an item's refusal reads besides the item.
     *
     * @param refuse       {@code trade.refuse_items}
     * @param receiverName who would get it, for row 8's words
     * @param claims       the collector claims of the giver and the receiver
     */
    public record Context(Refuse refuse, String receiverName, Claims claims) {

        public Context {
            refuse = refuse == null ? Refuse.NONE : refuse;
            claims = claims == null ? id -> Claim.NONE : claims;
        }
    }

    /**
     * Why this item can't go on a table (§4.9, first match wins), or null when it can. Checked at add and again on
     * the real stack at the swap. A container holding a refused item is refused with the inner reason; one that
     * can't be fully read is refused outright (row 0, D36).
     */
    public static Refusal refusal(ItemFacts f, Context ctx) {
        if (f == null) {
            return null;
        }
        if (f.unread()) {
            return new Refusal(0, TOO_DEEP);
        }
        if (f.kit()) {
            return new Refusal(1, KIT);
        }
        if (f.keeper()) {
            return new Refusal(2, keeper(f.name()));
        }
        if (f.bound()) {
            return new Refusal(3, RENTED);
        }
        if (f.courier()) {
            return new Refusal(4, PACKAGE);
        }
        if (f.displayOnly()) {
            return new Refusal(5, DISPLAY_COPY);
        }
        if (f.candyBag()) {
            return new Refusal(6, BAG);
        }
        if (ctx.refuse().refuses(f)) {
            return new Refusal(7, LOCKED);
        }
        if (f.collector()) {
            Claim c = ctx.claims().of(f.prizeId());
            Claim claim = c == null ? Claim.NONE : c;
            if (claim.giver() && claim.receiver()) {
                return new Refusal(8, hasIt(ctx.receiverName(), f.name()));
            }
            if (!claim.giver() && !claim.receiver()) {
                return new Refusal(8, NO_CLAIM);
            }
        }
        if (f.ghost()) {
            return new Refusal(9, "");
        }
        for (ItemFacts in : f.inside()) {
            Refusal r = refusal(in, ctx);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    // ---- money (§4.10, T3) ---------------------------------------------------------------------------

    /** Whether either table carries an Arcade prize, at any depth (fail closed). */
    public static boolean prizeOnTable(Collection<ItemFacts> a, Collection<ItemFacts> b) {
        return carries(a) || carries(b);
    }

    private static boolean carries(Collection<ItemFacts> table) {
        if (table == null) {
            return false;
        }
        for (ItemFacts f : table) {
            if (f != null && f.carriesPrize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * T3 at an add: an item carrying a prize while money is set on either side, or money while a prize is on
     * either table, is refused. Null when it may go on.
     */
    public static String moneyRule(boolean prizeOnTable, double moneyA, double moneyB) {
        return prizeOnTable && (moneyA > 0 || moneyB > 0) ? NO_MONEY_WITH_PRIZES : null;
    }

    // ---- the swap's last check (§4.6 step 1) ------------------------------------------------------------

    /**
     * One offered entry at the swap: what the table says and what the slot holds now.
     *
     * @param similar {@code bag.getItem(slot).isSimilar(snapshot)} (a Mini's uid is in its data, so another copy
     *                isn't similar)
     * @param have    how many the slot holds now
     * @param real    the real stack's facts (null when the slot is empty)
     */
    public record Held(int slot, String name, int want, boolean similar, int have, ItemFacts real) {
    }

    /**
     * One side, read now.
     *
     * @param online     the player is online
     * @param windowOpen their trade window is open ({@code Menu.isOpenFor})
     * @param world      their world's name
     * @param canPay     Vault's {@code has()} for their money (true when they give none)
     * @param claims     the collector claims for what this side gives (the giver is this side)
     */
    public record SideNow(UUID player, String name, boolean online, boolean windowOpen, String world, Where where,
                          List<Held> entries, double money, boolean canPay, Claims claims) {
    }

    /** Why the swap can't go ahead: what both players are told, and a console WARN (or null). */
    public record Failure(String message, String warn) {
    }

    /**
     * The swap's verify (§4.6 step 1) on facts read in the swap's own tick: both online with both windows open, the
     * same world, both still allowed to trade there; every entry still the same item with at least its amount; the
     * refusal scan and T3 on the REAL stacks; each Mini uid once across both tables and in no active listing (a
     * WARN: a copy exists somewhere, red team D-m4); each collector cap's claim rule (D41, row 8); and the money.
     *
     * @param moneyOn    Vault is there and {@code trade.money} is on
     * @param listedUids the uids of Minis on both tables that are in an active Vending listing, Display Case or
     *                   Auction
     * @return null when the swap may go ahead
     */
    public static Failure verify(SideNow a, SideNow b, boolean moneyOn, Refuse refuse, Set<String> listedUids) {
        for (SideNow s : List.of(a, b)) {
            if (!s.online() || !s.windowOpen()) {
                return new Failure(s.name() + " left the trade.", null);
            }
        }
        if (a.world() == null || !a.world().equals(b.world())) {
            return new Failure("You're not in the same world any more.", null);
        }
        for (SideNow s : List.of(a, b)) {
            if (s.where() == null || !s.where().ok()) {
                return new Failure(cantTradeNow(s.name()), null);
            }
        }
        for (SideNow s : List.of(a, b)) {
            for (Held h : s.entries()) {
                if (!h.similar() || h.have() < h.want()) {
                    return new Failure(s.name() + "'s " + h.name() + " changed - look again.", null);
                }
            }
        }
        List<ItemFacts> realA = reals(a);
        List<ItemFacts> realB = reals(b);
        for (SideNow s : List.of(a, b)) {
            Context ctx = new Context(refuse, (s == a ? b : a).name(), s.claims());
            for (ItemFacts real : s == a ? realA : realB) {
                Refusal r = refusal(real, ctx);
                if (r != null) {
                    return new Failure(r.silent() ? LOCKED : r.message(), null);
                }
            }
        }
        if (moneyRule(prizeOnTable(realA, realB), a.money(), b.money()) != null) {
            return new Failure(NO_MONEY_WITH_PRIZES, null);
        }
        Set<String> seen = new HashSet<>();
        Set<String> listed = listedUids == null ? Set.of() : listedUids;
        for (List<ItemFacts> table : List.of(realA, realB)) {
            for (ItemFacts f : table) {
                for (ItemFacts mini : f.minis()) {
                    if (!seen.add(mini.miniUid())) {
                        return new Failure("&7" + mini.name() + " is on the table twice - it can't be traded.",
                                "Mini " + mini.miniUid() + " is on both tables at once - a copy?");
                    }
                    if (listed.contains(mini.miniUid())) {
                        return new Failure("&7" + mini.name() + " is in a shop too - it can't be traded.",
                                "Mini " + mini.miniUid() + " is on the table and in a listing - a copy?");
                    }
                }
            }
        }
        if ((a.money() > 0 || b.money() > 0) && !moneyOn) {
            return new Failure("Money can't be traded right now.", null);
        }
        for (SideNow s : List.of(a, b)) {
            if (s.money() > 0 && !s.canPay()) {
                return new Failure(s.name() + " doesn't have that much money any more.", null);
            }
        }
        return null;
    }

    private static List<ItemFacts> reals(SideNow s) {
        List<ItemFacts> out = new ArrayList<>();
        for (Held h : s.entries()) {
            if (h.real() != null) {
                out.add(h.real());
            }
        }
        return out;
    }

    // ---- what the swap writes (§4.6 step 5, §4.11) -------------------------------------------------------

    /** The Mini venue of a moved stack: GIFT when the receiver's side gave nothing (no items, no money). */
    public static String venue(boolean receiverGaveNothing) {
        return receiverGaveNothing ? MiniService.VENUE_GIFT : MiniService.VENUE_TRADE;
    }

    /**
     * One collector claim the swap writes (D41).
     *
     * @param moves true: the giver's claim moves to the receiver ({@code unclaim} must delete exactly one row and
     *              {@code claimOnce} must insert one); false: the cap goes home to the account that holds its claim,
     *              which must still be there, and no row moves
     */
    public record ClaimMove(UUID giver, UUID receiver, String id, boolean moves) {
    }

    /**
     * The claim rows that move for the collector caps one side gives ({@code collectors}, at any depth), read
     * against the claims as they were at the verify; one row per collector id, however many caps of it go.
     */
    public static List<ClaimMove> claimMoves(UUID giver, UUID receiver, List<ItemFacts> collectors, Claims claims) {
        List<ClaimMove> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (ItemFacts f : collectors) {
            String id = f.prizeId().toLowerCase(Locale.ROOT);
            if (!done.add(id)) {
                continue;
            }
            Claim c = claims.of(f.prizeId());
            Claim claim = c == null ? Claim.NONE : c;
            out.add(new ClaimMove(giver, receiver, f.prizeId(), claim.giver() && !claim.receiver()));
        }
        return out;
    }
}
