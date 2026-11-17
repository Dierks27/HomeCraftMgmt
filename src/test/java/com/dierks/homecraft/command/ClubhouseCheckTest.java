package com.dierks.homecraft.command;

import com.dierks.homecraft.command.ClubhouseCheck.Facts;
import com.dierks.homecraft.command.GamesCheck.Line;
import com.dierks.homecraft.command.GamesCheck.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Clubhouse's rows in {@code /hcm games check} (CLUBHOUSE-SPEC §5): off is one OK line; a generated
 * room whose box fits, claimed and built, is one OK line; a box too close to something fails with how
 * to move it; an owner-built room says whether every spot is set, and WARNs when it stands in a world
 * that isn't a Games world (the Clubhouse review, #13: set before {@code here} checked the world).
 */
class ClubhouseCheckTest {

    private static final String BOX = "x 5376..5407, y 160..175, z 4448..4479";

    private static Facts generated(List<String> problems) {
        return new Facts(true, false, "games", true, BOX, problems, ArenaCheck.Claim.CLAIMED, null, true, List.of(),
                false, "", true);
    }

    private static Facts owner(String world, boolean listed, boolean complete) {
        return new Facts(true, true, "games", true, BOX, List.of(), ArenaCheck.Claim.UNCLAIMED, null, true,
                complete ? List.of() : List.of("podium 3 not set"), complete, world, listed);
    }

    private static List<Line> rows(Facts f) {
        List<Line> out = new ArrayList<>();
        ClubhouseCheck.rows(f, out);
        return out;
    }

    @Test
    void offAndAGoodGeneratedRoomAreOneOkLineEach() {
        List<Line> off = rows(new Facts(false, false, "", false, BOX, List.of(), ArenaCheck.Claim.UNKNOWN, null, false,
                List.of(), false, "", true));
        assertEquals(1, off.size(), "off: one line");
        assertEquals(Status.OK, off.get(0).status(), "off is fine: every race works as before");
        List<Line> ok = rows(generated(List.of()));
        assertEquals(1, ok.size(), "one line: " + ok);
        assertEquals(Status.OK, ok.get(0).status(), ok.get(0).what());
        assertTrue(ok.get(0).what().contains("built and checked"), ok.get(0).what());
    }

    @Test
    void aBoxTooCloseToSomethingFailsWithHowToMoveIt() {
        List<Line> bad = rows(generated(List.of("clubhouse is only 10 blocks from falling_floors (they must be 32 apart)")));
        assertEquals(Status.FAIL, bad.get(0).status(), "the box can't stand there");
        assertTrue(bad.get(0).what().contains("falling_floors") && bad.get(0).fix().contains("games.clubhouse.origin"),
                bad.get(0).toString());
    }

    @Test
    void anOwnerBuiltRoomOutsideTheGamesWorldsIsWarned() {
        List<Line> fine = rows(owner("games", true, true));
        assertEquals(1, fine.size(), "one line: " + fine);
        assertEquals(Status.OK, fine.get(0).status(), "in a Games world, every spot set");
        List<Line> hub = rows(owner("hub", false, true));
        assertEquals(Status.WARN, hub.get(0).status(), "a room in a world that isn't a Games world is warned about");
        assertTrue(hub.get(0).what().contains("'hub'") && hub.get(0).what().contains("games.worlds"), hub.get(0).what());
        assertTrue(hub.get(0).fix().contains("/hcm games clubhouse here") && hub.get(0).fix().contains("generated"),
                hub.get(0).fix());
        List<Line> both = rows(owner("hub", false, false));
        assertEquals(2, both.size(), "and the spots still to set, too: " + both);
        assertEquals(Status.WARN, both.get(1).status(), both.get(1).what());
    }

    /** 0.41.0: the generated room with its side rooms on, one with blocks in their way, and a game of chance listed. */
    private static Facts rooms(List<String> annex, String line, List<String> refused) {
        return new Facts(true, false, "games", true, BOX, List.of(), ArenaCheck.Claim.CLAIMED, null, true, List.of(),
                false, "", true, true, annex, line, refused);
    }

    @Test
    void theSideRoomsHaveTheirOwnLines() {
        List<Line> ok = rows(rooms(List.of(), "the Arcade Room and the Trophy Room, x 5376..5407", List.of()));
        assertEquals(2, ok.size(), ok.toString());
        assertTrue(ok.get(1).what().contains("side rooms: the Arcade Room"), ok.toString());
        assertEquals(Status.OK, ok.get(1).status());

        List<Line> near = rows(rooms(List.of("clubhouse is only 4 blocks from fresh_parkour's half A"), null, List.of()));
        assertEquals(Status.OK, near.get(0).status(), "the main room still fits: " + near);
        assertEquals(Status.WARN, near.get(1).status(), "the side rooms are left out, a WARN, never a FAIL");
        assertTrue(near.get(1).what().contains("left out"), near.toString());

        List<Line> blocked = rows(rooms(List.of(), "left out - the side rooms' box has 3 blocks that aren't the"
                + " Clubhouse's - /hcm games clubhouse rebuild confirm clears them", List.of()));
        assertEquals(Status.WARN, blocked.get(1).status());
        assertTrue(blocked.get(1).fix().contains("rebuild confirm"), blocked.toString());

        List<Line> chance = rows(rooms(List.of(), "built", List.of("games.clubhouse.arcade_room.games: ore_slots is a"
                + " game of chance, and those never go in the Arcade Room - left out")));
        assertEquals(Status.WARN, chance.get(2).status(), "a listed game of chance is a WARN: " + chance);
        assertTrue(chance.get(2).what().contains("game of chance"));

        Facts off = new Facts(true, false, "games", true, BOX, List.of(), ArenaCheck.Claim.CLAIMED, null, true,
                List.of(), false, "", true, false, List.of(), "both switched off", List.of());
        assertTrue(rows(off).get(1).what().contains("switched off"), rows(off).toString());
    }
}
