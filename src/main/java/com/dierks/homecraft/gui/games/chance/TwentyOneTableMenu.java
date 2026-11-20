package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.twentyone.TableRound;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneMath;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneSettings;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable.Phase;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneTable.Seat;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Game Parlor's Twenty-One table (54 slots; 0.47.0): everyone sitting at it has this screen open and sees the
 * same cards.
 *
 * <pre>
 *  row 0     0 the Arcade (its total) · 1-8 its cards (the hole card face down until it plays)
 *  rows 1-4  one seat each: its player and total · up to 7 cards · what it put in, or how it came out
 *  row 5     taking stakes: 45 the chips' total · 46-48 stakes or chips · 49 Leave · 50 Ready · 51 your balance
 *                           52 Hurry Up · 53 tokens or money
 *            cards out:     46 Hit · 47 Stand · 48 Double · 49 Leave · 50 whose move it is · 51 your balance
 *                           52 Hurry Up
 * </pre>
 *
 * Closing the screen gets you up from the table; with a hand out the hand waits for you (or for someone's Hurry Up,
 * after which it stands). Nothing here moves tokens or money: the table does, through the same gate as a hand alone.
 */
public final class TwentyOneTableMenu extends GameMenu {

    private static final ItemStack FACE_DOWN = Menus.icon(Material.MAP, "&8Face-down card");
    private static final int ARCADE_ROW = 0;
    private static final int CHIPS_BACK = 45;
    private static final int[] STAKES = {46, 47, 48};
    private static final int HIT = 46;
    private static final int STAND = 47;
    private static final int DOUBLE = 48;
    private static final int READY = 50;
    private static final int BALANCE = 51;
    private static final int HURRY = 52;
    private static final int SWITCH = 53;

    private final TwentyOne twentyOne;
    private final TwentyOneTable table;
    private final MoneyPlay money;
    /** The token stake picked. */
    private int stake;
    /** The round whose result this player has already heard. */
    private TableRound heard;
    private boolean seated;

    public TwentyOneTableMenu(HomeCraftManagement plugin, TwentyOne game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.twentyOne = game;
        this.table = game.table();
        this.money = new MoneyPlay(plugin, game.games(), viewer);
        List<Integer> playable = game.settings().playable();
        this.stake = playable.isEmpty() ? 0 : playable.get(0);
        init(54, Text.of("&2Twenty-One &8- the table"));
    }

    /** Sit down, then show the table; a full table says so and stays shut. */
    @Override
    public void open(Player player) {
        Seat s = table.sit(viewer, null);
        if (s == null) {
            viewer.sendMessage(Text.of("&7The Twenty-One table is full (" + TableRound.MAX_SEATS
                    + " are sitting). A seat opens when someone gets up."));
            Sounds.refused(viewer);
            return;
        }
        seated = true;
        stake = s.money() ? stake : s.stake() > 0 ? s.stake() : stake;
        money.follow(s.money(), s.stake());
        heard = table.round(); // what is already on the table isn't news
        super.open(player);
        table.sit(viewer, this::refresh);
        ticker(20, table::tick);
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (seated) {
            seated = false;
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
        TableRound round = table.round();
        paintArcade(round);
        List<Seat> seats = table.seats();
        for (int i = 0; i < TableRound.MAX_SEATS; i++) {
            paintSeat(9 * (i + 1), i < seats.size() ? seats.get(i) : null, round, me);
        }
        exitTile();
        if (me == null) {
            return;
        }
        set(BALANCE, me.money() || money.on() ? money.balanceTile() : balanceTile(), null);
        if (table.phase() == Phase.OPEN) {
            paintStakes(me);
        } else {
            paintMoves(me, round);
        }
        paintHurry(me);
        hearResult(me, round);
    }

    private void paintArcade(TableRound round) {
        if (round == null) {
            set(ARCADE_ROW, Menus.icon(Material.NAME_TAG, "&7The Arcade", "&7It draws to 17 and stops on any 17."),
                    null);
            return;
        }
        List<Card> cards = round.dealerCards();
        String total;
        if (round.holeHidden()) {
            total = "shows " + article(cards.get(0));
        } else {
            int b = TwentyOneHand.best(cards);
            total = b > 21 ? "over 21" : Integer.toString(b);
        }
        set(ARCADE_ROW, Menus.icon(Material.NAME_TAG, "&7The Arcade &8· &f" + total), null);
        paintCards(ARCADE_ROW + 1, 8, cards, round.holeHidden() ? 1 : -1);
    }

    private void paintSeat(int row, Seat seat, TableRound round, Seat me) {
        if (seat == null) {
            set(row, Menus.icon(Material.OAK_STAIRS, "&8An empty seat"), null);
            return;
        }
        boolean mine = seat == me;
        String who = mine ? "&aYou" : "&f" + seat.name();
        TableRound.Hand hand = round != null && seat.hand() >= 0 && seat.hand() < round.seats()
                ? round.hand(seat.hand()) : null;
        String total = "";
        if (hand != null) {
            int b = TwentyOneHand.best(hand.cards());
            total = " &8· " + (b > 21 ? "&cover 21"
                    : hand.playing() && TwentyOneHand.soft(hand.cards()) && b != 21 ? "&f" + (b - 10) + " or " + b
                    : "&f" + b);
            paintCards(row + 1, 7, hand.cards(), -1);
        }
        set(row, Menus.glint(Menus.icon(mine ? Material.LIME_DYE : Material.NAME_TAG,
                who + total + (seat.away() ? " &8(away)" : "")), table.waitingOn(seat)), null);
        set(row + 8, seatNote(seat, hand), null);
    }

    /** The end of a seat's row: what it is putting in, whose move it is, or how its hand came out. */
    private ItemStack seatNote(Seat seat, TableRound.Hand hand) {
        if (table.phase() == Phase.PLAYING && hand != null && hand.result() == null) {
            String in = cash(seat.money(), hand.putIn()) + " in";
            if (seat == table.turn()) {
                return Menus.glint(Menus.icon(Material.CLOCK, "&eTheir move &7- " + in), true);
            }
            return Menus.icon(Material.PAPER, hand.stood() ? "&7Stands &8- " + in : "&7Waiting &8- " + in);
        }
        List<String> lore = new ArrayList<>();
        if (hand != null && hand.result() != null) {
            lore.add(outcome(seat, hand));
        }
        if (table.phase() == Phase.OPEN) {
            String next = seat.stake() <= 0 ? "choosing" : cash(seat.money(), seat.stake()) + " in";
            ItemStack icon = seat.ready()
                    ? Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aReady &7- &6" + next,
                    lore.toArray(new String[0])), true)
                    : Menus.icon(Material.LIGHT_GRAY_CONCRETE, "&7Not ready yet &8- " + next,
                    lore.toArray(new String[0]));
            return icon;
        }
        return Menus.icon(Material.PAPER, lore.isEmpty() ? "&7Sitting this one out" : lore.get(0));
    }

    /** "Won: $5 in → $9 back", "Twenty-One!: ...", "Same total: your 5 back", "No win this time." */
    private static String outcome(Seat seat, TableRound.Hand hand) {
        int in = hand.putIn();
        int back = hand.payout();
        String head = switch (hand.result()) {
            case TWENTY_ONE -> "Twenty-One!";
            case WIN -> "Won";
            case PUSH, BOTH_TWENTY_ONE -> "Same total";
            case BUST -> "Over 21";
            case LOSE, ARCADE_TWENTY_ONE -> "The Arcade's hand";
        };
        if (back > in) {
            return "&a" + head + ": &6" + cash(seat.money(), in) + " in → " + cash(seat.money(), back) + " back";
        }
        if (back == in) {
            return "&f" + head + ": &7" + cash(seat.money(), back) + " back";
        }
        return "&7" + head + ". No win this time.";
    }

    private static String cash(boolean dollars, int amount) {
        return dollars ? Parlor.dollars(amount) : Integer.toString(amount);
    }

    /** Up to {@code room} cards from {@code first}; with more, "N cards" and the last {@code room - 1}. */
    private void paintCards(int first, int room, List<Card> cards, int hidden) {
        int from = 0;
        int slot = first;
        if (cards.size() > room) {
            from = cards.size() - (room - 1);
            set(slot++, Menus.icon(Material.BOOK, "&f" + cards.size() + " cards &8· &ftotal "
                    + TwentyOneHand.best(cards)), null);
        }
        for (int i = from; i < cards.size(); i++) {
            Card c = cards.get(i);
            set(slot++, i == hidden ? FACE_DOWN : CardGameTiles.card(c.name(), c.red(), c.value(),
                    c.rank() == 1 ? "&7Counts as 1 or 11" : "&7Counts as " + c.value()), null);
        }
    }

    // ---- taking stakes ------------------------------------------------------------------------------------

    private void paintStakes(Seat me) {
        boolean dollars = money.on();
        TwentyOneSettings s = twentyOne.settings();
        if (!me.ready()) {
            if (dollars) {
                for (int i = 0; i < money.chips() && i < STAKES.length; i++) {
                    int chip = money.chip(i);
                    set(STAKES[i], money.chipTile(i), e -> {
                        money.add(chip);
                        table.stake(viewer.getUniqueId(), money.stake(), true);
                    });
                }
                set(CHIPS_BACK, money.stakeTile(), e -> {
                    money.clear();
                    table.stake(viewer.getUniqueId(), 0, true);
                });
            } else {
                List<Integer> playable = s.playable();
                int at = Math.max(0, playable.indexOf(stake));
                int from = Math.max(0, Math.min(at - 1, playable.size() - STAKES.length));
                for (int j = 0; j < STAKES.length && from + j < playable.size(); j++) {
                    int value = playable.get(from + j);
                    set(STAKES[j], CardGameTiles.stake(value, value == stake, pays(s.odds(value))), e -> {
                        stake = value;
                        table.stake(viewer.getUniqueId(), value, false);
                    });
                }
            }
            if (money.offered() || dollars) {
                set(SWITCH, money.switchTile(), e -> {
                    money.flip();
                    boolean now = money.on();
                    table.stake(viewer.getUniqueId(), now ? money.stake() : stake, now);
                });
            }
        }
        TwentyOneSettings.Odds odds = table.odds(me);
        if (me.ready()) {
            set(READY, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aYou're ready &7- &6"
                            + MoneyPlay.amount(me.money(), me.stake()) + " in",
                    "&7The cards come when everyone is ready.", "&eClick to take it back"), true),
                    e -> table.ready(viewer, false));
        } else if (odds == null) {
            set(READY, CardGameTiles.unlit("Ready", dollars && money.stake() <= 0 ? "add a chip first"
                    : dollars ? "not at this stake" : "closed right now"), null);
        } else {
            List<String> lore = new ArrayList<>(MoneyPlay.say(me.money(), pays(odds)).length);
            for (String line : MoneyPlay.say(me.money(), pays(odds))) {
                lore.add(line);
            }
            lore.add("&eClick when you're ready for the cards");
            set(READY, Menus.icon(Material.GREEN_CONCRETE, "&aReady &7- &6" + MoneyPlay.amount(me.money(), odds.stake())
                    + " in", lore.toArray(new String[0])), e -> {
                hold(ClickHold.ACTION_MS);
                table.ready(viewer, true);
            });
        }
    }

    /** "Win: 5 in → 9 back" and the rest, as on a hand alone. */
    private static List<String> pays(TwentyOneSettings.Odds odds) {
        List<String> out = new ArrayList<>();
        if (odds == null) {
            return out;
        }
        TwentyOneMath.Payouts p = odds.payouts();
        out.add("&7Win: &f" + p.in() + " in → " + p.win() + " back");
        out.add("&7Twenty-One!: &f" + p.in() + " in → " + p.twentyOne() + " back");
        if (p.canDouble()) {
            out.add("&7Double win: &f" + (2 * p.in()) + " in → " + p.doubleWin() + " back");
        }
        out.add("&7With the best play it " + RtpLimits.playerLine(odds.rtp()) + ".");
        return out;
    }

    // ---- cards out ----------------------------------------------------------------------------------------

    private void paintMoves(Seat me, TableRound round) {
        Seat turn = table.turn();
        boolean mine = turn == me && round != null && me.hand() >= 0;
        if (mine) {
            TableRound.Hand hand = round.hand(me.hand());
            int b = TwentyOneHand.best(hand.cards());
            set(HIT, Menus.icon(Material.LIME_CONCRETE, "&aHit &7- take a card"), e -> {
                hold(ClickHold.ACTION_MS);
                table.hit(viewer);
            });
            set(STAND, Menus.icon(Material.YELLOW_CONCRETE, "&eStand &7- stay on " + b), e -> {
                hold(ClickHold.ACTION_MS);
                table.stand(viewer);
            });
            if (hand.canDouble()) {
                int extra = hand.terms().in();
                set(DOUBLE, Menus.icon(Material.ORANGE_CONCRETE, "&6Double &7- put in &6" + cash(me.money(), extra)
                                + " &7more", "&7You get exactly 1 card, then you stand."), e -> {
                    hold(ClickHold.ACTION_MS);
                    if (table.doubleDown(viewer)) {
                        Sounds.paid(viewer);
                    }
                });
            } else {
                set(DOUBLE, CardGameTiles.unlit("Double", hand.cards().size() == 2 ? "not at this stake"
                        : "first two cards only"), null);
            }
            set(READY, Menus.glint(Menus.icon(Material.OAK_SIGN, "&eYour move: Hit, Stand"
                    + (hand.canDouble() ? " or Double" : "")), true), null);
        } else {
            set(HIT, CardGameTiles.unlit("Hit", null), null);
            set(STAND, CardGameTiles.unlit("Stand", null), null);
            set(DOUBLE, CardGameTiles.unlit("Double", null), null);
            set(READY, Menus.icon(Material.OAK_SIGN, turn == null ? "&7The Arcade's turn"
                    : "&7Waiting for &f" + turn.name()), null);
        }
    }

    private void paintHurry(Seat me) {
        int left = table.hurrySeconds();
        if (left >= 0) {
            boolean onMe = table.waitingOn(me);
            set(HURRY, Menus.glint(Menus.icon(Material.CLOCK, (onMe ? "&c" : "&e") + "Hurry Up: " + left + " s",
                    "&7" + table.hurryBy() + " asked the table to move along.",
                    onMe ? "&7When it runs out, the table goes on without you." : "&7Then the table goes on."), true),
                    null);
        } else if (table.canHurry(me)) {
            set(HURRY, Menus.icon(Material.CLOCK, "&eHurry Up",
                    "&7Gives whoever the table is waiting on", "&730 seconds. Nobody is on a clock until", "&7someone asks.",
                    "&eClick to ask"), e -> table.hurry(viewer));
        }
    }

    /** The sound of this player's own result, once a round. */
    private void hearResult(Seat me, TableRound round) {
        if (round == null || round == heard || me.hand() < 0 || me.hand() >= round.seats()) {
            return;
        }
        TableRound.Hand hand = round.hand(me.hand());
        if (hand.result() == null) {
            return;
        }
        heard = round;
        if (hand.payout() > hand.putIn()) {
            Sounds.won(viewer);
        } else if (hand.payout() == hand.putIn()) {
            Sounds.received(viewer);
        } else {
            Sounds.miss(viewer);
        }
    }

    private static String article(Card c) {
        return c.rank() == 1 ? "an Ace" : c.rank() == 8 ? "an 8" : "a " + c.rankName();
    }
}
