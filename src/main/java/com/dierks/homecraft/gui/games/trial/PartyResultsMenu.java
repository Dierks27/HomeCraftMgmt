package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.trial.PartyRace;
import com.dierks.homecraft.games.trial.RaceStandings;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A party race's results for the whole group (54, owner decision D4), opened for every racer once
 * they are home.
 *
 * <p>4 the race; 19-25 and 28-34 everyone, finishers first by time ("1st Sam - 0:41.2"), then who
 * was still racing, whose race didn't count, who left and who sat it out; 40 back to the party to
 * race again; 49 Close. Each line's facts are in its NAME for Bedrock. A party race pays nothing of
 * its own: each finish was already the racer's normal run on the course.
 */
public final class PartyResultsMenu extends GameMenu {

    private final TimeTrials trials;
    private final String courseName;
    private final List<PartyRace.Line> lines;

    public PartyResultsMenu(HomeCraftManagement plugin, TimeTrials trials, Player viewer, String courseName,
                            List<PartyRace.Line> lines) {
        super(plugin, trials, viewer, null);
        this.trials = trials;
        this.courseName = courseName;
        this.lines = List.copyOf(lines);
        init(54, Text.of("&6Race results"));
    }

    @Override
    protected void build() {
        fill();
        set(4, Menus.icon(Material.GOLD_BLOCK, "&6Race results: " + courseName,
                "&7Each finish also counted as", "&7a normal run on the course."), null);
        int slot = 0;
        for (PartyRace.Line l : lines) {
            if (slot >= PartyMenu.MEMBER_SLOTS.length) {
                break;
            }
            set(PartyMenu.MEMBER_SLOTS[slot++], icon(l), null);
        }
        if (trials.party().lobby(viewer.getUniqueId()) != null) {
            set(40, Menus.icon(Material.LIME_CONCRETE, "&aBack to the party &7- race again"),
                    e -> trials.party().openLobby(viewer, null));
        }
        exitTile();
    }

    /** One racer's line: their place and time in the NAME. */
    static ItemStack icon(PartyRace.Line l) {
        return switch (l.result()) {
            case FINISHED -> Menus.icon(l.rank() == 1 ? Material.GOLD_INGOT : l.rank() == 2 ? Material.IRON_INGOT
                            : l.rank() == 3 ? Material.COPPER_INGOT : Material.PAPER,
                    "&e" + RaceStandings.ordinal(l.rank()) + " &f" + l.name() + " &7- " + TrialText.time(l.ms()));
            case STILL_RACING -> Menus.icon(Material.CLOCK, "&f" + l.name() + " &7- still racing, great effort!");
            case NOT_COUNTED -> Menus.icon(Material.GRAY_DYE, "&f" + l.name() + " &7- that race didn't count");
            case LEFT -> Menus.icon(Material.GRAY_DYE, "&f" + l.name() + " &7- left the race");
            case NOT_STARTED -> Menus.icon(Material.GRAY_DYE, "&f" + l.name() + " &7- sat this one out");
        };
    }
}
