package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.games.trial.Warmup;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Warm up first, or go straight to the timed run (27, owner decision D3). Opened whenever a course
 * starts while {@code games.trials.warmup_seconds} is above 0: from its screen's Start,
 * {@code /hcm play <course>}, a sign or Play again.
 *
 * <p>4 the course; 11 "Warm up (3:00) - then the timed run"; 15 "Go straight to the timed run";
 * 22 Close. The choice is in each item's NAME, so Bedrock players read it without a tap and hold.
 * Either button runs the gate again and takes the player to the start line.
 */
public final class WarmupChoiceMenu extends GameMenu {

    private final TimeTrials trials;
    private final Course course;

    public WarmupChoiceMenu(HomeCraftManagement plugin, TimeTrials trials, Course course, Player viewer) {
        super(plugin, trials, viewer, null);
        this.trials = trials;
        this.course = course;
        init(27, Text.of("&b" + course.name()));
    }

    @Override
    protected void build() {
        fill();
        set(4, Menus.icon(TimeTrials.icon(course.kind()), "&e" + course.name() + " &7(" + TrialText.label(course) + ")",
                "&7Warm up first, or go straight", "&7to the timed run."), null);
        int seconds = trials.settings().warmupSeconds();
        set(11, Menus.icon(Material.LIME_CONCRETE, "&a" + Warmup.choice(seconds) + " &7- then the timed run",
                "&7Run the course as much as you like.", "&7Nothing is timed or counted.",
                "&7Tap Start timed run when you're ready,", "&7or wait for the clock. Then 3-2-1!"),
                e -> {
                    viewer.closeInventory();
                    trials.startRun(viewer, course.id(), true);
                });
        set(15, Menus.icon(Material.YELLOW_CONCRETE, "&e" + Warmup.STRAIGHT,
                "&7The 3-2-1 starts at the start line.", "&7Your time counts."),
                e -> {
                    viewer.closeInventory();
                    trials.startRun(viewer, course.id(), false);
                });
        exitTile();
    }
}
