package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.bingo.Bingo;
import com.dierks.homecraft.games.chance.bingo.Bingo.Entry;
import com.dierks.homecraft.games.chance.bingo.Bingo.Phase;
import com.dierks.homecraft.games.chance.bingo.BingoCard;
import com.dierks.homecraft.games.chance.bingo.BingoSettings;
import com.dierks.homecraft.games.chance.bingo.BingoTicket;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Bingo Night's screen (0.57.0, 54 slots): buy your cards, then watch them daub themselves as the numbers are called.
 * The screen is the show: it stays open through the calling and goes back to buying when the round is over.
 *
 * <p>Before you have cards:
 * <pre>
 *  row 0   4 the round: none yet, or how long until the calling starts and how many cards are in
 *  row 1   10 12 14 16 one, two, three or four cards (a click picks it)
 *  row 3   28 how to play · 30 the prize · 32 how the prize works · 34 today
 *  row 5   49 Close · 50 Buy · 51 your balance · 53 tokens or money
 * </pre>
 * With cards (or watching a round being called):
 * <pre>
 *  rows 0-4, columns 0-4   the card you're looking at, B I N G O: white not called yet, green daubed, gold the
 *                          squares that made it; the star in the middle is free
 *  6 the last number called (its count is the number) · 7 the calls so far · 8 the prize
 *  15 who is in the round · 16 how to play · 17 how the prize works
 *  24 25 33 34 your cards one to four (a click shows it): how many squares each is from a line
 *  42 what your cards made · 49 Close · 50 Start now (while cards are sold) · 51 your balance
 * </pre>
 *
 * Nothing here moves tokens or money: Buy asks the round, which puts the price in through the play gate.
 */
public final class BingoMenu extends GameMenu {

    private static final int STATE = 4;
    private static final int[] COUNT = {10, 12, 14, 16};
    private static final int RULES = 28;
    private static final int PRIZE_A = 30;
    private static final int HOW_A = 32;
    private static final int TODAY = 34;
    private static final int BUY = 50;
    private static final int BALANCE = 51;
    private static final int SWITCH = 53;

    private static final int LAST = 6;
    private static final int CALLS = 7;
    private static final int PRIZE = 8;
    private static final int WHO = 15;
    private static final int RULES_B = 16;
    private static final int HOW = 17;
    private static final int[] TABS = {24, 25, 33, 34};
    private static final int MINE = 42;
    private static final int START = 50;

    private final Bingo bingo;
    private final MoneyPlay money;
    /** How many cards to buy. */
    private int count = 1;
    /** Which of your cards is shown. */
    private int view;
    private boolean watching;

    public BingoMenu(HomeCraftManagement plugin, Bingo game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.bingo = game;
        this.money = new MoneyPlay(plugin, game.games(), viewer);
        init(54, Text.of("&2Bingo Night"));
    }

    @Override
    public void open(Player player) {
        Entry me = me();
        if (me != null) {
            money.follow(me.money(), me.in());
        }
        super.open(player);
        if (showing()) {
            watching = true;
            bingo.watch(viewer.getUniqueId(), this::roundChanged);
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (watching) {
            watching = false;
            bingo.unwatch(viewer.getUniqueId());
        }
    }

    private void roundChanged() {
        if (showing()) {
            refresh();
        }
    }

    private Entry me() {
        return bingo.entry(viewer.getUniqueId());
    }

    // ---- what a tile says (pure) ------------------------------------------------------------------------------

    /** A stack showing {@code n} (bingo's numbers go to 75: past a stack of 64, the stack is made to hold 99). */
    static ItemStack numbered(ItemStack item, int n) {
        int amount = Math.max(1, Math.min(99, n));
        if (amount > 64) {
            try {
                ItemMeta meta = item.getItemMeta();
                meta.setMaxStackSize(99);
                item.setItemMeta(meta);
            } catch (RuntimeException | LinkageError e) {
                amount = 64; // the name still says the number
            }
        }
        item.setAmount(amount);
        return item;
    }

    /** The prize's lines (pure): the two parts, the cards in play, how it is shared. */
    static List<String> prizeLines(int line, int corners, boolean playsCorners, int cards, int least, boolean dollars) {
        List<String> out = new ArrayList<>();
        out.add("&7A line: &6" + Bingo.amount(dollars, line));
        if (playsCorners) {
            out.add("&7Then four corners: &6" + Bingo.amount(dollars, corners));
        }
        out.add("&7" + cards + " cards in play &8(always at least " + least + ")");
        out.add("&7Each part is shared by every card that");
        out.add("&7makes it on the same call.");
        return out;
    }

    /** How the prize works (pure); {@code low} is the lowest return of a card, or {@code NaN}. */
    static List<String> howLines(double low) {
        List<String> out = new ArrayList<>();
        out.add("&7The prize is sized by the cards in play:");
        out.add("&7more cards, a bigger prize, shared by more.");
        out.add("&7Every card is dealt the same way and has");
        out.add("&7the same chance, whoever holds it, so each");
        out.add("&7card gives back the same over lots of rounds"
                + (Double.isNaN(low) ? "." : ": about " + RtpLimits.wholePercent(low) + " of every 100."));
        out.add("&7NPC players' cards and the room's own count");
        out.add("&7toward the prize like yours: what they win");
        out.add("&7is play chips, and never yours.");
        out.add("&7Who makes what is decided when the");
        out.add("&7calling starts; your cards daub themselves.");
        return out;
    }

    // ---- painting -----------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        Entry me = me();
        boolean live = bingo.phase() == Phase.CALLING || bingo.phase() == Phase.DONE;
        if (me == null && !live) {
            paintBuy();
        } else {
            paintRound(me);
        }
        exitTile();
    }

    // ---- buying ---------------------------------------------------------------------------------------------

    private void paintBuy() {
        BingoSettings s = bingo.settings();
        boolean dollars = money.on();
        int price = s.price(dollars);
        paintState();
        for (int k = 1; k <= BingoTicket.MAX_CARDS; k++) {
            int n = k;
            boolean chosen = count == k;
            ItemStack icon = Menus.count(Menus.glint(Menus.icon(chosen ? Material.FILLED_MAP : Material.MAP,
                    (chosen ? "&6&l" : "&f") + k + " card" + (k == 1 ? "" : "s") + " &7- &6"
                            + Bingo.amount(dollars, price * k),
                    "&7" + Bingo.amount(dollars, price) + " a card.",
                    chosen ? "&aChosen" : "&eClick to choose"), chosen), k);
            set(COUNT[k - 1], icon, e -> {
                count = n;
                refresh();
            });
        }
        set(RULES, rulesTile(game.rules()), null);
        paintPrize(PRIZE_A, dollars, Math.max(s.roomCards(), bingo.cardsNow() + count), false);
        set(HOW_A, Menus.icon(Material.PAPER, "&eHow the prize works",
                howLines(bingo.lowestRtp()).toArray(new String[0])), null);
        set(TODAY, dollars ? money.todayTile(game, s.dailyLimit(), "rounds")
                : CardGameTiles.today(bingo.games(), game, viewer, plugin.clock(), s.dailyLimit(), "rounds"), null);
        set(BALANCE, dollars ? money.balanceTile() : balanceTile(), null);
        paintBuyButton(dollars, price * count);
        if (money.offered() || dollars) {
            set(SWITCH, money.switchTile(), e -> {
                money.flip();
                refresh();
            });
        }
    }

    private void paintState() {
        int cards = bingo.cardsNow();
        if (bingo.phase() == Phase.GATHERING) {
            int left = Math.max(0, bingo.secondsLeft());
            set(STATE, Menus.count(Menus.glint(Menus.icon(Material.CLOCK, "&eThe calling starts in " + left + " s",
                    "&7" + cards + (cards == 1 ? " card is" : " cards are") + " in so far.",
                    "&7Buy yours to play in this round."), true), left), null);
        } else {
            set(STATE, Menus.icon(Material.CLOCK, "&aNo round yet",
                    "&7The first cards bought start a short wait", "&7for everyone else to buy theirs."), null);
        }
    }

    private void paintPrize(int slot, boolean dollars, int cards, boolean now) {
        BingoSettings s = bingo.settings();
        int[] parts = now ? bingo.partsNow(dollars) : bingo.parts(dollars, cards);
        boolean corners = now ? bingo.playsCorners() : s.cornersShare() > 0;
        int whole = parts[0] + parts[1];
        set(slot, Menus.icon(Material.GOLD_INGOT, "&6The prize: " + Bingo.amount(dollars, whole),
                prizeLines(parts[0], parts[1], corners, cards, s.roomCards(), dollars).toArray(new String[0])), null);
    }

    /** Why a token buy of {@code stake} can't go now (the day's limit, the balance), or {@code null}. */
    private String tokenBlocker(int stake) {
        java.util.UUID id = viewer.getUniqueId();
        com.dierks.homecraft.games.Breaks b = plugin.games() == null ? null : plugin.games().breaks();
        int in = b == null ? 0 : b.tokensInToday(id);
        int limit = b == null ? com.dierks.homecraft.games.Breaks.NO_LIMIT : b.limit(id);
        int balance = plugin.tokens() == null ? 0 : plugin.tokens().balance(id);
        if (limit >= 0 && in + stake > limit) {
            return "over your limit today";
        }
        return balance < stake ? "need " + (stake - balance) + " more tokens" : null;
    }

    /** Why a money buy of {@code dollars} can't go now, or {@code null}. */
    private String moneyBlocker(int dollars) {
        com.dierks.homecraft.games.Refusal r = plugin.games() == null ? null
                : plugin.games().parlor().check(viewer, dollars, true);
        if (r == null) {
            return null;
        }
        return switch (r.reason()) {
            case PERSONAL_LIMIT -> "over your limit today";
            case BALANCE -> "not enough money";
            default -> "closed right now";
        };
    }

    private void paintBuyButton(boolean dollars, int cost) {
        String what = count + " card" + (count == 1 ? "" : "s");
        if (bingo.roundsLeft(viewer) == 0) {
            set(BUY, CardGameTiles.unlit("Buy", "no rounds left today"), null);
            return;
        }
        if (bingo.cardsNow() - npcAndRoomCards() + count > bingo.mostCards(dollars)) {
            set(BUY, CardGameTiles.unlit("Buy", "this round is full"), null);
            return;
        }
        String why = dollars ? moneyBlocker(cost) : tokenBlocker(cost);
        if (why != null) {
            set(BUY, CardGameTiles.unlit("Buy", why), null);
            return;
        }
        int n = count;
        set(BUY, Menus.icon(Material.GREEN_CONCRETE, "&aBuy " + what + " &7- &6" + Bingo.amount(dollars, cost),
                "&7Your " + (dollars ? "money goes" : "tokens go") + " in now.",
                "&7Your cards daub themselves as the numbers are called.",
                "&eClick to buy"), e -> {
            hold(ClickHold.ACTION_MS);
            if (bingo.buy(viewer, n, dollars)) {
                view = 0;
            }
            if (showing()) {
                refresh();
            }
        });
    }

    private int npcAndRoomCards() {
        int n = 0;
        for (Entry e : bingo.entries()) {
            n += e.holder() == Bingo.Holder.PLAYER ? 0 : e.cards().size();
        }
        return n;
    }

    // ---- the round --------------------------------------------------------------------------------------------

    private void paintRound(Entry me) {
        boolean dollars = me != null && me.money();
        List<BingoCard> cards = me == null ? List.of() : me.cards();
        view = Math.max(0, Math.min(view, cards.size() - 1));
        int[] rank = bingo.rank();
        int called = bingo.called();
        if (cards.isEmpty()) {
            paintWatching();
        } else {
            paintCard(me, view, cards.get(view), rank, called);
        }
        paintCalls();
        boolean live = bingo.phase() == Phase.CALLING || bingo.phase() == Phase.DONE;
        paintPrize(PRIZE, dollars, bingo.cardsInPlay(), live);
        paintWho();
        set(RULES_B, rulesTile(game.rules()), null);
        set(HOW, Menus.icon(Material.PAPER, "&eHow the prize works",
                howLines(bingo.lowestRtp()).toArray(new String[0])), null);
        for (int k = 0; k < cards.size() && k < TABS.length; k++) {
            paintTab(me, k, cards.get(k), rank, called);
        }
        if (me != null) {
            paintMine(me);
        }
        set(BALANCE, dollars ? money.balanceTile() : balanceTile(), null);
        if (me != null && bingo.phase() == Phase.GATHERING) {
            paintStartNow(me);
        }
    }

    /** Someone with no cards, while a round is called: what is going on, and when the next one can be bought. */
    private void paintWatching() {
        ItemStack glass = Menus.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7A round is being called",
                "&7You can buy cards for the next one", "&7when it is over.");
        for (int row = 0; row < BingoCard.SIZE; row++) {
            for (int col = 0; col < BingoCard.SIZE; col++) {
                set(row * 9 + col, glass, null);
            }
        }
    }

    private void paintCard(Entry me, int k, BingoCard card, int[] rank, int called) {
        int place = me.place(k);
        boolean line = place >= 0 && bingo.madeLine(place);
        boolean corners = place >= 0 && bingo.madeCorners(place);
        java.util.Set<Integer> gold = new java.util.HashSet<>();
        if (line && rank != null) {
            for (int s : card.line(rank)) {
                gold.add(s);
            }
        }
        if (corners) {
            for (int s : BingoCard.CORNERS) {
                gold.add(s);
            }
        }
        for (int sq = 0; sq < BingoCard.SQUARES; sq++) {
            int slot = (sq / BingoCard.SIZE) * 9 + sq % BingoCard.SIZE;
            int n = card.number(sq);
            String head = "&8Card " + (k + 1) + " · " + BingoCard.LETTERS.charAt(sq % BingoCard.SIZE);
            if (sq == BingoCard.FREE) {
                set(slot, Menus.glint(Menus.icon(Material.NETHER_STAR, "&eFree", head, "&7Daubed from the start."),
                        true), null);
                continue;
            }
            boolean daubed = rank != null && card.daubed(sq, rank, called);
            ItemStack icon;
            if (gold.contains(sq)) {
                icon = Menus.glint(Menus.icon(Material.GOLD_BLOCK, "&6&l" + BingoCard.ball(n) + " &6BINGO!", head),
                        true);
            } else if (daubed) {
                icon = Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&a" + BingoCard.ball(n) + " &a✔", head,
                        "&7Called and daubed."), true);
            } else {
                icon = Menus.icon(Material.WHITE_CONCRETE, "&f" + BingoCard.ball(n), head, "&7Not called yet.");
            }
            set(slot, numbered(icon, n), null);
        }
    }

    private void paintCalls() {
        int[] so = bingo.callsSoFar();
        if (so.length == 0) {
            set(LAST, Menus.icon(Material.SNOWBALL, "&7No number called yet",
                    bingo.phase() == Phase.GATHERING ? "&7The calling starts in " + Math.max(0, bingo.secondsLeft())
                            + " s." : "&7Waiting for the round."), null);
            set(CALLS, Menus.icon(Material.BOOK, "&eThe calls", "&7None yet."), null);
            return;
        }
        int last = so[so.length - 1];
        set(LAST, numbered(Menus.glint(Menus.icon(Material.SNOWBALL, "&e&l" + BingoCard.ball(last),
                "&7The last number called."), true), last), null);
        List<String> lore = new ArrayList<>();
        StringBuilder row = new StringBuilder("&f");
        int shown = 0;
        for (int i = so.length - 1; i >= 0 && shown < 15; i--, shown++) {
            row.append(shown % 5 == 0 ? "" : "&7, &f").append(BingoCard.ball(so[i]));
            if (shown % 5 == 4) {
                lore.add(row.toString());
                row = new StringBuilder("&f");
            }
        }
        if (shown % 5 != 0) {
            lore.add(row.toString());
        }
        lore.add("&7Newest first.");
        set(CALLS, Menus.icon(Material.BOOK, "&eCall " + so.length + " of " + BingoCard.BALLS,
                lore.toArray(new String[0])), null);
    }

    private void paintWho() {
        List<String> lore = new ArrayList<>();
        for (Entry e : bingo.entries()) {
            int n = e.cards().size();
            String name = switch (e.holder()) {
                case PLAYER -> "&f" + (e.id().equals(viewer.getUniqueId()) ? "You" : e.name());
                case NPC -> e.name();
                default -> "&7The room's own";
            };
            lore.add(name + " &7- " + n + " card" + (n == 1 ? "" : "s") + won(e));
        }
        if (lore.isEmpty()) {
            lore.add("&7Nobody yet.");
        }
        set(WHO, Menus.count(Menus.icon(Material.PLAYER_HEAD, "&fIn this round: " + bingo.cardsInPlay() + " cards",
                lore.toArray(new String[0])), Math.max(1, bingo.entries().size())), null);
    }

    /** " ✔ a line" and so on, once called out. */
    private String won(Entry e) {
        boolean line = false;
        boolean corners = false;
        for (int k = 0; k < e.cards().size(); k++) {
            line |= bingo.madeLine(e.place(k));
            corners |= bingo.madeCorners(e.place(k));
        }
        if (!line && !corners) {
            return "";
        }
        return " &6✔ " + (line && corners ? "a line and four corners" : line ? "a line" : "four corners");
    }

    private void paintTab(Entry me, int k, BingoCard card, int[] rank, int called) {
        int place = me.place(k);
        boolean made = place >= 0 && (bingo.madeLine(place) || bingo.madeCorners(place));
        List<String> lore = new ArrayList<>();
        if (made) {
            lore.add("&6It made " + (bingo.madeLine(place) && bingo.madeCorners(place) ? "a line and four corners!"
                    : bingo.madeLine(place) ? "a line!" : "four corners!"));
        } else if (rank != null && !bingo.lineCalled()) {
            int left = card.toGo(rank, called);
            lore.add("&7" + left + " to go for a line.");
        }
        lore.add(k == view ? "&aShown" : "&eClick to see it");
        ItemStack icon = Menus.count(Menus.glint(Menus.icon(made ? Material.GOLD_BLOCK : Material.FILLED_MAP,
                (k == view ? "&6&l" : "&e") + "Card " + (k + 1), lore.toArray(new String[0])), k == view || made), k + 1);
        set(TABS[k], icon, e -> {
            view = k;
            refresh();
        });
    }

    private void paintMine(Entry me) {
        int in = me.in();
        boolean dollars = me.money();
        if (bingo.phase() == Phase.DONE) {
            set(MINE, me.pays() > 0
                    ? Menus.glint(Menus.icon(Material.EMERALD, "&6Your cards made " + Bingo.amount(dollars, me.pays()),
                    "&7" + Bingo.amount(dollars, in) + " in → " + Bingo.amount(dollars, me.pays()) + " back."), true)
                    : Menus.icon(Material.PAPER, "&7No win this time", "&7" + Bingo.amount(dollars, in) + " in."), null);
            return;
        }
        int sofar = 0;
        for (int k = 0; k < me.cards().size(); k++) {
            int p = me.place(k);
            sofar += (bingo.madeLine(p) ? bingo.lineShare(p) : 0) + (bingo.madeCorners(p) ? bingo.cornersShare(p) : 0);
        }
        set(MINE, Menus.icon(Material.MAP, "&eYour cards: " + me.cards().size(),
                "&7" + Bingo.amount(dollars, in) + " in.",
                sofar > 0 ? "&6Made so far: " + Bingo.amount(dollars, sofar) : "&7Nothing made yet."), null);
    }

    private void paintStartNow(Entry me) {
        int waiting = 0;
        for (Entry e : bingo.entries()) {
            if (!e.startNow()) {
                waiting++;
            }
        }
        int left = Math.max(0, bingo.secondsLeft());
        if (me.startNow()) {
            set(START, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aYou asked to start now",
                    "&7Waiting for " + waiting + " more to ask,", "&7or " + left + " s."), true), null);
            return;
        }
        set(START, Menus.icon(Material.LIME_CONCRETE, "&aStart now",
                "&7The calling starts when everyone in", "&7the round asks, or in " + left + " s.",
                "&eClick to ask"), e -> {
            hold(ClickHold.ACTION_MS);
            bingo.startNow(viewer);
        });
    }
}
