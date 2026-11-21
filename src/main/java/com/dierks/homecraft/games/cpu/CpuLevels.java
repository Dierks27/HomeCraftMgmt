package com.dierks.homecraft.games.cpu;

import java.util.Collection;

/**
 * The level an NPC plays at in one game (the owner: "make it so I can set it to an easy level if they play with me and
 * Lilah or Just Lilah herself"). Pure.
 *
 * <ul>
 *   <li>If any real player in that game (at the table, in the race, in the match) wants easy, every NPC in the game
 *       plays at easy, whatever its own level.</li>
 *   <li>Otherwise, if any of them wants matched, NPCs play at the matched level: near that player's own record in that
 *       game where there is an honest way to tell (Connect Four and Tic-Tac-Toe against NPCs:
 *       {@link #afterGame}), else medium (the games of chance have no skill to match).</li>
 *   <li>Otherwise each NPC plays at its own level.</li>
 * </ul>
 */
public final class CpuLevels {

    private CpuLevels() {
    }

    /**
     * @param own     the NPC's own level
     * @param prefs   the real players' prefs in the game
     * @param matched the matched level for this game, or {@code null} when there is no honest way to tell (medium)
     */
    public static CpuLevel forGame(CpuLevel own, Collection<CpuChoice.Pref> prefs, CpuLevel matched) {
        boolean anyMatched = false;
        for (CpuChoice.Pref p : prefs) {
            if (p == CpuChoice.Pref.EASY) {
                return CpuLevel.EASY;
            }
            anyMatched |= p == CpuChoice.Pref.MATCHED;
        }
        if (anyMatched) {
            return matched == null ? CpuLevel.MEDIUM : matched;
        }
        return own;
    }

    /**
     * A player's matched level in a game after a game against an NPC at {@code played}: a win moves it one up from
     * there, a loss one down, a draw keeps it. It starts at medium.
     */
    public static CpuLevel afterGame(CpuLevel played, boolean won, boolean lost) {
        CpuLevel at = played == null ? CpuLevel.MEDIUM : played;
        return won ? at.up() : lost ? at.down() : at;
    }
}
