package com.dierks.homecraft.games.gen.api;

/**
 * The course a plan makes, as the engine that runs it sees it (GEN-SPEC §4.0): a time trial or a
 * golf course, ready to become an ordinary {@code game_courses} row at the flip.
 */
public sealed interface PlannedCourse permits PlannedTrial, PlannedGolf {
}
