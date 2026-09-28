package com.dierks.homecraft.games.cabinet.whack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whack-a-Zombie's rules and timing without a server.
 *
 * <p>Pinned here: the whole round's pops come from the seed alone (so the daily round is the same
 * for everyone, whatever they whack); Bedrock sees the very same pops for longer; a hole never
 * shows two pops at once; a zombie is +1, a villager -1 (never below zero), an empty hole
 * nothing; the round ends after its seconds; and a shipped 30-second round has enough zombies for
 * the gold milestone (35) and the daily goal (20) to be reachable.
 */
class WhackEngineTest {

    private static final int STEPS = 30 * WhackEngine.STEPS_PER_SECOND;

    @Test
    void theRoundIsWorkedOutFromTheSeedAlone() {
        assertEquals(WhackEngine.schedule(11, STEPS, 9), WhackEngine.schedule(11, STEPS, 9),
                "today's daily round is the same pops for everyone");
        assertNotEquals(WhackEngine.schedule(11, STEPS, 9), WhackEngine.schedule(12, STEPS, 9),
                "another seed pops differently");
        WhackEngine a = new WhackEngine(11, 30, false);
        WhackEngine b = new WhackEngine(11, 30, false);
        for (int t = 0; t < STEPS; t++) {
            if (t % 3 == 0) {
                for (int h = 0; h < WhackEngine.HOLES; h++) {
                    a.whack(h);
                }
            }
            a.tick();
            b.tick();
        }
        assertEquals(b.pops(), a.pops(), "whacking changes nothing about what comes next");
    }

    @Test
    void bedrockSeesTheSamePopsForLonger() {
        WhackEngine java = new WhackEngine(21, 30, false);
        WhackEngine bedrock = new WhackEngine(21, 30, true);
        assertEquals(java.pops(), bedrock.pops(), "both platforms get the same pops, holes and kinds");
        for (WhackEngine.Pop p : java.pops()) {
            assertTrue(p.bedrockWindow() > p.window(), "a Bedrock window is longer: " + p);
            assertTrue(p.window() >= 7, "a Java window is at least 0.7 s: " + p);
        }
        WhackEngine.Pop first = java.pops().get(0);
        for (int t = 0; t < first.start() + first.window(); t++) {
            java.tick();
            bedrock.tick();
        }
        assertNull(java.at(first.hole()), "the Java window has closed");
        assertEquals(first.kind(), bedrock.at(first.hole()), "while the Bedrock window is still open");
    }

    @Test
    void aHoleNeverHoldsTwoPopsAtOnce() {
        for (long seed = 0; seed < 200; seed++) {
            List<WhackEngine.Pop> pops = WhackEngine.schedule(seed, STEPS, WhackEngine.HOLES);
            for (int i = 0; i < pops.size(); i++) {
                for (int j = i + 1; j < pops.size(); j++) {
                    WhackEngine.Pop a = pops.get(i);
                    WhackEngine.Pop b = pops.get(j);
                    if (a.hole() == b.hole()) {
                        assertTrue(b.start() > a.end(true),
                                "seed " + seed + ": hole " + a.hole() + " is booked until " + a.end(true)
                                        + " but pops again at " + b.start());
                    }
                }
            }
        }
    }

    @Test
    void theFirstPopIsAZombieAndVillagersDoShowUp() {
        int villagers = 0;
        for (long seed = 0; seed < 100; seed++) {
            List<WhackEngine.Pop> pops = WhackEngine.schedule(seed, STEPS, WhackEngine.HOLES);
            assertEquals(WhackEngine.Kind.ZOMBIE, pops.get(0).kind(), "seed " + seed + ": nobody starts on a trick");
            villagers += (int) pops.stream().filter(p -> p.kind() == WhackEngine.Kind.VILLAGER).count();
        }
        assertTrue(villagers > 300, "about one pop in seven is a villager over 100 rounds, got " + villagers);
    }

    @Test
    void aZombieIsAPointAndLeavesItsHole() {
        WhackEngine e = new WhackEngine(5, 30, false);
        WhackEngine.Pop pop = firstOf(e, WhackEngine.Kind.ZOMBIE);
        advanceTo(e, pop.start());
        assertEquals(WhackEngine.Kind.ZOMBIE, e.at(pop.hole()), "the zombie is up");
        assertEquals(WhackEngine.Hit.ZOMBIE, e.whack(pop.hole()), "whack it");
        assertEquals(1, e.score(), "+1");
        assertNull(e.at(pop.hole()), "a whacked zombie goes back down at once");
        assertEquals(WhackEngine.Hit.EMPTY, e.whack(pop.hole()), "and can't be whacked twice");
        assertEquals(1, e.score(), "an empty hole scores nothing");
    }

    @Test
    void aVillagerCostsAPointButNeverBelowZero() {
        WhackEngine e = new WhackEngine(5, 30, false);
        WhackEngine.Pop villager = firstOf(e, WhackEngine.Kind.VILLAGER);
        advanceTo(e, villager.start());
        assertEquals(WhackEngine.Hit.VILLAGER, e.whack(villager.hole()), "don't bonk the villager");
        assertEquals(0, e.score(), "a villager on no points leaves the score at zero, not below");
        assertEquals(1, e.villagers(), "but it is counted");

        WhackEngine f = new WhackEngine(5, 30, false);
        for (WhackEngine.Pop p : f.pops()) {
            if (p.start() >= villager.start()) {
                break;
            }
            advanceTo(f, p.start());
            f.whack(p.hole());
        }
        advanceTo(f, villager.start());
        int before = f.score();
        f.whack(villager.hole());
        assertEquals(Math.max(0, before - 1), f.score(), "a villager takes one point away");
    }

    @Test
    void theRoundEndsAfterItsSeconds() {
        WhackEngine e = new WhackEngine(8, 12, false);
        assertEquals(12, e.secondsLeft(), "the clock starts full");
        for (int t = 0; t < 12 * WhackEngine.STEPS_PER_SECOND - 1; t++) {
            e.tick();
        }
        assertEquals(1, e.secondsLeft(), "the last tenth of a second still shows 1");
        e.tick();
        assertTrue(e.over(), "time's up");
        assertEquals(0, e.secondsLeft(), "no time left");
        for (int h = 0; h < WhackEngine.HOLES; h++) {
            assertNull(e.at(h), "nothing shows once the round is over");
            assertEquals(WhackEngine.Hit.EMPTY, e.whack(h), "and nothing scores");
        }
        e.tick();
        assertEquals(12 * WhackEngine.STEPS_PER_SECOND, e.now(), "time stops at the end");
    }

    @Test
    void aShippedRoundHasZombiesEnoughForGold() {
        for (long seed = 0; seed < 200; seed++) {
            WhackEngine e = new WhackEngine(seed, 30, false);
            for (int t = 0; t < STEPS; t++) {
                for (int h = 0; h < WhackEngine.HOLES; h++) {
                    if (e.at(h) == WhackEngine.Kind.ZOMBIE) {
                        e.whack(h);
                    }
                }
                e.tick();
            }
            assertTrue(e.score() >= 40 && e.score() <= 75,
                    "seed " + seed + ": a perfect round scores " + e.score()
                            + " — gold (35) must be reachable and the daily goal (20) well within");
            assertEquals(0, e.villagers(), "the perfect player bonked no villager");
        }
    }

    private static WhackEngine.Pop firstOf(WhackEngine e, WhackEngine.Kind kind) {
        return e.pops().stream().filter(p -> p.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " in this round"));
    }

    private static void advanceTo(WhackEngine e, int step) {
        while (e.now() < step) {
            e.tick();
        }
    }
}
