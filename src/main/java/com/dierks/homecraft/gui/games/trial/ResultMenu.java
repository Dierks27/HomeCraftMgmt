package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * How a run went (27, spec §11), opened once the player is back home.
 *
 * <p>4 the time; 11 how it was taken (a new best, your best, "That run didn't count" and why, or
 * a test run); 13 "Play again" — offered only once the return teleport has landed (a time trial is
 * a skill game, so a second go is about getting better); 15 the record; 16 tokens earned; 22 Back
 * to all the courses.
 */
public final class ResultMenu extends GameMenu {

    private final TimeTrials trials;
    private final TimeTrials.Result result;

    public ResultMenu(HomeCraftManagement plugin, TimeTrials trials, TimeTrials.Result result, Player viewer) {
        super(plugin, trials, viewer, () -> trials.open(viewer, null));
        this.trials = trials;
        this.result = result;
        init(27, Text.of("&b" + result.courseName()));
    }

    @Override
    protected void build() {
        fill();
        set(4, Menus.icon(Material.CLOCK, "&b" + result.courseName() + ": &f" + TrialText.time(result.ms())), null);
        set(11, verdict(), null);
        if (trials.home(viewer)) {
            set(13, Menus.icon(Material.LIME_CONCRETE, "&aPlay again", "&7Back to the start line."),
                    e -> trials.again(viewer, result));
        } else {
            set(13, Menus.icon(Material.GRAY_DYE, "&7Play again &8- once you're back"), null);
        }
        GamesDao.ScoreRow record = trials.record(result.courseId());
        set(15, Menus.icon(Material.GOLD_INGOT, record == null ? "&7No record yet"
                : "&6Record: &f" + TrialText.time(record.score()) + " &7by &f" + trials.holder(record.player())), null);
        if (result.counted()) {
            set(16, Menus.icon(Material.GOLD_NUGGET, result.earned() > 0 ? "&eEarned &6" + TrialText.tokens(result.earned())
                    : "&7No tokens from this run", "&7Your time is on the high scores."), null);
        }
        exitTile();
    }

    private ItemStack verdict() {
        if (result.test()) {
            return Menus.icon(Material.PAPER, "&dTest run &7- nothing recorded", result.reason() == null
                    ? "&7It would have counted." : "&7It wouldn't have counted: " + result.reason() + ".");
        }
        if (!result.counted()) {
            return Menus.icon(Material.REDSTONE, "&cThat run didn't count", "&7" + capital(result.reason()) + ".");
        }
        if (result.record()) {
            return Menus.icon(Material.NETHER_STAR, "&6★ New course record!");
        }
        if (result.personalBest()) {
            return Menus.icon(Material.EMERALD, "&e★ New best!");
        }
        return Menus.icon(Material.CLOCK, result.best() == null ? "&7Finished"
                : "&7Your best: &f" + TrialText.time(result.best()));
    }

    private static String capital(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
