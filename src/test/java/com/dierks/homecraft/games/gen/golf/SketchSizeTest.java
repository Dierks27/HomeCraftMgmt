package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenRandom;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The size-aware {@link HoleTemplate.Sketch} (GOLF-V4-SPEC §3.6, §7): 200 Adventure Golf holes were
 * drawn and block-hashed ({@link SketchHash}) BEFORE the Sketch learnt its size, into
 * {@code src/test/resources/gen/v3/golf-sketch-200.txt}; drawn again now they are the same holes,
 * block for block, in the same order — so every algo-3 layout re-derives and re-proves as it always
 * did. Never capture that file again.
 */
class SketchSizeTest {

    private static List<String[]> fixture() throws IOException {
        try (InputStream in = SketchSizeTest.class.getResourceAsStream("/gen/v3/golf-sketch-200.txt")) {
            assertNotNull(in, "the 200-hole fixture is on the test class path");
            List<String[]> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(line.trim().split(" "));
                }
            }
            return out;
        }
    }

    @Test
    void theTwoHundredHolesDrawnBeforeTheRefactorAreDrawnBlockForBlockTheSameNow() throws IOException {
        List<String[]> rows = fixture();
        assertEquals(200, rows.size(), "the fixture holds 200 holes");
        List<String> changed = new ArrayList<>();
        for (String[] r : rows) {
            HoleTemplate t = HoleTemplate.valueOf(r[0]);
            char tier = r[1].charAt(0);
            long seed = Long.parseLong(r[2]);
            HoleLayout l = t.draw(new GenRandom(seed).fork("sketch"), tier, Integer.parseInt(r[3]),
                    Integer.parseInt(r[4]), Integer.parseInt(r[5]));
            if (!SketchHash.of(l).equals(r[6])) {
                changed.add(String.join(" ", r) + " -> " + SketchHash.of(l) + " (" + l.describe() + ")");
            }
        }
        assertEquals(List.of(), changed, "no v3 hole changes by a single block when the Sketch takes its size");
    }

    @Test
    void theFixtureCoversEveryTemplateInEveryTierItServes() throws IOException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] r : fixture()) {
            seen.add(r[0] + " " + r[1]);
        }
        for (HoleTemplate t : HoleTemplate.values()) {
            for (char tier : (t == HoleTemplate.SAFE_STRAIGHT ? "S" : "EMH").toCharArray()) {
                if (t == HoleTemplate.SAFE_STRAIGHT || t.fits(tier)) {
                    assertTrue(seen.contains(t + " " + tier), t + " " + tier + " is drawn by the fixture");
                }
            }
        }
    }

    @Test
    void aDefaultSketchIsAnAdventureGolfPlotAndABiggerOneTakesItsOwnSize() {
        HoleTemplate.Sketch v3 = new HoleTemplate.Sketch();
        assertEquals(HoleTemplate.PLOT_X, v3.sx, "a default sketch is 20 wide");
        assertEquals(HoleTemplate.PLOT_Z, v3.sz, "and 40 deep");
        HoleTemplate.Sketch v4 = new HoleTemplate.Sketch(PlotGrid.V4.plotX(), PlotGrid.V4.plotZ());
        v4.lane(2, 37, 2, 61, 0); // a lane a 20 x 40 sketch can't hold
        v4.tee(19, 3);
        v4.cup(19, 60);
        HoleLayout l = v4.render(HoleTemplate.STRAIGHT, false, 9000, 4100, 164, "wide");
        assertEquals(9000 + 1, l.laneMinX() - 1, "the lane's walls start at the plot's first column");
        assertEquals(9000 + 38, l.laneMaxX() + 1, "and end at its last but one");
        assertThrows(IllegalStateException.class, () -> new HoleTemplate.Sketch().lane(2, 25, 2, 10, 0),
                "a 20-wide sketch still refuses a lane past its edge");
        assertThrows(IllegalStateException.class, () -> v4.cup(39, 10), "and a 40-wide one past its own");
    }

    @Test
    void aMirroredWideSketchMirrorsAcrossItsOwnWidth() {
        HoleTemplate.Sketch s = new HoleTemplate.Sketch(40, 64);
        s.lane(2, 6, 2, 20, 0);
        s.tee(4, 3);
        s.cup(4, 18);
        HoleLayout plain = s.render(HoleTemplate.STRAIGHT, false, 9000, 4100, 164, "plain");
        HoleLayout mirrored = s.render(HoleTemplate.STRAIGHT, true, 9000, 4100, 164, "mirrored");
        assertEquals(9000 + 4, plain.teeX(), "the tee at local x 4");
        assertEquals(9000 + 39 - 4, mirrored.teeX(), "mirrored, at 39 - 4 of a 40-wide plot");
        assertEquals(plain.blocks().size(), mirrored.blocks().size(), "the same blocks, mirrored");
    }
}
