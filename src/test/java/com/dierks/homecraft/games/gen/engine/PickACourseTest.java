package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.storage.GenArchiveDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picking a good course (WP-ADM), in the owner's words: "Like I could do the course preview a week
 * early or whatever and find a good one before posting it for the following week." With the engine,
 * a fake clock and world and the real database, at the shipped weekly cadence.
 *
 * <p>Pinned here: {@code preview <course> next} builds a candidate with the next set's first day,
 * length, tier and a seed (random unless typed, and said in the reply), and previewing again
 * replaces it; the course a test run plays is the preview's own plan, placed in the idle half
 * exactly as its flip would place it, and never live; the test is refused with no preview, on
 * golf (walked instead) and while the course is off; {@code choose} makes exactly the next set use
 * the preview's seed, stored like a pin so it holds across a restart (which puts the preview back
 * in the spare half with nothing to write, instead of emptying that half), the change's build
 * finds its blocks already right, the set goes up on fresh boards with its own course code and
 * real seed, and the set after goes back to its own seed; {@code unchoose} cancels; and choose is
 * refused without a preview, for a Classic, for a course that is off, and for a preview whose tier
 * isn't the next set's.
 */
class PickACourseTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    /** Monday 28 September 2026: the week the tests start in. */
    private static final long MON_28_SEP = 20724;
    private static final long MON_5_OCT = MON_28_SEP + 7;
    private static final long MON_12_OCT = MON_28_SEP + 14;
    private static final long PICK = 0x3f2aL;
    private static final String PICK_HEX = "0000000000003f2a";
    private static final Box A = DEF.half('A');
    private static final Box B = DEF.half('B');

    private Host host;
    private FakePlanner parkour;
    private final List<PlanInput> inputs = new ArrayList<>();
    private Map<String, Planner> planners;
    private GenService gen;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        host.settings = GenKit.weekly(SLOT);
        parkour = new FakePlanner(Slots.PARKOUR);
        planners = new LinkedHashMap<>();
        planners.put(Slots.PARKOUR, new Planner() { // the fake parkour planner, noting what each plan was asked
            @Override
            public String id() {
                return parkour.id();
            }

            @Override
            public int algo() {
                return parkour.algo();
            }

            @Override
            public Plan plan(PlanInput in) throws GenFailed {
                inputs.add(in);
                return parkour.plan(in);
            }

            @Override
            public Plan rederive(PlanInput in, GenTag tag) throws GenFailed {
                return parkour.rederive(in, tag);
            }
        });
        planners.put(Slots.RINGS, new FakePlanner(Slots.RINGS));
        planners.put(Slots.GOLF, new FakePlanner(Slots.GOLF));
        planners.put(Slots.BOAT, new FakePlanner(Slots.BOAT));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** A fresh engine over the same database and world: an enable, or a boot after a restart. */
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

    private String heard() {
        return String.join("\n", said);
    }

    /** The week's set is up in half A, and nothing else is going on. */
    private GenTag weekUp() {
        boot();
        drive(70);
        GenTag week = tag();
        assertNotNull(week, "the week's set is up");
        assertEquals(MON_28_SEP, week.day(), "this week's");
        assertEquals('A', week.half(), "in half A");
        return week;
    }

    /** {@code preview <SLOT> next <seed>} (a random one for null), built. */
    private SlotState.Preview previewNext(String seed) {
        said.clear();
        gen.previewNext(SLOT, seed, said::add);
        drive(10);
        SlotState.Preview pv = gen.slot(SLOT).preview;
        assertNotNull(pv, "the preview stands: " + heard());
        return pv;
    }

    // ---- preview next -----------------------------------------------------------------------------

    @Test
    void previewNextBuildsACandidateWithTheNextSetsSettingsAndSaysItsSeed() {
        GenTag week = weekUp();
        said.clear();
        gen.tier(SLOT, "hard", said::add);
        assertTrue(heard().contains("from its next build"), "a tier waits for the next set: " + heard());
        inputs.clear();
        SlotState.Preview pv = previewNext(null);

        assertEquals(MON_5_OCT, pv.day(), "made for next week's set");
        assertEquals(7, pv.cadence(), "a weekly one");
        assertEquals("hard", pv.mix(), "with the tier the next set will have");
        assertEquals(0, pv.reroll(), "as the next set's own build makes it (never a reroll)");
        assertEquals('B', pv.half(), "in the spare half, the one the next set is built in");
        assertEquals(1, inputs.size(), "one plan");
        PlanInput in = inputs.get(0);
        assertEquals("hard", in.tierOrMix(), "the planner was given the next set's tier");
        assertEquals(MON_5_OCT, in.day(), "and its first day");
        assertEquals(pv.seed(), in.seed(), "and the seed");
        assertEquals(host.fallDepth, in.fallDepth(), "and the live fall depth");
        assertTrue(heard().contains("seed " + GenSeed.hex(pv.seed())), "the reply says the random seed: " + heard());
        assertTrue(heard().contains("for Mon 5 Oct-Sun 11 Oct"), "and which set it is for: " + heard());
        assertTrue(heard().contains("ready in half B"), "and that it is ready: " + heard());
        assertTrue(heard().contains("/hcm games gen test " + SLOT), "how to try it: " + heard());
        assertTrue(heard().contains("/hcm games gen choose " + SLOT), "and how to use it then: " + heard());
        assertEquals(week, tag(), "the live course is untouched");
        assertTrue(gen.live(SLOT, week), "and open");
        assertTrue(gen.status(SLOT).stream().anyMatch(l -> l.contains("preview in half B, seed " + GenSeed.hex(pv.seed())
                + " (for Mon 5 Oct-Sun 11 Oct)")), "status shows it: " + gen.status(SLOT));

        SlotState.Preview again = previewNext(PICK_HEX);
        assertEquals(PICK, again.seed(), "previewing again replaces it, here with a typed seed");
        assertEquals('B', again.half(), "in the same half");
        assertEquals(GenKit.plan(DEF, B, PICK, 1).ops().size() + 1, host.world().count(B),
                "which now holds that seed's course only");

        said.clear();
        gen.promote(SLOT, true, said::add);
        assertTrue(heard().contains("That preview is for Mon 5 Oct-Sun 11 Oct") && heard().contains("choose"),
                "next week's preview isn't promoted now; choose uses it then: " + heard());
    }

    @Test
    void twoRandomPreviewsAreTwoCandidates() {
        weekUp();
        long first = previewNext(null).seed();
        long second = previewNext(null).seed();
        assertNotEquals(first, second, "a random seed each time (a 1 in 2^64 clash aside)");
    }

    // ---- test-playing a preview ---------------------------------------------------------------------

    @Test
    void theTestRunsCourseIsThePreviewsPlanPlacedAsItsFlipWouldPlaceItAndNeverLive() throws Exception {
        weekUp();
        SlotState.Preview pv = previewNext(PICK_HEX);
        GenOps.PreviewRun run = gen.previewRun(SLOT);
        assertNull(run.refusal(), "there is a preview to try");
        Course c = run.course();
        Course planned = ((PlannedTrial) GenKit.plan(DEF, B, PICK, 1).course()).course();
        assertEquals(SLOT, c.id(), "the slot's own id (the run is the slot's)");
        assertEquals(GenKit.WORLD, c.world(), "in the Fresh Courses world");
        assertEquals(planned.start(), c.start(), "its real start");
        assertEquals(planned.checkpoints(), c.checkpoints(), "its real checkpoints");
        assertEquals(planned.finish(), c.finish(), "its real finish");
        assertEquals(planned.fallY(), c.fallY(), "and its fall height");
        assertEquals(planned.kind(), c.kind(), "the same kind (so the same kit)");
        assertTrue(B.contains((int) Math.floor(c.start().x()), (int) Math.floor(c.start().y()),
                (int) Math.floor(c.start().z())), "in the spare half, where the preview stands");
        GenTag t = c.gen();
        assertNotNull(t, "it carries the tag its flip would give it (its star times)");
        assertEquals('B', t.half(), "its half");
        assertEquals(PICK, t.seed(), "its seed");
        assertEquals(MON_5_OCT, t.day(), "its set");
        assertEquals(pv.plan().hash(), t.planHash(), "its plan");
        assertTrue(t.goldMs() > 0 && t.silverMs() > t.goldMs(), "the star times come from its expert time");
        assertTrue(c.problems(List.of(GenKit.WORLD)).isEmpty(), "a complete course: " + c.problems(List.of(GenKit.WORLD)));
        assertFalse(gen.live(SLOT, t), "never live: no board, no reward, no stars");
        assertFalse(gen.standing(t), "and never a standing layout a run could count on");
        assertNotEquals(GenBoards.day(tag()), GenBoards.day(t), "not the live course's board either");

        // A preview of this set, promoted: the course the flip makes is exactly the one the test ran.
        SlotState.Preview now = null;
        said.clear();
        gen.preview(SLOT, "5eed", said::add);
        drive(10);
        now = gen.slot(SLOT).preview;
        assertEquals(MON_28_SEP, now.day(), "a preview of this set");
        Course tried = gen.previewRun(SLOT).course();
        gen.promote(SLOT, true, said::add);
        drive(10);
        assertEquals(0x5eedL, tag().seed(), "promoted: " + heard());
        Course live = CourseCodec.decode(SLOT, host.dao.course(SLOT).data()).course();
        assertEquals(live.start(), tried.start(), "the start the test ran is the live one's");
        assertEquals(live.targets(), tried.targets(), "and so are every checkpoint and the finish");
        assertEquals(live.fallY(), tried.fallY(), "and the fall height");
        assertEquals(live.name(), tried.name(), "under the same name");
        assertTrue(live.gen().sameLayout(tried.gen()), "the same layout, as the tag says");
        assertEquals(live.gen().goldMs(), tried.gen().goldMs(), "with the same star times");
    }

    @Test
    void aTestIsRefusedWithNoPreviewWhileItIsBuiltOnGolfAndWhileTheCourseIsOff() {
        host.settings = GenKit.weekly(SLOT, "fresh_tiny_golf");
        weekUp();
        assertEquals("&cNo preview yet - /hcm games gen preview " + SLOT + " first", gen.previewRun(SLOT).refusal(),
                "no preview: the owner's words");
        gen.previewNext(SLOT, PICK_HEX, said::add);
        assertEquals("&c" + DEF.name() + " is being built right now; try when it's done.", gen.previewRun(SLOT).refusal(),
                "not while the preview is still being built");
        drive(10);
        assertNotNull(gen.previewRun(SLOT).course(), "then it can be tried");

        // A golf preview (the fake planners make no golf, so it is put in place as a finished preview is).
        Slots.Def tiny = Slots.of("fresh_tiny_golf");
        gen.slot(tiny.id()).preview = new SlotState.Preview('B', GenKit.plan(tiny, tiny.half('B'), 7, 1), MON_5_OCT, 7,
                "EEE", 7, 0);
        assertEquals("&cWalk it with /hcm games gen tp fresh_tiny_golf idle - golf previews can't be test-played yet.",
                gen.previewRun("fresh_tiny_golf").refusal(), "golf has no test round: it is walked");

        gen.enable(SLOT, false, said::add);
        assertTrue(gen.previewRun(SLOT).refusal().contains(DEF.name() + " is off"), "a course that is off: "
                + gen.previewRun(SLOT).refusal());
        assertTrue(gen.previewRun("fresh_classic_parkour").refusal().contains("Only a Fresh Course"),
                "a Classics slot has no previews");
    }

    // ---- choose -------------------------------------------------------------------------------------

    @Test
    void chooseMakesExactlyTheNextSetUseTheSeedAcrossARestartThenTheSetAfterGoesBackToNormal() throws Exception {
        GenTag week = weekUp();
        previewNext(PICK_HEX);
        said.clear();
        gen.choose(SLOT, false, said::add);
        assertTrue(heard().contains("course for Mon 5 Oct-Sun 11 Oct is this preview (seed " + PICK_HEX + ")"),
                "chosen for next week: " + heard());
        assertTrue(heard().contains("the set after goes back to normal"), heard());
        assertEquals(PICK_HEX + ":1:" + MON_5_OCT + ":" + MON_5_OCT + ":7:easy:6",
                host.store.meta(GenAdminKeys.choose(SLOT)),
                "stored like a pin, holding for the one set that starts on Mon 5 Oct, with the set's length and the"
                        + " tier and fall depth it was tried at (fix2-D)");
        assertTrue(gen.status(null).stream().anyMatch(l -> l.contains("next set: chosen seed " + PICK_HEX
                + " (Mon 5 Oct-Sun 11 Oct)")), "status says so: " + gen.status(null));
        drive(60);
        assertEquals(week, tag(), "this week's course stays");
        assertEquals(GenKit.plan(DEF, B, PICK, 1).ops().size() + 1, host.world().count(B),
                "and the chosen preview stands in the spare half (it isn't emptied as an old half)");

        // A restart mid-week: the choice is read back, and the preview is put back with nothing to write.
        host.now = GenKit.at(2026, 10, 1, 4, 1);
        long writes = host.world().writes;
        boot();
        drive(90);
        assertEquals(week, tag(), "after the restart this week's course is still up");
        assertNotNull(gen.slot(SLOT).chosen, "the choice was read back");
        SlotState.Preview back = gen.slot(SLOT).preview;
        assertNotNull(back, "the chosen preview is a preview again (to try again)");
        assertEquals(PICK, back.seed(), "the chosen one");
        assertEquals(writes, host.world().writes, "found standing: not one block written, and none cleared");
        assertEquals(1, host.logged(Level.INFO, "is checked in its spare half again"), "one line says so");
        assertNotNull(gen.previewRun(SLOT).course(), "so it can be test-run again");

        // The change: the chosen seed goes up, found already built.
        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        writes = host.world().writes;
        stepUntil(() -> tag() != null && tag().day() == MON_5_OCT, 20 * 60);
        GenTag chosen = tag();
        assertEquals(MON_5_OCT, chosen.day(), "next week's set is up");
        assertEquals(PICK, chosen.seed(), "on the chosen seed");
        assertEquals('B', chosen.half(), "in the half the preview stood in");
        assertEquals(0, chosen.reroll(), "as the set's own layout, not a reroll");
        assertEquals(writes, host.world().writes, "the build found the preview's blocks right and wrote nothing");
        assertTrue(gen.slot(SLOT).lastLine.contains(" 0 ops in"), "status's last build says so: "
                + gen.slot(SLOT).lastLine);
        assertTrue(gen.live(SLOT, chosen), "and it is open");
        assertNotEquals(GenBoards.day(week), GenBoards.day(chosen), "on fresh boards");
        GenArchiveDao.Row archived = host.store.edition(SLOT, chosen.editionKey());
        assertNotNull(archived, "archived with its course code");
        assertEquals(PICK, archived.seed(), "and its real seed");
        assertNotNull(gen.fresh(SLOT), "published on the website");
        assertEquals(FreshFeed.shortSeed(PICK), gen.fresh(SLOT).seed(), "with that seed");
        assertEquals(archived.code(), gen.fresh(SLOT).code(), "and code");
        assertTrue(gen.status(null).stream().anyMatch(l -> l.contains("this set: chosen seed " + PICK_HEX)),
                "status: this set is the chosen one: " + gen.status(null));
        said.clear();
        gen.reroll(SLOT, said::add);
        assertTrue(heard().contains("is on the seed you chose for this set") && heard().contains("unchoose " + SLOT),
                "a reroll of the chosen set says how to let it go: " + heard());

        // The set after: its own seed again, and the choice is forgotten with one line.
        host.now = GenKit.at(2026, 10, 12, 4, 0) + 40_000;
        drive(30);
        GenTag after = tag();
        assertEquals(MON_12_OCT, after.day(), "the week after is up");
        assertEquals(GenSeed.seed(host.store.secret(), 7, MON_12_OCT, SLOT, 0), after.seed(),
                "on its own seed: back to normal");
        assertNull(host.store.meta(GenAdminKeys.choose(SLOT)), "the choice is gone");
        assertEquals(1, host.logged(Level.INFO, "chosen seed " + PICK_HEX + " was for"), "with one line");
        assertTrue(gen.status(null).stream().noneMatch(l -> l.contains("chosen seed")), "status: " + gen.status(null));
    }

    @Test
    void unchooseCancelsIt() throws Exception {
        weekUp();
        previewNext(PICK_HEX);
        gen.choose(SLOT, false, said::add);
        said.clear();
        gen.unchoose(SLOT, said::add);
        assertTrue(heard().contains("pick is cancelled") && heard().contains("its own new course"), heard());
        assertNull(host.store.meta(GenAdminKeys.choose(SLOT)), "forgotten in the database");
        assertTrue(gen.status(null).stream().noneMatch(l -> l.contains("chosen seed")), "status: " + gen.status(null));
        said.clear();
        gen.unchoose(SLOT, said::add);
        assertTrue(heard().contains("has no chosen course"), "twice: nothing to cancel: " + heard());
        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        drive(30);
        assertEquals(MON_5_OCT, tag().day(), "next week's set is up");
        assertEquals(GenSeed.seed(host.store.secret(), 7, MON_5_OCT, SLOT, 0), tag().seed(), "on its own seed");
    }

    @Test
    void chooseIsRefusedWithoutAPreviewForAClassicWhileOffAndForAnotherTierAndAsksBeforeReplacingOne()
            throws Exception {
        weekUp();
        said.clear();
        gen.choose(SLOT, false, said::add);
        assertTrue(heard().contains("No preview yet - /hcm games gen preview " + SLOT + " next first"), heard());
        said.clear();
        gen.choose("fresh_classic_parkour", true, said::add);
        assertTrue(heard().contains("Classics slot"), "a Classic: " + heard());

        previewNext(PICK_HEX);
        said.clear();
        gen.tier(SLOT, "hard", said::add);
        gen.choose(SLOT, true, said::add);
        assertTrue(heard().contains("would come out different"), "the next set's tier changed since: " + heard());
        assertNull(host.store.meta(GenAdminKeys.choose(SLOT)), "nothing chosen");
        gen.tier(SLOT, "easy", said::add);

        said.clear();
        gen.choose(SLOT, false, said::add);
        assertNotNull(host.store.meta(GenAdminKeys.choose(SLOT)), "chosen: " + heard());
        previewNext("5eed");
        said.clear();
        gen.choose(SLOT, false, said::add);
        assertTrue(heard().contains("already has seed " + PICK_HEX + " chosen") && heard().contains("choose " + SLOT
                + " confirm"), "replacing a choice asks first: " + heard());
        assertEquals(PICK, gen.slot(SLOT).chosen.seed(), "and keeps the first until then");
        gen.choose(SLOT, true, said::add);
        assertEquals(0x5eedL, gen.slot(SLOT).chosen.seed(), "confirmed: the new one");

        gen.enable(SLOT, false, said::add);
        said.clear();
        gen.choose(SLOT, true, said::add);
        assertTrue(heard().contains(DEF.name() + " is off"), "a course that is off: " + heard());
    }

    @Test
    void aOneSetPinIsReadBackAndHoldsForItsSetOnly() {
        GenScheduler.Pin pick = GenScheduler.Pin.oneSet(PICK, 3, MON_5_OCT);
        assertEquals(pick, GenScheduler.Pin.parse(pick.text()), "stored and read back");
        assertFalse(pick.activeOn(MON_28_SEP), "not this week");
        assertTrue(pick.activeOn(MON_5_OCT), "next week");
        assertFalse(pick.activeOn(MON_12_OCT), "not the week after");
        assertTrue(pick.endedBy(MON_12_OCT), "which ends it");
        GenScheduler.Pin plain = new GenScheduler.Pin(PICK, 3, 0);
        assertEquals(PICK_HEX + ":3:0", plain.text(), "a plain pin is stored as it always was");
        assertEquals(plain, GenScheduler.Pin.parse(PICK_HEX + ":3:0"), "and read back");
        assertTrue(plain.activeOn(MON_28_SEP) && plain.activeOn(MON_12_OCT), "for every set");
        assertNull(GenScheduler.Pin.parse(PICK_HEX + ":3"), "junk isn't a pin");
    }
}
