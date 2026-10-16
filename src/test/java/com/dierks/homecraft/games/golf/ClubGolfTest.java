package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf together's end in the Clubhouse (CLUBHOUSE-SPEC §3, §8): when the group's round ends, the group
 * goes to the Clubhouse and the board shows the group ranking; with the Clubhouse off (no door),
 * {@code golf_after} off, or the course in another world, they go home as before.
 */
class ClubGolfTest {

    private static final UUID SAM = UUID.randomUUID();
    private static final UUID AVA = UUID.randomUUID();
    private static final UUID LEE = UUID.randomUUID();

    /** A Clubhouse that takes golfers and remembers the board. */
    private static final class Door implements ClubDoor {
        final Map<UUID, ClubVisits.Kind> in = new LinkedHashMap<>();
        boolean golfAfter = true;
        /** Closing for a restart (the hold's last 66 s and the restart's own minute): the real one takes nobody in. */
        boolean closing;
        ClubBoard.Sheet sheet;
        final List<UUID> handedOut = new ArrayList<>();
        final List<UUID> handedBack = new ArrayList<>();
        boolean handOutOk = true;

        @Override
        public String world() {
            return "games";
        }

        @Override
        public boolean seatable(UUID player) {
            return in.containsKey(player);
        }

        @Override
        public boolean spectator(UUID player) {
            return false;
        }

        @Override
        public boolean handOut(Player p, Game to, String ref) {
            if (!handOutOk || in.remove(p.getUniqueId()) == null) {
                return false;
            }
            handedOut.add(p.getUniqueId());
            return true;
        }

        @Override
        public void handBack(Player p, ClubVisits.Kind kind) {
            handedBack.add(p.getUniqueId());
            in.put(p.getUniqueId(), kind);
        }

        @Override
        public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
            in.put(p.getUniqueId(), kind);
            return true;
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
            return golfAfter;
        }

        @Override
        public boolean closingForRestart() {
            return closing;
        }

        @Override
        public void result(ClubBoard.Sheet s, Consumer<Player> opener) {
            sheet = s;
        }

        @Override
        public void podium(List<UUID> topThree) {
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> true;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static GolfGroup.Row row(UUID id, String name, int... strokes) {
        List<GolfRun.HoleScore> s = new ArrayList<>();
        for (int k : strokes) {
            s.add(new GolfRun.HoleScore(3, k, false));
        }
        int total = 0;
        for (int k : strokes) {
            total += k;
        }
        return new GolfGroup.Row(id, name, s, total, GolfGroup.Seat.WAITING);
    }

    private static GolfGroup.Card card() {
        return new GolfGroup.Card("meadow", "Meadow Links", List.of(3, 3, 3), 2, List.of(row(SAM, "Sam", 3, 4, 3),
                row(AVA, "Ava", 2, 3, 3), row(LEE, "Lee", 4, 4, 5)), true, -1, GolfGroup.HOLE_CLOCK_SECONDS);
    }

    /**
     * The PRODBUG the journeys found, for golf together: a group whose round ends in the restart hold's last
     * minute went into the Clubhouse, still there at the restart. While the Clubhouse is closing for the restart
     * they go home with the group's card, as with no Clubhouse, and nothing of the Clubhouse's is touched. A
     * group that ends earlier in the hold still goes there (CLUBHOUSE-SPEC §7).
     */
    @Test
    void aGroupThatEndsAsTheClubhouseClosesForTheRestartGoesHomeAndLeavesTheClubhouseAlone() {
        Door door = new Door();
        door.closing = true;
        int[] forgot = {0};
        for (UUID id : List.of(SAM, AVA, LEE)) {
            assertFalse(ClubGolf.take(door, player(id), "games", () -> forgot[0]++, card(), null),
                    "the caller sends them home");
        }
        assertTrue(door.in.isEmpty(), "nobody taken in");
        assertEquals(0, forgot[0], "their rounds are left for the trip home, as with no Clubhouse");
        assertNull(door.sheet, "no board for a Clubhouse closing for the restart");
    }

    @Test
    void theGroupEndsInTheClubhouseAndTheBoardShowsTheRanking() {
        Door door = new Door();
        int[] forgot = {0};
        for (UUID id : List.of(SAM, AVA, LEE)) {
            assertTrue(ClubGolf.take(door, player(id), "games", () -> forgot[0]++, card(), null), "to the Clubhouse");
        }
        assertEquals(3, forgot[0], "each finished round is forgotten (it was recorded at its own last hole)");
        for (UUID id : List.of(SAM, AVA, LEE)) {
            assertEquals(ClubVisits.Kind.GOLF, door.in.get(id), "in the Clubhouse as a golfer");
        }
        assertEquals(List.of("&e1. &fAva &78 strokes (-1)", "&e2. &fSam &710 strokes (+1)",
                "&e3. &fLee &713 strokes (+4)"), door.sheet.rows(), "the group ranking, fewest strokes first");
        assertTrue(door.sheet.title().contains("Meadow Links"), door.sheet.title());
    }

    @Test
    void offGolfAfterOffOrAnotherWorldGoesHomeAsBefore() {
        int[] forgot = {0};
        assertFalse(ClubGolf.take(null, player(SAM), "games", () -> forgot[0]++, card(), null), "no Clubhouse: home");
        Door door = new Door();
        door.golfAfter = false;
        assertFalse(ClubGolf.take(door, player(SAM), "games", () -> forgot[0]++, card(), null), "golf_after off: home");
        door.golfAfter = true;
        assertFalse(ClubGolf.take(door, player(SAM), "other", () -> forgot[0]++, card(), null),
                "a course in another world: home (a session only moves within its own world)");
        assertEquals(0, forgot[0], "nothing was forgotten: the caller does what it always did");
        assertNull(door.sheet, "and the board heard nothing");
    }
    private static org.bukkit.Location tee(String world) {
        org.bukkit.World w = (org.bukkit.World) Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getName" -> world;
                    case "hashCode" -> world.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return new org.bukkit.Location(w, 10.5, 65, 10.5);
    }

    @Test
    void playAgainTogetherTakesAMemberWaitingInTheClubhouseStraightToHoleOne() {
        Door door = new Door();
        Player sam = player(SAM);
        List<String> told = new ArrayList<>();
        List<UUID> moved = new ArrayList<>();
        door.in.put(SAM, ClubVisits.Kind.GOLF); // the group's last round ended in the Clubhouse
        assertTrue(ClubGolf.waiting(door, SAM, "games"), "waiting there: the host's Start may take them");
        Boolean went = ClubGolf.fromClubhouse(door, sam, null, "meadow", tee("games"), (p, at) -> moved.add(p.getUniqueId()),
                p -> null, (p, r) -> told.add(r.message()));
        assertEquals(Boolean.TRUE, went, "on their way to hole 1");
        assertEquals(List.of(SAM), door.handedOut, "their Clubhouse session handed to golf, in place");
        assertEquals(List.of(SAM), moved, "then the session's own teleport to the first tee");
        assertTrue(told.isEmpty(), "nothing to say");

        assertNull(ClubGolf.fromClubhouse(door, player(AVA), null, "meadow", tee("games"), (p, at) -> true, p -> null,
                (p, r) -> told.add(r.message())), "not in the Clubhouse: the usual entry");
        door.in.put(AVA, ClubVisits.Kind.GOLF);
        assertNull(ClubGolf.fromClubhouse(door, player(AVA), null, "meadow", tee("other"), (p, at) -> true, p -> null,
                (p, r) -> told.add(r.message())), "a course in another world: the usual entry (a session teleport stays"
                + " in its world)");
        assertFalse(ClubGolf.waiting(door, AVA, "other"), "so the Start doesn't count them as free");

        Boolean refused = ClubGolf.fromClubhouse(door, player(AVA), null, "meadow", tee("games"), (p, at) -> true,
                p -> com.dierks.homecraft.games.Refusal.of("You can't play golf right now."),
                (p, r) -> told.add(r.message()));
        assertEquals(Boolean.FALSE, refused, "the gate said no");
        assertEquals(List.of(AVA), door.handedBack, "handed back to the Clubhouse, never left between the two");
        assertTrue(told.getLast().contains("can't play golf"), told.toString());

        Boolean noTee = ClubGolf.fromClubhouse(door, player(AVA), null, "meadow", tee("games"), (p, at) -> false,
                p -> null, (p, r) -> told.add(r.message()));
        assertEquals(Boolean.FALSE, noTee, "the teleport failed");
        assertEquals(List.of(AVA, AVA), door.handedBack, "back in the Clubhouse again");
        assertTrue(door.in.containsKey(AVA), "still in it");
    }

    @Test
    void theCardOffersPlayAgainTogetherHomeOrInTheClubhouse() {
        assertTrue(com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu.offersAgain(true, true, true, false, true),
                "home, as before");
        assertTrue(com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu.offersAgain(true, true, false, true, true),
                "waiting in the Clubhouse, where the group went at the end (it never showed there before)");
        assertFalse(com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu.offersAgain(true, true, false, false, true),
                "still in another game: not yet");
        assertFalse(com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu.offersAgain(false, true, true, true, true),
                "the round isn't over");
        assertFalse(com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu.offersAgain(true, false, true, true, true),
                "no open party");
        assertFalse(com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu.offersAgain(true, true, true, true, false),
                "the course is closed");
    }
}
