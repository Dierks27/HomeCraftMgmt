package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTagCodec;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.Pools;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatValidator;
import com.dierks.homecraft.games.gen.boat.LoopValidatorV2;
import com.dierks.homecraft.games.gen.golf.GolfValidator;
import com.dierks.homecraft.games.gen.golf.PlanBlocks;
import com.dierks.homecraft.games.trial.Course;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The algo-2 layouts frozen before Course Variety (§1.4): each reads back as the layout its row's
 * tag names, made by the algo-2 planners (their golden hashes); each passes the engine's own checks
 * and its generator's frozen validator; golf's witness lines replay on its blocks, and a golf
 * course moved into Classic Golf or a keep plot is proven again where it stands (§1.2). The live
 * structure checks the heal path runs on an older layout pass them too: every boat mark sits on the
 * ice, and no v2 golf hole has any water to leak.
 */
class V2FixturesTest {

    @Test
    void thereAreThreeLoopsAndThreeGolfCoursesAtTheAlgoTwoGoldens() {
        assertEquals(List.of("boat-easy", "boat-medium", "boat-hard"),
                V2Fixtures.boats().stream().map(V2Fixtures.Fixture::name).toList(), "one loop per tier");
        assertEquals(List.of("golf-9-a", "golf-9-b", "golf-3"),
                V2Fixtures.golf().stream().map(V2Fixtures.Fixture::name).toList(), "EEEMMMMHH twice, EEE once");
        // the hashes BoatPlannerTest and GolfPlannerTest pinned at algo 2 (seeds 1, C0FFEE, 5EED5EED; 1, 3f2a..., 1)
        assertEquals(List.of("17716620da48", "f556e10cb2e4", "b3ce7c41112a", "3c87c264a106", "655b02693a68",
                "47bd03bb54b0"), V2Fixtures.all().stream().map(V2Fixtures.Fixture::hash).toList(),
                "they are the algo-2 planners' own golden layouts");
    }

    @Test
    void eachReadsBackAsTheAlgoTwoLayoutItsTagNames() {
        for (V2Fixtures.Fixture f : V2Fixtures.all()) {
            Plan p = f.plan();
            assertEquals(2, p.algo(), f + ": an algo-2 plan");
            assertEquals(f.hash(), p.hash(), f + ": the plan the index pins");
            assertEquals(Plan.hash(p.palette(), p.ops(), p.signs(), p.course()), p.hash(), f + ": whose blocks hash so");
            assertEquals(f.slot().id(), p.slot(), f + ": for its slot");
            assertEquals(LegacyBoxes.half(f.slot(), f.half()), p.half(), f + ": in its 0.35 half, where it was made");
            assertEquals(f.seed(), p.seed(), f + ": from its seed");
            assertEquals(f.slot().id(), f.tag().slot(), f + ": the row's tag names the slot");
            assertEquals(2, f.tag().algo(), f + ": and algo 2");
            assertEquals(p.hash(), f.tag().planHash(), f + ": and this layout");
            assertEquals(f.seed(), f.tag().seed(), f + ": and its seed");
            assertEquals(f.half(), f.tag().half(), f + ": and its half");
            assertEquals(f.day(), f.tag().day(), f + ": and its edition");
            assertEquals(f.tag(), GenTagCodec.read(GenTagCodec.write(f.tag())), f + ": the tag round-trips");
            assertEquals(List.of(), PlanCheck.problems(p, f.slot(), p.half()),
                    f + ": the engine's shared checks pass, the new palette state rules included");
            assertEquals(List.of(), Palette.stateProblems(p.palette()), f + ": no block of it breaks a state rule");
        }
    }

    @Test
    void theLoopsPassTheFrozenLoopCheckThroughTheDispatcher() {
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            assertEquals(List.of(), LoopValidatorV2.problems(f.plan(), f.tierOrMix()), f + ": the frozen v2 check");
            assertEquals(List.of(), BoatValidator.problems(f.plan(), f.tierOrMix()), f + ": through the dispatcher");
            assertTrue(1000L * f.trial().course().minSeconds() <= f.trial().refMs(), f + ": its times fit");
            assertEquals(f.trial().refMs(), f.tag().refMs(), f + ": the row keeps its reference time");
        }
    }

    @Test
    void theGolfCoursesPassTheirFullCheckAndTheirWitnessesReplay() {
        for (V2Fixtures.Fixture f : V2Fixtures.golf()) {
            Plan p = f.plan();
            assertEquals(List.of(), GolfValidator.problems(p), f + ": the full check, the sloppy player included");
            assertEquals(List.of(), GolfValidator.quickProblems(p), f + ": and the quick one");
            assertEquals(f.golfCourse().witness(), f.tag().witness(), f + ": the row keeps the plan's witness lines");
            assertEquals(f.golfCourse().attempts(), f.tag().attempts(), f + ": and its attempts");
            PlanBlocks blocks = PlanBlocks.of(p.half(), p.palette(), p.ops());
            assertEquals(List.of(), LiveProof.replay(blocks, f.golfCourse().course().holes(), f.tag().witness()),
                    f + ": every hole's witness line holes out in exactly its putts on the plan's blocks");
            assertEquals(List.of(), Pools.problems(p, List.of(), Pools.GOLF_DEPTH), f + ": no water at all in v2");
        }
    }

    @Test
    void aMovedGolfCourseIsProvenAgainWhereItStands() {
        KeepArea keep = LegacyBoxes.keep();
        for (V2Fixtures.Fixture f : V2Fixtures.golf()) {
            for (Box to : List.of(LegacyBoxes.half(Slots.CLASSIC_GOLF, 'A'), LegacyBoxes.half(Slots.CLASSIC_GOLF, 'B'),
                    keep.build(2, f.slot()), keep.build(7, f.slot()))) {
                Box at = Box.sized(to.minX(), to.minY(), to.minZ(), f.plan().half().sizeX(), f.plan().half().sizeY(),
                        f.plan().half().sizeZ());
                Plan moved = PlanShift.to(f.plan(), at);
                assertEquals(List.of(), PlanCheck.problems(moved, f.slot(), at),
                        f + " moved to " + at.describe() + ": the shared checks pass");
                assertEquals(List.of(), PlanCheck.movedProblems(moved, f.slot()),
                        f + " moved to " + at.describe() + ": and golf's quick check proves it there (§1.2)");
            }
        }
    }

    @Test
    void theHealPathsStructureChecksPassEveryFrozenLayout() {
        for (V2Fixtures.Fixture f : V2Fixtures.all()) {
            Set<Long> solid = new HashSet<>();
            for (BlockOp op : f.plan().ops()) {
                solid.add(GenKit.pos(op.x(), op.y(), op.z()));
            }
            LiveProof.Solid isSolid = (x, y, z) -> solid.contains(GenKit.pos(x, y, z));
            LiveProof.Solid noWater = (x, y, z) -> false;
            if (f.golf()) {
                assertEquals(List.of(), LiveProof.structure(f.golfCourse().course(), f.plan().half(), isSolid, isSolid,
                        noWater), f + ": every tee and cup stands, and no pond to leak");
            } else {
                Course c = f.trial().course();
                assertEquals(List.of(), LiveProof.structure(c, isSolid),
                        f + ": the start, every checkpoint and the finish sit on the ice (§2.12)");
                assertTrue(c.checkpoints().size() > 1, f + ": (with checkpoints to check)");
            }
        }
    }
}
