package com.dierks.homecraft.games;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared no-push team ({@link NoPush}, the "player collisions" decision) on a fake main
 * scoreboard: joining puts the player on {@value NoPush#TEAM} with its collision rule NEVER;
 * leaving puts them back on the team they came from (another plugin's nametags), if it still
 * exists; joining twice remembers the first team; the start-up clear empties a team a crash left
 * and says how many were on it; and no scoreboard at all is a quiet no-op.
 */
class NoPushTest {

    /** A main scoreboard: entry → team, the teams that exist and the ones set to never collide. */
    static final class FakeBoard implements NoPush.Board {
        final Map<String, String> teamOf = new HashMap<>();
        final Set<String> teams = new HashSet<>();
        final Set<String> noCollision = new HashSet<>();

        @Override
        public String teamOf(String entry) {
            return teamOf.get(entry);
        }

        @Override
        public void ensureNoCollision(String team) {
            teams.add(team);
            noCollision.add(team);
        }

        @Override
        public boolean exists(String team) {
            return teams.contains(team);
        }

        @Override
        public void add(String team, String entry) {
            if (teams.contains(team)) {
                teamOf.put(entry, team); // a scoreboard entry is on one team at a time
            }
        }

        @Override
        public void remove(String team, String entry) {
            if (team.equals(teamOf.get(entry))) {
                teamOf.remove(entry);
            }
        }

        @Override
        public Set<String> entries(String team) {
            Set<String> out = new HashSet<>();
            teamOf.forEach((e, t) -> {
                if (t.equals(team)) {
                    out.add(e);
                }
            });
            return out;
        }
    }

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);

    @Test
    void joiningPutsThePlayerOnATeamWhereNobodyCollides() {
        FakeBoard board = new FakeBoard();
        NoPush noPush = new NoPush(() -> board);
        assertTrue(noPush.on(SAM, "Sam"), "done");
        assertEquals(NoPush.TEAM, board.teamOf("Sam"), "Sam is on the no-push team");
        assertTrue(board.noCollision.contains(NoPush.TEAM), "whose collision rule is NEVER");
        assertTrue(noPush.isOn(SAM), "and the helper knows it");
        noPush.off(SAM, "Sam");
        assertNull(board.teamOf("Sam"), "off it again, and on no team, as before");
        assertFalse(noPush.isOn(SAM), "forgotten");
    }

    @Test
    void leavingPutsThePlayerBackOnTheTeamTheyCameFrom() {
        FakeBoard board = new FakeBoard();
        board.teams.add("nametag_red");
        board.teamOf.put("Sam", "nametag_red"); // another plugin's nametag colour
        NoPush noPush = new NoPush(() -> board);
        noPush.on(SAM, "Sam");
        assertEquals(NoPush.TEAM, board.teamOf("Sam"), "the game's team while playing");
        noPush.on(SAM, "Sam"); // a second holder in the same game: nothing new remembered
        noPush.off(SAM, "Sam");
        assertEquals("nametag_red", board.teamOf("Sam"), "their own team back after the game");
        noPush.off(SAM, "Sam");
        assertEquals("nametag_red", board.teamOf("Sam"), "a second off changes nothing");

        board.teamOf.put("Ava", "nametag_blue");
        board.teams.add("nametag_blue");
        noPush.on(AVA, "Ava");
        board.teams.remove("nametag_blue"); // the other plugin removed its team meanwhile
        noPush.off(AVA, "Ava");
        assertNull(board.teamOf("Ava"), "a team that is gone isn't made again");
    }

    @Test
    void theStartUpClearEmptiesATeamACrashLeftAndCountsThem() {
        FakeBoard board = new FakeBoard();
        board.ensureNoCollision(NoPush.TEAM);
        board.teamOf.put("Sam", NoPush.TEAM);
        board.teamOf.put("Ava", NoPush.TEAM);
        board.teams.add("nametag_red");
        board.teamOf.put("Lee", "nametag_red");
        NoPush noPush = new NoPush(() -> board);
        assertEquals(2, noPush.clearAll(), "two were still on it (their earlier teams are lost: WARN)");
        assertEquals(Set.of(), board.entries(NoPush.TEAM), "nobody is on it now");
        assertEquals("nametag_red", board.teamOf("Lee"), "nobody else's team is touched");
        assertEquals(0, noPush.clearAll(), "a second start finds nothing");
        assertEquals(0, new NoPush(FakeBoard::new).clearAll(), "no team yet: nothing to clear");
    }

    @Test
    void anEntryTheTeamStillHoldsWithNothingRememberedIsTakenOff() {
        FakeBoard board = new FakeBoard();
        board.ensureNoCollision(NoPush.TEAM);
        board.teamOf.put("Sam", NoPush.TEAM); // on it, but not through this run's helper
        NoPush noPush = new NoPush(() -> board);
        noPush.off(SAM, "Sam");
        assertNull(board.teamOf("Sam"), "off it all the same");
        noPush.on(SAM, "Sam");
        noPush.off(SAM, "Sam");
        assertNull(board.teamOf("Sam"), "and never 'back' onto the no-push team itself");
    }

    @Test
    void stoppingPutsEveryoneBack() {
        FakeBoard board = new FakeBoard();
        board.teams.add("nametag_red");
        board.teamOf.put("Sam", "nametag_red");
        NoPush noPush = new NoPush(() -> board);
        noPush.on(SAM, "Sam");
        noPush.on(AVA, "Ava");
        assertEquals(2, noPush.offAll(), "both");
        assertEquals("nametag_red", board.teamOf("Sam"), "Sam's own team back");
        assertNull(board.teamOf("Ava"), "Ava on none, as before");
    }

    @Test
    void noScoreboardIsAQuietNoOp() {
        NoPush none = new NoPush(() -> null);
        assertFalse(none.on(SAM, "Sam"), "nothing to join");
        none.off(SAM, "Sam");
        assertEquals(0, none.clearAll(), "nothing to clear");
        NoPush broken = new NoPush(() -> {
            throw new IllegalStateException("no server");
        });
        assertFalse(broken.on(SAM, "Sam"), "a board that throws is no board, never a throw into a game");
        assertEquals(0, broken.clearAll(), "and never a throw into the start");
        assertEquals(0, NoPush.live().clearAll(), "the live board with no server (a test) is none too");
        assertFalse(NoPush.live().on(SAM, "Sam"), "so nothing is joined");
    }
}
