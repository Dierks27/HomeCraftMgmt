package com.dierks.homecraft.games.cpu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level an NPC plays at in one game (the owner: "make it so I can set it to an easy level if they play with me and
 * Lilah or Just Lilah herself"), and each player's setting with a parent's or admin's lock.
 */
class CpuLevelsTest {

    private static final CpuChoice.Pref EASY = CpuChoice.Pref.EASY;
    private static final CpuChoice.Pref MATCHED = CpuChoice.Pref.MATCHED;
    private static final CpuChoice.Pref ANY = CpuChoice.Pref.ANY;

    @Test
    void ifAnyRealPlayerWantsEasyEveryNpcInTheGamePlaysEasy() {
        for (CpuLevel own : CpuLevel.values()) {
            assertEquals(CpuLevel.EASY, CpuLevels.forGame(own, List.of(EASY), null), "Lilah alone");
            assertEquals(CpuLevel.EASY, CpuLevels.forGame(own, List.of(ANY, EASY), CpuLevel.HARD), "Jeff and Lilah");
            assertEquals(CpuLevel.EASY, CpuLevels.forGame(own, List.of(MATCHED, ANY, EASY), CpuLevel.HARD));
        }
    }

    @Test
    void otherwiseMatchedThenItsOwnLevel() {
        assertEquals(CpuLevel.HARD, CpuLevels.forGame(CpuLevel.HARD, List.of(ANY), CpuLevel.EASY), "any: its own");
        assertEquals(CpuLevel.EASY, CpuLevels.forGame(CpuLevel.EASY, List.of(ANY, ANY), null));
        assertEquals(CpuLevel.EASY, CpuLevels.forGame(CpuLevel.HARD, List.of(MATCHED), CpuLevel.EASY),
                "matched: the player's record");
        assertEquals(CpuLevel.MEDIUM, CpuLevels.forGame(CpuLevel.HARD, List.of(MATCHED, ANY), null),
                "matched with no honest way to tell: medium");
        assertEquals(CpuLevel.HARD, CpuLevels.forGame(CpuLevel.HARD, List.of(), null), "nobody: its own");
    }

    @Test
    void theMatchedLevelMovesWithEachGameAgainstAnNpc() {
        assertEquals(CpuLevel.HARD, CpuLevels.afterGame(CpuLevel.MEDIUM, true, false), "a win: one up");
        assertEquals(CpuLevel.EASY, CpuLevels.afterGame(CpuLevel.MEDIUM, false, true), "a loss: one down");
        assertEquals(CpuLevel.MEDIUM, CpuLevels.afterGame(CpuLevel.MEDIUM, false, false), "a draw: the same");
        assertEquals(CpuLevel.HARD, CpuLevels.afterGame(CpuLevel.HARD, true, false), "hard stays hard");
        assertEquals(CpuLevel.EASY, CpuLevels.afterGame(CpuLevel.EASY, false, true), "easy stays easy");
        assertEquals(CpuLevel.MEDIUM, CpuLevels.afterGame(null, false, false), "it starts at medium");
    }

    @Test
    void aSettingIsKeptAsOneShortLine() {
        for (boolean on : new boolean[]{true, false}) {
            for (CpuChoice.Pref p : CpuChoice.Pref.values()) {
                CpuChoice c = new CpuChoice(on, p);
                assertEquals(c, CpuChoice.parse(c.text()));
            }
        }
        assertEquals("on:any", CpuChoice.DEFAULT.text());
        assertNull(CpuChoice.parse("maybe:easy"));
        assertNull(CpuChoice.parse("on:hardest"));
        assertNull(CpuChoice.parse(null));
        assertEquals(CpuChoice.Pref.MATCHED, CpuChoice.Pref.of(" Matched "));
    }

    @Test
    void aLockedSettingCantBeLiftedByThePlayer() {
        CpuChoice lilah = new CpuChoice(true, EASY);
        CpuChoice.Prefs locked = new CpuChoice.Prefs(new CpuChoice(true, ANY), lilah);
        assertTrue(locked.locked());
        assertEquals(lilah, locked.effective(), "the lock applies, not her own");
        assertNull(locked.withOwn(new CpuChoice(true, ANY)), "she can't change it");
        assertNull(locked.withOwn(new CpuChoice(false, EASY)), "nor turn NPCs off herself");
        CpuChoice.Prefs free = new CpuChoice.Prefs(null, null);
        assertFalse(free.locked());
        assertEquals(CpuChoice.DEFAULT, free.effective(), "nothing set: on, each at its own level");
        CpuChoice.Prefs mine = free.withOwn(new CpuChoice(false, MATCHED));
        assertEquals(new CpuChoice(false, MATCHED), mine.effective(), "unlocked: their own applies");
    }
}
