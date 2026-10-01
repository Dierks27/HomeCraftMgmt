package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrackChunks;
import com.dierks.homecraft.gui.games.event.RaceNightMenu;
import com.dierks.homecraft.storage.EventDao;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night's own reads of a Mountain Run (COURSE-VARIETY-SPEC §5.2), the call sites {@link EventCopyTest}
 * and {@link RaceNightMenu}'s tests can't reach, since those hand their values in: the Race Night screen's
 * view ({@link RaceNight#view}), {@code /hcm games status} ({@link RaceNight#statusLines}) and
 * {@code /hcm games event status} ({@link EventAdmin}) on a night that is on, and the screen's next night
 * before it is made ({@link RaceNight.Upcoming}).
 *
 * <p>The real framework and database with no server: Fresh Courses' gate vouches for the course, and its
 * world is a fake with the Mountain Run's top level (the ice under the launch pit, where the automatic
 * grid goes) and the viewing stand's floor. An admin starts a night on it.
 *
 * <p>Pinned here (the review found every one of these hooks could be taken out with the whole suite still
 * green): on a Fresh Ice Boat layout of algo 3 the screen, the status line and the admin's status say it is
 * a downhill sprint ("3 downhill races", "a downhill sprint"); on the same marks under the algo-2 planner,
 * or hand-built, they read exactly as before ("3 races, 1 lap"). CV final gate: its shared warm-up is
 * "Warm-up runs" on the hub's sign and screen, the tile and the screen ("Warm-up laps" on the others).
 */
class RaceNightDownhillTest {

    private static final long T0 = GamesBench.at(2026, 10, 2, 12, 0);
    /** The Ice Boat slot's half A, where {@link MountainRuns} is laid out. */
    private static final Box HALF = Slots.ICE_BOAT.half('A');
    /** The viewing stand's spot over the half's centre, 5 above the pit. */
    private static final Point STAND = RaceStand.spot(HALF, MountainRuns.TOP);

    private GamesBench bench;
    private RaceNight night;
    private Player admin;

    /** Race Night on for {@code fresh_boat}, no schedule, 2-8 racers, laps from the course (0). */
    private static RaceNightSettings on() {
        return on(0);
    }

    /** The same, with a shared warm-up of {@code warmupSeconds} (0: none). */
    private static RaceNightSettings on(int warmupSeconds) {
        RaceNightSettings d = RaceNightSettings.defaults();
        return new RaceNightSettings(true, List.of(), Slots.ICE_BOAT.id(), d.races(), 0, d.announceMinutes(),
                d.joinMinutes(), 10, 2, 8, d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(), warmupSeconds,
                d.points(), d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(),
                d.prizeEventsPerWeek(), d.season(), d.standRadius(), d.hype());
    }

    /** Fresh Courses' gate: every course is live, and a layout's half is the Ice Boat slot's half A. */
    private static final class Gate implements GeneratedCourses {
        @Override
        public boolean live(String courseId, GenTag tag) {
            return true;
        }

        @Override
        public boolean standing(GenTag tag) {
            return true;
        }

        @Override
        public String closedLine(String courseId) {
            return "";
        }

        @Override
        public long nextChangeAt() {
            return -1;
        }

        @Override
        public boolean inArea(String world, int x, int y, int z) {
            return false;
        }

        @Override
        public Box half(GenTag tag) {
            return HALF;
        }
    }

    /**
     * The Games world: ice at the pit's level (so the grid behind the start fits), and the stand's floor;
     * every chunk is loaded.
     */
    private static final class Server implements Tracks.Server, TrackChunks.Loader {
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
            return true;
        }

        @Override
        public void loadAsync(int cx, int cz, Runnable done) {
            done.run();
        }

        private static boolean solid(int x, int y, int z) {
            return y == (int) MountainRuns.TOP - 1 || x == (int) Math.floor(STAND.x())
                    && y == (int) Math.floor(STAND.y()) - 1 && z == (int) Math.floor(STAND.z());
        }

        private World world() {
            return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getName" -> "games";
                        case "getMinHeight" -> -64;
                        case "getMaxHeight" -> 320;
                        case "isChunkLoaded" -> true;
                        case "getBlockAt" -> block(solid((int) a[0], (int) a[1], (int) a[2]) ? Material.ICE : Material.AIR);
                        case "hashCode" -> 1;
                        case "equals" -> proxy == a[0];
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
        }

        private static Block block(Material type) {
            return (Block) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Block.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getType" -> type;
                        case "isLiquid" -> false;
                        case "isPassable" -> type == Material.AIR;
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
        }
    }

    /** The server as the night sees it: the bench's clock; nothing else is asked before the window. */
    private final class Ports implements NightPorts {

        @Override
        public long now() {
            return bench.now();
        }

        @Override
        public long tick() {
            return 1_000;
        }

        @Override
        public boolean online(UUID player) {
            return false;
        }

        @Override
        public boolean free(UUID player) {
            return false;
        }

        @Override
        public String name(UUID player) {
            return "Ava";
        }

        @Override
        public String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link) {
            return null;
        }

        @Override
        public void regrid(UUID racer, Course raced, Course.Spot grid) {
        }

        @Override
        public void park(UUID racer) {
        }

        @Override
        public void home(UUID racer, EndReason why, String line) {
        }

        @Override
        public boolean reserve(String courseId, Object holder, String line) {
            return true;
        }

        @Override
        public void release(String courseId, Object holder) {
        }

        @Override
        public void endSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void warnSoloRuns(String courseId, Collection<UUID> racers, String line) {
        }

        @Override
        public void tell(UUID player, String line, boolean queueIfOffline) {
        }

        @Override
        public void title(UUID player, String big, String small) {
        }

        @Override
        public void bar(UUID player, String line, float progress, boolean lastLap) {
        }

        @Override
        public void watchers(String line) {
        }

        @Override
        public void announce(Announcer.Line line, String text, Collection<UUID> racers) {
        }

        @Override
        public void changed() {
        }

        @Override
        public long points(UUID player, String board) {
            return 0L;
        }

        @Override
        public void progress(UUID player, boolean won) {
        }

        @Override
        public void log(String line, boolean warn) {
        }
    }

    /** An admin's night, 25 minutes from now, on {@code track} (saved as the Ice Boat slot's row). */
    private NightRunner adminNight(Course track) throws Exception {
        return adminNight(track, on());
    }

    /** {@link #adminNight(Course)} under {@code settings}. */
    private NightRunner adminNight(Course track, RaceNightSettings settings) throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", settings);
        bench.games().generated(new Gate());
        bench.dao().saveCourse(new GamesDao.CourseRow(track.id(), "trials", track.kind().id(), track.name(),
                track.world(), track.enabled(), CourseCodec.encode(track), track.rev(), 0, 0), false);
        Course open = ((TimeTrials) bench.games().game("trials")).openCourse(track.id());
        assertNotNull(open, "fixture: the track is an open Time Trials course");
        assertEquals(track.gen() == null ? null : track.gen().algo(), open.gen() == null ? null : open.gen().algo(),
                "fixture: its tag was stored with it");
        night = (RaceNight) bench.games().game("race_night");
        night.dao(new EventDao(bench.db(), bench.dao()));
        night.tracks().server(new Server());
        night.portsFor(id -> new Ports());
        admin = bench.player("Admin");
        assertNull(night.adminStart("Admin", track.id(), null, null, 25, false), "fixture: an admin sets a night");
        NightRunner runner = night.night();
        assertNotNull(runner, "fixture: Race Night made it, and runs it");
        return runner;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bench != null) {
            bench.close();
        }
    }

    @Test
    void aNightOnTheMountainRunSaysDownhillOnTheScreenAndInBothStatuses() throws Exception {
        NightRunner n = adminNight(MountainRuns.medium());
        assertEquals(3, n.plan().races(), "fixture: the stand stands, so the night holds 3 races");
        assertEquals(1, n.laps(), "fixture: a sprint is raced once");

        RaceNightMenu.View v = night.view(admin);
        assertTrue(v.downhill(), "the screen's view knows the night's track is the Mountain Run");
        assertEquals("&bIce Boat &7- 3 downhill races",
                RaceNightMenu.tiles(v, true).get(RaceNightMenu.TRACK).name(),
                "the Race Night screen's track tile, from the real view: \"3 downhill races\" (§5.2)");

        String status = night.statusLines().get(0);
        assertTrue(status.contains(" on Ice Boat (3 downhill races) · "), "/hcm games status: " + status);

        night.admin().handle(admin, new String[]{"status"});
        String heard = bench.heard(admin.getUniqueId());
        assertTrue(heard.contains("race 0 of 3, a downhill sprint"), "/hcm games event status: " + heard);
        assertFalse(heard.contains(" lap"), "no laps on a sprint: " + heard);
        assertEquals(0, bench.severe(), "nothing threw: " + bench.severeLines());
    }

    @Test
    void theSameMarksUnderTheAlgo2PlannerReadAsBefore() throws Exception {
        asBefore(MountainRuns.medium(MountainRuns.tag(2, 7)), 3, "the algo-2 planner's layout (it has a stand)");
    }

    @Test
    void theSameMarksHandBuiltReadAsBefore() throws Exception {
        asBefore(MountainRuns.medium(null), 1, "a hand-built track (no stand: one race)");
    }

    private void asBefore(Course track, int races, String what) throws Exception {
        NightRunner n = adminNight(track);
        assertEquals(races, n.plan().races(), what + ": fixture");
        RaceNightMenu.View v = night.view(admin);
        assertFalse(v.downhill(), what + ": not downhill on the screen");
        String format = EventCopy.format(races, 1);
        assertEquals("&bIce Boat &7- " + format, RaceNightMenu.tiles(v, true).get(RaceNightMenu.TRACK).name(),
                what + ": the track tile as it always was");
        String status = night.statusLines().get(0);
        assertTrue(status.contains(" on Ice Boat (" + format + ") · "), what + ": /hcm games status: " + status);
        night.admin().handle(admin, new String[]{"status"});
        String heard = bench.heard(admin.getUniqueId());
        assertTrue(heard.contains("of " + races + ", 1 lap"), what + ": /hcm games event status: " + heard);
        assertFalse(heard.contains("downhill"), what + ": " + heard);
        assertEquals(0, bench.severe(), what + ": nothing threw: " + bench.severeLines());
    }

    /**
     * CV final gate: the shared warm-up on the Mountain Run is "Warm-up runs" (a run down from the top) on
     * the hub's sign and screen, the Race Night tile and its screen; on the same marks under the algo-2
     * planner, or hand-built, it reads "Warm-up laps" as it always did.
     */
    @Test
    void theWarmUpIsRunsOnTheMountainRunAndLapsOnTheSameMarksOtherwise() throws Exception {
        warmUp(MountainRuns.medium(), "Warm-up runs", "the Mountain Run");
        bench.close();
        bench = null;
        warmUp(MountainRuns.medium(MountainRuns.tag(2, 7)), "Warm-up laps", "the algo-2 planner's layout");
        bench.close();
        bench = null;
        warmUp(MountainRuns.medium(null), "Warm-up laps", "a hand-built track");
    }

    /** An admin's night on {@code track} with a warm-up, two racers in, run into its warm-up: what it says. */
    private void warmUp(Course track, String words, String what) throws Exception {
        NightRunner n = adminNight(track, on(60));
        while (n.phase() == EventMachine.Phase.SCHEDULED) {
            bench.move(1_000);
            n.tick();
        }
        assertEquals(EventMachine.Phase.OPEN, n.phase(), what + ": fixture: the join window opens");
        assertNull(n.join(new UUID(0, 1), "Ava"), what + ": fixture: Ava joins");
        assertNull(n.join(new UUID(0, 2), "Ben"), what + ": fixture: Ben joins");
        while (bench.now() < n.startsAt() - 14_000) {
            bench.move(1_000);
            n.tick();
        }
        assertEquals(EventMachine.Phase.WARMUP, n.phase(), what + ": fixture: into the shared warm-up");
        EventBoard.View v = night.board();
        assertEquals(words, EventBoard.sign(v).get(1), what + ": the hub sign");
        assertEquals("&6&lRace Night &7- " + words.toLowerCase(java.util.Locale.ROOT), EventBoard.screen(v).get(0),
                what + ": the hub screen");
        assertEquals("&cRace Night &7- " + words.toLowerCase(java.util.Locale.ROOT), night.tileName(),
                what + ": the Race Night tile");
        assertEquals(words + " are on.", night.view(admin).state(), what + ": the Race Night screen");
        assertEquals(0, bench.severe(), what + ": nothing threw: " + bench.severeLines());
    }

    @Test
    void theScreensNextNightIsDownhillOnlyOnTheMountainRun() {
        RaceNight.Upcoming mountain = RaceNight.Upcoming.of(MountainRuns.medium(), 0);
        assertEquals(new RaceNight.Upcoming("Ice Boat", 1, true), mountain,
                "the Mountain Run: raced once, downhill (the tile reads \"3 downhill races\")");
        assertEquals(new RaceNight.Upcoming("Ice Boat", 1, true), RaceNight.Upcoming.of(MountainRuns.medium(), 2),
                "laps asked for on a sprint: it's still raced once, downhill");
        assertEquals(new RaceNight.Upcoming("Ice Boat", 1, false),
                RaceNight.Upcoming.of(MountainRuns.medium(MountainRuns.tag(2, 7)), 0),
                "the same marks under the algo-2 planner: not downhill");
        assertEquals(new RaceNight.Upcoming("Ice Boat", 1, false), RaceNight.Upcoming.of(MountainRuns.medium(null), 0),
                "hand-built: not downhill");
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            Course loop = f.trial().course().withGen(f.tag());
            RaceNight.Upcoming u = RaceNight.Upcoming.of(loop, 2);
            assertFalse(u.downhill(), f + ": an algo-2 loop is not downhill");
            assertEquals(2, u.laps(), f + ": and keeps its 2 laps");
            assertEquals(loop.name(), u.track(), f + ": by its own name");
        }
        assertEquals(new RaceNight.Upcoming(null, 2, false), RaceNight.Upcoming.of(null, 2),
                "no track picked yet: the settings' laps, not downhill");
    }
}
