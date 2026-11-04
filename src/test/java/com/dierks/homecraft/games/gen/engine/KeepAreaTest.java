package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keep area's plots (GEN-SPEC-KEEP §4): every plot fits the largest generator footprint plus 16,
 * plots don't overlap, a course stands 8 in from its plot's edges, and the shipped area is well apart
 * from every generator region while one that overlaps or comes within 16 blocks of one is refused.
 */
class KeepAreaTest {

    /** Every shipped slot's and Classic's halves, each at its shipped origin and gap. */
    private static List<Box> halves() {
        List<Box> out = new ArrayList<>();
        DailySettings d = DailySettings.defaults();
        for (DailySettings.SlotConfig c : d.slots()) {
            out.addAll(Regions.halves(c));
        }
        for (DailySettings.SlotConfig c : d.archive().classics()) {
            out.addAll(Regions.halves(c));
        }
        return out;
    }

    @Test
    void plotsFitTheLargestCoursePlusSixteenAndNeverOverlap() {
        KeepArea a = new KeepArea(4096, 128, 5376, 24);
        assertEquals(128 + 16, KeepArea.PLOT_X, "an older Sky Rings' 128 wide plus 16 (pinned)");
        assertEquals(320 + 16, KeepArea.PLOT_Z, "its 320 long plus 16");
        assertEquals(176, KeepArea.PLOT_Y, "and its height");
        for (int n = 1; n <= a.maxPlots(); n++) {
            Box p = a.plot(n);
            assertTrue(a.area().contains(p), "plot " + n + " is inside the area");
            for (Slots.Def d : Slots.ALL) {
                Box b = a.build(n, d);
                assertEquals(p.minX() + KeepArea.MARGIN, b.minX(), "8 in from the plot's edge");
                if (d == Slots.ICE_BOAT || d == Slots.SKY_RINGS) {
                    assertFalse(p.contains(b) || KeepArea.fits(b), d.id() + ": the Mountain Run v2's 480 x 176 x 640"
                            + " and Sky Rings v3's 512 x 176 x 512 are bigger than a plot: they can't be kept (they"
                            + " stay in the archive)");
                    continue;
                }
                assertTrue(p.contains(b) && KeepArea.fits(b), d.id() + " fits plot " + n);
            }
            for (int m = n + 1; m <= a.maxPlots(); m++) {
                assertTrue(p.gap(a.plot(m)) >= 0, "plots " + n + " and " + m + " don't overlap");
            }
        }
        int[] old = LegacyBoxes.size(Slots.SKY_RINGS); // the largest course a plot holds: an older Sky Rings one
        Box rings1 = Box.sized(a.plot(1).minX() + KeepArea.MARGIN, 128, a.plot(1).minZ() + KeepArea.MARGIN, old[0],
                old[1], old[2]);
        Box rings2 = Box.sized(a.plot(2).minX() + KeepArea.MARGIN, 128, a.plot(2).minZ() + KeepArea.MARGIN, old[0],
                old[1], old[2]);
        assertTrue(KeepArea.fits(rings1) && a.plot(1).contains(rings1), "an older Sky Rings course fits a plot");
        assertTrue(rings1.gap(rings2) >= 16, "two kept courses side by side are 16 apart");
        assertThrows(IllegalArgumentException.class, () -> a.plot(25), "there is no plot 25");
        assertThrows(IllegalArgumentException.class, () -> a.plot(0), "nor plot 0");
    }

    @Test
    void theShippedAreaIsApartAndAnOverlappingOneIsRefused() {
        assertNull(DailySettings.defaults().archive().keep().problem(halves()),
                "the shipped area is clear of every half");
        int[] easy = Slots.DAILY_PARKOUR_EASY.origin();
        String on = new KeepArea(easy[0], 128, easy[2], 24).problem(halves());
        assertNotNull(on, "an area on top of the parkour halves is refused");
        assertTrue(on.contains("on top of"), on);
        Box last = halves().stream().max(java.util.Comparator.comparingInt(Box::maxZ)).orElseThrow();
        String near = new KeepArea(last.minX(), 128, last.maxZ() + 10, 24).problem(halves());
        assertNotNull(near, "one only 9 blocks past the southernmost half is refused (it must be 16 away)");
        assertNull(new KeepArea(last.minX(), 128, last.maxZ() + 17, 24).problem(halves()), "16 away is fine");
        assertNotNull(new KeepArea(4096, 200, 5376, 24).problem(halves()), "too high for the tallest course");
        assertNotNull(new KeepArea(29_000_000, 128, 5376, 24).problem(halves()), "past the world's edge");
    }
}
