package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.hilo.HigherLower;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Card;
import com.dierks.homecraft.games.chance.hilo.HigherLowerRun.Side;
import com.dierks.homecraft.games.chance.hilo.HigherLowerSettings;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The Higher or Lower table (spec §5.6): the card on show in the middle with Lower and Higher on
 * either side, Cash out underneath, the run's cards along the top, and the stakes, Start and "How
 * it pays" along the bottom.
 *
 * <p>Each button that can be used names what it does in tokens: Higher reads "right: 15 tokens",
 * Cash out reads "15 tokens". A side that could not grow the pot is never offered (it stays grey,
 * saying why), so a right guess is always worth more than what you had. The screen only draws
 * the engine's state: a guess is written to the round BEFORE its card is painted, and the end
 * settles once through {@link ChanceRounds}. Closing the screen leaves the run open; opening the
 * game again resumes it. After a run, Start is where it always is: no prompt to go again.
 *
 * <p>Each move (Start, a guess, Cash out) holds clicks for {@link ClickHold#ACTION_MS}: the table
 * repaints in place, so the rest of a double click on Higher would otherwise guess on the next
 * card before the player has seen it.
 */
public final class HigherLowerMenu extends GameMenu {

    /** The card spot before a run starts: one item, whatever comes next. */
    private static final ItemStack NEXT_CARD = Menus.icon(Material.MAP, "&8The first card shows when you start");

    private static final int RUN_ROW = 0;
    private static final int RULES = 9;
    private static final int GIVE_BACK = 11;
    private static final int STATUS = 13;
    private static final int TODAY = 15;
    private static final int LOWER = 20;
    private static final int SHOWN = 22;
    private static final int HIGHER = 24;
    private static final int CASH_OUT = 31;
    private static final int START = 50;
    private static final int BALANCE = 51;
    private static final int HOW_IT_PAYS = 52;

    private final HigherLower higherLower;
    private int stake;
    private ChanceRounds.Round round;
    /** The run on the table (being played, or the last one finished), or {@code null}. */
    private HigherLowerRun run;
    private boolean gone;

    /**
     * @param resume the player's OPEN round in this game, or {@code null} for a fresh table
     */
    public HigherLowerMenu(HomeCraftManagement plugin, HigherLower game, Player viewer, Runnable back,
                           ChanceRounds.Round resume) {
        super(plugin, game, viewer, back);
        this.higherLower = game;
        List<Integer> playable = game.settings().playable();
        this.stake = playable.isEmpty() ? 0 : playable.get(0);
        if (resume != null) {
            HigherLowerRun.Saved saved = HigherLowerRun.read(resume.data());
            this.round = resume;
            this.run = HigherLowerRun.replay(resume.seed(), saved.terms(), saved.actions());
            this.stake = saved.terms().in();
        }
        init(54, Text.of("&3Higher or Lower"));
    }

    /** A resumed run that was already over (a crash between its card and its payout) settles now. */
    @Override
    public void open(Player player) {
        if (round != null && run != null && run.done()) {
            settle(HigherLowerRun.data(run.terms(), run.actions()));
        }
        super.open(player);
    }

    private GamesService games() {
        return higherLower.games();
    }

    private boolean playing() {
        return run != null && !run.done() && round != null;
    }

    // ---- painting --------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        HigherLowerSettings s = higherLower.settings();
        HigherLowerSettings.Odds odds = s.odds(stake);
        paintRun();
        set(RULES, rulesTile(game.rules()), null);
        set(GIVE_BACK, giveBack(odds), null);
        set(STATUS, status(), null);
        set(TODAY, CardGameTiles.today(games(), game, viewer, plugin.clock(), s.dailyLimit(), "runs"), null);
        set(SHOWN, shownCard(), null);
        paintSide(LOWER, Side.LOWER);
        paintSide(HIGHER, Side.HIGHER);
        paintCashOut();
        List<Integer> playable = s.playable();
        for (int i = 0; i < playable.size() && i < CardGameTiles.STAKE_SLOTS.length; i++) {
            int value = playable.get(i);
            HigherLowerSettings.Odds o = s.odds(value);
            set(CardGameTiles.STAKE_SLOTS[i], CardGameTiles.stake(value, value == stake,
                    List.of("&7Cashes out by itself at &f" + o.terms().cap() + " tokens",
                            "&7The best play " + RtpLimits.playerLine(o.rtp()) + ".")), e -> {
                if (!playing()) {
                    stake = value;
                    refresh();
                }
            });
        }
        exitTile();
        set(START, startButton(odds), e -> start());
        set(BALANCE, balanceTile(), null);
        set(HOW_IT_PAYS, howItPays(odds), null);
    }

    /** The run's cards along the top: at most 8, or "N cards" and the last 7. */
    private void paintRun() {
        if (run == null) {
            set(RUN_ROW, Menus.icon(Material.NAME_TAG, "&7This run"), null);
            return;
        }
        List<Card> cards = run.cards();
        set(RUN_ROW, Menus.icon(Material.NAME_TAG, "&7This run &8· &f" + run.guesses() + " right"
                + (run.guesses() == 1 ? " guess" : " guesses")), null);
        int from = 0;
        int slot = RUN_ROW + 1;
        if (cards.size() > 8) {
            from = cards.size() - 7;
            set(slot++, Menus.icon(Material.BOOK, "&f" + cards.size() + " cards"), null);
        }
        for (int i = from; i < cards.size(); i++) {
            set(slot++, cardTile(cards.get(i)), null);
        }
    }

    private static ItemStack cardTile(Card c) {
        return CardGameTiles.card(c.name(), c.red(), c.rank(), "&7Aces are high.");
    }

    private ItemStack shownCard() {
        return run == null ? NEXT_CARD : cardTile(run.shown());
    }

    private void paintSide(int slot, Side side) {
        String arrow = side == Side.HIGHER ? "▲ Higher" : "▼ Lower";
        if (!playing()) {
            set(slot, CardGameTiles.unlit(arrow, null), null);
            return;
        }
        int rank = run.shown().rank();
        int k = HigherLowerRun.winners(rank, side);
        if (!run.offered(side)) {
            String why = k == 0 ? (side == Side.HIGHER ? "nothing is higher than " : "nothing is lower than ")
                    + run.shown().withArticle() : "can't grow your pot";
            set(slot, CardGameTiles.unlit(arrow, why), null);
            return;
        }
        long ifRight = run.potIfRight(side);
        set(slot, Menus.icon(Material.LIME_CONCRETE, "&a" + arrow + " &7- right: &6" + ifRight + " tokens",
                "&7" + k + " of 13 cards are " + (side == Side.HIGHER ? "higher" : "lower") + ".",
                "&7The same card, or the other way, and the run ends with nothing."), e -> guess(side));
    }

    private void paintCashOut() {
        if (!playing()) {
            set(CASH_OUT, CardGameTiles.unlit("Cash out", null), null);
        } else if (!run.canCashOut()) {
            set(CASH_OUT, CardGameTiles.unlit("Cash out", "after a right guess"), null);
        } else {
            set(CASH_OUT, Menus.glint(Menus.icon(Material.EMERALD, "&aCash out &7- &6" + run.pot() + " tokens",
                    "&7You put in " + run.terms().in() + ".", "&7Stop here and take your pot."), true), e -> cashOut());
        }
    }

    private ItemStack startButton(HigherLowerSettings.Odds odds) {
        if (playing()) {
            return CardGameTiles.unlit("Start", "finish this run first");
        }
        if (odds == null) {
            return CardGameTiles.unlit("Start", "closed right now");
        }
        return Menus.icon(Material.GREEN_CONCRETE, "&aStart &7- &6" + odds.stake() + " tokens in",
                "&7A card is shown; guess if the next is higher or lower.");
    }

    private ItemStack status() {
        if (gone) {
            return Menus.icon(Material.OAK_SIGN, "&7That run was already finished for you.");
        }
        if (run == null) {
            return Menus.icon(Material.OAK_SIGN, "&ePick your tokens, then Start",
                    "&7Guess if the next card is higher or lower.");
        }
        if (!run.done()) {
            if (run.guesses() == 0) {
                return Menus.icon(Material.OAK_SIGN, "&eHigher or lower than " + run.shown().withArticle() + "?",
                        "&7Your pot: " + run.pot() + " tokens.",
                        "&7Every extra guess risks what you have.");
            }
            return Menus.icon(Material.OAK_SIGN, "&eYour pot: &6" + run.pot() + " tokens &7- guess or cash out",
                    "&7Right guesses: " + run.guesses() + " of " + run.terms().maxGuesses() + ".",
                    "&7Every extra guess risks what you have.");
        }
        int in = run.terms().in();
        int back = run.payout();
        String headline = switch (run.end()) {
            case WRONG -> "It was " + run.shown().withArticle() + ".";
            case CASHED -> "Cashed out!";
            case TOP -> "Top pot reached!";
            case LAST_GUESS -> "That was the last guess.";
            case NO_SIDE -> noSideLine(run.shown(), back);
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

    /** "A 2 can only go up, so we cashed you out: +15" (spec R1.6). */
    private static String noSideLine(Card c, int back) {
        String why = c.rank() == HigherLowerRun.LOWEST ? "A 2 can only go up"
                : c.rank() == HigherLowerRun.HIGHEST ? "An Ace can only go down"
                : "No guess on " + c.withArticle() + " can grow your pot";
        return why + ", so we cashed you out: +" + back + ".";
    }

    private ItemStack giveBack(HigherLowerSettings.Odds odds) {
        if (odds == null) {
            return Menus.icon(Material.KNOWLEDGE_BOOK, "&7Pick how many tokens to put in");
        }
        return Menus.icon(Material.KNOWLEDGE_BOOK, "&7The best play gives back about &f"
                        + RtpLimits.wholePercent(odds.rtp()) + " &7of every 100 tokens",
                "&7(at " + odds.stake() + " tokens in)",
                "&7Every extra guess risks what you have.");
    }

    private ItemStack howItPays(HigherLowerSettings.Odds odds) {
        if (odds == null) {
            return Menus.icon(Material.WRITABLE_BOOK, "&eHow it pays");
        }
        HigherLowerRun.Terms t = odds.terms();
        long example = HigherLowerRun.potIfRight(t.in(), 7, t.r(), t.cap());
        return Menus.icon(Material.WRITABLE_BOOK, "&eHow it pays &7- &6" + t.in() + " in",
                "&7Your pot starts at the " + t.in() + " tokens you put in.",
                "&7A right guess grows it; the less likely the guess, the more it grows.",
                example > 0 ? "&7On a 7, Higher (7 of 13 cards) turns " + t.in() + " into " + example + "."
                        : "&7Each button says what you'd have if you're right.",
                "&7Cash out any time after a right guess.",
                "&7It cashes out by itself at " + t.cap() + " tokens or after " + t.maxGuesses()
                        + " right guesses.",
                "&7A wrong guess, or the same card, ends the run with nothing.");
    }

    // ---- clicks ------------------------------------------------------------------------------------

    private void start() {
        hold(ClickHold.ACTION_MS);
        if (playing()) {
            return;
        }
        HigherLowerSettings.Odds odds = higherLower.settings().odds(stake);
        if (odds == null) {
            refresh();
            return;
        }
        ChanceRounds.Round opened = games().rounds().open(viewer, game, odds.stake(),
                HigherLowerRun.data(odds.terms(), ""));
        if (opened == null) {
            ChanceRounds.Round open = games().rounds().openRound(viewer.getUniqueId(), game.id());
            if (open != null) {
                HigherLowerRun.Saved saved = HigherLowerRun.read(open.data());
                round = open;
                run = HigherLowerRun.replay(open.seed(), saved.terms(), saved.actions());
            }
            refresh();
            return;
        }
        Sounds.paid(viewer);
        gone = false;
        round = opened;
        run = HigherLowerRun.start(opened.seed(), odds.terms());
        refresh();
    }

    private void guess(Side side) {
        hold(ClickHold.ACTION_MS);
        if (!playing() || !run.offered(side)) {
            return;
        }
        HigherLowerRun next = copy();
        boolean right = next.guess(side);
        String data = HigherLowerRun.data(next.terms(), next.actions());
        if (next.done()) {
            run = next;
            settle(data);
        } else {
            ChanceRounds.Round stepped = games().rounds().step(round, data);
            if (stepped == null) {
                finishedElsewhere();
                return;
            }
            round = stepped;
            run = next;
            if (right) {
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            }
        }
        refresh();
    }

    private void cashOut() {
        hold(ClickHold.ACTION_MS);
        if (!playing() || !run.canCashOut()) {
            return;
        }
        HigherLowerRun next = copy();
        next.cashOut();
        run = next;
        settle(HigherLowerRun.data(next.terms(), next.actions()));
        refresh();
    }

    private HigherLowerRun copy() {
        return HigherLowerRun.replay(round.seed(), run.terms(), run.actions());
    }

    /** Pay the finished run once, then the sound that matches it (never a win's for a loss). */
    private void settle(String data) {
        if (!games().rounds().close(round, run.payout(), data)) {
            finishedElsewhere();
            return;
        }
        round = null;
        int back = run.payout();
        int in = run.terms().in();
        if (back > in) {
            Sounds.won(viewer);
        } else if (back == in) {
            Sounds.received(viewer);
        } else {
            Sounds.miss(viewer);
        }
    }

    /** The round was settled somewhere else (the ten-minute sweep): say so and clear the table. */
    private void finishedElsewhere() {
        round = null;
        run = null;
        gone = true;
        viewer.sendMessage(Text.of("&7That Higher or Lower run was already finished for you."));
        refresh();
    }
}
