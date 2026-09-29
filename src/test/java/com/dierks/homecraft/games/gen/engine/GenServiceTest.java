package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
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

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine with a fake clock, fake planners and a fake world over a real in-memory database
 * (GEN-SPEC §3, §6 S3).
 *
 * <p>Pinned here: the day's cycle (rollover, build, flip, still standing, CLEAR_OLD once nobody is
 * on the old half); every boot path of §3.5 — a stop while planning, mid-converge, after verify
 * but before the flip, after the flip but before the world was saved, mid-CLEAR_OLD, and a planner
 * version change — each converging on an intact course with the gate shut until it is verified; a
 * flip the database refuses keeps the old layout live; a pin restamps with no blocks; no build
 * while the secret can't be read; tries and retries; a second quick reroll gives a player on the
 * old-old layout their 20 minutes; foreign blocks and hand-built courses are never touched; and
 * nothing is started, or left running, two minutes before a restart.
 */
class GenServiceTest {

    private static final String SLOT = "daily_parkour_easy";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    private static final long DAY1 = 20725; // Tue 29 Sep 2026
    private static final Box A = DEF.half('A');
    private static final Box B = DEF.half('B');

    private Host host;
    private FakePlanner parkour;
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        parkour = new FakePlanner(Slots.PARKOUR);
        planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, parkour);
        planners.put(Slots.RINGS, new FakePlanner(Slots.RINGS));
        planners.put(Slots.GOLF, new FakePlanner(Slots.GOLF));
        planners.put(Slots.BOAT, new FakePlanner(Slots.BOAT));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** A fresh engine over the same database and world: an enable, or a boot after a crash. */
    private void boot() {
        if (gen != null) {
            gen.stop();
        }
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
    }

    /** Run the engine for {@code seconds}: twenty ticks and a check per second, 50 ms a tick. */
    private void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    /** Tick (with a check every twenty ticks) until {@code done} holds, at most {@code max} ticks. */
    private void stepUntil(java.util.function.BooleanSupplier done, int max) {
        for (int t = 1; t <= max && !done.getAsBoolean(); t++) {
            gen.tick();
            host.now += 50;
            if (t % 20 == 0) {
                gen.check();
            }
        }
    }

    private GenTag tag() {
        return gen.liveTag(SLOT);
    }

    private GamesDao.CourseRow row() throws Exception {
        return host.dao.course(SLOT);
    }

    private int planned() {
        return GenKit.plan(DEF, A, 1, 1).ops().size() + 1;
    }

    private void nextDay(int day) {
        host.now = GenKit.at(2026, 9, day, 4, 0) + 40_000;
    }

    private Person player(Box where, String course) {
        return new Person(UUID.randomUUID(), "Sam", GenKit.WORLD, where.minX() + 4.5, where.minY() + 11,
                where.minZ() + 4.5, course == null ? null : "trials", course);
    }

    // ---- the day's cycle -------------------------------------------------------------------------

    @Test
    void rolloverBuildFlipStillStandingThenClearOld() throws Exception {
        boot();
        drive(30);
        assertNull(tag(), "nothing is built before startup_delay_seconds");
        drive(40);
        GenTag day1 = tag();
        assertNotNull(day1, "the first set is up a minute after the worlds are ready");
        assertEquals(DAY1, day1.day(), "for today's course day");
        assertEquals('A', day1.half(), "in half A");
        assertTrue(gen.live(SLOT, day1), "and the gate is open on it");
        assertEquals(planned(), host.world().count(A), "half A holds exactly the plan");
        assertEquals(1, host.changed.getOrDefault("trials", 0), "Time Trials was told its courses changed");
        GamesDao.CourseRow r = row();
        assertEquals("trials", r.game(), "an ordinary trials row");
        Course c = CourseCodec.decode(r.id(), r.data()).course();
        assertEquals(day1, c.gen(), "carrying the gen tag");
        assertEquals(DEF.name(), c.name(), "under the slot's name");
        assertTrue(day1.goldMs() > 0 && day1.silverMs() > day1.goldMs(), "with star times fixed at the flip");
        assertEquals(GenKit.at(2026, 9, 30, 4, 0), gen.nextChangeAt(), "the next set is due at tomorrow's 04:00");

        Person onA = player(A, SLOT);
        host.people.add(onA); // mid-run on today's course when the next one flips
        nextDay(30);
        drive(20);
        GenTag day2 = tag();
        assertEquals(DAY1 + 1, day2.day(), "the next day's course is built");
        assertEquals('B', day2.half(), "into the other half");
        assertTrue(gen.live(SLOT, day2), "and is live");
        assertFalse(gen.live(SLOT, day1), "yesterday's can't be started any more");
        assertTrue(gen.standing(day1), "but it still stands: the run on it counts");
        assertEquals(planned(), host.world().count(A), "its blocks are all still there");

        drive(60);
        assertTrue(gen.standing(day1), "CLEAR_OLD waits for them, with no timeout");
        assertEquals(planned(), host.world().count(A), "and touches nothing meanwhile");

        host.people.clear();
        drive(15);
        assertEquals(0, host.world().count(A), "once nobody is on it the old half is emptied");
        assertFalse(gen.standing(day1), "and yesterday's layout no longer stands");
        assertTrue(gen.standing(day2), "today's does");
        assertEquals(planned(), host.world().count(B), "today's half is untouched");
    }

    @Test
    void theGateIsShutAtBootUntilTheLiveHalfIsVerified() {
        boot();
        drive(70);
        GenTag day1 = tag();
        // The server died after the flip and before the world was saved: the half is back to air.
        host.world().blocks.clear();
        host.world().signs.clear();
        gen.stop();
        gen = new GenService(host, planners);
        gen.start();
        assertFalse(gen.live(SLOT, day1), "the gate is shut the moment the engine starts");
        assertEquals(GenCopy.building(DEF.name()), gen.closedLine(SLOT), "players read that it is being built");
        gen.worldsReady();
        assertFalse(gen.live(SLOT, day1), "still shut before the check has run");
        drive(3);
        assertTrue(gen.live(SLOT, day1), "open once the half is healed and verified");
        assertEquals(planned(), host.world().count(A), "the unsaved blocks were put back");
        assertEquals(1, host.logged(Level.SEVERE, "healed"), "a boot heal is SEVERE in the log, once");
        assertEquals(day1, tag(), "the same layout, not a new one");
    }

    @Test
    void aBootMidHealChecksAgainHealsTheRestAndOpens() {
        boot();
        drive(70);
        GenTag day1 = tag();
        host.world().blocks.clear();
        host.world().signs.clear();
        host.settings = withBudget(host.settings, 3);
        boot();
        stepUntil(() -> host.world().count(A) > 0, 2_000);
        long partial = host.world().count(A);
        assertTrue(partial > 0 && partial < planned(), "the heal stopped partway: " + partial);
        assertFalse(gen.live(SLOT, day1), "the gate is still shut");
        host.settings = GenKit.settings(SLOT);
        boot();
        assertFalse(gen.live(SLOT, day1), "shut again at the next boot");
        drive(3);
        assertTrue(gen.live(SLOT, day1), "the second check healed the rest and opened it");
        assertEquals(planned(), host.world().count(A), "every block is back");
    }

    @Test
    void aBootWhileTheNextLayoutWasPlanningVerifiesTheOldOneThenBuildsTheNew() {
        boot();
        drive(70);
        GenTag day1 = tag();
        nextDay(30);
        host.holdPlans = true;
        drive(2);
        assertEquals(GenService.Kind.BUILD, gen.jobKind(), "the next build is planning");
        host.holdPlans = false;
        boot(); // the crash: the plan in flight is lost
        drive(2);
        assertTrue(gen.live(SLOT, day1), "the old layout is verified and opened first");
        drive(70);
        assertEquals(DAY1 + 1, tag().day(), "then the new one is built");
        assertEquals('B', tag().half(), "into B");
        host.runPlans(); // the lost plan finally answers the dead engine
        assertEquals(DAY1 + 1, tag().day(), "and changes nothing");
    }

    @Test
    void aBootMidConvergeFinishesTheIdleHalfFromWhereItStopped() {
        boot();
        drive(70);
        nextDay(30);
        host.settings = withBudget(host.settings, 3);
        gen.check();
        stepUntil(() -> host.world().count(B) > 0, 2_000);
        long partial = host.world().count(B);
        assertTrue(partial > 0 && partial < planned(), "half B is partly built: " + partial);
        host.settings = GenKit.settings(SLOT);
        boot();
        drive(80);
        assertEquals(DAY1 + 1, tag().day(), "the new layout flipped after the boot");
        assertEquals(planned(), host.world().count(B), "half B holds exactly its plan");
        assertEquals(0, host.world().count(A), "and yesterday's, with nobody on it, was emptied");
    }

    @Test
    void aFlipTheDatabaseRefusesKeepsTheOldLayoutLiveAndABootThenFlipsWithoutWrites() throws Exception {
        boot();
        drive(70);
        GenTag day1 = tag();
        int rev = row().rev();
        nextDay(30);
        host.store.flipFails = true;
        drive(20);
        assertEquals(day1, tag(), "the old layout is still the live one");
        assertTrue(gen.live(SLOT, day1), "and still open");
        assertEquals(rev, row().rev(), "the row wasn't touched");
        assertEquals(1, gen.slot(SLOT).tries, "one failed try");
        assertTrue(gen.slot(SLOT).lastError.contains("database"), "saying why: " + gen.slot(SLOT).lastError);
        assertEquals(planned(), host.world().count(B), "half B was built and verified before the flip");

        host.store.flipFails = false;
        long writes = host.world().writes;
        boot();
        stepUntil(() -> tag() != null && tag().day() == DAY1 + 1, 20 * 90);
        assertEquals(DAY1 + 1, tag().day(), "after the boot the new layout flips");
        assertEquals(writes, host.world().writes, "without a single block written: B already matched its plan");
        assertEquals(rev + 1, row().rev(), "one rev on");
    }

    @Test
    void aHalfThatNeverVerifiesNeverFlips() {
        com.dierks.homecraft.games.gen.api.BlockOp stuck = GenKit.plan(DEF, A, 1, 1).ops().get(3);
        GenKit.FakeWorld refusing = new GenKit.FakeWorld(GenKit.WORLD) {
            @Override
            public void set(int x, int y, int z, String state) {
                if (x == stuck.x() && y == stuck.y() && z == stuck.z()) {
                    return; // this block never takes, whatever is written
                }
                super.set(x, y, z, state);
            }
        };
        host.worlds.put(GenKit.WORLD, refusing);
        boot();
        drive(70);
        assertNull(tag(), "nothing flipped");
        assertEquals(0, host.store.flips, "the database was never asked to flip");
        assertEquals(1, gen.slot(SLOT).tries, "a failed try");
        assertTrue(gen.slot(SLOT).lastError.contains(stuck.x() + "," + stuck.y() + "," + stuck.z()),
                "naming the block that didn't match: " + gen.slot(SLOT).lastError);
    }

    @Test
    void aBootMidClearOldEmptiesTheIdleHalfOnceNothingElseIsDue() {
        boot();
        drive(70);
        host.settings = withBudget(host.settings, 3);
        nextDay(30);
        stepUntil(() -> tag().half() == 'B', 20_000);
        assertEquals('B', tag().half(), "day two is live in B");
        stepUntil(() -> host.world().count(A) < planned(), 20_000);
        long left = host.world().count(A);
        assertTrue(left > 0 && left < planned(), "CLEAR_OLD of A stopped partway: " + left);
        host.settings = GenKit.settings(SLOT);
        boot();
        drive(90);
        assertEquals(0, host.world().count(A), "after the boot the idle half is emptied anyway");
        assertTrue(gen.live(SLOT, tag()), "and B is verified and open");
    }

    @Test
    void aLayoutFromAnOlderPlannerIsCheckedStructurallyAndOpensWithAWarning() {
        boot();
        drive(70);
        GenTag day1 = tag();
        parkour.algo = 2;
        int rederives = parkour.rederives;
        boot();
        drive(3);
        assertTrue(gen.live(SLOT, day1), "the old layout opens");
        assertEquals(rederives, parkour.rederives, "without deriving it again (it can't be)");
        assertEquals(1, host.logged(Level.WARNING, "older version"), "with one WARN");

        Course c = ((PlannedTrial) GenKit.plan(DEF, A, day1.seed(), 1).course()).course();
        int sx = (int) Math.floor(c.start().x());
        int sz = (int) Math.floor(c.start().z());
        host.world().blocks.remove(GenKit.pos(sx, (int) c.start().y() - 1, sz));
        boot();
        drive(3);
        assertFalse(gen.live(SLOT, day1), "a layout that fails the check stays closed");
        assertTrue(host.logged(Level.SEVERE, "vouched") >= 1, "and says so");
    }

    // ---- pins, secrets, tries --------------------------------------------------------------------

    @Test
    void aPinnedLayoutIsRestampedForANewDayWithNoBlocks() throws Exception {
        boot();
        drive(70);
        GenTag day1 = tag();
        int rev = row().rev();
        gen.pin(SLOT, "today", 0, said::add);
        assertTrue(said.get(0).contains("pinned"), said.toString());
        long writes = host.world().writes;
        nextDay(30);
        drive(5);
        GenTag day2 = tag();
        assertEquals(DAY1 + 1, day2.day(), "the pinned layout is today's");
        assertTrue(day2.sameLayout(day1), "the same blocks");
        assertEquals(writes, host.world().writes, "and not one block written");
        assertEquals(rev + 1, row().rev(), "the row moved on one rev");
        assertNotEquals(GenBoards.day(day1), GenBoards.day(day2), "so it has a fresh day board");
        assertTrue(gen.live(SLOT, day2), "and it is open");
        assertTrue(gen.standing(day1), "a run started on it yesterday still counts");
    }

    @Test
    void noBuildWhileTheSecretCantBeRead() {
        host.store.secretFails = true;
        boot();
        drive(180);
        assertNull(tag(), "no secret, no build");
        assertEquals(0, gen.slot(SLOT).tries, "and it isn't a failed try");
        assertEquals(1, host.logged(Level.WARNING, "secret"), "one WARN (at most one an hour)");
        host.store.secretFails = false;
        drive(20);
        assertNotNull(tag(), "the build goes once it can be read");
    }

    @Test
    void aPlannerThatFailsOrThrowsIsAFailedTryRetriedLaterThenGivenUpForTheDay() {
        parkour.fail = new IllegalStateException("a planner bug");
        parkour.failAlways = true;
        boot();
        drive(70);
        assertNull(tag(), "nothing flips");
        assertEquals(1, gen.slot(SLOT).tries, "one failed try");
        assertEquals(1, host.logged(Level.SEVERE, "planner threw"), "the bug is in the log");
        drive(60 * 29);
        assertEquals(1, gen.slot(SLOT).tries, "no second try before retry_minutes");
        drive(90);
        assertEquals(2, gen.slot(SLOT).tries, "then another");
        parkour.fail = new StackOverflowError();
        drive(60 * 31 * 2);
        assertEquals(4, gen.slot(SLOT).tries, "an Error is a failed try too, up to max_tries_per_day");
        drive(60 * 45);
        assertEquals(4, gen.slot(SLOT).tries, "then it gives up for the day");
        assertTrue(gen.summary().stream().anyMatch(l -> l.contains(SLOT)), "and status says so: " + gen.summary());

        parkour.fail = new GenFailed(GenFailed.NOT_BUILT);
        nextDay(30);
        drive(70);
        assertEquals(1, gen.slot(SLOT).triesOn(DAY1 + 1), "a new day starts counting again");
        assertTrue(gen.slot(SLOT).lastError.contains("not built yet"), "a GenFailed says why");
        parkour.failAlways = false;
        parkour.fail = null;
        drive(60 * 31);
        assertEquals(DAY1 + 1, tag().day(), "and once the planner works, the course goes up");
    }

    @Test
    void aWorldThatThrowsMidBuildIsAFailedTryAndNeverEscapesTheEngine() {
        host.world().killAfter = 3;
        boot();
        drive(70); // would throw out of the test if anything escaped tick() or check()
        assertNull(tag(), "nothing flipped");
        assertEquals(1, gen.slot(SLOT).tries, "it is a failed try");
        assertEquals(1, host.logged(Level.SEVERE, "building " + SLOT + " failed"), "logged with the slot");
        assertTrue(host.world().tickets.isEmpty(), "and its chunk tickets were released");
        drive(60 * 31);
        assertNotNull(tag(), "the retry builds it");
    }

    // ---- people -----------------------------------------------------------------------------------

    @Test
    void aSecondQuickRerollGivesAPlayerOnTheOldOldLayoutTwentyMinutes() {
        boot();
        drive(70);
        GenTag first = tag();
        Person runner = player(A, SLOT);
        host.people.add(runner);
        gen.reroll(SLOT, said::add);
        drive(20);
        GenTag second = tag();
        assertEquals(1, second.reroll(), "the reroll is live");
        assertEquals('B', second.half(), "in B");
        assertTrue(gen.standing(first), "the runner's layout still stands");

        gen.reroll(SLOT, said::add);
        drive(3);
        assertEquals(GenService.Kind.BUILD, gen.jobKind(), "the second reroll's build waits for half A");
        assertTrue(host.told.contains(GenCopy.comingHere(20)), "the runner is told: " + host.told);
        assertTrue(gen.standing(first), "their layout still stands while they finish");
        assertEquals(planned(), host.world().count(A), "and nothing in A has changed");
        drive(60 * 19);
        assertTrue(host.told.contains(GenCopy.ONE_MINUTE), "one minute left");
        assertFalse(host.bars.isEmpty(), "with reminders on the action bar");
        assertTrue(host.ended.isEmpty(), "their run hasn't been ended yet");
        drive(90);
        assertEquals(List.of(runner.id()), host.ended, "when the time is up their run ends");
        assertTrue(host.told.contains(GenCopy.timesUp(SLOT)), "and they are told why");
        drive(30);
        assertEquals(2, tag().reroll(), "then the build goes on and flips");
        assertEquals('A', tag().half(), "into A");
    }

    @Test
    void someoneWhoIsNotPlayingItIsMovedOutOfTheIdleHalfAtOnce() {
        boot();
        drive(70);
        Person visitor = player(B, null);
        host.people.add(visitor);
        nextDay(30);
        drive(3);
        assertEquals(List.of(visitor.id()), host.moved, "a visitor in the half being built is moved");
        assertTrue(host.told.contains(GenCopy.MOVED), "and told why");
        drive(20);
        assertEquals('B', tag().half(), "the build went ahead");
    }

    @Test
    void aHealWaitsForSomeoneStandingInTheWayThenMovesThemButNeverARunnerOnIt() {
        boot();
        drive(70);
        List<com.dierks.homecraft.games.gen.api.BlockOp> ops = GenKit.plan(DEF, A, tag().seed(), 1).ops();
        com.dierks.homecraft.games.gen.api.BlockOp mine = ops.get(0);
        com.dierks.homecraft.games.gen.api.BlockOp theirs = ops.get(9);
        host.world().blocks.remove(GenKit.pos(mine.x(), mine.y(), mine.z()));
        host.world().blocks.remove(GenKit.pos(theirs.x(), theirs.y(), theirs.z()));
        Person runner = new Person(UUID.randomUUID(), "Runner", GenKit.WORLD, mine.x() + 0.5, mine.y() + 1,
                mine.z() + 0.5, "trials", SLOT);
        Person visitor = new Person(UUID.randomUUID(), "Visitor", GenKit.WORLD, theirs.x() + 0.5, theirs.y() + 1,
                theirs.z() + 0.5, null, null);
        host.people.add(runner);
        host.people.add(visitor);
        gen.rebuild(SLOT, said::add);
        drive(10);
        assertTrue(host.moved.isEmpty(), "at first everyone in the way is waited for");
        assertNull(host.world().at(theirs.x(), theirs.y(), theirs.z()), "no block is put into anyone");
        drive(25);
        assertEquals(List.of(visitor.id()), host.moved, "after 30 seconds in the way the visitor is moved");
        assertTrue(host.told.contains(GenCopy.MOVED), "and told why");
        drive(2);
        assertNotNull(host.world().at(theirs.x(), theirs.y(), theirs.z()), "then that block goes in");
        drive(60);
        assertFalse(host.moved.contains(runner.id()), "someone mid-run on the course being healed is never moved");
        assertNull(host.world().at(mine.x(), mine.y(), mine.z()), "and the block under them waits");
        host.people.clear();
        drive(2);
        assertNotNull(host.world().at(mine.x(), mine.y(), mine.z()), "until they step away");
        assertEquals(1, host.logged(Level.WARNING, "waiting for someone to step away"), "one WARN for the wait");
    }

    // ---- areas that aren't ours --------------------------------------------------------------------

    @Test
    void aForeignBlockOnTheFirstClaimRefusesTheSlotWithoutWritingThenClaimConfirmClearsIt() {
        host.world().put(A.minX() + 7, A.minY() + 3, A.minZ() + 9, "minecraft:stone");
        boot();
        drive(70);
        assertNull(tag(), "nothing is built");
        assertEquals(0, host.world().writes, "not a single block written");
        assertEquals("minecraft:stone", host.world().at(A.minX() + 7, A.minY() + 3, A.minZ() + 9), "the block is still there");
        String problem = gen.slot(SLOT).problem;
        assertNotNull(problem, "the slot is off");
        assertTrue(problem.contains("1 block that isn't") && problem.contains((A.minX() + 7) + "," + (A.minY() + 3)),
                "naming the count and the first block: " + problem);
        assertTrue(host.logged(Level.SEVERE, "Daily Courses' (first at") >= 1, "SEVERE in the log");

        gen.claim(SLOT, true, said::add);
        drive(80);
        assertNull(host.world().at(A.minX() + 7, A.minY() + 3, A.minZ() + 9), "claim confirm cleared it");
        assertNotNull(tag(), "and the course was built");
    }

    @Test
    void aHandBuiltCourseNearAHalfOrOnASlotsIdIsNeverTouched() throws Exception {
        Course near = new Course("river_run", TrialKind.PARKOUR, "River Run", Tier.EASY, GenKit.WORLD,
                new Course.Spot(A.minX() - 10, A.minY() + 5, A.minZ() + 5, 0f, 0f), List.of(), null, null, null,
                false, false, 1);
        host.dao.saveCourse(new GamesDao.CourseRow("river_run", "trials", "parkour", "River Run", GenKit.WORLD, false,
                CourseCodec.encode(near), 1, 0, 0));
        boot();
        drive(90);
        assertNull(tag(), "the slot is off");
        assertTrue(gen.slot(SLOT).problem.contains("river_run"), "naming the course: " + gen.slot(SLOT).problem);
        assertEquals(0, host.world().writes, "nothing was built");
        host.dao.deleteCourse("river_run");

        Course mine = new Course(SLOT, TrialKind.PARKOUR, "My Parkour", Tier.EASY, "world",
                new Course.Spot(0, 70, 0, 0f, 0f), List.of(), null, null, null, false, false, 1);
        host.dao.saveCourse(new GamesDao.CourseRow(SLOT, "trials", "parkour", "My Parkour", "world", false,
                CourseCodec.encode(mine), 1, 0, 0));
        boot();
        drive(90);
        assertTrue(gen.slot(SLOT).problem.contains("wasn't made by Daily Courses"), gen.slot(SLOT).problem);
        assertEquals("My Parkour", row().name(), "the hand-built row is never taken over");
    }

    // ---- restarts -------------------------------------------------------------------------------------

    @Test
    void nothingStartsCloseToARestartAndAJobStillRunningTwoMinutesBeforeOneIsGivenUp() {
        host.restarts = List.of(LocalTime.of(4, 0), LocalTime.of(16, 0));
        host.now = GenKit.at(2026, 9, 29, 15, 42) + 40_000;
        host.world().asyncLoads = true; // the chunks never arrive: the job is still going at 15:58
        boot();
        drive(61);
        assertEquals(GenService.Kind.BUILD, gen.jobKind(), "a build starts 16 minutes before the restart");
        drive(60 * 13);
        assertEquals(GenService.Kind.BUILD, gen.jobKind(), "still going at 15:56");
        drive(90);
        assertNull(gen.jobKind(), "two minutes before the restart it is given up");
        assertNull(tag(), "nothing flipped");
        assertEquals(0, gen.slot(SLOT).tries, "that isn't the build's fault: no try is counted");
        assertTrue(host.logged(Level.INFO, "restart") >= 1, "the log says why");
        assertTrue(host.world().tickets.isEmpty(), "its chunk tickets are released");
        drive(50);
        assertNull(gen.jobKind(), "and nothing starts again before the restart");

        host.world().asyncLoads = false;
        host.now = GenKit.at(2026, 9, 30, 3, 50);
        drive(5);
        assertNull(gen.jobKind(), "the next day's build waits out avoid_before_restart_minutes before 04:00");
        assertTrue(gen.slot(SLOT).waiting != null && gen.slot(SLOT).waiting.contains("restart"),
                "and status says why: " + gen.slot(SLOT).waiting);
    }

    @Test
    void aSlotSwitchedOffShutsItsGateAndEndsItsRuns() {
        boot();
        drive(70);
        GenTag day1 = tag();
        Person runner = player(A, SLOT);
        host.people.add(runner);
        gen.enable(SLOT, false, said::add);
        assertFalse(gen.live(SLOT, day1), "the gate is shut");
        assertEquals(List.of(runner.id()), host.ended, "the run on it ended");
        assertTrue(host.told.contains(GenCopy.closed(DEF.name())), "with the closed line");
        assertEquals(planned(), host.world().count(A), "its blocks stay");
        gen.enable(SLOT, true, said::add);
        drive(2);
        assertTrue(gen.live(SLOT, day1), "switched on again it opens (already verified)");
    }

    // ---- review fixes ------------------------------------------------------------------------------

    @Test
    void claimConfirmNeverClearsAHandBuiltCourseOrTheSpawnInARegionRefusedForThem() throws Exception {
        int sx = A.minX() + 20;
        int sy = A.minY() + 5;
        int sz = A.minZ() + 20;
        Course hand = new Course("river_run", TrialKind.PARKOUR, "River Run", Tier.EASY, GenKit.WORLD,
                new Course.Spot(sx + 0.5, sy + 1, sz + 0.5, 0f, 0f), List.of(), null, null, null, false, false, 1);
        host.dao.saveCourse(new GamesDao.CourseRow("river_run", "trials", "parkour", "River Run", GenKit.WORLD, true,
                CourseCodec.encode(hand), 1, 0, 0));
        host.world().put(sx, sy, sz, "minecraft:gold_block"); // its start pad, inside half A
        boot();
        drive(90);
        assertTrue(gen.slot(SLOT).problem.contains("river_run"), "refused for the course: " + gen.slot(SLOT).problem);

        gen.claim(SLOT, true, said::add);
        drive(60);
        assertEquals("minecraft:gold_block", host.world().at(sx, sy, sz), "claim confirm never clears a hand-built course");
        assertEquals(0, host.world().writes, "nothing at all is written");
        assertTrue(said.stream().anyMatch(l -> l.contains("can't be claimed") && l.contains("river_run")),
                "the admin is told why: " + said);
        gen.claim(SLOT, false, said::add);
        drive(10);
        assertFalse(gen.slot(SLOT).claimed, "a plain claim (a scan) is refused too, so the region is never claimed");

        host.dao.deleteCourse("river_run");
        host.world().blocks.clear();
        host.world().spawn = new int[]{A.minX() + 30, A.minY() + 10, A.minZ() + 30};
        host.world().put(A.minX() + 30, A.minY() + 9, A.minZ() + 30, "minecraft:stone_bricks");
        boot();
        drive(90);
        assertTrue(gen.slot(SLOT).problem.contains("spawn"), "refused for the spawn: " + gen.slot(SLOT).problem);
        said.clear();
        gen.claim(SLOT, true, said::add);
        drive(60);
        assertEquals("minecraft:stone_bricks", host.world().at(A.minX() + 30, A.minY() + 9, A.minZ() + 30),
                "claim confirm never clears the spawn either");
        assertTrue(said.stream().anyMatch(l -> l.contains("can't be claimed")), "and says why: " + said);
    }

    @Test
    void aClearedSlotSwitchedOnAfterARestartIsScannedThenBuiltOnAFreshBoard() throws Exception {
        boot();
        drive(70);
        GenTag first = tag();
        assertNotNull(first, "built");
        gen.clear(SLOT, said::add);
        drive(30);
        assertEquals(0, host.world().count(A), "cleared, and the claim is forgotten");
        boot(); // a restart the same day
        drive(5);
        gen.enable(SLOT, true, said::add);
        drive(120);
        GenTag after = tag();
        assertNotEquals(first.editionKey(), after.editionKey(), "a new course on a fresh board, not the old one's");
        assertTrue(gen.live(SLOT, after), "and it opens within minutes, not tomorrow");
        assertTrue(gen.slot(SLOT).claimed, "after the area was scanned and claimed again");
        assertTrue(host.logged(Level.WARNING, "isn't in its claimed region") >= 1, "the log says why it was rebuilt");
    }

    @Test
    void aRebuildOfALiveRowInAnUnclaimedRegionScansFirstAndNeverClearsSomeoneElsesBlock() {
        boot();
        drive(70);
        assertNotNull(tag(), "built");
        gen.clear(SLOT, said::add);
        drive(30);
        host.world().put(A.minX() + 40, A.minY() + 2, A.minZ() + 40, "minecraft:oak_planks"); // built while unguarded
        boot();
        drive(5);
        gen.enable(SLOT, true, said::add);
        gen.rebuild(SLOT, said::add);
        drive(60);
        assertEquals("minecraft:oak_planks", host.world().at(A.minX() + 40, A.minY() + 2, A.minZ() + 40),
                "a block Daily Courses never owned is left alone");
        assertFalse(gen.live(SLOT, tag()), "the course doesn't open in an area it hasn't claimed");
        assertTrue(gen.slot(SLOT).problem != null && gen.slot(SLOT).problem.startsWith("Region has 1 block"),
                "the scan found the block and says so: " + gen.slot(SLOT).problem);
    }

    @Test
    void aRunnerOnThePreviousLayoutOffBothHalvesIsWaitedForByClearOldAndTheNextReroll() {
        boot();
        drive(70);
        GenTag first = tag();
        Person glider = new Person(UUID.randomUUID(), "Kid", GenKit.WORLD, A.minX() - 20.5, A.minY() + 20,
                A.minZ() + 20.5, "trials", SLOT);
        host.people.add(glider);
        gen.reroll(SLOT, said::add);
        drive(60);
        assertEquals(1, tag().reroll(), "the reroll flipped");
        assertTrue(gen.standing(first), "the runner's layout still stands while they are off its half");
        assertEquals(planned(), host.world().count(A), "and CLEAR_OLD leaves its blocks alone");

        gen.reroll(SLOT, said::add);
        drive(5);
        assertEquals(1, tag().reroll(), "the second reroll waits for them");
        assertTrue(host.told.contains(GenCopy.comingHere(20)), "and they are told they have 20 minutes: " + host.told);
        host.people.clear(); // they finish
        drive(30);
        assertEquals(2, tag().reroll(), "then the build goes on");
        assertEquals('A', tag().half(), "into their old half");
    }

    @Test
    void aFailedBootCheckRebuildsTodayOnAFreshBoardWithTheNewTier() throws Exception {
        boot();
        drive(70);
        GenTag first = tag();
        gen.tier(SLOT, "hard", said::add);
        parkour.rederiveFail = new GenFailed("fall depth changed");
        host.now = GenKit.at(2026, 9, 29, 16, 0) + 40_000; // the 16:00 restart
        boot();
        drive(120);
        GenTag second = tag();
        assertEquals(DAY1, second.day(), "still today's course");
        assertEquals(1, second.reroll(), "but under the next reroll");
        assertNotEquals(first.editionKey(), second.editionKey(), "so the new layout gets its own day board");
        assertEquals("1", host.store.meta("gen." + SLOT + ".reroll." + DAY1), "and the reroll is kept");
        assertTrue(gen.live(SLOT, second), "it opens");
    }

    @Test
    void aPinThatNoLongerAppliesIsLoggedShownAsUnusedAndDoesntBlockAReroll() throws Exception {
        boot();
        drive(70);
        gen.pin(SLOT, "today", 0, said::add);
        parkour.algo = 2; // a plugin update
        boot();
        nextDay(30);
        drive(120);
        assertEquals(1, host.logs.stream().filter(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("pinned seed") && r.getMessage().contains("v1")).count(),
                "one WARN says the pin is ignored and why");
        assertTrue(gen.status(SLOT).stream().anyMatch(l -> l.contains("not used")), "status says so: " + gen.status(SLOT));
        said.clear();
        gen.reroll(SLOT, said::add);
        assertTrue(said.get(0).contains("gets a new course"), "and a reroll isn't refused: " + said);

        parkour.algo = 1;
        gen.unpin(SLOT, said::add);
        drive(70);
        gen.pin(SLOT, "today", 1, said::add); // for today only
        host.now = GenKit.at(2026, 10, 1, 4, 0) + 40_000;
        drive(120);
        assertNull(host.store.meta("gen." + SLOT + ".pin"), "a pin whose days are over is forgotten");
        assertEquals(1, host.logged(Level.INFO, "ended on"), "with one line");
    }

    @Test
    void whatAFinishPaysComesFromGamesDailyNotTheShippedAmounts() {
        com.dierks.homecraft.games.gen.DailySettings d = host.settings;
        List<com.dierks.homecraft.games.gen.DailySettings.SlotConfig> slots = new ArrayList<>();
        for (com.dierks.homecraft.games.gen.DailySettings.SlotConfig c : d.slots()) {
            slots.add(new com.dierks.homecraft.games.gen.DailySettings.SlotConfig(c.id(), c.enabled(), c.tierOrMix(),
                    c.origin(), c.id().equals(SLOT) ? 0 : 5));
        }
        host.settings = new com.dierks.homecraft.games.gen.DailySettings(d.enabled(), d.world(), d.rollover(),
                d.startupDelaySeconds(), d.avoidBeforeRestartMinutes(), d.retryMinutes(), d.maxTriesPerDay(),
                d.clearWaitMinutes(), d.keepDays(), d.worldRules(), d.safeSpot(), 0, List.of(3), 0, d.budget(),
                d.stars(), slots);
        boot();
        assertEquals(0, gen.dailyClear(SLOT), "daily_clear: 0 pays nothing on the first finish of the day");
        assertEquals(5, gen.dailyClear("daily_golf"), "and another slot's own amount is its own");
        assertEquals(0, gen.dailyClear("river_run"), "a course that isn't a slot has no daily reward");
        assertEquals(List.of(3), gen.starGoals(), "star_goals as configured");
        assertEquals(0, gen.starGoalReward(), "star_goal_reward as configured");
        assertEquals(0, gen.starGoalCap(), "daily_cap as configured");
    }

    private static com.dierks.homecraft.games.gen.DailySettings withBudget(
            com.dierks.homecraft.games.gen.DailySettings d, int blocks) {
        return new com.dierks.homecraft.games.gen.DailySettings(d.enabled(), d.world(), d.rollover(),
                d.startupDelaySeconds(), d.avoidBeforeRestartMinutes(), d.retryMinutes(), d.maxTriesPerDay(),
                d.clearWaitMinutes(), d.keepDays(), d.worldRules(), d.safeSpot(), d.dailyCap(), d.starGoals(),
                d.starGoalReward(), new com.dierks.homecraft.games.gen.DailySettings.Budget(blocks, blocks, 4, 1, 1, 40),
                d.stars(), d.slots());
    }
}
