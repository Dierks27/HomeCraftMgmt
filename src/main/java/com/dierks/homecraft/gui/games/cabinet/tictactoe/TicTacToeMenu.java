package com.dierks.homecraft.gui.games.cabinet.tictactoe;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToe;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeAI;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Tic-Tac-Toe's first screen (27 slots): who to play, the high scores, and whether friends may
 * invite you.
 *
 * <p>Hard says in its name that a draw is the best result — the Arcade plays perfectly, and
 * nobody should keep trying to beat something that can't be beaten without knowing it. Each level
 * says in its NAME whether today's token is still there (a win on easy, a draw or better on hard).
 *
 * <pre>
 *   .  .  .  .  ?  .  .  .  .      4 how to play
 *   .  .  E  H  .  F  .  S  .      11 easy · 12 hard · 14 a friend · 16 high scores
 *   .  .  I  .  X  .  .  .  .      20 friend invites on/off · 22 back / close
 * </pre>
 */
public final class TicTacToeMenu extends GameMenu {

    private final TicTacToe ttt;

    public TicTacToeMenu(HomeCraftManagement plugin, TicTacToe game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.ttt = game;
        init(27, Text.of("&bTic-Tac-Toe"));
    }

    @Override
    protected void build() {
        fill();
        List<String> rules = new ArrayList<>(ttt.rules());
        rules.add("Closing a game ends it.");
        set(4, rulesTile(rules), null);

        boolean token = ttt.rewardLeft(viewer);
        int reward = ttt.settings().dailyReward();
        String prize = token ? " &6- today's token" : "";
        String tokens = "+" + reward + " token" + (reward == 1 ? "" : "s");
        set(11, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aPlay the Arcade: Easy" + prize,
                "&7It mostly picks squares at random.",
                token ? "&6Win today for " + tokens + " (once a day)." : "&7Today's token is won. Play for fun!"),
                token), e -> play(TicTacToeAI.Level.EASY));
        set(12, Menus.glint(Menus.icon(Material.RED_CONCRETE, "&cPlay the Arcade: Hard &7- a draw is best" + prize,
                "&7It never loses: nobody can beat it.",
                token ? "&6Draw today for " + tokens + " (once a day)." : "&7Today's token is won. Play for fun!"),
                token), e -> play(TicTacToeAI.Level.HARD));

        String waiting = ttt.waitingOn(viewer);
        if (waiting != null) {
            set(14, Menus.icon(Material.CLOCK, "&7Waiting for " + waiting + "...",
                    "&eClick to call the invite off."), e -> {
                ttt.cancelInvite(viewer);
                refresh();
            });
        } else {
            set(14, Menus.icon(Material.PLAYER_HEAD, "&bPlay a friend &7- just for fun",
                    "&7Pick someone online to invite.", "&7Friend games pay no tokens."),
                    e -> ttt.pickFriend(viewer, this::reopen, this::reopen));
        }
        Long wins = ttt.wins(viewer);
        set(16, Menus.icon(Material.OAK_SIGN, "&eHigh scores &7- wins",
                "&7Your wins against the Arcade: &f" + (wins == null ? 0 : wins)), e -> ttt.scores(viewer, this::reopen));

        boolean takes = ttt.takesInvites(viewer);
        set(20, Menus.icon(takes ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eFriend invites: " + (takes ? "&aon" : "&coff"),
                takes ? "&7Friends can invite you to Tic-Tac-Toe." : "&7Nobody can invite you to Tic-Tac-Toe.",
                "&eClick to turn them " + (takes ? "off" : "on") + "."), e -> {
            ttt.setTakesInvites(viewer, !takes);
            refresh();
        });
        exitTile();
    }

    private void play(TicTacToeAI.Level level) {
        new TicTacToePlayMenu(plugin, ttt, viewer, this::reopen, ttt.vsArcade(viewer, level)).open(viewer);
    }

    private void reopen() {
        new TicTacToeMenu(plugin, ttt, viewer, back).open(viewer);
    }
}
