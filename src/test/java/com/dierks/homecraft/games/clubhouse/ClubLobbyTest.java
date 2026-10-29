package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.PlayerAs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Through the Clubhouse first (0.38.3, design C1, {@code solo_first}): a player who clicked Start on a course
 * arrives with the trip ({@link ClubNext}) in their arrival, counts down {@code first_seconds} on the action
 * bar ("Sky Rings in 3"), and goes at 0 or on "Go now"; "Stay here" calls it off. A pending trip stops the
 * Clubhouse's idle clock: nobody is sent home while on their way to a game.
 */
class ClubLobbyTest {

    private static final long T = 1_000_000L;
    private static final ClubNext SKY = new ClubNext("trials", "sky_rings", "Sky Rings", false);
    private final UUID sam = UUID.randomUUID();
    private final UUID ava = UUID.randomUUID();

    private static List<Integer> lefts(ClubLobby lobby, long now) {
        return lobby.second(now).stream().map(ClubLobby.Tick::left).toList();
    }

    @Test
    void theCountdownSaysTheWholeSecondsLeftThenZero() {
        ClubLobby lobby = new ClubLobby();
        lobby.start(sam, SKY, T, 5);
        assertEquals(List.of(5), lefts(lobby, T), "Sky Rings in 5 on arrival");
        assertEquals(List.of(5), lefts(lobby, T + 1), "a moment later still 5 (whole seconds, rounded up)");
        assertEquals(List.of(4), lefts(lobby, T + 1_000), "4");
        assertEquals(List.of(3), lefts(lobby, T + 2_000), "3");
        assertEquals(List.of(1), lefts(lobby, T + 4_000), "1");
        assertEquals(List.of(0), lefts(lobby, T + 5_000), "0: the trip is due");
        assertEquals(List.of(0), lefts(lobby, T + 9_000), "never below 0 (a late second still goes)");
        ClubLobby.Tick tick = lobby.second(T).getFirst();
        assertEquals(sam, tick.player(), "whose trip");
        assertEquals(SKY, tick.next(), "and where to: the name is on the action bar");
        assertTrue(lobby.waiting(sam), "the countdown never takes the trip itself");
    }

    @Test
    void firstSecondsIsHeldToOneToThirty() {
        ClubLobby lobby = new ClubLobby();
        lobby.start(sam, SKY, T, 0);
        assertEquals(List.of(1), lefts(lobby, T), "0 reads as 1 second");
        lobby.start(sam, SKY, T, 99);
        assertEquals(List.of(30), lefts(lobby, T), "99 reads as 30 seconds (a new start replaces the old)");
        assertEquals(1, lobby.size(), "one trip a player");
    }

    @Test
    void goNowTakesTheTripOnceAndStopsTheCountdown() {
        ClubLobby lobby = new ClubLobby();
        lobby.start(sam, SKY, T, 5);
        assertEquals(SKY, lobby.next(sam), "Go now's name: Go now: Sky Rings");
        assertEquals(SKY, lobby.take(sam), "Go now: the trip");
        assertNull(lobby.take(sam), "only once: a second tap (or the countdown) finds nothing");
        assertFalse(lobby.waiting(sam), "no longer on the way");
        assertEquals(List.of(), lobby.second(T + 5_000), "no countdown left");
        assertNull(lobby.take(null), "nobody");
    }

    @Test
    void stayHereCallsTheTripOff() {
        ClubLobby lobby = new ClubLobby();
        lobby.start(sam, SKY, T, 5);
        lobby.start(ava, new ClubNext("golf", "tiny", "Tiny Golf", false), T, 5);
        assertEquals(SKY, lobby.stay(sam), "Stay here: what was called off");
        assertNull(lobby.stay(sam), "nothing more to call off");
        assertNull(lobby.next(sam), "no Go now in the kit");
        assertTrue(lobby.waiting(ava), "someone else's trip is theirs");
        lobby.forget(ava);
        assertFalse(lobby.waiting(ava), "out of the Clubhouse: forgotten");
        lobby.start(sam, SKY, T, 5);
        lobby.clear();
        assertEquals(0, lobby.size(), "the Clubhouse stopped: nobody's trip is left");
        lobby.forget(null);
        lobby.start(null, SKY, T, 5);
        lobby.start(sam, null, T, 5);
        assertEquals(0, lobby.size(), "nothing for nobody or nowhere");
    }

    @Test
    void aPendingTripStopsTheIdleClockAndStayStartsItAgain() {
        ClubVisits visits = new ClubVisits();
        ClubLobby lobby = new ClubLobby();
        visits.enter(sam, "Sam", ClubVisits.Kind.VISIT, 0);
        lobby.start(sam, SKY, 0, 30);
        long min = ClubVisits.MINUTE;
        for (long t = min; t <= 5 * min; t += min) {
            assertEquals(List.of(), visits.second(t, 2, lobby::waiting, false),
                    "on their way to a game (max_minutes 2): never warned or sent home");
        }
        lobby.stay(sam);
        assertEquals(List.of(), visits.second(5 * min + 1_000, 2, lobby::waiting, false), "the clock starts again");
        assertEquals(List.of(new ClubVisits.Act(sam, ClubVisits.What.WARN_IDLE)),
                visits.second(6 * min + 1_000, 2, lobby::waiting, false), "the warning a minute before");
        assertEquals(List.of(new ClubVisits.Act(sam, ClubVisits.What.HOME_IDLE)),
                visits.second(7 * min + 1_000, 2, lobby::waiting, false), "then home, as before");
    }

    @Test
    void theArrivalCarriesTheTripAndARefusedEntryTakesNothing() throws Exception {
        GamesBench bench = new GamesBench(GamesBench.at(2026, 10, 26, 18, 0), List.of(Clubhouse.SPEC), "clubhouse",
                ClubhouseSettings.defaults());
        try {
            GamesService games = bench.games();
            Clubhouse club = (Clubhouse) games.game("clubhouse");
            Player nia = PlayerAs.limited(bench.player("Nia"), Set.of(), "world_nether");
            assertNull(Clubhouse.lobby(games, nia, SKY), "the Clubhouse not open: null, straight to the start");

            Map<UUID, Player> online = new HashMap<>();
            club.watch(new WatchLive(club, new WatchVisibility(new WatchVisibilityTest.Viewers()), online::get));
            ClubhouseRoom room = new ClubhouseRoom(new ClubhouseRoomTest.Host(), ClubhouseSettings.defaults());
            room.start();
            room.here(new ClubhouseRoom.Place("games", 100.5, 70, -20.5, 90f));
            club.room(room);
            assertEquals(Boolean.FALSE, Clubhouse.lobby(games, nia, SKY),
                    "refused by the gate (the nether): false, and they were told why");
            assertTrue(bench.heard(nia.getUniqueId()).contains("Games can't be played in this world."),
                    bench.heard(nia.getUniqueId()));
            assertNull(club.arrivingFor(nia.getUniqueId()), "nothing taken, nobody on the way");
            assertEquals(0, bench.severe(), "never a failure");

            club.expect(sam, ClubVisits.Kind.VISIT, SKY);
            assertEquals(SKY, club.arrivingFor(sam), "the arrival carries the course and the warm-up choice");
            club.expect(ava, ClubVisits.Kind.VISIT);
            assertNull(club.arrivingFor(ava), "a plain visit carries none");
            assertEquals(0, club.lobby().size(), "the countdown starts only once they are in");
            assertTrue(club.soloFirst() && club.soloAfter(), "the shipped switches: on");
        } finally {
            bench.close();
        }
    }
}
