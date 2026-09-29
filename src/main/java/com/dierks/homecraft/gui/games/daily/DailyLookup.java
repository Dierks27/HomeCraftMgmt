package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the course engines and the Fresh Courses screens read about a player's Fresh courses
 * (GEN-SPEC §5.2-§5.4, the weekly addendum), in one place so the Fresh Courses screen, a course's
 * own screen and the finish line always read the same boards: the live schedule, the Star Chart's
 * week, whether a course is its set's current one, a player's stars on a course in its set and
 * this week, whether the set's first-finish reward is had, a course's code, and the writes a
 * counted finish makes outside its own game (the stars, a Star Chart goal paid by Fresh Courses,
 * and what the quests and achievements hear).
 *
 * <p>Every read is forgiving: a database error is logged and reads as "nothing yet", because a
 * screen or a finish line must never fail over a star count.
 */
public final class DailyLookup {

    /** How far around a Fresh Courses half the course editors refuse points (GEN-SPEC §2.4). */
    public static final int EDITOR_MARGIN = 16;

    private DailyLookup() {
    }

    /** Fresh Courses itself, or {@code null} while it isn't registered. */
    public static DailyCourses fresh(GamesService games) {
        Game g = games == null ? null : games.game(Slots.DAILY);
        return g instanceof DailyCourses d ? d : null;
    }

    /** The running engine, or {@code null} while Fresh Courses is off. */
    public static GenService engine(GamesService games) {
        DailyCourses d = fresh(games);
        return d == null ? null : d.engine();
    }

    /**
     * The live schedule ({@code games.fresh.cadence}, {@code rebuild_at}, {@code rebuild_day}, the
     * quests' week); the shipped weekly one when Fresh Courses isn't registered.
     */
    public static Edition edition(GamesService games) {
        DailyCourses d = fresh(games);
        if (d != null) {
            try {
                return d.edition();
            } catch (RuntimeException e) {
                // its settings can't be read: the shipped schedule
            }
        }
        ZoneId zone = games == null ? ZoneOffset.UTC : games.clock().zone();
        DayOfWeek start = weekStart(games == null ? null : games.plugin());
        return new Edition(zone, Edition.DEFAULT_ROLLOVER, start, Edition.DEFAULT_CADENCE, start);
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
        return edition(games).weekKey(day);
    }

    /**
     * The Star Chart week a run finished now counts in: the week of its own course day
     * ({@code edition.weekKey(edition.day(now))}), whatever day its set began on.
     */
    public static long weekKey(GamesService games) {
        Edition ed = edition(games);
        return ed.weekKey(ed.day(games.clock().nowMillis()));
    }

    /**
     * Whether slot {@code slotId}'s live layout is its current set's (else it is still the last
     * one's: {@link GenCopy#previous}). True without an engine, so nothing is marked old by mistake.
     */
    public static boolean current(GamesService games, String slotId) {
        GenService e = engine(games);
        return e == null || e.today(slotId);
    }

    /** A course's code ("HARD-40"), or {@code null} (no engine, or a set from before the archive). */
    public static String code(GamesService games, GenTag tag) {
        GenService e = engine(games);
        try {
            return e == null || tag == null ? null : e.code(tag);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code " &8· &7Course code HARD-40"} for an item NAME, or {@code ""} without a code. */
    public static String codeSuffix(String code) {
        return code == null || code.isBlank() ? "" : " &8· &7" + GenCopy.courseCode(code);
    }

    /** The player's best stars on the course {@code tag} names, in its set (0 = none). */
    public static int stars(GamesService games, UUID player, GenTag tag) {
        Long best = games.scores().best(player, GenBoards.GAME, GenBoards.stars(tag));
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

    /** This week's Star Chart goals, each with its tokens (the engine's fixed list). */
    public static List<DailyStars.Goal> goals(GamesService games, long week) {
        return games.generated().goals(week);
    }

    /** What the first finish of the course {@code tag} names pays in its set (by the set's own cadence). */
    public static int freshClear(GamesService games, GenTag tag) {
        return tag == null ? 0 : games.generated().dailyClear(tag.slot(), tag.cadence());
    }

    /** Whether the player has had the first-finish reward of the set {@code tag} is in (a recall: the original's). */
    public static boolean freshClearPaid(GamesService games, UUID player, String gameId, GenTag tag) {
        try {
            return games.dao().rewardPaid(player, gameId, RewardKind.DAILY_CLEAR,
                    SkillRewards.freshClearRef(tag.slot(), tag.edition()));
        } catch (SQLException e) {
            log(games, Level.WARNING, "Fresh Courses: could not read a player's rewards", e);
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
            log(games, Level.SEVERE, "Fresh Courses: could not record a player's stars", e);
            return null;
        }
    }

    /**
     * Pay a Star Chart goal through Fresh Courses (its {@code daily_cap}, the {@code GAMES_DAILY}
     * source, a once-only {@code MILESTONE} ref), all or nothing: a goal today's caps can't pay whole
     * pays nothing and waits for a finish another day that week. Nothing when that game isn't
     * registered or isn't open (GEN-SPEC §8.5).
     *
     * @return the tokens actually paid
     */
    public static int payGoal(GamesService games, Player player, String ref, int tokens, String detail) {
        Game daily = games.game(Slots.DAILY);
        if (daily == null || tokens <= 0 || !games.enabled(daily)) {
            return 0;
        }
        return games.rewards().payWhole(player, daily, TokenService.Source.GAMES_DAILY, RewardKind.MILESTONE, ref,
                tokens, games.generated().starGoalCap(), detail, GenCopy.GOAL_LIMIT);
    }

    // ---- what the quests and achievements hear (EXTRAS E4) -------------------------------------------

    /**
     * Whether a run's stars were the first it has on its course in its set: the board went from
     * nothing to {@code dayBest} (stars always start at 1, so the rise is the whole best).
     */
    public static boolean firstStarsInSet(GamesDao.StarsAdded added) {
        return added != null && added.added() > 0 && added.added() == added.dayBest();
    }

    /** Whether a run took the week's total across its top goal (from below it to at or above it). */
    public static boolean reachedTopGoal(GamesDao.StarsAdded added, List<DailyStars.Goal> goals) {
        if (added == null || added.added() <= 0 || goals == null || goals.isEmpty()) {
            return false;
        }
        int top = 0;
        for (DailyStars.Goal g : goals) {
            top = Math.max(top, g.stars());
        }
        return top > 0 && added.weekBefore() < top && added.weekTotal() >= top;
    }

    /**
     * Whether every course of the live set has stars: {@code live} is the live set's tags (every
     * switched-on course with a vouched-for layout), {@code hasStars} whether the player has stars
     * on one. An empty set is never finished.
     */
    public static boolean setFinished(List<GenTag> live, Predicate<GenTag> hasStars) {
        if (live == null || live.isEmpty()) {
            return false;
        }
        for (GenTag t : live) {
            if (!hasStars.test(t)) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a counted finish on a Fresh course tells the quests and achievements (through
     * {@link GamesService#tellProgress}, guarded): the stars it added to the week, the week's top
     * goal when this run reached it, and a whole set finished when this run's stars were the last
     * ones missing from the live set (a recalled course isn't part of a set). Never throws.
     */
    public static void freshProgress(GamesService games, Player player, GenTag tag, GamesDao.StarsAdded added,
                                     long week, List<DailyStars.Goal> goals) {
        if (games == null || player == null || tag == null || added == null || added.added() <= 0) {
            return;
        }
        int stars = added.added();
        games.tellProgress(p -> p.starsEarned(player, stars));
        if (reachedTopGoal(added, goals)) {
            games.tellProgress(p -> p.starChartTopGoal(player, week));
        }
        if (tag.recalled() || !firstStarsInSet(added)) {
            return;
        }
        try {
            GenService e = engine(games);
            if (e == null) {
                return;
            }
            GenTag live = e.liveTag(tag.slot());
            if (live == null || !live.sameLayout(tag)) {
                return; // a finish on the last set's layout doesn't finish this set
            }
            List<GenTag> set = new ArrayList<>();
            for (Slots.Def d : Slots.ALL) {
                GenTag t = e.liveTag(d.id());
                if (t != null && e.live(d.id(), t)) {
                    set.add(t);
                }
            }
            UUID id = player.getUniqueId();
            if (setFinished(set, t -> t.sameLayout(tag) || stars(games, id, t) > 0)) {
                String edition = tag.edition();
                games.tellProgress(p -> p.freshSetFinished(player, edition));
            }
        } catch (RuntimeException ex) {
            log(games, Level.WARNING, "Fresh Courses: could not check a finished set", ex);
        }
    }

    /**
     * Whether block (x, y, z) of {@code world} is inside a Fresh Courses half expanded by
     * {@value #EDITOR_MARGIN} (where the course editors refuse points, GEN-SPEC §2.4).
     */
    public static boolean nearArea(GeneratedCourses g, String world, int x, int y, int z) {
        return nearBox(g, world, x, y, z, x, y, z);
    }

    /**
     * Whether any block of the box between (x1, y1, z1) and (x2, y2, z2), both included, is inside
     * a Fresh Courses half expanded by {@value #EDITOR_MARGIN}: a checkpoint with its radius, or a
     * golf hole's bounds, is kept as far away as the Fresh Courses engine keeps its halves from
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
