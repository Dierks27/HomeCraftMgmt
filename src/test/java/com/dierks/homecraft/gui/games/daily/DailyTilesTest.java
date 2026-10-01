package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.dropper.DropMarks;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The count a Fresh time trial's tile puts in its NAME on the Fresh Courses screen
 * ({@link DailyTiles#trialCount}, what {@code DailyTiles.tile} hands {@link DailyText#slotName} as
 * {@code holes}): a Dropper's levels, the Ice Boat Mountain Run's drops (COURSE-VARIETY-SPEC §5.2),
 * nothing for anything else. {@code DailyTiles.tile} itself makes an ItemStack, which needs a server.
 *
 * <p>Pinned here (the review found that setting the count to 0 in {@code tile} left the whole suite
 * green): the Mountain Run's tile reads "Ice Boat - 5 drops · ★★☆"; the flat algo-2 loops and a
 * hand-built track on the boat slot read their stars alone, as before; a Dropper still says its levels.
 */
class DailyTilesTest {

    /** A three-level Dropper as Fresh Courses stores one (EVENTS-DROPPER-SPEC §B.1.7). */
    private static Course dropper() {
        GenTag tag = new GenTag(Slots.FRESH_DROPPER.id(), Slots.DROPPER, 1, 20_725, 0, 7L, 'A', "d0d0", 30_000, 40_000,
                50_000, List.of(), List.of(), 1_790_000_000_000L, 7);
        return new Course("fresh_dropper", TrialKind.DROPPER, "Dropper", Tier.MEDIUM, "games",
                new Course.Spot(10.5, 100, 10.5, 0f, 30f), List.of(DropMarks.pool(12.5, 68, 12.5, 11),
                DropMarks.ledge(22.5, 100, 10.5), DropMarks.pool(24.5, 60, 12.5, 7), DropMarks.ledge(34.5, 100, 10.5)),
                DropMarks.pool(36.5, 52, 12.5, 5), 44.0, 4, true, false, 1, tag);
    }

    @Test
    void theMountainRunsTileCountsItsDrops() {
        Course run = MountainRuns.medium();
        assertEquals(MountainRuns.DROPS, DailyTiles.trialCount(Slots.ICE_BOAT, run), "§5.2: the Medium run's 5 drops");
        assertEquals(1, DailyTiles.trialCount(Slots.ICE_BOAT, MountainRuns.oneDrop()), "one Hop: 1 drop");
        assertEquals("&bIce Boat &7- 5 drops · ★★☆ &8· &7Course code BOAT-7",
                DailyTiles.name(Slots.ICE_BOAT, 7, true, 2, DailyTiles.trialCount(Slots.ICE_BOAT, run), 0, "BOAT-7"),
                "the Fresh Courses tile's NAME as tile() builds it: the drops, the stars, the code (Bedrock)");
        assertEquals("&bIce Boat &7- 5 drops", DailyTiles.name(Slots.ICE_BOAT, 7, true, 0,
                DailyTiles.trialCount(Slots.ICE_BOAT, run), 0, null), "no stars yet: the drops alone");
    }

    @Test
    void aFlatLoopOrAHandBuiltTrackOnTheBoatSlotSaysItsStarsAlone() {
        for (V2Fixtures.Fixture f : V2Fixtures.boats()) {
            Course loop = f.trial().course().withGen(f.tag());
            assertEquals(0, DailyTiles.trialCount(f.slot(), loop), f + ": an algo-2 loop has no drops to count");
            String name = DailyTiles.name(f.slot(), 7, true, 2, DailyTiles.trialCount(f.slot(), loop), 0, null);
            assertEquals("&bIce Boat &7- ★★☆", name, f + ": its NAME is exactly as before");
            assertFalse(name.contains("drop"), f + ": no drops in it");
        }
        assertEquals(0, DailyTiles.trialCount(Slots.ICE_BOAT, MountainRuns.medium(null)),
                "the same downhill marks with no tag: not a Mountain Run");
        assertEquals(0, DailyTiles.trialCount(Slots.ICE_BOAT, MountainRuns.medium(MountainRuns.tag(2, 7))),
                "the same marks under the algo-2 planner: not one either");
    }

    @Test
    void aDropperCountsItsLevelsAndAParkourCourseNothing() {
        assertEquals(3, DailyTiles.trialCount(Slots.FRESH_DROPPER, dropper()), "EVENTS-DROPPER-SPEC §B.1.8: 3 levels");
        assertEquals("&9Dropper &7- 3 levels · ★★☆", DailyTiles.name(Slots.FRESH_DROPPER, 7, true, 2,
                DailyTiles.trialCount(Slots.FRESH_DROPPER, dropper()), 0, null), "a Dropper's NAME, as before");
        Course parkour = MountainRuns.medium(null);
        assertEquals(0, DailyTiles.trialCount(Slots.DAILY_PARKOUR_EASY, parkour), "a parkour slot counts nothing");
    }

    @Test
    void nothingThrowsWithoutASlotOrACourse() {
        assertEquals(0, DailyTiles.trialCount(Slots.ICE_BOAT, null), "no course yet: 0");
        assertEquals(0, DailyTiles.trialCount(null, MountainRuns.medium()), "no slot: 0");
    }
}
