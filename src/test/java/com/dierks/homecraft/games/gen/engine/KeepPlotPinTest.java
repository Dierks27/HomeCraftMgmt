package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kept-course plots are pinned at 144 x 176 x 336 (GOLF-V4-SPEC §4.1, MOUNTAIN-V2-SPEC §10.3 item 3): a
 * generator that grows (the Mountain Run v2's 480 x 176 x 640) never moves a kept course, and a course whose
 * half doesn't fit a plot can't be kept ("Mountain Run v2 courses are too big to keep; they stay in the
 * archive"). Golf v4's 128 x 16 x 224, and every older course, still fit.
 */
class KeepPlotPinTest {

    @Test
    void thePlotSizeIsPinnedWhateverTheSlotsAreNow() {
        assertEquals(144, KeepArea.PLOT_X, "Sky Rings' 128 + 16, as in 0.35 and 0.36");
        assertEquals(176, KeepArea.PLOT_Y, "its height");
        assertEquals(336, KeepArea.PLOT_Z, "its 320 + 16");
        int widest = 0;
        List<Slots.Def> all = new ArrayList<>(Slots.ALL);
        all.addAll(Slots.CLASSICS);
        for (Slots.Def d : all) {
            widest = Math.max(widest, d.sizeX());
        }
        assertTrue(widest + 2 * KeepArea.MARGIN > KeepArea.PLOT_X, "(a plot computed from today's slots would have"
                + " grown: that is why it is pinned)");
        assertEquals(Box.sized(1760 + 720, 128, 7296, 144, 176, 336), new KeepArea(1760, 128, 7296, 24).plot(2),
                "plot 2 stands exactly where 0.36 put it");
    }

    @Test
    void aHalfFitsAPlotUpTo128By176By320() {
        assertTrue(KeepArea.fits(128, 176, 320), "the largest that fits");
        assertFalse(KeepArea.fits(129, 16, 64), "one wider doesn't");
        assertFalse(KeepArea.fits(64, 177, 64), "nor one taller");
        assertFalse(KeepArea.fits(64, 16, 321), "nor one longer");
        assertTrue(KeepArea.fits(Slots.DAILY_GOLF.half('A')), "Golf v4's 128 x 16 x 224 fits");
        assertTrue(KeepArea.fits(LegacyBoxes.half(Slots.ICE_BOAT, 'A')), "an older Mountain Run's 128 x 16 x 128 fits");
        assertTrue(KeepArea.fits(LegacyBoxes.half(Slots.DAILY_GOLF, 'A')), "and an older golf course");
        assertFalse(KeepArea.fits(Slots.ICE_BOAT.half('A')), "the Mountain Run v2's 480 x 176 x 640 doesn't");
        assertFalse(KeepArea.fits(null), "no half: nothing to keep");
    }

    @Test
    void keepingACourseThatDoesntFitSaysWhy() {
        assertEquals("Mountain Run v2 courses are too big to keep; they stay in the archive",
                KeepService.tooBig(Slots.ICE_BOAT, Slots.ICE_BOAT.half('A')), "the owner's words for the boat");
        assertEquals("it doesn't fit a plot (its area is 256 x 16 x 224; a plot holds 128 x 176 x 320)",
                KeepService.tooBig(Slots.DAILY_GOLF, Box.sized(0, 0, 0, 256, 16, 224)), "any other: with the sizes");
    }
}
