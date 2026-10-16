package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.V3GolfFixtures;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The algo-3 golf fixtures, frozen before Golf v4 ({@link V3GolfFixtures}, GOLF-V4-SPEC §7): they read
 * back, pass Adventure Golf's frozen rules (the full check, the kid's tree included) and their
 * witnesses replay, forever; Golf v4's validator never judges them, its planner never rebuilds them,
 * and the frozen planner still makes them block for block.
 */
class V3GolfFixturesTest {

    @Test
    void thereAreThreeAndTheyReadBackAsTheIndexSays() {
        List<V3GolfFixtures.Fixture> all = V3GolfFixtures.all();
        assertEquals(List.of("golf-9-a", "golf-9-b", "golf-3"), all.stream().map(V3GolfFixtures.Fixture::name).toList(),
                "Golf of the Week twice, Tiny Golf once");
        for (V3GolfFixtures.Fixture f : all) {
            assertEquals(3, f.plan().algo(), f + " is an algo-3 plan");
            assertEquals(f.hash(), f.plan().hash(), f + ": the archived plan is the indexed one");
            assertNotNull(f.tag(), f + " has its row's tag");
            assertEquals(3, f.tag().algo(), f + "'s tag says algo 3");
            assertEquals(f.hash(), f.tag().planHash(), f + "'s tag names its plan");
            assertEquals(f.golf().witness(), f.tag().witness(), f + ": the row stores the plan's witness lines");
            assertSame(PlotGrid.V3, PlotGrid.of(f.plan().slot(), f.plan().algo()), f + " stands on the 20 x 40 grid");
        }
    }

    @Test
    void theyPassAdventureGolfsFrozenRulesForever() {
        for (V3GolfFixtures.Fixture f : V3GolfFixtures.all()) {
            assertEquals(List.of(), GolfValidator.problems(f.plan()), f + " passes the full v3 check");
            assertTrue(!GolfValidator.v4(f.plan()), f + " is never judged by Golf v4's play rules");
        }
    }

    @Test
    void theirWitnessesReplay() {
        for (V3GolfFixtures.Fixture f : V3GolfFixtures.all()) {
            PlannedGolf g = f.golf();
            PlanBlocks grid = PlanBlocks.of(f.plan().half(), f.plan().palette(), f.plan().ops());
            for (int i = 0; i < g.course().holes().size(); i++) {
                GolfCourse.Hole h = g.course().holes().get(i);
                List<Putt> line = f.tag().witness().get(i);
                GolfShot.Replay r = GolfShot.replay(grid, h, line);
                assertTrue(r.holed() && r.strokes() == line.size(), f + " hole " + (i + 1) + "'s stored line holes");
                assertEquals(h.par(), line.size() + 1, f + " hole " + (i + 1) + ": par E + 1, as it was made");
            }
        }
    }

    @Test
    void theFrozenPlannerStillMakesThemAndGolfV4NeverRebuildsThem() throws GenFailed {
        for (V3GolfFixtures.Fixture f : V3GolfFixtures.all()) {
            PlanInput in = new PlanInput(f.slot(), LegacyBoxes.half(f.slot(), f.half()), f.half(), f.day(), 0, f.seed(),
                    f.mix(), 8, 0, null);
            Plan again = GolfPlanner.v3().plan(in);
            assertEquals(f.hash(), again.hash(), f + ": the frozen Adventure Golf planner makes it block for block");
            assertEquals(f.hash(), GolfPlanner.v3().rederive(in, f.tag()).hash(), f + ": and re-derives it from its tag");
            assertThrows(GenFailed.class, () -> new GolfPlanner().rederive(in, f.tag()),
                    f + ": Golf v4 refuses it (the engine checks an older layout's structure instead)");
        }
        assertEquals(Slots.TINY_GOLF, V3GolfFixtures.named("golf-3").slot(), "golf-3 is Tiny Golf's");
    }

    /**
     * The audit's GOLF01-04: an algo-3 edition made again from its seed (a {@code seed:} recall or keep) is made
     * by the frozen Adventure Golf planner in its own 0.36 size, never by Golf v4, so it is the same course: its
     * stored plan hash where it was made, and its stored plan moved wherever it is made again (Classic Golf's
     * corner, a keep plot, across x 8192 too); and the planner it is made by re-derives it from its tag.
     */
    @Test
    void anAlgo3EditionMadeAgainFromItsSeedIsTheSameCourse() throws GenFailed {
        GolfPlanner engine = new GolfPlanner();
        for (V3GolfFixtures.Fixture f : V3GolfFixtures.all()) {
            Planner.Remake again = engine.remake(f.slot(), GolfPlanner.ALGO_V3);
            assertTrue(again.exact(), f + ": the same course");
            assertEquals(GolfPlanner.ALGO_V3, again.planner().algo(), f + ": by Adventure Golf's frozen planner");
            Box made = f.plan().half();
            assertEquals(List.of(made.sizeX(), made.sizeY(), made.sizeZ()), List.of(again.sizeX(), again.sizeY(),
                    again.sizeZ()), f + ": in the size it was made in");
            PlanInput own = new PlanInput(f.slot(), made, f.half(), f.day(), 0, f.seed(), f.mix(), 8,
                    GolfPlanner.COURSE_BUDGET, null);
            assertEquals(f.hash(), again.planner().plan(own).hash(), f + ": its stored plan hash");
            assertEquals(f.hash(), again.planner().rederive(own, f.tag()).hash(), f + ": and from its tag");
            for (Box there : List.of(Box.sized(8768, 160, 4896, again.sizeX(), again.sizeY(), again.sizeZ()),
                    Box.sized(1768, 128, 7304, again.sizeX(), again.sizeY(), again.sizeZ()))) {
                PlanInput in = new PlanInput(f.slot(), there, 'A', f.day(), 0, f.seed(), f.mix(), 8,
                        GolfPlanner.COURSE_BUDGET, null);
                assertEquals(PlanShift.to(f.plan(), there).hash(), again.planner().plan(in).hash(),
                        f + ": its stored plan, moved to " + there.describe());
            }
        }
        Planner.Remake v4 = engine.remake(Slots.DAILY_GOLF, GolfPlanner.ALGO);
        assertTrue(v4.exact() && v4.planner().algo() == GolfPlanner.ALGO && v4.sizeX() == Slots.DAILY_GOLF.sizeX()
                && v4.sizeZ() == Slots.DAILY_GOLF.sizeZ(), "a Golf v4 edition: Golf v4 at the slot's size");
        Planner.Remake v2 = engine.remake(Slots.DAILY_GOLF, 2);
        assertTrue(!v2.exact() && v2.planner().algo() == GolfPlanner.ALGO,
                "algo 2: no frozen copy, so today's Golf v4, and not the same course");
        assertEquals(GolfPlanner.ALGO_V3, GolfPlanner.v3().remake(Slots.TINY_GOLF, GolfPlanner.ALGO_V3).planner()
                .algo(), "the frozen planner says the same");
    }
}
