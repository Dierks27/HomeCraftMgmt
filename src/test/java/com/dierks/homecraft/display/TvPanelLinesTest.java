package com.dierks.homecraft.display;

import com.dierks.homecraft.gui.MarketLabels;
import com.dierks.homecraft.market.sim.Badge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A TV price panel's lines under the live market (spec §7.1, the "TV line 5" column and the
 * "none, |pct| ≥ 3" row): the badge on line 5; for an unbadged item 3% or more off usual an empty
 * line 5 and "Usually $X" on line 6; nothing otherwise, so a quiet item's panel is the four lines
 * it always was.
 */
class TvPanelLinesTest {

    /** The panel's four fixed lines, as {@code DisplayService.panelText} writes them. */
    private static final String BASE = "&f&lWheat\n&e&l$3.46\n&a▲ 2.00%\n&7Stock: &f400";

    @Test
    void usuallyIsLineSixUnderAnEmptyLineFive() {
        String[] lines = (BASE + DisplayService.tvMoodLines(Badge.NONE, false, 4.2, "$3.30")).split("\n", -1);
        assertEquals(6, lines.length);
        assertEquals("", lines[4]);
        assertEquals("&7Usually &f$3.30", lines[5]);
        assertEquals(MarketLabels.usually("$3.30"), lines[5]);
        // A fall counts the same way.
        assertEquals("\n\n&7Usually &f$3.30", DisplayService.tvMoodLines(Badge.NONE, false, -3.0, "$3.30"));
    }

    @Test
    void aBadgeIsLineFiveAndNothingFollowsIt() {
        for (Badge b : Badge.values()) {
            if (b == Badge.NONE) {
                continue;
            }
            for (boolean fading : new boolean[] {false, true}) {
                String[] lines = (BASE + DisplayService.tvMoodLines(b, fading, 12.0, "$3.30")).split("\n", -1);
                assertEquals(5, lines.length, b + " " + fading);
                assertEquals(MarketLabels.tvLine(b, fading), lines[4]);
            }
        }
    }

    @Test
    void aQuietItemKeepsItsFourLines() {
        assertEquals("", DisplayService.tvMoodLines(Badge.NONE, false, 2.9, "$3.30"));
        assertEquals("", DisplayService.tvMoodLines(Badge.NONE, false, -2.9, "$3.30"));
        assertEquals("", DisplayService.tvMoodLines(Badge.NONE, false, Double.NaN, "$3.30"));
        assertEquals("", DisplayService.tvMoodLines(null, false, 0.0, "$3.30"));
    }
}
