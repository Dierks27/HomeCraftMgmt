package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Everything {@link ClubhouseRoom} needs from the running server, so the room's rules (the claim,
 * the scan of an unclaimed box, the converge-and-verify, a failed verify closing it, the owner-built
 * spots) run for real in tests with a fake world. Nothing here is asked off the main thread, and
 * nothing throws on purpose but the database reads that say so.
 */
public interface RoomHost {

    /** Now (epoch ms): the plugin's clock. */
    long now();

    /** A monotonic clock for the build's time budget ({@code System.nanoTime()}). */
    long nanoTime();

    Logger logger();

    /** The Games world ({@code games.fresh.world}, or the first of {@code games.worlds}), or {@code ""}. */
    String worldName();

    /** The world by name, or {@code null} when it isn't loaded. */
    WorldPort world(String name);

    /** A stored value ({@code hcm_meta}), or {@code null}. Throws when it can't be read. */
    String meta(String key) throws Exception;

    /** Store (or, with {@code null}, forget) a value. Throws when it can't be written. */
    void meta(String key, String value) throws Exception;

    /**
     * Why the box can't stand in {@code world}: too close to a Fresh Courses area, the kept courses,
     * the arena or a hand-built course, outside the world's height or border, or over the spawn or
     * safe spot. Empty when it can.
     */
    List<String> regionProblems(Box box, String world);

    /** Everyone online, with where their feet are and which world session they are in. */
    List<Person> people();

    boolean anyoneOnline();

    /** Paper's average tick time, in milliseconds. */
    double mspt();

    /** Move someone who isn't in the Clubhouse out of the box (the safe spot, or the spawn). */
    void toSafety(UUID player, String world);
}
