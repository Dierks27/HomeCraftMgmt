package com.dierks.homecraft.games.event;

import com.dierks.homecraft.storage.EventDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A night settled from its stored rows after a restart (EVENTS-DROPPER-SPEC §A.9), with no server.
 * Pinned here: the completed races' points stand, ranked by points then countback exactly like a
 * night that ran to the end; the podium rule counts who started race 1 (a DNS didn't); the finisher
 * prize needs a finished race; and a night that never claimed a prize slot pays nothing.
 */
class StoredNightTest {

    private static final String ID = "rn-20261002-1900";
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    private static final NightRules RULES = new NightRules(3, 0, 2, 8, List.of(10, 8, 6, 5), 2, 1, List.of(5, 3, 2), 1,
            false, 0, 60, 4, 20);

    private static EventDao.EntryRow entry(UUID p, String name) {
        return new EventDao.EntryRow(ID, p, name, 0, EventDao.IN, 0, null, 0, null);
    }

    private static EventDao.RaceRow row(int race, UUID p, Integer place, int points, String result) {
        return new EventDao.RaceRow(ID, race, p, place, place == null ? null : 40_000L + place, 8, points, result);
    }

    @Test
    void theCompletedRacesStandAndPayAsANightThatRanToTheEnd() {
        List<EventDao.EntryRow> entries = List.of(entry(A, "Ava"), entry(B, "Ben"), entry(C, "Cal"), entry(D, "Dee"));
        List<EventDao.RaceRow> races = List.of(
                row(1, A, 1, 10, "FINISHED"), row(1, B, 2, 8, "FINISHED"), row(1, C, null, 1, "STILL_RACING"),
                row(1, D, null, 0, "DNS"),
                row(2, B, 1, 10, "FINISHED"), row(2, A, 2, 8, "FINISHED"), row(2, C, 3, 6, "FINISHED"),
                row(2, D, 4, 5, "FINISHED"));
        List<EventDao.Placed> placed = StoredNight.placed(races, entries, RULES, true);
        assertEquals(4, placed.size(), "everyone who raced has a line");
        // Ava 18 and Ben 18: level on points; countback: one 1st each, one 2nd each: they share 1st
        assertEquals(1, placed.get(0).place(), "a shared 1st");
        assertEquals(1, placed.get(1).place(), "a shared 1st");
        assertEquals(18, placed.get(0).points(), "18 points");
        assertEquals(5, placed.get(0).prize(), "1st pays 5 (3 started race 1)");
        assertEquals(5, placed.get(1).prize(), "the tie shares the place and its prize");
        EventDao.Placed cal = placed.stream().filter(p -> p.player().equals(C)).findFirst().orElseThrow();
        assertEquals(3, cal.place(), "Cal 3rd with 7 (the next place after a shared 1st)");
        assertEquals(1, cal.prize(), "3rd needs 4 starters; Cal finished race 2: the finisher's 1");
        EventDao.Placed dee = placed.stream().filter(p -> p.player().equals(D)).findFirst().orElseThrow();
        assertEquals(4, dee.place(), "Dee 4th with 5");
        assertEquals(1, dee.prize(), "Dee finished race 2: 1");
    }

    @Test
    void aNightWithoutAPrizeSlotPaysNothing() {
        List<EventDao.EntryRow> entries = List.of(entry(A, "Ava"), entry(B, "Ben"));
        List<EventDao.RaceRow> races = List.of(row(1, A, 1, 10, "FINISHED"), row(1, B, 2, 8, "FINISHED"));
        List<EventDao.Placed> placed = StoredNight.placed(races, entries, RULES, false);
        assertTrue(placed.stream().allMatch(p -> p.prize() == 0), "just for fun: points only");
        assertEquals(10, placed.get(0).points(), "the points stand");
    }

    @Test
    void theFinisherPrizeNeedsAFinishedRace() {
        List<EventDao.EntryRow> entries = List.of(entry(A, "Ava"), entry(B, "Ben"), entry(C, "Cal"));
        List<EventDao.RaceRow> races = List.of(row(1, A, 1, 10, "FINISHED"), row(1, B, 2, 8, "FINISHED"),
                row(1, C, null, 1, "STILL_RACING"));
        List<EventDao.Placed> placed = StoredNight.placed(races, entries, RULES, true);
        EventDao.Placed cal = placed.stream().filter(p -> p.player().equals(C)).findFirst().orElseThrow();
        assertEquals(3, cal.place(), "Cal 3rd with the still-racing point");
        assertEquals(0, cal.prize(), "never finished a race: no finisher prize (and 3rd needs 4 starters)");
        assertEquals(3, placed.stream().filter(p -> p.player().equals(B)).findFirst().orElseThrow().prize(),
                "Ben 2nd: 3 tokens with 3 starters");
    }
}
