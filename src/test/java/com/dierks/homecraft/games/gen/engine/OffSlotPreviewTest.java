package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A course that is switched off can be previewed and tried before it is switched on (CV final gate):
 * Ice Boat ships off, and the owner's plan is "Gate 0 and a preview before switching it on". With the
 * engine, a fake clock and world and the real database, at the shipped weekly cadence, Ice Boat off in
 * config and never built.
 *
 * <p>Pinned here: {@code preview} and {@code preview next} on it check and claim its area first (an
 * area with foreign blocks, or next to a hand-built course, is refused as a build's is, touching
 * nothing), build into the spare half only, and never flip: no course row, no archived edition, no
 * course change told, nothing open; the test run's course is the preview's and never live, and
 * {@code tp idle} goes to its start; {@code promote} and {@code choose} are refused while it is off,
 * saying what switching it on does; the preview stands while it stays off; and switching it on builds
 * the set's own course (not the preview) in that half, over the preview, and opens it, as every reply
 * said. The explicit boxes are the slot's halves as the engine holds them.
 */
class OffSlotPreviewTest {

    private static final String SLOT = "fresh_boat";
    private static final Slots.Def DEF = Slots.ICE_BOAT;
    /** Monday 28 September 2026: the week the tests start in. */
    private static final long MON_28_SEP = 20724;
    private static final long MON_5_OCT = MON_28_SEP + 7;
    private static final String SEED_HEX = "0000000000005eed";

    private Host host;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000);
        host.settings = GenKit.weekly(); // every slot off, as Ice Boat ships
        planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, new FakePlanner(Slots.PARKOUR));
        planners.put(Slots.RINGS, new FakePlanner(Slots.RINGS));
        planners.put(Slots.GOLF, new FakePlanner(Slots.GOLF));
        planners.put(Slots.BOAT, new FakePlanner(Slots.BOAT));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
    }

    /** Twenty ticks and a check per second, 50 ms a tick. */
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

    private Box half(char which) {
        return gen.slot(SLOT).half(which);
    }

    /** Booted past its start-up delay with Ice Boat off: nothing built, nothing claimed. */
    private void offAndEmpty() throws Exception {
        boot();
        drive(70);
        assertFalse(gen.slot(SLOT).wanted(), "fixture: Ice Boat is off, as it ships");
        assertNull(gen.liveTag(SLOT), "fixture: nothing is built while it is off");
        assertNull(host.store.meta(GenAdminKeys.claim(SLOT)), "fixture: its area isn't claimed yet");
        assertEquals(0, host.world().writes, "fixture: not a block written");
    }

    /** Nothing a live course leaves: no row, no archived edition, no flip, no course change, nothing open. */
    private void nothingRecorded(String when) throws Exception {
        assertNull(gen.liveTag(SLOT), when + ": nothing is live");
        assertNull(host.dao.course(SLOT), when + ": no course row, so no course screen, board or reward");
        assertEquals(0, host.store.editionCount(SLOT), when + ": no archived edition (no history, no course code)");
        assertEquals(0, host.store.flips, when + ": never flipped");
        assertTrue(host.changed.isEmpty(), when + ": no course change was told to the games: " + host.changed);
    }

    @Test
    void aPreviewOfACourseThatIsOffIsBuiltAndTriedButNeverOpensOrRecordsAnything() throws Exception {
        offAndEmpty();

        gen.preview(SLOT, SEED_HEX, said::add);
        assertTrue(heard().contains("A preview of Ice Boat (seed " + SEED_HEX + ") is on its way into half A."),
                "a slot that is off can be previewed: " + heard());
        assertTrue(heard().contains("Ice Boat stays off: only an admin's test run can play the preview, and nothing"
                + " is recorded or paid."), "the reply says it stays off: " + heard());
        assertTrue(heard().contains("/hcm games gen on " + SLOT) && heard().contains("opens its own course for this"
                + " set, not the preview"), "and what switching it on does: " + heard());
        drive(10);

        SlotState.Preview pv = gen.slot(SLOT).preview;
        assertNotNull(pv, "the preview stands: " + heard());
        assertEquals('A', pv.half(), "in the spare half (nothing is live, so half A)");
        assertEquals(0x5eedL, pv.seed(), "on the seed asked for");
        assertEquals(MON_28_SEP, pv.day(), "made as this week's set");
        assertNotNull(host.store.meta(GenAdminKeys.claim(SLOT)), "its area was scanned and claimed first, as a build's is");
        assertTrue(host.world().count(half('A')) > 0, "the preview's blocks are in half A");
        assertEquals(0, host.world().count(half('B')), "and nothing in half B");
        assertTrue(heard().contains("The preview of Ice Boat is ready in half A (seed " + SEED_HEX + ")"),
                "it says it is ready: " + heard());
        assertTrue(heard().contains("Try it: &e/hcm games gen test " + SLOT) && heard().contains("tp " + SLOT + " idle"),
                "and how to try it: " + heard());
        assertFalse(heard().contains("promote") || heard().contains("choose"),
                "not to promote or choose it, which it can't be while off: " + heard());
        nothingRecorded("a preview while off");

        // Tried: the test run is the preview's own course, never live; tp idle goes to its start.
        GenOps.PreviewRun run = gen.previewRun(SLOT);
        assertNull(run.refusal(), "the test run isn't refused while the course is off: " + run.refusal());
        Course c = run.course();
        Course planned = ((PlannedTrial) pv.plan().course()).course();
        assertEquals(planned.start(), c.start(), "the run starts at the preview's start");
        assertEquals(planned.finish(), c.finish(), "and ends at its finish");
        GenTag t = c.gen();
        assertFalse(gen.live(SLOT, t), "never live: no board, no reward, no stars");
        assertFalse(gen.standing(t), "and never a standing layout a run could count on");
        GenOps.Spot idle = gen.spot(SLOT, true);
        assertEquals(planned.start().x(), idle.x(), "tp idle: the preview's start");
        assertEquals(planned.start().z(), idle.z(), "tp idle: the preview's start");

        // The next set's candidate, too.
        said.clear();
        gen.previewNext(SLOT, "3f2a", said::add);
        assertTrue(heard().contains("for Mon 5 Oct-Sun 11 Oct") && heard().contains("into half A"),
                "preview next works while off: " + heard());
        drive(10);
        assertEquals(MON_5_OCT, gen.slot(SLOT).preview.day(), "the candidate for next week replaced it");
        assertNotNull(gen.previewRun(SLOT).course(), "and can be tried");
        nothingRecorded("preview next while off");

        // Promote and choose are refused while off, saying what to do; nothing is stored.
        said.clear();
        gen.promote(SLOT, true, said::add);
        assertTrue(heard().contains("Ice Boat is off, so no preview can be made its course.")
                && heard().contains("&e/hcm games gen on " + SLOT + " &7opens it with its own course for this set"),
                "promote: " + heard());
        said.clear();
        gen.choose(SLOT, true, said::add);
        assertTrue(heard().contains("Ice Boat is off, so no preview can be picked for a set.")
                && heard().contains("Previews and test runs work while it's off"), "choose: " + heard());
        assertNull(host.store.meta(GenAdminKeys.choose(SLOT)), "no pick is stored");
        assertFalse(gen.tools(SLOT).on(), "the admin tools see it off");

        drive(120);
        assertNotNull(gen.slot(SLOT).preview, "the preview stands while it stays off (no clearing, no build)");
        nothingRecorded("two minutes later");
    }

    @Test
    void switchingOnAfterAPreviewBuildsTheSetsOwnCourseOverItAndOpensIt() throws Exception {
        offAndEmpty();
        gen.preview(SLOT, SEED_HEX, said::add);
        drive(10);
        assertNotNull(gen.slot(SLOT).preview, "fixture: the preview stands in half A");

        said.clear();
        gen.enable(SLOT, true, said::add);
        assertEquals("&aIce Boat is on. &7Its course for Mon 28 Sep-Sun 4 Oct is built next, in half A over the preview"
                + " there, and opens once it's built.", heard(), "the switch-on says what happens");
        drive(20);

        GenTag live = gen.liveTag(SLOT);
        assertNotNull(live, "the set's course went up");
        assertEquals(MON_28_SEP, live.day(), "this week's");
        assertEquals('A', live.half(), "in half A, where the preview stood");
        assertEquals(GenSeed.seed(host.store.secret(), 7, MON_28_SEP, SLOT, 0), live.seed(),
                "on the set's own seed, as documented: not the preview's");
        assertNotEquals(0x5eedL, live.seed(), "not the preview's seed");
        assertTrue(gen.live(SLOT, live), "and it is open");
        assertNull(gen.slot(SLOT).preview, "the preview is gone: its half holds the live course");
        assertEquals(GenKit.plan(DEF, half('A'), live.seed(), 1).ops().size() + 1, host.world().count(half('A')),
                "half A holds exactly the live course");
    }

    @Test
    void anOffCoursesPreviewIsRefusedWhereABuildWouldBeAndTouchesNothing() throws Exception {
        offAndEmpty();
        Box a = half('A');

        // A hand-built course next to its area: refused at once, as a build would be.
        Course near = new Course("river_run", TrialKind.PARKOUR, "River Run", Tier.EASY, GenKit.WORLD,
                new Course.Spot(a.minX() - 10, a.minY() + 5, a.minZ() + 5, 0f, 0f), List.of(), null, null, null,
                false, false, 1);
        host.dao.saveCourse(new GamesDao.CourseRow("river_run", "trials", "parkour", "River Run", GenKit.WORLD, false,
                CourseCodec.encode(near), 1, 0, 0));
        gen.preview(SLOT, null, said::add);
        assertTrue(heard().startsWith("&cIce Boat can't be previewed: &7") && heard().contains("river_run"),
                "refused, naming the course: " + heard());
        drive(10);
        assertNull(gen.slot(SLOT).preview, "nothing was built");
        assertEquals(0, host.world().writes, "not a block written");
        host.dao.deleteCourse("river_run");

        // The same course saved after the preview was asked for, before its job starts: refused at the start.
        said.clear();
        gen.previewNext(SLOT, null, said::add);
        assertTrue(heard().contains("is on its way"), "asked for with nothing in the way: " + heard());
        host.dao.saveCourse(new GamesDao.CourseRow("river_run", "trials", "parkour", "River Run", GenKit.WORLD, false,
                CourseCodec.encode(near), 1, 0, 0));
        drive(10);
        assertTrue(heard().contains("Ice Boat can't be built: &7") && heard().contains("river_run"),
                "its job checks the area as a build's does, though the course is off: " + heard());
        assertNull(gen.slot(SLOT).preview, "nothing was built");
        assertNull(host.store.meta(GenAdminKeys.claim(SLOT)), "nothing was claimed");
        assertEquals(0, host.world().writes, "not a block written");
        host.dao.deleteCourse("river_run");

        // Foreign blocks in its area: the preview's claim scan finds them and writes nothing.
        host.world().put(a.minX() + 7, a.minY() + 3, a.minZ() + 9, "minecraft:stone");
        said.clear();
        gen.preview(SLOT, null, said::add);
        drive(10);
        assertTrue(heard().contains("1 block that isn't Fresh Courses'") && heard().contains("claim " + SLOT + " confirm"),
                "the scan says so, and how to clear them: " + heard());
        assertNull(gen.slot(SLOT).preview, "nothing was built");
        assertEquals(0, host.world().writes, "not a block written");
        assertEquals("minecraft:stone", host.world().at(a.minX() + 7, a.minY() + 3, a.minZ() + 9), "the block stays");
        said.clear();
        gen.preview(SLOT, null, said::add);
        assertTrue(heard().startsWith("&cIce Boat can't be previewed: &7Region has 1 block"),
                "asked again, the standing problem is said at once: " + heard());
        assertEquals(0, host.store.flips, "and nothing ever flipped");
    }
}
