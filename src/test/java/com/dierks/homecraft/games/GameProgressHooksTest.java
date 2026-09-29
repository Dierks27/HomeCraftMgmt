package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.cabinet.connect.ConnectFour;
import com.dierks.homecraft.games.cabinet.connect.ConnectFourAI;
import com.dierks.homecraft.games.cabinet.connect.ConnectFourMatch;
import com.dierks.homecraft.games.cabinet.snake.Snake;
import com.dierks.homecraft.games.cabinet.sweeper.CreeperSweeper;
import com.dierks.homecraft.games.cabinet.sweeper.SweeperEngine;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToe;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeAI;
import com.dierks.homecraft.games.cabinet.tictactoe.TicTacToeMatch;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the games tell the quests and achievements (EXTRAS E4, {@link GameProgress}), against the
 * real cabinets and a real database.
 *
 * <p>Pinned here: a Classic cabinet run is one finish, and says whether it reached the board's gold
 * milestone; a daily board's scored try and its practice are finishes too; so is every game played
 * out against the Arcade that records nothing (a Connect Four win below hard, a loss, a draw) and a
 * Creeper Sweeper board lost to a creeper; a game someone quit is not, and a friend game never
 * reaches a finish at all; no game of chance so much as names {@link GameProgress} (nothing may reward playing
 * one); and a Fresh course's run counts in the Star Chart of its own week, not of the week its set
 * began in (a weekly set changing on Thursday, run on a Monday).
 */
class GameProgressHooksTest {

    private Host host;
    private GamesService games;
    private Fake alex;
    private final List<String> heard = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 10, 5, 15, 0)); // Mon 5 Oct 2026
        host.config = GamesKit.config(GamesKit.common(true, 100, 600, 6), "snake", Snake.SPEC.defaults(),
                "connect_four", ConnectFour.SPEC.defaults(), "tic_tac_toe", TicTacToe.SPEC.defaults(),
                "creeper_sweeper", CreeperSweeper.SPEC.defaults(), "fresh_courses", DailySettings.defaults()
                        .withEnabled(true).withRebuild(LocalTime.of(4, 0), DayOfWeek.THURSDAY));
        games = GamesKit.service(host, List.of(Snake.SPEC, ConnectFour.SPEC, TicTacToe.SPEC, CreeperSweeper.SPEC,
                DailyCourses.SPEC));
        games.progress(new GameProgress() {
            @Override
            public void cabinetFinished(Player player, String gameId, boolean practice, boolean goldMedal) {
                heard.add("cabinet " + gameId + (practice ? " practice" : "") + (goldMedal ? " gold" : ""));
            }
        });
        alex = new Fake("Alex");
        host.online.put(alex.id, alex.player);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void aClassicRunIsOneFinishAndSaysWhenItReachedGold() {
        Snake snake = (Snake) games.game("snake");
        snake.finishClassic(alex.player, Scores.CLASSIC, 12, false);
        assertEquals(List.of("cabinet snake"), heard, "one finish, bronze only: not gold");
        snake.finishClassic(alex.player, Scores.CLASSIC, 31, false);
        assertEquals(List.of("cabinet snake", "cabinet snake gold"), heard,
                "30 apples is Snake's gold milestone: the achievement can hear it");
    }

    @Test
    void aDailyBoardsScoredTryAndItsPracticeAreFinishes() {
        Snake snake = (Snake) games.game("snake");
        long day = host.clock.dayKey();
        snake.finishDaily(alex.player, new CabinetGame.DailyStart(day, 1L, false), 5, false, false);
        assertEquals(List.of("cabinet snake practice"), heard, "practice counts for FINISH_CABINET");
        snake.finishDaily(alex.player, new CabinetGame.DailyStart(day, 1L, true), 25, false, true);
        assertEquals(List.of("cabinet snake practice", "cabinet snake"), heard, "and so does the scored try");
    }

    @Test
    void aGameSomeoneQuitIsNotAFinish() throws IOException {
        ConnectFour four = (ConnectFour) games.game("connect_four");
        ConnectFourMatch left = ConnectFourMatch.vsArcade(alex.id, ConnectFourAI.Level.HARD, 7L);
        left.leave(alex.id);
        four.finish(alex.player, left);
        assertEquals(List.of(), heard, "a game the player quit is no finish");
        // A friend game (quit or played out) pays nothing and records nothing, so it never reaches
        // finishClassic, the one place a cabinet finish is told.
        for (String game : List.of("connect/ConnectFour.java", "tictactoe/TicTacToe.java")) {
            String code = Files.readString(Path.of("src/main/java/com/dierks/homecraft/games/cabinet/" + game));
            for (String method : List.of("public void leaveFriendGame(", "public void finishFriendGame(")) {
                String body = body(code, method);
                assertTrue(!body.contains("finishClassic") && !body.contains("finishDaily")
                        && !body.contains("tellProgress"), game + " " + method + " never tells a finish");
            }
        }
    }

    /** Run a game's finish; its sounds need a server, and they come after everything told. */
    private static void quietly(Runnable finish) {
        try {
            finish.run();
        } catch (LinkageError noServer) {
            // org.bukkit.Sound can't load without a server: the sound is the last thing a finish does
        }
    }

    @Test
    void everyConnectFourGamePlayedOutAgainstTheArcadeIsAFinish() {
        ConnectFour four = (ConnectFour) games.game("connect_four");
        // the pieces go straight onto the board: red (the player) first, then yellow (the Arcade)
        ConnectFourMatch easyWin = ConnectFourMatch.vsArcade(alex.id, ConnectFourAI.Level.EASY, 1L);
        for (int c : new int[]{0, 1, 0, 1, 0, 1, 0}) {
            easyWin.board().play(c);
        }
        assertEquals(ConnectFourMatch.Outcome.WON, easyWin.outcome(alex.id), "four in a column: a win on easy");
        quietly(() -> four.finish(alex.player, easyWin));
        assertEquals(List.of("cabinet connect_four"), heard, "a win below hard records nothing, but it is a finish");

        ConnectFourMatch hardLoss = ConnectFourMatch.vsArcade(alex.id, ConnectFourAI.Level.HARD, 1L);
        for (int c : new int[]{0, 1, 0, 1, 0, 1, 2, 1}) {
            hardLoss.board().play(c);
        }
        assertEquals(ConnectFourMatch.Outcome.LOST, hardLoss.outcome(alex.id), "the Arcade's four: a loss");
        quietly(() -> four.finish(alex.player, hardLoss));
        assertEquals(List.of("cabinet connect_four", "cabinet connect_four"), heard, "so is a loss");

        ConnectFourMatch hardWin = ConnectFourMatch.vsArcade(alex.id, ConnectFourAI.Level.HARD, 1L);
        for (int c : new int[]{0, 1, 0, 1, 0, 1, 0}) {
            hardWin.board().play(c);
        }
        quietly(() -> four.finish(alex.player, hardWin));
        assertEquals(3, heard.size(), "a hard win is told once, by finishClassic, not twice: " + heard);
    }

    @Test
    void aTicTacToeLossOrDrawAgainstTheArcadeIsAFinish() {
        TicTacToe ttt = (TicTacToe) games.game("tic_tac_toe");
        TicTacToeMatch draw = TicTacToeMatch.vsArcade(alex.id, TicTacToeAI.Level.EASY, 1L);
        for (int cell : new int[]{0, 1, 2, 4, 3, 5, 7, 6, 8}) {
            draw.board().play(cell);
        }
        assertEquals(TicTacToeMatch.Outcome.DRAW, draw.outcome(alex.id), "X O X / X O O / O X X: a draw");
        quietly(() -> ttt.finish(alex.player, draw));
        assertEquals(List.of("cabinet tic_tac_toe"), heard, "a draw records nothing, but it is a finish");

        TicTacToeMatch loss = TicTacToeMatch.vsArcade(alex.id, TicTacToeAI.Level.HARD, 1L);
        for (int cell : new int[]{0, 3, 1, 4, 8, 5}) {
            loss.board().play(cell);
        }
        assertEquals(TicTacToeMatch.Outcome.LOST, loss.outcome(alex.id), "the Arcade's middle row: a loss");
        quietly(() -> ttt.finish(alex.player, loss));
        assertEquals(List.of("cabinet tic_tac_toe", "cabinet tic_tac_toe"), heard, "so is a loss");

        TicTacToeMatch left = TicTacToeMatch.vsArcade(alex.id, TicTacToeAI.Level.EASY, 1L);
        left.leave(alex.id);
        quietly(() -> ttt.finish(alex.player, left));
        assertEquals(2, heard.size(), "a game the player quit is no finish");
    }

    /** A board with 30 creepers in 45 squares, dug square by square until one is found. */
    private static SweeperEngine dugUp() {
        SweeperEngine board = SweeperEngine.classic(30, 7L);
        board.dig(22, 1L);
        for (int cell = 0; cell < SweeperEngine.CELLS && !board.state().over(); cell++) {
            board.dig(cell, 2L);
        }
        assertEquals(SweeperEngine.State.LOST, board.state(), "a creeper was dug up");
        return board;
    }

    @Test
    void aCreeperDugUpEndsTheBoardAndIsAFinishScoredOrPractice() {
        CreeperSweeper sweeper = (CreeperSweeper) games.game("creeper_sweeper");
        sweeper.finish(alex.player, sweeper.classic("normal"), dugUp());
        assertEquals(List.of("cabinet creeper_sweeper"), heard, "a Classic board lost is a finish, with no medal");
        long day = host.clock.dayKey();
        sweeper.finish(alex.player, new CreeperSweeper.Run("normal", new CabinetGame.DailyStart(day, 1L, true)),
                dugUp());
        assertEquals("cabinet creeper_sweeper", heard.get(1), "so is today's scored try");
        sweeper.finish(alex.player, new CreeperSweeper.Run("normal", new CabinetGame.DailyStart(day, 1L, false)),
                dugUp());
        assertEquals("cabinet creeper_sweeper practice", heard.get(2), "and its practice, which says so");
        assertTrue(alex.heard().contains("That square hid a creeper"), "the player still reads what happened");
    }

    /** A method's body, by counting braces from its signature. */
    private static String body(String code, String signature) {
        int at = code.indexOf(signature);
        assertTrue(at >= 0, "the method is there: " + signature);
        int open = code.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        return code.substring(open);
    }

    @Test
    void noGameOfChanceEverTellsTheQuestsOrAchievements() throws IOException {
        Path root = Path.of("src/main/java/com/dierks/homecraft");
        List<Path> chance = new ArrayList<>();
        for (String dir : List.of("games/chance", "gui/games/chance")) {
            try (Stream<Path> files = Files.walk(root.resolve(dir))) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(chance::add);
            }
        }
        assertTrue(chance.size() > 5, "the games of chance are there to check: " + chance.size());
        for (Path p : chance) {
            String code = Files.readString(p);
            assertTrue(!code.contains("tellProgress") && !code.contains("GameProgress"),
                    p + " must never tell the quests or achievements anything");
        }
    }

    @Test
    void aFreshRunCountsInTheStarChartOfItsOwnWeek() {
        // weekly, changing on Thursday; the week starts on Monday: the set began Thu 1 Oct
        long monday = LocalDate.of(2026, 10, 5).toEpochDay();
        long setStart = LocalDate.of(2026, 10, 1).toEpochDay();
        assertEquals(setStart, DailyLookup.edition(games).editionStart(host.clock.nowMillis()),
                "the set up now began on Thursday");
        assertEquals(monday, DailyLookup.weekKey(games), "a Monday run counts in the week of that Monday");
        assertEquals(LocalDate.of(2026, 9, 28).toEpochDay(), DailyLookup.weekKey(games, setStart),
                "not the week the set began in");
    }
}
