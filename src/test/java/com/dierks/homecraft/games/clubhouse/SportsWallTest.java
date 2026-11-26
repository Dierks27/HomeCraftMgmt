package com.dierks.homecraft.games.clubhouse;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.56.0: the Sports Board's lines on the Parlor's wall board. */
class SportsWallTest {

    @Test
    void linesSitInTheBoardsMiddleAndAlwaysFit() {
        int most = 1 + 2 * SideRooms.WALL_GAMES;
        double tall = most * SideRooms.LINE_HIGH * SideRooms.BOARD_TEXT_SCALE;
        assertTrue(tall < SideRooms.BOARD_TOP, "a full board fits on the wall board");
        assertTrue(SideRooms.textBottom(most) + tall <= SideRooms.BOARD_TOP);
        assertTrue(SideRooms.textBottom(1) > SideRooms.textBottom(most), "fewer lines sit higher, in the middle");
        assertTrue(SideRooms.textBottom(200) > 0, "never under the board");
    }

    @Test
    void teamNamesAreShownAsTheyAre() {
        String plain = PlainTextComponentSerializer.plainText().serialize(SideRooms.boardComponent(List.of(
                "Tonight's games - $10 in pays", "NCAAF 7:30 PM  Texas A&M vs LSU", "  Texas A&M $14.10 · LSU $27.30")));
        assertEquals("Tonight's games - $10 in pays\nNCAAF 7:30 PM  Texas A&M vs LSU\nTexas A&M $14.10 · LSU $27.30",
                plain);
    }

    /** 0.57.1: every game on the board, five at a time, the page turning round and round. */
    @Test
    void theWallTurnsThroughEveryGame() {
        List<String> lines = new java.util.ArrayList<>(List.of("Tonight's games - $10 in pays"));
        for (int g = 1; g <= 12; g++) {
            lines.add("NBA 7:00 PM  Team" + g + " vs Other" + g);
            lines.add("  Team" + g + " $15.00 · Other" + g + " $20.00");
        }
        List<String> first = SideRooms.wallPage(lines, 0, 5);
        assertEquals("Tonight's games - $10 in pays  (1/3)", first.get(0));
        assertEquals(11, first.size(), "a heading and five games");
        assertTrue(first.get(1).contains("Team1 vs"));
        List<String> third = SideRooms.wallPage(lines, 2, 5);
        assertEquals("Tonight's games - $10 in pays  (3/3)", third.get(0));
        assertEquals(5, third.size(), "the last two games");
        assertTrue(third.get(1).contains("Team11 vs"));
        assertEquals(first, SideRooms.wallPage(lines, 3, 5), "round and round");
        List<String> few = lines.subList(0, 7);
        assertEquals(few, SideRooms.wallPage(few, 4, 5), "three games: one page, no number");
        assertEquals(List.of("No games in the next 24 hours"), SideRooms.wallPage(List.of("No games in the next 24 hours"), 1, 5));
    }
}
