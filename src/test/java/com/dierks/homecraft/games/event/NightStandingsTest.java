package com.dierks.homecraft.games.event;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Race Night's points and standings ({@link NightStandings}, EVENTS-DROPPER-SPEC §A.4.5), with no
 * server.
 *
 * <p>Pinned here: the points table (10, 8, 6, 5, 4, 3, 2, then 2 for any finisher beyond it);
 * still racing at the end earns 1; left, voided and not seated earn 0 and take no place (the places
 * close up); the night's order is points then countback (more 1sts, then more 2nds); racers still
 * level share a place; and the grid puts the fewest points at the front, level racers by who joined
 * first.
 */
class NightStandingsTest {

    private static final List<Integer> POINTS = List.of(10, 8, 6, 5, 4, 3, 2);
    private final UUID sam = UUID.randomUUID();
    private final UUID ava = UUID.randomUUID();
    private final UUID lee = UUID.randomUUID();
    private final UUID mia = UUID.randomUUID();
    private final UUID max = UUID.randomUUID();

    private static NightStandings.Row fin(UUID p, long ms) {
        return new NightStandings.Row(p, NightStandings.Result.FINISHED, ms, 10);
    }

    @Test
    void finishersScoreByPlaceAndBeyondTheListScoreTheFinishPoints() {
        List<NightStandings.Row> rows = new java.util.ArrayList<>();
        UUID[] who = new UUID[9];
        for (int i = 0; i < who.length; i++) {
            who[i] = UUID.randomUUID();
            rows.add(fin(who[i], 40_000 + i * 1000L));
        }
        List<NightStandings.Scored> s = NightStandings.score(rows, POINTS, 2, 1);
        int[] want = {10, 8, 6, 5, 4, 3, 2, 2, 2};
        for (int i = 0; i < want.length; i++) {
            assertEquals(i + 1, s.get(i).place(), "places in finish order");
            assertEquals(want[i], s.get(i).points(), "place " + (i + 1) + " scores " + want[i]);
        }
    }

    @Test
    void stillRacingEarnsAPointAndLeftVoidAndDnsEarnNothingAndTakeNoPlace() {
        List<NightStandings.Scored> s = NightStandings.score(List.of(
                new NightStandings.Row(sam, NightStandings.Result.VOID, 30_000, 10),
                fin(ava, 41_000),
                new NightStandings.Row(lee, NightStandings.Result.STILL_RACING, 0, 6),
                new NightStandings.Row(mia, NightStandings.Result.LEFT, 0, 3),
                fin(max, 44_000),
                new NightStandings.Row(UUID.randomUUID(), NightStandings.Result.DNS, 0, 0)), POINTS, 2, 1);
        assertEquals(ava, s.get(0).player(), "the voided faster time takes no place: Ava is 1st");
        assertEquals(10, s.get(0).points(), "and scores 1st place's points");
        assertEquals(max, s.get(1).player(), "the places close up: Max is 2nd");
        assertEquals(8, s.get(1).points(), "with 2nd's points");
        NightStandings.Scored still = s.stream().filter(x -> x.player().equals(lee)).findFirst().orElseThrow();
        assertEquals(1, still.points(), "still racing at the end: a point, great racing");
        assertNull(still.place(), "but no place");
        for (NightStandings.Scored x : s) {
            if (x.result() == NightStandings.Result.VOID || x.result() == NightStandings.Result.LEFT
                    || x.result() == NightStandings.Result.DNS) {
                assertEquals(0, x.points(), x.result() + " scores nothing");
                assertNull(x.place(), x.result() + " takes no place");
            }
        }
    }

    @Test
    void theSameMillisecondSharesThePlaceAndThePoints() {
        List<NightStandings.Scored> s = NightStandings.score(List.of(fin(sam, 40_000), fin(ava, 40_000),
                fin(lee, 41_000)), POINTS, 2, 1);
        assertEquals(1, s.get(0).place(), "a dead heat: both 1st");
        assertEquals(1, s.get(1).place(), "both 1st");
        assertEquals(10, s.get(1).points(), "both with 1st's points");
        assertEquals(3, s.get(2).place(), "and the next place is skipped");
        assertEquals(6, s.get(2).points(), "3rd's points");
    }

    @Test
    void theNightIsPointsThenCountbackThenSharedPlaces() {
        Map<UUID, Integer> points = new LinkedHashMap<>();
        points.put(sam, 26);
        points.put(ava, 26);
        points.put(lee, 18);
        points.put(mia, 18);
        Map<UUID, List<Integer>> places = Map.of(
                sam, List.of(2, 2, 1), // one 1st
                ava, List.of(1, 1, 5), // two 1sts: ahead on countback
                lee, List.of(3, 3, 3),
                mia, List.of(3, 3, 3));
        List<NightStandings.Ranked> r = NightStandings.rank(points, places);
        assertEquals(ava, r.get(0).player(), "level on points, Ava has more 1st places");
        assertEquals(1, r.get(0).place(), "1st");
        assertEquals(sam, r.get(1).player(), "Sam 2nd on countback");
        assertEquals(2, r.get(1).place(), "2nd, not shared");
        assertEquals(3, r.get(2).place(), "Lee and Mia are level on points AND countback: they share 3rd");
        assertEquals(3, r.get(3).place(), "both 3rd");
        assertEquals(lee, r.get(2).player(), "shown in the order they joined");
    }

    @Test
    void theGridPutsTheFewestPointsInFrontAndLevelRacersByWhoJoinedFirst() {
        List<UUID> joined = List.of(sam, ava, lee, mia);
        List<UUID> grid = NightStandings.grid(joined, Map.of(sam, 20L, ava, 8L, lee, 8L, mia, 30L));
        assertEquals(List.of(ava, lee, sam, mia), grid,
                "fewest points on pole; Ava and Lee level, Ava joined first; the leader starts at the back");
        assertEquals(joined, NightStandings.grid(joined, Map.of()), "nobody has points: the order they joined");
    }

    @Test
    void placesReadTheWayKidsSayThem() {
        assertEquals("1st", NightStandings.ordinal(1), "1st");
        assertEquals("2nd", NightStandings.ordinal(2), "2nd");
        assertEquals("3rd", NightStandings.ordinal(3), "3rd");
        assertEquals("4th", NightStandings.ordinal(4), "4th");
        assertEquals("11th", NightStandings.ordinal(11), "11th, not 11st");
        assertEquals("12th", NightStandings.ordinal(12), "12th, not 12nd");
        assertEquals("21st", NightStandings.ordinal(21), "21st");
    }
}
