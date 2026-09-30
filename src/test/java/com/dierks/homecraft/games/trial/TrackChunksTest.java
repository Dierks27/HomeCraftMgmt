package com.dierks.homecraft.games.trial;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
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
 * Round-2 audit, G2 #4 (fx2-C #4's bug class): a race's grid and stand are read from the live blocks,
 * and a read never loads a chunk on the main thread. A party race's Start (or "Race again!") from the
 * Clubhouse, 56 chunks from Fresh Ice Boat, read the boat grid's path up to 48 blocks behind the start
 * and the stand in the middle of the half, all in chunks nobody had loaded: each one a synchronous
 * load of 5-50 ms on the host's click. Race Night's nightly pick read them the same way.
 *
 * <p>Now a read notes the chunks it needed ({@link WorldSurface#missing}), the Start loads them
 * asynchronously ({@link TrackChunks#whenLoaded}) and reads again, and the grid it then seats is the
 * one the blocks give.
 */
class TrackChunksTest {

    /**
     * A world whose loaded chunks the test says and whose async loads it completes by hand; its blocks are
     * {@code blocks} (ice where they are solid, everything else air). Every read of a block in a chunk
     * that isn't loaded is a synchronous load, noted.
     */
    private static final class Server implements TrackChunks.Loader {
        final Set<Long> loaded = new HashSet<>();
        final List<String> syncLoads = new ArrayList<>();
        final List<long[]> asked = new ArrayList<>();
        final List<Runnable> pending = new ArrayList<>();
        final RaceGrid.Surface blocks;

        Server(RaceGrid.Surface blocks) {
            this.blocks = blocks;
        }

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

        World world() {
            return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getMinHeight" -> -64;
                        case "getMaxHeight" -> 320;
                        case "isChunkLoaded" -> a.length == 2 && loaded.contains(key((int) a[0], (int) a[1]));
                        case "getBlockAt" -> {
                            int x = (int) a[0];
                            int y = (int) a[1];
                            int z = (int) a[2];
                            if (!loaded.contains(key(x >> 4, z >> 4))) {
                                syncLoads.add((x >> 4) + "," + (z >> 4)); // Paper loads it, on the main thread
                            }
                            RaceGrid.Cell c = blocks.at(x, y, z);
                            yield block(c == RaceGrid.Cell.SOLID ? Material.ICE
                                    : c == RaceGrid.Cell.WATER ? Material.WATER : Material.AIR);
                        }
                        case "getChunkAt" -> {
                            syncLoads.add("getChunkAt " + java.util.Arrays.toString(a));
                            yield null;
                        }
                        case "hashCode" -> 1;
                        case "equals" -> proxy == a[0];
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
        }

        static Block block(Material type) {
            return (Block) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Block.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getType" -> type;
                        case "isLiquid" -> type == Material.WATER;
                        case "isPassable" -> type == Material.AIR;
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
        }
    }

    /** Ice at y 64 and below, air above: a plain frozen floor. */
    private static RaceGrid.Cell floor(int x, int y, int z) {
        return y <= 64 ? RaceGrid.Cell.SOLID : RaceGrid.Cell.AIR;
    }

    /** A straight boat course far from anything loaded: its start at (4480.5, 65, 4352.5), facing +z. */
    private static Course farBoat() {
        return new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.EASY, "games",
                new Course.Spot(4480.5, 65, 4352.5, 0, 0), List.of(new Course.Mark(4480.5, 65, 4360, 4)),
                new Course.Mark(4480.5, 65, 4370, 4), 60.0, null, true, false, 1);
    }

    @Test
    void aBlockInAChunkNobodyHasLoadedIsNeverReadByLoadingIt() {
        Server server = new Server(TrackChunksTest::floor);
        server.loaded.add(Server.key(0, 0));
        WorldSurface surface = new WorldSurface(server.world());
        assertEquals(RaceGrid.Cell.SOLID, surface.at(5, 64, 5), "a loaded chunk reads its blocks, as before");
        assertTrue(surface.complete(), "nothing missing yet");
        surface.at(40, 64, 5);
        assertEquals(List.of(), server.syncLoads, "the block in chunk (2, 0), which isn't loaded, is not read"
                + " through a synchronous load");
        assertFalse(surface.complete(), "so what was read isn't the world yet");
        assertEquals(1, surface.missing().size(), "one chunk is missing: " + surface.missing().size());
        assertEquals(2, surface.missing().get(0)[0], "chunk x 2");
        assertEquals(0, surface.missing().get(0)[1], "chunk z 0");

        WorldSurface admin = WorldSurface.mayLoad(server.world());
        assertEquals(RaceGrid.Cell.SOLID, admin.at(40, 64, 5), "an admin's command still reads through a load");
        assertTrue(admin.complete(), "and is complete");
    }

    @Test
    void aBoatGridAndItsStandFarFromEveryoneLoadNothingOnTheMainThread() {
        Server server = new Server(TrackChunksTest::floor); // the party waits in the Clubhouse: nothing near is loaded
        WorldSurface surface = new WorldSurface(server.world());
        RaceGrid.forCourse(farBoat(), surface, 8);
        RaceStand.standable(surface, new Point(4480.5, 70, 4400.5));
        assertEquals(List.of(), server.syncLoads, "the grid's path back from the start and the stand are read"
                + " without loading one chunk on the host's click");
        assertFalse(surface.complete(), "and the read says it needs chunks: " + surface.missing().size());
    }

    @Test
    void aStartLoadsTheTracksChunksOffTheMainThreadAndSeatsTheGridTheBlocksGive() {
        RaceGridTest.Blocks lane = RaceGridTest.lane(4);
        Course c = RaceGridTest.straightBoat(0.5);
        RaceGrid.Grid truth = RaceGrid.forCourse(c, lane, 8);
        assertEquals(8, truth.size(), "the lane seats 8");

        Server server = new Server(lane);
        RaceGrid.Grid seen = null;
        int waited = 0;
        for (int round = 0; round < 10 && seen == null; round++) {
            WorldSurface surface = new WorldSurface(server.world());
            RaceGrid.Grid g = RaceGrid.forCourse(c, surface, 8); // PartyRaces.start's read
            TrackChunks.Next next = TrackChunks.next(surface.missing(), waited);
            if (next == TrackChunks.Next.GO) {
                seen = g;
                continue;
            }
            assertEquals(TrackChunks.Next.WAIT, next, "a read that needed chunks waits for them: round " + round);
            int[] ran = {0};
            TrackChunks.whenLoaded(server, surface.missing(), () -> ran[0]++);
            assertEquals(0, ran[0], "the Start doesn't run again until the chunks are in");
            server.finishLoads();
            assertEquals(1, ran[0], "then it does, once");
            waited++;
        }
        assertEquals(List.of(), server.syncLoads, "no chunk was loaded on the main thread");
        assertTrue(seen != null && waited >= 1 && waited <= TrackChunks.MAX_WAITS, "the grid was read once its"
                + " chunks were in, after " + waited + " round(s) of loads");
        assertEquals(truth.spots(), seen.spots(), "and it is the grid the blocks give");
        assertEquals(TrackChunks.Next.GIVE_UP, TrackChunks.next(List.<int[]>of(new int[]{1, 1}), TrackChunks.MAX_WAITS),
                "chunks that never stay in: the host is told to try again, never a synchronous load");
    }

    @Test
    void chunksAlreadyInRunAtOnceAndEachMissingOneIsAskedForOnce() {
        Server server = new Server(TrackChunksTest::floor);
        server.loaded.add(Server.key(3, 4));
        int[] ran = {0};
        TrackChunks.whenLoaded(server, List.of(new int[]{3, 4}), () -> ran[0]++);
        assertEquals(1, ran[0], "loaded: at once");
        assertTrue(server.asked.isEmpty(), "nothing asked for");
        TrackChunks.whenLoaded(server, List.of(new int[]{5, 6}, new int[]{5, 6}, new int[]{7, 8}), () -> ran[0]++);
        assertEquals(2, server.asked.size(), "each missing chunk once");
        server.finishLoads();
        assertEquals(2, ran[0], "then it runs, once");
    }

    @Test
    void theRaceCodeNeverLoadsAChunkOnTheMainThread() throws IOException {
        for (String file : List.of("games/trial/PartyRaces.java", "games/trial/WorldSurface.java",
                "games/trial/TrackChunks.java", "games/event/Tracks.java")) {
            String code = Files.readString(Path.of("src/main/java/com/dierks/homecraft/" + file));
            assertFalse(code.contains(".getChunkAt("), file + ": World.getChunkAt blocks the main thread on a disk"
                    + " load");
        }
        String party = Files.readString(Path.of("src/main/java/com/dierks/homecraft/games/trial/PartyRaces.java"));
        assertFalse(party.contains("WorldSurface.mayLoad"), "a party race's Start never reads through a load");
    }
}
