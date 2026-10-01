package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keep area's gap between plots ({@code keep.plot_gap}, LAYOUT-SPEC §1.5, §3.2): with no gap its
 * plots are exactly 0.35's (touching, so every kept course sees its neighbours); with 576 they stand
 * 720 x 912 apart, each 36 chunks from the next; the area spans the gaps; a negative gap is refused.
 */
class KeepAreaGapTest {

    @Test
    void noGapGivesExactly035sPlots() {
        KeepArea a = new KeepArea(4096, 128, 5376, 24, 0);
        assertEquals(new Box(4096, 128, 5376, 4239, 303, 5711), a.plot(1), "plot 1, as 0.35 had it");
        assertEquals(new Box(4240, 128, 5376, 4383, 303, 5711), a.plot(2), "plot 2 touches it along x");
        assertEquals(new Box(4096, 128, 5712, 4239, 303, 6047), a.plot(7), "plot 7 starts the next row, touching");
        assertEquals(new Box(4096, 128, 5376, 4959, 303, 6719), a.area(), "the whole area");
        assertEquals("x 4096..4959, y 128..303, z 5376..6719", a.describe(), "said as 0.35 said it");
        assertEquals(0, a.plot(1).gap(a.plot(2)), "neighbours touch");
    }

    @Test
    void aGapOf576SpreadsThePlots720By912() {
        KeepArea a = new KeepArea(1760, 128, 7296, 24, Sight.GAP);
        for (int n = 1; n <= 24; n++) {
            Box p = a.plot(n);
            assertEquals(1760 + ((n - 1) % 6) * 720, p.minX(), "plot " + n + "'s x");
            assertEquals(7296 + ((n - 1) / 6) * 912, p.minZ(), "plot " + n + "'s z");
            assertEquals(List.of(144, 176, 336), List.of(p.sizeX(), p.sizeY(), p.sizeZ()), "plot " + n + "'s size");
        }
        assertEquals(new Box(1760, 128, 7296, 1903, 303, 7631), a.plot(1), "plot 1 (LAYOUT-SPEC §1.5)");
        assertEquals(new Box(5360, 128, 10032, 5503, 303, 10367), a.plot(24), "plot 24");
        assertEquals(new Box(1760, 128, 7296, 5503, 303, 10367), a.area(), "the area spans the gaps");
        assertEquals(576, a.plot(1).gap(a.plot(2)), "576 between neighbours along x");
        assertEquals(576, a.plot(1).gap(a.plot(7)), "and along z");
        assertEquals(36, Sight.chunksApart(a.plot(1), Sight.REACH, a.plot(2)), "36 chunks: out of sight");
        assertTrue(a.describe().endsWith("plots 576 apart"), "said: " + a.describe());
        assertEquals(24, a.plots().size(), "every plot");
        assertEquals(a.plot(24), a.plots().get(23), "in order");
        Box built = a.build(8, Slots.SKY_RINGS);
        assertEquals(a.plot(8).minX() + KeepArea.MARGIN, built.minX(), "a course still stands 8 in");
        assertTrue(a.plot(8).contains(built), "inside its plot");
    }

    @Test
    void aPartRowAndOnePlotSpanOnlyWhatTheyHold() {
        assertEquals(new Box(0, 128, 0, 143, 303, 335), new KeepArea(0, 128, 0, 1, 576).area(), "one plot, no gap");
        assertEquals(new Box(0, 128, 0, 2 * 144 + 576 - 1, 303, 335), new KeepArea(0, 128, 0, 2, 576).area(),
                "two plots and the gap between");
        assertEquals(new Box(0, 128, 0, 6 * 144 + 5 * 576 - 1, 303, 2 * 336 + 576 - 1),
                new KeepArea(0, 128, 0, 7, 576).area(), "a second row of one");
    }

    @Test
    void theFourPartFormIsTheDefaultGapAndANegativeGapIsRefused() {
        assertEquals(KeepArea.DEFAULT_GAP, new KeepArea(0, 128, 0, 6).gap(), "the default gap");
        assertTrue(KeepArea.DEFAULT_GAP == KeepArea.LEGACY_GAP || KeepArea.DEFAULT_GAP == KeepArea.SIGHT_GAP,
                "one of the two");
        assertThrows(IllegalArgumentException.class, () -> new KeepArea(0, 128, 0, 6, -16), "never negative");
        assertThrows(IllegalArgumentException.class, () -> new KeepArea(0, 128, 0, 6, 576).plot(7), "plots are 1-6");
    }

    @Test
    void aHalfInTheAreasGapsStillCountsAsInTheArea() {
        KeepArea a = new KeepArea(0, 128, 0, 12, 576);
        Box inTheGap = Box.sized(144 + 100, 160, 0, 64, 48, 64); // between plots 1 and 2
        String p = a.problem(List.of(inTheGap));
        assertNotNull(p, "the area is one box, gaps and all: nothing of Fresh Courses' may stand in it");
        assertTrue(p.contains("on top of"), "said so: " + p);
        assertNull(a.problem(List.of(Box.sized(a.area().maxX() + 17, 160, 0, 64, 48, 64))), "16 outside is fine");
    }
}
