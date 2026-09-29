package com.dierks.homecraft.games.event;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hub's {@code @event} displays ({@link EventBoard}, EVENTS-DROPPER-SPEC §A.6), with no server.
 *
 * <p>Pinned here: every state's sign reads as the spec draws it; every sign line is plain ASCII of
 * at most 15 characters, however long a name or a track is; "No race set" when nothing is planned;
 * and a hologram's screen carries the racers' names with their points.
 */
class EventBoardTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final long NOW = LocalDateTime.of(2026, 10, 2, 16, 46).atZone(CHICAGO).toInstant().toEpochMilli();
    private static final long START = LocalDateTime.of(2026, 10, 2, 19, 0).atZone(CHICAGO).toInstant().toEpochMilli();
    private static final List<EventBoard.Line> STANDINGS = List.of(new EventBoard.Line(1, "Sam", 18),
            new EventBoard.Line(2, "Ava", 16), new EventBoard.Line(3, "Lee", 10), new EventBoard.Line(4, "Mia", 4));

    private static EventBoard.View view(EventBoard.Shows shows, List<EventBoard.Line> lines) {
        return new EventBoard.View(shows, NOW, START, "Ice Boat", 3, 8, 2, 3, lines, CHICAGO);
    }

    @Test
    void everyStateReadsAsTheSpecDrawsIt() {
        assertEquals(List.of("RACE NIGHT", "No race set", "Ask an admin!", ""),
                EventBoard.sign(EventBoard.View.nothing(NOW, CHICAGO)), "nothing set");
        assertEquals(List.of("RACE NIGHT", "Fri 7:00 PM", "in 2h 14m", "Ice Boat"),
                EventBoard.sign(view(EventBoard.Shows.UPCOMING, List.of())), "upcoming");
        assertEquals(List.of("JOIN NOW!", "/hcm play race", "3 of 8 in", "starts 7:00"),
                EventBoard.sign(view(EventBoard.Shows.OPEN, List.of())), "the join window");
        assertEquals(List.of("RACE 2 OF 3", "1. Sam 18", "2. Ava 16", "3. Lee 10"),
                EventBoard.sign(view(EventBoard.Shows.RACING, STANDINGS)), "racing");
        assertEquals(List.of("WINNER", "Sam", "2. Ava", "3. Lee"),
                EventBoard.sign(view(EventBoard.Shows.RESULTS, STANDINGS)), "the results");
        assertEquals("Warm-up laps", EventBoard.sign(view(EventBoard.Shows.WARMUP, List.of())).get(1), "the warm-up");
    }

    @Test
    void everySignLineIsShortPlainAscii() {
        List<EventBoard.Line> long_ = List.of(new EventBoard.Line(1, "Supercalifragilistic", 108),
                new EventBoard.Line(2, "Émile★", 96), new EventBoard.Line(3, null, 0));
        EventBoard.View racing = new EventBoard.View(EventBoard.Shows.RACING, NOW, START,
                "The Very Long Glacier Loop", 12, 12, 5, 5, long_, CHICAGO);
        for (EventBoard.Shows s : EventBoard.Shows.values()) {
            EventBoard.View v = new EventBoard.View(s, NOW, START, "The Very Long Glacier Loop", 12, 12, 5, 5, long_,
                    CHICAGO);
            List<String> lines = EventBoard.sign(v);
            assertEquals(4, lines.size(), s + ": four lines");
            for (String line : lines) {
                assertTrue(line.length() <= EventBoard.SIGN_CHARS, s + ": at most 15 characters: '" + line + "'");
                assertTrue(line.chars().allMatch(c -> c >= 32 && c < 127), s + ": plain ASCII: '" + line + "'");
            }
        }
        assertTrue(EventBoard.sign(racing).get(1).endsWith(" 108"), "a long name is cut, never the points: "
                + EventBoard.sign(racing).get(1));
    }

    @Test
    void aHologramShowsTheRacersNamesAndPoints() {
        List<String> screen = EventBoard.screen(view(EventBoard.Shows.RACING, STANDINGS));
        assertEquals("&6&lRace Night &7- race 2 of 3", screen.get(0), "a title");
        assertEquals(5, screen.size(), "and every racer (up to 8)");
        assertTrue(screen.get(1).contains("Sam") && screen.get(1).contains("18 points"), "names and points: " + screen);
        for (EventBoard.Shows s : EventBoard.Shows.values()) {
            for (String line : EventBoard.screen(view(s, STANDINGS))) {
                assertTrue(line.codePoints().allMatch(c -> c <= 0xFFFF), s + ": nothing above U+FFFF: " + line);
            }
        }
    }
}
