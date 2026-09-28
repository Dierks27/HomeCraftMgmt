package com.dierks.homecraft.games.cabinet.tictactoe;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * One game of Tic-Tac-Toe: the board, who sits where, and whose turn it is (spec §10b). Pure —
 * players are UUIDs — so turns, leaving and outcomes are tested without a server.
 *
 * <p>The first seat plays X and moves first: the player against the Arcade, or whoever sent the
 * invite. The second seat is O: the friend, or {@code null} for the Arcade, which carries its
 * level and its own random generator seeded once per game. {@link #version()} goes up with every
 * change so each player's screen can notice the other's move.
 */
public final class TicTacToeMatch {

    /** How the game stands for one player. */
    public enum Outcome {
        PLAYING,
        WON,
        LOST,
        DRAW,
        /** This player left before the end. */
        LEFT,
        /** The other player left before the end. */
        OTHER_LEFT
    }

    private final TicTacToeBoard board = new TicTacToeBoard();
    private final UUID first;
    private final UUID second;
    private final TicTacToeAI.Level level;
    private final SplittableRandom rng;
    private UUID leaver;
    private int version;

    private TicTacToeMatch(UUID first, UUID second, TicTacToeAI.Level level, long seed) {
        this.first = Objects.requireNonNull(first, "first");
        this.second = second;
        this.level = level;
        this.rng = new SplittableRandom(seed);
    }

    /** A game against the Arcade at {@code level}; {@code seed} is this game's own tie-break seed. */
    public static TicTacToeMatch vsArcade(UUID player, TicTacToeAI.Level level, long seed) {
        return new TicTacToeMatch(player, null, Objects.requireNonNull(level, "level"), seed);
    }

    /** A friend game: {@code first} (who invited) plays X and moves first. */
    public static TicTacToeMatch friends(UUID first, UUID second) {
        if (first.equals(Objects.requireNonNull(second, "second"))) {
            throw new IllegalArgumentException("a friend game needs two players");
        }
        return new TicTacToeMatch(first, second, null, 0);
    }

    public TicTacToeBoard board() {
        return board;
    }

    /** The Arcade's level, or {@code null} in a friend game. */
    public TicTacToeAI.Level level() {
        return level;
    }

    public boolean vsArcade() {
        return second == null;
    }

    public UUID first() {
        return first;
    }

    public UUID second() {
        return second;
    }

    /** The other player of a friend game ({@code null} for the Arcade, or a stranger). */
    public UUID other(UUID player) {
        if (first.equals(player)) {
            return second;
        }
        return player != null && player.equals(second) ? first : null;
    }

    /** 0 for X, 1 for O, -1 for someone not in this game. */
    public int seat(UUID player) {
        if (first.equals(player)) {
            return 0;
        }
        return player != null && player.equals(second) ? 1 : -1;
    }

    /** Whether it is {@code player}'s move. */
    public boolean yourTurn(UUID player) {
        return !over() && seat(player) == board.turn();
    }

    /** Whether it is the Arcade's move. */
    public boolean arcadeToMove() {
        return vsArcade() && !over() && board.turn() == 1;
    }

    /** {@code player} marks {@code cell}. @return false (nothing changed) if they can't */
    public boolean play(UUID player, int cell) {
        if (!yourTurn(player) || !board.canPlay(cell)) {
            return false;
        }
        board.play(cell);
        version++;
        return true;
    }

    /** The Arcade makes its move. @return the cell, or -1 if it isn't its move */
    public int arcadeMove() {
        if (!arcadeToMove()) {
            return -1;
        }
        int cell = TicTacToeAI.choose(board, level, rng);
        if (cell >= 0) {
            board.play(cell);
            version++;
        }
        return cell;
    }

    /** {@code player} leaves before the end. False if it was already over or they aren't in it. */
    public boolean leave(UUID player) {
        if (over() || seat(player) < 0) {
            return false;
        }
        leaver = player;
        version++;
        return true;
    }

    /** Whether the game is finished (three in a row, a full board, or someone left). */
    public boolean over() {
        return leaver != null || board.over();
    }

    /** How it stands for {@code player}. */
    public Outcome outcome(UUID player) {
        if (leaver != null) {
            return leaver.equals(player) ? Outcome.LEFT : Outcome.OTHER_LEFT;
        }
        int w = board.winner();
        if (w >= 0) {
            return w == seat(player) ? Outcome.WON : Outcome.LOST;
        }
        return board.full() ? Outcome.DRAW : Outcome.PLAYING;
    }

    /** Goes up with every move or leave. */
    public int version() {
        return version;
    }

    /**
     * Whether this finished game earns today's reward against the Arcade: a win on easy, or a
     * draw or better on hard (the hard Arcade can't be beaten). Friend games never do.
     */
    public boolean earnsDaily(UUID player) {
        if (!vsArcade()) {
            return false;
        }
        Outcome o = outcome(player);
        return level == TicTacToeAI.Level.EASY ? o == Outcome.WON : (o == Outcome.WON || o == Outcome.DRAW);
    }
}
