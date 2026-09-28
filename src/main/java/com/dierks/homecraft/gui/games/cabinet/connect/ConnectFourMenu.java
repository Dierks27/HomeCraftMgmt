package com.dierks.homecraft.gui.games.cabinet.connect;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.cabinet.connect.ConnectFour;
import com.dierks.homecraft.games.cabinet.connect.ConnectFourAI;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Connect Four's first screen (27 slots): who to play, the high scores, and whether friends may
 * invite you.
 *
 * <p>The Arcade's three levels each say in their NAME whether today's token is still there to win
 * (normal and hard only — Bedrock shows lore only on a long press). A friend game says it pays
 * nothing before anyone is invited, and while an invite of yours is out the tile shows who you're
 * waiting on and calls it off when tapped.
 *
 * <pre>
 *   .  .  .  .  ?  .  .  .  .      4 how to play
 *   .  E  N  H  .  F  .  S  .      10 easy · 11 normal · 12 hard · 14 a friend · 16 high scores
 *   .  .  I  .  X  .  .  .  .      20 friend invites on/off · 22 back / close
 * </pre>
 */
public final class ConnectFourMenu extends GameMenu {

    private final ConnectFour connect;

    public ConnectFourMenu(HomeCraftManagement plugin, ConnectFour game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.connect = game;
        init(27, Text.of("&bConnect Four"));
    }

    @Override
    protected void build() {
        fill();
        List<String> rules = new ArrayList<>(connect.rules());
        rules.add("Closing a game ends it.");
        set(4, rulesTile(rules), null);

        boolean token = connect.rewardLeft(viewer);
        int reward = connect.settings().dailyReward();
        String prize = token ? " &6- today's token" : "";
        String tokenLine = token
                ? "&6Win today for +" + reward + " token" + (reward == 1 ? "" : "s") + " (once a day)."
                : "&7Today's token is won. Play for fun!";
        set(10, Menus.icon(Material.LIME_CONCRETE, "&aPlay the Arcade: Easy",
                "&7A gentle start.", "&7Easy wins are just for fun."), e -> play(ConnectFourAI.Level.EASY));
        set(11, Menus.glint(Menus.icon(Material.YELLOW_CONCRETE, "&ePlay the Arcade: Normal" + prize,
                "&7It looks four moves ahead.", tokenLine), token), e -> play(ConnectFourAI.Level.NORMAL));
        set(12, Menus.glint(Menus.icon(Material.RED_CONCRETE, "&cPlay the Arcade: Hard" + prize,
                "&7It looks seven moves ahead.", tokenLine, "&7Hard wins go on the high scores."), token),
                e -> play(ConnectFourAI.Level.HARD));

        String waiting = connect.waitingOn(viewer);
        if (waiting != null) {
            set(14, Menus.icon(Material.CLOCK, "&7Waiting for " + waiting + "...",
                    "&eClick to call the invite off."), e -> {
                connect.cancelInvite(viewer);
                refresh();
            });
        } else {
            set(14, Menus.icon(Material.PLAYER_HEAD, "&bPlay a friend &7- just for fun",
                    "&7Pick someone online to invite.", "&7Friend games pay no tokens."), e -> pickFriend());
        }
        Long wins = connect.hardWins(viewer);
        set(16, Menus.icon(Material.OAK_SIGN, "&eHigh scores &7- hard wins",
                "&7Your hard wins: &f" + (wins == null ? 0 : wins)), e -> connect.scores(viewer, this::reopen));

        boolean takes = connect.takesInvites(viewer);
        set(20, Menus.icon(takes ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eFriend invites: " + (takes ? "&aon" : "&coff"),
                takes ? "&7Friends can invite you to Connect Four." : "&7Nobody can invite you to Connect Four.",
                "&eClick to turn them " + (takes ? "off" : "on") + "."), e -> {
            connect.setTakesInvites(viewer, !takes);
            refresh();
        });
        exitTile();
    }

    /**
     * Each new game (and each invite) runs the gate again: a pause or a world change since this
     * screen opened counts.
     */
    private void play(ConnectFourAI.Level level) {
        if (!connect.mayPlay(viewer)) {
            return;
        }
        new ConnectFourPlayMenu(plugin, connect, viewer, this::reopen, connect.vsArcade(viewer, level)).open(viewer);
    }

    private void pickFriend() {
        if (!connect.mayPlay(viewer)) {
            return;
        }
        connect.pickFriend(viewer, this::reopen, this::reopen);
    }

    private void reopen() {
        new ConnectFourMenu(plugin, connect, viewer, back).open(viewer);
    }
}
