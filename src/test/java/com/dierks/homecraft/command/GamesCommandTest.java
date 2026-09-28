package com.dierks.homecraft.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of {@code /hcm play|leave|games} that need no server.
 *
 * <p>Pinned here: inside a world game only play, leave, games and help pass (any case); a score
 * reset is a dry run unless it ends in {@code confirm}, {@code all} means every board, and a
 * player may follow the board; admin numbers are whole and in range or refused.
 */
class GamesCommandTest {

    @Test
    void onlyPlayLeaveGamesAndHelpWorkInsideAWorldGame() {
        for (String ok : new String[]{"play", "LEAVE", "games", "help"}) {
            assertTrue(GamesCommand.allowedInSession(ok), ok + " is how a player gets out or gets help");
        }
        for (String no : new String[]{"arcade", "market", "museum", "auction", "binder", "tokens", "trail", ""}) {
            assertFalse(GamesCommand.allowedInSession(no), no + " could hand items into the session or move them");
        }
        assertFalse(GamesCommand.allowedInSession(null), "nothing is not a command");
    }

    @Test
    void aScoreResetIsADryRunUnlessItEndsInConfirm() {
        GamesCommand.Reset dry = GamesCommand.Reset.parse(new String[]{"Snake"});
        assertEquals(new GamesCommand.Reset("snake", null, null, false), dry, "every board, everyone, a dry run");
        assertEquals(new GamesCommand.Reset("snake", null, null, true),
                GamesCommand.Reset.parse(new String[]{"snake", "confirm"}), "confirm clears");
        assertEquals(new GamesCommand.Reset("snake", "classic", null, false),
                GamesCommand.Reset.parse(new String[]{"snake", "classic"}), "one board");
        assertEquals(new GamesCommand.Reset("snake", null, "Alex", true),
                GamesCommand.Reset.parse(new String[]{"snake", "all", "Alex", "confirm"}),
                "all = every board, then a player");
        assertEquals(new GamesCommand.Reset("trials", "course:river_run", "Alex", false),
                GamesCommand.Reset.parse(new String[]{"trials", "course:river_run", "Alex"}),
                "a course board and a player, still a dry run");
        assertNull(GamesCommand.Reset.parse(new String[]{}), "a game is needed");
        assertNull(GamesCommand.Reset.parse(new String[]{"confirm"}), "confirm alone names no game");
    }

    @Test
    void adminNumbersAreWholeAndInRange() {
        assertEquals(7, GamesCommand.parse("7", 1, 365), "a pause of 7 days");
        assertEquals(-2, GamesCommand.parse("0", 1, 365), "below the range");
        assertEquals(-2, GamesCommand.parse("366", 1, 365), "above it");
        assertEquals(-2, GamesCommand.parse("7.5", 1, 365), "not whole");
        assertEquals(-2, GamesCommand.parse("seven", 1, 365), "not a number");
        assertEquals(0, GamesCommand.parse(" 0 ", 0, 10_000), "a limit of 0 means nothing a day");
    }
}
