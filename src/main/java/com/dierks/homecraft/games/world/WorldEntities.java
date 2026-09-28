package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Entities a game spawns — a golf ball, a race boat (spec R2.15, R3.8, R3.13).
 *
 * <p>They are never persistent and always carry {@link Keys#GAME_ENTITY}
 * ({@code "<gameId>:<owner>"}), so a crash can't leave one standing: anything tagged is removed
 * when the game starts and whenever a chunk loads with one in it. Deliberately NOT the effects
 * service's tag, which deletes whatever carries it.
 */
public final class WorldEntities {

    private WorldEntities() {
    }

    /** Mark {@code entity} as {@code game}'s, spawned for {@code owner}, and never saved with the chunk. */
    public static void tag(Entity entity, Game game, UUID owner) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(Keys.GAME_ENTITY, PersistentDataType.STRING,
                game.id() + ":" + (owner == null ? "" : owner.toString()));
    }

    /** The game that spawned {@code entity}, or {@code null} if no game did. */
    public static String gameId(Entity entity) {
        String tag = tag(entity);
        if (tag == null) {
            return null;
        }
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(0, colon);
    }

    /** Who {@code entity} was spawned for, or {@code null}. */
    public static UUID owner(Entity entity) {
        String tag = tag(entity);
        if (tag == null || tag.indexOf(':') < 0) {
            return null;
        }
        try {
            return UUID.fromString(tag.substring(tag.indexOf(':') + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Remove every entity {@code game} spawned, in every loaded world.
     *
     * @return how many were removed
     */
    public static int sweep(Game game) {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (game.id().equals(gameId(e))) {
                    e.remove();
                    removed++;
                }
            }
        }
        // F3: also sweep on EntitiesLoadEvent (through GamesService.on) for chunks loaded later.
        return removed;
    }

    private static String tag(Entity entity) {
        if (entity == null || Keys.GAME_ENTITY == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(Keys.GAME_ENTITY, PersistentDataType.STRING);
    }
}
