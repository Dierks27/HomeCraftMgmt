package com.dierks.homecraft.games.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who hears Race Night in chat ({@link Announcer}, EVENTS-DROPPER-SPEC §A.6), with no server.
 *
 * <p>Pinned here: at most 4 lines a night to a player who hasn't joined (and a new night starts the
 * count again); news off is honoured; nobody in a world game gets chat; nobody outside the games'
 * worlds gets chat; and racers never get the broadcast lines (the night tells them itself).
 */
class AnnouncerTest {

    private static final Announcer.Who LISTENER = new Announcer.Who(true, true, false, false);

    @Test
    void atMostFourLinesANightToANonRacer() {
        Announcer a = new Announcer();
        UUID p = UUID.randomUUID();
        int heard = 0;
        for (int i = 0; i < 3; i++) {
            for (Announcer.Line line : Announcer.Line.values()) {
                if (a.tell("rn-20261002-1900", p, line, LISTENER)) {
                    heard++;
                }
            }
        }
        assertEquals(Announcer.MAX_LINES, heard, "however many lines go out, a player hears 4 a night");
        assertEquals(4, a.heard(p), "counted");
        assertTrue(a.tell("rn-20261009-1900", p, Announcer.Line.HEADS_UP, LISTENER),
                "next week's night starts the count again");
    }

    @Test
    void newsOffIsHonoured() {
        assertFalse(Announcer.hears(Announcer.Line.HEADS_UP, new Announcer.Who(false, true, false, false), 0),
                "/hcm play news off silences Race Night too");
        assertTrue(Announcer.hears(Announcer.Line.HEADS_UP, LISTENER, 0), "news on: they hear it");
    }

    @Test
    void nobodyMidSessionOrOutsideTheGamesWorldsGetsChat() {
        for (Announcer.Line line : Announcer.Line.values()) {
            assertFalse(Announcer.hears(line, new Announcer.Who(true, true, true, false), 0),
                    "someone mid-course never gets " + line);
            assertFalse(Announcer.hears(line, new Announcer.Who(true, false, false, false), 0),
                    "someone in a world without games never gets " + line);
            assertFalse(Announcer.hears(line, new Announcer.Who(true, true, false, true), 0),
                    "a racer never gets the broadcast " + line + " (the night tells them)");
        }
    }
}
