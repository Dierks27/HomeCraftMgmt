package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who hears that a score went on a board (EXTRAS E3: the hub's leaderboard displays redraw from
 * it), against a real database.
 *
 * <p>Pinned here: every recorded score tells the listener its game and board, a better or a worse
 * one alike; a listener that throws is logged and the score is still recorded and answered; and
 * a replaced or removed listener hears nothing more.
 */
class ScoresListenerTest {

    private Host host;
    private GamesService games;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 9, 29, 15, 0));
        TestGame snake = GamesKit.skill("test_snake", "Test Snake");
        games = GamesKit.service(host, List.of(GamesKit.spec(snake, new SkillSettings(true, 2), null)));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void everyScoreTellsTheListenerItsGameAndBoard() {
        List<String> heard = new ArrayList<>();
        games.scores().onSubmit((game, board) -> heard.add(game + "|" + board));
        UUID sam = UUID.randomUUID();
        games.scores().submit(sam, "test_snake", "classic", 12, false);
        games.scores().submit(sam, "test_snake", "classic", 3, false);
        games.scores().submit(sam, "trials", "course:river_run", 61_000, true);
        assertEquals(List.of("test_snake|classic", "test_snake|classic", "trials|course:river_run"), heard,
                "a better score and a worse one alike: the board may have a new row either way");
    }

    @Test
    void aListenerThatThrowsNeverReachesTheGame() {
        games.scores().onSubmit((game, board) -> {
            throw new IllegalStateException("boom");
        });
        ScoreResult r = games.scores().submit(UUID.randomUUID(), "test_snake", "classic", 12, false);
        assertTrue(r.personalBest(), "the score is recorded and answered all the same");
        assertTrue(host.logs.stream().anyMatch(l -> l.getMessage().contains("leaderboard")), "and the failure logged");
        List<String> heard = new ArrayList<>();
        games.scores().onSubmit((game, board) -> heard.add(board));
        games.scores().onSubmit(null);
        games.scores().submit(UUID.randomUUID(), "test_snake", "classic", 5, false);
        assertEquals(List.of(), heard, "a removed listener hears nothing");
    }
}
