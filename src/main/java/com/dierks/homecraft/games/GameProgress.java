package com.dierks.homecraft.games;

import org.bukkit.entity.Player;

/**
 * What the skill games tell the rest of the plugin when a player finishes something: quests and
 * achievements listen here (spec EXTRAS E4). The games call it at their finish sites; the Arcade
 * registers the one listener ({@link GamesService#progress(GameProgress)}).
 *
 * <p>Games of chance NEVER call it: nothing outside a game of chance may reward playing one.
 * Every method has an empty default, so {@link #NONE} (the value until something registers) does
 * nothing, and a listener implements only what it needs. The service guards every call, so a
 * listener that throws can't break a game.
 */
public interface GameProgress {

    /** Nothing listens. */
    GameProgress NONE = new GameProgress() {
    };

    /**
     * A cabinet game ran to its end (Classic or today's board, practice included). A friend game
     * someone quit, or a run closed early, is not a finish.
     *
     * @param goldMedal this run reached the board's gold milestone
     */
    default void cabinetFinished(Player player, String gameId, boolean practice, boolean goldMedal) {
    }

    /**
     * A counted time-trial run finished (hand-built, Fresh or Classic).
     *
     * @param record the run set the course's record
     */
    default void courseFinished(Player player, String courseId, boolean fresh, boolean record) {
    }

    /** A golf round was finished (every hole played). */
    default void golfFinished(Player player, String courseId, int strokes, int par, int holesInOne, boolean fresh) {
    }

    /** Fresh Courses stars were added to this week's chart. */
    default void starsEarned(Player player, int stars) {
    }

    /** The player has now finished every Fresh course of the current set. */
    default void freshSetFinished(Player player, String set) {
    }

    /** The player reached this week's top Star Chart goal. */
    default void starChartTopGoal(Player player, long week) {
    }
}
