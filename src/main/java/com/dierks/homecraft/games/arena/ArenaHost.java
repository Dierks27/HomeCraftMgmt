package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.Feet;
import com.dierks.homecraft.games.arena.rules.RoundResult;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Everything {@link ArenaService} needs from the running server, in one place, so the arena's
 * rules (the gate, the reset, the claim, strays, the restart hold, collisions put back on every
 * path) run for real in tests with a fake clock, a fake world and fake players. The live host is
 * {@link FallingFloors}'; nothing here is asked off the main thread, and nothing here throws on
 * purpose (a failure is logged and treated as "can't tell").
 */
public interface ArenaHost {

    /** What a player holds, by where they are in the arena. */
    enum KitKind {
        /** In the gallery between rounds: Ready, Play solo (when offered) and Leave game. */
        LOBBY,
        /** On the floors: Leave game only. */
        ROUND,
        /** Watching a round from the gallery (out, or arrived late): Leave game only. */
        WATCH
    }

    /**
     * A kit to give.
     *
     * @param ready whether the player has pressed Ready (the item says so)
     * @param solo  whether "Play solo" is offered (alone, with solo play on)
     */
    record Kit(KitKind kind, boolean ready, boolean solo) {
    }

    /** A sound to play. */
    enum Cue {
        /** A second of the countdown or the 3-2-1. */
        COUNT,
        /** Go. */
        GO,
        /** Out of the round. */
        OUT,
        /** The round's results. */
        END
    }

    /** Now (epoch ms): the plugin's clock. */
    long now();

    /** A monotonic clock for the reset's time budget ({@code System.nanoTime()}). */
    long nanoTime();

    Logger logger();

    /** The live {@code games.falling_floors} settings. */
    FallingFloorsSettings settings();

    /** The world the arena is in ({@code games.fresh.world}, or the first Games world), or {@code ""}. */
    String worldName();

    /** The world by name, or {@code null} when it isn't loaded. */
    WorldPort world(String name);

    /** The week (a local epoch day) the arena's shape belongs to at {@code now}. */
    long weekKey(long now);

    /** The server's seed secret, or {@code null} while the database can't give it. */
    Long secret();

    /** The arena's claim ({@code gen.floors.claim}), or {@code null}. Throws when it can't be read. */
    String claim() throws Exception;

    /** Record (or, with {@code null}, forget) the arena's claim. Throws when it can't be written. */
    void claim(String value) throws Exception;

    /**
     * Why the box can't stand in {@code world}: too close to a Fresh Courses area, the kept courses
     * or a hand-built course, outside the world's height or border, or over the spawn or safe spot.
     * Empty when it can.
     */
    List<String> regionProblems(Box box, String world);

    /** Everyone online, with where their feet are and which world session they are in. */
    List<Person> people();

    /** Whether anyone is online (the reset's budget is the same either way; for status). */
    boolean anyoneOnline();

    /** Paper's average tick time, in milliseconds. */
    double mspt();

    /** Whether a scheduled restart is minutes away (no new round starts). */
    boolean holding();

    /** The restart's time for players ("4:00 PM"), or {@code null}. */
    String heldFor();

    /** Where a player's feet are now, or {@code null} when they are offline. */
    Feet feet(UUID player);

    /** The arena session's own teleport (the session's safe point becomes this spot). */
    void teleport(UUID player, String world, ArenaSite.Spot spot);

    /** Move someone who isn't in the arena's session out of the box: the safe spot, or the spawn. */
    void toSafety(UUID player, String world);

    /** End the player's arena session: their things come back. */
    void endSession(UUID player);

    /** Whether other players can push this one (a round turns it off, and every end path back on). */
    void collidable(UUID player, boolean on);

    void kit(UUID player, Kit kit);

    void tell(UUID player, String line);

    void actionBar(UUID player, String line);

    void title(UUID player, String big, String small, int stayTicks);

    void sound(UUID player, Cue cue);

    /** Show (or update) the countdown bar for a player. */
    void bar(UUID player, String title, float progress);

    /** Take the countdown bar away. */
    void hideBar(UUID player);

    /** A player's name for the results lines. */
    String name(UUID player);

    /**
     * A round was played out: its boards, its rewards and its quests
     * ({@code ArenaScoring}, paid through {@link FloorsRewards}).
     *
     * @param week the week the round's floors were made for
     */
    void scored(RoundResult result, long week);
}
