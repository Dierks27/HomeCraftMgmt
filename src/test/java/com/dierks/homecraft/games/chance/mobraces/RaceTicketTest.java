package com.dierks.homecraft.games.chance.mobraces;

import com.dierks.homecraft.games.ExitSettler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entry's round data and the exit rule, which finishes a round from that data alone: with no race drawn the stake
 * comes back whole; with a race drawn it pays exactly what that race pays the pick, the winner drawn from the race's
 * seed with the chances written in the data; an old entry (every racer as likely, before races had cards) by the old
 * rule; anything that isn't an entry's data is refused.
 */
class RaceTicketTest {

    private static final ExitSettler RULE = MobRaces.SPEC.settler();
    private static final int[] CARD = {2104, 1180, 2933, 612, 2571, 600};

    @Test
    void anEntryReadsBackAsItWasWritten() {
        RaceTicket t = RaceTicket.entry(3, 5, 74, CARD);
        assertEquals("v=2;pick=3;in=5;pays=74;card=2104,1180,2933,612,2571,600", t.data());
        assertEquals(t, RaceTicket.read(t.data()));
        assertFalse(t.drawn());
        assertEquals(-1, t.winner());
        RaceTicket in = t.in(-123456789L);
        assertEquals("v=2;pick=3;in=5;pays=74;card=2104,1180,2933,612,2571,600;race=-123456789", in.data());
        assertEquals(in, RaceTicket.read(in.data()));
        assertTrue(in.drawn());
        assertEquals(-123456789L, in.race());
        assertArrayEquals(CARD, in.card());
        assertFalse(t.data().contains("race"), "nothing about the result exists before the race is drawn");
        // the chances are its own copy
        int[] mine = CARD.clone();
        RaceTicket kept = RaceTicket.entry(0, 5, 20, mine);
        mine[0] = 0;
        assertArrayEquals(CARD, kept.card());
        assertThrows(IllegalArgumentException.class, () -> RaceTicket.entry(0, 5, 20, null), "a new entry has a card");
    }

    @Test
    void withNoRaceDrawnTheStakeComesBackWhole() {
        for (int pick = 0; pick < Racer.COUNT; pick++) {
            for (int[] p : new int[][]{{5, 21}, {10, 76}, {20, 45}, {50, 270}}) {
                for (RaceTicket t : new RaceTicket[]{RaceTicket.entry(pick, p[0], p[1], CARD),
                        RaceTicket.even(pick, p[0], p[1])}) {
                    assertEquals(p[0], t.payout(), "nothing is decided: what went in comes back");
                    assertFalse(t.won());
                    for (long roundSeed : new long[]{0, 1, 99, -4}) {
                        assertEquals(p[0], RULE.payoutOnExit(roundSeed, p[0], t.data()), "whatever the round's own"
                                + " seed: " + t.data());
                    }
                }
            }
        }
    }

    /** A new entry: the winner is the race seed's draw over the card written in the data. */
    @Test
    void withARaceDrawnItPaysExactlyWhatTheRacePaysThePick() {
        int[] won = new int[Racer.COUNT];
        int races = 20_000;
        for (long race = 0; race < races; race++) {
            int winner = RaceCard.winner(race, CARD);
            won[winner]++;
            if (race < 600) {
                assertEquals(winner, RaceScript.of(race, 200, CARD).winner(), "the race the room watches has that"
                        + " winner");
            }
            for (int pick = 0; pick < Racer.COUNT; pick++) {
                RaceTicket t = RaceTicket.entry(pick, 10, 40, CARD).in(race);
                int expected = pick == winner ? 40 : 0;
                assertEquals(expected, t.payout());
                assertEquals(pick == winner, t.won());
                // the round's own seed plays no part: the race's seed and the card are in the data
                assertEquals(expected, RULE.payoutOnExit(race + 17, 10, t.data()));
                assertEquals(expected, RULE.payoutOnExit(0, 10, t.data()));
            }
        }
        for (int i = 0; i < Racer.COUNT; i++) {
            double p = CARD[i] / (double) RaceCard.WHOLE;
            assertEquals(races * p, won[i], 5 * Math.sqrt(races * p * (1 - p)), Racer.of(i) + " wins at its chance");
        }
    }

    /** An old entry ({@code v=1}) still open after the upgrade: every racer as likely, and the pay it was written with. */
    @Test
    void anOldEntryIsSettledByTheOldRule() {
        String old = "v=1;pick=3;in=5;pays=27";
        RaceTicket t = RaceTicket.read(old);
        assertNull(t.card(), "no card: every racer as likely");
        assertEquals(old, t.data(), "it reads back as it was written");
        assertEquals(5, RULE.payoutOnExit(1L, 5, old), "no race drawn: the stake back");
        int wins = 0;
        for (long race = 0; race < 600; race++) {
            String drawn = old + ";race=" + race;
            int expected = RaceScript.evenWinner(race) == 3 ? 27 : 0;
            assertEquals(expected, RULE.payoutOnExit(race, 5, drawn), drawn);
            assertEquals(expected, RaceTicket.read(drawn).payout());
            wins += expected > 0 ? 1 : 0;
        }
        assertTrue(wins > 60 && wins < 140, "about one race in six: " + wins);
        // the old draw is the seed's nextInt(6), exactly as it was
        for (long race = 0; race < 200; race++) {
            assertEquals(new java.util.SplittableRandom(race).nextInt(6), RaceScript.evenWinner(race));
        }
    }

    @Test
    void nonsenseIsRefused() {
        for (String bad : new String[]{null, "", "   ", "hello", "v=1", "v=1;pick=3;in=5", "v=3;pick=3;in=5;pays=27",
                "v=1;pick=6;in=5;pays=27", "v=1;pick=-1;in=5;pays=27", "v=1;pick=x;in=5;pays=27",
                "v=1;pick=3;in=0;pays=27", "v=1;pick=3;in=5;pays=-1", "v=1;pick=3;in=5;pays=27;race=soon",
                "v=1;pick=3;in=5;pays=27;extra=1", "pick=3;in=5;pays=27", "v=1;pick=3;in=5;pays=27;",
                "v=1;;pick=3;in=5;pays=27", "v=1,pick=3,in=5,pays=27",
                "v=2;pick=3;in=5;pays=27", "v=1;pick=3;in=5;pays=27;card=2104,1180,2933,612,2571,600",
                "v=2;pick=3;in=5;pays=27;card=2104,1180,2933,612,2571", "v=2;pick=3;in=5;pays=27;card=1,1,1,1,1,1",
                "v=2;pick=3;in=5;pays=27;card=0,1180,2933,612,2571,2704", "v=2;pick=3;in=5;pays=27;card=a,b,c,d,e,f",
                "v=2;pick=3;in=5;pays=27;card=2104,1180,2933,612,2571,600,0"}) {
            assertThrows(IllegalArgumentException.class, () -> RULE.payoutOnExit(1L, 5, bad),
                    "refused, so the framework gives the stake back: " + bad);
        }
        // data that disagrees with what the round holds is refused too
        String five = RaceTicket.entry(3, 5, 27, CARD).data();
        assertThrows(IllegalArgumentException.class, () -> RULE.payoutOnExit(1L, 50, five));
        assertEquals(5, RULE.payoutOnExit(1L, 5, five));
        assertThrows(IllegalArgumentException.class, () -> RaceTicket.entry(6, 5, 27, CARD));
        assertThrows(IllegalArgumentException.class, () -> RaceTicket.entry(0, 0, 27, CARD));
    }
}
