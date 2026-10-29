package com.dierks.homecraft.gui.games.clubhouse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.clubhouse.ClubBoardText;
import com.dierks.homecraft.games.clubhouse.FameBoard;
import com.dierks.homecraft.games.clubhouse.StatsCard;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * My stats (54; 0.38.3), from the Clubhouse kit's My stats item: the same numbers as the board on the west wall
 * that only this player sees, as a chest screen, every fact in an item's NAME (Bedrock shows lore only on
 * tap-and-hold).
 *
 * <pre>
 *  row 0     4 who (only you see these)
 *  row 2     19 runs this week · 20 this week's family points (0.38.4) · 21 tokens from games this week ·
 *            23 best golf · 25 personal bests (22 "counting" until the numbers have been read)
 *  rows 3-4  27-44 one per course played: its best time and the place on its board, best place first
 *  row 5     49 Close
 * </pre>
 * It shows what was last read ({@link StatsCard.Mine}): it never reads the database itself (the Clubhouse reads it
 * off the main thread, and tapping My stats asks for a fresh read). It belongs to the Clubhouse, so it opens in the
 * Clubhouse's session.
 */
public final class MyStatsMenu extends GameMenu {

    static final int HEADER = 4;
    static final int RUNS = 19;
    static final int WEEK = 20;
    static final int COUNTING = 22;
    static final int TOKENS = 21;
    static final int GOLF = 23;
    static final int BESTS = 25;
    static final int FIRST_BEST = 27;
    static final int MAX_BESTS = 18;

    private final StatsCard.Mine mine;

    public MyStatsMenu(HomeCraftManagement plugin, Game clubhouse, Player viewer, StatsCard.Mine mine) {
        super(plugin, clubhouse, viewer, null);
        this.mine = mine;
        init(54, Text.of(ClubBoardText.STATS_TITLE));
    }

    @Override
    protected void build() {
        fill();
        set(HEADER, Menus.icon(Material.PAPER, ClubBoardText.statsTitle(viewer.getName()),
                "&7Only you see these."), null);
        exitTile();
        if (mine == null) {
            set(COUNTING, Menus.icon(Material.CLOCK, ClubBoardText.COUNTING, "&7Tap My stats again in a moment."),
                    null);
            return;
        }
        set(RUNS, Menus.icon(Material.CLOCK, "&eRuns this week: &f" + mine.runsThisWeek(),
                "&7Counted runs on this week's boards."), null);
        set(WEEK, Menus.icon(Material.CAKE, weekName(mine), ClubBoardText.WEEK_LORE), null); // 0.38.4
        set(TOKENS, Menus.icon(Material.GOLD_NUGGET, "&eTokens from games this week: &f" + mine.tokensWon(),
                "&7Courses, golf, cabinets, Race Night,", "&7Falling Floors and Weekly Cup prizes."), null);
        FameBoard.GolfRound g = mine.bestGolf();
        set(GOLF, Menus.icon(Material.SNOWBALL, g == null ? "&7No golf rounds yet"
                : "&eBest golf: &f" + ClubBoardText.course(g.course()) + " " + g.strokes() + " ("
                + ClubBoardText.vs(g.vsPar()) + ")"), null);
        int n = mine.bests().size();
        set(BESTS, Menus.icon(Material.NETHER_STAR, "&ePersonal bests: &f" + n + (n == 1 ? " course" : " courses")),
                null);
        for (int i = 0; i < Math.min(MAX_BESTS, n); i++) {
            StatsCard.Best b = mine.bests().get(i);
            set(FIRST_BEST + i, Menus.icon(Material.FEATHER, bestName(b)), null);
        }
    }

    /** "&amp;eThis week: &amp;f9 pts, 2nd": the family week tile's name (0.38.4), its key fact in the NAME. */
    static String weekName(StatsCard.Mine m) {
        String line = Text.plain(ClubBoardText.statsWeek(m.weekPoints(), m.weekRank()));
        int colon = line.indexOf(':');
        return "&eThis week: &f" + line.substring(colon + 1).strip();
    }

    /** "&amp;fSky Rings &amp;7- &amp;e1:02.4 &amp;7(#2)": a course's best and its place. */
    static String bestName(StatsCard.Best b) {
        return "&f" + ClubBoardText.course(b.course()) + " &7- &e" + TrialText.time(b.ms())
                + (b.rank() > 0 ? " &7(#" + b.rank() + ")" : "");
    }
}
