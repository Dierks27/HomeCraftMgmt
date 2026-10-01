package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.gui.games.daily.DailyText;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Time Trials' own Mountain Run hooks (COURSE-VARIETY-SPEC §5.2), the call sites {@link BoatHypeTest}
 * can't reach: the Courses tab's NAME fact ({@link TimeTrials#tileFact}, what {@code dailyFace} puts in
 * the NAME) and the big title at a checkpoint ({@link TimeTrials#checkpointBig}, what {@code reached}
 * shows). With no server, on the hand-made Medium run ({@link MountainRuns}), the frozen algo-2 loops
 * ({@link V2Fixtures}), the same marks as a hand-built track, and a Dropper; and once through a real
 * Courses tab ({@link TimeTrials#faces}, the real framework and database, no server: a tile's item needs
 * one, so its face is read).
 *
 * <p>Pinned here (the review found that taking either hook out left the whole suite green): the
 * Mountain Run's tile says "5 drops · " before its stars and its racers see "Final drop!" at cp 10, where
 * the finish comes next, and nowhere on a run whose last drop is far up the mountain;
 * a flat loop, a hand-built track and a parkour course read exactly as before; a Dropper still says its
 * levels.
 */
class MountainRunHooksTest {

    @Test
    void theMountainRunsTileNameSaysItsDropsBeforeItsStars() {
        assertEquals("5 drops · ★★☆", TimeTrials.tileFact(MountainRuns.medium(), 7, 2),
                "§5.2: the drops, then the stars, in the NAME for Bedrock");
        assertEquals("5 drops · no time this week", TimeTrials.tileFact(MountainRuns.medium(), 7, 0),
                "no time yet: the drops, then that there's no time in this set");
        assertEquals("5 drops · no time today", TimeTrials.tileFact(MountainRuns.medium(MountainRuns.tag(3, 1)), 1, 0),
                "a daily set's words");
        assertEquals("1 drop · ★☆☆", TimeTrials.tileFact(MountainRuns.oneDrop(), 7, 1), "one drop reads \"1 drop\"");
        assertEquals("&bIce Boat &7- 5 drops · ★★☆ &a(new this week)", DailyText.tabName(Slots.ICE_BOAT, "Ice Boat",
                        TimeTrials.tileFact(MountainRuns.medium(), 7, 2), true, 7),
                "the whole Courses-tab NAME as dailyFace builds it");
    }

    @Test
    void theCoursesTabOfARealScreenPutsTheDropsInTheMountainRunsName() throws Exception {
        GamesBench bench = new GamesBench(GamesBench.at(2026, 10, 2, 12, 0), List.of(TimeTrials.SPEC), "trials",
                TimeTrialsSettings.defaults());
        try {
            TimeTrials trials = (TimeTrials) bench.games().game("trials");
            Course run = MountainRuns.medium();
            Course loop = V2Fixtures.boat("medium").trial().course().withGen(V2Fixtures.boat("medium").tag());
            Player ava = bench.player("Ava");
            List<TimeTrials.Face> faces = trials.faces(ava, List.of(run, loop), null);
            assertEquals(2, faces.size(), "fixture: a tile for each");
            String mountain = faces.get(0).name();
            assertTrue(mountain.startsWith("&bIce Boat &7- 5 drops · no time this week "),
                    "the Mountain Run's Courses-tab NAME, as dailyFace builds it: " + mountain);
            String flat = faces.get(1).name();
            assertTrue(flat.startsWith("&bIce Boat &7- no time this week "), "an algo-2 loop's, as before: " + flat);
            assertFalse(flat.contains("drop"), "no drops on a flat loop: " + flat);
            assertEquals(0, bench.severe(), "nothing threw: " + bench.severeLines());
        } finally {
            bench.close();
        }
    }

    @Test
    void aFlatLoopAHandBuiltTrackAndAParkourCourseSayTheirStarsAlone() {
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            Course loop = f.trial().course().withGen(f.tag());
            assertEquals("★★☆", TimeTrials.tileFact(loop, 7, 2), f + ": an algo-2 loop's NAME is as it was");
            assertEquals("no time this week", TimeTrials.tileFact(loop, 7, 0), f + ": and with no time yet");
        }
        assertEquals("★★☆", TimeTrials.tileFact(MountainRuns.medium(null), 7, 2),
                "the same downhill marks, hand-built: nothing new");
        assertEquals("★★☆", TimeTrials.tileFact(MountainRuns.medium(MountainRuns.tag(2, 7)), 7, 2),
                "the same marks under the algo-2 planner's tag: nothing new");
        assertEquals("★★☆", TimeTrials.tileFact(DropperCourses.asParkour(), 7, 2), "a parkour course: its stars");
    }

    @Test
    void aDropperStillSaysItsLevels() {
        GenTag dropper = new GenTag(Slots.FRESH_DROPPER.id(), Slots.DROPPER, 1, 20_725, 0, 7L, 'A', "d0d0", 30_000,
                40_000, 50_000, List.of(), List.of(), 1_790_000_000_000L, 7);
        assertEquals("3 levels · ★★★", TimeTrials.tileFact(DropperCourses.hand(dropper), 7, 3),
                "EVENTS-DROPPER-SPEC §B.1.8: the levels, then the stars");
        assertEquals("3 levels · no time this week", TimeTrials.tileFact(DropperCourses.hand(dropper), 7, 0),
                "and with no time yet");
    }

    @Test
    void finalDropIsTheBigTitleAtTheCheckpointBeforeTheLastLip() {
        Course run = MountainRuns.medium();
        for (int i = 0; i < run.checkpoints().size(); i++) {
            assertEquals(i == MountainRuns.FINAL_DROP_AT ? "&aFinal drop!" : "", TimeTrials.checkpointBig(run, i),
                    "checkpoint " + (i + 1) + " of " + run.checkpoints().size()
                            + ": the big title only before the Final Drop (the small line has the count)");
        }
        assertEquals("", TimeTrials.checkpointBig(MountainRuns.oneDrop(), 0),
                "one Hop between cp 1 and cp 2, then cp 3 before the finish: the finish doesn't come next");
        for (Tier tier : List.of(Tier.EASY, Tier.HARD)) {
            Course far = MountainRuns.farFinalDrop(tier);
            for (int i = 0; i < far.checkpoints().size(); i++) {
                assertEquals("", TimeTrials.checkpointBig(far, i), tier + ", checkpoint " + (i + 1)
                        + ": a last drop far up the mountain (its sign says HOP!) has no big title");
            }
        }
    }

    @Test
    void everyOtherCheckpointAnnouncementIsAsItWas() {
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            Course loop = f.trial().course().withGen(f.tag());
            for (int i = 0; i < loop.checkpoints().size(); i++) {
                assertEquals("", TimeTrials.checkpointBig(loop, i), f + ": checkpoint " + (i + 1) + " has no big title");
            }
        }
        Course hand = MountainRuns.medium(null);
        for (int i = 0; i < hand.checkpoints().size(); i++) {
            assertEquals("", TimeTrials.checkpointBig(hand, i),
                    "a hand-built downhill track, checkpoint " + (i + 1) + ": as it always was");
        }
        assertEquals("", TimeTrials.checkpointBig(DropperCourses.hand(), 1), "a Dropper's ledge: nothing new");
        assertEquals("", TimeTrials.checkpointBig(MountainRuns.dropBeforeTheFirstCheckpoint(), 0),
                "a drop before the first checkpoint has no checkpoint to say it at");
    }
}
