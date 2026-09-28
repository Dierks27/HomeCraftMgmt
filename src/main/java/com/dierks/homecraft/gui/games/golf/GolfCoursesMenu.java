package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Mini Golf's course list (54, spec §12): {@code /hcm play golf}.
 *
 * <pre>
 *  row 0     4 Mini Golf and its rules
 *  rows 1-3  10-16, 19-25, 28-34 the open courses, 21 a page (name, holes, par; your best, the record)
 *  row 5     45/53 page arrows when needed, 47 Pick your ball, 48 How to play, 49 Back/Close
 * </pre>
 *
 * A course opens its own screen ({@link GolfCourseMenu}) with Start; nothing starts from here, so
 * a stray click never takes anyone anywhere.
 */
public final class GolfCoursesMenu extends GameMenu {

    private static final int HEADER = 4;
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int BALL = 47;
    private static final int RULES = 48;

    private final MiniGolf golf;
    private final int page;

    public GolfCoursesMenu(HomeCraftManagement plugin, MiniGolf golf, Player viewer, int page, Runnable back) {
        super(plugin, golf, viewer, back);
        this.golf = golf;
        this.page = Math.max(0, page);
        init(54, Text.of("&d" + golf.name()));
    }

    @Override
    protected void build() {
        fill();
        List<GolfCourse> courses = golf.playable();
        List<String> lore = new ArrayList<>();
        for (String line : golf.rules()) {
            lore.add("&7" + line);
        }
        int n = courses.size();
        set(HEADER, Menus.icon(Material.SNOWBALL, "&d" + golf.name() + " &7- " + n + " course" + (n == 1 ? "" : "s"),
                lore.toArray(new String[0])), null);
        int pages = Math.max(1, (n + SLOTS.length - 1) / SLOTS.length);
        int p = Math.min(page, pages - 1);
        if (courses.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No courses are open yet", "&7Check back soon!"), null);
        }
        for (int i = 0; i < SLOTS.length && p * SLOTS.length + i < n; i++) {
            GolfCourse c = courses.get(p * SLOTS.length + i);
            set(SLOTS[i], golf.courseTile(viewer, c),
                    e -> new GolfCourseMenu(plugin, golf, viewer, c.id(), this::reopen).open(viewer));
        }
        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new GolfCoursesMenu(plugin, golf, viewer, p - 1, back).open(viewer));
        }
        if (p < pages - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new GolfCoursesMenu(plugin, golf, viewer, p + 1, back).open(viewer));
        }
        set(BALL, GolfBallMenu.currentTile(golf, viewer),
                e -> new GolfBallMenu(plugin, golf, viewer, 0, this::reopen).open(viewer));
        set(RULES, rulesTile(golf.rules()), null);
        exitTile();
    }

    private void reopen() {
        new GolfCoursesMenu(plugin, golf, viewer, page, back).open(viewer);
    }
}
