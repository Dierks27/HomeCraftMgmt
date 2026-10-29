package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.trial.PartyRace;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The family week's rules (0.38.4; the owner: a family scoreboard of the week with a podium ceremony): what each
 * thing a player does is worth, the daily cap, which boards the points live on, who won a party race, and when
 * the week's ceremony runs. Pure, so every rule is tested without a server; {@link FamilyWeek} applies them.
 *
 * <p><b>Points.</b> A finished run {@value #RUN}, a personal best {@value #PERSONAL_BEST}, the week's best
 * {@value #WEEK_BEST}, a record {@value #RECORD}, a party race win {@value #PARTY_WIN}, a round of golf
 * {@value #GOLF_ROUND}, a hole-in-one {@value #HOLE_IN_ONE}, a cheer given {@value #CHEER}; at most
 * {@value #DAY_CAP} a day per player, so the daughter can keep up and nothing can be farmed.
 *
 * <p><b>The week</b> is the wall boards' week: local midnight on {@code games.quests.week_starts}, NOT the
 * Weekly Cup's 04:00 week. Its key is the local epoch day it started ({@code GameClock.weekKey}).
 *
 * <p><b>The ceremony</b> runs once per week, the first time at least two of last week's top three are in the
 * room, and only while nobody is being sent home for a restart and no other Photo is waiting. The marker
 * ({@code gen.clubhouse.week.done}) is the week key it last ran, or was skipped, for: written before the podium,
 * so a crash in between never repeats it, and kept across restarts.
 */
public final class FamilyWeekRules {

    public static final int RUN = 1;
    public static final int PERSONAL_BEST = 2;
    public static final int WEEK_BEST = 3;
    public static final int RECORD = 5;
    public static final int PARTY_WIN = 3;
    public static final int GOLF_ROUND = 1;
    public static final int HOLE_IN_ONE = 3;
    public static final int CHEER = 1;
    /** At most this many points a day per player. */
    public static final long DAY_CAP = 10;
    /** The ceremony waits this long (ms) for last week's top three to come in; then the week is let go. */
    public static final long CEREMONY_MS = 7L * 24 * 60 * 60 * 1000;

    private FamilyWeekRules() {
    }

    // ---- points ------------------------------------------------------------------------------------

    /** A counted run's points: the run, plus a personal best, the week's best and a record as they apply. */
    public static int runPoints(boolean personalBest, boolean weekBest, boolean record) {
        return RUN + (personalBest ? PERSONAL_BEST : 0) + (weekBest ? WEEK_BEST : 0) + (record ? RECORD : 0);
    }

    /** A recorded round's points: the round, plus a personal best, a record, and each hole-in-one. */
    public static int golfPoints(boolean personalBest, boolean record, int holesInOne) {
        return GOLF_ROUND + (personalBest ? PERSONAL_BEST : 0) + (record ? RECORD : 0)
                + Math.max(0, holesInOne) * HOLE_IN_ONE;
    }

    /** How much of {@code delta} fits under {@code cap} with {@code today} already counted: never below 0. */
    public static long capped(long delta, long today, long cap) {
        return Math.max(0, Math.min(delta, cap - today));
    }

    // ---- boards ------------------------------------------------------------------------------------

    /** The week's points board: {@code club:<weekKey>}. */
    public static String boardOf(long weekKey) {
        return "club:" + weekKey;
    }

    /** A day's points board (the cap): {@code clubday:<dayKey>}. */
    public static String dayBoard(long dayKey) {
        return "clubday:" + dayKey;
    }

    /** The week's race wins board: {@code clubwins:<weekKey>} (a count, never capped: it isn't points). */
    public static String winsBoard(long weekKey) {
        return "clubwins:" + weekKey;
    }

    /**
     * Who won a party race: the first FINISHED line of the ranked results, when at least two raced; {@code null}
     * for a race alone, a race nobody finished, or no lines.
     */
    public static UUID partyWinner(List<PartyRace.Line> lines, int racers) {
        if (lines == null || racers < 2) {
            return null;
        }
        for (PartyRace.Line l : lines) {
            if (l.result() == PartyRace.Result.FINISHED) {
                return l.id();
            }
        }
        return null;
    }

    // ---- the ceremony ------------------------------------------------------------------------------

    /** What the week's check says to do now. */
    public enum Turn {
        /** Nothing: this week is done, or the ceremony is still waiting for its three. */
        NONE,
        /** Mark this week done without a ceremony: the first week ever, or last week had nobody. */
        DONE_NOW,
        /** Run it: the podium, the title, the firework, the chat line; then mark the week done. */
        CEREMONY,
        /** Its time ran out: mark the week done. */
        EXPIRED
    }

    /**
     * The ceremony's decision.
     *
     * @param weekKey         this week's key
     * @param doneKey         the marker: the week last done, or {@code null} when there never was one
     * @param now             the clock (ms)
     * @param weekStartMs     when this week began (ms)
     * @param lastWeekRows    how many of last week's top three there are at all (0: nobody played)
     * @param lastWeekTopHere how many of them are in the room now
     * @param closing         whether the room is sending everyone home for a restart
     * @param photoPending    whether another Photo is waiting for its podium
     */
    public static Turn turn(long weekKey, String doneKey, long now, long weekStartMs, int lastWeekRows,
                            int lastWeekTopHere, boolean closing, boolean photoPending) {
        if (doneKey != null && doneKey.equals(Long.toString(weekKey))) {
            return Turn.NONE;
        }
        if (doneKey == null || lastWeekRows <= 0) {
            return Turn.DONE_NOW;
        }
        if (now >= weekStartMs + CEREMONY_MS) {
            return Turn.EXPIRED;
        }
        if (lastWeekTopHere >= 2 && !closing && !photoPending) {
            return Turn.CEREMONY;
        }
        return Turn.NONE;
    }

    /** "&amp;6Last week: &amp;f1. Sam, 2. Lee, 3. Ana": the chat line, in podium order. */
    public static String lastLine(List<String> names) {
        List<String> parts = new ArrayList<>();
        int n = 1;
        for (String name : names == null ? List.<String>of() : names) {
            parts.add(n++ + ". " + ClubBoardText.name(name));
        }
        return "&6Last week: &f" + String.join(", ", parts);
    }
}
