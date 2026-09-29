package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static List<Box> halves() {
        List<Box> out = new ArrayList<>();
        for (Slots.Def d : Slots.ALL) {
            out.addAll(Regions.halves(d, d.origin()));
        }
        for (Slots.Def d : Slots.CLASSICS) {
            out.addAll(Regions.halves(d, d.origin()));
        }
        return out;
    }

    @Test
    void plotsFitTheLargestCoursePlusSixteenAndNeverOverlap() {
        KeepArea a = new KeepArea(4096, 128, 5376, 24);
        assertEquals(128 + 16, KeepArea.PLOT_X, "Sky Rings' 128 wide plus 16");
        assertEquals(320 + 16, KeepArea.PLOT_Z, "Sky Rings' 320 long plus 16");
        assertEquals(176, KeepArea.PLOT_Y, "and Sky Rings' height");
        for (int n = 1; n <= a.maxPlots(); n++) {
            Box p = a.plot(n);
            assertTrue(a.area().contains(p), "plot " + n + " is inside the area");
            for (Slots.Def d : Slots.ALL) {
                Box b = a.build(n, d);
                assertTrue(p.contains(b), d.id() + " fits plot " + n);
                assertEquals(p.minX() + KeepArea.MARGIN, b.minX(), "8 in from the plot's edge");
            }
            for (int m = n + 1; m <= a.maxPlots(); m++) {
                assertTrue(p.gap(a.plot(m)) >= 0, "plots " + n + " and " + m + " don't overlap");
            }
        }
        Box rings1 = a.build(1, Slots.SKY_RINGS);
        Box rings2 = a.build(2, Slots.SKY_RINGS);
        assertTrue(rings1.gap(rings2) >= 16, "two kept courses side by side are 16 apart");
        assertThrows(IllegalArgumentException.class, () -> a.plot(25), "there is no plot 25");
        assertThrows(IllegalArgumentException.class, () -> a.plot(0), "nor plot 0");
    }

    @Test
    void theShippedAreaIsApartAndAnOverlappingOneIsRefused() {
        assertNull(new KeepArea(4096, 128, 5376, 24).problem(halves()), "the shipped area is clear of every half");
        String on = new KeepArea(4096, 128, 4096, 24).problem(halves());
        assertNotNull(on, "an area on top of the parkour halves is refused");
        assertTrue(on.contains("on top of"), on);
        Box last = halves().stream().max(java.util.Comparator.comparingInt(Box::maxZ)).orElseThrow();
        String near = new KeepArea(4096, 128, last.maxZ() + 10, 24).problem(halves());
        assertNotNull(near, "one only 9 blocks from a half is refused (it must be 16 away)");
        assertNull(new KeepArea(4096, 128, last.maxZ() + 17, 24).problem(halves()), "16 away is fine");
        assertNotNull(new KeepArea(4096, 200, 5376, 24).problem(halves()), "too high for the tallest course");
        assertNotNull(new KeepArea(29_000_000, 128, 5376, 24).problem(halves()), "past the world's edge");
    }
}
