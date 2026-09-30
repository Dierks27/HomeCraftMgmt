package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper's planner (EVENTS-DROPPER-SPEC §B.1.5): 2,000 seeds of each shipped mix all plan and
 * pass the independent validator; three seeds a mix are pinned; the plan stays inside its half and
 * its block budget; the layouts really differ; the SAFE_STRAIGHT fallback passes every tier, so a
 * course is never missing a level; and every host makes the same plan, because its work is counted,
 * never timed.
 */
class DropperPlannerTest {

    private static final DropperPlanner PLANNER = new DropperPlanner();
    private static final int SEEDS = 2_000;

    // ---- 2,000 seeds a mix ------------------------------------------------------------------------

    @Test
    void twoThousandEasyDroppersAllPlanAndPassTheValidator() {
        manySeeds("EEE");
    }

    @Test
    void twoThousandDroppersOfTheShippedMixAllPlanAndPassTheValidator() {
        manySeeds("EEMMH");
    }

    @Test
    void twoThousandAllHardDroppersAllPlanAndPassTheValidator() {
        manySeeds("HHHHH");
    }

    /**
     * Plan {@value #SEEDS} seeds of {@code mix} (half A and B by turns, in parallel: plans are pure and
     * independent) and check every one: it plans (so its own validator passed), it places at most
     * 20,000 blocks, all inside its half, within its work budget; and the layouts differ.
     */
    private static void manySeeds(String mix) {
        Map<Integer, String> failed = new ConcurrentHashMap<>();
        Set<String> hashes = ConcurrentHashMap.newKeySet();
        Map<LayerKit.Template, AtomicInteger> templates = new ConcurrentHashMap<>();
        AtomicInteger maxOps = new AtomicInteger();
        AtomicInteger outside = new AtomicInteger();
        AtomicLong maxWork = new AtomicLong();
        AtomicLong totalWork = new AtomicLong();
        AtomicInteger twins = new AtomicInteger();
        AtomicInteger decoys = new AtomicInteger();
        AtomicInteger straight = new AtomicInteger();
        IntStream.range(0, SEEDS).parallel().forEach(n -> {
            char h = n % 2 == 0 ? 'A' : 'B';
            PlanInput in = DropperFixtures.input(mix, n, h);
            DropperPlanner.Build b;
            try {
                b = PLANNER.build(in, false);
            } catch (GenFailed e) {
                failed.put(n, e.getMessage());
                return;
            }
            Plan p = b.plan();
            hashes.add(p.hash());
            maxOps.accumulateAndGet(p.ops().size(), Math::max);
            for (BlockOp op : p.ops()) {
                if (!in.half().contains(op.x(), op.y(), op.z())) {
                    outside.incrementAndGet();
                }
            }
            maxWork.accumulateAndGet(p.work(), Math::max);
            totalWork.addAndGet(p.work());
            straight.addAndGet(b.safeLevels());
            for (DropperPlanner.LevelPlan lp : b.levels()) {
                twins.addAndGet(lp.twin() != null ? 1 : 0);
                for (int j = 0; j < lp.shapes().length; j++) {
                    templates.computeIfAbsent(lp.shapes()[j].template(), t -> new AtomicInteger()).incrementAndGet();
                    decoys.addAndGet(lp.decoys()[j] != null ? 1 : 0);
                }
            }
        });
        assertTrue(failed.isEmpty(), mix + ": every seed plans and passes the validator; these didn't: "
                + new TreeMap<>(failed).entrySet().stream().limit(5).toList());
        assertTrue(hashes.size() >= SEEDS * 95 / 100, mix + ": at least 95% of the layouts are different: "
                + hashes.size() + " of " + SEEDS);
        assertTrue(maxOps.get() <= DropperValidator.MAX_OPS, mix + ": at most 20,000 blocks: " + maxOps.get());
        assertEquals(0, outside.get(), mix + ": every block inside its half");
        assertTrue(maxWork.get() <= DropperPlanner.WORK_BUDGET, mix + ": within the work budget: " + maxWork.get());
        assertTrue(totalWork.get() / SEEDS < 50_000, mix + ": a typical plan flies under 50k ticks: "
                + totalWork.get() / SEEDS);
        assertEquals(0, straight.get(), mix + ": a real seed never needs the straight fallback");
        for (LayerKit.Template t : DropRules.levels(mix).get(DropRules.levels(mix).size() - 1).templates()) {
            assertTrue(templates.containsKey(t), mix + ": the " + t + " template is drawn: " + templates.keySet());
        }
        if (mix.contains("M") || mix.contains("H")) {
            assertTrue(twins.get() > 0, mix + ": some levels offer a real choice of two openings");
        }
        if (mix.contains("H")) {
            assertTrue(decoys.get() > 0, mix + ": some Hard layers have a decoy");
        }
    }

    // ---- pinned and repeatable ----------------------------------------------------------------------

    @Test
    void threeSeedsOfEachMixMakeExactlyThePinnedLayouts() {
        // Re-pinned in C1 for TrialKind.DROPPER (the kind is part of the plan hash); ALGO stays 1, since
        // no dropper had been built with the stand-in kind (WIRING.md §1).
        Map<String, List<String>> golden = Map.of(
                "EEE", List.of("ae04f1bbb4f0", "001bcd1cdc57", "32bc511dd1f2"),
                "EEMMH", List.of("85276ccc6388", "2bf1c8ef5950", "6587e2722bd9"),
                "HHHHH", List.of("38d96b8dac98", "fd9a863103f6", "b24172a9cb4a"));
        Map<String, List<String>> made = new java.util.TreeMap<>();
        for (String mix : golden.keySet()) {
            made.put(mix, List.of(DropperFixtures.plan(mix, 0).hash(), DropperFixtures.plan(mix, 1).hash(),
                    DropperFixtures.plan(mix, 2).hash()));
        }
        assertEquals(new java.util.TreeMap<>(golden), made,
                "a change to what the planner makes for a seed bumps ALGO and re-pins these");
    }

    @Test
    void theSamePlanComesOutEveryTimeWithTheSameCountedWork() throws GenFailed {
        for (String mix : List.of("EEE", "EEMMH", "HHHHH")) {
            Plan a = new DropperPlanner().plan(DropperFixtures.input(mix, 7, 'B'));
            Plan b = new DropperPlanner().plan(DropperFixtures.input(mix, 7, 'B'));
            assertEquals(a.hash(), b.hash(), mix + ": the same seed makes the same blocks");
            assertEquals(a.work(), b.work(), mix + ": after the same counted work, on any host");
            assertEquals(a.summary(), b.summary(), mix + ": and says the same about it");
            assertTrue(a.work() > 0, mix + ": the work is really counted: " + a.work());
        }
    }

    @Test
    void aLevelIsTheSameWhateverTheOtherLevelsAre() throws GenFailed {
        Plan ee = PLANNER.plan(DropperFixtures.input("EE", 11, 'A'));
        Plan em = PLANNER.plan(DropperFixtures.input("EM", 11, 'A'));
        Plan mm = PLANNER.plan(DropperFixtures.input("MM", 11, 'A'));
        Box half = ee.half();
        assertEquals(inside(ee, DropperGeometry.shaft(half, 0)), inside(em, DropperGeometry.shaft(half, 0)),
                "level 1 is fork(\"level:0\"): making level 2 medium doesn't touch it");
        assertEquals(inside(em, DropperGeometry.shaft(half, 1)), inside(mm, DropperGeometry.shaft(half, 1)),
                "level 2 is fork(\"level:1\"): making level 1 medium doesn't touch it");
        assertFalse(inside(ee, DropperGeometry.shaft(half, 1)).equals(inside(em, DropperGeometry.shaft(half, 1))),
                "while a level of another tier is really another level");
    }

    /** A shaft's inside blocks, as text lines in a stable order. */
    private static List<String> inside(Plan p, DropperGeometry.Shaft s) {
        List<String> out = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            if (s.inside(op.x(), op.z())) {
                out.add(op.x() + "," + op.y() + "," + op.z() + " " + p.blockOf(op));
            }
        }
        Collections.sort(out);
        return out;
    }

    // ---- between the sampled starts ---------------------------------------------------------------

    /**
     * The pilots leave from every 0.3 blocks of the ledge's edge and a few moments of the walking
     * step, and a child leaves from anywhere: a proof flown from one exact moment passed levels that
     * bonked a pilot stepping off two hundredths of a block later. So real plans are flown again from
     * the starts halfway between the sampled ones and from both ends of the step, walking and jumping,
     * at every delay, and sloppy: every one still reaches the water, keeping at least half the pilots'
     * own clearance (r/4) from every block.
     */
    @Test
    void pilotsLeavingBetweenTheSampledStartsStillReachTheWater() {
        double[] timings = {0.001, 0.333, 0.667, 0.999};
        double[] exits = {-1.05, -0.75, -0.45, -0.15, 0.15, 0.45, 0.75, 1.05};
        List<String> missed = new ArrayList<>();
        int flown = 0;
        for (String mix : List.of("EEE", "EEMMH", "HHHHH")) {
            List<DropRules.Level> tiers = DropRules.levels(mix);
            for (int n = 0; n < 6; n++) {
                Plan p = DropperFixtures.plan(mix, n);
                DropWorld w = DropperFixtures.world(p);
                for (int i = 0; i < tiers.size(); i++) {
                    DropRules.Level tier = tiers.get(i);
                    DropCheck.View v = DropperFixtures.view(p, i, tier);
                    DropCheck.Flight f = DropCheck.witness(w, v, DropperFixtures.witness(p, i), tier.tube());
                    List<DropPilot.Target> targets = DropCheck.targets(w, v, f.crossings());
                    List<String> labels = new ArrayList<>();
                    List<DropSim.Body> starts = new ArrayList<>();
                    List<DropPilot> pilots = new ArrayList<>();
                    for (double t : timings) {
                        for (double e : exits) {
                            for (int d : tier.delays()) {
                                for (boolean jump : new boolean[]{false, true}) {
                                    labels.add((jump ? "jump" : "walk") + " off at " + e + ", step " + t + ", " + d
                                            + " ticks late");
                                    starts.add(DropPilot.start(v.edgeX(), v.edgeZ(), v.fx(), v.fz(), -v.fz(), v.fx(),
                                            e, t, v.ledgeTop(), jump));
                                    pilots.add(new DropPilot(v.fx(), v.fz(), targets, d, 0));
                                }
                            }
                            double aim = e < 0 ? tier.aimError() : -tier.aimError();
                            labels.add("sloppy " + aim + " degrees off at " + e + ", step " + t);
                            starts.add(DropPilot.start(v.edgeX(), v.edgeZ(), v.fx(), v.fz(), -v.fz(), v.fx(), e, t,
                                    v.ledgeTop(), false));
                            pilots.add(new DropPilot(v.fx(), v.fz(), targets, tier.delays()[1], aim));
                        }
                    }
                    for (int k = 0; k < starts.size(); k++) {
                        DropRun.Result r = DropRun.fly(w, starts.get(k), pilots.get(k), tier.tube() / 4,
                                DropSim.MAX_TICKS, Double.NaN, false);
                        flown++;
                        if (!r.splashed()) {
                            missed.add(mix + " seed " + n + " level " + (i + 1) + ": " + labels.get(k) + " "
                                    + r.outcome());
                        }
                    }
                }
            }
        }
        assertTrue(flown > 15_000, "a real sample of starts was flown: " + flown);
        assertTrue(missed.isEmpty(), missed.size() + " of " + flown + " pilots leaving between the sampled starts"
                + " didn't reach the water with r/4 to spare: " + missed.stream().limit(5).toList());
    }

    // ---- never missing a level --------------------------------------------------------------------

    @Test
    void theStraightFallbackPassesEveryTiersPilots() throws GenFailed {
        for (String mix : List.of("EEEEE", "MMMMM", "HHHHH")) {
            for (int n = 0; n < 24; n++) {
                char h = n % 2 == 0 ? 'A' : 'B';
                DropperPlanner.Build b = PLANNER.build(DropperFixtures.input(mix, n, h), true);
                assertEquals(5, b.safeLevels(), mix + " seed " + n + ": every level straight, and every one proven");
                for (DropperPlanner.LevelPlan lp : b.levels()) {
                    assertTrue(lp.safe(), mix + ": level " + (lp.index() + 1) + " is SAFE_STRAIGHT");
                    for (int j = 0; j < lp.open().length; j++) {
                        assertEquals(LayerKit.Template.PLATE, lp.shapes()[j].template(), mix + ": plain plates");
                        int k = lp.tier().minOpening() + 2;
                        assertTrue(lp.open()[j].sizeX() >= k && lp.open()[j].sizeZ() >= k, mix + ": level "
                                + (lp.index() + 1) + " layer " + (j + 1) + " opens the tier's minimum + 2 ("
                                + k + "): " + lp.open()[j].size());
                    }
                }
            }
        }
    }

    @Test
    void aStarvedWorkBudgetStillMakesEveryLevelAsAStraightDrop() throws GenFailed {
        PlanInput starved = new PlanInput(DropperSlots.DROPPER_SLOT, LegacyBoxes.half(DropperSlots.DROPPER_SLOT, 'A'),
                'A', DropperFixtures.DAY, 0, 99, "EEMMH", 6, 1, null);
        DropperPlanner.Build b = PLANNER.build(starved, false);
        assertEquals(5, b.levels().size(), "a course is never missing a level");
        assertEquals(5, b.safeLevels(), "with no budget to search, every level is the proven straight drop");
        assertTrue(b.plan().summary().getLast().endsWith("5 straight"), "and the admin line says so: "
                + b.plan().summary().getLast());
    }

    // ---- the Planner contract (C0) -----------------------------------------------------------------

    @Test
    void itIsTheDropperGenerator() {
        assertInstanceOf(Planner.class, PLANNER, "a Fresh Courses generator");
        assertEquals("dropper", PLANNER.id(), "generator id dropper");
        assertEquals(DropperSlots.DROPPER, PLANNER.id(), "the id its slots name");
        assertEquals(1, PLANNER.algo(), "version 1");
        assertEquals(300_000, DropperPlanner.WORK_BUDGET, "the budget GenService.WORK gives it");
        for (Slots.Def d : DropperSlots.ALL) {
            assertEquals(PLANNER.id(), d.generator(), d.id() + " is made by it");
            assertEquals(Slots.GAME_TRIALS, d.game(), d.id() + "'s row is an ordinary trials row");
            assertTrue(DropperGeometry.fits(d.half('A'), DropRules.MAX_LEVELS), d.id() + "'s half fits 5 shafts");
        }
    }

    @Test
    void theSlotsAreTheSpecsTable() {
        assertEquals(List.of("fresh_dropper_easy", "fresh_dropper", "fresh_classic_dropper"),
                DropperSlots.ALL.stream().map(Slots.Def::id).toList(), "the three slots");
        assertEquals("EEE", DropperSlots.EASY.tierOrMix(), "Easy Dropper: 3 easy levels");
        assertEquals("EEMMH", DropperSlots.DROPPER_SLOT.tierOrMix(), "Dropper: 5 levels, easy to hard");
        assertEquals(new Box(5376, 160, 4096, 5439, 223, 4111), DropperSlots.EASY.half('A'), "Easy's half A");
        assertEquals(new Box(5472, 160, 4160, 5535, 223, 4175), DropperSlots.DROPPER_SLOT.half('B'),
                "the Dropper's half B, 32 past A");
        assertEquals(4224, DropperSlots.CLASSIC.originZ(), "the Classic Dropper, 48 further along z");
        assertTrue(DropperSlots.EASY.enabled() && DropperSlots.DROPPER_SLOT.enabled(),
                "both ship on (inside games.fresh, which ships off)");
        assertEquals(List.of(1, 2), List.of(DropperSlots.EASY.dailyClear(), DropperSlots.DROPPER_SLOT.dailyClear()),
                "a first finish pays 1 and 2 a day");
        assertEquals(List.of(2, 3), List.of(DropperSlots.EASY.weeklyClear(), DropperSlots.DROPPER_SLOT.weeklyClear()),
                "and 2 and 3 a week");
    }

    @Test
    void rederivingFromItsTagMakesTheSameLayout() throws GenFailed {
        Plan p = DropperFixtures.plan("EEMMH", 4);
        GenTag tag = tag(p, DropRules.refMs("EEMMH"));
        PlanInput in = DropperFixtures.input("EEMMH", 99, 'A');
        assertEquals(p.hash(), PLANNER.rederive(in, tag).hash(), "the tag's seed, not the input's, makes the layout");
        PlanInput changed = DropperFixtures.input("EEEEE", 99, 'A');
        assertEquals(p.hash(), PLANNER.rederive(changed, tag).hash(),
                "an admin changed the mix since: the mixes with the tag's reference time are tried");
        assertEquals(PLANNER.plan(in).hash(), PLANNER.rederive(in, null).hash(), "no tag: just a plan of the input");
    }

    @Test
    void aTagTheSeedNoLongerMakesOrAnOlderVersionIsRefused() {
        Plan p = DropperFixtures.plan("EEE", 4);
        PlanInput in = DropperFixtures.input("EEE", 4, 'A');
        GenTag wrong = new GenTag("fresh_dropper_easy", "dropper", 1, DropperFixtures.DAY, 0, p.seed(), 'A',
                "000000000000", DropRules.refMs("EEE"), 0, 0, List.of(), List.of(), 0, 7);
        GenFailed e = assertThrows(GenFailed.class, () -> PLANNER.rederive(in, wrong), "a hash it no longer makes");
        assertTrue(e.getMessage().contains("no longer makes"), "said plainly: " + e.getMessage());
        GenTag old = new GenTag("fresh_dropper_easy", "dropper", 2, DropperFixtures.DAY, 0, p.seed(), 'A',
                p.hash(), DropRules.refMs("EEE"), 0, 0, List.of(), List.of(), 0, 7);
        assertThrows(GenFailed.class, () -> PLANNER.rederive(in, old), "another version's layout isn't re-derived");
    }

    private static GenTag tag(Plan p, long refMs) {
        return new GenTag(p.slot(), "dropper", DropperPlanner.ALGO, DropperFixtures.DAY, 0, p.seed(), 'A', p.hash(),
                refMs, 0, 0, List.of(), List.of(), 0, 7);
    }

    @Test
    void everyFailureIsAGenFailed() {
        Slots.Def d = DropperSlots.DROPPER_SLOT;
        assertThrows(GenFailed.class, () -> PLANNER.plan(new PlanInput(d, LegacyBoxes.half(d, 'A'), 'A', 1, 0, 1, "EXE",
                6, 0, null)), "a bad mix");
        assertThrows(GenFailed.class, () -> PLANNER.plan(new PlanInput(d, LegacyBoxes.half(d, 'A'), 'A', 1, 0, 1, "easy",
                6, 0, null)), "a trial tier is not a mix");
        assertThrows(GenFailed.class, () -> PLANNER.plan(new PlanInput(d, Box.sized(0, 0, 0, 40, 64, 16), 'A', 1, 0,
                1, "EEEEE", 6, 0, null)), "a half too narrow for five shafts");
        GenFailed stop = assertThrows(GenFailed.class, () -> PLANNER.plan(new PlanInput(d, LegacyBoxes.half(d, 'A'), 'A',
                1, 0, 1, "EEMMH", 6, 0, () -> true)), "a cancelled job");
        assertEquals("cancelled", stop.getMessage(), "gives up as cancelled");
    }

    // ---- what a plan holds --------------------------------------------------------------------------

    @Test
    void theCourseIsTheLedgesAndPoolsInOrderWithTheTimesOfItsMix() {
        Plan p = DropperFixtures.plan("EEMMH", 5);
        Course c = DropperFixtures.course(p);
        PlannedTrial t = (PlannedTrial) p.course();
        assertEquals("fresh_dropper", c.id(), "the row is the slot");
        assertEquals(DropperPlanner.KIND, c.kind(), "the planner's kind");
        assertEquals(com.dierks.homecraft.games.trial.TrialKind.DROPPER, c.kind(),
                "a dropper row (C1 added TrialKind.DROPPER, so the stand-in is gone)");
        assertEquals(DropRules.tier("EEMMH"), c.tier(), "the mix's rounded mean: medium");
        assertEquals(8, c.checkpoints().size(), "pool, ledge four times: at most 8 for 5 levels");
        assertEquals(5, DropMarks.levels(c), "five levels");
        assertEquals(DropRules.refMs("EEMMH"), t.refMs(), "the reference time, 17.4 s");
        assertEquals(DropRules.minSeconds("EEMMH"), c.minSeconds().intValue(), "the shortest honest time");
        assertEquals(List.of(), DropMarks.problems(c), "a well-formed dropper");
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            lowest = Math.min(lowest, DropMarks.floorRow(DropMarks.poolOf(c, i)));
            assertEquals(p.half().minY() + DropperGeometry.LEDGE_TOP, DropMarks.ledgeOf(c, i).y(), 1e-9,
                    "level " + (i + 1) + "'s ledge top is at 56 in the half");
        }
        assertEquals(lowest - DropperGeometry.FALL_BELOW_FLOOR, c.fallY(), 1e-9,
                "the fall line is 4 under the lowest floor");
        DropCheck.View v = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        assertEquals(DropMarks.yaw(v.fx(), v.fz()), c.start().yaw(), 1e-4, "the start faces into the shaft");
        assertEquals(DropperPlanner.START_PITCH, c.start().pitch(), 0, "looking down into it");
    }

    @Test
    void layersComeFromTheTiersDepthsAndLedgesFromEveryWall() {
        Set<DropProgram.Dir> facings = EnumSet.noneOf(DropProgram.Dir.class);
        for (String mix : List.of("EEE", "EEMMH", "HHHHH")) {
            for (int n = 0; n < 8; n++) {
                Plan p = DropperFixtures.plan(mix, n);
                for (int i = 0; i < mix.length(); i++) {
                    DropRules.Level tier = DropRules.levels(mix).get(i);
                    DropCheck.View v = DropperFixtures.view(p, i, tier);
                    facings.add(v.forward());
                    List<Integer> rows = v.layerRows();
                    assertEquals(tier.layers(), rows.size(), mix + " level " + (i + 1) + ": the tier's layers");
                    int first = v.ledgeTop() - rows.get(0) - 1;
                    assertTrue(first >= tier.firstMin() && first <= tier.firstMax(), mix + " level " + (i + 1)
                            + ": the first layer " + first + " below the ledge, in " + tier.firstMin() + "-"
                            + tier.firstMax());
                    for (int j = 1; j < rows.size(); j++) {
                        int gap = rows.get(j - 1) - rows.get(j);
                        assertTrue(gap >= tier.gapMin() && gap <= tier.gapMax(), mix + " level " + (i + 1) + ": layer "
                                + (j + 1) + " is " + gap + " under the last, in " + tier.gapMin() + "-"
                                + tier.gapMax());
                    }
                }
            }
        }
        assertEquals(4, facings.size(), "the ledge goes on a seeded wall: all four are drawn " + facings);
    }

    @Test
    void theWitnessSplashesAtLeastRPlusTheHalfBodyInsideThePool() {
        for (String mix : List.of("EEMMH", "HHHHH")) {
            for (int n = 0; n < 8; n++) {
                Plan p = DropperFixtures.plan(mix, n);
                for (int i = 0; i < mix.length(); i++) {
                    DropRules.Level tier = DropRules.levels(mix).get(i);
                    DropCheck.View v = DropperFixtures.view(p, i, tier);
                    DropRun.Result r = DropCheck.witness(DropperFixtures.world(p), v, DropperFixtures.witness(p, i), 0)
                            .result();
                    assertTrue(r.splashed(), mix + " level " + (i + 1) + ": the witness splashes");
                    double[] box = DropMarks.poolBox(v.pool());
                    double margin = Math.min(Math.min(r.body().x() - box[0], box[3] - r.body().x()),
                            Math.min(r.body().z() - box[2], box[5] - r.body().z()));
                    assertTrue(margin >= tier.tube() + DropSim.HALF_WIDTH - 1e-9, mix + " level " + (i + 1)
                            + ": it enters the water " + margin + " inside the pool's edge, at least r + 0.3");
                }
            }
        }
    }

    @Test
    void aFailingPilotWidensItsOpeningByOneTowardTheBlockItHit() {
        DropperGeometry.Shaft s = DropperGeometry.shaft(LegacyBoxes.half(DropperSlots.DROPPER_SLOT, 'A'), 1);
        DropperPlanner.Rect open = new DropperPlanner.Rect(s.x1() + 4, s.z1() + 4, s.x1() + 6, s.z1() + 6);
        assertEquals(new DropperPlanner.Rect(s.x1() + 3, s.z1() + 4, s.x1() + 6, s.z1() + 6),
                DropperPlanner.widen(s, open, s.x1() + 3, s.z1() + 5), "a hit to the west grows it one block west");
        assertEquals(new DropperPlanner.Rect(s.x1() + 4, s.z1() + 4, s.x1() + 7, s.z1() + 7),
                DropperPlanner.widen(s, open, s.x1() + 7, s.z1() + 7), "a hit off a corner grows both ways");
        DropperPlanner.Rect atWall = new DropperPlanner.Rect(s.x1(), s.z1(), s.x1() + 2, s.z1() + 2);
        assertNull(DropperPlanner.widen(s, atWall, s.x1() - 1, s.z1() + 1), "never through the wall");
        assertNull(DropperPlanner.widen(s, open, s.x1() + 5, s.z1() + 5), "a hit inside it can't widen it");
        assertEquals(2, DropperPlanner.WIDENINGS, "at most twice a layer, then the level is redrawn");
        assertEquals(20, DropperPlanner.REDRAWS, "at most 20 redraws, then the straight drop");
    }

    @Test
    void poolsAreSealedAndHoldOnlyStillWater() {
        for (String mix : List.of("EEE", "EEMMH", "HHHHH")) {
            for (int n = 0; n < 6; n++) {
                Plan p = DropperFixtures.plan(mix, n);
                DropWorld w = DropperFixtures.world(p);
                Course c = DropperFixtures.course(p);
                List<double[]> boxes = new ArrayList<>();
                for (int i = 0; i < DropMarks.levels(c); i++) {
                    boxes.add(DropMarks.poolBox(DropMarks.poolOf(c, i)));
                }
                int water = 0;
                for (BlockOp op : p.ops()) {
                    String b = p.blockOf(op);
                    if (!DropBlocks.isWater(b)) {
                        continue;
                    }
                    water++;
                    assertEquals(DropBlocks.WATER, b, mix + ": water is only ever a still source");
                    assertTrue(boxes.stream().anyMatch(x -> op.x() >= x[0] && op.x() + 1 <= x[3] && op.z() >= x[2]
                            && op.z() + 1 <= x[5] && op.y() >= x[1] && op.y() + 1 <= x[4]),
                            mix + ": water only in a pool box: " + op);
                    for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}}) {
                        int x = op.x() + d[0];
                        int y = op.y() + d[1];
                        int z = op.z() + d[2];
                        assertTrue(w.get(x, y, z) == DropWorld.WATER || w.solid(x, y, z),
                                mix + " seed " + n + ": water at " + op + " has water or a block beside and under it");
                    }
                }
                int expected = 0;
                for (DropRules.Level l : DropRules.levels(mix)) {
                    expected += l.pool() * l.pool() * DropperGeometry.POOL_DEPTH;
                }
                assertEquals(expected, water,
                        mix + ": each pool is its tier's size, 3 deep, and nothing else is water");
            }
        }
    }

    @Test
    void theColoursMeanWhatTheyAlwaysMean() {
        Plan p = DropperFixtures.plan("EEMMH", 2);
        Course c = DropperFixtures.course(p);
        for (int i = 0; i < 5; i++) {
            DropRules.Level tier = DropRules.levels("EEMMH").get(i);
            DropCheck.View v = DropperFixtures.view(p, i, tier);
            for (int row : v.layerRows()) {
                for (int x = v.x1(); x <= v.x2(); x++) {
                    for (int z = v.z1(); z <= v.z2(); z++) {
                        String b = DropperFixtures.blockAt(p, x, row, z);
                        assertTrue(b == null || b.equals(DropBlocks.plate(i)) || b.equals(DropBlocks.LIGHT),
                                "level " + (i + 1) + "'s obstacles are its own colour or guide lights: " + b);
                    }
                }
            }
            assertEquals(DropBlocks.LEDGE, DropperFixtures.blockAt(p, v.ledge()[0], v.ledgeTop() - 1, v.ledge()[1]),
                    "level " + (i + 1) + "'s ledge is lime, the start colour");
            String wall = DropperFixtures.blockAt(p, v.x1() + 5, v.ledgeTop(), v.z1() - 1);
            assertEquals(DropBlocks.glass(i), wall, "level " + (i + 1) + "'s walls are its own glass");
            if (!tier.wholeFloor()) {
                String rim = i == 4 ? DropBlocks.RIM_LAST : DropBlocks.RIM;
                double[] box = DropMarks.poolBox(DropMarks.poolOf(c, i));
                int rx = (int) box[0] > v.x1() ? (int) box[0] - 1 : (int) box[3];
                assertEquals(rim, DropperFixtures.blockAt(p, rx, v.surfaceY() - 1, (int) box[2]),
                        "the floor round level " + (i + 1) + "'s pool is "
                                + (i == 4 ? "gold, the finish" : "light blue"));
            } else {
                assertEquals(DropBlocks.LIGHT, DropperFixtures.blockAt(p, v.x1(), v.surfaceY() - 4, v.z1()),
                        "an Easy pool glows from below");
            }
        }
        DropCheck.View first = DropperFixtures.view(p, 0, DropRules.Level.EASY);
        assertEquals("minecraft:glass", DropperFixtures.blockAt(p, first.x2() + 1, first.ledgeTop(), first.z1() + 3),
                "the wall two shafts share is clear glass");
    }

    @Test
    void easyLightsEveryOpeningMediumTheFirstAndHardNone() {
        Plan p = DropperFixtures.plan("EEMMH", 3);
        for (int i = 0; i < 5; i++) {
            DropRules.Level tier = DropRules.levels("EEMMH").get(i);
            DropCheck.View v = DropperFixtures.view(p, i, tier);
            for (int j = 0; j < v.layerRows().size(); j++) {
                int lights = 0;
                for (int x = v.x1(); x <= v.x2(); x++) {
                    for (int z = v.z1(); z <= v.z2(); z++) {
                        String b = DropperFixtures.blockAt(p, x, v.layerRows().get(j), z);
                        lights += DropBlocks.LIGHT.equals(b) ? 1 : 0;
                    }
                }
                if (j < tier.litLayers()) {
                    assertTrue(lights > 0, "level " + (i + 1) + " (" + tier + ") layer " + (j + 1) + " is lit");
                } else {
                    assertEquals(0, lights, "level " + (i + 1) + " (" + tier + ") layer " + (j + 1) + " is not");
                }
            }
        }
    }

    @Test
    void eachLevelHasItsSignItsHeadroomAndItsProofInTheSummary() {
        Plan p = DropperFixtures.plan("EEMMH", 6);
        assertEquals(5, p.signs().size(), "one sign a level");
        for (int i = 0; i < 5; i++) {
            SignText s = p.signs().get(i);
            assertEquals(List.of("LEVEL " + (i + 1) + " of 5", "Step off and", "fall into the", "WATER!"), s.lines(),
                    "level " + (i + 1) + "'s sign");
            assertTrue(s.blockData().startsWith(Palette.WALL_SIGN), "on the wall");
            DropperGeometry.Shaft sh = DropperGeometry.shaft(p.half(), i);
            Box room = p.keepClear().get(i);
            assertTrue(room.contains(new Box(sh.x1(), sh.ledgeTop(), sh.z1(), sh.x2(), p.half().maxY(), sh.z2())),
                    "level " + (i + 1) + "'s inside and the air over its ledge are kept clear");
            assertTrue(p.half().contains(room), "inside the half");
            assertNotNull(DropperFixtures.witness(p, i), "level " + (i + 1) + "'s witness is in the summary, readable");
        }
        assertTrue(p.summary().get(0).contains("(EEMMH)"), "the first line names the mix: " + p.summary().get(0));
        assertEquals(List.of(), DropperValidator.problems(p, "EEMMH"), "and the whole plan is proven");
    }

    @Test
    void aDropperMovedToTheClassicSlotOrArchivedIsStillProven() {
        Plan p = DropperFixtures.plan("EEMMH", 8);
        Plan recalled = PlanShift.to(p, LegacyBoxes.half(DropperSlots.CLASSIC, 'B'));
        assertEquals(List.of(), DropperValidator.problems(recalled),
                "moved whole blocks away it is the same course, proven from its own pools' mix");
        PlanCodec.Read back = PlanCodec.decode(PlanCodec.encode(p));
        assertTrue(back.ok(), "a dropper plan is archived: " + back.problem());
        assertEquals(p.hash(), back.plan().hash(), "and reads back the same layout");
        assertEquals(p.summary(), back.plan().summary(), "with its witnesses");
        assertEquals(List.of(), DropperValidator.problems(back.plan(), "EEMMH"), "still proven");
    }

    @Test
    void theWitnessTextIsItsShortestForm() {
        for (String mix : List.of("EEE", "EEMMH", "HHHHH")) {
            for (int n = 0; n < 3; n++) {
                Plan p = DropperFixtures.plan(mix, n);
                for (int i = 0; i < mix.length(); i++) {
                    DropProgram w = DropperFixtures.witness(p, i);
                    assertEquals(w.normalised().encode(), w.encode(), mix + " seed " + n + " level " + (i + 1)
                            + ": no repeated keys or empty segments for an admin to puzzle over");
                }
            }
        }
    }
}
