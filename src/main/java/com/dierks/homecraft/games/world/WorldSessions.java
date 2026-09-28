package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * World sessions: taking a player into a world game and bringing them back exactly as they were
 * (spec §7, R2, R3.7).
 *
 * <p>The order of every step is the design. Entering: close the inventory, teleport, THEN save
 * the player's state (after the teleport, so Multiverse-Inventories has already swapped in the
 * Games world's inventory), THEN clear and give the kit. Leaving: strip the kit, restore in place,
 * mark the row RETURN in the same tick, THEN teleport back, THEN delete the row. A restore
 * overwrites and never adds, a RETURN row is never applied twice, and the row is deleted only
 * after the player is home — so a quit, a crash or a stop at any point leaves something the next
 * join can finish, and nothing can be duplicated.
 *
 * <p>World sessions never change attributes, walk or fly speed, invulnerability or collisions.
 * At most one session per player; a player with any saved-state row (even one still sending them
 * back) can't enter another.
 */
public final class WorldSessions {

    private final GamesService games;

    public WorldSessions(GamesService games) {
        this.games = games;
    }

    /**
     * Take the player into {@code game} at {@code start}. Refused (false, player told) when they
     * are already in a session or have a saved-state row, are dead, asleep, riding, gliding,
     * falling, burning, in water or lava, or were hurt in the last 5 seconds.
     *
     * @param ref     the course (or other) the session is for
     * @param onReady run once the player is in, saved, cleared and kitted
     * @return whether the entry started
     */
    public boolean enter(Player player, Game game, String ref, Location start, Consumer<Player> onReady) {
        // F3: the enter sequence (R2.2, R2.9).
        return false;
    }

    /** End the player's session: strip the kit, restore, send them back (R2.3). */
    public void leave(Player player, EndReason reason) {
        // F3
    }

    /** The player's live session, or {@code null} when not in one. */
    public Session session(Player player) {
        // F3
        return null;
    }

    /**
     * Teleport a session player as part of the game (a checkpoint, "go to my ball"). The exact
     * destination is recorded so the guard knows this teleport is ours (R2.8).
     *
     * @return whether the teleport was started
     */
    public boolean teleport(Player player, Location to) {
        // F3
        return false;
    }

    /** Run {@code action}, a dismount the game makes itself (re-seating in a boat), past the guard. */
    public void ownDismount(Player player, Runnable action) {
        // F3: flag the dismount as ours around the action.
        action.run();
    }
}
