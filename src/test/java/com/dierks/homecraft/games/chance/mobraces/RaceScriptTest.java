package com.dierks.homecraft.games.chance.mobraces;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Mob Race as written before it is run: the same seed is the same race; everyone starts at the line, only ever
 * moves forward and finishes, in the script's order, the winner clear of the second; the winner is the card's draw;
 * the stats lean on the show a little (a quick starter is up early, a stayer comes on late); and a race is worth
 * watching (the lead changes hands, and the winner is behind someone on the way).
 */
class RaceScriptTest {

    /** The shipped race: 25 seconds. */
    private static final int TICKS = MobRacesSettings.defaults().raceTicks();

    /** Each seed's race is run on a card of its own (a different one for every seed). */
    private static int[] card(long seed) {
        return RaceCard.of(seed * 7919 + 3).parts();
    }

    private static RaceScript race(long seed, int ticks) {
        return RaceScript.of(seed, ticks, card(seed));
    }

    @Test
    void theSameSeedIsTheSameRace() {
        for (long seed : new long[]{0, 1, -7, 42, Long.MAX_VALUE, Long.MIN_VALUE}) {
            RaceScript a = race(seed, TICKS);
            RaceScript b = race(seed, TICKS);
            assertEquals(seed, a.seed());
            assertEquals(TICKS, a.ticks(), "the winner is home on the race's last tick");
            for (int racer = 0; racer < Racer.COUNT; racer++) {
                assertEquals(a.finishTick(racer), b.finishTick(racer));
                assertEquals(a.placeOf(racer), b.placeOf(racer));
                for (int t = 0; t <= a.lastTick() + 40; t++) {
                    assertEquals(a.at(racer, t), b.at(racer, t), 0.0, "seed " + seed + " racer " + racer + " tick " + t);
                    assertEquals(a.beyond(racer, t), b.beyond(racer, t), 0.0);
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> RaceScript.of(1, 99, card(1)), "a race has a length");
        assertThrows(IllegalArgumentException.class, () -> RaceScript.of(1, 200, new int[]{1, 1, 1, 1, 1, 1}),
                "and chances that add up");
    }

    @Test
    void everyoneStartsAtTheLineOnlyMovesForwardAndFinishesInOrder() {
        for (int ticks : new int[]{MobRacesSettings.RACE_MIN * 20, TICKS, MobRacesSettings.RACE_MAX * 20}) {
            for (long seed = 0; seed < 150; seed++) {
                RaceScript s = race(seed, ticks);
                String what = "seed " + seed + ", " + ticks + " ticks";
                assertEquals(RaceScript.winner(seed, card(seed)), s.winner(), what + ": the winner is the seed's one"
                        + " draw over the card");
                assertEquals(s.winner(), s.finisher(0));
                assertEquals(ticks, s.finishTick(s.winner()), what + ": the winner finishes on the race's tick");
                Set<Integer> seen = new HashSet<>();
                for (int place = 0; place < Racer.COUNT; place++) {
                    int racer = s.finisher(place);
                    assertTrue(seen.add(racer), what + ": every racer has one place");
                    assertEquals(place, s.placeOf(racer));
                    if (place > 0) {
                        int gap = s.finishTick(racer) - s.finishTick(s.finisher(place - 1));
                        assertTrue(gap >= (place == 1 ? RaceScript.WIN_GAP_MIN : RaceScript.GAP_MIN), what + ": place "
                                + place + " comes home " + gap + " ticks after the one before");
                        assertTrue(gap <= RaceScript.WIN_GAP_MAX, what + ": and not long after");
                    }
                }
                assertEquals(s.finishTick(s.finisher(Racer.COUNT - 1)), s.lastTick());
                for (int racer = 0; racer < Racer.COUNT; racer++) {
                    int end = s.finishTick(racer);
                    assertEquals(0.0, s.at(racer, 0), 0.0, what + ": everyone starts at the line");
                    assertEquals(0.0, s.at(racer, -5), 0.0);
                    for (int t = 1; t <= s.lastTick() + 60; t++) {
                        double before = s.at(racer, t - 1);
                        double now = s.at(racer, t);
                        assertTrue(now >= before, what + ": racer " + racer + " never goes back at tick " + t);
                        if (t <= end) {
                            assertTrue(now > before, what + ": racer " + racer + " never stands still before it"
                                    + " finishes (tick " + t + ")");
                        }
                        assertEquals(t >= end, now == 1.0, what + ": racer " + racer + " is home from tick " + end
                                + " and not before (tick " + t + ": " + now + ")");
                        assertTrue(s.beyond(racer, t) >= s.beyond(racer, t - 1), "it only runs on, never back");
                        assertEquals(t <= end, s.beyond(racer, t) == 0.0, "and only once it has finished");
                    }
                    assertEquals(RaceScript.RUN_ON * (Racer.COUNT - s.placeOf(racer)),
                            s.beyond(racer, end + RaceScript.SLOW_TICKS + 5), 1e-12, "it comes to rest in its place");
                }
                // when the winner crosses the line, nobody else has
                for (int racer = 0; racer < Racer.COUNT; racer++) {
                    if (racer != s.winner()) {
                        assertTrue(s.at(racer, ticks) < 1.0, what + ": only the winner is home at the finish");
                    }
                }
            }
        }
    }

    /** The winner wins by a margin the room can see: the second is well short of the line when the winner crosses it. */
    @Test
    void theWinnerIsClearOfTheSecondAtTheLine() {
        MobRacesSettings shipped = MobRacesSettings.defaults();
        double length = shipped.laps() * RaceTrack.LAP;
        double least = Double.MAX_VALUE;
        for (long seed = 0; seed < 400; seed++) {
            RaceScript s = race(seed, TICKS);
            double gap = (1.0 - s.at(s.finisher(1), TICKS)) * length;
            least = Math.min(least, gap);
        }
        assertTrue(least >= 0.4, "the second is at least 0.4 of a block behind on a track 8 blocks long: " + least);
    }

    /** The script's winner is the card's draw, whatever the race's length; and over many races, at its chance. */
    @Test
    void theWinnerIsTheCardsDraw() {
        int[] parts = RaceCard.of(42).parts();
        int races = 6_000;
        int[] wins = new int[Racer.COUNT];
        for (long seed = 0; seed < races; seed++) {
            RaceScript s = RaceScript.of(seed, 200, parts);
            assertEquals(RaceCard.winner(seed, parts), s.winner());
            wins[s.winner()]++;
        }
        for (int i = 0; i < Racer.COUNT; i++) {
            double p = parts[i] / (double) RaceCard.WHOLE;
            assertEquals(races * p, wins[i], 5 * Math.sqrt(races * p * (1 - p)), Racer.of(i) + " at " + p);
        }
        for (long seed = 0; seed < 300; seed++) {
            assertEquals(RaceScript.winner(seed, card(seed)), race(seed, TICKS).winner());
        }
    }

    /**
     * The stats lean on the show, a little: over many races on even chances, the Chicken (Start 5) is further on
     * early than the Cow (Start 2), and the Cow (Stamina 5) runs more of its race in the last third than the Rabbit
     * (Stamina 1).
     */
    @Test
    void theStatsLeanOnTheShow() {
        int[] even = {1667, 1667, 1667, 1667, 1666, 1666};
        double chickenEarly = 0;
        double cowEarly = 0;
        double cowLate = 0;
        double rabbitLate = 0;
        int races = 1_500;
        for (long seed = 0; seed < races; seed++) {
            RaceScript s = RaceScript.of(seed, TICKS, even);
            chickenEarly += s.at(Racer.CHICKEN.lane(), s.finishTick(Racer.CHICKEN.lane()) / 5);
            cowEarly += s.at(Racer.COW.lane(), s.finishTick(Racer.COW.lane()) / 5);
            cowLate += 1 - s.at(Racer.COW.lane(), s.finishTick(Racer.COW.lane()) * 2 / 3);
            rabbitLate += 1 - s.at(Racer.RABBIT.lane(), s.finishTick(Racer.RABBIT.lane()) * 2 / 3);
        }
        assertTrue(chickenEarly > cowEarly, "a quick starter is up early: " + chickenEarly / races + " vs "
                + cowEarly / races);
        assertTrue(cowLate > rabbitLate, "a stayer comes on late: " + cowLate / races + " vs " + rabbitLate / races);
    }

    /** Every place behind the winner goes to every racer too: the order isn't the lanes'. */
    @Test
    void theOrderBehindTheWinnerIsMixed() {
        boolean[][] took = new boolean[Racer.COUNT][Racer.COUNT];
        for (long seed = 0; seed < 600; seed++) {
            RaceScript s = race(seed, 200);
            for (int place = 0; place < Racer.COUNT; place++) {
                took[place][s.finisher(place)] = true;
            }
        }
        for (int place = 0; place < Racer.COUNT; place++) {
            for (int racer = 0; racer < Racer.COUNT; racer++) {
                assertTrue(took[place][racer], Racer.of(racer) + " finishes in place " + (place + 1) + " sometimes");
            }
        }
    }

    /**
     * A race is worth watching: the lead changes hands at least three times before the winner is home, and a third of
     * the way in the winner is behind someone, so it never leads from the line to the finish.
     */
    @Test
    void theLeadChangesHandsAndTheWinnerComesFromBehind() {
        long total = 0;
        int races = 0;
        for (int ticks : new int[]{MobRacesSettings.RACE_MIN * 20, TICKS, MobRacesSettings.RACE_MAX * 20}) {
            for (long seed = 0; seed < 400; seed++) {
                RaceScript s = race(seed, ticks);
                String what = "seed " + seed + ", " + ticks + " ticks";
                // counted here from the positions themselves, not taken from the script's own word
                int changes = 0;
                boolean winnerBehind = false;
                int lead = s.leaderAt(RaceScript.COUNT_FROM);
                for (int t = RaceScript.COUNT_FROM; t <= ticks; t++) {
                    int now = leader(s, t);
                    assertEquals(now, s.leaderAt(t), what);
                    if (now != lead) {
                        changes++;
                        lead = now;
                    }
                    winnerBehind |= now != s.winner();
                }
                assertEquals(changes, s.leadChanges(), what);
                assertTrue(changes >= RaceScript.LEAD_CHANGES, what + ": the lead changed " + changes + " times");
                assertTrue(winnerBehind, what + ": the winner does not lead all the way");
                assertTrue(leader(s, ticks / 3) != s.winner(), what + ": a third of the way in, someone else leads");
                assertEquals(s.winner(), leader(s, ticks), what + ": and the winner leads at the finish");
                total += changes;
                races++;
            }
        }
        assertTrue(total / (double) races >= 4, "a typical race has several lead changes: " + total / (double) races);
    }

    private static int leader(RaceScript s, int tick) {
        int lead = 0;
        for (int racer = 1; racer < Racer.COUNT; racer++) {
            if (s.at(racer, tick) > s.at(lead, tick)) {
                lead = racer;
            }
        }
        return lead;
    }
}
