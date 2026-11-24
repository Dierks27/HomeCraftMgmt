package com.dierks.homecraft.games.cabinet.whack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.54.1, gentle Whack-a-Zombie (the owner: "just not controller friendly at all"): one zombie that waits. */
class GentleWhackTest {

    private static int upHole(WhackEngine e) {
        for (int h = 0; h < WhackEngine.HOLES; h++) {
            if (e.at(h) != null) {
                return h;
            }
        }
        return -1;
    }

    private static int countUp(WhackEngine e) {
        int n = 0;
        for (int h = 0; h < WhackEngine.HOLES; h++) {
            n += e.at(h) == null ? 0 : 1;
        }
        return n;
    }

    @Test
    void oneZombieAtATimeNeverAVillagerAndItWaits() {
        WhackEngine e = WhackEngine.gentle(42, 30);
        assertTrue(e.isGentle());
        for (int i = 0; i < WhackEngine.START; i++) {
            e.tick();
        }
        int h = upHole(e);
        assertTrue(h >= 0, "the first zombie is up a second in");
        for (int i = 0; i < WhackEngine.GENTLE_WINDOW - 1; i++) {
            e.tick();
            assertEquals(h, upHole(e), "it stays where it is for five seconds");
            assertEquals(1, countUp(e), "and only one at a time");
            assertEquals(WhackEngine.Kind.ZOMBIE, e.at(h), "never a villager");
        }
    }

    @Test
    void aWhackScoresAndTheNextComesSoonSomewhereElse() {
        WhackEngine e = WhackEngine.gentle(7, 30);
        for (int i = 0; i < WhackEngine.START; i++) {
            e.tick();
        }
        int h = upHole(e);
        assertEquals(WhackEngine.Hit.EMPTY, e.whack((h + 1) % WhackEngine.HOLES), "an empty hole does nothing");
        assertEquals(WhackEngine.Hit.ZOMBIE, e.whack(h));
        assertEquals(1, e.score());
        assertNull(e.at(h), "it goes at once");
        for (int i = 0; i < WhackEngine.GENTLE_GAP; i++) {
            e.tick();
        }
        int next = upHole(e);
        assertTrue(next >= 0, "the next one comes a moment later");
        assertNotEquals(h, next, "never the same hole twice in a row");
    }

    @Test
    void aMissedZombieGoesAfterItsWaitAndTheScoreNeverDrops() {
        WhackEngine e = WhackEngine.gentle(3, 30);
        for (int i = 0; i < WhackEngine.START + WhackEngine.GENTLE_WINDOW; i++) {
            e.tick();
        }
        assertEquals(0, countUp(e), "gone after its wait");
        assertEquals(0, e.score());
        while (!e.over()) {
            e.tick();
            int h = upHole(e);
            if (h >= 0) {
                e.whack(h);
            }
        }
        assertTrue(e.score() > 20, "whacked straight away, a 30 s round is plenty of zombies: " + e.score());
        assertNull(e.at(0));
        assertNotNull(e.pops());
    }
}
