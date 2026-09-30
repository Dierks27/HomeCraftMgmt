package com.dierks.homecraft.games.golf;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx2-C #4: a hole starts only once the chunks it reads (its tee's and its cup's) are loaded, and
 * never by loading one on the main thread: at once when they are there (the player stands at the
 * tee), else after asynchronous loads. "Play again together" from the Clubhouse starts hole 1 while
 * the player's teleport is still on its way, 400 blocks from a tee nobody has loaded.
 */
class HoleChunksTest {

    /** A world whose loaded chunks the test says, and whose async loads it completes by hand. */
    private static final class World implements GolfRounds.ChunkLoader {
        final Set<Long> loaded = new HashSet<>();
        final List<long[]> asked = new ArrayList<>();
        final List<Runnable> pending = new ArrayList<>();

        static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xffffffffL);
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return loaded.contains(key(cx, cz));
        }

        @Override
        public void loadAsync(int cx, int cz, Runnable done) {
            asked.add(new long[]{cx, cz});
            pending.add(() -> {
                loaded.add(key(cx, cz));
                done.run();
            });
        }

        void finishLoads() {
            List<Runnable> now = new ArrayList<>(pending);
            pending.clear();
            now.forEach(Runnable::run);
        }
    }

    /** A hole with its tee at (tx, tz) and its cup at (cx, cz). */
    private static GolfCourse.Hole hole(double tx, double tz, int cx, int cz) {
        return new GolfCourse.Hole(new GolfCourse.Tee(tx, 64, tz, 0f), new GolfCourse.Spot(cx, 63, cz), 3,
                new GolfCourse.Spot(Math.min((int) tx, cx) - 4, 60, Math.min((int) tz, cz) - 4),
                new GolfCourse.Spot(Math.max((int) tx, cx) + 4, 70, Math.max((int) tz, cz) + 4));
    }

    private final World world = new World();
    private int started;

    @Test
    void aHoleWhoseChunksAreLoadedStartsAtOnceWithNothingLoaded() {
        GolfCourse.Hole h = hole(5120.5, 4100.5, 5140, 4110);
        for (int[] c : GolfRounds.holeChunks(h)) {
            world.loaded.add(World.key(c[0], c[1]));
        }
        GolfRounds.whenLoaded(world, h, () -> started++);
        assertEquals(1, started, "the player is at the tee: the hole starts now, as before");
        assertTrue(world.asked.isEmpty(), "and no chunk is asked for");
    }

    @Test
    void aTeeNobodyHasLoadedStartsOnlyOnceItsChunkHasComeInOffTheMainThread() {
        GolfCourse.Hole h = hole(5120.5, 4100.5, 5140, 4110);
        GolfRounds.whenLoaded(world, h, () -> started++);
        assertEquals(0, started, "the ball isn't placed on a tee whose chunk isn't there yet");
        assertEquals(2, world.asked.size(), "the tee's and the cup's chunks are loaded asynchronously");
        world.finishLoads();
        assertEquals(1, started, "once both are in, the hole starts, once");
        world.finishLoads();
        assertEquals(1, started, "and never twice");
    }

    @Test
    void aTeeAndCupInOneChunkAskForItOnce() {
        GolfCourse.Hole h = hole(5121.5, 4097.5, 5125, 4101);
        assertEquals(1, GolfRounds.holeChunks(h).size(), "one chunk holds both");
        GolfRounds.whenLoaded(world, h, () -> started++);
        assertEquals(1, world.asked.size(), "asked for once");
        world.finishLoads();
        assertEquals(1, started, "then the hole starts");
    }

    @Test
    void golfNeverLoadsAChunkOnTheMainThread() throws IOException {
        String code = Files.readString(Path.of("src/main/java/com/dierks/homecraft/games/golf/GolfRounds.java"));
        assertFalse(code.contains(".getChunkAt("), "World.getChunkAt blocks the main thread on a disk load: a hole"
                + " waits for getChunkAtAsync instead");
    }
}
