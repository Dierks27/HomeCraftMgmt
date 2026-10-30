package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Scores;
import com.dierks.homecraft.games.clubhouse.ClubData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Time Trials as the Clubhouse's boards see it (0.38.3): read-only, from what is in memory, on the main thread.
 * Who is on which course now and how far along (Now playing), and the open courses with the boards their times go
 * on (this week's best and My stats, read from the database elsewhere: {@link ClubData}). Nothing here changes a
 * run, reads the database or moves anything.
 */
public final class TrialsNow {

    private TrialsNow() {
    }

    /**
     * One live run.
     *
     * @param course      the course's name
     * @param reached     checkpoints reached
     * @param checkpoints the course's checkpoints (0: none)
     * @param ms          the clock, 0 while it isn't running
     * @param starting    the 3-2-1
     * @param warmup      in its warm-up (not timed)
     * @param racing      a race (a party race or Race Night)
     * @param done        finished (or parked on a race's stand)
     * @param rings       an elytra course, whose checkpoints are rings ("ring 7" on the boards)
     */
    public record Run(UUID player, String course, int reached, int checkpoints, long ms, boolean starting,
                      boolean warmup, boolean racing, boolean done, boolean rings) {

        /** A run on a course whose checkpoints are called checkpoints. */
        public Run(UUID player, String course, int reached, int checkpoints, long ms, boolean starting,
                   boolean warmup, boolean racing, boolean done) {
            this(player, course, reached, checkpoints, ms, starting, warmup, racing, done, false);
        }
    }

    /** Every live run now; none while Time Trials is off. */
    public static List<Run> runs(GamesService games) {
        TimeTrials t = trials(games);
        if (t == null) {
            return List.of();
        }
        long nanos = System.nanoTime();
        List<Run> out = new ArrayList<>();
        for (TrialRun r : new ArrayList<>(t.liveRuns())) {
            Progress p = r.progress;
            out.add(new Run(r.player, r.course.name(), p == null ? 0 : p.reachedCheckpoints(),
                    p == null ? r.course.checkpoints().size() : p.checkpoints(), r.running() ? r.elapsedMs(nanos) : 0,
                    r.phase == TrialRun.Phase.COUNTDOWN && !r.warmup, r.warmup, r.race != null,
                    r.phase == TrialRun.Phase.DONE || r.phase == TrialRun.Phase.PARKED,
                    r.course.kind() == TrialKind.ELYTRA));
        }
        return out;
    }

    /**
     * The open courses (the Games screen's order) and their boards: a hand-built course's all-time board and this
     * week's; a Fresh course's set board for both, all this week's only when its edition began this week.
     */
    public static List<ClubData.CourseBoards> boards(GamesService games) {
        TimeTrials t = trials(games);
        if (t == null) {
            return List.of();
        }
        long week = t.weekKey();
        List<ClubData.CourseBoards> out = new ArrayList<>();
        for (Course c : t.openCourses()) {
            String record = TimeTrials.board(c);
            if (c.generated()) {
                boolean thisWeek = c.gen().recall() == null && c.gen().day() >= week;
                out.add(new ClubData.CourseBoards(c.id(), c.name(), record, record, thisWeek));
            } else {
                out.add(new ClubData.CourseBoards(c.id(), c.name(), record, Scores.week(c.id(), week), true));
            }
        }
        return out;
    }

    /** This week's key (the week boards' and the weekly quests' week: a local epoch day), or -1 while it is off. */
    public static long weekKey(GamesService games) {
        TimeTrials t = trials(games);
        return t == null ? -1 : t.weekKey();
    }

    private static TimeTrials trials(GamesService games) {
        Game g = games == null ? null : games.game(TimeTrials.SPEC.id());
        return g instanceof TimeTrials t && games.enabled(t) ? t : null;
    }
}
