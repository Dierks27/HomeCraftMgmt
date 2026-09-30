package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeping a course for good (GEN-SPEC-KEEP §4-§6) on the real engine: the archived plan is built into
 * the next free plot and registered as a NORMAL course (no gen block) that the course games' own
 * validation accepts, with the edition's records copied (none with {@code --fresh-board}); the new id
 * obeys the hand-built rules; the plot writer throws outside its plot; a plot must be empty the first
 * time; {@code clear-plot} takes the course and its boards down, moves people out and clears the
 * plot; a stop halfway leaves nothing registered and the next start finishes or cleans; and keep is
 * refused when the area is full, off, or the plan unreadable. A free plot is unguarded hand-built
 * territory, so every keep scans it again, and no plot is scanned, cleared or built while keeping is
 * off, over another plot's kept course or around a registered course; an edition is kept once; and
 * plot jobs keep the engine's guards: a hung re-made plan is killed, a keep cut short waits for the
 * boot checks, and nothing starts, or is promised to go on, that a restart would drop.
 */
class KeepTest {

    private static final String SLOT = "fresh_parkour_hard";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_HARD;
    private static final Box A = DEF.half('A');

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(on(1), SLOT);
        planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT)) {
            planners.put(id, new FakePlanner(id));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private static long on(int n) {
        LocalDate d = LocalDate.of(2026, 9, 29).plusDays(n - 1);
        return GenKit.at(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 4, 0) + 40_000;
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
    }

    private void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    /** Tick (a check every second) until the keep has placed some blocks of its course, but not all. */
    private void partWay() throws Exception {
        for (int t = 1; t <= 4000; t++) {
            gen.tick();
            host.now += 50;
            if (t % 20 == 0) {
                gen.check();
            }
            if (host.store.meta(GenAdminKeys.KEEP_PENDING) != null && host.world().count(area().plot(1)) > 3) {
                return;
            }
        }
        throw new AssertionError("the keep never got going: " + heard());
    }

    private GenTag build(int day) {
        host.now = on(day);
        if (gen == null) {
            boot();
            drive(70);
        } else {
            drive(20);
        }
        drive(15);
        return gen.liveTag(SLOT);
    }

    private void keep(String which, String id, String name, boolean fresh, boolean confirm) {
        said.clear();
        GenArgs.Which w = GenArgs.which(which);
        gen.keep(w.slot() != null ? null : SLOT, w, id, name, fresh, confirm, said::add);
    }

    private String heard() {
        return String.join("\n", said);
    }

    private KeepArea area() {
        return host.settings.archive().keep();
    }

    private Map<String, String> relative(Box b) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<Long, String> e : host.world().copy(b).entrySet()) {
            long p = e.getKey();
            int x = (int) (p >> 38);
            int z = (int) ((p << 26) >> 38);
            int y = (int) ((p << 52) >> 52);
            out.put((x - b.minX()) + "," + (y - b.minY()) + "," + (z - b.minZ()), e.getValue());
        }
        return out;
    }

    // ---- keeping -------------------------------------------------------------------------------------

    @Test
    void keepBuildsTheArchivedPlanIntoAPlotAndRegistersANormalCourseWithItsRecords() throws Exception {
        GenTag t1 = build(1);
        Map<String, String> original = relative(A);
        UUID amy = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        host.dao.submit(amy, "trials", GenBoards.day(t1), 61_000, true, host.now);
        host.dao.submit(bob, "trials", GenBoards.day(t1), 58_500, true, host.now);
        build(2);
        keep("HARD-1", "dragon_run", "Dragon Run", false, false);
        assertTrue(heard().contains("plot 1") && heard().contains("2 records") && heard().contains("confirm"),
                "without confirm it says what it would do: " + heard());
        drive(15);
        assertNull(host.dao.course("dragon_run"), "and does nothing");
        int trialsChanged = host.changed.getOrDefault("trials", 0);
        keep("HARD-1", "dragon_run", "Dragon Run", false, true);
        drive(20);
        GamesDao.CourseRow row = host.dao.course("dragon_run");
        assertNotNull(row, "the kept course is registered: " + heard());
        assertTrue(heard().contains("Kept!") && heard().contains("2 records were copied"), heard());
        assertEquals("trials", row.game(), "a trials course");
        assertEquals("parkour", row.kind(), "of the plan's kind");
        assertEquals("Dragon Run", row.name(), "named as asked");
        assertEquals(1, row.rev(), "at layout 1");
        assertFalse(Regions.hasGen(row.data()), "with NO gen block: a normal course from now on");
        Course c = CourseCodec.decode(row.id(), row.data()).course();
        assertTrue(c.problems(List.of(GenKit.WORLD)).isEmpty(), "the course tools' own checks pass: "
                + c.problems(List.of(GenKit.WORLD)));
        assertTrue(c.enabled(), "and it is open");
        Box build = area().build(1, DEF);
        Course moved = ((PlannedTrial) PlanShift.to(GenKit.plan(DEF, A, t1.seed(), 1), build).course()).course();
        assertEquals(moved.start(), c.start(), "its start moved into the plot");
        assertEquals(moved.checkpoints(), c.checkpoints(), "its checkpoints");
        assertEquals(moved.finish(), c.finish(), "its finish");
        assertEquals(moved.fallY(), c.fallY(), "and its fall height");
        assertEquals(moved.tier(), c.tier(), "with the plan's tier");
        assertEquals(original, relative(build), "block for block the original course, in plot 1");
        assertEquals(original.size(), host.world().count(area().plot(1)), "and nothing else in the plot");
        List<GamesDao.ScoreRow> board = host.dao.top("trials", "course:dragon_run", true, 5);
        assertEquals(List.of(bob, amy), board.stream().map(GamesDao.ScoreRow::player).toList(),
                "the old records are the ones to beat, in order");
        assertEquals("dragon_run", host.store.edition(SLOT, t1.editionKey()).keptAs(), "the archive says kept as");
        assertTrue(gen.history(SLOT, 1).stream().anyMatch(l -> l.startsWith("&fHARD-1 ") && l.contains("(kept as"
                + " dragon_run)")), "and so does history");
        assertTrue(gen.plots().stream().anyMatch(l -> l.startsWith("&f1: &edragon_run")), "plots lists it: "
                + gen.plots());
        assertEquals(trialsChanged + 1, host.changed.getOrDefault("trials", 0), "Time Trials was told");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "nothing is left pending");
        assertEquals(List.of(1), gen.usedPlots(), "plot 1 is used");
    }

    @Test
    void theFreshBoardFlagStartsEmptyAndTheNextFreePlotIsUsed() throws Exception {
        GenTag t1 = build(1);
        host.dao.submit(UUID.randomUUID(), "trials", GenBoards.day(t1), 61_000, true, host.now);
        keep("HARD-1", "first_copy", null, false, true);
        drive(20);
        GenTag t2 = build(2);
        host.dao.submit(UUID.randomUUID(), "trials", GenBoards.day(t2), 64_000, true, host.now);
        GenArgs.Which current = GenArgs.which("current");
        said.clear();
        gen.keep(SLOT, current, "second_copy", null, true, true, said::add);
        drive(20);
        assertNotNull(host.dao.course("second_copy"), "the current course was kept too: " + heard());
        assertEquals("Second Copy", host.dao.course("second_copy").name(), "named from its id");
        assertTrue(heard().contains("board starts empty"), heard());
        assertTrue(host.dao.top("trials", "course:second_copy", true, 5).isEmpty(), "--fresh-board copies nothing");
        assertEquals(1, host.dao.top("trials", "course:first_copy", true, 5).size(), "the first copy has its record");
        assertEquals(List.of(1, 2), gen.usedPlots(), "the next free plot was used");
        assertTrue(host.world().count(area().plot(2)) > 0, "and built in");
    }

    @Test
    void theNewIdObeysTheSameRulesAsAHandBuiltCourse() throws Exception {
        build(1);
        host.playIds.add("blackjack");
        for (String bad : List.of("fresh_golf", "fresh_classic_golf", "fresh_courses", "trials", "accept", "auto", "x",
                "bad-id", "9lives", "blackjack")) {
            keep("HARD-1", bad, null, false, true);
            assertTrue(heard().startsWith("&c"), "'" + bad + "' is refused: " + heard());
        }
        drive(20);
        assertTrue(gen.usedPlots().isEmpty(), "nothing was kept");
        keep("HARD-1", "dragon_run", null, false, true);
        drive(20);
        build(2);
        keep("HARD-2", "dragon_run", null, false, true);
        assertTrue(heard().contains("already a course called 'dragon_run'"), "an existing course: " + heard());
    }

    @Test
    void thePlotWriterThrowsOutsideItsPlot() {
        GenKit.FakeWorld world = host.world();
        Box plot = area().plot(3);
        HalfWriter w = new HalfWriter(world, plot);
        w.set(plot.minX(), plot.minY(), plot.minZ(), GenKit.CONCRETE);
        w.set(plot.maxX(), plot.maxY(), plot.maxZ(), GenKit.CONCRETE);
        assertEquals(2, world.writes, "inside it writes");
        Box next = area().plot(4);
        assertThrows(IllegalStateException.class, () -> w.set(next.minX(), next.minY(), next.minZ(), GenKit.CONCRETE),
                "never into the next plot");
        assertThrows(IllegalStateException.class, () -> w.set(plot.minX(), plot.maxY() + 1, plot.minZ(),
                GenKit.CONCRETE), "nor above it");
        assertThrows(IllegalStateException.class, () -> w.sign(plot.minX() - 1, plot.minY(), plot.minZ(),
                List.of("HI")), "nor a sign beside it");
        assertEquals(2, world.writes, "and nothing was written outside");
        Plan elsewhere = PlanShift.to(GenKit.plan(DEF, A, 7, 1), area().build(4, DEF));
        assertThrows(IllegalArgumentException.class, () -> new BuildJob(world, plot, elsewhere, BuildJob.Mode.CONVERGE),
                "a plan for another plot is refused before a block is set");
    }

    @Test
    void aPlotMustBeEmptyTheFirstTimeItIsUsed() throws Exception {
        build(1);
        Box plot = area().plot(1);
        host.world().put(plot.minX() + 100, plot.minY() + 3, plot.minZ() + 200, "minecraft:dirt");
        keep("HARD-1", "dragon_run", null, false, true);
        drive(20);
        assertTrue(heard().contains("Plot 1 has 1 block that isn't Fresh Courses'") && heard().contains(
                "claim plot 1 confirm"), "it is refused, with how to clear it: " + heard());
        assertNull(host.dao.course("dragon_run"), "nothing is registered");
        assertEquals(1, host.world().count(plot), "and nothing is touched");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "and nothing pending");
        said.clear();
        gen.claimPlot(1, false, said::add);
        drive(20);
        assertTrue(heard().contains("Plot 1 has 1 block"), "claim without confirm only counts: " + heard());
        said.clear();
        gen.claimPlot(1, true, said::add);
        drive(20);
        assertTrue(heard().contains("Plot 1 is cleared"), heard());
        assertEquals(0, host.world().count(plot), "confirm clears it");
        keep("HARD-1", "dragon_run", null, false, true);
        drive(20);
        assertNotNull(host.dao.course("dragon_run"), "and then keeping works: " + heard());
    }

    @Test
    void clearPlotTakesTheCourseAndItsBoardsDownMovesPeopleOutAndClearsThePlot() throws Exception {
        GenTag t1 = build(1);
        keep("HARD-1", "dragon_run", null, false, true);
        drive(20);
        UUID sam = UUID.randomUUID();
        host.dao.submit(sam, "trials", "course:dragon_run", 55_000, true, host.now);
        host.dao.submit(sam, "trials", "week:dragon_run:20720", 55_000, true, host.now);
        Box plot = area().plot(1);
        Box build = area().build(1, DEF);
        Person inside = new Person(sam, "Sam", GenKit.WORLD, build.minX() + 4.5, build.minY() + 11, build.minZ() + 4.5,
                "trials", "dragon_run");
        host.people.add(inside);
        said.clear();
        gen.clearPlot(1, false, said::add);
        assertTrue(heard().contains("deletes the course dragon_run") && heard().contains("confirm"), heard());
        drive(5);
        assertNotNull(host.dao.course("dragon_run"), "nothing without confirm");
        said.clear();
        gen.clearPlot(1, true, said::add);
        drive(20);
        assertNull(host.dao.course("dragon_run"), "the course row is gone");
        assertTrue(host.dao.top("trials", "course:dragon_run", true, 5).isEmpty(), "its all-time board too");
        assertTrue(host.dao.top("trials", "week:dragon_run:20720", true, 5).isEmpty(), "and its weekly boards");
        assertTrue(host.ended.contains(sam), "Sam's run ended");
        assertEquals(0, host.world().count(plot), "the plot is air again");
        assertNull(host.store.edition(SLOT, t1.editionKey()).keptAs(), "the archive no longer says kept");
        assertTrue(gen.usedPlots().isEmpty(), "the plot is free");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "and nothing is pending");
        host.people.clear();
        keep("HARD-1", "dragon_run", null, false, true);
        drive(20);
        assertEquals(List.of(1), gen.usedPlots(), "plot 1 can be used again at once");
    }

    @Test
    void aKeepStoppedHalfwayLeavesNothingRegisteredAndTheNextStartFinishesIt() throws Exception {
        GenTag t1 = build(1);
        Map<String, String> original = relative(A);
        keep("HARD-1", "dragon_run", null, false, true);
        partWay();
        assertNull(host.dao.course("dragon_run"), "nothing registered before verify");
        assertNotNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "the job is recorded");
        gen.stop();
        gen = null;
        host.now += 60_000;
        boot();
        drive(30);
        assertNotNull(host.dao.course("dragon_run"), "the next start finished it");
        assertEquals(original, relative(area().build(1, DEF)), "block for block");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "and forgot the job");
        assertEquals("dragon_run", host.store.edition(SLOT, t1.editionKey()).keptAs(), "kept as");
    }

    @Test
    void aKeepThatCantBeFinishedAfterAStopIsCleanedUp() throws Exception {
        build(1);
        keep("HARD-1", "dragon_run", null, false, true);
        partWay();
        gen.stop();
        gen = null;
        // meanwhile someone made a golf course of that name by hand
        host.dao.saveCourse(new GamesDao.CourseRow("dragon_run", "golf", "golf", "Dragon Run", GenKit.WORLD, false,
                "format: 1\nholes: []\n", 1, host.now, host.now));
        host.now += 60_000;
        boot();
        drive(30);
        assertEquals("golf", host.dao.course("dragon_run").game(), "the hand-built course is untouched");
        assertEquals(0, host.world().count(area().plot(1)), "the half-built plot is cleared");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "and the job forgotten");
        assertTrue(gen.usedPlots().isEmpty(), "no plot is taken");
    }

    @Test
    void aKeepThatFailsMidBuildIsNeverRegisteredAndItsPlotIsCleaned() throws Exception {
        build(1);
        keep("HARD-1", "dragon_run", null, false, true);
        host.world().killAfter = 5;
        drive(30);
        assertNull(host.dao.course("dragon_run"), "a build that failed registers nothing: " + heard());
        assertEquals(0, host.world().count(area().plot(1)), "and its plot is cleaned up");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "nothing pending");
    }

    // ---- a free plot is hand-built territory: scanned every time, and never cleared over a course ------

    @Test
    void everyKeepScansItsPlotSoWhatAnAdminBuiltInAFreePlotIsNeverWiped() throws Exception {
        build(1);
        keep("HARD-1", "dragon_run", null, false, true);
        drive(20);
        said.clear();
        gen.clearPlot(1, true, said::add);
        drive(20);
        Box plot = area().plot(1);
        assertEquals(0, host.world().count(plot), "plot 1 is air again: " + heard());
        // Nothing guards a free plot (admins may build anywhere in the keep area): one builds there.
        int bx = plot.minX() + 100;
        int by = plot.minY() + 3;
        int bz = plot.minZ() + 200;
        host.world().put(bx, by, bz, "minecraft:chest");
        build(2);
        keep("HARD-2", "second_run", null, false, true);
        drive(20);
        assertTrue(heard().contains("Plot 1 has 1 block that isn't Fresh Courses'"),
                "the keep scans the plot again, although it was cleared and found empty before: " + heard());
        assertEquals("minecraft:chest", host.world().at(bx, by, bz), "the admin's chest is untouched");
        assertNull(host.dao.course("second_run"), "nothing is registered");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "or pending");
        said.clear();
        gen.claimPlot(1, false, said::add);
        drive(20);
        assertTrue(heard().contains("Plot 1 has 1 block"), "claim plot counts it again too: " + heard());
    }

    @Test
    void claimPlotAndKeepAreRefusedWhileKeepingIsOffSoALiveHalfIsNeverCleared() throws Exception {
        GenTag t = build(1);
        Box live = DEF.half(t.half());
        long before = host.world().count(live);
        assertTrue(before > 0, "the live course stands");
        DailySettings.Archive shipped = host.settings.archive();
        KeepArea bad = new KeepArea(live.minX() - 8, live.minY(), live.minZ() - 8, 24);
        String problem = bad.problem(List.of(live));
        assertNotNull(problem, "config refuses an area on top of a half");
        host.settings = host.settings.withArchive(shipped.withKeep(bad, problem));
        for (boolean confirm : new boolean[]{false, true}) {
            said.clear();
            gen.claimPlot(1, confirm, said::add);
            assertTrue(heard().contains("Plot 1 can't be used") && heard().contains("keeping is off"),
                    "claim plot" + (confirm ? " confirm" : "") + " is refused: " + heard());
        }
        drive(20);
        assertEquals(before, host.world().count(live), "the live half is untouched");
        assertTrue(gen.live(SLOT, t), "and still open");
        // a keep asked for before a reload switched keeping off is checked again as it starts
        host.settings = host.settings.withArchive(shipped);
        keep("HARD-1", "dragon_run", null, false, true);
        host.settings = host.settings.withArchive(shipped.withKeep(shipped.keep(), "it is on top of a Fresh"
                + " Courses area"));
        drive(5);
        assertTrue(heard().contains("can't be used: keeping is off"), "refused as it starts: " + heard());
        assertNull(host.dao.course("dragon_run"), "nothing kept");
        assertEquals(0, host.world().count(shipped.keep().plot(1)), "nothing built");
    }

    @Test
    void afterTheKeepAreaMovesAPlotOnTopOfAKeptCourseIsNeverClearedOrBuiltIn() throws Exception {
        build(1);
        keep("HARD-1", "dragon_run", null, false, true);
        drive(20);
        Box old = area().plot(1);
        Map<String, String> standing = relative(old);
        assertFalse(standing.isEmpty(), "dragon_run stands in plot 1");
        KeepArea moved = new KeepArea(old.minX() - KeepArea.PLOT_X - area().gap(), old.minY(), old.minZ(), 24,
                area().gap());
        assertEquals(old, moved.plot(2), "the new plot 2 is where plot 1 was");
        host.settings = host.settings.withArchive(host.settings.archive().withKeep(moved, null));
        for (boolean confirm : new boolean[]{false, true}) {
            said.clear();
            gen.claimPlot(2, confirm, said::add);
            assertTrue(heard().contains("Plot 2 can't be used") && heard().contains("where dragon_run stands"),
                    "claim plot 2" + (confirm ? " confirm" : "") + " is refused: " + heard());
        }
        drive(20);
        assertEquals(standing, relative(old), "dragon_run's blocks are untouched");
        build(2);
        keep("HARD-2", "second_run", null, false, true);
        drive(25);
        assertNotNull(host.dao.course("second_run"), "a keep passes over that plot: " + heard());
        assertEquals(List.of(1, 3), gen.usedPlots(), "into plot 3");
        assertEquals(standing, relative(old), "and dragon_run still stands");
        assertNotNull(host.dao.course("dragon_run"), "registered as before");
    }

    @Test
    void aPlotWithARegisteredCourseInItIsNeverClearedOrBuiltIn() throws Exception {
        build(1);
        Box plot = area().plot(1);
        Course.Spot start = new Course.Spot(plot.minX() + 50.5, plot.minY() + 20, plot.minZ() + 50.5, 0f, 0f);
        Course.Mark finish = new Course.Mark(plot.minX() + 60.5, plot.minY() + 20, plot.minZ() + 60.5, 2.0);
        Course hand = new Course("cliff_hop", com.dierks.homecraft.games.trial.TrialKind.PARKOUR, "Cliff Hop",
                com.dierks.homecraft.games.trial.Tier.EASY, GenKit.WORLD, start, List.of(), finish,
                (double) plot.minY() + 10, null, true, false, 1);
        host.dao.saveCourse(new GamesDao.CourseRow("cliff_hop", "trials", "parkour", "Cliff Hop", GenKit.WORLD, true,
                CourseCodec.encode(hand), 1, host.now, host.now));
        host.world().put(plot.minX() + 50, plot.minY() + 19, plot.minZ() + 50, "minecraft:stone");
        said.clear();
        gen.claimPlot(1, true, said::add);
        assertTrue(heard().contains("Plot 1 can't be used") && heard().contains("cliff_hop is inside it"),
                "a hand-built course in the plot: refused, and named: " + heard());
        drive(20);
        assertEquals(1, host.world().count(plot), "its block stands");
        keep("HARD-1", "dragon_run", null, false, true);
        drive(25);
        assertNotNull(host.dao.course("dragon_run"), "a keep passes over plot 1: " + heard());
        assertEquals(List.of(2), gen.usedPlots(), "and uses plot 2");
        assertEquals(1, host.world().count(plot), "cliff_hop is untouched");
    }

    // ---- one kept course a set ---------------------------------------------------------------------------

    @Test
    void anEditionIsKeptOnceAndAPlotsCourseKeepsItsEditionArchived() throws Exception {
        host.settings = host.settings.withArchive(host.settings.archive().withKeepDays(1));
        GenTag t1 = build(1);
        keep("HARD-1", "first_copy", null, false, true);
        drive(20);
        keep("HARD-1", "second_copy", null, false, true);
        assertTrue(heard().contains("HARD-1 is already kept as first_copy"), "a second keep is refused: " + heard());
        said.clear();
        gen.keep(SLOT, GenArgs.which("current"), "third_copy", null, false, true, said::add);
        assertTrue(heard().contains("already kept as first_copy"), "by any name: " + heard());
        drive(20);
        assertEquals(List.of(1), gen.usedPlots(), "one plot, one course");
        assertEquals("first_copy", host.store.edition(SLOT, t1.editionKey()).keptAs(), "which the archive names");
        // Even with its kept_as lost, plot 1's course keeps HARD-1 archived (archive.keep is 1 day here).
        try (PreparedStatement ps = host.connection.prepareStatement(
                "UPDATE gen_editions SET kept_as = NULL WHERE code = 'HARD-1'")) {
            ps.executeUpdate();
        }
        for (int d = 2; d <= 5; d++) {
            build(d);
        }
        assertNotNull(host.store.edition(SLOT, t1.editionKey()), "HARD-1 stays archived: a plot's course came from it");
        assertNull(host.store.editionByCode("HARD-2"), "while an old one nobody kept is pruned");
    }

    // ---- the engine's guards hold for plot jobs too ------------------------------------------------------

    @Test
    void aReMadePlanThatHangsIsGivenUpLikeTheEnginesOwnAndTheBuildsGoOn() throws Exception {
        GenTag t1 = build(1);
        host.holdPlans = true;
        said.clear();
        gen.keep(SLOT, GenArgs.which("seed:" + com.dierks.homecraft.games.gen.api.GenSeed.hex(t1.seed())),
                "remade_run", null, false, true, said::add);
        for (int i = 0; i < 60 && host.plannerQueue.isEmpty(); i++) {
            drive(1);
        }
        assertFalse(host.plannerQueue.isEmpty(), "the keep's plan went to the planner: " + heard());
        host.holdPlans = false; // the engine's own plans run from now on; the keep's never answers
        drive(100);
        assertTrue(gen.keeper().busy(), "after 100 seconds it still waits for its plan");
        drive(25);
        assertTrue(heard().contains("planning took longer than 120 seconds"), "after 120 it is given up: " + heard());
        GenTag t2 = build(2);
        assertNotEquals(t1.editionKey(), t2.editionKey(), "and the next day's build went on");
        host.runPlans(); // the hung plan comes back at last
        drive(20);
        assertNull(host.dao.course("remade_run"), "and is dropped: nothing registered");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "nothing pending");
        assertEquals(0, host.world().count(area().plot(1)), "and its plot is empty");
    }

    @Test
    void aKeepCutShortWaitsForTheBootCheckOfTheLiveCoursesBeforeItGoesOn() throws Exception {
        GenTag t1 = build(1);
        keep("HARD-1", "dragon_run", null, false, true);
        partWay();
        gen.stop();
        gen = null;
        host.now += 60_000;
        boot();
        boolean plotFirst = false;
        for (int s = 0; s < 40; s++) {
            drive(1);
            plotFirst |= gen.keeper().busy() && !gen.live(SLOT, t1);
        }
        assertFalse(plotFirst, "the plot waited while the live course was still shut for its check");
        assertTrue(gen.live(SLOT, t1), "the live course was checked and opened");
        assertNotNull(host.dao.course("dragon_run"), "and then the keep was finished");
    }

    @Test
    void aKeepStoppedForARestartIsOnlyPromisedToGoOnWhenItWill() throws Exception {
        build(1);
        keep("HARD-1", "dragon_run", null, false, true);
        for (int i = 0; i < 200 && gen.keeper().jobKind() == null; i++) {
            gen.tick();
            host.now += 50;
            if (i % 20 == 19) {
                gen.check();
            }
        }
        assertEquals(KeepService.Kind.KEEP, gen.keeper().jobKind(), "the keep is checking its plot");
        said.clear();
        gen.keeper().cancel("a restart is less than 2 minutes away");
        assertTrue(heard().contains("Nothing was changed; run it again after the restart"), heard());
        assertFalse(heard().contains("goes on after the restart"), "never promised to go on: " + heard());
        assertFalse(gen.keeper().hasWork(), "nothing waits");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "and nothing is recorded");
        keep("HARD-1", "dragon_run", null, false, true);
        partWay();
        said.clear();
        gen.keeper().cancel("a restart is less than 2 minutes away");
        assertTrue(heard().contains("It goes on after the restart"), "one that has built is: " + heard());
        assertNotNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "it is recorded");
    }

    @Test
    void aKeepWaitingWhenTheRestartHoldBeginsIsNotStartedAndItsAdminTold() throws Exception {
        build(1);
        keep("HARD-1", "dragon_run", null, false, true); // it would start at the next check
        host.restarts = List.of(java.time.LocalTime.of(4, 12)); // it is about 4:02: inside the 15 minutes
        drive(3);
        assertTrue(heard().contains("not started - a restart is due at 4:12 AM")
                && heard().contains("run it again after the restart"), heard());
        assertNull(gen.keeper().jobKind(), "it never started");
        assertFalse(gen.keeper().hasWork(), "and doesn't wait for a restart that drops it");
        assertEquals(0, host.world().count(area().plot(1)), "nothing was built");
        assertNull(host.store.meta(GenAdminKeys.KEEP_PENDING), "or recorded");
    }

    @Test
    void keepIsRefusedWhenTheAreaIsFullOffOrThePlanUnreadable() throws Exception {
        build(1);
        DailySettings.Archive shipped = host.settings.archive();
        host.settings = host.settings.withArchive(shipped.withKeep(new KeepArea(4096, 128, 5376, 1), null));
        keep("HARD-1", "one_plot", null, false, true);
        drive(20);
        GenTag t2 = build(2);
        keep("HARD-2", "two_plots", null, false, true);
        assertTrue(heard().contains("The keep area is full (1 plots)"), heard());
        host.settings = host.settings.withArchive(shipped.withKeep(shipped.keep(), "it is on top of a Fresh Courses"
                + " area"));
        keep("HARD-2", "three", null, false, true);
        assertTrue(heard().contains("Keeping is off"), heard());
        host.settings = host.settings.withArchive(shipped);
        try (PreparedStatement ps = host.connection.prepareStatement(
                "UPDATE gen_editions SET plan = X'00' WHERE code = 'HARD-2'")) {
            ps.executeUpdate();
        }
        keep("HARD-2", "four", null, false, true);
        assertTrue(heard().contains("can't be read") && heard().contains("seed:"
                + com.dierks.homecraft.games.gen.api.GenSeed.hex(t2.seed())), "unreadable: the seed is offered: "
                + heard());
        said.clear();
        gen.keep(SLOT, GenArgs.which("seed:" + com.dierks.homecraft.games.gen.api.GenSeed.hex(t2.seed())), "four",
                null, false, true, said::add);
        drive(20);
        assertNotNull(host.dao.course("four"), "made again from its seed: " + heard());
        assertTrue(heard().contains("re-made"), "and said to be re-made: " + heard());
    }

    // ---- the adapter, on its own ------------------------------------------------------------------------

    @Test
    void aMovedGolfCourseAndTrialPassTheCourseGamesOwnValidation() {
        KeepArea a = LegacyBoxes.keep();
        Plan golf = PlanShift.to(golfPlan(), a.build(5, Slots.TINY_GOLF));
        GamesDao.CourseRow g = KeptCourses.row("mini_links", "Mini Links", GenKit.WORLD, golf.course(), 1000);
        assertEquals("golf", g.game(), "a golf row");
        assertTrue(KeptCourses.problems(g, List.of(GenKit.WORLD)).isEmpty(), "Mini Golf's own checks pass: "
                + KeptCourses.problems(g, List.of(GenKit.WORLD)));
        GolfCourse back = com.dierks.homecraft.games.golf.CourseCodec.fromRow(g);
        assertNull(back.gen(), "no gen block");
        assertTrue(back.playable(List.of(GenKit.WORLD)), "and it is playable");
        Box bounds = a.plot(5);
        for (GolfCourse.Hole h : back.holes()) {
            assertTrue(bounds.contains(h.cup().x(), h.cup().y(), h.cup().z()), "every cup moved into the plot");
        }
        Plan trial = PlanShift.to(GenKit.plan(DEF, A, 11, 1), a.build(6, DEF));
        GamesDao.CourseRow t = KeptCourses.row("cliff_hop", "Cliff Hop", GenKit.WORLD, trial.course(), 1000);
        assertTrue(KeptCourses.problems(t, List.of(GenKit.WORLD)).isEmpty(), "Time Trials' own checks pass");
        assertFalse(KeptCourses.problems(t, List.of("elsewhere")).isEmpty(), "and would say so for an unlisted world");
    }

    /** A two-hole Tiny Golf plan in its 0.35 half B, tees and cups inside their bounds. */
    private static Plan golfPlan() {
        Box half = LegacyBoxes.half(Slots.TINY_GOLF, 'B');
        List<GolfCourse.Hole> holes = List.of(
                new GolfCourse.Hole(new GolfCourse.Tee(5220.5, 164.0, 4100.5, 180.0f), new GolfCourse.Spot(5220, 162,
                        4112), 2, new GolfCourse.Spot(5217, 161, 4097), new GolfCourse.Spot(5224, 168, 4115)),
                new GolfCourse.Hole(new GolfCourse.Tee(5242.5, 164.0, 4100.5, 0.5f), new GolfCourse.Spot(5242, 162,
                        4118), 3, new GolfCourse.Spot(5239, 161, 4097), new GolfCourse.Spot(5246, 168, 4121)));
        PlannedGolf pg = new PlannedGolf(new GolfCourse("fresh_tiny_golf", "Tiny Golf", "", false, 1, holes),
                List.of(0, 3), List.of(List.of(new Putt(12.5f, 4)), List.of(new Putt(270.0f, 4))), List.of(1, 1),
                List.of(2, 3));
        return Plan.of("fresh_tiny_golf", 2, -77L, half, List.of("minecraft:lime_concrete"),
                List.of(new BlockOp(5220, 163, 4100, (short) 0)), List.of(), List.of(), pg, List.of(), 99_000);
    }
}
