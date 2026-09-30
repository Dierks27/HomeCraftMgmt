package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenScheduler;
import com.dierks.homecraft.games.gen.engine.GenService;
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
 * The Fresh Courses screen (36, GEN-SPEC §5.4, the weekly addendum §2, GEN-SPEC-KEEP §3): every
 * course of the current set in one place, what {@code /hcm play fresh_courses} and the Fresh
 * Courses tile open. Its title and header follow the cadence: "This week's courses - Mon 28
 * Sep-Sun 4 Oct" as shipped, "Today's courses - Tue 29 Sep" when daily.
 *
 * <pre>
 *  4        the set's dates, when the courses change and when next
 *  9-17     one tile per course, in slot order, in its tier's colour; the NAME carries the key facts
 *           (your stars in this set, a golf course's holes and par, a dropper's levels, its course
 *           code) for Bedrock
 *  18 Classic courses (the tip)   20 Classic Parkour   22 Classic Sky Rings   24 Classic Golf
 *           26 Classic Dropper
 *  29 Star Chart (your stars this week)     31 Back/Close     33 How stars work
 * </pre>
 *
 * <p>A course that can't be played right now shows grey, "being built, back soon", with why in
 * its lore; a click on it says so. A click on an open one opens that course's own screen through
 * the gate, like its tile on the Courses or Golf tab. A Classics slot holds an old course an admin
 * brought back (its old records are the ones to beat), is being built with one, or is empty (and
 * says how to ask for one). The Star Chart opens the week's board of Fresh Courses.
 */
public final class FreshMenu extends GameMenu {

    private static final int HEADER = 4;
    private static final int ROW = 9;
    private static final int TIP = 18;
    /** Classic Parkour, Classic Sky Rings, Classic Golf, Classic Dropper. */
    static final int[] CLASSICS = {20, 22, 24, 26};
    private static final int CHART = 29;
    private static final int HOW = 33;

    public FreshMenu(HomeCraftManagement plugin, Game fresh, Player viewer, Runnable back) {
        super(plugin, fresh, viewer, back);
        init(36, Text.of(title(cadence(plugin))));
    }

    // ---- the pure parts (tested) -----------------------------------------------------------

    /** Where {@code n} tiles go in the second row: centred, in order. */
    static int[] row(int n) {
        int count = Math.max(0, Math.min(9, n));
        int[] out = new int[count];
        int start = ROW + (9 - count) / 2;
        for (int i = 0; i < count; i++) {
            out[i] = start + i;
        }
        return out;
    }

    /** The screen's title: "&amp;eThis week's courses", "&amp;eToday's courses", "&amp;eThe current courses". */
    static String title(int cadence) {
        return "&e" + GenCopy.current(cadence);
    }

    /**
     * The header's name: "&amp;eThis week's courses &amp;7- Mon 28 Sep-Sun 4 Oct" (a set longer than
     * a day shows its first and last day), "&amp;eToday's courses &amp;7- Tue 29 Sep".
     */
    static String headerName(int cadence, long firstDay) {
        return "&e" + GenCopy.current(cadence) + " &7- " + DailyText.setDates(cadence, firstDay);
    }

    /** What the screen says when no course is up yet. */
    static final String NONE_YET = "&7No courses yet";

    // ---- the screen ------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        exitTile();
        GamesService games = plugin.games();
        if (games == null) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7The games are closed right now"), null);
            return;
        }
        Edition ed = DailyLookup.edition(games);
        long now = games.clock().nowMillis();
        long next = games.generated().nextChangeAt();
        int cadence = ed.cadenceDays();
        long firstDay = ed.editionStart(now);
        GenService engine = DailyLookup.engine(games);
        if (engine != null) {
            // the set up now: one kept over a cadence change keeps its own length and dates
            GenScheduler.Target set = engine.current(Slots.DAILY_PARKOUR_EASY.id());
            cadence = set.cadence();
            firstDay = set.start();
        }
        List<String> head = new ArrayList<>();
        String when = next > now ? GenCopy.when(next, games.clock().zone()) : null;
        head.add("&7" + GenCopy.schedule(ed.cadenceDays(), ed.rebuildDay(), when) + ".");
        if (next > now) {
            head.add(GenCopy.newIn(next - now));
        }
        head.add("&7The same courses for everyone.");
        head.add("&7Finish one for a &6★&7!");
        set(HEADER, Menus.icon(Material.CLOCK, headerName(cadence, firstDay), head.toArray(new String[0])), null);

        List<DailyTiles.View> views = DailyTiles.shownViews(games, Slots.ALL);
        int[] at = row(views.size());
        for (int i = 0; i < at.length; i++) {
            DailyTiles.View v = views.get(i);
            set(at[i], DailyTiles.tile(games, viewer, v, ed.cadenceDays()),
                    e -> DailyTiles.click(games, viewer, v, this::reopen));
        }
        if (views.isEmpty()) {
            set(13, Menus.icon(Material.GRAY_DYE, NONE_YET, "&7The first set is being built."), null);
        }

        set(TIP, Menus.icon(Material.PAPER, "&6Classic courses", "&7Old courses come back here,",
                "&7with their old records to beat.", GenCopy.CLASSICS_TIP), null);
        for (int i = 0; i < Slots.CLASSICS.size() && i < CLASSICS.length; i++) {
            Slots.Def classic = Slots.CLASSICS.get(i);
            set(CLASSICS[i], DailyTiles.classicTile(games, viewer, classic),
                    e -> DailyTiles.classicClick(games, viewer, classic, this::reopen));
        }

        long week = DailyLookup.weekKey(games);
        List<DailyStars.Goal> goals = DailyLookup.goals(games, week);
        chart(games, week, goals);
        set(HOW, Menus.icon(Material.BOOK, "&eHow stars work", DailyText.howStars(goals).toArray(new String[0])),
                null);
    }

    /** The Star Chart tile: your stars this week, the next goal, the week's top; a click opens the board. */
    private void chart(GamesService games, long week, List<DailyStars.Goal> goals) {
        long mine = DailyLookup.weekStars(games, viewer.getUniqueId(), week);
        List<String> lore = new ArrayList<>();
        String goal = DailyText.nextGoal(mine, goals);
        if (goal != null) {
            lore.add(goal);
        }
        GamesDao.ScoreRow top = DailyLookup.chartTop(games, week);
        if (top != null) {
            boolean yours = top.player().equals(viewer.getUniqueId());
            String who = yours ? "you" : Bukkit.getOfflinePlayer(top.player()).getName();
            lore.add("&7Top this week: &f" + top.score() + "★ &7by &f" + (who == null ? "a player" : who));
        }
        lore.add("&7Your best stars add up all week.");
        Game fresh = games.game(Slots.DAILY);
        if (fresh != null) {
            lore.add("&eClick to see the chart");
        }
        set(CHART, Menus.glint(Menus.icon(Material.NETHER_STAR, DailyText.chartName(mine), lore.toArray(new String[0])),
                false), e -> {
            if (fresh == null) {
                Sounds.refused(viewer);
                return;
            }
            games.screens().scores(viewer, fresh, GenBoards.week(week), false, this::reopen);
        });
    }

    /** The configured cadence, for the title (the shipped weekly one when it can't be read). */
    private static int cadence(HomeCraftManagement plugin) {
        try {
            return DailyLookup.edition(plugin == null ? null : plugin.games()).cadenceDays();
        } catch (RuntimeException e) {
            return Edition.DEFAULT_CADENCE;
        }
    }

    private void reopen() {
        new FreshMenu(plugin, game, viewer, back).open(viewer);
    }
}
