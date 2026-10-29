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
        /** 0.38.3's switches. */
        boolean soloAfter = true;
        boolean soloFirst = true;
        /** Closing for a restart (the hold's last 66 s and the restart's own minute): the real one takes nobody in. */
        boolean closing;
        ClubBoard.Sheet sheet;
        final Map<UUID, Consumer<Player>> solo = new LinkedHashMap<>();
        final Map<UUID, String> lines = new LinkedHashMap<>();
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
            lines.put(p.getUniqueId(), line);
            return true;
        }

        @Override
        public boolean soloAfter() {
            return soloAfter;
        }

        @Override
        public boolean soloFirst() {
            return soloFirst;
        }

        @Override
        public void soloResult(Player p, Consumer<Player> opener) {
            solo.put(p.getUniqueId(), opener);
        }

        /** 0.38.4: the record line that came with the scorecard, or null. */
        final Map<UUID, String> records = new LinkedHashMap<>();

        @Override
        public void soloResult(Player p, Consumer<Player> opener, String recordLine) {
            soloResult(p, opener);
            records.put(p.getUniqueId(), recordLine);
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
                    case "getName" -> id.equals(SAM) ? "Sam" : "Ava";
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

    // ---- 0.38.3: a round alone through, from and back to the Clubhouse ----------------------------------

    @Test
    void aRoundAloneEndsInTheClubhouseWithItsOwnScorecard() {
        Door door = new Door();
        int[] forgot = {0};
        java.util.function.Consumer<Player> scorecard = p -> { };
        String line = com.dierks.homecraft.games.clubhouse.ClubhouseText.backSolo("Tiny Golf",
                "24 strokes (2 under par)");
        assertTrue(ClubGolf.soloTake(door, player(SAM), "games", () -> forgot[0]++, line, scorecard),
                "back to the Clubhouse (solo_after)");
        assertEquals(1, forgot[0], "the finished round forgotten (it was recorded already)");
        assertEquals(ClubVisits.Kind.VISIT, door.in.get(SAM), "in as a visitor");
        assertEquals(line, door.lines.get(SAM), "reading how it went");
        assertEquals(scorecard, door.solo.get(SAM), "their own scorecard, for them only");
        assertEquals(1, door.solo.size(), "nobody else's");
        assertNull(door.sheet, "the board (a golf group's ranking) is never touched");
    }

    @Test
    void theRecordLineReachesTheDoorOnlyWhenTheRoundSetTheRecord() {
        Door door = new Door();
        String line = com.dierks.homecraft.games.clubhouse.ClubhouseText.newRecordLine("Sam", "Tiny Golf", "24 strokes");
        assertTrue(ClubGolf.soloTake(door, player(SAM), "games", () -> { }, "x", p -> { }, line), "back, a record");
        assertEquals(line, door.records.get(SAM), "the Clubhouse hears the record with the scorecard");
        assertTrue(ClubGolf.soloTake(door, player(SAM), "games", () -> { }, "x", p -> { }, null), "back, no record");
        assertNull(door.records.get(SAM), "nothing to celebrate");
        GolfCard card = new GolfCard("tiny", "Tiny Golf", java.util.List.of(3, 3, 3), java.util.List.of(
                new GolfRun.HoleScore(3, 8, false), new GolfRun.HoleScore(3, 8, false), new GolfRun.HoleScore(3, 8, false)),
                24, false);
        assertEquals("&6Sam set a new record on Tiny Golf: &f24 strokes", ClubGolf.recordLine(player(SAM), card, true));
        assertNull(ClubGolf.recordLine(player(SAM), card, false), "no record, no line");
    }

    @Test
    void aRoundAloneGoesHomeWithSoloAfterOffOrTheClubhouseOffOrAnotherWorld() {
        int[] forgot = {0};
        assertFalse(ClubGolf.soloTake(null, player(SAM), "games", () -> forgot[0]++, "x", p -> { }), "no Clubhouse");
        Door door = new Door();
        door.soloAfter = false;
        assertFalse(ClubGolf.soloTake(door, player(SAM), "games", () -> forgot[0]++, "x", p -> { }), "solo_after off");
        door.soloAfter = true;
        assertFalse(ClubGolf.soloTake(door, player(SAM), "other", () -> forgot[0]++, "x", p -> { }), "another world");
        assertEquals(0, forgot[0], "nothing forgotten: home as before, the scorecard there");
        assertTrue(door.in.isEmpty() && door.solo.isEmpty(), "and nothing of the Clubhouse's touched");
    }

    @Test
    void aRoundAloneThatEndsAsTheClubhouseClosesForTheRestartGoesHome() {
        Door door = new Door();
        door.closing = true;
        int[] forgot = {0};
        assertFalse(ClubGolf.soloTake(door, player(SAM), "games", () -> forgot[0]++, "x", p -> { }), "home");
        assertEquals(0, forgot[0], "untouched");
        assertTrue(door.in.isEmpty(), "nobody taken in");
    }

    @Test
    void aRoundAloneStartedInTheClubhouseIsHandedOutToHoleOne() {
        Door door = new Door();
        door.in.put(SAM, ClubVisits.Kind.VISIT);
        List<UUID> moved = new ArrayList<>();
        List<String> told = new ArrayList<>();
        Boolean went = ClubGolf.fromClubhouse(door, player(SAM), null, "tiny", tee("games"),
                (p, at) -> moved.add(p.getUniqueId()), p -> null, (p, r) -> told.add(r.message()));
        assertEquals(Boolean.TRUE, went, "a round alone from the Clubhouse: on their way to hole 1");
        assertEquals(List.of(SAM), door.handedOut, "handed out, in place");
        assertEquals(List.of(SAM), moved, "the session's own teleport to the first tee");
        door.in.put(AVA, ClubVisits.Kind.VISIT);
        Boolean failed = ClubGolf.fromClubhouse(door, player(AVA), null, "tiny", tee("games"), (p, at) -> false,
                p -> null, (p, r) -> told.add(r.message()));
        assertEquals(Boolean.FALSE, failed, "the teleport failed");
        assertEquals(List.of(AVA), door.handedBack, "handed back to the Clubhouse");
        assertEquals(ClubVisits.Kind.GOLF, door.in.get(AVA), "still in it");
        assertTrue(told.getLast().contains("hole 1"), told.toString());
    }

    @Test
    void leaveGamePartwayThroughARoundFromTheClubhouseGoesBackThere() {
        Door door = new Door();
        int[] forgot = {0};
        String line = com.dierks.homecraft.games.clubhouse.ClubhouseText.LEFT_ROUND;
        assertTrue(ClubGolf.leftToClub(door, player(SAM), "games", () -> forgot[0]++, line), "back there");
        assertEquals(1, forgot[0], "the round forgotten, never recorded");
        assertEquals(line, door.lines.get(SAM), "reading where they are");
        assertFalse(ClubGolf.leftToClub(null, player(AVA), "games", () -> forgot[0]++, line), "no Clubhouse: home");
        assertFalse(ClubGolf.leftToClub(door, player(AVA), "other", () -> forgot[0]++, line), "another world: home");
        door.closing = true;
        assertFalse(ClubGolf.leftToClub(door, player(AVA), "games", () -> forgot[0]++, line), "closing: home");
        assertEquals(1, forgot[0], "untouched when home");
    }
}
