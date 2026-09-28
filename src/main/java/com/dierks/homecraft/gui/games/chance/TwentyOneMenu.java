package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.twentyone.TwentyOne;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand.Card;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneMath;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Twenty-One table (spec §5.4): the Arcade's cards on the top row, yours two rows down, Hit /
 * Stand / Double between, and the stakes, Deal and "How it pays" along the bottom.
 *
 * <p>The screen only draws the engine's state and forwards clicks. Every token move goes through
 * {@link ChanceRounds}: Deal opens the round (the gate runs there), a Hit is written to the round
 * BEFORE its card is painted, a Double asks {@link ChanceRounds#raise} for the extra tokens (the
 * limits are checked again at that moment, R1.10) and the end settles once. The Arcade's hole card
 * is one shared face-down item — the same bytes whatever it hides — until the hand is over.
 *
 * <p>Closing the screen leaves the hand open; opening Twenty-One again resumes it. After a hand the
 * result stays on the table and Deal is where it always is: no prompt to go again.
 */
public final class TwentyOneMenu extends GameMenu {

    /** The hole card, and any card not yet shown: one item for all of them (spec §10b). */
    private static final ItemStack FACE_DOWN = Menus.icon(Material.MAP, "&8Face-down card");

    private static final int ARCADE_ROW = 0;
    private static final int YOUR_ROW = 18;
    private static final int RULES = 9;
    private static final int GIVE_BACK = 11;
    private static final int STATUS = 13;
    private static final int TODAY = 15;
    private static final int HIT = 29;
    private static final int STAND = 31;
    private static final int DOUBLE = 33;
    private static final int DEAL = 50;
    private static final int BALANCE = 51;
    private static final int HOW_IT_PAYS = 52;

    private final TwentyOne twentyOne;
    /** The stake the next Deal puts in. */
    private int stake;
    /** The OPEN round being played, or {@code null}. */
    private ChanceRounds.Round round;
    /** The hand on the table (being played, or the last one finished), or {@code null}. */
    private TwentyOneHand hand;
    /** A hand that was finished somewhere else (the sweep) while this screen was open. */
    private boolean gone;

    /**
     * @param resume the player's OPEN round in this game, or {@code null} for a fresh table
     */
    public TwentyOneMenu(HomeCraftManagement plugin, TwentyOne game, Player viewer, Runnable back,
                         ChanceRounds.Round resume) {
        super(plugin, game, viewer, back);
        this.twentyOne = game;
        List<Integer> playable = game.settings().playable();
        this.stake = playable.isEmpty() ? 0 : playable.get(0);
        if (resume != null) {
            TwentyOneHand.Saved saved = TwentyOneHand.read(resume.data());
            this.round = resume;
            this.hand = TwentyOneHand.replay(resume.seed(), saved.terms(), saved.actions());
            this.stake = saved.terms().in();
        }
        init(54, Text.of("&2Twenty-One"));
    }

    /** A resumed hand that was already over (a crash between its last card and its payout) settles now. */
    @Override
    public void open(Player player) {
        if (round != null && hand != null && hand.done()) {
            settle(TwentyOneHand.data(hand.terms(), hand.actions()));
        }
        super.open(player);
    }

    private GamesService games() {
        return twentyOne.games();
    }

    private boolean playing() {
        return hand != null && !hand.done() && round != null;
    }

    // ---- painting --------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        TwentyOneSettings s = twentyOne.settings();
        TwentyOneSettings.Odds odds = s.odds(stake);
        paintArcade();
        paintYours();
        set(RULES, rulesTile(game.rules()), null);
        set(GIVE_BACK, giveBack(odds), null);
        set(STATUS, status(), null);
        set(TODAY, CardGameTiles.today(games(), game, viewer, plugin.clock(), s.dailyLimit(), "hands"), null);
        paintButtons();
        List<Integer> playable = s.playable();
        for (int i = 0; i < playable.size() && i < CardGameTiles.STAKE_SLOTS.length; i++) {
            int value = playable.get(i);
            set(CardGameTiles.STAKE_SLOTS[i], CardGameTiles.stake(value, value == stake, pays(s.odds(value))), e -> {
                if (!playing()) {
                    stake = value;
                    refresh();
                }
            });
        }
        exitTile();
        set(DEAL, dealButton(odds), e -> deal());
        set(BALANCE, balanceTile(), null);
        set(HOW_IT_PAYS, howItPays(odds), null);
    }

    private void paintArcade() {
        if (hand == null) {
            set(ARCADE_ROW, Menus.icon(Material.NAME_TAG, "&7The Arcade"), null);
            return;
        }
        List<Card> cards = hand.dealerCards();
        String total;
        if (hand.holeHidden()) {
            total = "shows " + article(cards.get(0));
        } else {
            int b = TwentyOneHand.best(cards);
            total = b > 21 ? "over 21" : Integer.toString(b);
        }
        set(ARCADE_ROW, Menus.icon(Material.NAME_TAG, "&7The Arcade &8· &f" + total), null);
        paintCards(ARCADE_ROW, cards, hand.holeHidden() ? 1 : -1);
    }

    private void paintYours() {
        if (hand == null) {
            set(YOUR_ROW, Menus.icon(Material.NAME_TAG, "&aYou"), null);
            return;
        }
        List<Card> cards = hand.playerCards();
        int b = TwentyOneHand.best(cards);
        String total = b > 21 ? "&cover 21"
                : !hand.done() && TwentyOneHand.soft(cards) && b != 21 ? "&f" + (b - 10) + " or " + b : "&f" + b;
        set(YOUR_ROW, Menus.icon(Material.NAME_TAG, "&aYou &8· " + total), null);
        paintCards(YOUR_ROW, cards, -1);
    }

    /**
     * One row of at most 8 cards after its label; with more, the first tile says "N cards ·
     * total T" and the last 7 are shown (spec §5.4).
     *
     * @param hidden the index of a face-down card, or -1
     */
    private void paintCards(int row, List<Card> cards, int hidden) {
        int from = 0;
        int slot = row + 1;
        if (cards.size() > 8) {
            from = cards.size() - 7;
            set(slot++, Menus.icon(Material.BOOK, "&f" + cards.size() + " cards &8· &ftotal "
                    + TwentyOneHand.best(cards)), null);
        }
        for (int i = from; i < cards.size(); i++) {
            Card c = cards.get(i);
            set(slot++, i == hidden ? FACE_DOWN : CardGameTiles.card(c.name(), c.red(), c.value(),
                    c.rank() == 1 ? "&7Counts as 1 or 11" : "&7Counts as " + c.value()), null);
        }
    }

    private void paintButtons() {
        if (playing()) {
            int b = TwentyOneHand.best(hand.playerCards());
            set(HIT, Menus.icon(Material.LIME_CONCRETE, "&aHit &7- take a card"), e -> hit());
            set(STAND, Menus.icon(Material.YELLOW_CONCRETE, "&eStand &7- stay on " + b), e -> stand());
            String why = doubleRefusal();
            if (why == null) {
                int extra = hand.terms().in();
                set(DOUBLE, Menus.icon(Material.ORANGE_CONCRETE, "&6Double &7- put in &6" + extra + " &7more",
                        "&7You get exactly 1 card, then you stand.",
                        "&7A doubled win: " + (2 * extra) + " in → " + hand.pays().doubleWin() + " back"),
                        e -> doubleDown());
            } else {
                set(DOUBLE, CardGameTiles.unlit("Double", why), null);
            }
        } else {
            set(HIT, CardGameTiles.unlit("Hit", null), null);
            set(STAND, CardGameTiles.unlit("Stand", null), null);
            set(DOUBLE, CardGameTiles.unlit("Double", null), null);
        }
    }

    /**
     * Why Double can't be used now, or {@code null} when it can: the hand's own rules first, then
     * the limits re-checked for the extra tokens (gate steps 2, 4, 7, 8), exactly as the debit will.
     */
    private String doubleRefusal() {
        if (hand.playerCards().size() != 2) {
            return "first two cards only";
        }
        if (!hand.pays().canDouble()) {
            return "not at this stake";
        }
        int extra = hand.terms().in();
        Refusal r = games().gate().extra(viewer, game, extra);
        if (r == null) {
            return null;
        }
        return switch (r.reason()) {
            case DAILY_LIMIT, PERSONAL_LIMIT -> "over your limit today";
            case BALANCE -> {
                int have = plugin.tokens() == null ? 0 : plugin.tokens().balance(viewer.getUniqueId());
                int need = Math.max(1, extra - have);
                yield "need " + need + " more token" + (need == 1 ? "" : "s");
            }
            case PAUSED -> "you're taking a break";
            case NO_PERMISSION -> "not open to you";
            case WORLD -> "not in this world";
            default -> "closed right now";
        };
    }

    private ItemStack dealButton(TwentyOneSettings.Odds odds) {
        if (playing()) {
            return CardGameTiles.unlit("Deal", "finish this hand first");
        }
        if (odds == null) {
            return CardGameTiles.unlit("Deal", "closed right now");
        }
        return Menus.icon(Material.GREEN_CONCRETE, "&aDeal &7- &6" + odds.stake() + " tokens in",
                "&7Two cards each; one of the Arcade's stays face down.");
    }

    private ItemStack status() {
        if (gone) {
            return Menus.icon(Material.OAK_SIGN, "&7That hand was already finished for you.");
        }
        if (hand == null) {
            return Menus.icon(Material.OAK_SIGN, "&ePick your tokens, then Deal",
                    "&7Beat the Arcade's hand without going over 21.");
        }
        if (!hand.done()) {
            return Menus.icon(Material.OAK_SIGN, "&eYour move: Hit, Stand" + (hand.canDouble() ? " or Double" : ""),
                    "&7You have " + TwentyOneHand.best(hand.playerCards()) + ". The Arcade shows "
                            + article(hand.dealerCards().get(0)) + ".");
        }
        int in = hand.putIn();
        int back = hand.payout();
        int theirs = TwentyOneHand.best(hand.dealerCards());
        String headline = switch (hand.result()) {
            case TWENTY_ONE -> "Twenty-One!";
            case WIN -> theirs > 21 ? "The Arcade went over 21." : "You beat the Arcade's " + theirs + ".";
            case PUSH -> "Same total.";
            case BOTH_TWENTY_ONE -> "You both have Twenty-One.";
            case LOSE -> "The Arcade has " + theirs + ".";
            case BUST -> "Over 21.";
            case ARCADE_TWENTY_ONE -> "The Arcade has Twenty-One.";
        };
        if (back > in) {
            return Menus.glint(Menus.icon(Material.EMERALD, "&a" + headline + " &6" + in + " in → " + back + " back"),
                    true);
        }
        if (back == in) {
            return Menus.glint(Menus.icon(Material.GOLD_NUGGET, "&f" + headline + " &7Your " + back + " back"), false);
        }
        return Menus.icon(Material.GRAY_DYE, "&7" + headline + " No win this time.");
    }

    private ItemStack giveBack(TwentyOneSettings.Odds odds) {
        if (odds == null) {
            return Menus.icon(Material.KNOWLEDGE_BOOK, "&7Pick how many tokens to put in");
        }
        return Menus.icon(Material.KNOWLEDGE_BOOK, "&7With the best play it gives back about &f"
                        + RtpLimits.wholePercent(odds.rtp()) + " &7of every 100 tokens",
                "&7(at " + odds.stake() + " tokens in)");
    }

    private ItemStack howItPays(TwentyOneSettings.Odds odds) {
        if (odds == null) {
            return Menus.icon(Material.WRITABLE_BOOK, "&eHow it pays");
        }
        List<String> lore = new ArrayList<>(pays(odds));
        lore.add("&7Same total: your tokens back.");
        lore.add("&7Over 21, or lower than the Arcade: nothing back.");
        lore.add("&7The Arcade checks for Twenty-One when it shows an Ace or a 10.");
        return Menus.icon(Material.WRITABLE_BOOK, "&eHow it pays &7- &6" + odds.stake() + " in",
                lore.toArray(new String[0]));
    }

    /** "Win: 5 in → 9 back", "Twenty-One!: 5 in → 11 back", "Double win: 10 in → 18 back". */
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

    private static String article(Card c) {
        return c.rank() == 1 ? "an Ace" : c.rank() == 8 ? "an 8" : "a " + c.rankName();
    }

    // ---- clicks ------------------------------------------------------------------------------------

    private void deal() {
        if (playing()) {
            return;
        }
        TwentyOneSettings.Odds odds = twentyOne.settings().odds(stake);
        if (odds == null) {
            refresh();
            return;
        }
        ChanceRounds.Round opened = games().rounds().open(viewer, game, odds.stake(),
                TwentyOneHand.data(odds.terms(), ""));
        if (opened == null) {
            ChanceRounds.Round open = games().rounds().openRound(viewer.getUniqueId(), game.id());
            if (open != null) {
                TwentyOneHand.Saved saved = TwentyOneHand.read(open.data());
                round = open;
                hand = TwentyOneHand.replay(open.seed(), saved.terms(), saved.actions());
            }
            refresh();
            return;
        }
        Sounds.paid(viewer);
        gone = false;
        round = opened;
        hand = TwentyOneHand.deal(opened.seed(), odds.terms());
        if (hand.done()) {
            settle(TwentyOneHand.data(hand.terms(), ""));
        }
        refresh();
    }

    private void hit() {
        if (!playing()) {
            return;
        }
        TwentyOneHand next = copy();
        next.hit();
        String data = TwentyOneHand.data(next.terms(), next.actions());
        if (next.done()) {
            hand = next;
            settle(data);
        } else {
            ChanceRounds.Round stepped = games().rounds().step(round, data);
            if (stepped == null) {
                finishedElsewhere();
                return;
            }
            round = stepped;
            hand = next;
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
        refresh();
    }

    private void stand() {
        if (!playing()) {
            return;
        }
        TwentyOneHand next = copy();
        next.stand();
        hand = next;
        settle(TwentyOneHand.data(next.terms(), next.actions()));
        refresh();
    }

    private void doubleDown() {
        if (!playing() || !hand.canDouble()) {
            return;
        }
        int extra = hand.terms().in();
        TwentyOneHand next = copy();
        next.doubleDown();
        String data = TwentyOneHand.data(next.terms(), next.actions());
        ChanceRounds.Round withDouble = new ChanceRounds.Round(round.id(), round.gameId(), round.player(),
                round.seed(), round.stake(), data, round.state());
        if (!games().rounds().raise(viewer, withDouble, extra)) {
            refresh();
            return;
        }
        Sounds.paid(viewer);
        round = new ChanceRounds.Round(round.id(), round.gameId(), round.player(), round.seed(),
                round.stake() + extra, data, round.state());
        hand = next;
        settle(data);
        refresh();
    }

    /** The hand as it stands, to try the next action on without touching the one on the table. */
    private TwentyOneHand copy() {
        return TwentyOneHand.replay(round.seed(), hand.terms(), hand.actions());
    }

    /** Pay the finished hand once, then the sound that matches it (never a win's for a loss). */
    private void settle(String data) {
        if (!games().rounds().close(round, hand.payout(), data)) {
            finishedElsewhere();
            return;
        }
        round = null;
        int back = hand.payout();
        if (back > hand.putIn()) {
            Sounds.won(viewer);
        } else if (back == hand.putIn()) {
            Sounds.received(viewer);
        } else {
            Sounds.miss(viewer);
        }
    }

    /** The round was settled somewhere else (the ten-minute sweep): say so and clear the table. */
    private void finishedElsewhere() {
        round = null;
        hand = null;
        gone = true;
        viewer.sendMessage(Text.of("&7That Twenty-One hand was already finished for you."));
        refresh();
    }
}
