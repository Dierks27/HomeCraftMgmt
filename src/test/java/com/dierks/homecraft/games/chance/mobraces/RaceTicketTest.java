package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.games.ExitSettler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entry's round data and the exit rule, which finishes a round from that data alone: with no race drawn the stake
 * comes back whole; with a race drawn it pays exactly what that race pays the pick; anything that isn't an entry's
 * data is refused.
 */
class RaceTicketTest {

    private static final ExitSettler RULE = MobRaces.SPEC.settler();

    @Test
    void anEntryReadsBackAsItWasWritten() {
        RaceTicket t = RaceTicket.entry(3, 5, 27);
        assertEquals("v=1;pick=3;in=5;pays=27", t.data());
        assertEquals(t, RaceTicket.read(t.data()));
        assertFalse(t.drawn());
        RaceTicket in = t.in(-123456789L);
        assertEquals("v=1;pick=3;in=5;pays=27;race=-123456789", in.data());
        assertEquals(in, RaceTicket.read(in.data()));
        assertTrue(in.drawn());
        assertEquals(-123456789L, in.race());
        assertFalse(t.data().contains("race"), "nothing about the result exists before the race is drawn");
    }

    @Test
    void withNoRaceDrawnTheStakeComesBackWhole() {
        for (int pick = 0; pick < Racer.COUNT; pick++) {
            for (int[] p : new int[][]{{5, 27}, {10, 54}, {20, 108}, {50, 270}}) {
                RaceTicket t = RaceTicket.entry(pick, p[0], p[1]);
                assertEquals(p[0], t.payout(), "nothing is decided: what went in comes back");
                assertFalse(t.won());
                for (long roundSeed : new long[]{0, 1, 99, -4}) {
                    assertEquals(p[0], RULE.payoutOnExit(roundSeed, p[0], t.data()), "whatever the round's own seed");
                }
            }
        }
    }

    @Test
    void withARaceDrawnItPaysExactlyWhatTheRacePaysThePick() {
        int wins = 0;
        for (long race = 0; race < 600; race++) {
            int winner = RaceScript.winner(race);
            assertEquals(winner, RaceScript.of(race, 200).winner(), "the race the room watches has that winner");
            for (int pick = 0; pick < Racer.COUNT; pick++) {
                RaceTicket t = RaceTicket.entry(pick, 10, 54).in(race);
                int expected = pick == winner ? 54 : 0;
                assertEquals(expected, t.payout());
                assertEquals(pick == winner, t.won());
                // the round's own seed plays no part: the race's seed is in the data
                assertEquals(expected, RULE.payoutOnExit(race + 17, 10, t.data()));
                assertEquals(expected, RULE.payoutOnExit(0, 10, t.data()));
                wins += pick == winner ? 1 : 0;
            }
        }
        assertEquals(600, wins, "exactly one pick in six is paid each race");
    }

    @Test
    void nonsenseIsRefused() {
        for (String bad : new String[]{null, "", "   ", "hello", "v=1", "v=1;pick=3;in=5", "v=2;pick=3;in=5;pays=27",
                "v=1;pick=6;in=5;pays=27", "v=1;pick=-1;in=5;pays=27", "v=1;pick=x;in=5;pays=27",
                "v=1;pick=3;in=0;pays=27", "v=1;pick=3;in=5;pays=-1", "v=1;pick=3;in=5;pays=27;race=soon",
                "v=1;pick=3;in=5;pays=27;extra=1", "pick=3;in=5;pays=27", "v=1;pick=3;in=5;pays=27;",
                "v=1;;pick=3;in=5;pays=27", "v=1,pick=3,in=5,pays=27"}) {
            assertThrows(IllegalArgumentException.class, () -> RULE.payoutOnExit(1L, 5, bad),
                    "refused, so the framework gives the stake back: " + bad);
        }
        // data that disagrees with what the round holds is refused too
        String five = RaceTicket.entry(3, 5, 27).data();
        assertThrows(IllegalArgumentException.class, () -> RULE.payoutOnExit(1L, 50, five));
        assertEquals(5, RULE.payoutOnExit(1L, 5, five));
        assertThrows(IllegalArgumentException.class, () -> RaceTicket.entry(6, 5, 27));
        assertThrows(IllegalArgumentException.class, () -> RaceTicket.entry(0, 0, 27));
    }
}
