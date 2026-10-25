package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Spot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which launchers are in which Mega Firework Show ({@link MegaShowService.Claims}, 0.38.1): a launcher in a show
 * refuses another show that would use it (never skipping to farther free ones: the caller's {@code nearest} never
 * leaves one out), a show ends only its own claims (red-team ECON-2's shape, as Party Time), a quit ends the user's
 * shows, a stop ends them all, and at most four play at once. And the one long show a player: a Mega show holds it, so
 * a Grand show is refused while it plays, and the other way round.
 */
class MegaShowClaimsTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID DAUGHTER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static List<Spot> launchers(int x) {
        return List.of(new Spot("world", x, 64, 0), new Spot("world", x + 3, 64, 0), new Spot("world", x + 6, 64, 0));
    }

    @Test
    void aLauncherInAShowRefusesAnotherShowThatWouldUseIt() {
        MegaShowService.Claims c = new MegaShowService.Claims();
        List<Spot> three = launchers(0);
        long first = c.tryClaim(DAUGHTER, three);
        assertTrue(first != 0, "the first show takes its three");
        assertTrue(c.busy(three) && c.busy(three.get(1)));
        assertEquals(0, c.tryClaim(OWNER, three), "the same three: refused");
        assertEquals(0, c.tryClaim(OWNER, List.of(three.get(2), new Spot("world", 50, 64, 0))),
                "one of them is enough to refuse (the show never moves on to farther free ones)");
        assertFalse(c.busy(new Spot("world", 50, 64, 0)), "and the refusal claimed nothing");
        assertFalse(c.busy(new Spot("world_nether", 0, 64, 0)), "the same block in another world is another launcher");
        long other = c.tryClaim(OWNER, launchers(100));
        assertTrue(other != 0 && other != first, "launchers elsewhere are free for anyone's show");
        assertEquals(2, c.running());
        assertEquals(0, c.tryClaim(OWNER, List.of()), "no launcher: nothing to claim");
        assertEquals("Those launchers are in a show right now. Try again when it ends!",
                MegaShow.refusal(true, false, 3, c.busy(three), c.running()), "and the refusal says so");
    }

    @Test
    void aShowEndsOnlyItsOwnClaims() {
        MegaShowService.Claims c = new MegaShowService.Claims();
        List<Spot> three = launchers(0);
        long first = c.tryClaim(DAUGHTER, three);
        assertTrue(c.release(first), "its own end frees its launchers");
        assertFalse(c.busy(three));
        long second = c.tryClaim(OWNER, three);
        assertTrue(second != 0 && second != first, "the next show takes them, with its own number");
        assertFalse(c.release(first), "a late end from the first show's timer changes nothing");
        assertFalse(c.release(first), "nor every tick after it");
        assertTrue(c.busy(three) && c.on(second), "the second show still holds them");
        assertFalse(c.on(first));
        assertTrue(c.release(second));
        assertFalse(c.busy(three));
        assertFalse(c.release(0), "0 is never a show");
    }

    @Test
    void aQuitEndsTheUsersShows() {
        MegaShowService.Claims c = new MegaShowService.Claims();
        long hers = c.tryClaim(DAUGHTER, launchers(0));
        long owners = c.tryClaim(OWNER, launchers(100));
        assertEquals(List.of(hers), c.showsOf(DAUGHTER), "hers only");
        for (long show : c.showsOf(DAUGHTER)) {
            c.release(show); // what MegaShowService.onQuit does with each
        }
        assertFalse(c.busy(launchers(0)), "her launchers are free");
        assertTrue(c.busy(launchers(100)) && c.on(owners), "the owner's show plays on");
        assertEquals(List.of(), c.showsOf(DAUGHTER));
    }

    @Test
    void aStopEndsThemAll() {
        MegaShowService.Claims c = new MegaShowService.Claims();
        c.tryClaim(DAUGHTER, launchers(0));
        c.tryClaim(OWNER, launchers(100));
        c.releaseAll();
        assertEquals(0, c.running());
        assertFalse(c.busy(launchers(0)) || c.busy(launchers(100)));
        assertTrue(c.tryClaim(DAUGHTER, launchers(0)) != 0, "and after a restart they start again");
    }

    @Test
    void atMostFourAtOnce() {
        MegaShowService.Claims c = new MegaShowService.Claims();
        long[] shows = new long[MegaShow.MAX_RUNNING];
        for (int i = 0; i < MegaShow.MAX_RUNNING; i++) {
            shows[i] = c.tryClaim(UUID.randomUUID(), launchers(i * 100));
            assertTrue(shows[i] != 0, "show " + (i + 1));
        }
        assertEquals(4, c.running());
        assertEquals(0, c.tryClaim(UUID.randomUUID(), launchers(1000)), "a fifth: the sky is full");
        assertEquals("The sky is full of shows! Try again in a minute.",
                MegaShow.refusal(true, false, 3, c.busy(launchers(1000)), c.running()));
        assertFalse(c.busy(launchers(1000)), "it claimed nothing");
        assertTrue(c.release(shows[0]));
        assertTrue(c.tryClaim(UUID.randomUUID(), launchers(1000)) != 0, "once one ends, another can start");
    }

    /**
     * 0.38.1's one long show a player ({@code FireworkShows.Guard}, through {@code holdLong} and {@code releaseLong}):
     * a Mega show holds it from its start to its end, so a Grand show is refused while it plays and a Mega show while a
     * Grand one does; only the Mega show's own end frees it, and a short show is never refused.
     */
    @Test
    void theMegaShowIsThePlayersOneLongShow() {
        FireworkShows.Guard g = new FireworkShows.Guard();
        long mega = g.tryStart(DAUGHTER, true, 10_000); // FireworkShows.holdLong
        assertTrue(mega != 0);
        assertTrue(g.longOn(DAUGHTER), "a Mega show on is her long show");
        assertEquals(0, g.tryStart(DAUGHTER, FireworkShow.isLong(15), 11_000), "a Grand show is refused, and kept");
        assertTrue(g.tryStart(DAUGHTER, FireworkShow.isLong(3), 11_000) != 0, "a three-second show never waits");
        assertEquals(PrizeItemListener.SHOW_ON, MegaShow.refusal(true, g.longOn(DAUGHTER), 3, false, 1),
                "a second Mega show: 0.38.1's own line");
        g.end(DAUGHTER, mega - 1);
        assertTrue(g.longOn(DAUGHTER), "an old number frees nothing");
        g.end(DAUGHTER, mega); // FireworkShows.releaseLong, from the Mega show's end
        assertFalse(g.longOn(DAUGHTER), "its own end frees it");
        long grand = g.tryStart(DAUGHTER, true, 12_000);
        assertTrue(grand != 0, "then a Grand show plays");
        assertEquals(0, g.tryStart(DAUGHTER, true, 13_000), "and a Mega show waits for it (holdLong is 0)");
        assertFalse(g.longOn(OWNER), "each player's own");
    }
}
