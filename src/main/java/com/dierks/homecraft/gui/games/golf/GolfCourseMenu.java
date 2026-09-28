package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfRun;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * One golf course (27, spec §12): what {@code /hcm play <course>} and its tile open.
 *
 * <pre>
 *  4  the course: holes and par, each hole's par
 *  10 your best     11 high scores (the record)     13 Start     15 Pick your ball     16 How to play
 *  22 Back/Close
 * </pre>
 *
 * Start takes the player into the game (their things are kept safe until they come back), so it
 * is the one button here that goes anywhere; everything else is looking.
 */
public final class GolfCourseMenu extends GameMenu {

    private static final int HEADER = 4;
    private static final int BEST = 10;
    private static final int SCORES = 11;
    private static final int START = 13;
    private static final int BALL = 15;
    private static final int RULES = 16;

    private final MiniGolf golf;
    private final String courseId;

    public GolfCourseMenu(HomeCraftManagement plugin, MiniGolf golf, Player viewer, String courseId, Runnable back) {
        super(plugin, golf, viewer, back);
        this.golf = golf;
        this.courseId = courseId;
        GolfCourse c = golf.course(courseId);
        init(27, Text.of("&d" + (c == null ? golf.name() : c.name())));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        GolfCourse c = golf.playableCourse(courseId);
        if (c == null) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7This course is closed right now"), null);
            return;
        }
        List<String> holes = new ArrayList<>();
        StringBuilder pars = new StringBuilder("&7Pars:");
        for (int p : c.pars()) {
            pars.append(' ').append(p);
        }
        holes.add(pars.toString());
        set(HEADER, Menus.icon(Material.SNOWBALL, "&d" + c.name() + " &7- " + MiniGolf.holes(c.holes().size())
                + ", par " + c.par(), holes.toArray(new String[0])), null);

        Long best = golf.best(viewer.getUniqueId(), c.id());
        set(BEST, Menus.glint(Menus.icon(Material.GOLD_INGOT, best == null ? "&7No finish of yours yet"
                : "&eYour best: &f" + GolfRun.strokesText(best.intValue()),
                best == null ? "&7Finish it to set one." : "&7" + GolfRun.vsParText(best.intValue() - c.par()) + "."),
                best != null), null);

        GamesDao.ScoreRow record = golf.record(c.id());
        String holder = record == null ? null : Bukkit.getOfflinePlayer(record.player()).getName();
        set(SCORES, Menus.icon(Material.OAK_SIGN, record == null ? "&eHigh scores &7- no record yet"
                : "&eHigh scores &7- record " + GolfRun.strokesText((int) record.score()),
                record == null ? "&7Be the first!" : "&7Set by " + (holder == null ? "a player" : holder) + ".",
                "&eClick to see them"), e -> golf.openScores(viewer, c.id(), this::reopen));

        set(START, Menus.icon(Material.LIME_CONCRETE, "&aStart &7- hole 1, par " + c.holes().get(0).par(),
                "&7You go to the course with just", "&7your clubs. Your things are kept", "&7safe and come back when you leave.",
                "&eClick to play"), e -> {
            if (!golf.start(viewer, c.id())) {
                Sounds.refused(viewer);
            }
        });
        set(BALL, GolfBallMenu.currentTile(golf, viewer),
                e -> new GolfBallMenu(plugin, golf, viewer, 0, this::reopen).open(viewer));
        set(RULES, rulesTile(golf.rules()), null);
    }

    private void reopen() {
        new GolfCourseMenu(plugin, golf, viewer, courseId, back).open(viewer);
    }
}
