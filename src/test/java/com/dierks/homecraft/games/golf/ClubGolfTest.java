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
        ClubBoard.Sheet sheet;

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
            return false;
        }

        @Override
        public void handBack(Player p, ClubVisits.Kind kind) {
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
                row(AVA, "Ava", 2, 3, 3), row(LEE, "Lee", 4, 4, 5)), true, -1);
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
}
