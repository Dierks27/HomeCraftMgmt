package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.DailySettings.SlotConfig;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.dropper.DropMarks;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import com.dierks.homecraft.games.gen.dropper.DropperValidator;
import com.dierks.homecraft.games.gen.engine.GenKit.FakeWorld;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper in the Fresh Courses engine (EVENTS-DROPPER-SPEC §B.1.2, §B.1.9, §B.1.10; WIRING §2-3),
 * with no server.
 *
 * <p>Pinned here: with every slot and Classics slot on, the droppers' shipped origins pass every
 * region check and stand 32 from everything; the keep plot size doesn't move; a dropper's plan may use
 * still pool water and no other generator's may, nor may a dropper use flowing water; a dropper plan
 * round-trips through the archive codec and, moved for a recall or a keep, is proven again where it
 * will stand (a spoiled pool is refused); the planner's work budget is the counted one; a dropper's
 * star factors are its mix's rounded tier; the live check sees water through the chunk snapshots and
 * finds a drained pool; and the feed's past sets name a dropper's tier like any trial's.
 */
class DropperEngineTest {

    private static Plan plan(Slots.Def slot, String mix, int n) {
        long day = 20_000 + n;
        try {
            return new DropperPlanner().plan(new PlanInput(slot, slot.half('A'), 'A', day, 0,
                    GenSeed.seed(0x5EC12E7L, day, slot.id(), 0), mix, 6, 0, null));
        } catch (GenFailed e) {
            throw new AssertionError(mix + " plans: " + e.getMessage(), e);
        }
    }

    @Test
    void withEverythingOnTheDroppersStillPassEveryRegionCheck() {
        List<SlotConfig> all = new ArrayList<>();
        for (SlotConfig c : DailySettings.defaults().slots()) {
            all.add(c.withEnabled(true));
        }
        for (SlotConfig c : DailySettings.defaults().archive().classics()) {
            all.add(c.withEnabled(true));
        }
        assertTrue(all.stream().anyMatch(c -> c.id().equals("fresh_classic_dropper")), "Classic Dropper is a Classics slot");
        List<String> warns = new ArrayList<>();
        List<SlotConfig> out = Regions.validate(all, warns::add, "games.fresh.slots");
        assertEquals(List.of(), warns, "the shipped dropper origins need no WARN, with every other area on");
        assertEquals(all, out, "and nothing is moved or switched off");
        assertEquals(144, KeepArea.PLOT_X, "the keep plot is as wide as before (Sky Rings' 128 + 16)");
        assertEquals(176, KeepArea.PLOT_Y, "as tall");
        assertEquals(336, KeepArea.PLOT_Z, "as long: a dropper fits in the plot size that was already there");
        Box build = new KeepArea(4096, 128, 5376, 24).build(1, Slots.FRESH_DROPPER);
        assertEquals(List.of(64, 64, 16), List.of(build.sizeX(), build.sizeY(), build.sizeZ()),
                "a kept dropper stands in its plot at its own size");
    }

    @Test
    void onlyADroppersPlanMayUseStillPoolWater() {
        List<String> palette = List.of("minecraft:glass", "minecraft:water[level=0]");
        assertEquals(List.of(), PlanCheck.paletteProblems(palette, Slots.FRESH_DROPPER), "a dropper's pool water is fine");
        assertEquals(List.of(), PlanCheck.paletteProblems(palette, Slots.CLASSIC_DROPPER), "and a Classic Dropper's");
        for (Slots.Def d : List.of(Slots.DAILY_PARKOUR_EASY, Slots.SKY_RINGS, Slots.DAILY_GOLF, Slots.ICE_BOAT)) {
            assertEquals(List.of("minecraft:water[level=0]"), PlanCheck.paletteProblems(palette, d),
                    d.id() + " still refuses water");
        }
        assertEquals(List.of("minecraft:water[level=3]"), PlanCheck.paletteProblems(List.of("minecraft:water[level=3]"),
                Slots.FRESH_DROPPER), "flowing water is refused even for a dropper");
        assertEquals(List.of("minecraft:lava"), PlanCheck.paletteProblems(List.of("minecraft:lava"), Slots.FRESH_DROPPER),
                "and so is anything else outside the palette");

        Plan p = plan(Slots.FRESH_DROPPER, "EEMMH", 1);
        assertEquals(List.of(), PlanCheck.problems(p, Slots.FRESH_DROPPER, p.half()), "a real dropper plan passes");
        assertTrue(PlanCheck.problems(p, Slots.DAILY_PARKOUR_EASY, p.half()).stream()
                .anyMatch(s -> s.contains("water")), "the same blocks as anyone else's plan are refused for the water");
        assertEquals(List.of(), PlanCheck.generator(new DropperPlanner(), p, input(Slots.FRESH_DROPPER, "EEMMH", 1)),
                "the dropper's own validator is run on the planner thread, and it passes");
    }

    private static PlanInput input(Slots.Def slot, String mix, int n) {
        long day = 20_000 + n;
        return new PlanInput(slot, slot.half('A'), 'A', day, 0, GenSeed.seed(0x5EC12E7L, day, slot.id(), 0), mix, 6, 0,
                null);
    }

    @Test
    void aPlanIsProvenAgainstTheMixItWasAskedForNotTheMixItsPoolsName() {
        Plan hard = plan(Slots.EASY_DROPPER, "HHH", 1);
        PlanInput easy = input(Slots.EASY_DROPPER, "EEE", 1);
        assertEquals(List.of(), PlanCheck.problems(hard, Slots.EASY_DROPPER, hard.half()),
                "(the shared checks can't tell: it is a sound dropper for this half)");
        List<String> refused = PlanCheck.generator(new DropperPlanner(), hard, easy);
        assertFalse(refused.isEmpty(), "but an HHH plan for a slot asked to make EEE is refused");
        assertTrue(refused.stream().anyMatch(r -> r.contains("pool")), "for its pools, which aren't Easy's: " + refused);
        assertEquals(List.of(), PlanCheck.generator(new DropperPlanner(), hard, input(Slots.EASY_DROPPER, "hhh", 1)),
                "the mix it was asked for passes, however it is written");
        assertFalse(PlanCheck.generator(new DropperPlanner(), plan(Slots.FRESH_DROPPER, "EEMMH", 3),
                input(Slots.FRESH_DROPPER, "EEMM", 3)).isEmpty(), "and so is a plan with a level more than asked for");
        assertEquals(List.of(), PlanCheck.generator(new DropperPlanner(), hard, easy, true),
                "a heal's plan is its tag's own layout (its hash is checked next): its pools name its mix");
    }

    @Test
    void aDropperRoundTripsThroughTheArchiveAndIsProvenAgainWhereItIsMoved() {
        Plan p = plan(Slots.EASY_DROPPER, "EEE", 2);
        PlanCodec.Read read = PlanCodec.decode(PlanCodec.encode(p));
        assertTrue(read.ok(), "it reads back: " + read.problem());
        assertEquals(p, read.plan(), "every block, pool, sign and mark exactly");

        for (Box to : List.of(Slots.CLASSIC_DROPPER.half('B'), new KeepArea(4096, 128, 5376, 24).build(3,
                Slots.EASY_DROPPER))) {
            Plan moved = PlanShift.to(read.plan(), to);
            assertEquals(to, moved.half(), "moved to " + to.describe());
            assertEquals(List.of(), PlanCheck.problems(moved, Slots.EASY_DROPPER, to),
                    "its shared checks pass there (against the slot it was made for, as recall and keep check it)");
            assertEquals(List.of(), PlanCheck.movedProblems(moved, Slots.EASY_DROPPER),
                    "and it is proven sealed and solvable where it will stand");
            Course c = ((PlannedTrial) moved.course()).course();
            Course was = ((PlannedTrial) p.course()).course();
            assertEquals(was.start().x() + to.minX() - p.half().minX(), c.start().x(), 1e-9, "its marks moved with it");
            assertEquals(was.fallY() + to.minY() - p.half().minY(), c.fallY(), 1e-9, "and its fall height");
        }

        BlockOp water = p.ops().stream().filter(op -> Palette.poolWater(p.blockOf(op))).findFirst().orElseThrow();
        List<BlockOp> spoiled = new ArrayList<>();
        for (BlockOp op : p.ops()) {
            boolean besideWater = op.y() == water.y() && op.z() == water.z() && op.x() == water.x() - 1;
            if (!besideWater) {
                spoiled.add(op);
            }
        }
        Plan leaky = Plan.of(p.slot(), p.algo(), p.seed(), p.half(), p.palette(), spoiled, p.signs(), p.keepClear(),
                p.course(), p.summary(), p.work());
        Plan movedLeaky = PlanShift.to(leaky, Slots.CLASSIC_DROPPER.half('A'));
        assertFalse(PlanCheck.movedProblems(movedLeaky, Slots.EASY_DROPPER).isEmpty(),
                "a pool with a hole in its wall is refused wherever it would go");
        assertFalse(DropperValidator.problems(leaky).isEmpty(), "(the validator's own verdict)");
        assertEquals(List.of(), PlanCheck.movedProblems(movedLeaky, Slots.DAILY_PARKOUR_HARD),
                "other generators' moved plans need only the shared checks, as before");
    }

    @Test
    void theWorkBudgetAndTheStarTierAreTheDroppers() {
        assertEquals(DropperPlanner.WORK_BUDGET, GenService.WORK.get(Slots.DROPPER), "counted pilot ticks, not time");
        assertEquals(300_000L, GenService.WORK.get(Slots.DROPPER), "the spec's 300k");
        assertEquals(ParkourPlanner.WORK_BUDGET, GenService.WORK.get(Slots.PARKOUR), "the others' are unchanged");
        assertEquals("easy", GenService.starTier(Slots.EASY_DROPPER, "EEE"), "EEE takes easy's star factors");
        assertEquals("medium", GenService.starTier(Slots.FRESH_DROPPER, "EEMMH"), "EEMMH medium's (the rounded mean)");
        assertEquals("hard", GenService.starTier(Slots.FRESH_DROPPER, "HHHHM"), "HHHHM hard's");
        assertEquals("hard", GenService.starTier(Slots.DAILY_PARKOUR_HARD, "hard"), "a parkour slot's tier is its own");
        assertEquals("EEEMMMMHH", GenService.starTier(Slots.DAILY_GOLF, "EEEMMMMHH"), "golf is untouched");
    }

    @Test
    void theLiveCheckSeesWaterThroughTheChunkSnapshots() {
        Plan p = plan(Slots.EASY_DROPPER, "EEE", 3);
        FakeWorld w = new FakeWorld("games");
        for (BlockOp op : p.ops()) {
            w.put(op.x(), op.y(), op.z(), p.blockOf(op));
        }
        Box h = p.half();
        for (int cx = h.minX() >> 4; cx <= h.maxX() >> 4; cx++) {
            w.load(cx, h.minZ() >> 4, ok -> {
            });
        }
        Course c = ((PlannedTrial) p.course()).course();
        LiveProof.Solid solid = (x, y, z) -> {
            WorldPort.ChunkView v = w.snapshot(x >> 4, z >> 4);
            return v != null && !v.air(x, y, z);
        };
        LiveProof.Solid water = (x, y, z) -> GenService.water(w.snapshot(x >> 4, z >> 4), x, y, z);
        assertEquals(List.of(), LiveProof.structure(c, solid, water), "every ledge and pool of the built dropper stands");

        DropMarks.Probe probe = DropMarks.probes(c).stream().filter(DropMarks.Probe::water).reduce((a, b) -> b)
                .orElseThrow();
        w.blocks.remove(GenKit.pos(probe.x(), probe.y(), probe.z()));
        assertEquals(List.of("level 3's pool has no water at its centre"), LiveProof.structure(c, solid, water),
                "a drained pool is found, by level");
        assertFalse(GenService.water(null, 0, 0, 0), "no snapshot: no water");
    }

    @Test
    void theFeedNamesADroppersPastSetByItsTier() {
        assertEquals("medium", FreshFeed.tier(Slots.DROPPER, "EEMMH"), "the rounded mean, as its row");
        assertEquals("easy", FreshFeed.tier(Slots.DROPPER, "EEE"), "Easy Dropper's");
        assertEquals("hard", FreshFeed.tier(Slots.PARKOUR, "Hard"), "a trial's tier as it always was");
        assertEquals("zzz", FreshFeed.tier(Slots.DROPPER, "zzz"), "an unreadable mix is passed on as it is");
    }
}
