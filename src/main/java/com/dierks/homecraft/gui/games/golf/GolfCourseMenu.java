package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfGroup;
import com.dierks.homecraft.games.golf.GolfRun;
import com.dierks.homecraft.games.golf.MiniGolf;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One golf course (27, spec §12): what {@code /hcm play <course>} and its tile open.
 *
 * <pre>
 *  4  the course: holes and par, each hole's par
 *  10 your best     11 high scores (the record)     12 Play with friends (golf together, D4)
 *  13 Start     15 Pick your ball     16 How to play
 *  22 Back/Close
 * </pre>
 *
 * Start takes the player into the game (their things are kept safe until they come back), so it
 * is the one button here that goes anywhere; everything else is looking.
 *
 * <p>Golf of the Week and Tiny Golf (GEN-SPEC §5.4) show their set, in the set's words ("this
 * week" as shipped, "today" when daily): 4 the name with its course code, 10 your best this week,
 * 11 this week's board, and 14 your stars this week with the star lines and what its first finish
 * this week pays. A course recalled into Classic Golf shows its original set's board.
 */
public final class GolfCourseMenu extends GameMenu {

    private static final int HEADER = 4;
    private static final int BEST = 10;
    private static final int SCORES = 11;
    private static final int START = 13;
    private static final int BALL = 15;
    private static final int RULES = 16;
    private static final int STARS = 14;
    /** Golf together (EVENTS-OWNER-DECISIONS D4). */
    private static final int FRIENDS = 12;

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
        GenTag t = c.gen();
        GamesService games = plugin.games();
        int cadence = GenCopy.words(t); // a Classic's board holds its original set's times: no "this week"
        if (t != null && t.recalled()) {
            holes.add("&7Its old records are the ones to beat.");
        } else if (t != null) {
            holes.add("&7" + GenCopy.schedule(cadence, DailyLookup.edition(games).rebuildDay(), null) + ".");
            long now = games.clock().nowMillis();
            long next = games.generated().nextChangeAt();
            if (!DailyLookup.current(games, t.slot())) {
                holes.add(GenCopy.previous(cadence));
            } else if (next > now) {
                holes.add(GenCopy.newIn(next - now));
            }
        }
        set(HEADER, Menus.icon(Material.SNOWBALL, headerName(c, t == null ? null : DailyLookup.code(games, t)),
                holes.toArray(new String[0])), null);

        String mine = t == null ? "&eYour best: &f" : "&e" + GenCopy.yourBest(cadence) + ": &f";
        Long best = golf.best(viewer.getUniqueId(), c.id());
        set(BEST, Menus.glint(Menus.icon(Material.GOLD_INGOT, best == null
                ? (t == null ? "&7No finish of yours yet" : "&7No finish of yours " + GenCopy.when(cadence) + " yet")
                : mine + GolfRun.strokesText(best.intValue()),
                best == null ? "&7Finish it to set one." : "&7" + GolfRun.vsParText(best.intValue() - c.par()) + "."),
                best != null), null);

        GamesDao.ScoreRow record = golf.record(c.id());
        String holder = record == null ? null : Bukkit.getOfflinePlayer(record.player()).getName();
        String scores = t == null ? "&eHigh scores" : "&e" + GenCopy.times(cadence).replace("times", "scores");
        set(SCORES, Menus.icon(Material.OAK_SIGN, record == null ? scores + " &7- no one yet"
                : scores + " &7- best " + GolfRun.strokesText((int) record.score()),
                record == null ? "&7Be the first!" : "&7Set by " + (holder == null ? "a player" : holder) + ".",
                "&eClick to see them"), e -> golf.openScores(viewer, c.id(), this::reopen));
        if (t != null) {
            set(STARS, dailyStars(games, c, t), null);
        }

        set(START, Menus.icon(Material.LIME_CONCRETE, "&aStart &7- hole 1, par " + c.holes().get(0).par(),
                "&7You go to the course with just", "&7your clubs. Your things are kept", "&7safe and come back when you leave.",
                "&eClick to play"), e -> {
            if (!golf.start(viewer, c.id())) {
                Sounds.refused(viewer);
            }
        });
        set(FRIENDS, Menus.icon(Material.CAKE, "&bPlay with friends &7- up to " + GolfGroup.MAX,
                "&7Everyone plays the same hole at", "&7once, each with their own ball.", "&7Invite friends, then Start.",
                "&eClick to make a party"), e -> golf.together().openLobby(viewer, c.id(), this::reopen));
        set(BALL, GolfBallMenu.currentTile(golf, viewer),
                e -> new GolfBallMenu(plugin, golf, viewer, 0, this::reopen).open(viewer));
        set(RULES, rulesTile(golf.rules()), null);
    }

    /**
     * The header NAME: "&amp;dGolf of the Week &amp;7- 9 holes, par 29 &amp;8· &amp;7Course code GOLF-3"
     * (a recalled course: "&amp;6Classic: Tiny Golf (week of 5 Oct) ...").
     */
    static String headerName(GolfCourse c, String code) {
        String name = c.gen() != null && c.gen().recalled()
                ? "&6" + com.dierks.homecraft.games.trial.TimeTrials.classicName(c.gen(), c.name()) : "&d" + c.name();
        return name + " &7- " + MiniGolf.holes(c.holes().size()) + ", par " + c.par() + DailyLookup.codeSuffix(code);
    }

    /** A Fresh course's stars in its set (in the name, for Bedrock), the star lines and the set's first finish. */
    private ItemStack dailyStars(GamesService games, GolfCourse c, GenTag t) {
        int cadence = GenCopy.words(t);
        int stars = DailyLookup.stars(games, viewer.getUniqueId(), t);
        List<String> lore = new ArrayList<>();
        lore.add(DailyText.starStrokes(c.par(), c.holes().size()));
        int fresh = DailyLookup.freshClear(games, t);
        String first = DailyText.firstFinish(cadence, fresh, fresh > 0
                && DailyLookup.freshClearPaid(games, viewer.getUniqueId(), golf.id(), t));
        if (first != null) {
            lore.add(first);
        }
        long week = DailyLookup.weekStars(games, viewer.getUniqueId(), DailyLookup.weekKey(games));
        lore.add("&7Star Chart this week: &6" + week + "★");
        return Menus.glint(Menus.icon(Material.NETHER_STAR, DailyText.starsNow(cadence, stars),
                lore.toArray(new String[0])), stars >= 3);
    }

    private void reopen() {
        new GolfCourseMenu(plugin, golf, viewer, courseId, back).open(viewer);
    }
}
