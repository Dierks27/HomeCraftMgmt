package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the course engines and the daily screens read about a player's daily courses (GEN-SPEC
 * §5.2-§5.4), in one place so the Today's Courses screen, a course's own screen and the finish
 * line always read the same boards: the course day, the Star Chart's week, a player's stars on a
 * course that day and this week, whether today's first-finish reward is had, and the two writes a
 * counted finish makes outside its own game (the stars, and a Star Chart goal paid by the
 * {@code daily} game).
 *
 * <p>Every read is forgiving: a database error is logged and reads as "nothing yet", because a
 * screen or a finish line must never fail over a star count.
 */
public final class DailyLookup {

    /** How far around a Daily Courses half the course editors refuse points (GEN-SPEC §2.4). */
    public static final int EDITOR_MARGIN = 16;

    private DailyLookup() {
    }

    /** The course day now, as the engine sees it ({@link DailyText#courseDay}). */
    public static long courseDay(GamesService games) {
        return DailyText.courseDay(games.clock().nowMillis(), games.generated().nextChangeAt(), games.clock().zone());
    }

    /** The first day of a week ({@code quests.week_starts_on}), like the time trials' weekly boards. */
    public static DayOfWeek weekStart(HomeCraftManagement plugin) {
        try {
            var quests = plugin == null ? null : plugin.config().quests();
            if (quests != null && quests.weekStartsOn() != null) {
                return quests.weekStartsOn();
            }
        } catch (RuntimeException e) {
            // the default week
        }
        return DayOfWeek.MONDAY;
    }

    /** The Star Chart week that course day {@code day} is in. */
    public static long weekKey(GamesService games, long day) {
        ZoneId zone = games.clock().zone();
        return new Edition(zone, null, weekStart(games.plugin())).weekKey(day);
    }

    /** The player's best stars on a course that course day (0 = none). */
    public static int stars(GamesService games, UUID player, String courseId, long day) {
        Long best = games.scores().best(player, GenBoards.GAME, GenBoards.stars(courseId, day));
        return best == null ? 0 : (int) Math.max(0, Math.min(3, best));
    }

    /** The player's Star Chart total for a week (0 = none). */
    public static long weekStars(GamesService games, UUID player, long week) {
        Long total = games.scores().best(player, GenBoards.GAME, GenBoards.week(week));
        return total == null ? 0 : Math.max(0, total);
    }

    /** The Star Chart's top total this week, or {@code null}. */
    public static GamesDao.ScoreRow chartTop(GamesService games, long week) {
        return games.scores().record(GenBoards.GAME, GenBoards.week(week), false);
    }

    /** Whether the player has had a course's first-finish reward for course day {@code day}. */
    public static boolean dailyClearPaid(GamesService games, UUID player, String gameId, String courseId, long day) {
        try {
            return games.dao().rewardPaid(player, gameId, RewardKind.DAILY_CLEAR,
                    SkillRewards.dailyClearRef(courseId, day));
        } catch (SQLException e) {
            log(games, Level.WARNING, "Daily Courses: could not read a player's rewards", e);
            return false;
        }
    }

    /**
     * Record a counted finish's stars ({@link GamesDao#addStars}: one transaction), or {@code null}
     * when it couldn't be (logged; the finish itself still counts).
     */
    public static GamesDao.StarsAdded addStars(GamesService games, UUID player, String dayBoard, String weekBoard,
                                               int stars) {
        try {
            return games.dao().addStars(player, dayBoard, weekBoard, stars, games.clock().nowMillis());
        } catch (SQLException e) {
            log(games, Level.SEVERE, "Daily Courses: could not record a player's stars", e);
            return null;
        }
    }

    /**
     * Pay a Star Chart goal through the {@code daily} game (its {@code daily_cap}, the
     * {@code GAMES_DAILY} source, a once-only {@code MILESTONE} ref); nothing when that game isn't
     * registered or isn't open (GEN-SPEC §8.5).
     *
     * @return the tokens actually paid
     */
    public static int payGoal(GamesService games, Player player, String ref, int tokens, String detail) {
        Game daily = games.game(Slots.DAILY);
        if (daily == null || tokens <= 0 || !games.enabled(daily)) {
            return 0;
        }
        return games.rewards().pay(player, daily, TokenService.Source.GAMES_DAILY, RewardKind.MILESTONE, ref, tokens,
                games.generated().starGoalCap(), detail);
    }

    /**
     * Whether block (x, y, z) of {@code world} is inside a Daily Courses half expanded by
     * {@value #EDITOR_MARGIN} (where the course editors refuse points, GEN-SPEC §2.4).
     */
    public static boolean nearArea(GeneratedCourses g, String world, int x, int y, int z) {
        return nearBox(g, world, x, y, z, x, y, z);
    }

    /**
     * Whether any block of the box between (x1, y1, z1) and (x2, y2, z2), both included, is inside
     * a Daily Courses half expanded by {@value #EDITOR_MARGIN}: a checkpoint with its radius, or a
     * golf hole's bounds, is kept as far away as the Daily Courses engine keeps its halves from
     * hand-built courses, so an edit the editor takes never switches a slot off.
     *
     * <p>{@link GeneratedCourses#inArea} answers for one block of a half. Every half is at least
     * {@code m} blocks on each side (the smallest is 16 high), so on each axis the blocks of the
     * box grown by {@code m} that a half covers are either at one of its ends or at least
     * {@code m} in a row, and every run of {@code m} holds one of every {@code m}-th block: asking
     * the grown box's ends and every {@code m}-th block between them, on all three axes, finds any
     * half it meets without knowing where the halves are (for a single block, the 27 moves of
     * {@code -m}, 0 and {@code +m}).
     */
    public static boolean nearBox(GeneratedCourses g, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        if (g == null || world == null) {
            return false;
        }
        int m = EDITOR_MARGIN;
        int[] xs = lattice(Math.min(x1, x2) - m, Math.max(x1, x2) + m, m);
        int[] ys = lattice(Math.min(y1, y2) - m, Math.max(y1, y2) + m, m);
        int[] zs = lattice(Math.min(z1, z2) - m, Math.max(z1, z2) + m, m);
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    if (g.inArea(world, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Every {@code step}-th block from {@code lo}, and {@code hi}. */
    private static int[] lattice(int lo, int hi, int step) {
        int n = (int) (((long) hi - lo) / step) + 1;
        boolean end = lo + (long) (n - 1) * step != hi;
        int[] out = new int[n + (end ? 1 : 0)];
        for (int i = 0; i < n; i++) {
            out[i] = lo + i * step;
        }
        if (end) {
            out[n] = hi;
        }
        return out;
    }

    private static void log(GamesService games, Level level, String line, Throwable e) {
        HomeCraftManagement plugin = games.plugin();
        (plugin != null ? plugin.getLogger() : Logger.getLogger("HomeCraftManagement")).log(level, line, e);
    }
}
