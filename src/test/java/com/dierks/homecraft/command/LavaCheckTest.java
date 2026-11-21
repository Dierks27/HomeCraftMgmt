package com.dierks.homecraft.command;

import com.dierks.homecraft.command.ArenaCheck.Claim;
import com.dierks.homecraft.command.GamesCheck.Line;
import com.dierks.homecraft.command.GamesCheck.Status;
import com.dierks.homecraft.command.LavaCheck.Facts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Floor Is Lava's rows in {@code /hcm games check}: off is one OK line; a box that fits, claimed and built, with a
 * cabinet place in the Arcade Room, is one OK line; no world, a world not loaded, a box too close to something, a
 * claim at another place or unreadable, a playroom that closed itself, and no cabinet in the Clubhouse each say what
 * is wrong and how to fix it.
 */
class LavaCheckTest {

    private static final String BOX = "x 7312..7343, y 160..175, z 8544..8575";

    private static Facts good() {
        return new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, null, true, true, true);
    }

    private static List<Line> rows(Facts f) {
        List<Line> out = new ArrayList<>();
        LavaCheck.rows(f, out);
        return out;
    }

    private static Line one(Facts f) {
        List<Line> out = rows(f);
        assertEquals(1, out.size(), "one line: " + out);
        return out.get(0);
    }

    @Test
    void offIsOneOkLineAndNothingElse() {
        Line l = one(null);
        assertEquals(Status.OK, l.status());
        assertTrue(l.what().contains("The Floor Is Lava is off"), l.what());
        assertEquals(Status.OK, one(new Facts(false, "", false, BOX, List.of("x"), Claim.UNKNOWN, "y", false, false,
                false)).status(), "switched off: nothing else is checked");
    }

    @Test
    void aBoxThatFitsClaimedAndBuiltIsOneOkLine() {
        Line l = one(good());
        assertEquals(Status.OK, l.status());
        assertEquals("The Floor Is Lava: box fits (" + BOX + "), claimed, playroom ready", l.what());
        assertEquals("The Floor Is Lava: box fits (" + BOX + "), empty (claimed at its first build), playroom being"
                + " built", one(new Facts(true, "games", true, BOX, List.of(), Claim.UNCLAIMED, null, false, true,
                true)).what());
    }

    @Test
    void noWorldOrAWorldNotLoadedFails() {
        Line none = one(new Facts(true, "", false, BOX, List.of(), Claim.CLAIMED, null, false, true, true));
        assertEquals(Status.FAIL, none.status());
        assertTrue(none.fix().contains("games.worlds"), none.fix());
        Line gone = one(new Facts(true, "games", false, BOX, List.of(), Claim.CLAIMED, null, false, true, true));
        assertEquals(Status.FAIL, gone.status());
        assertTrue(gone.what().contains("'games' isn't loaded"), gone.what());
    }

    @Test
    void aBoxTooCloseToSomethingFailsWithHowToMoveIt() {
        List<Line> out = rows(new Facts(true, "games", true, BOX, List.of("only 8 blocks from clubhouse"),
                Claim.UNCLAIMED, "its box can't be used", false, true, true));
        assertEquals(1, out.size(), "the closing reason repeats the problem: said once " + out);
        assertEquals(Status.FAIL, out.get(0).status());
        assertTrue(out.get(0).what().contains("only 8 blocks from clubhouse"), out.get(0).what());
        assertTrue(out.get(0).fix().contains("games.floor_is_lava.origin"), out.get(0).fix());
    }

    @Test
    void aClaimElsewhereOrUnreadableWarns() {
        List<Line> moved = rows(new Facts(true, "games", true, BOX, List.of(), Claim.MOVED, null, true, true, true));
        assertEquals(Status.WARN, moved.get(0).status());
        assertTrue(moved.get(0).what().contains("claimed at another place"), moved.get(0).what());
        List<Line> unknown = rows(new Facts(true, "games", true, BOX, List.of(), Claim.UNKNOWN, null, true, true,
                true));
        assertEquals(Status.WARN, unknown.get(0).status());
    }

    @Test
    void aClosedPlayroomFailsAndSaysHowToOpenIt() {
        Line foreign = one(new Facts(true, "games", true, BOX, List.of(), Claim.UNCLAIMED, "its box has 3 blocks"
                + " that aren't the playroom's - /hcm games lava claim confirm clears them", false, true, true));
        assertEquals(Status.FAIL, foreign.status());
        assertTrue(foreign.fix().startsWith("/hcm games lava claim confirm"), foreign.fix());
        Line broken = one(new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, "its room couldn't be built",
                false, true, true));
        assertEquals(Status.FAIL, broken.status());
        assertTrue(broken.fix().contains("/hcm games lava rebuild"), broken.fix());
    }

    @Test
    void noCabinetInTheClubhouseIsAWarningOnly() {
        List<Line> off = rows(new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, null, true, false, false));
        assertEquals(2, off.size(), off.toString());
        assertEquals(Status.OK, off.get(0).status());
        assertEquals(Status.WARN, off.get(1).status());
        assertTrue(off.get(1).what().contains("no cabinet in the Clubhouse"), off.get(1).what());
        List<Line> full = rows(new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, null, true, true, false));
        assertEquals(Status.WARN, full.get(1).status());
        assertTrue(full.get(1).what().contains("no place for The Floor Is Lava's cabinet"), full.get(1).what());
    }
}
