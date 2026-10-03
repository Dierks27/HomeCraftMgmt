package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import com.dierks.homecraft.games.trial.MountainRuns;
import com.dierks.homecraft.games.trial.MountainRunsV2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Ice Boat tile's NAME on the Fresh Courses screen for a Mountain Run v2 (MOUNTAIN-V2-SPEC §12), pure.
 *
 * <p>Pinned here: a v2 run names its style before its drops ("Ice Boat - Winding Road · 27 drops · ★★☆",
 * MA's {@code GenCopy.boatV2Tile}), with no trailing dot when there are no stars yet; the style is the run's
 * own (read off its seed, as the tile's caller passes it); the algo-3 run and every other slot read as before.
 */
class FreshBoatTileTest {

    @Test
    void aV2RunsTileNamesItsStyleBeforeItsDrops() {
        assertEquals("&bIce Boat &7- Winding Road · 27 drops · ★★☆",
                DailyTiles.name(Slots.ICE_BOAT, 7, true, 2, 27, 0, null, BoatStyle.ROAD), "a Winding Road with 2 stars");
        assertEquals("&bIce Boat &7- Slalom · 14 drops", DailyTiles.name(Slots.ICE_BOAT, 7, true, 0, 14, 0, null,
                BoatStyle.SLALOM), "a Slalom with no time yet: no trailing dot");
        assertEquals("&bIce Boat &7- Winding Road · 1 drop · ★☆☆",
                DailyTiles.name(Slots.ICE_BOAT, 7, true, 1, 1, 0, null, BoatStyle.ROAD), "one drop");
    }

    @Test
    void theStyleIsTheRunsOwnAndEveryOtherTileReadsAsBefore() {
        assertEquals(1, DailyTiles.trialCount(Slots.ICE_BOAT, MountainRunsV2.road()) / MountainRunsV2.DROPS,
                "fixture: the tile counts a v2 run's drops");
        assertEquals("&bIce Boat &7- 5 drops · ★★☆", DailyTiles.name(Slots.ICE_BOAT, 7, true, 2,
                DailyTiles.trialCount(Slots.ICE_BOAT, MountainRuns.medium()), 0, null), "the algo-3 run as before");
        assertEquals("&bIce Boat &7- 5 drops · ★★☆", DailyTiles.name(Slots.ICE_BOAT, 7, true, 2, 5, 0, null, null),
                "no style: as before");
        assertEquals("&aEasy Parkour &7- ★★☆", DailyTiles.name(Slots.DAILY_PARKOUR_EASY, 7, true, 2, 0, 0, null,
                BoatStyle.ROAD), "a style never shows on another course");
    }
}
