package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Every open time-trial course (54, spec §11): {@code /hcm play trials}.
 *
 * <p>Row 0 is the header (4) with the rules and this week's course; rows 1-4 are the courses,
 * easiest first — each tile the same as on the Games screen's Courses tab ("River Run (Boat ·
 * Medium) - best 1:02.3") — 36 a page with the page arrows on 45 and 53 when there are more; 48
 * is how to play and 49 the way out. A course opens its own screen: rules, your best, the record,
 * Start.
 */
public final class CourseListMenu extends GameMenu {

    static final int PER_PAGE = 36;
    private static final int FIRST = 9;

    private final TimeTrials trials;
    private final int page;

    public CourseListMenu(HomeCraftManagement plugin, TimeTrials trials, Player viewer, Runnable back) {
        this(plugin, trials, viewer, back, 0);
    }

    private CourseListMenu(HomeCraftManagement plugin, TimeTrials trials, Player viewer, Runnable back, int page) {
        super(plugin, trials, viewer, back);
        this.trials = trials;
        this.page = Math.max(0, page);
        init(54, Text.of("&b" + trials.name()));
    }

    @Override
    protected void build() {
        fill();
        List<Course> open = trials.openCourses();
        String week = trials.courseOfWeek();
        set(4, header(open, week), null);
        int pages = pages(open.size());
        int p = Math.min(page, pages - 1);
        if (open.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No courses yet", "&7They're built in the Games world.",
                    "&7Check back soon!"), null);
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int at = p * PER_PAGE + i;
            if (at >= open.size()) {
                break;
            }
            Course c = open.get(at);
            set(FIRST + i, trials.courseTile(viewer, c, week), e -> new CourseMenu(plugin, trials, c, viewer,
                    this::reopen).open(viewer));
        }
        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new CourseListMenu(plugin, trials, viewer, back, p - 1).open(viewer));
        }
        if (p < pages - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new CourseListMenu(plugin, trials, viewer, back, p + 1).open(viewer));
        }
        set(48, rulesTile(trials.rules()), null);
        exitTile();
    }

    private ItemStack header(List<Course> open, String week) {
        List<String> lore = new ArrayList<>();
        for (String line : trials.rules()) {
            lore.add("&7" + line);
        }
        for (Course c : open) {
            if (c.id().equals(week)) {
                lore.add("&6★ Course of the week: &f" + c.name());
            }
        }
        return Menus.icon(Material.FEATHER, "&b" + trials.name() + " &7- " + open.size() + " course"
                + (open.size() == 1 ? "" : "s"), lore.toArray(new String[0]));
    }

    private void reopen() {
        new CourseListMenu(plugin, trials, viewer, back, page).open(viewer);
    }

    /** How many pages {@code count} courses fill (at least one). */
    static int pages(int count) {
        return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
    }
}
