package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * A race track's chunks, loaded off the main thread before its blocks are read (round 2, G2 #4; the
 * bug class of fx2-C #4, which fixed golf's tee and cup).
 *
 * <p>Why: a party race's Start, or "Race again!" from the Clubhouse (56 chunks from Fresh Ice Boat), reads
 * the boat grid's path up to {@link RaceGrid#REACH} blocks behind the start and the viewing stand in the
 * middle of the half, and Race Night's schedule reads the same when it makes a night. Those chunks are
 * rarely loaded then, and {@code World.getBlockAt} would load each one synchronously on the main thread.
 * So the reads go through a {@link WorldSurface} that never loads one and says which it needed; the
 * caller loads those here, asynchronously, and reads again once they are in. The track's first read
 * needs at most a few chunks; a read after that one can reach a chunk more (a spot nudged across a
 * chunk edge), so a caller tries up to {@link #MAX_WAITS} times. Pure but for {@link #server}, the thin
 * piece over Paper.
 */
public final class TrackChunks {

    /**
     * How many rounds of loads a Start waits for before it gives up with "try again in a moment": each
     * loads what the last read needed and wasn't loaded (a chunk can also be unloaded again meanwhile).
     */
    public static final int MAX_WAITS = 4;

    private TrackChunks() {
    }

    /** The chunks a read needs, seen through the least of a world, so the rule is tested without a server. */
    public interface Loader {
        /** Whether the chunk is in memory now. */
        boolean loaded(int cx, int cz);

        /** Load the chunk off the main thread; {@code done} runs on the main thread once it is in (or failed). */
        void loadAsync(int cx, int cz, Runnable done);
    }

    /** One number for chunk ({@code cx}, {@code cz}). */
    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** The chunk {x, z} of a {@link #key}. */
    static int[] chunk(long key) {
        return new int[]{(int) (key >> 32), (int) key};
    }

    /**
     * Run {@code then} once every chunk of {@code chunks} is loaded: at once when they are, else after each
     * missing one has come in asynchronously, once. A chunk is never loaded here on the main thread.
     */
    public static void whenLoaded(Loader loader, List<int[]> chunks, Runnable then) {
        List<int[]> missing = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int[] c : chunks) {
            if (seen.add(key(c[0], c[1])) && !loader.loaded(c[0], c[1])) {
                missing.add(c);
            }
        }
        if (missing.isEmpty()) {
            then.run();
            return;
        }
        int[] left = {missing.size()};
        for (int[] c : missing) {
            loader.loadAsync(c[0], c[1], () -> {
                if (--left[0] == 0) {
                    then.run();
                }
            });
        }
    }

    /**
     * What a Start does with a read of the track ({@code missing}: the chunks it needed that weren't loaded)
     * after {@code waited} rounds of loads: go on with it, wait for those chunks and read again, or give up.
     */
    public enum Next {
        /** Every block was read from a loaded chunk: the grid and the stand hold. */
        GO,
        /** Load the missing chunks, then read again. */
        WAIT,
        /** The chunks didn't stay in: "try again in a moment" (never a synchronous load). */
        GIVE_UP
    }

    /** {@link Next} for a read that needed {@code missing} chunks after {@code waited} rounds of loads. */
    public static Next next(List<int[]> missing, int waited) {
        return missing.isEmpty() ? Next.GO : waited < MAX_WAITS ? Next.WAIT : Next.GIVE_UP;
    }

    /**
     * {@link Loader} on the server: Paper's async load, back on the main thread inside {@code game}'s
     * guard, and only while {@code game} is still running.
     */
    public static Loader server(World world, GamesService games, Game game) {
        return new Loader() {
            @Override
            public boolean loaded(int cx, int cz) {
                return world.isChunkLoaded(cx, cz);
            }

            @Override
            public void loadAsync(int cx, int cz, Runnable done) {
                world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
                    Runnable back = () -> {
                        if (games.enabled(game)) { // the game may have stopped meanwhile: nothing then
                            games.guard(game, done);
                        }
                    };
                    if (Bukkit.isPrimaryThread()) {
                        back.run();
                    } else if (games.plugin() != null && games.plugin().isEnabled()) {
                        Bukkit.getScheduler().runTask(games.plugin(), back);
                    }
                });
            }
        };
    }
}
