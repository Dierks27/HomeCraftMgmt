package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrackChunks;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 3 (fx3-2, on round 2's G2 #4): the schedule reads a track from loaded chunks only, and a night
 * must never be lost to that. Round 2 asked for the missing chunks with an empty callback and read the
 * track again at the next once-a-second pass; on a server that unloads chunks at once
 * ({@code chunks.delay-chunk-unloads-by} 0 or under a second) they were gone again by then, every pass
 * came back "loading", and the night's heads-up passed with no night and no console line.
 *
 * <p>Now the read is done again in the chunks' own callback, once every chunk the read asked for is in
 * ({@link Tracks.Wait}), while they are sure to be loaded. A track whose chunks come in but still don't
 * stay after {@link TrackChunks#MAX_WAITS} such reads, or never come in within
 * {@link RaceNight#TRACK_LOAD_MS}, is read once through a load on the main thread, with one WARN, so
 * the night is made and the owner is told why.
 *
 * <p>The real framework and database, with no server: the course's world is a fake whose chunks the
 * test loads and unloads by hand ({@link Tracks#server}), and the night due is given to the schedule
 * ({@link RaceNight#nextNight}).
 */
class RaceNightTrackLoadTest {

    /** Tuesday 29 September 2026, noon (the kit's zone). */
    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final long SEC = 1_000L;
    private static final long MIN = 60_000L;

    private GamesBench bench;
    private RaceNight night;
    private EventDao dao;
    private Server server;
    /**
     * Race Night's logger with no plugin, held here: {@link Logger#getLogger} keeps loggers only weakly, so
     * one nobody holds can be collected between the test's handler going on and the line being logged.
     */
    private static final Logger LOG = Logger.getLogger("HomeCraftManagement");
    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler catcher = new Handler() {
        @Override
        public void publish(LogRecord record) {
            synchronized (logged) {
                logged.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** Race Night on, for {@code course} (a course id or {@code auto}), 30 minutes' heads-up, a 10-minute window. */
    private static RaceNightSettings on(String course) {
        RaceNightSettings d = RaceNightSettings.defaults();
        return new RaceNightSettings(true, List.of(), course, d.races(), d.laps(), 30, 10, d.adminJoinMinutes(), 2, 8,
                d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(), d.warmupSeconds(), d.points(),
                d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(), d.prizeEventsPerWeek(),
                d.season(), d.standRadius());
    }

    /**
     * A straight boat lane in the Games world: an ice floor at y 64 from z -70 to 10, walls either side, its
     * start at z 0, so the grid behind it runs back into the chunks north of chunk (0, 0). It has no admin grid,
     * so its grid is the automatic one, read from the live blocks behind the start (8 spots).
     */
    private static Course lane() {
        return new Course("lane", TrialKind.BOAT, "Ice Lane", Tier.EASY, "games", new Course.Spot(0.5, 65, 0, 0, 0),
                List.of(new Course.Mark(0.5, 65, 5, 4)), new Course.Mark(0.5, 65, 9, 4), 60.0, null, true, false, 1);
    }

    private static RaceGrid.Cell laneBlock(int x, int y, int z) {
        if (z < -70 || z > 10 || Math.abs(x) > 5) {
            return RaceGrid.Cell.AIR;
        }
        if (y == 64 || (y == 65 || y == 66) && Math.abs(x) == 5) {
            return RaceGrid.Cell.SOLID;
        }
        return RaceGrid.Cell.AIR;
    }

    /** The night due now: its heads-up (30 minutes before its 12:30 start) is at noon. */
    private static EventSchedule.Occurrence dueNow() {
        long startsAt = GamesBench.at(2026, 9, 29, 12, 30);
        return new EventSchedule.Occurrence(EventPlan.scheduledId(LocalDate.of(2026, 9, 29), LocalTime.of(12, 30)),
                LocalDate.of(2026, 9, 29), LocalTime.of(12, 30), startsAt - 10 * MIN, startsAt, null);
    }

    /**
     * The course's world: the chunks the test says are loaded, async loads it completes by hand. How a
     * load ends is the test's: the chunk stays in until the test unloads it, is unloaded again right
     * after its callbacks ran ({@code chunks.delay-chunk-unloads-by: 0}), is gone before they run (a
     * callback a tick late on such a server), comes back inside the ask (a future Paper hands back
     * already done), or never comes back at all. Every block read in a chunk that isn't loaded is a
     * synchronous load on the main thread, noted.
     */
    private static final class Server implements Tracks.Server, TrackChunks.Loader {
        enum Stay { KEPT, UNLOADED_AFTER_CALLBACK, GONE_BEFORE_CALLBACK, AT_ONCE, NEVER }

        final Set<Long> loaded = new HashSet<>();
        final List<String> syncLoads = new ArrayList<>();
        final List<long[]> asked = new ArrayList<>();
        final List<long[]> pending = new ArrayList<>();
        final List<Runnable> callbacks = new ArrayList<>();
        Stay stay = Stay.KEPT;

        static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xffffffffL);
        }

        @Override
        public World world(String name) {
            return "games".equals(name) ? world() : null;
        }

        @Override
        public TrackChunks.Loader loader(World world) {
            return this;
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return loaded.contains(key(cx, cz));
        }

        @Override
        public void loadAsync(int cx, int cz, Runnable done) {
            asked.add(new long[]{cx, cz});
            if (stay == Stay.AT_ONCE) { // the load's future was already done: its callback runs inside the ask
                loaded.add(key(cx, cz));
                done.run();
                return;
            }
            if (stay == Stay.NEVER) {
                return; // lost on the way: its callback never runs
            }
            pending.add(new long[]{cx, cz});
            callbacks.add(done);
        }

        /** Every load asked for so far comes in; its callbacks run on the "main thread", as the stay says. */
        void finishLoads() {
            List<long[]> chunks = new ArrayList<>(pending);
            List<Runnable> now = new ArrayList<>(callbacks);
            pending.clear();
            callbacks.clear();
            if (stay != Stay.GONE_BEFORE_CALLBACK) {
                for (long[] c : chunks) {
                    loaded.add(key((int) c[0], (int) c[1]));
                }
            }
            now.forEach(Runnable::run);
            if (stay == Stay.UNLOADED_AFTER_CALLBACK) {
                for (long[] c : chunks) {
                    loaded.remove(key((int) c[0], (int) c[1]));
                }
            }
        }

        World world() {
            return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getName" -> "games";
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
                            RaceGrid.Cell c = laneBlock(x, y, z);
                            yield block(c == RaceGrid.Cell.SOLID ? Material.ICE : Material.AIR);
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
                        case "isLiquid" -> false;
                        case "isPassable" -> type == Material.AIR;
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
        }
    }

    private void open(String course) throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", on(course));
        Course built = lane();
        bench.dao().saveCourse(new GamesDao.CourseRow(built.id(), "trials", built.kind().id(), built.name(),
                built.world(), built.enabled(), CourseCodec.encode(built), built.rev(), 0, 0), false);
        assertNotNull(((TimeTrials) bench.games().game("trials")).openCourse("lane"), "the lane is an open course");
        dao = new EventDao(bench.db(), bench.dao());
        night = (RaceNight) bench.games().game("race_night");
        night.dao(dao);
        server = new Server();
        night.tracks().server(server);
        night.nextNight(RaceNightTrackLoadTest::dueNow);
    }

    @BeforeEach
    void catchLogs() {
        LOG.addHandler(catcher);
    }

    @AfterEach
    void tearDown() throws Exception {
        LOG.removeHandler(catcher);
        if (bench != null) {
            bench.close();
        }
    }

    /** The lines Race Night logged at {@code level} about night {@code id}. */
    private List<String> lines(Level level, String id) {
        List<String> out = new ArrayList<>();
        synchronized (logged) {
            for (LogRecord r : logged) {
                if (r.getLevel() == level && r.getMessage() != null && r.getMessage().contains(id)) {
                    out.add(r.getMessage());
                }
            }
        }
        return out;
    }

    /** Complete the loads asked for, round after round, until none is asked (at most {@code max} rounds). */
    private int finishLoads(int max) {
        int rounds = 0;
        while (!server.pending.isEmpty() && rounds < max) {
            server.finishLoads();
            rounds++;
        }
        return rounds;
    }

    @Test
    void aScheduledNightIsMadeTheMomentItsTracksChunksAreInEvenWhenTheServerUnloadsThemAtOnce() throws Exception {
        open("lane");
        server.stay = Server.Stay.UNLOADED_AFTER_CALLBACK; // chunks.delay-chunk-unloads-by: 0
        String id = dueNow().id();

        night.schedule(); // the once-a-second pass that finds the heads-up due
        assertNull(night.night(), "the lane's chunks aren't loaded: no night is made from a read of half a track");
        assertFalse(server.pending.isEmpty(), "its chunks are asked for off the main thread");
        assertEquals(List.of(), server.syncLoads, "and none is loaded on the main thread");

        int rounds = finishLoads(TrackChunks.MAX_WAITS);
        assertNotNull(night.night(), "the night is made in the chunks' own callback, while they are in, after "
                + rounds + " round(s) of loads - not at the next pass, when this server has unloaded them again");
        assertEquals(id, night.night().plan().id(), "the scheduled night due");
        assertEquals(EventMachine.Phase.SCHEDULED, night.night().phase(), "made at its heads-up, its window later");
        assertEquals(8, night.night().maxRacers(), "on the grid the blocks give: the lane seats 8");
        assertTrue(server.loaded.isEmpty(), "and the server did unload every chunk right after: " + server.loaded.size());
        assertEquals(List.of(), server.syncLoads, "no chunk was ever loaded on the main thread");
        assertEquals(List.of(), lines(Level.WARNING, id), "nothing went wrong, so nothing is warned");
    }

    @Test
    void onAServerThatKeepsChunksAWhileTheNightIsMadeWithoutWaitingForTheNextPass() throws Exception {
        open("lane");
        server.stay = Server.Stay.KEPT; // Paper's default: unloaded 10 s later
        night.schedule();
        assertNull(night.night(), "a read of a track in unloaded chunks is not the track (its grid read as air)");
        assertFalse(server.asked.isEmpty(), "its chunks are asked for");
        finishLoads(TrackChunks.MAX_WAITS);
        assertNotNull(night.night(), "once they are in, the night is made, with no pass of the clock needed");
        assertEquals(8, night.night().maxRacers(), "on the whole grid");
        assertEquals(List.of(), server.syncLoads, "without a load on the main thread");
    }

    @Test
    void theAutoPickIsReadInTheCallbackTooSoItsNightIsMadeOnAServerThatUnloadsAtOnce() throws Exception {
        open(RaceNightSettings.AUTO);
        server.stay = Server.Stay.UNLOADED_AFTER_CALLBACK;

        night.schedule();
        assertNull(night.night(), "the only candidate's chunks aren't in yet");
        finishLoads(TrackChunks.MAX_WAITS);
        assertNotNull(night.night(), "course: auto's pick is read again in the callback and the night is made");
        assertEquals("lane", night.night().plan().course(), "on the lane, the only boat course");
        assertEquals(List.of(), server.syncLoads, "without a load on the main thread");
    }

    @Test
    void chunksThatComeInButNeverStayAreReadOnceThroughALoadAfterMaxWaitsRoundsAndTheOwnerIsTold() throws Exception {
        open("lane");
        server.stay = Server.Stay.GONE_BEFORE_CALLBACK; // unloaded again before even the callback's read
        String id = dueNow().id();

        night.schedule();
        for (int round = 1; round <= TrackChunks.MAX_WAITS; round++) {
            server.finishLoads();
            assertNull(night.night(), "round " + round + ": still no track read whole, so no night from half of one");
            assertEquals(List.of(), server.syncLoads, "and no load on the main thread yet, round " + round);
            assertFalse(server.pending.isEmpty(), "its chunks are asked for again, round " + round);
        }
        assertEquals(List.of(), lines(Level.WARNING, id), "nothing to warn about while it still waits");

        server.finishLoads(); // they came in and went again, MAX_WAITS times: they don't stay on this server
        assertNotNull(night.night(), "the night is made: the track is read once through a load, as before round 2,"
                + " not lost");
        assertEquals(id, night.night().plan().id(), "the scheduled night due");
        assertEquals(8, night.night().maxRacers(), "on the grid the blocks give");
        assertFalse(server.syncLoads.isEmpty(), "that read loaded the track's chunks on the main thread, once");
        List<String> warns = lines(Level.WARNING, id);
        assertEquals(1, warns.size(), "and the console says so, once: " + warns);
        assertTrue(warns.get(0).contains("didn't come in and stay loaded")
                        && warns.get(0).contains("delay-chunk-unloads-by"),
                "saying why, and what to look at: " + warns.get(0));
    }

    @Test
    void chunksThatNeverComeBackAreReadOnceThroughALoadAfterTenSecondsAndTheOwnerIsTold() throws Exception {
        open("lane");
        server.stay = Server.Stay.NEVER; // the loads never call back
        String id = dueNow().id();

        long waited = 0;
        while (waited < RaceNight.TRACK_LOAD_MS) {
            night.schedule(); // a once-a-second pass
            assertNull(night.night(), "still loading " + waited + " ms in: no night from half a track yet");
            assertEquals(List.of(), server.syncLoads, "and no load on the main thread yet, " + waited + " ms in");
            bench.move(SEC);
            waited += SEC;
        }
        assertEquals(List.of(), lines(Level.WARNING, id), "nothing to warn about while it waits");

        night.schedule(); // ten seconds on
        assertNotNull(night.night(), "the night is made, not silently lost: the track is read through a load");
        assertFalse(server.syncLoads.isEmpty(), "that read loaded the track's chunks on the main thread");
        List<String> warns = lines(Level.WARNING, id);
        assertEquals(1, warns.size(), "and the console says so, once: " + warns);
        assertTrue(warns.get(0).contains("10 s"), "with how long it waited: " + warns.get(0));
    }

    @Test
    void aLoadThatEndsInsideTheReadIsReadRightAfterAndNeverMakesTheNightTwice() throws Exception {
        open("lane");
        server.stay = Server.Stay.AT_ONCE; // Paper hands back a load already done: its callback runs inside the ask
        String id = dueNow().id();

        night.schedule();
        assertNotNull(night.night(), "the pass reads the track again right after the read whose loads ended inside"
                + " it, while those chunks are in");
        assertEquals(1, lines(Level.INFO, id).stream().filter(l -> l.contains("starts at")).count(),
                "and makes the night once: " + lines(Level.INFO, id));
        assertEquals(List.of(), server.syncLoads, "without a load on the main thread");
    }

    @Test
    void passesOfTheClockWhileTheChunksAreOnTheirWayLeaveOneReadWhenTheyComeInAndAreNoRoundOfLoads() throws Exception {
        open("lane");
        server.stay = Server.Stay.GONE_BEFORE_CALLBACK;
        String id = dueNow().id();

        for (int i = 0; i < 3; i++) { // a slow disk: three seconds of passes before the loads are back
            night.schedule();
            bench.move(SEC);
        }
        int[] reads = {0};
        night.tracks().server(new Tracks.Server() {
            @Override
            public World world(String name) {
                reads[0]++;
                return server.world(name);
            }

            @Override
            public TrackChunks.Loader loader(World world) {
                return server;
            }
        });
        server.finishLoads();
        assertEquals(1, reads[0], "one read when they come in: the waits the later passes replaced don't each read"
                + " the world again (and ask again, and multiply)");
        assertNull(night.night(), "they didn't stay, so no night from that read");

        for (int round = 2; round <= TrackChunks.MAX_WAITS; round++) {
            server.finishLoads();
            assertNull(night.night(), "round " + round + " of loads: the clock's passes were no rounds, so it still"
                    + " waits for the chunks");
        }
        server.finishLoads();
        assertNotNull(night.night(), "after MAX_WAITS rounds it reads through a load, and the night is made");
        assertEquals(1, lines(Level.INFO, id).stream().filter(l -> l.contains("starts at")).count(),
                "once");
        assertEquals(1, lines(Level.WARNING, id).size(), "with one WARN: " + lines(Level.WARNING, id));
    }

    @Test
    void aWaitRunsItsOneCallbackOnceEveryAskOfTheReadIsInAndNeverWhenNothingWasAsked() {
        List<Tracks.Wait> ran = new ArrayList<>();
        Tracks.Wait none = new Tracks.Wait(ran::add);
        none.asked();
        assertEquals(List.of(), ran, "a read that asked for nothing (the track was all in) calls nothing back");

        Tracks.Wait two = new Tracks.Wait(ran::add); // course: auto, two candidates still loading
        Runnable a = two.part();
        Runnable b = two.part();
        two.asked();
        a.run();
        assertEquals(List.of(), ran, "one candidate's chunks in, the other's not: no read yet");
        b.run();
        assertEquals(List.of(two), ran, "both in: one callback for the read, with the wait it was for");
        b.run();
        assertEquals(1, ran.size(), "and never twice");

        Tracks.Wait early = new Tracks.Wait(ran::add); // loads handed back done inside the read
        early.part().run();
        assertEquals(1, ran.size(), "not while the read is still asking");
        early.asked();
        assertEquals(List.of(two, early), ran, "but as soon as it has asked for everything");
    }
}
