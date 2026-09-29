package com.dierks.homecraft.display;

import com.dierks.homecraft.storage.DisplayDao;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which leaderboard displays the once-a-second tick draws again (EXTRAS E3,
 * {@link DisplayService#dueBoards}), with no server.
 *
 * <p>Pinned here: nothing is drawn when no score came in; a display is drawn again only when a
 * score went on the board it shows, once however many scores that board got; a leaderboard whose
 * board isn't known yet is drawn (it may show that board); and a market display never is.
 */
class BoardRedrawTest {

    private static DisplayDao.Display display(long id, String kind, String itemId) {
        return new DisplayDao.Display(id, kind, "world", 0, 64, (int) id, itemId, 1, 1, "north", null, null, 0L);
    }

    private final DisplayDao.Display snakeSign = display(1, DisplayDao.SIGN, "@board:snake");
    private final DisplayDao.Display snakeHolo = display(2, DisplayDao.HOLOGRAM, "@board:snake");
    private final DisplayDao.Display hardTv = display(3, DisplayDao.TV, "@board:fresh_parkour_hard");
    private final DisplayDao.Display newSign = display(4, DisplayDao.SIGN, "@board:river_run");
    private final DisplayDao.Display wheat = display(5, DisplayDao.SIGN, "wheat");
    private final DisplayDao.Display news = display(6, DisplayDao.TV, "@news");
    private final List<DisplayDao.Display> all = List.of(snakeSign, snakeHolo, hardTv, newSign, wheat, news);
    private final Map<Long, String> keys = new HashMap<>(Map.of(1L, "snake|classic", 2L, "snake|classic",
            3L, "trials|gfresh:fresh_parkour_hard:7:2960"));

    @Test
    void nothingIsDrawnWhenNoScoreCameIn() {
        assertEquals(List.of(), DisplayService.dueBoards(all, keys, Set.of()), "a quiet second draws nothing");
        assertEquals(List.of(), DisplayService.dueBoards(all, keys, null), "nor does nothing at all");
    }

    @Test
    void onlyTheDisplaysOfABoardAScoreWentOnAreDrawnAgainEachOnce() {
        keys.put(4L, "trials|course:river_run");
        assertEquals(List.of(snakeSign, snakeHolo), DisplayService.dueBoards(all, keys, Set.of("snake|classic")),
                "both Snake leaderboards, once each, however many scores Snake's board got in that second");
        assertEquals(List.of(hardTv), DisplayService.dueBoards(all, keys,
                Set.of("trials|gfresh:fresh_parkour_hard:7:2960", "golf|golf:meadow")),
                "a board nobody shows draws nothing");
        assertEquals(List.of(), DisplayService.dueBoards(all, keys, Set.of("snake|daily:20724")),
                "another board of the same game is not the one shown");
    }

    @Test
    void aLeaderboardWhoseBoardIsntKnownYetIsDrawnAndAMarketDisplayNever() {
        assertEquals(List.of(newSign), DisplayService.dueBoards(all, keys, Set.of("golf|golf:meadow")),
                "a leaderboard not read yet may show that board: drawn, never the wheat sign or the news board");
        assertEquals(List.of(snakeSign, snakeHolo, hardTv, newSign), DisplayService.dueBoards(all, Map.of(),
                Set.of("x|y")), "with nothing known, every leaderboard is drawn, and only those");
    }
}
