package com.dierks.homecraft.gui.games.cabinet.whack;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.whack.WhackAZombie;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Whack-a-Zombie's first screen (27 slots): Play, today's Daily challenge, and the high scores.
 *
 * <p>It says everything before a round starts (spec §10b): closing a round ends it with no score,
 * the daily's FIRST round is the one scored try (so closing it uses the try up), and once that's
 * gone the Daily tile reads "practice".
 *
 * <pre>
 *   .  .  .  .  ?  .  .  .  .      4 how to play
 *   .  .  P  .  D  .  H  T  .      11 play · 13 daily · 15 high scores · 16 today's round
 *   .  .  .  .  X  .  .  .  .      22 back / close
 * </pre>
 */
public final class WhackAZombieMenu extends GameMenu {

    private final WhackAZombie whack;

    public WhackAZombieMenu(HomeCraftManagement plugin, WhackAZombie game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.whack = game;
        init(27, Text.of("&bWhack-a-Zombie"));
    }

    @Override
    protected void build() {
        fill();
        List<String> rules = new ArrayList<>(whack.rules());
        rules.add("Closing a round ends it: no score.");
        set(4, rulesTile(rules), null);

        Long best = whack.best(viewer);
        set(11, Menus.icon(Material.LIME_CONCRETE,
                "&aPlay" + (best == null || best == 0 ? "" : " &7- your best: " + best + " points"),
                "&7A new round every time.",
                "&7Milestones: " + ladder(whack.settings().milestones()) + " points",
                "&7Closing the round ends it (no score)."), e -> classic());

        int reward = whack.settings().dailyReward();
        String prize = reward > 0 ? " &6(+" + reward + " token" + (reward == 1 ? "" : "s") + ")" : "";
        if (!whack.triedToday(viewer)) {
            set(13, Menus.glint(Menus.icon(Material.CLOCK, "&eDaily challenge &7- your scored try",
                    "&7Today's round is the same for everyone.",
                    "&7Goal: " + WhackAZombie.DAILY_GOAL + " points" + prize,
                    "&7You get one scored try a day.",
                    "&cClosing the round uses up your try."), true), e -> daily());
        } else {
            set(13, Menus.glint(Menus.icon(Material.CLOCK, "&7Daily challenge &8- practice",
                    "&7You've had today's scored try.",
                    "&7Practice isn't recorded."), false), e -> daily());
        }

        set(15, Menus.icon(Material.OAK_SIGN, "&eHigh scores", "&7The most points in a round."),
                e -> whack.scores(viewer, Scores.CLASSIC, this::reopen));
        set(16, Menus.icon(Material.FILLED_MAP, "&eToday's round", "&7Scored tries on today's daily round."),
                e -> whack.scores(viewer, whack.todayBoard(), this::reopen));
        exitTile();
    }

    private void play(CabinetGame.DailyStart daily) {
        long seed = daily == null ? whack.classicSeed() : daily.seed();
        new WhackAZombiePlayMenu(plugin, whack, viewer, this::reopen, daily, seed).open(viewer);
    }

    /** Each new game runs the gate again: a pause or a world change since this screen opened counts. */
    private void classic() {
        if (whack.mayPlay(viewer)) {
            play(null);
        }
    }

    private void daily() {
        if (whack.mayPlay(viewer)) {
            CabinetGame.DailyStart start = whack.startDaily(viewer);
            if (start != null) { // null: held for a restart (told)
                play(start);
            }
        }
    }

    private void reopen() {
        new WhackAZombieMenu(plugin, whack, viewer, back).open(viewer);
    }

    /** "15 / 25 / 35". */
    static String ladder(List<Integer> milestones) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < milestones.size(); i++) {
            s.append(i == 0 ? "" : " / ").append(milestones.get(i));
        }
        return s.toString();
    }
}
