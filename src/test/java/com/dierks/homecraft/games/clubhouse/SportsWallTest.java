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
}
