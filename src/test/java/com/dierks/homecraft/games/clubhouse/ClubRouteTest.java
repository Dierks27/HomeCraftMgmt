package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a course's or a round's Start goes, and whether its end comes back (0.38.3, the owner: "go to the
 * clubhouse when click into a game"): a player in the Clubhouse is handed out from it; anyone else goes
 * through it first with {@code solo_first} on; and every fallback (no Clubhouse, a test run, a rider, another
 * session, the restart hold or the Clubhouse closing for it, another world, the switch off) is exactly
 * 0.38.2's direct start, and its end goes home.
 */
class ClubRouteTest {

    private static final UUID SAM = UUID.randomUUID();

    /** A Clubhouse whose switches, closing and visitors the test sets. */
    static final class FakeDoor implements ClubDoor {
        boolean soloFirst = true;
        boolean soloAfter = true;
        boolean closing;
        final Set<UUID> in = new HashSet<>();
        String world = "games";

        @Override
        public String world() {
            return world;
        }

        @Override
        public boolean seatable(UUID player) {
            return in.contains(player);
        }

        @Override
        public boolean spectator(UUID player) {
            return false;
        }

        @Override
        public boolean handOut(Player p, Game to, String ref) {
            return false;
        }

        @Override
        public void handBack(Player p, ClubVisits.Kind kind) {
        }

        @Override
        public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
            return false;
        }

        @Override
        public boolean closingForRestart() {
            return closing;
        }

        @Override
        public boolean partyAfter() {
            return true;
        }

        @Override
        public boolean nightAfter() {
            return true;
        }

        @Override
        public boolean golfAfter() {
            return true;
        }

        @Override
        public boolean soloFirst() {
            return soloFirst;
        }

        @Override
        public boolean soloAfter() {
            return soloAfter;
        }

        @Override
        public void result(ClubBoard.Sheet sheet, Consumer<Player> opener) {
        }

        @Override
        public void podium(List<UUID> topThree) {
        }
    }

    private static ClubRoute.Way start(ClubDoor club, String world, boolean test, boolean rider, boolean inSession,
                                       boolean held) {
        return ClubRoute.start(club, SAM, world, test, rider, inSession, held);
    }

    @Test
    void aStartGoesThroughTheClubhouseFirstWithSoloFirstOn() {
        FakeDoor door = new FakeDoor();
        assertEquals(ClubRoute.Way.LOBBY, start(door, "games", false, false, false, false),
                "home, the Clubhouse open, the course in its world: through the Clubhouse");
        assertEquals(ClubRoute.Way.LOBBY, start(door, "GAMES", false, false, false, false), "any case of the world");
    }

    @Test
    void aPlayerInTheClubhouseIsHandedOutFromItEvenWithSoloFirstOff() {
        FakeDoor door = new FakeDoor();
        door.in.add(SAM);
        assertEquals(ClubRoute.Way.HAND_OUT, start(door, "games", false, false, true, false),
                "in the Clubhouse (a session of its own): handed out to the start, no trip home");
        door.soloFirst = false;
        assertEquals(ClubRoute.Way.HAND_OUT, start(door, "games", false, false, true, false),
                "solo_first off (design C2): still the only way on from inside");
    }

    @Test
    void everyFallbackIsTheDirectStartOf0382() {
        assertEquals(ClubRoute.Way.DIRECT, start(null, "games", false, false, false, false),
                "the Clubhouse off, building or failed its check: no door");
        FakeDoor door = new FakeDoor();
        assertEquals(ClubRoute.Way.DIRECT, start(door, "games", true, false, false, false), "an admin's test run");
        assertEquals(ClubRoute.Way.DIRECT, start(door, "games", false, true, false, false), "a ride is booked");
        assertEquals(ClubRoute.Way.DIRECT, start(door, "games", false, false, true, false),
                "in some other session (refused there with IN_SESSION, as before)");
        assertEquals(ClubRoute.Way.DIRECT, start(door, "games", false, false, false, true),
                "the restart hold (the start is refused as before)");
        assertEquals(ClubRoute.Way.DIRECT, start(door, "lobby_world", false, false, false, false),
                "a course in another world");
        assertEquals(ClubRoute.Way.DIRECT, start(door, "", false, false, false, false), "a course with no world");
        assertEquals(ClubRoute.Way.DIRECT, start(door, null, false, false, false, false), "or none known");
        door.closing = true;
        assertEquals(ClubRoute.Way.DIRECT, start(door, "games", false, false, false, false),
                "the Clubhouse closing for the restart");
        door.closing = false;
        door.soloFirst = false;
        assertEquals(ClubRoute.Way.DIRECT, start(door, "games", false, false, false, false),
                "solo_first off: straight to the start (design C2)");
        assertEquals(ClubRoute.Way.DIRECT, ClubRoute.start(new FakeDoor(), null, "games", false, false, false, false),
                "no player");
        door.in.add(SAM);
        assertEquals(ClubRoute.Way.DIRECT, start(door, "games", true, false, true, false),
                "a test run from inside the Clubhouse is never handed out (the session refuses it, as before)");
    }

    @Test
    void aSoloEndComesBackOnlyWithSoloAfterAndTheSameWorld() {
        FakeDoor door = new FakeDoor();
        assertTrue(ClubRoute.after(door, "games", false, false), "back to the Clubhouse");
        assertFalse(ClubRoute.after(null, "games", false, false), "no Clubhouse: home");
        assertFalse(ClubRoute.after(door, "games", true, false), "a test run: home");
        assertFalse(ClubRoute.after(door, "games", false, true), "with a rider: home (the rider goes with them)");
        assertFalse(ClubRoute.after(door, "nether_course", false, false), "another world: home");
        door.soloAfter = false;
        assertFalse(ClubRoute.after(door, "games", false, false), "solo_after off: home, as 0.38.2");
        door.soloAfter = true;
        door.closing = true;
        assertTrue(ClubRoute.after(door, "games", false, false), "after() never looks at the hold: early in the"
                + " hold a finish still comes back, warned and home a minute later, as a race's end; the caller"
                + " asks closingForRestart() itself for the last minute (R383-S3)");
    }

    @Test
    void leaveGamePartwayGoesBackOnlyFromARunStartedThere() {
        FakeDoor door = new FakeDoor();
        assertTrue(ClubRoute.leaveBack(door, "games", true, false, false), "started from the Clubhouse: back there");
        assertFalse(ClubRoute.leaveBack(door, "games", false, false, false), "started from home: home");
        assertFalse(ClubRoute.leaveBack(null, "games", true, false, false), "the Clubhouse went meanwhile: home");
        assertFalse(ClubRoute.leaveBack(door, "games", true, true, false), "a test run: home");
        assertFalse(ClubRoute.leaveBack(door, "games", true, false, true), "with a rider: home");
        assertFalse(ClubRoute.leaveBack(door, "other", true, false, false), "another world: home");
        door.closing = true;
        assertFalse(ClubRoute.leaveBack(door, "games", true, false, false), "closing for the restart: home");
        door.closing = false;
        door.soloAfter = false;
        assertTrue(ClubRoute.leaveBack(door, "games", true, false, false),
                "Leave game partway isn't solo_after's: it follows where the run started");
    }

    @Test
    void aDoorThatDoesntSayIsTheClubhouseWithTheNewSwitchesOff() {
        ClubDoor plain = plainDoor();
        assertFalse(plain.soloFirst(), "the races' and golf's fake doors (0.38.2's) say nothing: off");
        assertFalse(plain.soloAfter(), "both switches");
        plain.soloResult(null, null); // a no-op, never a throw
        assertEquals(ClubRoute.Way.DIRECT, ClubRoute.start(plain, SAM, "games", false, false, false, false),
                "so a Start never goes through the Clubhouse");
        assertFalse(ClubRoute.after(plain, "games", false, false), "and a finish never comes back");
    }

    /** A door that overrides none of 0.38.3's defaults. */
    private static ClubDoor plainDoor() {
        return new ClubDoor() {
            @Override
            public String world() {
                return "games";
            }

            @Override
            public boolean seatable(UUID player) {
                return false;
            }

            @Override
            public boolean spectator(UUID player) {
                return false;
            }

            @Override
            public boolean handOut(Player p, Game to, String ref) {
                return false;
            }

            @Override
            public void handBack(Player p, ClubVisits.Kind kind) {
            }

            @Override
            public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
                return false;
            }

            @Override
            public boolean partyAfter() {
                return true;
            }

            @Override
            public boolean nightAfter() {
                return true;
            }

            @Override
            public boolean golfAfter() {
                return true;
            }

            @Override
            public void result(ClubBoard.Sheet sheet, Consumer<Player> opener) {
            }

            @Override
            public void podium(List<UUID> topThree) {
            }
        };
    }
}
