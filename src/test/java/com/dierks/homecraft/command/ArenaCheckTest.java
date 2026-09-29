package com.dierks.homecraft.command;

import com.dierks.homecraft.command.ArenaCheck.Claim;
import com.dierks.homecraft.command.ArenaCheck.Facts;
import com.dierks.homecraft.command.GamesCheck.Line;
import com.dierks.homecraft.command.GamesCheck.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Falling Floors' rows in {@code /hcm games check} (EXTRAS E1, EVENTS-DROPPER-SPEC §C.2 WP-F): off
 * is one OK line; a box that fits, claimed and verified, is one OK line saying so; no world, a
 * world not loaded, a box too close to something, a claim at another place, a claim that can't be
 * read and an arena that closed itself each say what is wrong and how to fix it.
 */
class ArenaCheckTest {

    private static final String BOX = "x 5376..5423, y 176..215, z 4352..4399";

    private static Facts good() {
        return new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, null, true);
    }

    private static List<Line> rows(Facts f) {
        List<Line> out = new ArrayList<>();
        ArenaCheck.rows(f, out);
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
        assertEquals(Status.OK, l.status(), "nothing to check");
        assertTrue(l.what().contains("Falling Floors is off"), l.what());
        assertEquals(Status.OK, one(new Facts(false, "", false, BOX, List.of("x"), Claim.UNKNOWN, "y", false)).status(),
                "switched off: its box isn't checked");
    }

    @Test
    void aBoxThatFitsClaimedAndBuiltIsOneOkLine() {
        Line l = one(good());
        assertEquals(Status.OK, l.status());
        assertEquals("Falling Floors: box fits (" + BOX + "), claimed, floors ready", l.what());
        assertEquals("Falling Floors: box fits (" + BOX + "), empty (claimed at its first build), floors being built",
                one(new Facts(true, "games", true, BOX, List.of(), Claim.UNCLAIMED, null, false)).what(),
                "before its first build");
    }

    /** F review #8: a round_seconds longer than the restart hold is a WARN (rounds stop starting early). */
    @Test
    void aRoundLongerThanTheRestartHoldIsAWarning() {
        Facts longRounds = new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, null, true, 900, 5);
        List<Line> out = rows(longRounds);
        assertEquals(2, out.size(), "the warning and the box's line: " + out);
        Line warn = out.get(0);
        assertEquals(Status.WARN, warn.status());
        assertTrue(warn.what().contains("round_seconds (900 s)") && warn.what().contains("restart hold (5 min)"),
                warn.what());
        assertTrue(warn.fix().contains("games.falling_floors.round_seconds"), warn.fix());
        assertEquals(Status.OK, out.get(1).status(), "the box still fits");

        assertEquals(Status.OK, one(new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, null, true, 180, 5))
                .status(), "the shipped 180 s inside a 5-minute hold: nothing to say");
        assertEquals(Status.OK, one(new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, null, true, 900, 0))
                .status(), "no restart times set: nothing is held, nothing to say");
    }

    @Test
    void eachThingWrongSaysHowToFixIt() {
        Line noWorld = one(new Facts(true, "", false, BOX, List.of(), Claim.CLAIMED, null, false));
        assertEquals(Status.FAIL, noWorld.status());
        assertTrue(noWorld.fix().contains("games.worlds"), noWorld.fix());

        Line notLoaded = one(new Facts(true, "games", false, BOX, List.of(), Claim.CLAIMED, null, false));
        assertEquals(Status.FAIL, notLoaded.status());
        assertTrue(notLoaded.what().contains("'games' isn't loaded"), notLoaded.what());

        Line near = one(new Facts(true, "games", true, BOX, List.of("falling_floors is only 12 blocks from fresh_boat's"
                + " half A (they must be 32 apart)"), Claim.CLAIMED, null, false));
        assertEquals(Status.FAIL, near.status());
        assertTrue(near.what().contains("fresh_boat") && near.fix().equals("move it with games.falling_floors.origin"),
                near.toString());

        Line moved = one(new Facts(true, "games", true, BOX, List.of(), Claim.MOVED, null, true));
        assertEquals(Status.WARN, moved.status(), "a claim at another place is a warning");
        assertTrue(moved.fix().contains("clear them by hand"), moved.fix());

        assertEquals(Status.WARN, one(new Facts(true, "games", true, BOX, List.of(), Claim.UNKNOWN, null, true))
                .status(), "a claim that can't be read");

        Line foreign = one(new Facts(true, "games", true, BOX, List.of(), Claim.UNCLAIMED, "its box has 2 blocks that"
                + " aren't Falling Floors' - /hcm games floors claim confirm clears them", false));
        assertEquals(Status.FAIL, foreign.status());
        assertTrue(foreign.fix().startsWith("/hcm games floors claim confirm"), foreign.fix());

        Line failed = one(new Facts(true, "games", true, BOX, List.of(), Claim.CLAIMED, "The floors could not be put"
                + " back after 3 tries (first at 5390,200,4370)", false));
        assertEquals(Status.FAIL, failed.status());
        assertTrue(failed.what().contains("5390,200,4370") && failed.fix().contains("floors reset"), failed.toString());
    }
}
