package com.dierks.homecraft.gui.games.cabinet.match;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.match.MiniMatch;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Mini Match's first screen (27 slots): Play, today's Daily challenge, and the high scores.
 *
 * <p>It says everything before a board is dealt (spec §10b): closing a game ends it with no
 * score, the daily's FIRST deal is the one scored try (so closing it uses the try up), and once
 * that's gone the Daily tile reads "practice".
 *
 * <pre>
 *   .  .  .  .  ?  .  .  .  .      4 how to play
 *   .  .  P  .  D  .  H  T  .      11 play · 13 daily · 15 high scores · 16 today's board
 *   .  .  .  .  X  .  .  .  .      22 back / close
 * </pre>
 */
public final class MiniMatchMenu extends GameMenu {

    private final MiniMatch match;

    public MiniMatchMenu(HomeCraftManagement plugin, MiniMatch game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.match = game;
        init(27, Text.of("&bMini Match"));
    }

    @Override
    protected void build() {
        fill();
        List<String> rules = new ArrayList<>(match.rules());
        rules.add("Closing a game ends it: no score.");
        set(4, rulesTile(rules), null);

        Long best = match.best(viewer);
        set(11, Menus.icon(Material.LIME_CONCRETE, "&aPlay" + (best == null ? "" : " &7- your best: " + best + " flips"),
                "&7A new board every time.",
                "&7Milestones: " + ladder(match.settings().milestones()) + " flips",
                "&7Closing the game ends it (no score)."), e -> play(null));

        int reward = match.settings().dailyReward();
        String prize = reward > 0 ? " &6(+" + reward + " token" + (reward == 1 ? "" : "s") + ")" : "";
        if (!match.triedToday(viewer)) {
            set(13, Menus.glint(Menus.icon(Material.CLOCK, "&eDaily challenge &7- your scored try",
                    "&7Today's board is the same for everyone.",
                    "&7Goal: " + MiniMatch.DAILY_GOAL + " flips or fewer" + prize,
                    "&7You get one scored try a day.",
                    "&cClosing the game uses up your try."), true), e -> daily());
        } else {
            set(13, Menus.glint(Menus.icon(Material.CLOCK, "&7Daily challenge &8- practice",
                    "&7You've had today's scored try.",
                    "&7Practice isn't recorded."), false), e -> daily());
        }

        set(15, Menus.icon(Material.OAK_SIGN, "&eHigh scores", "&7The fewest flips."),
                e -> match.scores(viewer, Scores.CLASSIC, this::reopen));
        set(16, Menus.icon(Material.FILLED_MAP, "&eToday's board", "&7Scored tries on today's daily board."),
                e -> match.scores(viewer, match.todayBoard(), this::reopen));
        exitTile();
    }

    private void play(CabinetGame.DailyStart daily) {
        long seed = daily == null ? match.classicSeed() : daily.seed();
        new MiniMatchPlayMenu(plugin, match, viewer, this::reopen, daily, seed).open(viewer);
    }

    private void daily() {
        play(match.startDaily(viewer));
    }

    private void reopen() {
        new MiniMatchMenu(plugin, match, viewer, back).open(viewer);
    }

    /** "30 / 24 / 20". */
    static String ladder(List<Integer> milestones) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < milestones.size(); i++) {
            s.append(i == 0 ? "" : " / ").append(milestones.get(i));
        }
        return s.toString();
    }
}
