package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
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
 * Today's Courses (27, GEN-SPEC §5.4): every daily course in one place, what {@code /hcm play
 * daily} and the Daily Courses tile open.
 *
 * <pre>
 *  4        today's date and when the next set is due
 *  10-16    one tile per daily course, in slot order, in its tier's colour; the NAME carries the
 *           key fact (your stars today, a golf course's holes and par) for Bedrock
 *  20 Star Chart (your stars this week)     22 Back/Close     24 How stars work
 * </pre>
 *
 * <p>A course that can't be played right now shows grey, "being built, back soon", with why in
 * its lore; a click on it says so. A click on an open one opens that course's own screen through
 * the gate, like its tile on the Courses or Golf tab. The Star Chart opens the week's board of the
 * {@code daily} game.
 */
public final class TodayMenu extends GameMenu {

    private static final int HEADER = 4;
    private static final int ROW = 9;
    private static final int CHART = 20;
    private static final int HOW = 24;

    public TodayMenu(HomeCraftManagement plugin, Game daily, Player viewer, Runnable back) {
        super(plugin, daily, viewer, back);
        init(27, Text.of("&eToday's Courses"));
    }

    // ---- the pure parts (tested) -----------------------------------------------------------

    /** Where {@code n} tiles go in the middle row: centred, in order. */
    static int[] row(int n) {
        int count = Math.max(0, Math.min(9, n));
        int[] out = new int[count];
        int start = ROW + (9 - count) / 2;
        for (int i = 0; i < count; i++) {
            out[i] = start + i;
        }
        return out;
    }

    /** The header's name: "&amp;eToday's Courses &amp;7- Tue 29 Sep". */
    static String headerName(long courseDay) {
        return "&eToday's Courses &7- " + DailyText.date(courseDay);
    }

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
        GeneratedCourses gen = games.generated();
        long today = DailyLookup.courseDay(games);
        long now = games.clock().nowMillis();
        long next = gen.nextChangeAt();
        List<String> head = new ArrayList<>();
        if (next > now) {
            head.add(GenCopy.newIn(next - now));
        }
        head.add("&7The same courses for everyone,");
        head.add("&7new every morning.");
        head.add("&7Finish one for a &6★&7!");
        set(HEADER, Menus.icon(Material.CLOCK, headerName(today), head.toArray(new String[0])), null);

        List<DailyTiles.View> views = DailyTiles.shownViews(games, Slots.ALL);
        int[] at = row(views.size());
        for (int i = 0; i < at.length; i++) {
            DailyTiles.View v = views.get(i);
            set(at[i], DailyTiles.tile(games, viewer, v, today), e -> DailyTiles.click(games, viewer, v, this::reopen));
        }
        if (views.isEmpty()) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7No daily courses yet", "&7The first set is being built."), null);
        }
        chart(games, gen, today);
        set(HOW, Menus.icon(Material.BOOK, "&eHow stars work",
                DailyText.howStars(gen.starGoals(), gen.starGoalReward()).toArray(new String[0])), null);
    }

    /** The Star Chart tile: your stars this week, the next goal, the week's top; a click opens the board. */
    private void chart(GamesService games, GeneratedCourses gen, long today) {
        long week = DailyLookup.weekKey(games, today);
        long mine = DailyLookup.weekStars(games, viewer.getUniqueId(), week);
        List<String> lore = new ArrayList<>();
        String goal = DailyText.nextGoal(mine, gen.starGoals(), gen.starGoalReward());
        if (goal != null) {
            lore.add(goal);
        }
        GamesDao.ScoreRow top = DailyLookup.chartTop(games, week);
        if (top != null) {
            boolean yours = top.player().equals(viewer.getUniqueId());
            String who = yours ? "you" : Bukkit.getOfflinePlayer(top.player()).getName();
            lore.add("&7Top this week: &f" + top.score() + "★ &7by &f" + (who == null ? "a player" : who));
        }
        lore.add("&7Every day's best stars add up.");
        Game daily = games.game(Slots.DAILY);
        if (daily != null) {
            lore.add("&eClick to see the chart");
        }
        set(CHART, Menus.glint(Menus.icon(Material.NETHER_STAR, DailyText.chartName(mine), lore.toArray(new String[0])),
                false), e -> {
            if (daily == null) {
                Sounds.refused(viewer);
                return;
            }
            games.screens().scores(viewer, daily, GenBoards.week(week), false, this::reopen);
        });
    }

    private void reopen() {
        new TodayMenu(plugin, game, viewer, back).open(viewer);
    }
}
