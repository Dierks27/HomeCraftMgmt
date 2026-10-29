package com.dierks.homecraft.games.clubhouse;

import java.util.UUID;

/**
 * Where a course's or a round of golf's Start goes, and whether its end comes back to the Clubhouse
 * (0.38.3, the owner: "Can you make it so we go to the clubhouse when click into a game?"): the one place
 * the fallbacks are decided, pure over the Clubhouse's door, so every start path (the course screen, the
 * warm-up choice, Today's pick, a join sign, {@code /hcm play <course>}, Play again) and every end behaves
 * the same, and a test walks them all.
 *
 * <p><b>Off means exactly 0.38.2.</b> The start goes straight to the course, and the end goes home, when:
 * <ul>
 *   <li>the Clubhouse's door is {@code null} (switched off, still building, its check failed);</li>
 *   <li>the course is in another world (a session teleport never leaves its world);</li>
 *   <li>the restart hold is on (the start only: a start would be refused anyway), or the Clubhouse is closing
 *       for the restart (the end: earlier in the hold a finish still comes in and is sent home a minute later,
 *       warned, as a race's end is);</li>
 *   <li>a ride is booked (the driver has a rider, or the player rides with someone);</li>
 *   <li>it is an admin's test run;</li>
 *   <li>the player is in some other world session;</li>
 *   <li>or {@code solo_first} (the start) or {@code solo_after} (the end) is off.</li>
 * </ul>
 * A player already in the Clubhouse is always handed out from it, whatever {@code solo_first} says: it is
 * the only way on from there.
 */
public final class ClubRoute {

    private ClubRoute() {
    }

    /** Where a course's or a round's Start goes. */
    public enum Way {
        /** The player is in the Clubhouse now: their session is handed to the game in place (no trip home). */
        HAND_OUT,
        /** Through the Clubhouse first: "Off to Sky Rings!", a countdown, then on to it ({@code solo_first}). */
        LOBBY,
        /** Straight to the start, exactly as 0.38.2. */
        DIRECT
    }

    /**
     * Where a Start goes.
     *
     * @param club        the Clubhouse's door, {@code null} while it is off, closed or not built
     * @param player      who starts
     * @param courseWorld the course's world (a session teleport never leaves its world)
     * @param test        an admin's test run
     * @param rider       a ride is booked (the driver has a rider, or the player rides with someone)
     * @param inSession   the player is in some world session now
     * @param restartHeld the restart hold is on ({@code GamesService.restartHeld() != null})
     */
    public static Way start(ClubDoor club, UUID player, String courseWorld, boolean test, boolean rider,
                            boolean inSession, boolean restartHeld) {
        if (club == null || test || player == null) {
            return Way.DIRECT;
        }
        if (club.seatable(player)) {
            return Way.HAND_OUT; // in the Clubhouse: the only way on is a hand-out
        }
        if (inSession || rider || restartHeld || !club.soloFirst() || club.closingForRestart()
                || !sameWorld(club, courseWorld)) {
            return Way.DIRECT;
        }
        return Way.LOBBY;
    }

    /**
     * Whether a solo run's finish, or a round alone's end, comes back to the Clubhouse ({@code solo_after}).
     * The caller still asks {@link ClubDoor#closingForRestart()} itself, to say why it goes home.
     */
    public static boolean after(ClubDoor club, String world, boolean test, boolean rider) {
        return club != null && club.soloAfter() && !test && !rider && sameWorld(club, world);
    }

    /**
     * Whether Leave game partway through a run or a round goes back to the Clubhouse: only one that was
     * handed out from it ({@code fromClub}), never while it is closing for the restart (home, as before).
     */
    public static boolean leaveBack(ClubDoor club, String world, boolean fromClub, boolean test, boolean rider) {
        return club != null && fromClub && !test && !rider && sameWorld(club, world) && !club.closingForRestart();
    }

    /** Whether {@code world} is the Clubhouse's (a blank or unknown world never is). */
    public static boolean sameWorld(ClubDoor club, String world) {
        return club != null && world != null && !world.isBlank() && world.equalsIgnoreCase(club.world());
    }
}
