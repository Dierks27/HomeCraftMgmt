package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.NoPush;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way out of the Clubhouse (CLUBHOUSE-SPEC §7, §8), on the real framework: Leave game, a quit, a
 * kick, the restart hold and a timeout (their session ends), the Clubhouse or the games stopping: each
 * takes the player out of the Clubhouse and off the no-push team, back on the team they came from
 * (their things come back with the world session's end, which is the framework's restore). An
 * arrival that never happens is swept: nothing waits on a ghost.
 */
class ClubhouseExitsTest {

    /** A main scoreboard: entry to team. */
    static final class Board implements NoPush.Board {
        final Map<String, String> teamOf = new HashMap<>();
        final Set<String> teams = new HashSet<>();

        @Override
        public String teamOf(String entry) {
            return teamOf.get(entry);
        }

        @Override
        public void ensureNoCollision(String team) {
            teams.add(team);
        }

        @Override
        public boolean exists(String team) {
            return teams.contains(team);
        }

        @Override
        public void add(String team, String entry) {
            if (teams.contains(team)) {
                teamOf.put(entry, team);
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

    private GamesBench bench;
    private Clubhouse club;
    private final Board board = new Board();

    @BeforeEach
    void setUp() {
        bench = new GamesBench(GamesBench.at(2026, 9, 29, 19, 0), List.of(Clubhouse.SPEC), "clubhouse",
                ClubhouseSettings.defaults());
        club = (Clubhouse) bench.games().game("clubhouse");
        club.pushes(new NoPush(() -> board));
        board.teams.add("red");
    }

    @AfterEach
    void tearDown() throws Exception {
        bench.close();
    }

    private Player in(String name, ClubVisits.Kind kind) {
        Player p = bench.player(name);
        board.teamOf.put(name, "red"); // another plugin's nametag team
        club.admit(p.getUniqueId(), name, kind, false);
        assertEquals(NoPush.TEAM, board.teamOf.get(name), name + " can't push or be pushed in the Clubhouse");
        return p;
    }

    private void out(Player p, String how) {
        assertFalse(club.visits().in(p.getUniqueId()), p.getName() + " is out of the Clubhouse (" + how + ")");
        assertEquals("red", board.teamOf.get(p.getName()), p.getName() + " is off the no-push team, back on their own"
                + " team (" + how + ")");
    }

    @Test
    void everyWayOutTakesThemOutAndOffTheNoPushTeam() {
        Player leave = in("Leave", ClubVisits.Kind.VISIT);
        Player quit = in("Quit", ClubVisits.Kind.PARTY);
        Player kick = in("Kick", ClubVisits.Kind.NIGHT);
        Player hold = in("Hold", ClubVisits.Kind.GOLF);
        Player idle = in("Idle", ClubVisits.Kind.VISIT);
        Player stop1 = in("StopA", ClubVisits.Kind.VISIT);
        Player stop2 = in("StopB", ClubVisits.Kind.PARTY);

        club.onSessionEnd(leave, EndReason.QUIT_ITEM);
        out(leave, "Leave game");
        club.onQuit(quit); // the quit, then its session's end: twice is fine
        club.onSessionEnd(quit, EndReason.DISCONNECT);
        out(quit, "a quit");
        club.onSessionEnd(kick, EndReason.DISCONNECT);
        out(kick, "a kick");
        club.onSessionEnd(hold, EndReason.FINISH); // the restart hold's minute: its session ended
        out(hold, "the restart hold");
        club.onSessionEnd(idle, EndReason.FINISH); // max_minutes: its session ended
        out(idle, "a timeout");
        club.onSessionEnd(stop1, EndReason.GAME_OFF); // the Clubhouse switched off: the framework ends it
        out(stop1, "the Clubhouse switched off");
        club.stop(); // the games stopping (a reload, the server): everyone left in it
        out(stop2, "the games stopping");
        assertEquals(0, club.visits().size(), "nobody left in it");
    }

    @Test
    void anArrivalThatNeverHappensLeavesNoGhost() {
        UUID ghost = UUID.randomUUID();
        club.expect(ghost, ClubVisits.Kind.PARTY);
        club.sweepArrivals(id -> true);
        assertTrue(club.arriving(ghost), "an entry still on its way (ENTERING) is never swept");
        club.sweepArrivals(id -> false);
        assertFalse(club.arriving(ghost), "an entry that was dropped on the way is forgotten");
        assertFalse(club.visits().in(ghost), "and was never a visitor");
    }

    @Test
    void offMeansNoDoorForAnyFlow() {
        assertNull(Clubhouse.door(bench.games()), "not started (no room built): no door, every flow as before");
        assertFalse(Clubhouse.offered(bench.games()), "and no Go or Watch button anywhere");
        assertNull(Clubhouse.door(null), "no games: no door");
    }
}
