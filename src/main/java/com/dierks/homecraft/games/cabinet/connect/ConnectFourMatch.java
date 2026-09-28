package com.dierks.homecraft.games.cabinet.connect;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * One game of Connect Four: the board, who sits where, and whose turn it is (spec §10b). Pure —
 * players are UUIDs — so turn order, leaving and outcomes are tested without a server.
 *
 * <p>The first seat plays red and moves first: the player against the Arcade, or whoever sent
 * the invite in a friend game. The second seat is yellow: the friend, or {@code null} for the
 * Arcade. A game against the Arcade carries its level and its own random generator (for the
 * Arcade's tie-breaks), seeded once per game.
 *
 * <p>{@link #version()} goes up with every change, so each player's screen can notice the other
 * player's move by comparing it, without the two screens knowing about each other.
 */
public final class ConnectFourMatch {

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

    private final ConnectFourBoard board = new ConnectFourBoard();
    private final UUID first;
    private final UUID second;
    private final ConnectFourAI.Level level;
    private final SplittableRandom rng;
    private UUID leaver;
    private int version;

    private ConnectFourMatch(UUID first, UUID second, ConnectFourAI.Level level, long seed) {
        this.first = Objects.requireNonNull(first, "first");
        this.second = second;
        this.level = level;
        this.rng = new SplittableRandom(seed);
    }

    /** A game against the Arcade at {@code level}; {@code seed} is this game's own tie-break seed. */
    public static ConnectFourMatch vsArcade(UUID player, ConnectFourAI.Level level, long seed) {
        return new ConnectFourMatch(player, null, Objects.requireNonNull(level, "level"), seed);
    }

    /** A friend game: {@code first} (who invited) plays red and moves first. */
    public static ConnectFourMatch friends(UUID first, UUID second) {
        if (first.equals(Objects.requireNonNull(second, "second"))) {
            throw new IllegalArgumentException("a friend game needs two players");
        }
        return new ConnectFourMatch(first, second, null, 0);
    }

    public ConnectFourBoard board() {
        return board;
    }

    /** The Arcade's level, or {@code null} in a friend game. */
    public ConnectFourAI.Level level() {
        return level;
    }

    public boolean vsArcade() {
        return second == null;
    }

    /** Red, who moves first. */
    public UUID first() {
        return first;
    }

    /** Yellow: the friend, or {@code null} for the Arcade. */
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

    /** 0 for red, 1 for yellow, -1 for someone not in this game. */
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

    /**
     * {@code player} drops into {@code col}.
     *
     * @return the row it landed in, or -1 (not their move, a full column, the game is over)
     */
    public int play(UUID player, int col) {
        if (!yourTurn(player) || !board.canPlay(col)) {
            return -1;
        }
        int row = board.play(col);
        version++;
        return row;
    }

    /** The Arcade makes its move. @return the column, or -1 if it isn't its move. */
    public int arcadeMove() {
        if (!arcadeToMove()) {
            return -1;
        }
        int col = ConnectFourAI.choose(board, level, rng);
        if (col >= 0) {
            board.play(col);
            version++;
        }
        return col;
    }

    /** {@code player} leaves before the end. False if the game was already over or they aren't in it. */
    public boolean leave(UUID player) {
        if (over() || seat(player) < 0) {
            return false;
        }
        leaver = player;
        version++;
        return true;
    }

    /** Whether the game is finished (four in a row, a full board, or someone left). */
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
     * Whether this finished game earns today's reward: a win against the Arcade on normal or
     * hard. Easy wins and friend games never do.
     */
    public boolean earnsDaily(UUID player) {
        return vsArcade() && level != ConnectFourAI.Level.EASY && outcome(player) == Outcome.WON;
    }

    /** Whether this finished game counts on the {@code hard} board: a win against the hard Arcade. */
    public boolean hardWin(UUID player) {
        return vsArcade() && level == ConnectFourAI.Level.HARD && outcome(player) == Outcome.WON;
    }
}
