package com.dierks.homecraft.games.world;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Entities a game spawns — a golf ball, a race boat (spec R2.15, R3.8, R3.13).
 *
 * <p>They are never persistent and always carry {@link Keys#GAME_ENTITY}
 * ({@code "<gameId>:<owner>"}), so a crash can't leave one standing: anything tagged is removed
 * when the game starts, once the worlds are up, and whenever a chunk loads with one in it (a
 * non-persistent entity is never saved, so one that loads is always a leftover). Deliberately NOT
 * the effects service's tag, which deletes whatever carries it — and an entity carrying that tag
 * is never touched here.
 *
 * <p>The session guard also keeps them whole: nobody can damage, break, enter (but the owner) or
 * dress a tagged entity, so a boat can never become a boat item.
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
     * Remove every entity {@code game} spawned, in every loaded world (a game calls this from its
     * {@code start()}).
     *
     * @return how many were removed
     */
    public static int sweep(Game game) {
        return sweepWhere(e -> game.id().equals(gameId(e)));
    }

    /**
     * Remove every game's entities except those of a player still playing, in every loaded world
     * (the worlds-up pass).
     *
     * @param playing whether an owner is still in a session (their entities stay)
     * @return how many were removed
     */
    static int sweepAll(Predicate<UUID> playing) {
        return sweepWhere(e -> {
            if (gameId(e) == null) {
                return false;
            }
            UUID owner = owner(e);
            return owner == null || !playing.test(owner);
        });
    }

    /**
     * Remove the game entities of a chunk that just loaded (EntitiesLoadEvent): a live one is never
     * saved, so any that loads is a leftover.
     *
     * @return how many were removed
     */
    static int removeLoaded(Collection<Entity> entities) {
        int removed = 0;
        for (Entity e : entities) {
            if (gameId(e) != null && !effect(e)) {
                e.remove();
                removed++;
            }
        }
        return removed;
    }

    private static int sweepWhere(Predicate<Entity> match) {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!effect(e) && match.test(e)) {
                    e.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Whether the effects service owns it: never ours to remove. */
    private static boolean effect(Entity e) {
        return Keys.EFFECT_ENTITY != null && e.getPersistentDataContainer().has(Keys.EFFECT_ENTITY);
    }

    private static String tag(Entity entity) {
        if (entity == null || Keys.GAME_ENTITY == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(Keys.GAME_ENTITY, PersistentDataType.STRING);
    }
}
