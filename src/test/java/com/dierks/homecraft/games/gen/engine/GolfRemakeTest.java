package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.V3GolfFixtures;
import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.golf.CourseCodec;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 0.36 (algo-3, Adventure Golf) golf edition made again from its seed — a {@code seed:} recall or keep —
 * on the real engine with the real golf planner (the audit's GOLF01-GOLF04, FX-GOLF). The engine's golf
 * planner is Golf v4, which would make another course from that seed (other holes, lengths and pars), so:
 * <ul>
 *   <li>an algo-3 edition is made again by the frozen Adventure Golf planner ({@code GolfPlanner.v3()}) in its
 *       own 0.36 size: it is the SAME course, block for block its stored plan (its plan hash, moved), so its
 *       old board and records are honestly its own — the recall stays on its board, the keep copies them;</li>
 *   <li>an edition of a version the planner keeps no copy of (algo 2) is made by today's Golf v4: the reply
 *       says "made again with today's golf (v4): not the same holes", a keep starts on an empty board, and a
 *       recall says its board's records were set on the old course.</li>
 * </ul>
 * And the hint beside an edition whose stored plan can't be read says which of the two a {@code seed:} would make
 * ("the same course again from its seed" or "it again with today's golf (v4: not the same holes)", FX-LAST).
 */
class GolfRemakeTest {

    private static final String GOTW = Slots.DAILY_GOLF.id();
    private static final String TINY = Slots.TINY_GOLF.id();
    private static final String CLASSIC = Slots.CLASSIC_GOLF.id();

    private Host host;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(on(1), TINY);
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new FakePlanner(id));
        }
        planners.put(Slots.GOLF, new GolfPlanner()); // the engine's: Golf v4
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        drive(70);
        drive(15);
        assertNotNull(gen.liveTag(TINY), "the engine is up, Tiny Golf built");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private static long on(int n) {
        LocalDate d = LocalDate.of(2026, 9, 29).plusDays(n - 1);
        return GenKit.at(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 4, 0) + 40_000;
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

    private String heard() {
        return String.join("\n", said);
    }

    /**
     * {@code f} archived as an ended weekly edition of its slot made by golf planner version {@code algo}, with
     * its stored plan, and two players' rounds on its board (25 and 27 strokes).
     */
    private GenArchiveDao.Row archived(V3GolfFixtures.Fixture f, int algo) throws Exception {
        String edition = Edition.editionKey(Edition.WEEKLY, f.day(), 0);
        new GenArchiveDao(host.db).archive(new GenArchiveDao.Row(f.slot().id(), edition, null, 0, f.day(), f.seed(),
                Slots.GOLF + "/" + algo, f.slot().kind(), f.mix(), f.slot().name(), 1000L, null,
                PlanCodec.encode(f.plan()), 0, 0, 1000L, null), 1000L);
        try (PreparedStatement ps = host.connection.prepareStatement(
                "UPDATE gen_editions SET ends_at = 2000 WHERE slot = ? AND edition = ?")) {
            ps.setString(1, f.slot().id());
            ps.setString(2, edition);
            ps.executeUpdate();
        }
        GenArchiveDao.Row row = host.store.edition(f.slot().id(), edition);
        host.dao.submit(UUID.randomUUID(), Slots.GAME_GOLF, row.board(), 25, true, host.now);
        host.dao.submit(UUID.randomUUID(), Slots.GAME_GOLF, row.board(), 27, true, host.now);
        return row;
    }

    private static String seed(V3GolfFixtures.Fixture f) {
        return "seed:" + GenSeed.hex(f.seed()).substring(0, 12);
    }

    // ---- an Adventure Golf edition comes back as it was ---------------------------------------------------

    @Test
    void aReMadeRecallOfAnAlgo3GolfOfTheWeekIsTheSameCourseOnItsOwnBoard() throws Exception {
        V3GolfFixtures.Fixture f = V3GolfFixtures.named("golf-9-a");
        GenArchiveDao.Row row = archived(f, GolfPlanner.ALGO_V3);
        said.clear();
        gen.recall(CLASSIC, GOTW, GenArgs.which(seed(f)), GenArgs.DAYS_DEFAULT, false, said::add);
        assertTrue(heard().contains(row.code()) && heard().contains("re-made from its seed")
                && heard().contains("its old records are the ones to beat"), "the old records are its own: " + heard());
        assertFalse(heard().contains("not the same"), "it is the same course: " + heard());
        for (int s = 0; s < 120 && gen.liveTag(CLASSIC) == null; s++) {
            drive(1);
        }
        GenTag c = gen.liveTag(CLASSIC);
        assertNotNull(c, "made again from its seed: " + heard() + " " + gen.status(CLASSIC));
        assertEquals(GolfPlanner.ALGO_V3, c.algo(), "by Adventure Golf, the planner that made it, never Golf v4");
        Box h = Slots.CLASSIC_GOLF.half(c.half());
        Box own = Box.sized(h.minX(), h.minY(), h.minZ(), 64, 16, 128);
        assertEquals(f.plan().half().sizeX(), own.sizeX(), "in its own 0.36 size (x)");
        assertEquals(f.plan().half().sizeZ(), own.sizeZ(), "and (z)");
        Plan moved = PlanShift.to(f.plan(), own);
        assertEquals(moved.hash(), c.planHash(), "its stored plan's hash: block for block the archived course, moved");
        assertEquals(row.board(), GenBoards.day(c), "so its old board is honestly its own");
        assertEquals(moved.ops().size() + moved.signs().size(), host.world().count(h), "and nothing else stands there");
        GolfCourse played = CourseCodec.fromRow(host.dao.course(CLASSIC));
        assertEquals(9, played.holes().size(), "nine holes");
        assertEquals(((PlannedGolf) f.plan().course()).course().par(), played.par(),
                "at the par it was made with");
    }

    @Test
    void aReMadeKeepOfAnAlgo3GolfOfTheWeekIsTheSameCourseWithItsRecords() throws Exception {
        V3GolfFixtures.Fixture f = V3GolfFixtures.named("golf-9-b");
        GenArchiveDao.Row row = archived(f, GolfPlanner.ALGO_V3);
        said.clear();
        gen.keep(GOTW, GenArgs.which(seed(f)), "old_week", null, false, false, said::add);
        assertTrue(heard().contains("made again from its seed (re-made)") && heard().contains("its 2 records are"
                + " copied onto it"), "the same holes: its records come with it: " + heard());
        assertFalse(heard().contains("not the same"), heard());
        said.clear();
        gen.keep(GOTW, GenArgs.which(seed(f)), "old_week", null, false, true, said::add);
        for (int s = 0; s < 200 && host.dao.course("old_week") == null; s++) {
            drive(1);
        }
        GamesDao.CourseRow kept = host.dao.course("old_week");
        assertNotNull(kept, "kept: " + heard());
        assertTrue(heard().contains("2 records were copied"), heard());
        GolfCourse c = CourseCodec.fromRow(kept);
        assertEquals(GolfPlanner.ALGO_V3, c.keptAlgo(), "kept as the Adventure Golf course it was (2:00 clock)");
        Box plot = host.settings.archive().keep().plot(1);
        Box build = Box.sized(plot.minX() + KeepArea.MARGIN, plot.minY(), plot.minZ() + KeepArea.MARGIN, 64, 16, 128);
        Plan moved = PlanShift.to(f.plan(), build);
        assertEquals(((PlannedGolf) moved.course()).course().holes(), c.holes(),
                "its stored plan's holes, moved into the plot");
        assertEquals(moved.ops().size() + moved.signs().size(), host.world().count(plot), "block for block");
        assertEquals(2, host.dao.top(Slots.GAME_GOLF, KeptCourses.board(Slots.GAME_GOLF, "old_week"), true, 5).size(),
                "its records are on its lasting board");
    }

    // ---- a version the planner keeps no copy of: another course, said so ------------------------------------

    @Test
    void aReMadeKeepByAnotherVersionSaysSoAndStartsOnAnEmptyBoard() throws Exception {
        V3GolfFixtures.Fixture f = V3GolfFixtures.named("golf-3");
        GenArchiveDao.Row row = archived(f, 2); // a Tiny Golf of 0.35 (algo 2): no frozen copy of that planner
        said.clear();
        gen.keep(TINY, GenArgs.which(seed(f)), "old_tiny", null, false, false, said::add);
        assertTrue(heard().contains("starting empty."), "a different course doesn't take the old records: " + heard());
        assertFalse(heard().contains("copied"), heard());
        assertTrue(heard().contains("It is made again with today's golf (v4): not the same holes as " + row.code()
                + ", so its board starts empty."), "and says why: " + heard());
        said.clear();
        gen.keep(TINY, GenArgs.which(seed(f)), "old_tiny", null, false, true, said::add);
        assertTrue(heard().contains("not the same holes"), "said again as it starts: " + heard());
        for (int s = 0; s < 200 && host.dao.course("old_tiny") == null; s++) {
            drive(1);
        }
        GamesDao.CourseRow kept = host.dao.course("old_tiny");
        assertNotNull(kept, "kept: " + heard());
        assertTrue(heard().contains("Its board starts empty."), heard());
        assertEquals(GolfPlanner.ALGO, CourseCodec.fromRow(kept).keptAlgo(), "made by today's Golf v4");
        assertEquals(0, host.dao.top(Slots.GAME_GOLF, KeptCourses.board(Slots.GAME_GOLF, "old_tiny"), true, 5).size(),
                "no record of the old course is copied onto it");
        assertEquals(2, host.dao.top(Slots.GAME_GOLF, row.board(), true, 5).size(), "the old board is untouched");
    }

    @Test
    void aReMadeRecallByAnotherVersionSaysItIsNotTheSameHoles() throws Exception {
        V3GolfFixtures.Fixture f = V3GolfFixtures.named("golf-3");
        GenArchiveDao.Row row = archived(f, 2);
        said.clear();
        gen.recall(CLASSIC, TINY, GenArgs.which(seed(f)), GenArgs.DAYS_DEFAULT, false, said::add);
        assertTrue(heard().contains("made again with today's golf (v4): not the same holes"), heard());
        assertFalse(heard().contains("the ones to beat"), "its old records aren't promised as the ones to beat: "
                + heard());
        assertTrue(heard().contains("its board is still " + row.code() + "'s, whose records were set on the old"
                + " course."), heard());
        for (int s = 0; s < 120 && gen.liveTag(CLASSIC) == null; s++) {
            drive(1);
        }
        GenTag c = gen.liveTag(CLASSIC);
        assertNotNull(c, "made again: " + heard() + " " + gen.status(CLASSIC));
        assertEquals(GolfPlanner.ALGO, c.algo(), "by today's Golf v4");
    }

    // ---- the hint beside an unreadable plan is true per edition (FX-LAST) ----------------------------------

    /** {@code row}'s stored plan made unreadable. */
    private void unreadable(GenArchiveDao.Row row) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(
                "UPDATE gen_editions SET plan = X'00' WHERE slot = ? AND edition = ?")) {
            ps.setString(1, row.slot());
            ps.setString(2, row.edition());
            ps.executeUpdate();
        }
    }

    @Test
    void anUnreadableAlgo3PlansHintSaysTheSameCourseAgainNotTodaysGenerator() throws Exception {
        V3GolfFixtures.Fixture f = V3GolfFixtures.named("golf-9-a");
        GenArchiveDao.Row row = archived(f, GolfPlanner.ALGO_V3);
        unreadable(row);
        String hex = GenSeed.hex(f.seed());
        String history = String.join("\n", gen.historyOf(GOTW, GenArgs.which(row.code())));
        assertTrue(history.contains("can't be read") && history.contains("&7- make the same course again from its seed:"
                + " recall ... seed:" + hex), history);
        said.clear();
        gen.recall(CLASSIC, GOTW, GenArgs.which(row.code()), GenArgs.DAYS_DEFAULT, false, said::add);
        assertTrue(heard().contains("&7Make the same course again from its seed, marked re-made: &e/hcm games gen recall "
                + CLASSIC + " " + GOTW + " seed:" + hex), heard());
        said.clear();
        gen.keep(GOTW, GenArgs.which(row.code()), "old_week", null, false, false, said::add);
        assertTrue(heard().contains("&7Make the same course again from its seed, marked re-made: &e/hcm games gen keep "
                + GOTW + " seed:" + hex + " old_week"), heard());
        assertFalse((history + heard()).contains("today's"), "Adventure Golf is kept frozen, not made by Golf v4: "
                + history + "\n" + heard());
    }

    @Test
    void anUnreadablePlanOfAnotherVersionsHintSaysTodaysGolfAndNotTheSameHoles() throws Exception {
        V3GolfFixtures.Fixture f = V3GolfFixtures.named("golf-3");
        GenArchiveDao.Row row = archived(f, 2);
        unreadable(row);
        String hex = GenSeed.hex(f.seed());
        String history = String.join("\n", gen.historyOf(TINY, GenArgs.which(row.code())));
        assertTrue(history.contains("&7- make it again with today's golf (v4: not the same holes): recall ... seed:"
                + hex), history);
        said.clear();
        gen.recall(CLASSIC, TINY, GenArgs.which(row.code()), GenArgs.DAYS_DEFAULT, false, said::add);
        assertTrue(heard().contains("&7Make it again with today's golf (v4: not the same holes), marked re-made: &e/hcm"
                + " games gen recall " + CLASSIC + " " + TINY + " seed:" + hex), heard());
        said.clear();
        gen.keep(TINY, GenArgs.which(row.code()), "old_tiny", null, false, false, said::add);
        assertTrue(heard().contains("&7Make it again with today's golf (v4: not the same holes), marked re-made: &e/hcm"
                + " games gen keep " + TINY + " seed:" + hex + " old_tiny"), heard());
        assertFalse((history + heard()).contains("the same course"), history + "\n" + heard());
    }
}
