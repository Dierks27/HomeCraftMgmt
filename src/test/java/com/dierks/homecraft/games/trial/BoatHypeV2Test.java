package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Mountain Run v2 says about itself (MOUNTAIN-V2-SPEC §12, red-team F04, F05), with no server, on the
 * hand-made v2 run ({@link MountainRunsV2}) and the algo-3 Medium run ({@link MountainRuns}).
 *
 * <p>Pinned here: only a Fresh Ice Boat layout of algo 4 or later is a v2; its style is read off its seed,
 * so the tile ("Winding Road · 19 drops · ") and the hype ("This week: the Winding Road - 19 drops, 20 blocks
 * down the mountain!", or the Slalom's "through the gates") always say what the run really is; its model
 * time is its tag's T_m (5/4 of the star reference), and any other course has none; its checkpoints are
 * quiet except every 10th, the one nearest half way and the one before the Final Drop, each with its own
 * title; half way is measured from checkpoint 1, never the start, so every grid spot hears "Halfway!" at the
 * same checkpoint (audit M00); and an algo-3 run, a hand-built track and every other course read exactly as
 * before.
 */
class BoatHypeV2Test {

    @Test
    void onlyAFreshIceBoatLayoutOfAlgo4OnIsAMountainRunV2() {
        assertTrue(BoatHype.mountainV2(MountainRunsV2.road()), "algo 4 is Mountain Run v2");
        assertTrue(BoatHype.mountainV2(MountainRunsV2.of(MountainRunsV2.tag(1, 120_000, 5, 7))), "and a later one");
        assertFalse(BoatHype.mountainV2(MountainRuns.medium()), "the algo-3 spiral isn't");
        assertFalse(BoatHype.mountainV2(MountainRunsV2.of(null)), "a hand-built track isn't");
        assertFalse(BoatHype.mountainV2(null), "nothing isn't");
        assertEquals(BoatPlanner.ALGO, BoatHype.V2_ALGO, "v2 is boat planner algo 4");
    }

    @Test
    void theStyleIsReadOffTheSeedSoTheCopyFollowsTheRealRun() {
        assertEquals(BoatStyle.ROAD, BoatHype.style(MountainRunsV2.road()), "a Winding Road seed");
        assertEquals(BoatStyle.SLALOM, BoatHype.style(MountainRunsV2.slalom()), "a Slalom seed");
        assertEquals(BoatStyle.of(MountainRunsV2.ROAD_SEED), BoatHype.style(MountainRunsV2.road()),
                "BoatStyle.of(c.gen().seed()), the planner's own rule");
        assertTrue(BoatHype.slalom(MountainRunsV2.slalom()) && !BoatHype.slalom(MountainRunsV2.road()),
                "slalom() says which");
        assertNull(BoatHype.style(MountainRuns.medium()), "the spiral has no style");
        assertNull(BoatHype.style(MountainRunsV2.of(null)), "nor has a hand-built track");
        assertFalse(BoatHype.slalom(MountainRuns.medium()), "so it is never a Slalom");
    }

    @Test
    void theTileNamesTheStyleAndTheDrops() {
        assertEquals(MountainRunsV2.DROPS, BoatHype.drops(MountainRunsV2.road()), "fixture: 18 Hops and the Final Drop");
        assertEquals("Winding Road · 19 drops · ", BoatHype.fact(MountainRunsV2.road()), "a Winding Road's tile");
        assertEquals("Slalom · 19 drops · ", BoatHype.fact(MountainRunsV2.slalom()), "a Slalom's tile");
        assertEquals(GenCopy.boatV2Tile(false, 19), BoatHype.fact(MountainRunsV2.road()), "MA's GenCopy line");
        assertEquals("5 drops · ", BoatHype.fact(MountainRuns.medium()), "the algo-3 run reads as before");
        assertEquals("", BoatHype.fact(MountainRunsV2.of(null)), "a hand-built track says nothing");
    }

    @Test
    void theHypeNamesTheStyleTheDropsAndHowFarDown() {
        assertEquals(MountainRunsV2.DESCENT, BoatHype.descent(MountainRunsV2.road()), "fixture: 20 blocks down");
        assertEquals("&bThis week: the Winding Road - 19 drops, 20 blocks down the mountain!",
                BoatHype.line(MountainRunsV2.road(), true), "MOUNTAIN-V2-SPEC §12: the Winding Road's hype");
        assertEquals("&bThis week: the Slalom - 19 drops through the gates!",
                BoatHype.line(MountainRunsV2.slalom(), true), "and the Slalom's");
        assertEquals("&bToday: the Winding Road - 19 drops, 20 blocks down the mountain!",
                BoatHype.line(MountainRunsV2.of(MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000, 4, 1)), true),
                "in a daily set's own words");
        assertNull(BoatHype.line(MountainRunsV2.road(), false), "the hype switch still turns it off");
        assertEquals("&bThis week: 5 drops down the mountain!", BoatHype.line(MountainRuns.medium(), true),
                "the algo-3 run reads as before");
    }

    @Test
    void theModelTimeIsTheTagsTmNeverTheStarReference() {
        GenTag t = MountainRunsV2.road().gen();
        assertEquals(96_000, t.refMs(), "fixture: the star reference is 4/5 of T_m (red-team F04)");
        assertEquals(120_000, BoatHype.modelMs(MountainRunsV2.road()), "T_m, what the windows scale with");
        assertEquals(BoatPlanner.modelMs(t), BoatHype.modelMs(MountainRunsV2.road()), "MA's BoatPlanner.modelMs");
        assertEquals(0, BoatHype.modelMs(MountainRuns.medium()), "an algo-3 run's windows don't scale");
        assertEquals(0, BoatHype.modelMs(MountainRunsV2.of(null)), "nor a hand-built track's");
        assertEquals(0, BoatHype.modelMs(null), "nor nothing's");
    }

    @Test
    void halfwayIsTheCheckpointNearestHalfTheDistanceAlongTheMarksFromCheckpoint1() {
        Course c = MountainRunsV2.road();
        List<Course.Mark> cps = c.checkpoints();
        List<Double> at = new ArrayList<>();
        double run = 0;
        for (int i = 0; i < cps.size(); i++) {
            run += i == 0 ? 0 : cps.get(i - 1).center().distance(cps.get(i).center());
            at.add(run);
        }
        double mid = (run + cps.get(cps.size() - 1).center().distance(c.finish().center())) / 2;
        int best = 0;
        for (int i = 0; i < at.size(); i++) {
            if (Math.abs(at.get(i) - mid) < Math.abs(at.get(best) - mid)) {
                best = i;
            }
        }
        assertEquals(best, BoatHype.halfway(c), "the checkpoint nearest half way along, from the first checkpoint");
        GenTag tag = MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000);
        assertEquals(5, BoatHype.halfway(MountainRunsV2.straight(10, tag)),
                "10 checkpoints 10 apart, finish 10 on: 100 from checkpoint 1, so half way is checkpoint 6 (index 5)");
        assertEquals(5, BoatHype.halfway(MountainRunsV2.straight(11, tag)),
                "11 checkpoints: the 6th and 7th are as near half of 110; the first of them");
        assertEquals(-1, BoatHype.halfway(MountainRuns.medium()), "an algo-3 run has no HALFWAY title");
        assertEquals(-1, BoatHype.halfway(MountainRunsV2.of(null)), "nor a hand-built track");
    }

    @Test
    void halfwayNeverReadsTheStartSoEveryGridSpotHearsItAtTheSameCheckpoint() {
        for (Course c : List.of(MountainRunsV2.road(), MountainRunsV2.straight(10,
                MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000)))) {
            int pole = BoatHype.halfway(c);
            Course.Spot s = c.start();
            for (int back = 2; back <= 48; back += 2) {
                // a race moves the start back to each racer's own grid spot (RaceTrack.raced), up to 48 blocks
                Course raced = c.withStart(new Course.Spot(s.x() + back, s.y(), s.z() - back / 2.0, s.yaw(), 0f));
                assertEquals(pole, BoatHype.halfway(raced), "audit M00: " + back + " back, the same checkpoint");
                assertEquals(BoatHype.HALFWAY, BoatHype.checkpointTitle(raced, pole), "with its title");
            }
        }
    }

    @Test
    void thePlannerAndTheRuntimeShareOneHalfwayRule() {
        Course c = MountainRunsV2.road();
        List<Point> centres = new ArrayList<>();
        for (Course.Mark m : c.checkpoints()) {
            centres.add(m.center());
        }
        assertEquals(BoatHype.halfway(c), BoatHype.halfway(centres, c.finish().center()),
                "BoatHype.halfway(course) is the shared rule on its marks (MountainPlanner places the sign with it)");
        assertEquals(0, BoatHype.halfway(List.of(new Point(0, 0, 0)), new Point(9, 0, 0)), "one checkpoint: it");
        assertEquals(-1, BoatHype.halfway(List.of(), new Point(9, 0, 0)), "none: -1");
        assertEquals(-1, BoatHype.halfway(null, new Point(9, 0, 0)), "nothing: -1");
    }

    @Test
    void aV2RunIsQuietExceptEveryTenthHalfwayAndTheFinalDrop() {
        Course c = MountainRunsV2.road();
        int half = BoatHype.halfway(c);
        int last = BoatHype.finalDrop(c);
        assertEquals(MountainRunsV2.CHECKPOINTS - 1, last, "fixture: the Final Drop is on the finish's own leg");
        List<Integer> loud = new ArrayList<>();
        for (int i = 0; i < c.checkpoints().size(); i++) {
            if (BoatHype.loud(c, i)) {
                loud.add(i);
            }
        }
        List<Integer> want = new ArrayList<>(List.of(9, 19, 29, 39, 49, 59, 69));
        if (!want.contains(half)) {
            want.add(half);
        }
        want.add(last);
        want.sort(null);
        assertEquals(want, loud, "every 10th checkpoint (10, 20, ... 70), the halfway one and the one before the"
                + " Final Drop; every other is quiet");
        assertEquals(BoatHype.FINAL_DROP, BoatHype.checkpointTitle(c, last), "Final drop! before the Final Drop");
        assertEquals(BoatHype.HALFWAY, BoatHype.checkpointTitle(c, half), "Halfway! at the halfway checkpoint");
        assertEquals("", BoatHype.checkpointTitle(c, 9), "the 10th shows the title with no big word");
        assertEquals("", BoatHype.checkpointTitle(c, 0), "and a quiet one has none");
    }

    @Test
    void theFinalDropWinsWhereItIsAlsoHalfway() {
        GenTag tag = MountainRunsV2.tag(MountainRunsV2.ROAD_SEED, 120_000);
        Course one = MountainRunsV2.straight(1, tag);
        Course.Mark f = one.finish();
        Course drop = one.withFinish(new Course.Mark(f.x(), f.y() - 2, f.z(), f.radius()));
        assertEquals(0, BoatHype.halfway(drop), "fixture: its one checkpoint is the halfway one");
        assertEquals(0, BoatHype.finalDrop(drop), "fixture: and the one before the Final Drop");
        assertEquals(BoatHype.FINAL_DROP, BoatHype.checkpointTitle(drop, 0), "the Final Drop's title wins");
        assertTrue(BoatHype.loud(drop, 0), "and it is loud");
    }

    @Test
    void theQuietLineIsTheCheckpointAndTheClock() {
        assertEquals("&aCheckpoint 37/74 &7· 1:12.4", BoatHype.quietBar(36, 74, "1:12.4"),
                "§12: the action bar's \"Checkpoint 37/74 · 1:12.4\"");
        assertTrue(BoatHype.quietBar(0, 1, "0:00.0").codePoints().allMatch(cp -> cp <= 0xFFFF),
                "nothing Bedrock can't draw");
    }

    @Test
    void everyOtherCourseIsLoudAtEveryCheckpointAsBefore() {
        Course v3 = MountainRuns.medium();
        for (int i = 0; i < v3.checkpoints().size(); i++) {
            assertTrue(BoatHype.loud(v3, i), "the algo-3 run shows every checkpoint's title: " + i);
        }
        assertEquals(BoatHype.FINAL_DROP, BoatHype.checkpointTitle(v3, MountainRuns.FINAL_DROP_AT),
                "with its Final drop! as before");
        assertEquals("", BoatHype.checkpointTitle(v3, 4), "and nothing at its middle (no Halfway! on the spiral)");
        Course hand = MountainRunsV2.of(null);
        assertTrue(BoatHype.loud(hand, 3) && BoatHype.loud(hand, 36), "a hand-built track is never quiet");
    }
}
