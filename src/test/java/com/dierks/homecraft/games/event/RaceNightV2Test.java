package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import com.dierks.homecraft.games.trial.BoatHype;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.MountainRunsV2;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrackChunks;
import com.dierks.homecraft.games.trial.TrialKind;
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
    /** What the nights announced and warned solo riders, in order ({@link Ports}). */
    private final List<String> said = new java.util.ArrayList<>();

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
            said.add("warn: " + line);
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
            said.add(line + ": " + text);
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

    // ---- audit fixes (FX-MD) ----------------------------------------------------------------------------

    /** Race Night under {@code settings} with {@code tracks} saved as Time Trials courses, and Fresh Courses' {@code fresh}. */
    private void boot(RaceNightSettings settings, DailySettings fresh, Course... tracks) throws Exception {
        bench = fresh == null
                ? new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", settings)
                : new GamesBench(T0, List.of(TimeTrials.SPEC, RaceNight.SPEC), "trials", TimeTrialsSettings.defaults(),
                "race_night", settings, Slots.DAILY, fresh);
        bench.games().generated(new Gate());
        for (Course c : tracks) {
            bench.dao().saveCourse(new GamesDao.CourseRow(c.id(), "trials", c.kind().id(), c.name(), c.world(),
                    c.enabled(), CourseCodec.encode(c), c.rev(), 0, 0), false);
        }
        night = (RaceNight) bench.games().game("race_night");
        night.dao(new EventDao(bench.db(), bench.dao()));
        night.tracks().server(new Server());
        night.portsFor(id -> new Ports());
    }

    /** Fresh Courses' settings with Ice Boat's {@code style} ({@code null}: random). */
    private static DailySettings freshWith(BoatStyle style) {
        DailySettings d = DailySettings.defaults();
        List<DailySettings.SlotConfig> slots = new java.util.ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            slots.add(c.id().equals(Slots.ICE_BOAT.id()) ? c.withStyle(style) : c);
        }
        return d.withSlots(slots);
    }

    /** Race Night on with {@code course: auto}. */
    private static RaceNightSettings auto() {
        RaceNightSettings d = on();
        return new RaceNightSettings(true, List.of(), "auto", d.races(), 0, d.announceMinutes(), d.joinMinutes(), 10, 2,
                8, d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(), d.warmupSeconds(), d.points(),
                d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(), d.prizeEventsPerWeek(),
                d.season(), d.standRadius(), d.hype());
    }

    /** A hand-built boat course in the Games world, on the fake world's ice (y 169), a grid's room behind its start. */
    private static Course handBuilt() {
        return new Course("lane", TrialKind.BOAT, "Ice Lane", Tier.EASY, "games",
                new Course.Spot(6300.5, MountainRunsV2.TOP, 3000.5, 0f, 0f),
                List.of(new Course.Mark(6300.5, MountainRunsV2.TOP, 3010.5, 4)),
                new Course.Mark(6300.5, MountainRunsV2.TOP, 3020.5, 4), 60.0, null, true, false, 1);
    }

    @Test
    void theSlalomsFixFollowsTheConfiguredStyle() throws Exception {
        boot(on(), freshWith(null), MountainRunsV2.slalom());
        String why = night.adminStart("Admin", Slots.ICE_BOAT.id(), null, null, 25, false);
        assertEquals("Can't race there: Ice Boat is the Slalom this week, and " + RaceTrack.SLALOM + " - /hcm games gen"
                + " reroll fresh_boat confirm makes it the Winding Road.", why,
                "style random: a reroll makes it the Winding Road (F05)");
        bench.close();

        boot(on(), freshWith(BoatStyle.SLALOM), MountainRunsV2.slalom());
        why = night.adminStart("Admin", Slots.ICE_BOAT.id(), null, null, 25, false);
        assertNotNull(why, "fixture: still refused");
        assertTrue(why.contains(" - set games.fresh.slots.fresh_boat.style to random or road and /hcm reload, then"
                + " /hcm games gen reroll fresh_boat confirm"), "audit M08/M11: under style: slalom a reroll alone only"
                + " makes another Slalom, so the line says to change the style first: " + why);
        assertTrue(!why.contains(" - /hcm games gen reroll"), "and never offers the reroll alone: " + why);
        assertTrue(night.fit().trackProblem().contains("style to random or road"), "the schedule's skip line too: "
                + night.fit().trackProblem());
        assertTrue(night.check().stream().anyMatch(c -> c.what().contains(RaceTrack.SLALOM) && c.fix() != null
                && c.fix().startsWith("set games.fresh.slots.fresh_boat.style to random or road")),
                "and /hcm games check's fix: " + night.check());
    }

    @Test
    void aLiveSlalomWithRaceNightOnIsInTheStatusWithTheExactCommand() throws Exception {
        boot(on(), freshWith(null), MountainRunsV2.slalom());
        List<String> status = night.statusLines();
        assertTrue(status.contains("slalom · Ice Boat is the Slalom this week, and " + RaceTrack.SLALOM
                + " - /hcm games gen reroll fresh_boat confirm makes it the Winding Road"), "audit M10: Race Night"
                + " switched on after the week's Ice Boat was built as a Slalom says so, with the command: " + status);
        assertTrue(night.check().stream().anyMatch(c -> c.what().contains(RaceTrack.SLALOM)
                && "/hcm games gen reroll fresh_boat confirm makes it the Winding Road".equals(c.fix())),
                "and /hcm games check: " + night.check());
        assertTrue(BoatHype.slalom(((TimeTrials) bench.games().game("trials")).course(Slots.ICE_BOAT.id())),
                "nothing rerolled it: the live course is never changed silently");
        bench.close();

        boot(on(), freshWith(null), MountainRunsV2.road());
        assertTrue(night.statusLines().stream().noneMatch(l -> l.startsWith("slalom")), "a Winding Road needs no line");
        bench.close();

        RaceNightSettings d = on();
        RaceNightSettings off = new RaceNightSettings(false, List.of(), Slots.ICE_BOAT.id(), d.races(), 0,
                d.announceMinutes(), d.joinMinutes(), 10, 2, 8, d.finishWindowSeconds(), d.maxRaceMinutes(),
                d.breakSeconds(), d.warmupSeconds(), d.points(), d.finishPoints(), d.stillRacingPoints(), d.prizes(),
                d.finisherPrize(), d.prizeEventsPerWeek(), d.season(), d.standRadius(), d.hype());
        boot(off, freshWith(null), MountainRunsV2.slalom());
        assertTrue(night.statusLines().stream().noneMatch(l -> l.startsWith("slalom")),
                "with Race Night off the Slalom is a solo week, and nothing to fix: " + night.statusLines());
    }

    @Test
    void underCourseAutoTheNextNightNeverNamesASlalomItWillRefuse() throws Exception {
        boot(auto(), null, handBuilt(), MountainRunsV2.slalom());
        assertEquals(List.of("fresh_boat", "lane"), night.tracks().candidates(), "fixture: both are candidates");
        EventSchedule.Occurrence o = null;
        for (int h = 0; h < 24 && o == null; h++) {
            String id = EventPlan.scheduledId(java.time.LocalDate.of(2026, 10, 9), java.time.LocalTime.of(h, 0));
            if ("fresh_boat".equals(RaceTrack.pick(night.tracks().candidates(), id))) {
                o = new EventSchedule.Occurrence(id, java.time.LocalDate.of(2026, 10, 9), java.time.LocalTime.of(h, 0),
                        T0, T0 + 600_000, null);
            }
        }
        assertNotNull(o, "fixture: a night whose turn falls on the Slalom");
        Course next = night.nextTrack(o);
        assertNotNull(next, "a track is named");
        assertEquals("lane", next.id(), "audit M07: the next in turn the night can race, as Tracks.pick makes it");
        assertEquals("Ice Lane", night.nextTrackName(o), "the board, the status and the feed name it");
        assertEquals("", RaceNight.Upcoming.of(next, 0).where(), "the screen's TRACK tile has no \" on the Slalom\"");
        Tracks.Found made = night.tracks().pick(o.id(), 3, 2, 8);
        assertNull(made.problem(), "fixture: the night is made: " + made.problem());
        assertEquals(next.id(), made.track().base().id(), "on the very track the screen named");
    }

    @Test
    void aNightResumedAfterARestartRunsWithTheWindowsOfTheTrackItResumesOn() throws Exception {
        for (long model : new long[]{MountainRunsV2.MODEL_MS, 132_000}) {
            Course track = MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, model));
            boot(on(), null, track);
            EventDao dao = new EventDao(bench.db(), bench.dao());
            String id = EventPlan.scheduledId(java.time.LocalDate.of(2026, 10, 2), java.time.LocalTime.of(12, 20));
            NightRules stored = NightRules.of(RaceNightSettings.defaults(), 3, 0, false, 8); // a row 0.36 stored
            assertEquals(60, stored.finishWindowSeconds(), "fixture: 0.36's 60 s window");
            assertEquals(4, stored.maxRaceMinutes(), "fixture: and 4 minutes");
            dao.open(new EventDao.EventRow(id, track.id(), T0 - 10 * 60_000L, T0 + 20 * 60_000L, EventDao.OPEN,
                    stored.encode(), 0, false, "", "", T0 - 15 * 60_000L, null, ""));
            night.recover();
            NightRunner resumed = night.night();
            assertNotNull(resumed, "T_m " + model + ": the night resumes on its track");
            NightRules r = resumed.plan().rules();
            assertEquals(NightRules.finishWindow(60, model), r.finishWindowSeconds(),
                    "audit M05: the window of the track it resumes on, not the row's 60 s");
            assertEquals(NightRules.maxRace(4, model), r.maxRaceMinutes(), "and its longest race");
            long leader = Math.round(model / 0.85);
            long kid = Math.round(model / 0.45);
            assertTrue(leader + r.finishWindowSeconds() * 1000L >= kid, "T_m " + model + ": a racer at 0.45 behind"
                    + " a leader at 0.85 is in before the window closes");
            assertTrue(r.maxRaceMinutes() * 60_000L >= kid, "and before the race's longest");
            bench.close();
        }
        assertEquals(150, NightRules.finishWindow(60, 120_000), "120 s: 150 s");
        assertEquals(165, NightRules.finishWindow(60, 132_000), "132 s: 165 s");
        assertEquals(7, NightRules.maxRace(4, 132_000), "and 7 minutes");
        bench = null;
    }

    @Test
    void aV2NightHoldsItsTrackLongEnoughForASoloRunStartedBeforeToFinish() throws Exception {
        NightRunner n = adminNight(MountainRunsV2.road());
        long reserve = EventMachine.reserveMs(MountainRunsV2.MODEL_MS);
        assertEquals(330_000, reserve, "audit M06: 1 minute plus 2.25 T_m, 5:30 on a two-minute run");
        long startsAt = n.startsAt();
        bench.move(startsAt - reserve - 1_000 - bench.now());
        n.step();
        assertTrue(said.stream().noneMatch(l -> l.startsWith("LAST_CALL")), "no hold 5:31 before: " + said);
        bench.move(1_000);
        n.step();
        assertTrue(said.contains("LAST_CALL: &bRace Night &7starts in 6 minutes! &70 racers in so far - last call: &e"
                + EventCopy.COMMAND), "the last call says when it really starts: " + said);
        assertTrue(said.contains("warn: &eRace Night needs this track in 4 minutes."),
                "and a rider on a solo run is told how long they have, in whole minutes: " + said);
        long kid = Math.round(MountainRunsV2.MODEL_MS / 0.45);
        assertTrue(startsAt - reserve - 1 + kid <= startsAt - EventMachine.SOLO_END_MS,
                "a 0.45 rider who set off just before the hold is in before solo runs end");
    }
}
