package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GameKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcade Room's games (0.41.0): read from {@code games.clubhouse.arcade_room.games}, in order, at most six; a game
 * of chance is refused even when someone lists it, and so is anything that isn't an arcade cabinet.
 */
class KidSetTest {

    private static final Map<String, GameKind> KINDS = Map.of("whack_a_zombie", GameKind.CABINET, "simon_says",
            GameKind.CABINET, "mini_match", GameKind.CABINET, "tic_tac_toe", GameKind.CABINET, "snake",
            GameKind.CABINET, "ore_slots", GameKind.CHANCE, "wheel", GameKind.CHANCE, "trials", GameKind.TRIAL);

    @Test
    void theShippedListIsTheFourKidGamesInOrder() {
        List<String> warns = new ArrayList<>();
        assertEquals(ClubhouseSettings.KID_GAMES, KidSet.resolve(ClubhouseSettings.defaults().arcadeGames(), KINDS::get,
                6, warns::add));
        assertEquals(List.of(), warns);
        assertEquals(List.of("whack_a_zombie", "simon_says", "mini_match", "tic_tac_toe"), ClubhouseSettings.KID_GAMES);
    }

    @Test
    void gamesOfChanceAreRefusedEvenWhenListed() {
        List<String> warns = new ArrayList<>();
        List<String> out = KidSet.resolve(List.of("ore_slots", "Snake", "wheel", "trials", "nope", "snake",
                "simon_says"), KINDS::get, 6, warns::add);
        assertEquals(List.of("snake", "simon_says"), out, "only the cabinets, once each, in order");
        assertEquals(4, warns.size(), "one WARN each: " + warns);
        assertTrue(warns.get(0).contains("game of chance"), warns.get(0));
        assertTrue(KidSet.refusal("wheel", KINDS::get).contains("game of chance"));
        assertNull(KidSet.refusal("tic_tac_toe", KINDS::get));
    }

    @Test
    void atMostTheRoomsCabinets() {
        assertEquals(List.of("snake", "simon_says"), KidSet.resolve(List.of("snake", "simon_says", "mini_match"),
                KINDS::get, 2, null));
    }
}
