package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expert on a pond hole (Course Variety §3.9): par is the safe line. A pond on the fewest-putts
 * line (a hole-in-one that skims past the water) is refused and the next-best line with room to
 * miss sets par; a pond beside the lane changes nothing; a dry hole is searched exactly as it always
 * was. And the sloppy player, followed into every outcome of its tree, is never wet on a hole the
 * validator accepts (G-T3).
 */
class SafeExpertTest {

    private static ExpertSearch.Result plain(AdventureKit.Drawn d) throws GenFailed {
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        return ExpertSearch.search(g, h, LaneMap.of(g, h, 3), ExpertSearch.MAX_DEPTH, Work.unlimited());
    }

    private static ExpertSearch.Result safe(AdventureKit.Drawn d, Work work) throws GenFailed {
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        return SafeExpert.search(g, h, LaneMap.of(g, h, 3), ExpertSearch.MAX_DEPTH, work);
    }

    @Test
    void aPondOnTheWitnessLineIsRefusedAndTheSafeLineSetsPar() throws GenFailed {
        AdventureKit.Drawn d = GolfValidatorV3Test.brave();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        ExpertSearch.Result brave = plain(d);
        assertEquals(1, brave.strokes(), "the fewest putts: a hole-in-one " + brave.witness());
        assertEquals(1, SafeExpert.unsafePutt(g, h, brave.witness()), "whose putt splashes 3 degrees off");
        ExpertSearch.Result safe = safe(d, Work.unlimited());
        assertTrue(safe.found(), "the safe expert finds a line");
        assertEquals(2, safe.strokes(), "two putts with room to miss: par 3, and the brave ace is a birdie-plus");
        assertEquals(0, SafeExpert.unsafePutt(g, h, safe.witness()), "every putt of it is dry 3 degrees either side");
        GolfShot.Replay r = GolfShot.replay(g, h, safe.witness());
        assertTrue(r.holed() && r.putts() == 2 && r.strokes() == 2, "and it replays exactly from the tee");
    }

    @Test
    void aPondBesideTheLaneChangesNothing() throws GenFailed {
        AdventureKit.Drawn d = GolfValidatorV3Test.pondBeside();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        ExpertSearch.Result plain = plain(d);
        ExpertSearch.Result safe = safe(d, Work.unlimited());
        assertTrue(LaneMap.of(g, h, 3).hazards() > 0, "(water in play)");
        assertEquals(plain.witness(), safe.witness(), "the expert's line was already safe: the same line");
        assertEquals(0, SafeExpert.unsafePutt(g, h, safe.witness()), "dry either side");
    }

    @Test
    void aDryHoleIsSearchedExactlyAsItAlwaysWas() throws GenFailed {
        for (HoleLayout l : List.of(GolfKit.straight(12, 1), GolfKit.dogleg(12, 8, 0), GolfKit.island(6, 8, 10))) {
            PlanBlocks g = GolfKit.grid(l);
            GolfCourse.Hole h = GolfKit.hole(l);
            LaneMap lane = LaneMap.of(g, h, 3);
            Work a = Work.unlimited();
            Work b = Work.unlimited();
            ExpertSearch.Result plain = ExpertSearch.search(g, h, lane, 3, a);
            ExpertSearch.Result safe = SafeExpert.search(g, h, lane, 3, b);
            assertEquals(plain, safe, l.describe() + ": no water in play, so ExpertSearch's own answer");
            assertEquals(a.used(), b.used(), l.describe() + ": at ExpertSearch's own cost");
        }
    }

    @Test
    void theTwinsAreCountedWorkAndTheSearchStopsWhenItRunsOut() throws GenFailed {
        AdventureKit.Drawn d = GolfValidatorV3Test.brave();
        Work plainWork = Work.unlimited();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        ExpertSearch.search(g, h, LaneMap.of(g, h, 3), 3, plainWork);
        Work safeWork = Work.unlimited();
        safe(d, safeWork);
        assertTrue(safeWork.used() > plainWork.used(), "a pond hole costs more: every dry putt's twins are played");
        ExpertSearch.Result starved = safe(d, new Work(50, null));
        assertEquals(ExpertSearch.Status.OVER_BUDGET, starved.status(), "and a spent budget stops it, as it stops"
                + " the plain search");
    }

    @Test
    void aTwinIntoThePondIsNotDry() {
        AdventureKit.Drawn d = GolfValidatorV3Test.pondBeside();
        PlanBlocks g = d.grid();
        GolfCourse.Hole h = d.hole(2);
        BallPhysics.Hole area = GolfShot.area(g, h);
        double x = d.x(6) + 0.5;
        double z = d.z(6) + 0.5;
        assertEquals(Boolean.FALSE, SafeExpert.twinsDry(g, area, x, AdventureKit.TURF, z, new Putt(-90, 3),
                Work.unlimited()), "a putt at the pond from beside it: its twins splash too");
        assertEquals(Boolean.TRUE, SafeExpert.twinsDry(g, area, x, AdventureKit.TURF, z, new Putt(0, 2),
                Work.unlimited()), "a putt along the lane: both twins stay dry");
        assertEquals(null, SafeExpert.twinsDry(g, area, x, AdventureKit.TURF, z, new Putt(0, 2), new Work(1, null)),
                "and with no work left for both twins, it can't say");
    }

    @Test
    void theSloppyPlayerOnAnAcceptedPondHoleIsNeverWetAndItsTreeIsUnchangedByWatching() throws GenFailed {
        for (AdventureKit.Drawn d : List.of(GolfValidatorV3Test.pondBeside(),
                AdventureKit.draw(0, LaneMapV3Test.MIDDLE_POND), GolfValidatorV3Test.brave())) {
            List<Putt> line = d.line();
            PlanBlocks g = d.grid();
            GolfCourse.Hole h = d.hole(GolfPlanner.par(line.size()));
            LaneMap lane = LaneMap.of(g, h, 3);
            KidPolicy.Result quiet = KidPolicy.evaluate(g, h, lane, h.par() + 1, Work.unlimited());
            List<GolfShot.Result> seen = new ArrayList<>();
            KidPolicy.Result watched = KidPolicy.evaluate(g, h, lane, h.par() + 1, Work.unlimited(), seen::add);
            assertEquals(quiet, watched, "watching the tree doesn't change it");
            assertTrue(watched.within(), "the kid finishes within par + 1");
            assertFalse(seen.isEmpty(), "every outcome of its tree was seen");
            assertTrue(seen.stream().noneMatch(GolfShot.Result::penalty),
                    "and none of them is a splash: the kid is never wet (G-T3)");
        }
    }
}
