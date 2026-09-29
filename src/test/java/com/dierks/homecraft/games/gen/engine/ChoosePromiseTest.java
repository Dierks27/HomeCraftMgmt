package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
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
 * The admin tools review, group D (fix2-D): {@code choose} keeps its promise, "the course you
 * tested is the course that goes live", whatever changes after the pick, and {@code gen test} never
 * plays a preview whose half has since been written over. With the engine, a fake clock and world
 * and the real database, as {@link PickACourseTest}.
 *
 * <p>Pinned here: a tier (or mix) or {@code trials.fall_depth} change after a pick drops the pick,
 * with a line to the admin who typed it, one console line and a status line, so the set goes up on
 * its own seed at the new settings (D0); a pick made while a cadence change is still settling in is
 * the set that goes up at the change, never forgotten at once and never "this set's" early (D1); a
 * cadence or {@code rebuild_day} change that moves the next set off the pick's drops it the same way,
 * instead of status promising it for a set that never comes (D2); a preview whose half a failed or
 * stopped job has started writing over is gone, so a test run can't start on blocks that aren't its
 * (D3); and the tools know when the chosen course is the one up now (D5).
 */
class ChoosePromiseTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    /** Monday 28 September 2026: the week the tests start in. */
    private static final long MON_28_SEP = 20724;
    private static final long TUE_29_SEP = MON_28_SEP + 1;
    private static final long MON_5_OCT = MON_28_SEP + 7;
    private static final long PICK = 0x3f2aL;
    private static final String PICK_HEX = "0000000000003f2a";

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

    private String status() {
        return String.join("\n", gen.status(SLOT));
    }

    private String chosenMeta() throws Exception {
        return host.store.meta(GenAdminKeys.choose(SLOT));
    }

    private long ownSeed(int cadence, long day) throws Exception {
        return GenSeed.seed(host.store.secret(), cadence, day, SLOT, 0);
    }

    /** The week's set is up in half A, and nothing else is going on. */
    private GenTag weekUp() {
        boot();
        drive(70);
        GenTag week = tag();
        assertNotNull(week, "the week's set is up");
        assertEquals(MON_28_SEP, week.day(), "this week's");
        return week;
    }

    /** {@code preview <SLOT> next <seed>}, built. */
    private SlotState.Preview previewNext(String seed) {
        said.clear();
        gen.previewNext(SLOT, seed, said::add);
        drive(10);
        SlotState.Preview pv = gen.slot(SLOT).preview;
        assertNotNull(pv, "the preview stands: " + heard());
        return pv;
    }

    /** {@code choose <SLOT>}, which must take. */
    private void choose() throws Exception {
        said.clear();
        gen.choose(SLOT, false, said::add);
        assertNotNull(chosenMeta(), "chosen: " + heard());
    }

    private DailySettings withTier(DailySettings d, String tier) {
        List<DailySettings.SlotConfig> slots = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            slots.add(c.id().equals(SLOT) ? c.withTierOrMix(tier) : c);
        }
        return d.withSlots(slots);
    }

    // ---- D0: a tier, mix or fall depth change after choose ------------------------------------------

    @Test
    void aTierChangeAfterChooseDropsThePickSaysSoAndTheSetGoesUpOnItsOwnSeedAtTheNewTier() throws Exception {
        weekUp();
        previewNext(PICK_HEX);
        choose();
        said.clear();
        gen.tier(SLOT, "easy", said::add);
        assertFalse(heard().contains("dropped"), "the tier it was tried at keeps the pick: " + heard());
        assertNotNull(chosenMeta(), "still chosen");

        said.clear();
        gen.tier(SLOT, "hard", said::add);
        assertTrue(heard().contains("Your pick for Mon 5 Oct-Sun 11 Oct (seed " + PICK_HEX + ") is dropped"),
                "the admin who changed the tier reads that the pick is gone: " + heard());
        assertTrue(heard().contains("tried as easy") && heard().contains("will be hard"),
                "and why, in the owner's terms: " + heard());
        assertTrue(heard().contains("/hcm games gen preview " + SLOT + " next"), "and how to pick again: " + heard());
        assertNull(chosenMeta(), "the pick is forgotten in the database");
        assertEquals(1, host.logged(Level.WARNING, SLOT + "'s pick for Mon 5 Oct-Sun 11 Oct (seed " + PICK_HEX
                + ") is dropped"), "the console says so once");
        assertFalse(status().contains("chosen seed"), "status no longer promises it: " + status());
        assertTrue(status().contains("pick for Mon 5 Oct-Sun 11 Oct dropped"), "it says it was dropped: " + status());
        drive(5);
        assertEquals(1, host.logged(Level.WARNING, "is dropped"), "said once, not every second");

        // The change: the set goes up on its own seed, at the tier the admin asked for.
        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        inputs.clear();
        stepUntil(() -> tag() != null && tag().day() == MON_5_OCT, 20 * 60);
        GenTag next = tag();
        assertEquals(MON_5_OCT, next.day(), "next week's set is up");
        assertNotEquals(PICK, next.seed(), "not the chosen seed at a tier nobody tried");
        assertEquals(ownSeed(7, MON_5_OCT), next.seed(), "its own seed");
        assertEquals("hard", inputs.get(inputs.size() - 1).tierOrMix(), "at the new tier");
    }

    @Test
    void aFallDepthOrAConfigTierChangeAfterChooseDropsThePickWithAConsoleLine() throws Exception {
        weekUp();
        previewNext(PICK_HEX);
        choose();

        host.fallDepth = 9; // trials.fall_depth, which shapes a parkour course
        drive(2);
        assertNull(chosenMeta(), "a new fall depth makes another course: the pick goes");
        assertEquals(1, host.logged(Level.WARNING, "fall_depth 6"), "the console says why: " + host.logs.stream()
                .map(r -> r.getMessage()).toList());
        said.clear();
        gen.choose(SLOT, true, said::add);
        assertTrue(heard().contains("would come out different"), "the preview made at the old depth can't be chosen"
                + " again: " + heard());
        assertNull(chosenMeta(), "nothing chosen");

        host.fallDepth = 6;
        choose(); // the preview fits again
        host.settings = withTier(host.settings, "hard"); // a config edit, seen at the next boot
        host.now += 60_000;
        boot();
        drive(10);
        assertNull(chosenMeta(), "a config tier change drops it too");
        assertEquals(1, host.logged(Level.WARNING, "tried as easy"), "with a console line");
        assertEquals(0, host.logged(Level.INFO, "is checked in its spare half again"),
                "and the dropped pick isn't put back after the restart");
    }

    // ---- D1: choose while a longer cadence is settling in ---------------------------------------------

    @Test
    void aPickMadeWhileDailyTurnsWeeklyIsTheWeeklySetThatGoesUpAtTheChange() throws Exception {
        host.settings = GenKit.settings(SLOT); // daily
        boot();
        drive(70);
        assertEquals(TUE_29_SEP, tag().day(), "Tuesday's daily course is up");
        host.now = GenKit.at(2026, 9, 29, 10, 0);
        host.settings = GenKit.weekly(SLOT);
        drive(2);

        SlotState.Preview pv = previewNext(PICK_HEX);
        assertEquals(MON_28_SEP, pv.day(), "the next set is the week the switch lands in");
        assertEquals(Edition.WEEKLY, pv.cadence(), "a weekly one");
        choose();
        assertTrue(heard().contains("course for Mon 28 Sep-Sun 4 Oct is this preview"), heard());
        drive(5);
        assertNotNull(chosenMeta(), "the pick is kept, not forgotten at the next check");
        assertTrue(status().contains("next set: chosen seed " + PICK_HEX + " (Mon 28 Sep-Sun 4 Oct)"),
                "status says it waits: " + status());
        assertEquals(PICK, gen.tools(SLOT).chosenSeed(), "and the tools show it");

        host.now = GenKit.at(2026, 9, 30, 4, 0) + 40_000;
        stepUntil(() -> tag() != null && tag().cadence() == Edition.WEEKLY, 20 * 60);
        GenTag week = tag();
        assertEquals(MON_28_SEP, week.day(), "Wednesday 4:00: the week's set goes up");
        assertEquals(PICK, week.seed(), "on the chosen seed");
        assertTrue(status().contains("this set: chosen seed " + PICK_HEX), "status: " + status());
    }

    @Test
    void aPickMadeWhileWeeklyTurnsFortnightlyWaitsForTheChangeAndTheLiveWeekStaysRerollable() throws Exception {
        weekUp();
        host.now = GenKit.at(2026, 9, 30, 10, 0);
        host.settings = host.settings.withCadence(14);
        drive(2);

        SlotState.Preview pv = previewNext(PICK_HEX);
        assertEquals(14, pv.cadence(), "the next set is a 14-day one");
        choose();
        String set = GenService.editionName(14, pv.day());
        assertTrue(status().contains("next set: chosen seed " + PICK_HEX + " (" + set + ")"),
                "the pick waits for the change; the week kept up now isn't it: " + status());
        assertFalse(status().contains("this set: chosen"), status());
        GenOps.Tools t = gen.tools(SLOT);
        assertFalse(t.chosenUpNow(), "the tools know it isn't up yet");
        said.clear();
        gen.reroll(SLOT, said::add);
        assertFalse(heard().contains("is on the seed you chose for this set"), "the live week isn't the chosen one: "
                + heard());

        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        stepUntil(() -> tag() != null && tag().cadence() == 14, 20 * 120);
        assertEquals(pv.day(), tag().day(), "the 14-day set goes up at the change");
        assertEquals(PICK, tag().seed(), "on the chosen seed");
        assertTrue(status().contains("this set: chosen seed " + PICK_HEX), "status: " + status());
    }

    // ---- D2: a cadence or rebuild_day change after a pick ------------------------------------------

    @Test
    void aRebuildDayMovedAfterAPickDropsItInsteadOfPromisingASetThatNeverComes() throws Exception {
        weekUp();
        previewNext(PICK_HEX);
        choose();
        host.now = GenKit.at(2026, 9, 30, 10, 0);
        host.settings = host.settings.withRebuild(LocalTime.of(4, 0), DayOfWeek.FRIDAY);
        drive(2);
        assertNull(chosenMeta(), "Mon 5 Oct is no longer a set's first day: the pick goes");
        assertEquals(1, host.logged(Level.WARNING, SLOT + "'s pick for Mon 5 Oct-Sun 11 Oct (seed " + PICK_HEX
                + ") is dropped"), "with a console line");
        assertEquals(1, host.logged(Level.WARNING, "the next set is Fri 9 Oct-Thu 15 Oct now"), "saying why: "
                + host.logs.stream().map(r -> r.getMessage()).toList());
        assertFalse(status().contains("chosen seed"), "status doesn't promise it: " + status());
        assertTrue(status().contains("pick for Mon 5 Oct-Sun 11 Oct dropped"), "it says so: " + status());
        assertNull(gen.tools(SLOT).chosenSeed(), "nor do the tools");
    }

    @Test
    void aWeeklyPickIsDroppedWhenTheCourseGoesDailyAndNeverGoesUpAsAOneDaySet() throws Exception {
        weekUp();
        previewNext(PICK_HEX);
        choose();
        host.now = GenKit.at(2026, 9, 30, 10, 0);
        host.settings = host.settings.withCadence(Edition.DAILY);
        drive(2);
        assertNull(chosenMeta(), "the week it was picked for won't come: the pick goes");
        assertEquals(1, host.logged(Level.WARNING, "is dropped"), "with a console line");

        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        stepUntil(() -> tag() != null && tag().day() == MON_5_OCT, 20 * 120);
        assertEquals(Edition.DAILY, tag().cadence(), "Monday's daily course is up");
        assertEquals(ownSeed(1, MON_5_OCT), tag().seed(), "on its own seed, not the weekly pick for one day");
    }

    // ---- D3: a preview whose half has been written over -------------------------------------------

    @Test
    void aPreviewWhoseHalfAFailedOrStoppedJobStartedWritingOverIsGone() {
        weekUp();
        previewNext(PICK_HEX);
        assertNull(gen.previewRun(SLOT).refusal(), "the preview can be tried");

        host.world().killAfter = 20; // the next preview dies after 20 writes into the same half
        said.clear();
        gen.previewNext(SLOT, "5eed", said::add);
        drive(10);
        assertTrue(heard().contains("died mid-write"), "that build failed: " + heard());
        assertEquals("&cNo preview yet - /hcm games gen preview " + SLOT + " first", gen.previewRun(SLOT).refusal(),
                "the old preview's half isn't its any more, so there is nothing to try");
        assertFalse(status().contains("preview in half"), "and status doesn't show it: " + status());

        previewNext(PICK_HEX);
        long before = host.world().writes;
        said.clear();
        gen.previewNext(SLOT, "5eed", said::add);
        stepUntil(() -> host.world().writes > before + 5, 20 * 30);
        assertTrue(host.world().writes > before, "the new preview has started writing");
        gen.enable(SLOT, false, said::add);
        gen.enable(SLOT, true, said::add);
        assertEquals("&cNo preview yet - /hcm games gen preview " + SLOT + " first", gen.previewRun(SLOT).refusal(),
                "stopped halfway: the same");
    }

    @Test
    void aPreviewBuildThatFailsBeforeWritingAnythingKeepsTheOldPreview() {
        weekUp();
        previewNext(PICK_HEX);
        parkour.fail = new GenFailed("no room for it"); // the next plan fails: not one block is written
        long before = host.world().writes;
        said.clear();
        gen.previewNext(SLOT, "5eed", said::add);
        drive(10);
        assertTrue(heard().contains("no room for it"), "that build failed: " + heard());
        assertEquals(before, host.world().writes, "before writing anything");
        assertNull(gen.previewRun(SLOT).refusal(), "so the preview standing there can still be tried");
        assertEquals(PICK, gen.slot(SLOT).preview.seed(), "the one that stands");
    }

    // ---- the stored pick ---------------------------------------------------------------------------

    @Test
    void aPickIsStoredWithItsSetsLengthAndSettingsAndAnOlderOneIsStillRead() {
        GenScheduler.Choice pick = new GenScheduler.Choice(PICK, 3, MON_5_OCT, 7, "easy", 6);
        assertEquals(PICK_HEX + ":3:" + MON_5_OCT + ":" + MON_5_OCT + ":7:easy:6", pick.text(),
                "a one-set pin's four fields, then the set's length and what it was tried at");
        assertEquals(pick, GenScheduler.Choice.parse(pick.text()), "read back");
        assertEquals(GenScheduler.Pin.oneSet(PICK, 3, MON_5_OCT), pick.pin(), "to the scheduler, a one-set pin");
        assertTrue(pick.isSet(MON_5_OCT, 7), "its set");
        assertFalse(pick.isSet(MON_5_OCT, 14), "not a 14-day set starting that day");
        assertFalse(pick.isSet(MON_5_OCT, 1), "nor that day alone");
        assertTrue(pick.fits("easy", 6) && !pick.fits("hard", 6) && !pick.fits("easy", 9), "tried at easy, depth 6");
        GenScheduler.Choice golf = new GenScheduler.Choice(PICK, 3, MON_5_OCT, 7, "EEEMMMMHH", 0);
        assertEquals(golf, GenScheduler.Choice.parse(golf.text()), "a mix, and no fall depth (golf doesn't use it)");
        assertTrue(golf.fits("EEEMMMMHH", 9), "any fall depth");

        GenScheduler.Choice old = GenScheduler.Choice.parse(PICK_HEX + ":3:" + MON_5_OCT + ":" + MON_5_OCT);
        assertEquals(new GenScheduler.Choice(PICK, 3, MON_5_OCT, 0, null, 0), old, "a pick stored before still reads");
        assertTrue(old.isSet(MON_5_OCT, 14) && old.fits("hard", 9), "matched by its day alone, as it always was");
        assertNull(GenScheduler.Choice.parse(PICK_HEX + ":3:0"), "a plain pin isn't a pick");
        assertNull(GenScheduler.Choice.parse(PICK_HEX + ":3:1:1:x:easy:6"), "junk isn't one");
        assertNull(GenScheduler.Choice.parse(null), "nor is nothing");
    }

    // ---- D5: the tools know the chosen course is up now -------------------------------------------

    @Test
    void duringTheChosenSetTheToolsSayThePickIsUpNow() throws Exception {
        weekUp();
        previewNext(PICK_HEX);
        choose();
        GenOps.Tools waiting = gen.tools(SLOT);
        assertEquals(PICK, waiting.chosenSeed(), "picked");
        assertFalse(waiting.chosenUpNow(), "and still to come");
        previewNext("5eed");
        assertTrue(heard().contains("Your pick for Mon 5 Oct-Sun 11 Oct (seed " + PICK_HEX + ") stays chosen"),
                "another candidate for next week doesn't replace the pick, and says so (D6): " + heard());

        host.now = GenKit.at(2026, 10, 5, 4, 0) + 40_000;
        stepUntil(() -> tag() != null && tag().day() == MON_5_OCT, 20 * 60);
        assertEquals(PICK, tag().seed(), "the chosen week is up");
        GenOps.Tools up = gen.tools(SLOT);
        assertEquals(PICK, up.chosenSeed(), "still the pick");
        assertTrue(up.chosenUpNow(), "and the tools know it is the one up now");
        assertEquals("Mon 5 Oct-Sun 11 Oct", up.chosenFor(), "for this set");
        said.clear();
        gen.unchoose(SLOT, said::add);
        assertTrue(heard().contains("stays until the next set") && heard().contains("regenerate"),
                "letting it go says the course stays, and that regenerate works now: " + heard());
    }
}
