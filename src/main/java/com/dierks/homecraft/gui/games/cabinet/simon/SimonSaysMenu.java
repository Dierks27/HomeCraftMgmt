package com.dierks.homecraft.gui.games.cabinet.simon;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.simon.SimonSays;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Simon Says's first screen (27 slots): Play, today's Daily challenge, and the high scores.
 *
 * <p>It says everything before a pattern is played (spec §10b): closing a game ends it with no
 * score, the daily's FIRST game is the one scored try (so closing it uses the try up), and once
 * that's gone the Daily tile reads "practice".
 *
 * <pre>
 *   .  .  .  .  ?  .  .  .  .      4 how to play
 *   .  .  P  .  D  .  H  T  .      11 play · 13 daily · 15 high scores · 16 today's board
 *   .  .  .  .  X  .  .  .  .      22 back / close
 * </pre>
 */
public final class SimonSaysMenu extends GameMenu {

    private final SimonSays simon;

    public SimonSaysMenu(HomeCraftManagement plugin, SimonSays game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.simon = game;
        init(27, Text.of("&bSimon Says"));
    }

    @Override
    protected void build() {
        fill();
        List<String> rules = new ArrayList<>(simon.rules());
        rules.add("Closing a game ends it: no score.");
        set(4, rulesTile(rules), null);

        Long best = simon.best(viewer);
        set(11, Menus.icon(Material.LIME_CONCRETE,
                "&aPlay" + (best == null || best == 0 ? "" : " &7- your best: " + best),
                "&7A new pattern every time.",
                "&7Milestones: " + ladder(simon.settings().milestones()),
                "&7Closing the game ends it (no score)."), e -> classic());

        int reward = simon.settings().dailyReward();
        String prize = reward > 0 ? " &6(+" + reward + " token" + (reward == 1 ? "" : "s") + ")" : "";
        if (!simon.triedToday(viewer)) {
            set(13, Menus.glint(Menus.icon(Material.CLOCK, "&eDaily challenge &7- your scored try",
                    "&7Today's pattern is the same for everyone.",
                    "&7Goal: play back " + SimonSays.DAILY_GOAL + prize,
                    "&7You get one scored try a day.",
                    "&cClosing the game uses up your try."), true), e -> daily());
        } else {
            set(13, Menus.glint(Menus.icon(Material.CLOCK, "&7Daily challenge &8- practice",
                    "&7You've had today's scored try.",
                    "&7Practice isn't recorded."), false), e -> daily());
        }

        set(15, Menus.icon(Material.OAK_SIGN, "&eHigh scores", "&7The longest patterns."),
                e -> simon.scores(viewer, Scores.CLASSIC, this::reopen));
        set(16, Menus.icon(Material.FILLED_MAP, "&eToday's pattern", "&7Scored tries on today's daily pattern."),
                e -> simon.scores(viewer, simon.todayBoard(), this::reopen));
        exitTile();
    }

    private void play(CabinetGame.DailyStart daily) {
        long seed = daily == null ? simon.classicSeed() : daily.seed();
        new SimonSaysPlayMenu(plugin, simon, viewer, this::reopen, daily, seed).open(viewer);
    }

    /** Each new game runs the gate again: a pause or a world change since this screen opened counts. */
    private void classic() {
        if (simon.mayPlay(viewer)) {
            play(null);
        }
    }

    private void daily() {
        if (simon.mayPlay(viewer)) {
            play(simon.startDaily(viewer));
        }
    }

    private void reopen() {
        new SimonSaysMenu(plugin, simon, viewer, back).open(viewer);
    }

    /** "5 / 10 / 15". */
    static String ladder(List<Integer> milestones) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < milestones.size(); i++) {
            s.append(i == 0 ? "" : " / ").append(milestones.get(i));
        }
        return s.toString();
    }
}
