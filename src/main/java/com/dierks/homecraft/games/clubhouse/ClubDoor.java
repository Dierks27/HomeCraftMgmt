package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Clubhouse as the races and golf see it (CLUBHOUSE-SPEC §2, §3): the one door every flow
 * goes through, so race mode, party races, Race Night and golf together each gain only a line at
 * the place they differ, and a test passes a fake. The live door is {@link Clubhouse}'s, and it is
 * only handed out while the Clubhouse is on, built and checked ({@link Clubhouse#door}): with it off
 * or not built there is no door, and every flow does exactly what it did before.
 *
 * <p><b>One session all along.</b> A racer seated from the Clubhouse, or back in it after a race, is
 * in one world session: {@link #handOut} and {@link #takeIn} hand it from one game to the other in
 * place (nothing restored, nothing saved again), so their things are saved once and come back once.
 */
public interface ClubDoor {

    /** The Clubhouse's world: a session only ever teleports within its own world. */
    String world();

    /** Whether the player is in the Clubhouse now, in the room, and may be taken to a race from it. */
    boolean seatable(UUID player);

    /** Whether the player is in the Clubhouse as a spectator (the party's Watch): never seated. */
    boolean spectator(UUID player);

    /**
     * The player leaves the Clubhouse for a race: off the no-push team, back in adventure mode, the
     * Clubhouse's kit gone, and their session handed to {@code to} (its course {@code ref}).
     *
     * @return whether it was handed over (false: they stay in the Clubhouse)
     */
    boolean handOut(Player p, Game to, String ref);

    /** A seat that failed after {@link #handOut}: their session comes back to the Clubhouse, in the room. */
    void handBack(Player p, ClubVisits.Kind kind);

    /**
     * Take a player from another game's session (a race, a round of golf) into the Clubhouse: moved
     * to an arrival spot inside their session (the teleport's result is checked), the session handed
     * over, the Clubhouse's kit, and {@code line} to read. Never while it is closing for a restart
     * ({@link #closingForRestart}).
     *
     * @return whether they are in (false: the caller does what it always did, and sends them home)
     */
    boolean takeIn(Player p, ClubVisits.Kind kind, String line);

    /**
     * Whether the Clubhouse is closing for a restart ({@code games.restart_times}): in the restart
     * hold, from {@link ClubVisits#LAST_IN_BEFORE_RESTART} (66 s) before the restart to the end of its
     * own minute, when someone taken in could no longer get the hold's minute and be home before the
     * restart ({@link ClubVisits#closedForRestart}; nobody may be in the Clubhouse across one). A race,
     * Race Night or a golf group that would end here asks first and sends its players home instead,
     * each with its own home line; {@link #takeIn} refuses too. Earlier in the hold it is false: a race
     * or golf group already going still ends here (CLUBHOUSE-SPEC §7), and its players are warned and
     * sent home a minute later with everyone.
     */
    default boolean closingForRestart() {
        return false;
    }

    /** {@code party_after}: party racers come back here after the race. */
    boolean partyAfter();

    /** {@code race_night_after}: everyone at the track comes here at the end of Race Night. */
    boolean nightAfter();

    /** {@code golf_after}: a golf-together group comes here when its round ends. */
    boolean golfAfter();

    /**
     * An event's final result, for the board and the "Results" item.
     *
     * @param opener what "Results" opens for a player (a results screen), or {@code null} for the
     *               board's lines in chat
     */
    void result(ClubBoard.Sheet sheet, Consumer<Player> opener);

    /**
     * Race Night is over and its racers are on their way here: the podium (its top three, in the
     * night's own order, ties as the night ranked them) and "Photo time!".
     */
    void podium(List<UUID> topThree);
}
