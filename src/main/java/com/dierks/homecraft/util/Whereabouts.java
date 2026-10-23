package com.dierks.homecraft.util;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Where a player is, as the economy sees it: in a world game, home from every game, in a Games world. One
 * copy for everyone who asks (the Prize Counter, the candy shower, the admin give-backs, and from 0.38 the
 * look sweeps, the trails and the trade window), where each used to keep its own.
 */
public final class Whereabouts {

    private Whereabouts() {
    }

    /** Home from every game: no session, nothing on the way back, no saved-state row sending them back. */
    public static boolean homeFromGames(HomeCraftManagement plugin, Player player) {
        var games = plugin.games();
        return games == null || homeFromGames(games.sessions().session(player) != null, games.sessions().home(player));
    }

    /** {@link #homeFromGames(HomeCraftManagement, Player)} with the Games running: no session, and home. */
    static boolean homeFromGames(boolean inSession, boolean home) {
        return !inSession && home;
    }

    /** In a world game: a session, on its way in or out too. Never, while the Games aren't running. */
    public static boolean inGameSession(HomeCraftManagement plugin, Player player) {
        var games = plugin.games();
        return games != null && games.sessions().session(player) != null;
    }

    /** A Games world (case-insensitive): checked on its own, because an empty economy list means everywhere. */
    public static boolean gamesWorld(HomeCraftManagement plugin, World world) {
        var g = plugin.config().games();
        return world != null && g != null && gamesWorld(world.getName(), g.common().worlds());
    }

    /** {@link #gamesWorld(HomeCraftManagement, World)} on names: {@code world} is one of {@code gamesWorlds}. */
    static boolean gamesWorld(String world, Collection<String> gamesWorlds) {
        if (world == null || gamesWorlds == null) {
            return false;
        }
        for (String name : gamesWorlds) {
            if (name != null && name.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }
}
