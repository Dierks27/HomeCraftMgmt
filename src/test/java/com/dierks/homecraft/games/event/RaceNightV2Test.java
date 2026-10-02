package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.MountainRunsV2;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race Night on a Mountain Run v2 (MOUNTAIN-V2-SPEC §12; red-team F03, F05), end to end with the real framework
 * and database and no server: Fresh Courses' gate vouches for the course, its half is Ice Boat's v4 half, and
 * its world is a fake with the summit pit's ice (where the automatic grid goes) and the stand's floor at the
 * bottom. An admin starts a night on it.
 *
 * <p>Pinned here: a night on a v2 Winding Road stores the track's own windows (150 s and 6 minutes on a
 * two-minute run, config untouched) and parks finishers on the stand at the bottom, by the finish; its screen
 * and status say "3 downhill races on the Winding Road"; the schedule's restart fit sees the longer night; a v2
 * Slalom is refused with a line that says why and what fixes it, and nothing is made. (The algo-3 run's night
 * keeps config's windows exactly: {@link RaceNightDownhillTest}.)
 */
class RaceNightV2Test {

    private static final long T0 = GamesBench.at(2026, 10, 2, 12, 0);
    /** The Ice Boat slot's v4 half A, where {@link MountainRunsV2} is laid out. */
    private static final Box HALF = MountainRunsV2.HALF;
    /** The viewing stand at the bottom, 5 above the finish (MOUNTAIN-V2-SPEC §4.1). */
    private static final Point STAND = RaceStand.spotV4(HALF, MountainRunsV2.finish().y());

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
     * The Games world: ice at the summit pit's level (so the grid behind the start fits), and the floor of
     * the stand at the bottom; every chunk is loaded.
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
            return y == (int) MountainRunsV2.TOP - 1 || x == (int) Math.floor(STAND.x())
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
    void aNightOnAV2WindingRoadStoresItsOwnWindowsAndParksAtTheBottom() throws Exception {
        NightRunner n = adminNight(MountainRunsV2.road());
        assertEquals(150, n.plan().rules().finishWindowSeconds(), "the finish window: 1.25 x T_m (120 s)");
        assertEquals(6, n.plan().rules().maxRaceMinutes(), "the longest race: 3 x T_m");
        assertEquals(60, RaceNightSettings.defaults().finishWindowSeconds(), "config is untouched");
        assertEquals(STAND, n.track().stand(), "finishers wait on the stand at the bottom, by the finish");
        assertEquals(3, n.plan().races(), "with its stand, the night holds 3 races");

        RaceNightMenu.View v = night.view(admin);
        assertEquals("&bIce Boat &7- 3 downhill races on the Winding Road",
                RaceNightMenu.tiles(v, true).get(RaceNightMenu.TRACK).name(), "the Race Night screen (§12)");
        String status = night.statusLines().get(0);
        assertTrue(status.contains(" on Ice Boat (3 downhill races on the Winding Road) · "), "/hcm games status: "
                + status);
        assertEquals(0, bench.severe(), "nothing threw: " + bench.severeLines());
    }

    @Test
    void theRestartFitSeesTheLongerNight() throws Exception {
        adminNight(MountainRunsV2.road());
        RaceNightSettings s = on();
        assertEquals(NightRules.of(s, s.races(), s.laps(), false, s.maxRacers(), 120_000).worstMillis(),
                night.fit().worstMs(), "the schedule's fit uses the track's effective windows (F03)");
        assertTrue(night.fit().worstMs() > NightRules.of(s, s.races(), s.laps(), false, s.maxRacers()).worstMillis(),
                "longer than config's own");
    }

    @Test
    void aV2SlalomIsNeverRacedAndTheLineSaysWhy() throws Exception {
        Course slalom = MountainRunsV2.slalom();
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", on());
        bench.games().generated(new Gate());
        bench.dao().saveCourse(new GamesDao.CourseRow(slalom.id(), "trials", slalom.kind().id(), slalom.name(),
                slalom.world(), slalom.enabled(), CourseCodec.encode(slalom), slalom.rev(), 0, 0), false);
        night = (RaceNight) bench.games().game("race_night");
        night.dao(new EventDao(bench.db(), bench.dao()));
        night.tracks().server(new Server());
        night.portsFor(id -> new Ports());
        String why = night.adminStart("Admin", slalom.id(), null, null, 25, false);
        assertNotNull(why, "a Slalom isn't raced (F05: Race Night is the Winding Road)");
        assertTrue(why.contains(RaceTrack.SLALOM) && why.contains("reroll"), "and the line says why and what fixes it: "
                + why);
        assertNull(night.night(), "nothing was made");
        assertTrue(night.fit().trackProblem().contains(RaceTrack.SLALOM), "the schedule skips its nights, saying why");
        assertTrue(night.check().stream().anyMatch(c -> c.what().contains(RaceTrack.SLALOM) && c.fix() != null
                && c.fix().contains("/hcm games gen reroll fresh_boat confirm")), "/hcm games check says what to do");
    }

    @Test
    void withCourseAutoASlalomAsTheOnlyBoatCourseSkipsTheNightToo() throws Exception {
        Course slalom = MountainRunsV2.slalom();
        RaceNightSettings d = on();
        RaceNightSettings auto = new RaceNightSettings(true, List.of(), "auto", d.races(), 0, d.announceMinutes(),
                d.joinMinutes(), 10, 2, 8, d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(),
                d.warmupSeconds(), d.points(), d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(),
                d.prizeEventsPerWeek(), d.season(), d.standRadius(), d.hype());
        assertTrue(auto.autoCourse(), "fixture: course auto");
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", auto);
        bench.games().generated(new Gate());
        bench.dao().saveCourse(new GamesDao.CourseRow(slalom.id(), "trials", slalom.kind().id(), slalom.name(),
                slalom.world(), slalom.enabled(), CourseCodec.encode(slalom), slalom.rev(), 0, 0), false);
        night = (RaceNight) bench.games().game("race_night");
        night.dao(new EventDao(bench.db(), bench.dao()));
        night.tracks().server(new Server());
        String problem = night.fit().trackProblem();
        assertTrue(problem != null && problem.contains(RaceTrack.SLALOM), "the schedule skips its nights: " + problem);
        String why = night.adminStart("Admin", null, null, null, 25, false);
        assertTrue(why != null && why.contains(RaceTrack.SLALOM), "and an admin's night is refused: " + why);
    }
}
