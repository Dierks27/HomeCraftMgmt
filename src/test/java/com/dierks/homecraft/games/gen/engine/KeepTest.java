package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
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
 * refused when the area is full, off, or the plan unreadable.
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
        keep("HARD-1", "dragon_run", null, false, true);
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

    @Test
    void keepIsRefusedWhenTheAreaIsFullOffOrThePlanUnreadable() throws Exception {
        GenTag t1 = build(1);
        DailySettings.Archive shipped = host.settings.archive();
        host.settings = host.settings.withArchive(shipped.withKeep(new KeepArea(4096, 128, 5376, 1), null));
        keep("HARD-1", "one_plot", null, false, true);
        drive(20);
        keep("HARD-1", "two_plots", null, false, true);
        assertTrue(heard().contains("The keep area is full (1 plots)"), heard());
        host.settings = host.settings.withArchive(shipped.withKeep(shipped.keep(), "it is on top of a Fresh Courses"
                + " area"));
        keep("HARD-1", "three", null, false, true);
        assertTrue(heard().contains("Keeping is off"), heard());
        host.settings = host.settings.withArchive(shipped);
        try (PreparedStatement ps = host.connection.prepareStatement(
                "UPDATE gen_editions SET plan = X'00' WHERE code = 'HARD-1'")) {
            ps.executeUpdate();
        }
        keep("HARD-1", "four", null, false, true);
        assertTrue(heard().contains("can't be read") && heard().contains("seed:"
                + com.dierks.homecraft.games.gen.api.GenSeed.hex(t1.seed())), "unreadable: the seed is offered: "
                + heard());
        said.clear();
        gen.keep(SLOT, GenArgs.which("seed:" + com.dierks.homecraft.games.gen.api.GenSeed.hex(t1.seed())), "four",
                null, false, true, said::add);
        drive(20);
        assertNotNull(host.dao.course("four"), "made again from its seed: " + heard());
        assertTrue(heard().contains("re-made"), "and said to be re-made: " + heard());
    }

    // ---- the adapter, on its own ------------------------------------------------------------------------

    @Test
    void aMovedGolfCourseAndTrialPassTheCourseGamesOwnValidation() {
        KeepArea a = new KeepArea(4096, 128, 5376, 24);
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

    /** A two-hole Tiny Golf plan in its half B, tees and cups inside their bounds. */
    private static Plan golfPlan() {
        Box half = Slots.TINY_GOLF.half('B');
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
