package com.dierks.homecraft.gui.games.cabinet.tictactoe;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToe;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeAI;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeBoard;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeMatch;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.UUID;

/**
 * One game of Tic-Tac-Toe (54 slots): the 3 by 3 board in the middle of rows 1-3.
 *
 * <p>Against the Arcade its reply comes on this screen's ticker a moment after your move. In a
 * friend game each player has their own screen on the same {@link TicTacToeMatch}, and each
 * ticker notices the other's move by the match's version. Closing the screen (or Back) mid-game
 * ends it: against the Arcade it simply doesn't count, against a friend it ends for both.
 *
 * <pre>
 *   .  .  .  O  .  X  .  .  .      3 their turn marker · 5 yours
 *   .  .  .  c  c  c  .  .  .
 *   .  .  .  c  c  c  .  .  .      the board
 *   .  .  .  c  c  c  .  .  .
 *   .  .  .  .  N  .  .  .  .      40 new game
 *   .  W  .  ?  X  H  .  .  .      46 wins · 48 how to play · 49 back · 50 high scores
 * </pre>
 */
public final class TicTacToePlayMenu extends GameMenu {

    private static final int[] CELLS = {12, 13, 14, 21, 22, 23, 30, 31, 32};
    /** Server ticks per screen tick. */
    private static final long PERIOD = 2;
    /** Screen ticks before the Arcade plays. */
    private static final int ARCADE_DELAY = 5;
    private static final Material[] MARK = {Material.RED_CONCRETE, Material.LIGHT_BLUE_CONCRETE};
    private static final String[] NAME = {"&c&lX", "&b&lO"};

    private final TicTacToe ttt;
    private final TicTacToeMatch match;
    private final UUID me;
    private final int seat;
    private final ItemStack empty = Menus.icon(Material.WHITE_STAINED_GLASS_PANE, "&7Tap to play here");
    private final ItemStack[] mark = new ItemStack[2];
    private final ItemStack[] winning = new ItemStack[2];
    private boolean started;
    private int seen = -1;
    private int arcadeIn = -1;
    private boolean told;

    /** @param back what Back does (the first screen) */
    public TicTacToePlayMenu(HomeCraftManagement plugin, TicTacToe game, Player viewer, Runnable back,
                             TicTacToeMatch match) {
        super(plugin, game, viewer, back);
        this.ttt = game;
        this.match = match;
        this.me = viewer.getUniqueId();
        this.seat = Math.max(0, match.seat(me));
        for (int s = 0; s < 2; s++) {
            mark[s] = Menus.icon(MARK[s], NAME[s]);
            winning[s] = Menus.glint(Menus.icon(MARK[s], NAME[s] + " &7★ three in a row"), true);
        }
        init(54, Text.of("&bTic-Tac-Toe"));
    }

    @Override
    public void open(Player player) {
        super.open(player);
        if (!started && isOpenFor(viewer)) {
            started = true;
            ticker(PERIOD, this::tick);
        }
    }

    @Override
    protected void build() {
        fill();
        TicTacToeBoard board = match.board();
        boolean over = match.over();
        for (int i = 0; i < TicTacToeBoard.CELLS; i++) {
            int cell = i;
            int owner = board.at(i);
            ItemStack item = owner < 0 ? empty : board.inWinLine(i) ? winning[owner] : mark[owner];
            set(CELLS[i], item, over ? null : e -> play(cell));
        }
        set(3, theirTile(), null);
        set(5, yourTile(), null);
        if (match.vsArcade()) {
            set(40, Menus.icon(MARK[seat], over ? "&eNew game" : "&eNew game &7(starts over)",
                    over ? "&7Same level, fresh board." : "&7This game won't count."), e -> newGame());
        } else if (over) {
            set(40, Menus.icon(Material.OAK_DOOR, "&eBack to Tic-Tac-Toe"), e -> back.run());
        }
        Long wins = ttt.wins(viewer);
        long w = wins == null ? 0 : wins;
        set(46, Menus.count(Menus.icon(Material.GOLD_INGOT, "&eWins: &f" + w,
                ttt.rewardLeft(viewer) ? "&6Today's token: win on easy, or draw on hard."
                        : "&7Today's token is won."), (int) Math.max(1, Math.min(64, w))), null);
        set(48, rulesTile(ttt.rules()), null);
        set(49, Menus.icon(Material.BARRIER, "&cBack",
                over ? "&7Back to Tic-Tac-Toe." : match.vsArcade() ? "&7Leaves this game (it won't count)."
                        : "&7Leaves this game (it ends for both)."), e -> back.run());
        set(50, over ? Menus.icon(Material.OAK_SIGN, "&eHigh scores &7- wins")
                        : Menus.icon(Material.OAK_SIGN, "&7High scores &8- after this game"),
                over ? e -> ttt.scores(viewer, back) : null);
        seen = match.version();
    }

    /** One screen tick: notice a friend's move, and let the Arcade play after a short pause. */
    private void tick() {
        if (match.arcadeToMove()) {
            if (arcadeIn < 0) {
                arcadeIn = ARCADE_DELAY;
            } else if (--arcadeIn <= 0) {
                arcadeIn = -1;
                if (match.arcadeMove() >= 0) {
                    markSound(0.8f);
                }
                if (match.over()) {
                    report();
                }
                refresh();
                return;
            }
        }
        if (match.version() != seen) {
            if (!match.vsArcade() && match.yourTurn(me)) {
                markSound(0.8f);
            }
            refresh();
        }
    }

    private void play(int cell) {
        if (!match.yourTurn(me)) {
            viewer.sendActionBar(Text.of(match.over() ? "&7The game is over." : "&7Wait for your turn."));
            return;
        }
        if (!match.play(me, cell)) {
            viewer.sendActionBar(Text.of("&7That square is taken."));
            return;
        }
        markSound(1.2f);
        if (match.over()) {
            report();
        }
        refresh();
    }

    /** The game just ended on this screen: record, pay and tell (once). */
    private void report() {
        if (told) {
            return;
        }
        told = true;
        if (match.vsArcade()) {
            ttt.finish(viewer, match);
        } else {
            ttt.finishFriendGame(match);
        }
    }

    private void newGame() {
        if (!ttt.mayPlay(viewer)) {
            return;
        }
        new TicTacToePlayMenu(plugin, ttt, viewer, back, ttt.vsArcade(viewer, match.level())).open(viewer);
    }

    private ItemStack yourTile() {
        Material m = MARK[seat];
        String you = "&fYou play " + NAME[seat];
        return switch (match.outcome(me)) {
            case PLAYING -> match.yourTurn(me)
                    ? Menus.glint(Menus.icon(m, "&aYour turn! &7Tap a square", you), true)
                    : Menus.glint(Menus.icon(m, you), false);
            case WON -> Menus.glint(Menus.icon(Material.NETHER_STAR, "&a&lYou won!"), true);
            case LOST -> Menus.icon(m, "&7" + theirName() + " won this one");
            case DRAW -> match.vsArcade() && match.level() == TicTacToeAI.Level.HARD
                    ? Menus.glint(Menus.icon(Material.NETHER_STAR, "&a&lA draw! &7The best result on hard"), true)
                    : Menus.icon(m, "&7A draw: the board is full");
            case LEFT -> Menus.icon(m, "&7You left the game");
            case OTHER_LEFT -> Menus.icon(m, "&7" + theirName() + " left the game");
        };
    }

    private ItemStack theirTile() {
        int them = 1 - seat;
        String who = match.vsArcade()
                ? "&eThe Arcade &7(" + match.level().name().toLowerCase(Locale.ROOT) + ")"
                : "&e" + theirName();
        boolean theirTurn = !match.over() && match.board().turn() == them;
        String name = who + (theirTurn ? (match.vsArcade() ? " &e- thinking..." : " &e- their turn") : "");
        return Menus.glint(Menus.icon(MARK[them], name, "&7Plays " + NAME[them] + "&7."), theirTurn);
    }

    private String theirName() {
        if (match.vsArcade()) {
            return "The Arcade";
        }
        UUID other = match.other(me);
        String name = other == null ? null : Bukkit.getOfflinePlayer(other).getName();
        return name == null ? "Your friend" : name;
    }

    private void markSound(float pitch) {
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, pitch);
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (!match.vsArcade()) {
            ttt.leaveFriendGame(viewer, match, false);
        }
    }
}
