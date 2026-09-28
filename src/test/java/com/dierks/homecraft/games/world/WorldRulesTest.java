package com.dierks.homecraft.games.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The small rules around world sessions: who may start ("Stand still and safe", R2.9), who may
 * change a Games world (read-only for everyone but admins while games are on, R2.14), and how
 * {@code /hcm games saved} reads its words whichever way the command hands them over.
 */
class WorldRulesTest {

    private static SessionCore.Standing standing(boolean dead, boolean sleeping, boolean gliding, boolean riding,
                                                 boolean ownBoat, boolean otherScreen, float fall, boolean onGround,
                                                 int fire, boolean lava, boolean water, long sinceHurt) {
        return new SessionCore.Standing(dead, sleeping, gliding, riding, ownBoat, otherScreen, fall, onGround, fire,
                lava, water, sinceHurt);
    }

    @Test
    void onlyAStillSafePlayerMayStart() {
        assertNull(SessionCore.refusal(SessionCore.Standing.still()), "standing still on the ground: go");
        assertEquals(SessionCore.SAFE, SessionCore.refusal(standing(true, false, false, false, false, false, 0, true, 0,
                false, false, Long.MAX_VALUE)), "dead");
        assertEquals("Get out of bed first.", SessionCore.refusal(standing(false, true, false, false, false, false, 0,
                true, 0, false, false, Long.MAX_VALUE)), "asleep");
        assertEquals("Land first.", SessionCore.refusal(standing(false, false, true, false, false, false, 0, false, 0,
                false, false, Long.MAX_VALUE)), "gliding");
        assertEquals("Get off first.", SessionCore.refusal(standing(false, false, false, true, false, false, 0, true, 0,
                false, false, Long.MAX_VALUE)), "riding something");
        assertNull(SessionCore.refusal(standing(false, false, false, true, true, false, 0, false, 0, false, false,
                Long.MAX_VALUE)), "seated in the game's own boat is fine, even off the ground");
        assertEquals("Close what you have open first.", SessionCore.refusal(standing(false, false, false, false, false,
                true, 0, true, 0, false, false, Long.MAX_VALUE)), "another plugin's inventory open");
        assertNull(SessionCore.refusal(standing(false, false, false, false, false, false, 3f, true, 0, false, false,
                Long.MAX_VALUE)), "a fall of exactly 3 is still standing");
        for (SessionCore.Standing unsafe : List.of(
                standing(false, false, false, false, false, false, 3.5f, true, 0, false, false, Long.MAX_VALUE),
                standing(false, false, false, false, false, false, 0, false, 0, false, false, Long.MAX_VALUE),
                standing(false, false, false, false, false, false, 0, true, 1, false, false, Long.MAX_VALUE),
                standing(false, false, false, false, false, false, 0, true, 0, true, false, Long.MAX_VALUE),
                standing(false, false, false, false, false, false, 0, true, 0, false, true, Long.MAX_VALUE),
                standing(false, false, false, false, false, false, 0, true, 0, false, false, 99))) {
            assertEquals(SessionCore.SAFE, SessionCore.refusal(unsafe),
                    "falling, in the air, burning, in lava or water, or hurt in the last 100 ticks: " + unsafe);
        }
        assertNull(SessionCore.refusal(standing(false, false, false, false, false, false, 0, true, 0, false, false,
                SessionCore.HURT_TICKS)), "hurt exactly 100 ticks ago is long enough");
        assertNull(SessionCore.refusal(standing(false, false, false, false, false, false, 0, true, -20, false, false,
                Long.MAX_VALUE)), "negative fire ticks mean not burning");
    }

    @Test
    void theGamesWorldsAreReadOnlyForEveryoneButAdminsWhileGamesAreOn() {
        List<String> worlds = List.of("games", "Games_2");
        assertTrue(GamesWorldGuard.readOnly(true, worlds, "games", false), "a Games world, games on, not an admin");
        assertTrue(GamesWorldGuard.readOnly(true, worlds, "games_2", false), "world names match case-blind");
        assertFalse(GamesWorldGuard.readOnly(true, worlds, "games", true), "admins build courses and signs");
        assertFalse(GamesWorldGuard.readOnly(false, worlds, "games", false), "games off: the world is left alone");
        assertFalse(GamesWorldGuard.readOnly(true, worlds, "world", false), "any other world is not ours");
        assertFalse(GamesWorldGuard.readOnly(true, List.of(), "games", false), "no Games worlds, nothing read-only");
    }

    @Test
    void theSavedCommandReadsItsWordsHoweverTheyAreHandedOver() {
        String[] words = {"Bob", "show"};
        assertArrayEquals(words, SavedStateAdmin.words(new String[]{"games", "saved", "Bob", "show"}),
                "the whole command line");
        assertArrayEquals(words, SavedStateAdmin.words(new String[]{"saved", "Bob", "show"}), "from 'saved' on");
        assertArrayEquals(words, SavedStateAdmin.words(new String[]{"Bob", "show"}), "the words after 'saved'");
        assertArrayEquals(new String[]{"saved", "show"}, SavedStateAdmin.words(new String[]{"saved", "show"}),
                "a player really called 'saved' still works");
        assertArrayEquals(new String[]{"Bob", "discard", "confirm"},
                SavedStateAdmin.words(new String[]{"games", "saved", "Bob", "discard", "confirm"}), "and confirm");
        assertArrayEquals(new String[]{""}, SavedStateAdmin.words(new String[]{"saved", ""}),
                "tab completion right after 'saved '");
        assertEquals(0, SavedStateAdmin.words(new String[]{"saved"}).length, "just 'saved'");
        assertEquals(0, SavedStateAdmin.words(null).length, "nothing");
    }
}
