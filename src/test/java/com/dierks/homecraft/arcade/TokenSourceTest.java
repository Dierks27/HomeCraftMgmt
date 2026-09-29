package com.dierks.homecraft.arcade;

import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token ledger's sources. Every ledger line is stored under a source's NAME, so a constant is
 * appended and never renamed or moved: a renamed one would orphan every line written under its old
 * name ({@code /hcm tokens audit} would stop adding them up). Pinned here: the whole list in order,
 * the six this batch appends (EVENTS-DROPPER-SPEC C1, EVENTS-RECONCILED "Packages"), and that the
 * Weekly Cup's are not games of chance.
 */
class TokenSourceTest {

    @Test
    void sourcesAreAppendedAndNoneIsRenamedOrMoved() {
        assertEquals(List.of("LOGIN_STREAK", "PLAYTIME", "QUEST", "ACHIEVEMENT", "CRATE", "LOTTO", "PRIZE", "PITY",
                        "PACK", "TRADE_IN", "HUNT", "ADMIN", "REFUND", "ARCADE_SLOTS", "ARCADE_TWENTY_ONE",
                        "ARCADE_WHEEL", "ARCADE_HILO", "ARCADE_COIN_FLIP", "GAMES_SWEEPER", "GAMES_MERGE",
                        "GAMES_SNAKE", "GAMES_MATCH", "GAMES_SIMON", "GAMES_WHACK", "GAMES_CONNECT", "GAMES_TICTACTOE",
                        "GAMES_PARKOUR", "GAMES_ELYTRA", "GAMES_BOAT", "GAMES_GOLF", "GAMES_DAILY",
                        "GAMES_DROPPER", "GAMES_RACE_NIGHT", "GAMES_FLOORS",
                        "GAMES_CUP_ENTRY", "GAMES_CUP_PRIZE", "GAMES_CUP_REFUND"),
                Arrays.stream(TokenService.Source.values()).map(Enum::name).toList(),
                "a new source goes at the end; an old one is never renamed or moved");
    }

    @Test
    void theNewSourcesReadByNameWithTheirLabels() {
        assertSame(TokenService.Source.GAMES_DROPPER, TokenService.Source.of("GAMES_DROPPER"), "stored by name");
        assertEquals("Dropper", TokenService.Source.GAMES_DROPPER.label(), "what the token history says");
        assertEquals("Race Night", TokenService.Source.GAMES_RACE_NIGHT.label(), "Race Night's prizes");
        assertEquals("Falling Floors", TokenService.Source.GAMES_FLOORS.label(), "Falling Floors' rewards");
        assertEquals("Weekly Cup entry", TokenService.Source.GAMES_CUP_ENTRY.label(), "an entry");
        assertEquals("Weekly Cup prize", TokenService.Source.GAMES_CUP_PRIZE.label(), "a share of the pool");
        assertEquals("Weekly Cup refund", TokenService.Source.GAMES_CUP_REFUND.label(), "an entry given back");
        for (TokenService.Source s : TokenService.Source.values()) {
            String label = s.label().toLowerCase(java.util.Locale.ROOT);
            for (String banned : List.of("bet", "wager", "gamble", "casino", "lucky", "jackpot")) {
                assertFalse(s.name().startsWith("GAMES_") && label.contains(banned),
                        s + "'s label must not say '" + banned + "'");
            }
            assertTrue(s.label().chars().allMatch(c -> c < 0x80), s + "'s label is plain text");
        }
    }

    @Test
    void theCupsSourcesMatchThePureRulesAndAreNotGamesOfChance() {
        for (com.dierks.homecraft.games.cup.CupSource c : com.dierks.homecraft.games.cup.CupSource.values()) {
            assertSame(TokenService.Source.valueOf(c.name()), TokenService.Source.of(c.name()),
                    c + " maps to the token service's constant by name");
            assertFalse(GamesDao.CHANCE_SOURCES.contains(c.name()),
                    c + " is a skill contest's pool, never counted as tokens put into games of chance");
        }
        for (String s : List.of("GAMES_DROPPER", "GAMES_RACE_NIGHT", "GAMES_FLOORS")) {
            assertFalse(GamesDao.CHANCE_SOURCES.contains(s), s + " is a skill game's");
        }
    }
}
