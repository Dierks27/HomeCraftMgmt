package com.dierks.homecraft.gui.games.cabinet.connect;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.cabinet.connect.ConnectFour;
import com.dierks.homecraft.games.cabinet.connect.ConnectFourBoard;
import com.dierks.homecraft.games.cabinet.connect.ConnectFourMatch;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.UUID;

/**
 * One game of Connect Four (54 slots), laid out per spec R3.14: the 7 by 5 board in rows 0-4,
 * columns 1-7 (slot = row·9 + 1 + col, the top row first); tapping any cell drops into its
 * column. Column 0 is the other side's colour with its turn marker on top, column 8 yours with
 * "New game" at the bottom; row 5 has the wins (46), how to play (48), Back (49) and the high
 * scores (50).
 *
 * <p>Against the Arcade, its reply comes on this screen's ticker a moment after your move, so it
 * reads as a move rather than a flicker. In a friend game each player has their own screen on the
 * same {@link ConnectFourMatch}; each ticker notices the other's move by the match's version and
 * repaints. Closing the screen (or Back) mid-game ends it: against the Arcade it simply doesn't
 * count, against a friend it ends for both.
 *
 * <pre>
 *   O  b  b  b  b  b  b  b  Y      0 their turn marker · 8 yours
 *   o  b  b  b  b  b  b  b  y
 *   o  b  b  b  b  b  b  b  y      b = the board, o/y = the colours
 *   o  b  b  b  b  b  b  b  y
 *   o  b  b  b  b  b  b  b  N      44 new game
 *   .  W  .  ?  X  H  .  .  .      46 wins · 48 how to play · 49 back · 50 high scores
 * </pre>
 */
public final class ConnectFourPlayMenu extends GameMenu {

    /** Server ticks per screen tick. */
    private static final long PERIOD = 2;
    /** Screen ticks before the Arcade drops its piece. */
    private static final int ARCADE_DELAY = 5;
    private static final Material[] PIECE = {Material.RED_CONCRETE, Material.YELLOW_CONCRETE};
    private static final Material[] STRIP = {Material.RED_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE};
    private static final String[] COLOUR = {"&c", "&e"};
    private static final String[] COLOUR_NAME = {"red", "yellow"};

    private final ConnectFour connect;
    private final ConnectFourMatch match;
    private final UUID me;
    private final int seat;
    private final ItemStack[] empty = new ItemStack[ConnectFourBoard.COLS];
    private final ItemStack[] piece = new ItemStack[2];
    private final ItemStack[] winning = new ItemStack[2];
    private final ItemStack[] strip = new ItemStack[2];
    private boolean started;
    private int seen = -1;
    private int arcadeIn = -1;
    private boolean told;

    /** @param back what Back does (the first screen) */
    public ConnectFourPlayMenu(HomeCraftManagement plugin, ConnectFour game, Player viewer, Runnable back,
                               ConnectFourMatch match) {
        super(plugin, game, viewer, back);
        this.connect = game;
        this.match = match;
        this.me = viewer.getUniqueId();
        this.seat = Math.max(0, match.seat(me));
        for (int c = 0; c < empty.length; c++) {
            empty[c] = Menus.icon(Material.BLUE_STAINED_GLASS_PANE, "&7Drop in column " + (c + 1));
        }
        for (int s = 0; s < 2; s++) {
            String name = COLOUR[s] + COLOUR_NAME[s].substring(0, 1).toUpperCase(Locale.ROOT)
                    + COLOUR_NAME[s].substring(1);
            piece[s] = Menus.icon(PIECE[s], name);
            winning[s] = Menus.glint(Menus.icon(PIECE[s], COLOUR[s] + "&l★ Four in a row ★"), true);
            strip[s] = Menus.icon(STRIP[s], " ");
        }
        init(54, Text.of("&bConnect Four"));
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
        ConnectFourBoard board = match.board();
        boolean over = match.over();
        for (int row = 0; row < board.rows(); row++) {
            int engineRow = board.rows() - 1 - row;
            for (int col = 0; col < board.cols(); col++) {
                int c = col;
                int owner = board.at(col, engineRow);
                ItemStack item = owner < 0 ? empty[col]
                        : board.inWinLine(col, engineRow) ? winning[owner] : piece[owner];
                set(row * 9 + 1 + col, item, over ? null : e -> drop(c));
            }
        }
        int them = 1 - seat;
        set(0, theirTile(), null);
        set(8, yourTile(), null);
        for (int row = 1; row < 5; row++) {
            set(row * 9, strip[them], null);
            if (row < 4) {
                set(row * 9 + 8, strip[seat], null);
            }
        }
        if (match.vsArcade()) {
            set(44, Menus.icon(PIECE[seat], over ? "&eNew game" : "&eNew game &7(starts over)",
                    over ? "&7Same level, fresh board." : "&7This game won't count."), e -> newGame());
        } else {
            set(44, over ? Menus.icon(Material.OAK_DOOR, "&eBack to Connect Four") : strip[seat],
                    over ? e -> back.run() : null);
        }

        Long wins = connect.hardWins(viewer);
        long w = wins == null ? 0 : wins;
        set(46, Menus.count(Menus.icon(Material.GOLD_INGOT, "&eHard wins: &f" + w,
                connect.rewardLeft(viewer) ? "&6Today's token: win on normal or hard."
                        : "&7Today's token is won."), (int) Math.max(1, Math.min(64, w))), null);
        set(48, rulesTile(connect.rules()), null);
        set(49, Menus.icon(Material.BARRIER, "&cBack",
                over ? "&7Back to Connect Four." : match.vsArcade() ? "&7Leaves this game (it won't count)."
                        : "&7Leaves this game (it ends for both)."), e -> back.run());
        set(50, over ? Menus.icon(Material.OAK_SIGN, "&eHigh scores &7- hard wins")
                        : Menus.icon(Material.OAK_SIGN, "&7High scores &8- after this game"),
                over ? e -> connect.scores(viewer, back) : null);
        seen = match.version();
    }

    /** One screen tick: notice a friend's move, and let the Arcade move after a short pause. */
    private void tick() {
        if (match.arcadeToMove()) {
            if (arcadeIn < 0) {
                arcadeIn = ARCADE_DELAY;
            } else if (--arcadeIn <= 0) {
                arcadeIn = -1;
                if (match.arcadeMove() >= 0) {
                    dropSound(0.8f);
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
                dropSound(0.8f);
            }
            refresh();
        }
    }

    private void drop(int col) {
        if (!match.yourTurn(me)) {
            viewer.sendActionBar(Text.of(match.over() ? "&7The game is over." : "&7Wait for your turn."));
            return;
        }
        if (match.play(me, col) < 0) {
            viewer.sendActionBar(Text.of("&7That column is full."));
            Sounds.refused(viewer);
            return;
        }
        dropSound(1.2f);
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
            connect.finish(viewer, match);
        } else {
            connect.finishFriendGame(match);
        }
    }

    private void newGame() {
        if (!connect.mayPlay(viewer)) {
            return;
        }
        new ConnectFourPlayMenu(plugin, connect, viewer, back, connect.vsArcade(viewer, match.level())).open(viewer);
    }

    private ItemStack yourTile() {
        Material m = PIECE[seat];
        String you = COLOUR[seat] + "You &7- " + COLOUR_NAME[seat];
        return switch (match.outcome(me)) {
            case PLAYING -> match.yourTurn(me)
                    ? Menus.glint(Menus.icon(m, "&aYour turn! &7Tap a column", you), true)
                    : Menus.glint(Menus.icon(m, you), false);
            case WON -> Menus.glint(Menus.icon(Material.NETHER_STAR, "&a&lYou won!"), true);
            case LOST -> Menus.icon(m, "&7" + theirName() + " won this one");
            case DRAW -> Menus.icon(m, "&7A draw: the board is full");
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
        return Menus.glint(Menus.icon(PIECE[them], name, COLOUR[them] + "Plays " + COLOUR_NAME[them] + "."),
                theirTurn);
    }

    private String theirName() {
        if (match.vsArcade()) {
            return "The Arcade";
        }
        UUID other = match.other(me);
        String name = other == null ? null : Bukkit.getOfflinePlayer(other).getName();
        return name == null ? "Your friend" : name;
    }

    private void dropSound(float pitch) {
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_WOOD_PLACE, 0.6f, pitch);
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (!match.vsArcade()) {
            connect.leaveFriendGame(viewer, match, false);
        }
    }
}
