package com.dierks.homecraft.games.clubhouse;

/**
 * Where a player in the Clubhouse's lobby goes on to (0.38.3, the owner: "go to the clubhouse when click into a
 * game"): a course or a round of golf, by its game, its id and its name, and whether they chose a warm-up first
 * (the warm-up screen still comes before the trip, and its choice is carried across it).
 *
 * @param gameId the game it is ({@code trials} or {@code golf})
 * @param ref    the course's id
 * @param name   the course's name, for "Off to Sky Rings!" and "Go now: Sky Rings"
 * @param warmUp the player chose "Warm up" on the warm-up screen before the trip
 */
public record ClubNext(String gameId, String ref, String name, boolean warmUp) {
}
