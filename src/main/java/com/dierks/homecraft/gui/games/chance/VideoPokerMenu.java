package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.poker.PokerDeal;
import com.dierks.homecraft.games.chance.poker.PokerHand;
import com.dierks.homecraft.games.chance.poker.VideoPoker;
import com.dierks.homecraft.games.chance.poker.VideoPokerSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.pack.CardFace;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.57.0: the Video Poker machine's screen (54 slots), made for a controller as much as a mouse: the five cards side
 * by side in one row, and Deal (then Draw) right under the middle one, so a thumb goes down from the cards to the one
 * button that moves the hand on.
 *
 * <pre>
 *  row 0   0 how to play · 2 what it gives back · 4 the hand: what to do, or how it came out · 6 today
 *  row 1   9-17 the pay table, best hand first: each row's prize at the stake chosen (the row the cards on the
 *          table make glows)
 *  row 2   20-24 the five cards (a tap holds one or lets it go: a held card glows and says HELD)
 *  row 3   31 Deal, then Draw
 *  row 5   45 the chips' total · 46-48 stakes or chips · 49 Back · 51 your balance · 52 how it pays · 53 tokens or money
 * </pre>
 *
 * <p>Every number here is the stake's own {@link VideoPokerSettings.Odds}: the prizes the round is opened with and
 * paid by, and the exact return of the best play. The screen only draws the hand: Deal opens the round (the stake in,
 * through the play gate), and Draw settles it once through {@link ChanceRounds} BEFORE the new cards are painted.
 * Closing the screen leaves a dealt hand open; opening the game again resumes it. After a hand, Deal is where it
 * always is: nothing asks for another go. Each move holds clicks for {@link ClickHold#ACTION_MS}, so the rest of a
 * double click on Deal never draws the hand before its cards were seen. Every slot is painted (filler first) and no
 * item can be taken: it is a {@link GameMenu}.
 */
public final class VideoPokerMenu extends GameMenu {

    private static final int RULES = 0;
    private static final int GIVE_BACK = 2;
    private static final int STATUS = 4;
    private static final int TODAY = 6;
    private static final int PAY_ROW = 9;
    static final int[] CARDS = {20, 21, 22, 23, 24};
    static final int DEAL = 31;
    private static final int CHIPS_BACK = 45;
    private static final int BALANCE = 51;
    private static final int HOW = 52;
    private static final int SWITCH = 53;
    /** Each pay table row's colour, best hand first (the Jacks or Better row white: your tokens back). */
    private static final Material[] ROW_COLOURS = {Material.RED_CONCRETE, Material.PURPLE_CONCRETE,
            Material.MAGENTA_CONCRETE, Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.LIME_CONCRETE,
            Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.WHITE_CONCRETE};

    private final VideoPoker poker;
    private final MoneyPlay money;
    /** The token stake picked. */
    private int stake;
    /** The hand's open round while it is being played, else {@code null}. */
    private ChanceRounds.Round round;
    /** The hand on the table (being played, or the last one drawn), or {@code null}. */
    private PokerDeal deal;
    /** The prizes of the hand on the table (it keeps its own, whatever is picked next). */
    private VideoPokerSettings.Odds dealOdds;
    /** Whether the hand on the table is (or was) for dollars. */
    private boolean dealMoney;
    private boolean gone;

    /**
     * @param resume the player's OPEN hand, or {@code null} for a fresh machine
     */
    public VideoPokerMenu(HomeCraftManagement plugin, VideoPoker game, Player viewer, Runnable back,
                          ChanceRounds.Round resume) {
        super(plugin, game, viewer, back);
        this.poker = game;
        this.money = new MoneyPlay(plugin, game.games(), viewer);
        List<Integer> open = game.settings().open();
        this.stake = open.isEmpty() ? 0 : open.get(0);
        if (resume != null) {
            PokerDeal d = PokerDeal.resume(resume.seed(), resume.data());
            PokerDeal.Saved saved = PokerDeal.read(resume.data());
            this.round = resume;
            this.deal = d;
            this.dealMoney = resume.money();
            this.dealOdds = new VideoPokerSettings.Odds(saved.stake(), toList(saved.prizes()),
                    new RtpLimits.Ratio(0, 1));
            money.follow(resume.money(), saved.stake());
            if (!resume.money()) {
                stake = saved.stake();
            }
        }
        init(54, Text.of("&2Video Poker"));
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int x : a) {
            out.add(x);
        }
        return out;
    }

    private GamesService games() {
        return poker.games();
    }

    /** A hand is dealt and not drawn yet. */
    private boolean playing() {
        return deal != null && !deal.drawn() && round != null;
    }

    /** What the next hand pays: the token stake picked, or the dollars the chips add up to. */
    private VideoPokerSettings.Odds odds() {
        return money.on() ? poker.moneyOdds(money.stake()) : poker.settings().odds(stake);
    }

    /** The odds the pay table shows: the hand on the table's while there is one, else the next hand's. */
    private VideoPokerSettings.Odds shown() {
        return deal != null && dealOdds != null ? dealOdds : odds();
    }

    /** Whether the pay table shows dollars. */
    private boolean shownMoney() {
        return deal != null ? dealMoney : money.on();
    }

    /** "5 tokens" or "$5", for the hand on the table (or the next one). */
    private String amount(int n) {
        return VideoPoker.amount(shownMoney(), n);
    }

    // ---- painting --------------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        VideoPokerSettings s = poker.settings();
        boolean dollars = money.on();
        VideoPokerSettings.Odds next = odds();
        set(RULES, rulesTile(game.rules()), null);
        set(GIVE_BACK, giveBack(next), null);
        set(STATUS, status(), null);
        set(TODAY, dollars ? money.todayTile(game, s.dailyLimit(), "hands")
                : CardGameTiles.today(games(), game, viewer, plugin.clock(), s.dailyLimit(), "hands"), null);
        paintPayTable();
        paintCards();
        paintDeal(next);
        if (dollars) {
            for (int i = 0; i < money.chips() && i < 3; i++) {
                int chip = money.chip(i);
                set(CardGameTiles.STAKE_SLOTS[i], money.chipTile(i), e -> {
                    if (!playing()) {
                        money.add(chip);
                        afterPick();
                    }
                });
            }
            set(CHIPS_BACK, money.stakeTile(), e -> {
                if (!playing()) {
                    money.clear();
                    afterPick();
                }
            });
        } else {
            List<Integer> open = s.open();
            for (int i = 0; i < open.size() && i < CardGameTiles.STAKE_SLOTS.length; i++) {
                int value = open.get(i);
                VideoPokerSettings.Odds o = s.odds(value);
                set(CardGameTiles.STAKE_SLOTS[i], CardGameTiles.stake(value, value == stake,
                        List.of("&7A Royal Flush pays &f" + o.prize(PokerHand.ROYAL_FLUSH) + " tokens",
                                "&7The best play " + RtpLimits.playerLine(o.rtp()) + ".")), e -> {
                    if (!playing() && stake != value) {
                        stake = value;
                        afterPick();
                    }
                });
            }
        }
        exitTile();
        set(BALANCE, dollars ? money.balanceTile() : balanceTile(), null);
        set(HOW, howItPays(shown()), null);
        if (money.offered() || dollars) {
            set(SWITCH, money.switchTile(), e -> {
                if (!playing()) {
                    money.flip();
                    afterPick();
                }
            });
        }
    }

    /** A new stake or currency picked between hands: the last hand goes, so the pay table shows the next one's. */
    private void afterPick() {
        if (deal != null && deal.drawn()) {
            deal = null;
            dealOdds = null;
        }
        gone = false;
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
        refresh();
    }

    private void paintPayTable() {
        VideoPokerSettings.Odds o = shown();
        VideoPokerSettings s = poker.settings();
        PokerHand made = deal == null ? null : deal.hand();
        for (int i = 0; i < VideoPokerSettings.ROWS.size(); i++) {
            PokerHand h = VideoPokerSettings.ROWS.get(i);
            if (o == null) {
                set(PAY_ROW + i, Menus.icon(ROW_COLOURS[i], "&e" + h.label(), "&7" + example(h)), null);
                continue;
            }
            int prize = o.prize(h);
            boolean lit = made == h;
            int multiple = s.multiple(h);
            List<String> lore = new ArrayList<>();
            lore.add("&7" + example(h));
            lore.add("&7Pays " + amount(prize) + " for " + amount(o.stake()) + " in.");
            if (o.capped(h, multiple)) {
                lore.add("&8Held to the biggest payout (" + multiple + " times would be more).");
            }
            if (lit) {
                lore.add(deal.drawn() ? "&aThe hand on the table." : "&aWhat your cards make now.");
            }
            String name = (lit ? "&a» " : "&e") + h.label() + " &7- &6" + amount(prize)
                    + (prize == o.stake() ? " &7(your " + amount(prize) + " back)" : "");
            set(PAY_ROW + i, Menus.glint(Menus.count(Menus.icon(ROW_COLOURS[i], name,
                    lore.toArray(new String[0])), Math.max(1, Math.min(64, multiple))), lit), null);
        }
    }

    /** What a hand looks like, in a few words. */
    static String example(PokerHand h) {
        return switch (h) {
            case ROYAL_FLUSH -> "10, Jack, Queen, King, Ace, all one suit.";
            case STRAIGHT_FLUSH -> "Five in a row, all one suit.";
            case FOUR_OF_A_KIND -> "Four cards of one rank.";
            case FULL_HOUSE -> "Three of one rank and two of another.";
            case FLUSH -> "Five cards of one suit.";
            case STRAIGHT -> "Five in a row (an Ace is high or low).";
            case THREE_OF_A_KIND -> "Three cards of one rank.";
            case TWO_PAIR -> "Two pairs.";
            case JACKS_OR_BETTER -> "A pair of Jacks, Queens, Kings or Aces.";
            case NOTHING -> "Anything less.";
        };
    }

    private void paintCards() {
        if (deal == null) {
            boolean art = CardGameTiles.art(plugin, viewer);
            for (int slot : CARDS) {
                set(slot, CardGameTiles.faceDown(art, "&8A card is dealt here", "&7Pick your stake, then Deal."), null);
            }
            return;
        }
        int[] cards = deal.cards();
        for (int i = 0; i < 5; i++) {
            int index = i;
            set(CARDS[i], cardTile(cards[i], i), playing() ? e -> toggle(index) : null);
        }
    }

    /**
     * A card: its short form and name ("&amp;cQ♥ &amp;7Queen of Hearts"), the stack its rank (Aces high, none on its
     * picture); held ones glow and say HELD (and are a filled map on a plain card).
     */
    private ItemStack cardTile(int card, int i) {
        boolean art = CardGameTiles.art(plugin, viewer);
        CardFace face = CardFace.aceHigh(PokerHand.rank(card) + 2, PokerHand.suit(card));
        int count = CardGameTiles.rankCount(art, face);
        if (!playing()) {
            return CardGameTiles.card(art, face, count);
        }
        boolean held = deal.held(i);
        String name = CardGameTiles.name(face);
        ItemStack icon = Menus.icon(held ? Material.FILLED_MAP : Material.PAPER,
                held ? "&a&lHELD &8· " + name : name,
                held ? "&aHeld: it stays when you Draw." : "&7It is replaced when you Draw.",
                held ? "&eTap to let it go" : "&eTap to hold it");
        return Menus.glint(CardGameTiles.drawn(Menus.count(icon, count), CardGameTiles.model(art, face)), held);
    }

    private void paintDeal(VideoPokerSettings.Odds next) {
        if (playing()) {
            int held = Integer.bitCount(deal.heldMask());
            String keep = held == 0 ? "all five are replaced" : held == 5 ? "you keep all five"
                    : "you keep " + held + ", " + (5 - held) + " new";
            set(DEAL, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&a&lDraw &7- " + keep,
                    "&7Once only: the cards not held are replaced.", "&eClick to draw"), true), e -> draw());
            return;
        }
        String why = blocker(next);
        if (why != null) {
            set(DEAL, CardGameTiles.unlit("Deal", why), e -> deal());
            return;
        }
        set(DEAL, Menus.glint(Menus.icon(Material.GREEN_CONCRETE, "&aDeal &7- &6" + VideoPoker.amount(money.on(),
                next.stake()) + " in", "&7Five cards from a fresh deck.", "&eClick to deal"), true), e -> deal());
    }

    /** Why Deal can't go now, for its tile, or {@code null}. The gate still decides on the click. */
    private String blocker(VideoPokerSettings.Odds next) {
        if (next == null) {
            return money.on() ? (money.stake() <= 0 ? "add a chip first" : "not at this stake") : "closed right now";
        }
        int left = handsLeft();
        if (left == 0) {
            return "no hands left today";
        }
        if (money.on()) {
            Refusal r = games().parlor().check(viewer, next.stake(), true);
            if (r == null) {
                return null;
            }
            return switch (r.reason()) {
                case PERSONAL_LIMIT -> "over your limit today";
                case BALANCE -> "not enough money";
                default -> "closed right now";
            };
        }
        Breaks b = games().breaks();
        int in = b == null ? 0 : b.tokensInToday(viewer.getUniqueId());
        int limit = b == null ? Breaks.NO_LIMIT : b.limit(viewer.getUniqueId());
        if (limit >= 0 && in + next.stake() > limit) {
            return "over your limit today";
        }
        int balance = plugin.tokens() == null ? 0 : plugin.tokens().balance(viewer.getUniqueId());
        if (balance < next.stake()) {
            return "need " + (next.stake() - balance) + " more tokens";
        }
        return null;
    }

    /** Hands left today, or -1 if they can't be counted now. */
    private int handsLeft() {
        try {
            int played = games().dao().playsToday(viewer.getUniqueId(), game.id(), plugin.clock().dayKey());
            return Math.max(0, poker.settings().dailyLimit() - played);
        } catch (SQLException | RuntimeException e) {
            return -1;
        }
    }

    private ItemStack status() {
        if (gone) {
            return Menus.icon(Material.OAK_SIGN, "&7That hand was already finished for you.");
        }
        if (deal == null) {
            return Menus.icon(Material.OAK_SIGN, money.on() ? "&eAdd your chips, then Deal"
                    : "&ePick your tokens, then Deal", "&7Then tap the cards to hold, and Draw once.");
        }
        PokerHand h = deal.hand();
        if (!deal.drawn()) {
            return Menus.icon(Material.OAK_SIGN, "&eTap the cards to hold, then Draw",
                    h == PokerHand.NOTHING ? "&7Your cards make nothing yet." : "&7Your cards make " + h.label()
                            + " now.", "&7Held cards glow and say HELD.");
        }
        int in = deal.stake();
        int back = deal.payout();
        String cards = "&7" + PokerDeal.line(deal.cards());
        if (back > in) {
            return Menus.glint(Menus.icon(Material.EMERALD, "&a" + h.label() + "! &6" + amount(in) + " in → "
                    + amount(back) + " back", cards), true);
        }
        if (back == in) {
            return Menus.icon(Material.GOLD_NUGGET, "&f" + h.label() + ": &7your " + amount(back) + " back", cards);
        }
        return Menus.icon(Material.GRAY_DYE, "&7" + (h == PokerHand.NOTHING ? "" : h.label() + ". ")
                + "No win this time.", cards);
    }

    private ItemStack giveBack(VideoPokerSettings.Odds o) {
        if (o == null) {
            return Menus.icon(Material.KNOWLEDGE_BOOK, money.on() ? "&7Add chips to see what it pays"
                    : "&7Pick how much to put in");
        }
        boolean dollars = money.on();
        return Menus.icon(Material.KNOWLEDGE_BOOK, "&7The best play gives back about &f"
                        + RtpLimits.wholePercent(o.rtp()) + " &7of every " + (dollars ? "$100" : "100 tokens"),
                "&7(at " + VideoPoker.amount(dollars, o.stake()) + " in)",
                "&7Counted exactly over every deal, hold and draw.",
                "&7Holding any other way gives back less.");
    }

    private ItemStack howItPays(VideoPokerSettings.Odds o) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Five cards from a fresh 52-card deck.");
        lore.add("&7Tap the ones to keep; Draw replaces the rest, once.");
        lore.add("&7The hand is paid by the table:");
        if (o != null) {
            for (PokerHand h : VideoPokerSettings.ROWS) {
                lore.add("&7  " + h.label() + ": &f" + amount(o.prize(h)));
            }
        }
        lore.add("&7A pair of 10s or lower pays nothing.");
        lore.add("&7An Ace is high or low in a straight.");
        return Menus.icon(Material.WRITABLE_BOOK, "&eHow it pays" + (o == null ? "" : " &7- &6" + amount(o.stake())
                + " in"), lore.toArray(new String[0]));
    }

    // ---- clicks ----------------------------------------------------------------------------------------------

    private void deal() {
        hold(ClickHold.ACTION_MS);
        if (playing()) {
            return;
        }
        boolean dollars = money.on();
        VideoPokerSettings.Odds odds = odds();
        if (odds == null) {
            refresh();
            return;
        }
        ChanceRounds.Round opened = games().rounds().open(viewer, game, odds.stake(),
                PokerDeal.openData(odds.stake(), odds.prizeArray()), dollars);
        if (opened == null) {
            ChanceRounds.Round open = games().rounds().openRound(viewer.getUniqueId(), game.id());
            if (open != null) { // a hand of theirs is already open: it is the one on the table
                PokerDeal.Saved saved = PokerDeal.read(open.data());
                round = open;
                deal = PokerDeal.resume(open.seed(), open.data());
                dealMoney = open.money();
                dealOdds = new VideoPokerSettings.Odds(saved.stake(), toList(saved.prizes()),
                        new RtpLimits.Ratio(0, 1));
                gone = false;
            }
            refresh();
            return;
        }
        Sounds.paid(viewer);
        gone = false;
        round = opened;
        dealMoney = dollars;
        dealOdds = odds;
        deal = PokerDeal.deal(opened.seed(), odds.stake(), odds.prizeArray());
        refresh();
    }

    private void toggle(int i) {
        if (!playing()) {
            return;
        }
        deal.toggle(i);
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, deal.held(i) ? 1.4f : 0.9f);
        refresh();
    }

    private void draw() {
        hold(ClickHold.ACTION_MS);
        if (!playing()) {
            return;
        }
        PokerDeal next = PokerDeal.resume(round.seed(), round.data());
        for (int i = 0; i < 5; i++) {
            if (deal.held(i)) {
                next.toggle(i);
            }
        }
        next.draw();
        // paid once, in one transaction, before the new cards are painted
        if (!games().rounds().close(round, next.payout(), next.data())) {
            finishedElsewhere();
            return;
        }
        round = null;
        deal = next;
        int back = next.payout();
        int in = next.stake();
        PokerHand h = next.hand();
        if (back > in) {
            Sounds.won(viewer);
            viewer.sendMessage(Text.of("&aVideo Poker: " + h.label() + "! &6" + amount(in) + " in → " + amount(back)
                    + " back&a."));
        } else if (back == in) {
            Sounds.received(viewer);
            viewer.sendMessage(Text.of("&fVideo Poker: " + h.label() + ", your " + amount(back) + " back."));
        } else {
            Sounds.miss(viewer);
        }
        refresh();
    }

    /** The hand was settled somewhere else (the ten-minute sweep): say so and clear the table. */
    private void finishedElsewhere() {
        round = null;
        deal = null;
        dealOdds = null;
        gone = true;
        viewer.sendMessage(Text.of("&7That Video Poker hand was already finished for you."));
        refresh();
    }
}
