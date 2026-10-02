package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceStand;
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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Race Night open for sign-ups survives a restart even when its track isn't ready the moment the boot
 * looks (final review, MAJOR). This plugin loads before Multiverse-Core (for the void world), so at a
 * server start the Games world comes up after the games do, and a Fresh Ice Boat track (the Mountain Run
 * among them) stays closed until the boot heal has checked its blocks, a tick or more later. The boot read
 * those as "starting grid seats 0" or "isn't open right now" and called the night off.
 *
 * <p>Now the boot runs from the clock's first tick, once the worlds are up, and a track that isn't ready
 * yet ({@link Tracks.Found#notYet}) keeps the night OPEN: it is looked at again once a second
 * ({@link RaceNight#schedule}) and resumed with its sign-ups when the track is ready, or called off with
 * its usual message (and its prize slot given back) once its start is too close to resume
 * ({@link EventMachine#RESUME_MIN_MS}). A track that is really gone is called off at once, as before.
 *
 * <p>The real framework and database with no server: the worlds are fakes ({@link Tracks#server}) the test
 * loads when it says, and Fresh Courses' gate is one the test opens.
 */
class RaceNightResumeTest {

    /** Tuesday 29 September 2026, noon (the kit's zone). */
    private static final long T0 = GamesBench.at(2026, 9, 29, 12, 0);
    private static final long SEC = 1_000L;
    private static final long MIN = 60_000L;
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    /** The night a stop left: its window opened at 11:50, it starts at 12:20. */
    private static final String ID = EventPlan.scheduledId(LocalDate.of(2026, 9, 29), LocalTime.of(12, 20));
    private static final long STARTS_AT = T0 + 20 * MIN;
    /** The Ice Boat slot's half A, where {@link MountainRuns} is laid out, and its stand. */
    private static final Box HALF = LegacyBoxes.v036(Slots.ICE_BOAT, 'A');
    private static final Point STAND = RaceStand.spot(HALF, MountainRuns.TOP);

    private GamesBench bench;
    private RaceNight night;
    private EventDao dao;
    /** Whether the Games world is loaded (Multiverse has loaded it). */
    private boolean worldUp;
    /** Whether Fresh Courses' gate vouches for the Fresh track (the boot heal has checked its blocks). */
    private boolean gateOpen;

    /** Race Night switched on, with no schedule: only the night a stop left. */
    private static RaceNightSettings on() {
        RaceNightSettings d = RaceNightSettings.defaults();
        return new RaceNightSettings(true, List.of(), "lane", d.races(), d.laps(), d.announceMinutes(), d.joinMinutes(),
                d.adminJoinMinutes(), 2, 8, d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(),
                d.warmupSeconds(), d.points(), d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(),
                d.prizeEventsPerWeek(), d.season(), d.standRadius());
    }

    /**
     * A hand-built straight boat lane in the Games world, with no admin grid: its grid is the automatic
     * one, read from the live blocks behind the start (8 spots), so it needs the world.
     */
    private static Course lane() {
        return new Course("lane", TrialKind.BOAT, "Ice Lane", Tier.EASY, "games", new Course.Spot(0.5, 65, 0, 0, 0),
                List.of(new Course.Mark(0.5, 65, 5, 4)), new Course.Mark(0.5, 65, 9, 4), 60.0, null, true, false, 1);
    }

    /** The lane's blocks: an ice floor at y 64 from z -70 to 10, walls either side. */
    private static boolean laneSolid(int x, int y, int z) {
        if (z < -70 || z > 10 || Math.abs(x) > 5) {
            return false;
        }
        return y == 64 || (y == 65 || y == 66) && Math.abs(x) == 5;
    }

    /** The Mountain Run's blocks: ice at the pit's level (where the grid goes) and the stand's floor. */
    private static boolean mountainSolid(int x, int y, int z) {
        return y == (int) MountainRuns.TOP - 1 || x == (int) Math.floor(STAND.x())
                && y == (int) Math.floor(STAND.y()) - 1 && z == (int) Math.floor(STAND.z());
    }

    /** The server's worlds: the Games world once {@link #worldUp}, every chunk loaded, with {@code solid} blocks. */
    private Tracks.Server server(Predicate<int[]> solid) {
        TrackChunks.Loader chunks = new TrackChunks.Loader() {
            @Override
            public boolean loaded(int cx, int cz) {
                return true;
            }

            @Override
            public void loadAsync(int cx, int cz, Runnable done) {
                done.run();
            }
        };
        World games = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> "games";
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "isChunkLoaded" -> true;
                    case "getBlockAt" -> block(solid.test(new int[]{(int) a[0], (int) a[1], (int) a[2]})
                            ? Material.ICE : Material.AIR);
                    case "hashCode" -> 1;
                    case "equals" -> proxy == a[0];
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
        return new Tracks.Server() {
            @Override
            public World world(String name) {
                return worldUp && "games".equals(name) ? games : null;
            }

            @Override
            public TrackChunks.Loader loader(World world) {
                return chunks;
            }
        };
    }

    private static Block block(Material type) {
        return (Block) Proxy.newProxyInstance(RaceNightResumeTest.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getType" -> type;
                    case "isLiquid" -> false;
                    case "isPassable" -> type == Material.AIR;
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    /**
     * Fresh Courses' gate: a hand-built course is none of its business; the Fresh track is live once
     * {@link #gateOpen}; its half is the Ice Boat slot's half A.
     */
    private final class Gate implements GeneratedCourses {
        @Override
        public boolean live(String courseId, GenTag tag) {
            return tag == null || gateOpen;
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

    /** The real framework and database, {@code track} saved as a Time Trials course and read through {@code server}. */
    private void boot(Course track, Tracks.Server server) throws Exception {
        bench = new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", on());
        bench.games().generated(new Gate());
        bench.dao().saveCourse(new GamesDao.CourseRow(track.id(), "trials", track.kind().id(), track.name(),
                track.world(), track.enabled(), CourseCodec.encode(track), track.rev(), 0, 0), false);
        assertNotNull(((TimeTrials) bench.games().game("trials")).course(track.id()), "fixture: a Time Trials course");
        dao = new EventDao(bench.db(), bench.dao());
        night = (RaceNight) bench.games().game("race_night");
        night.dao(dao);
        night.tracks().server(server);
    }

    /** The night a stop left OPEN on {@code course}, 20 minutes from its start, with Ava, Ben and Cal signed up. */
    private void left(String course, boolean prized) throws Exception {
        String rules = NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8).encode();
        dao.open(new EventDao.EventRow(ID, course, T0 - 10 * MIN, STARTS_AT, EventDao.OPEN, rules, 0, false, "", "",
                T0 - 15 * MIN, null, ""));
        for (UUID u : List.of(A, B, C)) {
            assertTrue(dao.join(ID, u, u == A ? "Ava" : u == B ? "Ben" : "Cal", T0 - 5 * MIN), "fixture: joined");
        }
        if (prized) {
            assertTrue(dao.claimPrizeSlot(ID, "2920", 3), "fixture: it holds one of the week's prize nights");
        }
    }

    /** The lines kept for {@code player}'s next join. */
    private List<String> told(UUID player) throws Exception {
        return new ArrayList<>(bench.dao().prefsLike(player, ChanceRounds.NOTICE).values());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bench != null) {
            bench.close();
        }
    }

    @Test
    void anOpenNightOnAHandBuiltTrackWhoseWorldLoadsAfterTheBootResumesWithItsSignUps() throws Exception {
        boot(lane(), server(b -> laneSolid(b[0], b[1], b[2])));
        left("lane", false);

        night.recover(); // the boot, with the Games world not loaded yet (Multiverse loads it after this plugin)
        assertNull(night.night(), "no night from a track whose world isn't there yet");
        assertEquals(EventDao.OPEN, dao.event(ID).state(), "the night isn't called off: the world just isn't up yet"
                + " (it was: \"its track can't be raced now (Ice Lane's starting grid seats 0 ...)\")");
        assertEquals(List.of(), told(A), "and nobody is told it was called off");
        assertTrue(String.join("\n", night.statusLines()).contains("resuming · " + ID),
                "the admin's status says it waits for its track: " + night.statusLines());

        night.recover(); // a second look at the boot's rows never doubles anything
        bench.move(SEC);
        night.schedule(); // a second on: still no world
        assertNull(night.night(), "still waiting for the world");
        assertEquals(EventDao.OPEN, dao.event(ID).state(), "and still open");

        worldUp = true; // Multiverse has loaded the Games world
        bench.move(SEC);
        night.schedule();
        NightRunner resumed = night.night();
        assertNotNull(resumed, "the night is resumed the moment its track can be read");
        assertEquals(ID, resumed.plan().id(), "the same night");
        assertEquals(EventMachine.Phase.OPEN, resumed.phase(), "its window was open: open again");
        assertEquals(3, resumed.joined().size(), "with its three sign-ups");
        assertEquals(8, resumed.maxRacers(), "on the grid the lane's blocks give");

        night.recover(); // the boot's pass once more, with the night on: it is never recovered twice
        assertSame(resumed, night.night(), "the same night carries on");
        assertEquals(EventDao.OPEN, dao.event(ID).state(), "its row isn't called off as \"another night is on\"");
        assertEquals(List.of(), told(A), "nobody was ever told it was called off");
        assertEquals(0, bench.severe(), "nothing threw: " + bench.severeLines());
    }

    @Test
    void anOpenNightOnAFreshTrackWhoseGateOpensAfterTheBootHealResumes() throws Exception {
        worldUp = true; // the world is up; the Fresh gate opens only once the boot heal has checked the blocks
        boot(MountainRuns.medium(), server(b -> mountainSolid(b[0], b[1], b[2])));
        left(Slots.ICE_BOAT.id(), false);

        night.recover();
        assertNull(night.night(), "the Mountain Run isn't open yet: nothing to resume on");
        assertEquals(EventDao.OPEN, dao.event(ID).state(), "but the night isn't called off for it (it was: \"its track"
                + " can't be raced now (Ice Boat isn't open right now)\")");
        for (int s = 0; s < 30; s++) { // a boot heal that takes half a minute
            bench.move(SEC);
            night.schedule();
            assertNull(night.night(), s + " s on: the gate is still closed");
            assertEquals(EventDao.OPEN, dao.event(ID).state(), s + " s on: still open");
        }

        gateOpen = true; // the boot heal vouched for the layout
        bench.move(SEC);
        night.schedule();
        NightRunner resumed = night.night();
        assertNotNull(resumed, "resumed once the gate opens");
        assertEquals(ID, resumed.plan().id(), "the same night");
        assertEquals(Slots.ICE_BOAT.id(), resumed.plan().course(), "on the Mountain Run");
        assertTrue(EventCopy.downhill(resumed.track().base()), "fixture: it is the downhill Mountain Run");
        assertEquals(3, resumed.plan().races(), "its three races, as it was set");
        assertEquals(3, resumed.joined().size(), "with its three sign-ups");
        assertEquals(List.of(), told(B), "nobody was told it was called off");
        assertEquals(0, bench.severe(), "nothing threw: " + bench.severeLines());
    }

    @Test
    void aNightWhoseTrackNeverComesBackIsCalledOffAtTheDeadlineAndGivesItsPrizeSlotBack() throws Exception {
        boot(lane(), server(b -> laneSolid(b[0], b[1], b[2]))); // the world was deleted: it never loads
        left("lane", true);

        night.recover();
        long deadline = STARTS_AT - EventMachine.RESUME_MIN_MS;
        while (bench.now() < deadline) {
            bench.move(MIN);
            night.schedule();
            assertEquals(EventDao.OPEN, dao.event(ID).state(), "kept open while it could still resume, "
                    + (STARTS_AT - bench.now()) / MIN + " min before its start");
        }
        assertEquals(deadline, bench.now(), "fixture: exactly the last moment it could resume");
        assertEquals(List.of(), told(A), "nobody told anything yet");

        bench.move(SEC); // too close to its start to resume now
        night.schedule();
        assertNull(night.night(), "never resumed");
        EventDao.EventRow row = dao.event(ID);
        assertEquals(EventDao.CALLED_OFF, row.state(), "called off once its start is too close");
        String why = "its track can't be raced now (the world games isn't loaded)";
        assertEquals("called off: " + why, row.note(), "with the track's problem, as the boot always said it");
        assertFalse(row.prized(), "nothing was raced, so the week's prize night is given back");
        assertEquals(0, dao.prizedIn("2920"), "and the week has all of them again");
        for (UUID u : List.of(A, B, C)) {
            assertEquals(List.of("&7Race Night was called off - " + why + ". See you next time!"), told(u),
                    u + " is told once, at their next join");
        }

        bench.move(SEC);
        night.schedule();
        night.recover();
        assertEquals(1, told(A).size(), "called off once, never twice: " + told(A));
        assertEquals(0, bench.severe(), "nothing threw: " + bench.severeLines());
    }

    @Test
    void aNightWhoseCourseIsGoneIsStillCalledOffAtOnce() throws Exception {
        worldUp = true;
        boot(lane(), server(b -> laneSolid(b[0], b[1], b[2])));
        left("old_lane", false); // a course an admin deleted: nothing will bring it back

        night.recover();
        assertNull(night.night(), "nothing to resume");
        EventDao.EventRow row = dao.event(ID);
        assertEquals(EventDao.CALLED_OFF, row.state(), "called off at the boot, as before: nothing to wait for");
        assertEquals("called off: its track can't be raced now (there's no course called old_lane)", row.note(),
                "with its usual message");
        assertEquals(1, told(C).size(), "and its racers are told: " + told(C));
        assertFalse(String.join("\n", night.statusLines()).contains("resuming"), "nothing waits");
    }
}
