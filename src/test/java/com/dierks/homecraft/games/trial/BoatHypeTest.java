package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.games.daily.DailyText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Ice Boat Mountain Run says about itself (COURSE-VARIETY-SPEC §5.2, §8), with no server, on
 * the hand-made Medium run ({@link MountainRuns}) and the frozen algo-2 loops ({@link V2Fixtures}).
 *
 * <p>Pinned here: the drops are the legs that end lower (a Big Drop is one drop, like a Hop); only a
 * Fresh Ice Boat layout of algo 3 or later is a Mountain Run, so the flat loops, kept courses and
 * hand-built tracks say nothing new; the tile fact reads "5 drops · " like a Dropper's levels; the hype
 * line follows the set's cadence and the {@code hype} switch, and says "1 drop" for one; "Final drop!"
 * is heard at the checkpoint before the last lip, and nowhere else; a race from the grid keeps it all;
 * every line is kid-safe and Bedrock can draw it; and nothing here throws on a half-made course.
 */
class BoatHypeTest {

    @Test
    void theDropsAreTheLegsThatEndLowerABigDropCountingOnce() {
        Course run = MountainRuns.medium();
        assertEquals(MountainRuns.DROPS, BoatHype.drops(run),
                "Hop, Big Drop, Hop, Hop and the Final Drop: five legs end lower, two of them by 2 blocks");
        assertEquals(7.0, MountainRuns.TOP - run.finish().y(), "fixture: 7 blocks down in all (§2.4 Medium)");
        assertEquals(1, BoatHype.drops(MountainRuns.oneDrop()), "one Hop is one drop");
        assertEquals(0, BoatHype.drops(MountainRuns.flat(MountainRuns.tag())), "a flat track has none");
        List<Course.Mark> small = new ArrayList<>(MountainRuns.CHECKPOINTS);
        Course.Mark m = small.get(2);
        small.set(2, new Course.Mark(m.x(), m.y() + 0.9, m.z(), m.radius())); // a bump under a whole block
        assertEquals(MountainRuns.DROPS, BoatHype.drops(run.withCheckpoints(small)),
                "a mark that sits a little high is not a drop of its own, and the next leg down still counts once");
    }

    @Test
    void onlyAFreshIceBoatLayoutOfAlgo3OnIsAMountainRun() {
        assertTrue(BoatHype.mountain(MountainRuns.medium()), "the Medium run, algo 3");
        assertTrue(BoatHype.mountain(MountainRuns.medium(MountainRuns.tag(4, 7))), "a later boat planner too");
        assertFalse(BoatHype.mountain(MountainRuns.medium(MountainRuns.tag(2, 7))), "the algo-2 loop planner's isn't");
        assertFalse(BoatHype.mountain(MountainRuns.medium(null)), "a hand-built track (no tag) isn't");
        GenTag parkour = new GenTag("fresh_parkour_easy", "parkour", 3, 20_725, 0, 1L, 'A', "abc", 0, 0, 0,
                List.of(), List.of(), 0L, 7);
        assertFalse(BoatHype.mountain(MountainRuns.medium(parkour)), "another generator's tag isn't");
        Course asParkour = new Course("x", TrialKind.PARKOUR, "X", Tier.EASY, "games", MountainRuns.START,
                MountainRuns.CHECKPOINTS, MountainRuns.FINISH, null, null, true, false, 1, MountainRuns.tag());
        assertFalse(BoatHype.mountain(asParkour), "and only a boat course is one");
        assertEquals(3, BoatHype.FIRST_ALGO, "§0.5: BoatPlanner.ALGO goes 2 -> 3 for the Mountain Run");
    }

    @Test
    void theFrozenAlgo2LoopsSayNothingNew() {
        assertEquals(3, V2Fixtures.boats().size(), "fixture: one loop a tier");
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            Course loop = f.trial().course().withGen(f.tag());
            assertEquals(2, f.tag().algo(), f + ": fixture is an algo-2 layout");
            assertFalse(BoatHype.mountain(loop), f + ": a flat loop is not a Mountain Run");
            assertEquals(0, BoatHype.drops(loop), f + ": and has no drops");
            assertEquals("", BoatHype.fact(loop), f + ": its tile NAME is as it was");
            assertNull(BoatHype.line(loop, true), f + ": no hype line, even with hype on");
            assertEquals(-1, BoatHype.finalDrop(loop), f + ": and no Final Drop title");
            for (int i = 0; i < loop.checkpoints().size(); i++) {
                assertEquals("", BoatHype.checkpointTitle(loop, i), f + ": checkpoint " + i + " is as it was");
            }
        }
    }

    @Test
    void theTileFactGoesBeforeTheStarsLikeADroppersLevels() {
        assertEquals("5 drops · ", BoatHype.fact(MountainRuns.medium()), "§5.2: \"5 drops · \"");
        assertEquals("1 drop · ", BoatHype.fact(MountainRuns.oneDrop()), "one drop reads \"1 drop\"");
        assertEquals("", BoatHype.fact(MountainRuns.medium(null)), "a hand-built track: nothing new");
        assertEquals("", BoatHype.fact(MountainRuns.flat(MountainRuns.tag())), "no drops: nothing to say");
        assertEquals("", BoatHype.fact(MountainRuns.medium(MountainRuns.tag(2, 7))), "an algo-2 layout: nothing");
        assertEquals("&bIce Boat &7- 5 drops · ★★☆ &a(new this week)", DailyText.tabName(Slots.ICE_BOAT, "Ice Boat",
                        BoatHype.fact(MountainRuns.medium()) + DailyText.trialFact(7, 2), true, 7),
                "the Courses tab's NAME as Time Trials builds it (the fact, then the stars), all in the NAME for Bedrock");
    }

    @Test
    void theHypeLineFollowsTheCadenceAndTheSwitch() {
        assertEquals("&bThis week: 5 drops down the mountain!", BoatHype.line(MountainRuns.medium(), true),
                "§8: the heads-up tail on a weekly set");
        assertEquals("&bToday: 5 drops down the mountain!",
                BoatHype.line(MountainRuns.medium(MountainRuns.tag(3, 1)), true), "a daily set says today");
        assertEquals("&bOn this course: 5 drops down the mountain!",
                BoatHype.line(MountainRuns.medium(MountainRuns.tag(3, 3)), true), "any other cadence: its own words");
        assertEquals("&bThis week: 1 drop down the mountain!", BoatHype.line(MountainRuns.oneDrop(), true),
                "§8: 1 -> \"1 drop\"");
        assertNull(BoatHype.line(MountainRuns.medium(), false), "games.race_night.hype: false says nothing");
        assertNull(BoatHype.line(MountainRuns.medium(null), true), "a hand-built track says nothing");
        assertNull(BoatHype.line(MountainRuns.flat(MountainRuns.tag()), true), "nor does a track with no drops");
    }

    @Test
    void finalDropIsHeardAtTheCheckpointBeforeTheLastLipAndNowhereElse() {
        Course run = MountainRuns.medium();
        assertEquals(MountainRuns.FINAL_DROP_AT, BoatHype.finalDrop(run),
                "the last checkpoint, cp 10: the Final Drop is on its leg to the finish");
        for (int i = 0; i < run.checkpoints().size(); i++) {
            assertEquals(i == MountainRuns.FINAL_DROP_AT ? "&aFinal drop!" : "", BoatHype.checkpointTitle(run, i),
                    "checkpoint " + (i + 1) + ": the title only before the Final Drop");
        }
        assertEquals("", BoatHype.checkpointTitle(run, -1), "no checkpoint: no title");
        assertEquals(0, BoatHype.finalDrop(MountainRuns.oneDrop()), "one Hop between cp 1 and cp 2: heard at cp 1");
        assertEquals(-1, BoatHype.finalDrop(MountainRuns.dropBeforeTheFirstCheckpoint()),
                "a drop before the first checkpoint has no checkpoint before it");
        assertEquals(-1, BoatHype.finalDrop(MountainRuns.medium(null)), "a hand-built downhill track: nothing new");
        assertEquals(-1, BoatHype.finalDrop(MountainRuns.flat(MountainRuns.tag())), "no drop: no title");
    }

    @Test
    void aRaceFromTheGridIsTheSameSprintWithTheSameDrops() {
        Course run = MountainRuns.medium();
        assertFalse(Laps.loop(run), "§5.1: a sprint's finish is far from its start, so it's not a loop");
        assertEquals(1, Laps.natural(run), "and it is raced once");
        Course.Spot grid = new Course.Spot(MountainRuns.START.x() - 12, MountainRuns.TOP, MountainRuns.START.z() + 1.5,
                -90f, 0f); // row 3 of the grid, back along the pit
        Course raced = Laps.raced(run, grid, 0).course();
        assertEquals(MountainRuns.DROPS, BoatHype.drops(raced), "the grid is on the pit's level: the same drops");
        assertEquals(MountainRuns.FINAL_DROP_AT, BoatHype.finalDrop(raced), "and the same Final Drop checkpoint");
        assertTrue(BoatHype.mountain(raced), "the raced course keeps its tag");
    }

    @Test
    void everyLineIsKidSafeAndBedrockCanDrawIt() {
        List<String> lines = new ArrayList<>(List.of(BoatHype.FINAL_DROP, BoatHype.fact(MountainRuns.medium()),
                BoatHype.fact(MountainRuns.oneDrop()), TrialText.drops(1), TrialText.drops(5)));
        for (int cadence : new int[]{1, 2, 3, 7, 14, 28}) {
            lines.add(BoatHype.line(MountainRuns.medium(MountainRuns.tag(3, cadence)), true));
        }
        lines.add(BoatHype.line(MountainRuns.oneDrop(), true));
        for (String line : lines) {
            assertTrue(GenCopy.copyProblems(line).isEmpty(), "kid-safe, drawable: " + GenCopy.copyProblems(line));
            assertFalse(line.toLowerCase(Locale.ROOT).contains("try again"), "no near-miss words: " + line);
        }
    }

    @Test
    void nothingThrowsOnAHalfMadeCourse() {
        assertFalse(BoatHype.mountain(null), "no course: not a Mountain Run");
        assertEquals(0, BoatHype.drops(null), "no course: no drops");
        assertEquals("", BoatHype.fact(null), "no course: no fact");
        assertNull(BoatHype.line(null, true), "no course: no hype");
        assertEquals(-1, BoatHype.finalDrop(null), "no course: no Final Drop");
        assertEquals("", BoatHype.checkpointTitle(null, 0), "no course: no title");
        Course bare = new Course("fresh_boat", TrialKind.BOAT, "Ice Boat", Tier.MEDIUM, "games", null, List.of(), null,
                null, null, false, false, 1, MountainRuns.tag());
        assertEquals(0, BoatHype.drops(bare), "no start yet: no drops");
        assertEquals(-1, BoatHype.finalDrop(bare), "and no Final Drop");
        assertNull(BoatHype.line(bare, true), "and no hype");
    }
}
