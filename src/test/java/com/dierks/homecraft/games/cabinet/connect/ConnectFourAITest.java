package com.dierks.homecraft.games.cabinet.connect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcade's Connect Four play on the cabinet's 7 by 5, without a server.
 *
 * <p>Pinned here: normal and hard take a win that's there, and take it over blocking; they block
 * a four that's coming, across or up; hard never loses to a naive opponent (random, leftmost, or
 * "win if you can, block if you must") whoever moves first; moves that score the same are picked
 * between by the game's seed (so the choices of many seeds are mirror-symmetric on a symmetric
 * board) and one seed replays one game; easy leaves a four open now and then; and the evaluation
 * values the middle column.
 */
class ConnectFourAITest {

    private static final ConnectFourAI.Level[] STRONG = {ConnectFourAI.Level.NORMAL, ConnectFourAI.Level.HARD};

    @Test
    void theArcadeTakesAWinThatIsThereRatherThanBlock() {
        for (ConnectFourAI.Level level : STRONG) {
            ConnectFourBoard b = ConnectFourBoard.parse(
                    ".......",
                    ".......",
                    "......Y",
                    "......Y",
                    "RRR...Y");
            assertEquals(3, ConnectFourAI.choose(b, level, new SplittableRandom(1)),
                    level + ": red wins in column 3 now; blocking yellow's column 6 would be a waste");
        }
    }

    @Test
    void theArcadeBlocksAFourAcross() {
        for (ConnectFourAI.Level level : STRONG) {
            for (long seed = 0; seed < 5; seed++) {
                ConnectFourBoard b = ConnectFourBoard.parse(
                        ".......",
                        ".......",
                        ".......",
                        "Y......",
                        "RRR...Y");
                assertEquals(3, ConnectFourAI.choose(b, level, new SplittableRandom(seed)),
                        level + " seed " + seed + ": yellow must block red's three across the bottom");
            }
        }
    }

    @Test
    void theArcadeBlocksAFourUp() {
        for (ConnectFourAI.Level level : STRONG) {
            ConnectFourBoard b = ConnectFourBoard.parse(
                    ".......",
                    ".......",
                    "..Y....",
                    "..Y....",
                    "RRY.R..");
            assertEquals(2, ConnectFourAI.choose(b, level, new SplittableRandom(3)),
                    level + ": red must block yellow's three up column 2");
        }
    }

    @Test
    void hardNeverLosesToANaiveOpponent() {
        List<Function<ConnectFourBoard, Integer>> opponents = new ArrayList<>();
        SplittableRandom chaos = new SplittableRandom(99);
        opponents.add(b -> randomMove(b, chaos));
        opponents.add(ConnectFourAITest::leftmost);
        opponents.add(b -> greedy(b, chaos));
        int games = 0;
        for (Function<ConnectFourBoard, Integer> opponent : opponents) {
            for (int arcadeSeat = 0; arcadeSeat < 2; arcadeSeat++) {
                for (int g = 0; g < 10; g++) {
                    SplittableRandom arcade = new SplittableRandom(1000L * arcadeSeat + g);
                    ConnectFourBoard b = new ConnectFourBoard();
                    while (!b.over()) {
                        int col = b.turn() == arcadeSeat
                                ? ConnectFourAI.choose(b, ConnectFourAI.Level.HARD, arcade)
                                : opponent.apply(b);
                        assertTrue(b.play(col) >= 0, "every move played is legal (column " + col + ")");
                    }
                    assertTrue(b.winner() < 0 || b.winner() == arcadeSeat,
                            "the hard Arcade (seat " + arcadeSeat + ") lost game " + g + " to a naive opponent");
                    games++;
                }
            }
        }
        assertEquals(60, games, "every scripted game was played");
    }

    @Test
    void equalMovesArePickedByTheGamesSeed() {
        for (ConnectFourAI.Level level : STRONG) {
            Set<Integer> chosen = new TreeSet<>();
            for (long seed = 0; seed < 40; seed++) {
                chosen.add(ConnectFourAI.choose(symmetric(), level, new SplittableRandom(seed)));
            }
            for (int col : chosen) {
                assertTrue(chosen.contains(6 - col),
                        level + ": the board is mirror-symmetric, so if column " + col + " is best its mirror "
                                + (6 - col) + " is too and some seed picks it; chose " + chosen);
            }
            assertTrue(chosen.size() >= 2, level + ": the seed decides between equal moves, chose " + chosen);
            int once = ConnectFourAI.choose(symmetric(), level, new SplittableRandom(7));
            assertEquals(once, ConnectFourAI.choose(symmetric(), level, new SplittableRandom(7)),
                    level + ": one seed always picks the same");
        }
    }

    @Test
    void oneSeedReplaysOneGame() {
        assertEquals(playOut(5), playOut(5), "the same game seed and the same opponent replay move for move");
    }

    @Test
    void easyLeavesAFourOpenNowAndThen() {
        int missed = 0;
        for (long seed = 0; seed < 200; seed++) {
            ConnectFourBoard b = ConnectFourBoard.parse(
                    ".......",
                    ".......",
                    ".......",
                    "Y......",
                    "RRR...Y");
            if (ConnectFourAI.choose(b, ConnectFourAI.Level.EASY, new SplittableRandom(seed)) != 3) {
                missed++;
            }
        }
        assertTrue(missed >= 10 && missed <= 100,
                "easy should miss a block now and then (about one in five), missed " + missed + " of 200");
    }

    @Test
    void theEvaluationValuesTheMiddle() {
        int middle = ConnectFourAI.evaluate(ConnectFourBoard.parse(
                ".......", ".......", ".......", ".......", "...R..."));
        int edge = ConnectFourAI.evaluate(ConnectFourBoard.parse(
                ".......", ".......", ".......", ".......", "R......"));
        assertTrue(middle < edge, "for yellow to move, red in the middle (" + middle + ") is worse than red on the edge ("
                + edge + ")");
        assertEquals(3, ConnectFourAI.order(7)[0], "the search tries the middle column first");
    }

    /** Column 3 full and nothing else: a board that looks the same in a mirror. Yellow to move. */
    private static ConnectFourBoard symmetric() {
        return ConnectFourBoard.parse(
                "...R...",
                "...Y...",
                "...R...",
                "...Y...",
                "...R...");
    }

    private static List<Integer> playOut(long seed) {
        SplittableRandom arcade = new SplittableRandom(seed);
        SplittableRandom other = new SplittableRandom(1);
        ConnectFourBoard b = new ConnectFourBoard();
        List<Integer> moves = new ArrayList<>();
        while (!b.over()) {
            int col = b.turn() == 1 ? ConnectFourAI.choose(b, ConnectFourAI.Level.NORMAL, arcade) : randomMove(b, other);
            b.play(col);
            moves.add(col);
        }
        return moves;
    }

    private static int randomMove(ConnectFourBoard b, SplittableRandom rng) {
        while (true) {
            int col = rng.nextInt(b.cols());
            if (b.canPlay(col)) {
                return col;
            }
        }
    }

    private static int leftmost(ConnectFourBoard b) {
        for (int c = 0; c < b.cols(); c++) {
            if (b.canPlay(c)) {
                return c;
            }
        }
        return -1;
    }

    /** Win if you can, block if you must, else anything. */
    private static int greedy(ConnectFourBoard b, SplittableRandom rng) {
        int me = b.turn();
        for (int c = 0; c < b.cols(); c++) {
            if (b.winsWith(c, me)) {
                return c;
            }
        }
        for (int c = 0; c < b.cols(); c++) {
            if (b.winsWith(c, 1 - me)) {
                return c;
            }
        }
        return randomMove(b, rng);
    }
}
