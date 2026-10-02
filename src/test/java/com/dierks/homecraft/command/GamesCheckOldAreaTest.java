package com.dierks.homecraft.command;

import com.dierks.homecraft.command.GamesCheck.Line;
import com.dierks.homecraft.command.GamesCheck.OldArea;
import com.dierks.homecraft.command.GamesCheck.OldState;
import com.dierks.homecraft.command.GamesCheck.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm games check}'s lines about the old areas a move left (V4-DECISIONS "One move mechanism"): news
 * while one is emptied by itself, and once it is ("Golf of the Week moved to its new area; its old area is
 * empty"), and a WARN with the one fix when it can't be (something in its way, an owner's own move, its world
 * not loaded, or blocks left that aren't Fresh Courses').
 */
class GamesCheckOldAreaTest {

    private static final String WHERE = "half A x 7488..7551, y 160..175, z 4096..4223; half B x 8128..8191, y 160..175,"
            + " z 4096..4223";

    private static Line line(OldState state, String detail, long removed, long left) {
        return GamesCheck.oldArea(new OldArea("fresh_golf", "Golf of the Week", state, WHERE, detail, 40, removed, left));
    }

    @Test
    void anAreaEmptiedByItselfIsNewsNotAProblem() {
        Line waiting = line(OldState.WAITING, null, 0, 0);
        assertEquals(Status.OK, waiting.status(), "waiting: OK");
        assertEquals("Golf of the Week moved to its new area; its old area (" + WHERE + ") is emptied by itself once"
                + " nothing else is being built", waiting.what(), "said plainly");
        Line running = line(OldState.RUNNING, null, 0, 0);
        assertEquals(Status.OK, running.status(), "running: OK");
        assertTrue(running.what().endsWith("is being emptied, 40%"), running.what());
        Line done = line(OldState.EMPTIED, null, 1234, 0);
        assertEquals(Status.OK, done.status(), "emptied: OK");
        assertEquals("Golf of the Week moved to its new area; its old area is empty (1234 blocks taken away)",
                done.what(), "the owner's second line");
    }

    @Test
    void anAreaThatCantBeEmptiedWarnsWithItsOneFix() {
        Line held = line(OldState.HELD, "the Clubhouse is only 3 blocks from its old half A", 0, 0);
        assertEquals(Status.WARN, held.status(), "a WARN, never a FAIL: nothing is broken, an area waits");
        assertTrue(held.what().contains("can't be emptied: the Clubhouse is only 3 blocks from its old half A. Nothing"
                + " there was changed and it stays guarded"), held.what());
        assertEquals("move what is in the way, then /hcm games gen tidy fresh_golf confirm", held.fix(), "the fix");
        Line manual = line(OldState.MANUAL, null, 0, 0);
        assertEquals(Status.WARN, manual.status(), "an owner's move");
        assertTrue(manual.fix().startsWith("/hcm games gen tidy fresh_golf confirm empties it"), manual.fix());
        Line elsewhere = line(OldState.ELSEWHERE, "old_games", 0, 0);
        assertEquals(Status.WARN, elsewhere.status(), "its world isn't loaded");
        assertTrue(elsewhere.what().contains("is in old_games, which isn't loaded"), elsewhere.what());
        Line left = line(OldState.EMPTIED, "7490,161,4100 minecraft:bedrock", 900, 1);
        assertEquals(Status.WARN, left.status(), "a block left that isn't ours");
        assertTrue(left.what().contains("1 block that isn't Fresh Courses' was left there (first at 7490,161,4100"
                + " minecraft:bedrock)"), left.what());
        assertTrue(left.fix().contains("nothing guards that area now"), left.fix());
    }

    @Test
    void theFreshSectionListsThemAfterTheCourses() {
        GamesCheck.Fresh fr = new GamesCheck.Fresh(true, "weekly", "New courses every Monday", "games", true, true,
                List.of(), List.of(), null, null, "x 1760..5503, 24 plots", Map.of(),
                List.of(new OldArea("fresh_boat", "Ice Boat", OldState.EMPTIED, "half A x 6080..6207", null, 100, 400, 0)));
        List<Line> out = new ArrayList<>();
        GamesCheck.fresh(fr, out);
        assertTrue(out.stream().anyMatch(l -> l.status() == Status.OK && l.what().equals("Ice Boat moved to its new"
                + " area; its old area is empty (400 blocks taken away)")), "the boat's line: " + out);
        GamesCheck.Fresh none = new GamesCheck.Fresh(true, "weekly", "s", "games", true, true, List.of(), List.of(),
                null, null, "x", Map.of());
        assertEquals(List.of(), none.oldAreas(), "a Fresh without any says none");
    }
}
