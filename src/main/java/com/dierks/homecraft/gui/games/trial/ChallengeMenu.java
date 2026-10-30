package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.PartyRaces;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The challenge's course picker (0.38.4, the Clubhouse kit's "Challenge - race anyone online"): one tile per open
 * course that has party races (never a Dropper), the name in the NAME and the route in the lore; a tap goes on
 * to the everyone-online picker ({@link ChallengePickerMenu}) through {@code PartyRaces.challengeOn}. 27 slots,
 * the way out at 22.
 */
public final class ChallengeMenu extends GameMenu {

    /** The slots the course tiles fill, in order (the way out is 22). */
    static final int[] TILES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24,
            25, 26};

    private final TimeTrials trials;

    public ChallengeMenu(HomeCraftManagement plugin, TimeTrials trials, Player viewer, Runnable back) {
        super(plugin, trials, viewer, back);
        this.trials = trials;
        init(27, Text.of("&6Challenge: pick a course"));
    }

    @Override
    protected void build() {
        fill();
        List<Course> courses = trials.openCourses().stream().filter(c -> PartyRaces.offered(c.kind())).toList();
        if (courses.isEmpty()) {
            set(13, Menus.icon(Material.PAPER, "&7No course is open right now"), null);
        }
        for (int i = 0; i < courses.size() && i < TILES.length; i++) {
            Course c = courses.get(i);
            set(TILES[i], Menus.icon(TimeTrials.icon(c.kind()), "&e" + c.name() + " &7(" + TrialText.label(c) + ")",
                    "&7" + TrialText.route(c), "&eTap, then pick who to challenge"),
                    e -> trials.party().challengeOn(viewer, c.id(), back));
        }
        exitTile();
    }
}
