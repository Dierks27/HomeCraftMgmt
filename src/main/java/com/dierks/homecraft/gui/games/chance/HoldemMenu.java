package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.chance.holdem.HandValue;
import com.dierks.homecraft.games.chance.holdem.Holdem;
import com.dierks.homecraft.games.chance.holdem.HoldemHand;
import com.dierks.homecraft.games.chance.holdem.HoldemSettings;
import com.dierks.homecraft.games.chance.holdem.HoldemTable;
import com.dierks.homecraft.games.chance.holdem.HoldemTable.Kind;
import com.dierks.homecraft.games.chance.holdem.HoldemTable.Phase;
import com.dierks.homecraft.games.chance.holdem.HoldemTable.Seat;
import com.dierks.homecraft.games.chance.holdem.PokerCard;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.pack.CardFace;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Texas Hold'em table's screen (54 slots; 0.57.0): everyone sitting at the table (and anyone watching a full one)
 * has it open and sees the same table; only your own two cards are face up to you until the showdown.
 *
 * <pre>
 *  row 0     0 the pot (and side pots) · 2-6 the board · 8 the hand: its number, the blinds, the button
 *  rows 1-2  six seats, three to a row: [who, chips, what they did] [card] [card]
 *  row 3     27-28 your two cards · 29 your best hand now · 31 the last hand's result · 33 what a chip is · 35 how to play
 *  row 4     your turn: 36 the least raise · 37 +1 big blind · 38 +5 big blinds · 39 + the pot
 *            41 sit out / back in · 44 your balance
 *  row 5     your turn: 45 Fold · 46 Check or Call · 47 Raise to · 48 All in
 *            49 Leave · 50 whose turn it is, or the next hand · 51 your chips · 52 Hurry Up · 53 today
 *            no chips:  45 the chips put together (money) · 46-48 buy-ins or chips · 50 Buy in (or more play chips)
 * </pre>
 *
 * The four moves sit side by side on the bottom row, so a controller's cursor goes straight across them. Closing the
 * screen between hands gets you up with your chips; in a hand you are still in, you are away, and the table checks or
 * folds for you. Nothing here moves tokens or money: the table does, through the play gate.
 */
public final class HoldemMenu extends GameMenu {

    private static final int POT = 0;
    private static final int[] BOARD = {2, 3, 4, 5, 6};
    private static final int INFO = 8;
    private static final int MY_CARDS = 27;
    private static final int MY_HAND = 29;
    private static final int RESULT = 31;
    private static final int WHAT = 33;
    private static final int RULES = 35;
    private static final int LEAST = 36;
    private static final int PLUS_BB = 37;
    private static final int PLUS_5BB = 38;
    private static final int PLUS_POT = 39;
    private static final int SIT_OUT = 41;
    private static final int BALANCE = 44;
    private static final int FOLD = 45;
    private static final int CALL = 46;
    private static final int RAISE = 47;
    private static final int ALL_IN = 48;
    private static final int STATUS = 50;
    private static final int CHIPS = 51;
    private static final int HURRY = 52;
    private static final int TODAY = 53;
    private static final int[] BUY_INS = {46, 47, 48};
    private static final int BUY = 50;

    /** The screen each player has open on a table now: an older one closing doesn't get them up. */
    private static final Map<UUID, HoldemMenu> OPEN = new ConcurrentHashMap<>();

    private final Holdem holdem;
    private final HoldemTable table;
    private final MoneyPlay money;
    private boolean seated;
    private boolean watching;
    /** The raise being built, and the turn it was built for. */
    private int raise;
    private String raiseFor = "";
    /** The token buy-in picked. */
    private int buyIn;
    /** The hand whose result this player has heard, and the turn they were last pinged for. */
    private int heard = -1;
    private String pinged = "";

    public HoldemMenu(HomeCraftManagement plugin, Holdem game, HoldemTable table, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.holdem = game;
        this.table = table;
        this.money = new MoneyPlay(plugin, game.games(), viewer);
        List<Integer> b = game.settings().buyIns();
        this.buyIn = b.isEmpty() ? 0 : b.get(Math.min(1, b.size() - 1));
        init(54, Text.of(MenuStyle.title("Texas Hold'em", table.kind().label.toLowerCase(java.util.Locale.ROOT))));
    }

    /** Sit down (or watch a full table), then show it. */
    @Override
    public void open(Player player) {
        HoldemTable other = holdem.tableOf(viewer.getUniqueId());
        if (other != null && other != table) {
            other.leave(viewer.getUniqueId()); // one table at a time (gets up there with the chips)
        }
        HoldemMenu before = OPEN.get(viewer.getUniqueId());
        if (before != null && before.table != table) {
            before.table.leave(viewer.getUniqueId()); // they were watching another table: not any more
        }
        if (table.kind() == Kind.MONEY && !holdem.games().parlor().offered(viewer)) {
            holdem.games().tell(viewer, holdem.games().parlor().allowed(viewer));
            return;
        }
        Seat s = table.sit(viewer, null);
        if (s == null) {
            watching = true;
            viewer.sendMessage(Text.of("&7The " + table.kind().label.toLowerCase(java.util.Locale.ROOT)
                    + " is full (" + HoldemTable.MAX_SEATS + " are sitting): you're watching. A seat opens when"
                    + " someone gets up."));
        } else {
            seated = true;
        }
        if (table.kind() == Kind.MONEY && !money.on()) {
            money.flip();
        }
        HoldemHand h = table.hand();
        heard = h != null && h.over() ? table.handNo() : -1; // what is already on the table isn't news
        OPEN.put(viewer.getUniqueId(), this);
        super.open(player);
        if (seated) {
            table.sit(viewer, this::refresh);
        } else {
            table.watch(viewer, this::refresh);
        }
        ticker(20, () -> {
            if (table.nextSeconds() >= 0) {
                refresh(); // the next hand's countdown (Hurry Up's, the table repaints itself)
            }
        });
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (OPEN.get(viewer.getUniqueId()) != this) {
            return; // another screen of this table took over: they are still sitting
        }
        OPEN.remove(viewer.getUniqueId());
        if (seated || watching) {
            seated = false;
            watching = false;
            table.leave(viewer.getUniqueId());
        }
    }

    private Seat me() {
        return table.seat(viewer.getUniqueId());
    }

    // ---- painting -----------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        Seat me = me();
        HoldemHand hand = table.phase() == Phase.WAITING ? null : table.hand();
        paintPot(hand);
        paintBoard(hand);
        paintInfo(hand);
        List<Seat> places = table.places();
        for (int p = 0; p < HoldemTable.MAX_SEATS; p++) {
            paintSeat(9 + (p / 3) * 9 + (p % 3) * 3, places.get(p), hand, me);
        }
        set(RESULT, resultTile(), null);
        set(WHAT, whatTile(), null);
        set(RULES, rulesTile(holdem.rules()), null);
        exitTile();
        if (me == null) {
            set(STATUS, Menus.icon(Material.SPYGLASS, "&7Watching", "&7The table is full. A seat opens when",
                    "&7someone gets up: open the table again then."), null);
            return;
        }
        paintMine(me, hand);
        set(CHIPS, Menus.icon(Material.GOLD_NUGGET, "&eYour chips: &6" + table.chipsText(table.chips(me)),
                "&7Worth " + table.worth(table.chips(me)) + "."), null);
        if (table.kind().stakes()) {
            set(BALANCE, table.kind() == Kind.MONEY ? money.balanceTile() : balanceTile(), null);
            int limit = holdem.settings().dailyLimit();
            set(TODAY, table.kind() == Kind.MONEY ? money.todayTile(holdem, limit, "buy-ins")
                    : CardGameTiles.today(holdem.games(), holdem, viewer, plugin.clock(), limit, "buy-ins"), null);
        }
        HoldemHand.Player mine = table.inHand(me);
        boolean myTurn = me == table.turn() && hand != null;
        if (myTurn) {
            paintMoves(me, hand);
        } else if (needsChips(me, mine)) {
            paintBuyIn(me);
        } else {
            for (int slot : new int[] {FOLD, CALL, RAISE, ALL_IN}) {
                set(slot, CardGameTiles.unlit(slot == FOLD ? "Fold" : slot == CALL ? "Check or Call"
                        : slot == RAISE ? "Raise" : "All in", null), null);
            }
        }
        if (!needsChips(me, mine)) {
            paintSitOut(me);
            paintStatus(me, hand); // (with no chips, the Buy in button has its place)
        }
        paintHurry(me);
        hearResult(me, hand);
        pingTurn(me, myTurn, hand);
    }

    private boolean needsChips(Seat me, HoldemHand.Player mine) {
        return table.chips(me) <= 0 && (table.phase() != Phase.PLAYING || mine == null || mine.folded());
    }

    private void paintPot(HoldemHand hand) {
        if (hand == null) {
            set(POT, Menus.icon(Material.BOWL, "&7No hand yet", "&7The cards come when two players",
                    "&7with chips are at the table."), null);
            return;
        }
        List<HoldemHand.Pot> pots = hand.pots();
        List<String> lore = new ArrayList<>();
        lore.add("&7" + hand.street().label);
        if (pots.size() > 1) {
            for (int k = 0; k < pots.size(); k++) {
                lore.add("&7" + (k == 0 ? "Main pot" : "Side pot " + k) + ": &6" + table.chipsText(pots.get(k).amount())
                        + " &8(" + pots.get(k).eligible().size() + " in it)");
            }
        }
        set(POT, Menus.glint(Menus.icon(Material.GOLD_BLOCK, "&6Pot: " + table.chipsText(hand.pot()),
                lore.toArray(new String[0])), hand.over()), null);
    }

    private void paintBoard(HoldemHand hand) {
        String[] names = {"The flop", "The flop", "The flop", "The turn", "The river"};
        List<PokerCard> board = hand == null ? List.of() : hand.board();
        for (int k = 0; k < BOARD.length; k++) {
            set(BOARD[k], k < board.size() ? card(board.get(k))
                    : Menus.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8" + names[k]), null);
        }
    }

    private void paintInfo(HoldemHand hand) {
        HoldemSettings s = holdem.settings();
        List<String> lore = new ArrayList<>();
        lore.add("&7Blinds: &f" + s.smallBlind() + " &7and &f" + s.bigBlind() + " &7chips.");
        if (hand != null) {
            Seat button = table.atHand(hand.button());
            lore.add("&7The button: &f" + (button == null ? "-" : plain(button.name())));
        }
        lore.add("&7The blinds move one seat every hand.");
        set(INFO, Menus.icon(Material.CLOCK, "&eHand " + Math.max(1, table.handNo()) + " &8· &7" + table.kind().label,
                lore.toArray(new String[0])), null);
    }

    private void paintSeat(int slot, Seat seat, HoldemHand hand, Seat me) {
        if (seat == null) {
            set(slot, Menus.icon(Material.OAK_STAIRS, "&8An empty seat"), null);
            return;
        }
        boolean mine = seat == me;
        boolean npc = seat.npc() != null;
        HoldemHand.Player p = hand == null ? null : table.inHand(seat);
        String marker = "";
        if (p != null) {
            int i = seat.inHand();
            marker = i == hand.button() ? " &e(D)" : "";
            marker += i == hand.smallBlindAt() ? " &7(SB)" : i == hand.bigBlindAt() ? " &7(BB)" : "";
        }
        String who = (mine ? "&aYou" : npc ? seat.name() : "&f" + seat.name()) + marker + " &8· &6"
                + table.chipsText(table.chips(seat));
        List<String> lore = new ArrayList<>();
        if (p != null) {
            if (p.folded()) {
                lore.add("&8Folded");
            } else if (p.allIn()) {
                lore.add("&6All in");
            }
            if (table.phase() == Phase.PLAYING && p.street() > 0) {
                lore.add("&7This round: &f" + p.street() + " in");
            }
            if (p.total() > 0) {
                lore.add("&7This hand: &f" + p.total() + " in");
            }
            if (p.last() != null && !p.folded()) {
                lore.add("&7Last: &f" + actText(p));
            }
            if (hand.over() && p.won() > 0) {
                lore.add("&aGot &6" + table.chipsText(p.won()) + " &afrom the pot");
            }
            if (p.value() != null) {
                lore.add("&f" + p.value().name());
            }
        } else if (seat.sittingOut()) {
            lore.add("&7Sitting out");
        } else if (table.chips(seat) <= 0) {
            lore.add(table.kind().stakes() ? "&7Choosing what to buy in with" : "&7Out of chips");
        } else if (table.phase() == Phase.PLAYING) {
            lore.add("&7Waits for the next hand");
        }
        if (seat.away()) {
            lore.add("&8Away: the table checks or folds for them");
        }
        boolean turn = seat == table.turn();
        if (turn) {
            lore.add(npc ? "&eThinking..." : mine ? "&aYour move" : "&eTheir move");
        }
        if (npc) {
            boolean canSend = me != null && table.phase() != Phase.PLAYING;
            lore.add("&7An NPC player: it plays pretend chips.");
            if (canSend) {
                lore.add("&eClick to ask it to get up");
            }
            set(slot, Menus.glint(Menus.count(Menus.icon(Material.ARMOR_STAND, who, lore.toArray(new String[0])),
                    seat.plays().stars), turn), canSend ? e -> {
                        if (table.sendAway(viewer, seat)) {
                            viewer.playSound(viewer.getLocation(), org.bukkit.Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.5f, 1.4f);
                        }
                    } : null);
        } else {
            set(slot, Menus.glint(Menus.icon(mine ? Material.LIME_DYE : Material.NAME_TAG, who,
                    lore.toArray(new String[0])), turn), null);
        }
        // the two cards: yours face up, everyone's at the showdown, face down while they're in, nothing once folded
        if (p != null && !p.folded()) {
            boolean show = mine || hand.street() == HoldemHand.Street.SHOWDOWN;
            for (int k = 0; k < 2; k++) {
                set(slot + 1 + k, show ? card(p.hole().get(k)) : faceDown(), null);
            }
        }
    }

    private static String actText(HoldemHand.Player p) {
        return switch (p.last()) {
            case RAISE -> "Raise to " + p.lastTo();
            case CALL -> "Call (" + p.lastTo() + " in)";
            case SMALL_BLIND, BIG_BLIND -> p.last().label + " (" + p.lastTo() + ")";
            default -> p.last().label;
        };
    }

    /** Your two cards, large in your own row, and the best hand you have now. */
    private void paintMine(Seat me, HoldemHand hand) {
        HoldemHand.Player p = hand == null ? null : table.inHand(me);
        if (p == null) {
            set(MY_CARDS, CardGameTiles.faceDown(CardGameTiles.art(plugin, viewer),
                    "&8Your cards come with the next hand"), null);
            return;
        }
        set(MY_CARDS, card(p.hole().get(0)), null);
        set(MY_CARDS + 1, card(p.hole().get(1)), null);
        if (p.folded()) {
            set(MY_HAND, Menus.icon(Material.GRAY_DYE, "&7You folded this hand"), null);
            return;
        }
        List<PokerCard> seven = new ArrayList<>(p.hole());
        seven.addAll(hand.board());
        if (seven.size() >= 5) {
            HandValue v = HandValue.of(seven);
            set(MY_HAND, Menus.icon(Material.BOOK, "&aYou have " + v.name(), "&7" + v.cardsText()), null);
        } else {
            PokerCard a = p.hole().get(0);
            PokerCard b = p.hole().get(1);
            String what = a.rank() == b.rank() ? "a Pair of " + PokerCard.plural(a.rank())
                    : PokerCard.rankName(Math.max(a.rank(), b.rank())) + " high";
            set(MY_HAND, Menus.icon(Material.BOOK, "&aYou have " + what,
                    a.suit() == b.suit() ? "&7Both " + a.suitName() + "." : "&7The flop comes next."), null);
        }
    }

    private ItemStack resultTile() {
        List<String> lines = table.lastResult();
        if (lines.isEmpty()) {
            return Menus.icon(Material.WRITABLE_BOOK, "&7The last hand", "&7Nothing yet.");
        }
        List<String> lore = new ArrayList<>();
        for (String l : lines) {
            lore.add(l.replace("&6Hold'em: ", ""));
        }
        return Menus.icon(Material.WRITTEN_BOOK, "&eThe last hand", lore.toArray(new String[0]));
    }

    private ItemStack whatTile() {
        return switch (table.kind()) {
            case FRIENDLY -> Menus.icon(Material.LIME_WOOL, "&aFriendly table", "&7Free play chips: nothing goes in,",
                    "&7nothing comes out. NPC players welcome.");
            case TOKENS -> Menus.icon(Material.SUNFLOWER, "&eTokens table", "&71 chip = 1 token.",
                    "&7Leave between hands: your chips", "&7come back as tokens.");
            case MONEY -> Menus.icon(Material.GOLD_INGOT, "&6Money table", "&71 chip = $1.",
                    "&7Leave between hands: your chips", "&7come back as money.");
        };
    }

    // ---- your move ----------------------------------------------------------------------------------------

    private void paintMoves(Seat me, HoldemHand hand) {
        int i = me.inHand();
        int toCall = hand.toCall(i);
        boolean check = hand.canCheck(i);
        if (check) {
            set(FOLD, CardGameTiles.unlit("Fold", "nothing to call: check"), null);
        } else {
            set(FOLD, Menus.icon(Material.RED_CONCRETE, "&cFold", "&7Give up this hand.",
                    "&7What you put in stays in the pot."), e -> act(() -> table.fold(viewer)));
        }
        String callName = check ? "&aCheck" : "&aCall &6" + toCall
                + (toCall >= table.chips(me) ? " &7(all you have)" : "");
        set(CALL, Menus.icon(Material.LIME_CONCRETE, callName, check ? "&7Put nothing in, stay in the hand."
                : "&7Put in &f" + toCall + " &7to match."), e -> act(() -> table.checkCall(viewer)));
        if (hand.canRaise(i)) {
            String key = table.handNo() + ":" + hand.street() + ":" + hand.currentBet();
            int least = hand.minRaiseTo(i);
            int most = hand.maxRaiseTo(i);
            if (!key.equals(raiseFor) || raise < least) {
                raiseFor = key;
                raise = least;
            }
            raise = Math.min(raise, most);
            boolean allOfIt = raise >= most;
            set(RAISE, Menus.glint(Menus.icon(Material.ORANGE_CONCRETE, "&6" + (hand.currentBet() == 0 ? "Put in "
                            : "Raise to ") + raise + (allOfIt ? " &7(all in)" : ""),
                    "&7The chips below change how much.", "&7The least is " + least + "."), true),
                    e -> act(() -> table.raiseTo(viewer, raise)));
            int bb = holdem.settings().bigBlind();
            set(LEAST, Menus.icon(Material.BOWL, "&7Back to the least: " + least), e -> {
                raise = least;
                refresh();
            });
            chip(PLUS_BB, Material.COPPER_INGOT, bb, most, "+" + bb);
            chip(PLUS_5BB, Material.IRON_INGOT, 5 * bb, most, "+" + (5 * bb));
            int pot = hand.pot() + toCall;
            chip(PLUS_POT, Material.GOLD_INGOT, pot, most, "+" + pot + " &7(the pot)");
        } else {
            set(RAISE, CardGameTiles.unlit("Raise", "not open now"), null);
        }
        int all = hand.maxRaiseTo(i) - hand.player(i).street();
        set(ALL_IN, Menus.icon(Material.TNT, "&cAll in &7- &6" + all, "&7Put in every chip you have."),
                e -> act(() -> table.allIn(viewer)));
    }

    /** A chip that adds {@code amount} to the raise (up to everything). */
    private void chip(int slot, Material icon, int amount, int most, String label) {
        if (raise >= most) {
            set(slot, CardGameTiles.unlit(Text.plain(label), "that's everything"), null);
            return;
        }
        set(slot, Menus.count(Menus.icon(icon, "&6" + label, "&eClick to add it to the raise"), Math.min(64, amount)),
                e -> {
                    raise = Math.min(most, raise + amount);
                    refresh();
                });
    }

    private void act(java.util.function.BooleanSupplier move) {
        hold(ClickHold.ACTION_MS);
        if (!move.getAsBoolean()) {
            Sounds.refused(viewer);
        }
    }

    // ---- buying in ----------------------------------------------------------------------------------------

    private void paintBuyIn(Seat me) {
        switch (table.kind()) {
            case FRIENDLY -> {
                int n = holdem.settings().friendlyChips();
                set(BUY, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aTake " + n + " play chips",
                        "&7Free: play chips are just for fun."), true), e -> {
                    hold(ClickHold.ACTION_MS);
                    table.moreChips(viewer);
                });
            }
            case TOKENS -> {
                List<Integer> b = holdem.settings().buyIns();
                for (int k = 0; k < BUY_INS.length && k < b.size(); k++) {
                    int value = b.get(k);
                    set(BUY_INS[k], CardGameTiles.stake(value, value == buyIn, List.of("&7" + value + " chips")), e -> {
                        buyIn = value;
                        refresh();
                    });
                }
                if (b.contains(buyIn)) {
                    set(BUY, Menus.glint(Menus.icon(Material.GREEN_CONCRETE, "&aBuy in &7- &6" + buyIn + " tokens",
                            "&7You sit down with " + buyIn + " chips.", "&7Leave between hands: your chips",
                            "&7come back as tokens.", "&eClick to buy in"), true), e -> buy(buyIn));
                }
            }
            case MONEY -> {
                for (int k = 0; k < money.chips() && k < BUY_INS.length; k++) {
                    int chip = money.chip(k);
                    set(BUY_INS[k], money.chipTile(k), e -> {
                        money.add(chip);
                        refresh();
                    });
                }
                set(FOLD, money.stakeTile(), e -> {
                    money.clear();
                    refresh();
                });
                int d = money.stake();
                if (d > 0) {
                    set(BUY, Menus.glint(Menus.icon(Material.GREEN_CONCRETE, "&aBuy in &7- &6$" + d,
                            "&7You sit down with " + d + " chips.", "&7Leave between hands: your chips",
                            "&7come back as money.", "&eClick to buy in"), true), e -> buy(d));
                } else {
                    set(BUY, CardGameTiles.unlit("Buy in", "add a chip first"), null);
                }
            }
        }
    }

    private void buy(int amount) {
        hold(ClickHold.ACTION_MS);
        if (table.buyIn(viewer, amount)) {
            Sounds.paid(viewer);
        }
    }

    private void paintSitOut(Seat me) {
        if (me.sittingOut()) {
            set(SIT_OUT, Menus.glint(Menus.icon(Material.RED_BED, "&eSitting out", "&7You keep your seat and chips.",
                    "&eClick to play the next hand"), true), e -> table.sitOut(viewer, false));
        } else {
            set(SIT_OUT, Menus.icon(Material.WHITE_BED, "&7Sit out", "&7Keep your seat and chips but skip",
                    "&7the next hands.", "&eClick to sit out"), e -> table.sitOut(viewer, true));
        }
    }

    // ---- the status line, Hurry Up, sounds ----------------------------------------------------------------

    private void paintStatus(Seat me, HoldemHand hand) {
        Seat turn = table.turn();
        ItemStack tile;
        if (table.phase() == Phase.PLAYING && turn != null) {
            if (turn == me) {
                tile = Menus.glint(Menus.icon(Material.OAK_SIGN, "&aYour move", "&7Fold, Check or Call, Raise or All in."),
                        true);
            } else {
                tile = Menus.icon(Material.OAK_SIGN, "&7Waiting for &f" + plain(turn.name()),
                        turn.away() ? "&8They're away: the table moves for them soon." : "&7It's their move.");
            }
        } else if (table.nextSeconds() >= 0) {
            tile = Menus.icon(Material.OAK_SIGN, "&eNext hand in " + table.nextSeconds() + " s");
        } else {
            tile = Menus.icon(Material.OAK_SIGN, "&7Waiting for players", "&7A hand starts when two players",
                    "&7with chips are at the table.");
        }
        set(STATUS, tile, null);
    }

    private void paintHurry(Seat me) {
        int left = table.hurrySeconds();
        if (left >= 0) {
            boolean onMe = table.waitingOn(me);
            set(HURRY, Menus.glint(Menus.icon(Material.CLOCK, (onMe ? "&c" : "&e") + "Hurry Up: " + left + " s",
                    "&7" + table.hurryBy() + " asked the table to move along.",
                    onMe ? "&7When it runs out you check, or fold." : "&7Then the table moves for them."), true),
                    null);
        } else if (table.canHurry(me)) {
            set(HURRY, Menus.icon(Material.CLOCK, "&eHurry Up", "&7Gives whoever the table is waiting on",
                    "&730 seconds. Nobody is on a clock until", "&7someone asks.", "&eClick to ask"),
                    e -> table.hurry(viewer));
        }
    }

    /** The sound of this player's own result, once a hand. */
    private void hearResult(Seat me, HoldemHand hand) {
        if (hand == null || !hand.over() || heard == table.handNo()) {
            return;
        }
        HoldemHand.Player p = table.inHand(me);
        heard = table.handNo();
        if (p == null) {
            return;
        }
        if (p.won() > p.total()) {
            Sounds.won(viewer);
        } else if (p.won() > 0) {
            Sounds.received(viewer);
        } else {
            Sounds.miss(viewer);
        }
    }

    /** A soft ping when it becomes this player's move. */
    private void pingTurn(Seat me, boolean myTurn, HoldemHand hand) {
        if (!myTurn) {
            return;
        }
        String key = table.handNo() + ":" + hand.street() + ":" + hand.pot();
        if (!key.equals(pinged)) {
            pinged = key;
            viewer.playSound(viewer.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
        }
    }

    /** A face-up card, for this screen's viewer. */
    private ItemStack card(PokerCard c) {
        return card(CardGameTiles.art(plugin, viewer), c);
    }

    /** A face-down card (another player's, while they are in), for this screen's viewer. */
    private ItemStack faceDown() {
        return CardGameTiles.faceDown(CardGameTiles.art(plugin, viewer), "&8Face-down card");
    }

    /**
     * A card. Without HCM's pack: its suit's colour picture (four colours, so a flush reads at a glance), named in the
     * same colour ("&amp;9J♦ &amp;7Jack of Diamonds"). With it: the card's own picture, red and black as on a real
     * deck, named so too. 0.58.0: the count is the rank with Aces high (an Ace 14, as in Video Poker; it was 1), or no
     * number on a picture ({@link CardGameTiles#rankCount}).
     */
    static ItemStack card(boolean art, PokerCard c) {
        CardFace face = CardFace.aceHigh(c.rank(), c.suit());
        int count = CardGameTiles.rankCount(art, face);
        if (art) {
            return CardGameTiles.card(true, face, count);
        }
        Material m = switch (c.suit()) {
            case 0 -> Material.INK_SAC;
            case 1 -> Material.RED_DYE;
            case 2 -> Material.BLUE_DYE;
            default -> Material.GREEN_DYE;
        };
        String colour = switch (c.suit()) {
            case 0 -> "&f";
            case 1 -> "&c";
            case 2 -> "&9";
            default -> "&a";
        };
        return Menus.count(Menus.icon(m, CardGameTiles.name(colour, face)), count);
    }

    private static String plain(String name) {
        return name == null ? "" : name.replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }
}
