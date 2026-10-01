package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlannedCourse;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.LiveBlocks;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.storage.GamesDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The one place a kept course meets the course games' own classes (GEN-SPEC-KEEP §4 steps 1 and 5):
 * the id rules, the row, the checks and the board a kept course's records go on.
 *
 * <p>Why one small adapter: Time Trials and Mini Golf own their courses, and their classes may
 * change under a later pass. A kept course is simply a NORMAL row — game {@code trials} (kind and
 * tier from the plan) or {@code golf}, with no {@code gen:} block — so it behaves exactly like a
 * hand-built one from then on: its admin tools edit it, edits bump its {@code rev}, and it has the
 * normal boards and rewards. Everything here goes through the games' public API
 * ({@code Course}, {@code CourseCodec}, {@code GolfCourse}, golf's {@code CourseCodec},
 * {@code GameCatalog}, {@code Scores}) and nothing else, so a change there touches only this file.
 * The one thing a kept golf course carries from its plan is the rules it was proven by: kept from
 * an Adventure Golf layout, it says so ({@link GolfCourse#adventure}), so its sand stays sand.
 */
public final class KeptCourses {

    /** Words the golf course tool keeps for itself ({@code /hcm games golf create|list|help}, and "auto"). */
    static final List<String> GOLF_WORDS = List.of("create", "list", "help", "auto");

    private KeptCourses() {
    }

    /**
     * Why {@code id} can't name a kept course, or {@code null} when it can: the SAME rules as a
     * hand-built course — the id's shape, not a game's id or one of its other names, not a word
     * {@code /hcm play} or {@code /hcm games feature} keeps ("auto"), not anything that already opens
     * with {@code /hcm play}, and not an existing course.
     *
     * @param golf      whether it will be a golf course (golf's tool keeps a few more words)
     * @param playTaken whether {@code /hcm play} already resolves the id (a game, an alias, a course)
     * @param exists    whether any world game already has a course called it
     */
    public static String idProblem(String id, boolean golf, Predicate<String> playTaken, boolean exists) {
        String k = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (golf ? GolfCourse.idProblem(k) != null || k.length() < 2 : !TrialText.validId(k)) {
            return "A course id is 2-32 lower-case letters, digits or _, starting with a letter.";
        }
        if (GameCatalog.taken(k)) {
            return "'" + k + "' is a game's id, or a word /hcm play keeps for itself.";
        }
        if (TrialText.keptWord(k) || (golf && GOLF_WORDS.contains(k))) {
            return "'" + k + "' is a word the course tools keep for themselves.";
        }
        if (exists) {
            return "There's already a course called '" + k + "'.";
        }
        if (playTaken != null && playTaken.test(k)) {
            return "'" + k + "' already opens something with /hcm play.";
        }
        return null;
    }

    /**
     * The row of a kept course: the planned course already moved into its plot, as a NORMAL course
     * (no {@code gen:} block), open, at layout 1.
     */
    public static GamesDao.CourseRow row(String id, String name, String world, PlannedCourse moved, long now) {
        return row(id, name, world, moved, false, now);
    }

    /**
     * The row of a course kept from {@code moved} (its plan, already moved into its plot):
     * {@link #row(String, String, String, PlannedCourse, long)}, and a golf course planned at a
     * version that plays Adventure Golf's rules keeps playing by them ({@link GolfCourse#adventure}:
     * its sand stays sand, as its par and proofs were worked out; Course Variety review).
     */
    public static GamesDao.CourseRow row(String id, String name, String world, Plan moved, long now) {
        return row(id, name, world, moved.course(), LiveBlocks.sandPlays(moved.algo()), now);
    }

    private static GamesDao.CourseRow row(String id, String name, String world, PlannedCourse moved,
                                          boolean adventure, long now) {
        if (moved instanceof PlannedGolf g) {
            GolfCourse c = new GolfCourse(id, name, world, true, 1, g.course().holes(), null, adventure);
            return com.dierks.homecraft.games.golf.CourseCodec.toRow(c, now, now);
        }
        Course p = ((PlannedTrial) moved).course();
        Course c = new Course(id, p.kind(), name, p.tier(), world, p.start(), p.checkpoints(), p.finish(), p.fallY(),
                p.minSeconds(), true, false, 1);
        return new GamesDao.CourseRow(c.id(), Slots.GAME_TRIALS, c.kind().id(), c.name(), c.world(), true,
                CourseCodec.encode(c), 1, now, now);
    }

    /**
     * What the course games' own checks say about a kept course's row, read back as they would read
     * it: empty when it opens like any hand-built course (a start and a finish in a listed world; or
     * every hole complete, its tee and cup inside its bounds, par 2-6).
     */
    public static List<String> problems(GamesDao.CourseRow row, List<String> gamesWorlds) {
        List<String> out = new ArrayList<>();
        try {
            if (Slots.GAME_GOLF.equals(row.game())) {
                GolfCourse g = com.dierks.homecraft.games.golf.CourseCodec.fromRow(row);
                if (g.generated()) {
                    out.add("it still has a gen block");
                }
                out.addAll(g.problems(gamesWorlds));
                return out;
            }
            CourseCodec.Decoded d = CourseCodec.decode(row.id(), row.data());
            out.addAll(d.problems());
            if (d.course() == null) {
                out.add("it can't be read back");
                return out;
            }
            if (d.course().generated()) {
                out.add("it still has a gen block");
            }
            if (!d.course().enabled()) {
                out.add("it reads back closed");
            }
            out.addAll(d.course().problems(gamesWorlds));
        } catch (RuntimeException e) {
            out.add("it can't be read back (" + e.getMessage() + ")");
        }
        return out;
    }

    /** The all-time board a kept course's records go on: {@code course:<id>} or {@code golf:<id>}. */
    public static String board(String game, String id) {
        return Slots.GAME_GOLF.equals(game) ? Scores.golf(id) : Scores.course(id);
    }

    /** A name as typed, made safe (colour codes out, at most 32 characters); the id's own name when empty. */
    public static String name(String typed, String id) {
        String clean = TrialText.cleanName(typed == null ? null : typed.replace("\"", ""));
        return clean == null ? TrialText.defaultName(id) : clean;
    }
}
