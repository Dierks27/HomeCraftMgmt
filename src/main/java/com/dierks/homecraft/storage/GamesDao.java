package com.dierks.homecraft.storage;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.ChanceRounds;
import com.dierks.homecraft.games.ChanceRounds.Round;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.world.SavedState;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Every games table (schema v34): rounds of the games of chance, skill rewards, scores, Take a
 * break, the saved state of players in a world game, courses, per-player preferences, and the
 * server's daily-board secret.
 *
 * <p>The rule that shapes this class: <b>anything that moves tokens is ONE transaction</b>. The
 * tokens put in, the round row and the tokens back land together or not at all, through
 * {@link TokenDao#change} called inside {@link Database#transaction} (which it joins). That is
 * what makes a game crash-safe — a restart can never leave tokens taken without a round, or a
 * round paid twice — and what makes {@code /hcm tokens audit} tell the truth. A refused debit
 * writes nothing; a guarded close ({@code ... WHERE state = 'OPEN'}) pays only for the call that
 * flipped the row; a one-time reward is a row the database refuses to write twice. Never call
 * {@code TokenService.change} from here: it swallows the SQL error and the transaction would
 * commit half a play.
 *
 * <p>Ledger details are stored colour-stripped. {@code now} is always passed in by the caller.
 * Everything runs on the main thread against the plugin's one connection, like every DAO.
 */
public final class GamesDao {

    // ---- rows ---------------------------------------------------------------------------------

    /** One line of a score board. */
    public record ScoreRow(UUID player, long score, long at) {
    }

    /**
     * A player's Take a break settings ({@code game_breaks}). A player with no row has
     * {@link #empty}: no limits, nothing pending, not paused.
     *
     * @param dailyTokens      their own daily limit, or -1 for none
     * @param pendingTokens    a raise waiting to start (-1 = "no limit"), or -2 for none
     * @param pendingDay       the local day it starts
     * @param pausedUntil      their own pause end (epoch ms), 0 for none
     * @param adminTokens      an admin's limit for them, or -1 for none
     * @param adminPausedUntil an admin's pause end, 0 for none
     * @param updatedAt        when it last changed
     */
    public record BreakRow(UUID player, int dailyTokens, int pendingTokens, long pendingDay, long pausedUntil,
                           int adminTokens, long adminPausedUntil, long updatedAt) {

        public static BreakRow empty(UUID player) {
            return new BreakRow(player, -1, -2, 0, 0, -1, 0, 0);
        }

        public BreakRow withDailyTokens(int v) {
            return new BreakRow(player, v, pendingTokens, pendingDay, pausedUntil, adminTokens, adminPausedUntil, updatedAt);
        }

        public BreakRow withPending(int tokens, long day) {
            return new BreakRow(player, dailyTokens, tokens, day, pausedUntil, adminTokens, adminPausedUntil, updatedAt);
        }

        public BreakRow withPausedUntil(long v) {
            return new BreakRow(player, dailyTokens, pendingTokens, pendingDay, v, adminTokens, adminPausedUntil, updatedAt);
        }

        public BreakRow withAdminTokens(int v) {
            return new BreakRow(player, dailyTokens, pendingTokens, pendingDay, pausedUntil, v, adminPausedUntil, updatedAt);
        }

        public BreakRow withAdminPausedUntil(long v) {
            return new BreakRow(player, dailyTokens, pendingTokens, pendingDay, pausedUntil, adminTokens, v, updatedAt);
        }

        public BreakRow withUpdatedAt(long v) {
            return new BreakRow(player, dailyTokens, pendingTokens, pendingDay, pausedUntil, adminTokens, adminPausedUntil, v);
        }
    }

    /**
     * A world game's course ({@code game_courses}). {@code data} is the game's own definition as
     * YAML text; {@code rev} goes up with every geometry edit, so a run can tell it is stale.
     */
    public record CourseRow(String id, String game, String kind, String name, String world, boolean enabled,
                            String data, int rev, long createdAt, long updatedAt) {
    }

    /**
     * The ledger sources that count as "tokens put into games of chance" for the day's limit
     * (R1.10): the Scratch Ticket, Crates, Card Packs bought with tokens, and the five games of
     * chance. Only their NEGATIVE ledger lines count.
     */
    public static final List<String> CHANCE_SOURCES = List.of(
            TokenService.Source.LOTTO.name(), TokenService.Source.CRATE.name(), TokenService.Source.PACK.name(),
            TokenService.Source.ARCADE_SLOTS.name(), TokenService.Source.ARCADE_TWENTY_ONE.name(),
            TokenService.Source.ARCADE_WHEEL.name(), TokenService.Source.ARCADE_HILO.name(),
            TokenService.Source.ARCADE_COIN_FLIP.name());

    /** The game a reward that is once a day across games is stored under. */
    public static final String ACROSS_GAMES = "*";

    private static final Pattern COLOUR = Pattern.compile("(?i)[&§][0-9a-fk-or]");
    private static final String ROUND_COLUMNS = "id, player, game, seed, stake, data, state";
    private static final String SECRET_KEY = "games_secret";

    /** Thrown inside a transaction to roll it back after a refusal that came too late to avoid. */
    private static final class Refused extends RuntimeException {
        Refused() {
            super(null, null, false, false);
        }
    }

    private final Database database;
    private final TokenDao tokens;

    public GamesDao(Database database) {
        this.database = database;
        this.tokens = new TokenDao(database);
    }

    // ---- rounds -------------------------------------------------------------------------------

    /**
     * One instant play in one transaction: take {@code stake}, write the SETTLED round, pay
     * {@code payout}.
     *
     * @return the round, or {@code null} when the stake was refused (nothing is written)
     */
    public Round settleRound(UUID player, String game, TokenService.Source source, long day, int stake, int payout,
                             long seed, String data, String detailStake, String detailPayout, long now)
            throws SQLException {
        requireNonNegative(stake, payout);
        return database.transaction(c -> {
            if (stake > 0 && tokens.change(player, -stake, source.name(), plain(detailStake), now) == TokenDao.REFUSED) {
                return null;
            }
            long id = insertRound(c, player, game, day, stake, payout, ChanceRounds.SETTLED, seed, data, null, now, now);
            if (payout > 0) {
                tokens.change(player, payout, source.name(), plain(detailPayout), now);
            }
            return new Round(id, game, player, seed, stake, data, ChanceRounds.SETTLED);
        });
    }

    /**
     * Start a multi-step round: take {@code stake} and write the OPEN row, in one transaction.
     *
     * @return the round, or {@code null} when the stake was refused or the player already has an
     *         OPEN round in this game (resume that one); nothing is written either way
     */
    public Round openRound(UUID player, String game, TokenService.Source source, long day, int stake, long seed,
                           String data, String detail, long now) throws SQLException {
        requireNonNegative(stake, 0);
        return database.transaction(c -> {
            if (openRoundFor(c, player, game) != null) {
                return null;
            }
            if (stake > 0 && tokens.change(player, -stake, source.name(), plain(detail), now) == TokenDao.REFUSED) {
                return null;
            }
            long id = insertRound(c, player, game, day, stake, 0, ChanceRounds.OPEN, seed, data, null, now, null);
            return new Round(id, game, player, seed, stake, data, ChanceRounds.OPEN);
        });
    }

    /**
     * Put {@code extra} more into an OPEN round (a Double), re-checking everything inside the
     * transaction, at the moment of the debit (R1.10): the round is still OPEN and is the
     * player's, {@code gateCheck} still passes (the pause and the day's limit with the extra), and
     * the balance covers it.
     *
     * @param gateCheck the caller's gate steps for the extra amount, run inside the transaction
     *                  (it may read through this DAO); {@code null} = none
     * @param data      the round's new data, or {@code null} to keep it
     * @return false when refused; nothing is written then
     */
    public boolean raiseStake(long roundId, UUID player, TokenService.Source source, int extra,
                              BooleanSupplier gateCheck, String data, String detail, long now) throws SQLException {
        if (extra <= 0) {
            throw new IllegalArgumentException("a raise puts more in: " + extra);
        }
        return database.transaction(c -> {
            Round r = round(c, roundId);
            if (r == null || !r.open() || !r.player().equals(player)) {
                return false;
            }
            if (gateCheck != null && !gateCheck.getAsBoolean()) {
                return false;
            }
            if (tokens.change(player, -extra, source.name(), plain(detail), now) == TokenDao.REFUSED) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE game_rounds SET stake = stake + ?, data = COALESCE(?, data), touched_at = ? "
                            + "WHERE id = ? AND state = 'OPEN'")) {
                ps.setInt(1, extra);
                ps.setString(2, data);
                ps.setLong(3, now);
                ps.setLong(4, roundId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /**
     * Record a round's new data (an action), only while it is OPEN.
     *
     * @return false if it was already settled (or does not exist)
     */
    public boolean updateRound(long roundId, String data, long now) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE game_rounds SET data = ?, touched_at = ? WHERE id = ? AND state = 'OPEN'")) {
                ps.setString(1, data);
                ps.setLong(2, now);
                ps.setLong(3, roundId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /**
     * Settle an OPEN round: set SETTLED with its payout and pay it, in one transaction. Guarded
     * on {@code state = 'OPEN'}: only the call that flips the row pays.
     *
     * @param data the final data, or {@code null} to keep it
     * @return false if it was already settled (or does not exist) — nothing is paid then
     */
    public boolean closeRound(long roundId, int payout, String data, TokenService.Source source, String detail,
                              long now) throws SQLException {
        requireNonNegative(0, payout);
        return database.transaction(c -> {
            Round r = round(c, roundId);
            if (r == null) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE game_rounds SET state = 'SETTLED', payout = ?, data = COALESCE(?, data), "
                            + "settled_at = ?, touched_at = ? WHERE id = ? AND state = 'OPEN'")) {
                ps.setInt(1, payout);
                ps.setString(2, data);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setLong(5, roundId);
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            if (payout > 0) {
                tokens.change(r.player(), payout, source.name(), plain(detail), now);
            }
            return true;
        });
    }

    /**
     * A Coin Flip between two players: take {@code stake} from both, write one SETTLED round per
     * player (each naming the other as {@code opponent}; the payout on the winner's row) and pay
     * the winner — all in one transaction. If the second player's tokens are refused, the first
     * player's are put back by rolling the whole transaction back.
     *
     * <p>Called inside a caller's transaction, a refusal rolls back that whole transaction (the
     * rollback has to reach it); call it on its own.
     *
     * @param winner {@code a} or {@code b}
     * @param data   stored on both rows (it carries the shared pair id)
     * @return {@code [a's round, b's round]}, or {@code null} when either was refused (nothing is
     *         written)
     */
    public List<Round> pairRound(UUID a, UUID b, String game, TokenService.Source source, long day, int stake,
                                 long seed, UUID winner, int payout, String data, String detailStake,
                                 String detailPayout, long now) throws SQLException {
        requireNonNegative(stake, payout);
        if (a.equals(b) || !(winner.equals(a) || winner.equals(b))) {
            throw new IllegalArgumentException("a pair round is two different players, one of whom wins");
        }
        boolean nested = !database.connection().getAutoCommit();
        try {
            return database.transaction(c -> {
                if (stake > 0 && tokens.change(a, -stake, source.name(), plain(detailStake), now) == TokenDao.REFUSED) {
                    return null;
                }
                if (stake > 0 && tokens.change(b, -stake, source.name(), plain(detailStake), now) == TokenDao.REFUSED) {
                    throw new Refused(); // a's tokens are already taken: undo everything
                }
                int payA = winner.equals(a) ? payout : 0;
                int payB = payout - payA;
                long idA = insertRound(c, a, game, day, stake, payA, ChanceRounds.SETTLED, seed, data, b, now, now);
                long idB = insertRound(c, b, game, day, stake, payB, ChanceRounds.SETTLED, seed, data, a, now, now);
                if (payout > 0) {
                    tokens.change(winner, payout, source.name(), plain(detailPayout), now);
                }
                return List.of(new Round(idA, game, a, seed, stake, data, ChanceRounds.SETTLED),
                        new Round(idB, game, b, seed, stake, data, ChanceRounds.SETTLED));
            });
        } catch (Refused r) {
            if (nested) {
                throw r;
            }
            return null;
        }
    }

    /** One round by id, or {@code null}. */
    public Round round(long roundId) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return round(c, roundId);
        }
    }

    /** The player's OPEN round in this game (to resume), or {@code null}. */
    public Round openRoundFor(UUID player, String game) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return openRoundFor(c, player, game);
        }
    }

    /** Every OPEN round of this player, oldest first. */
    public List<Round> openRounds(UUID player) throws SQLException {
        return rounds("SELECT " + ROUND_COLUMNS + " FROM game_rounds WHERE state = 'OPEN' AND player = ? ORDER BY id",
                player.toString());
    }

    /** Every OPEN round on the server, oldest first (start-up settles those of offline players). */
    public List<Round> openRounds() throws SQLException {
        return rounds("SELECT " + ROUND_COLUMNS + " FROM game_rounds WHERE state = 'OPEN' ORDER BY id");
    }

    /**
     * OPEN rounds nobody has touched since {@code untouchedSince} ({@code touched_at} is set on
     * open, step and raise): start-up and the one-minute sweep settle those left 10 minutes.
     */
    public List<Round> staleOpenRounds(long untouchedSince) throws SQLException {
        return rounds("SELECT " + ROUND_COLUMNS + " FROM game_rounds WHERE state = 'OPEN' AND touched_at < ? "
                + "ORDER BY id", untouchedSince);
    }

    /** Rounds of this game the player played on this local day (OPEN or SETTLED). */
    public int playsToday(UUID player, String game, long day) throws SQLException {
        return count("SELECT COUNT(*) FROM game_rounds WHERE player = ? AND game = ? AND day = ? "
                + "AND state IN ('OPEN', 'SETTLED')", player.toString(), game, day);
    }

    /**
     * Rounds of this game between {@code a} and {@code b} on this local day, whoever asked whom:
     * every pair round writes one row per player, so {@code a}'s rows against {@code b} count
     * each flip once.
     */
    public int pairPlaysToday(UUID a, UUID b, String game, long day) throws SQLException {
        return count("SELECT COUNT(*) FROM game_rounds WHERE game = ? AND day = ? AND player = ? AND opponent = ? "
                + "AND state = 'SETTLED'", game, day, a.toString(), b.toString());
    }

    /**
     * Tokens the player put into games of chance since {@code dayStart}: the sum of their
     * negative ledger lines under {@link #CHANCE_SOURCES} (R1.10). The ledger is the one record
     * every game of chance — new and old — already writes, so nothing can go uncounted.
     */
    public int chanceTokensToday(UUID player, long dayStart) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return tokensIn(c, player, dayStart);
        }
    }

    /**
     * Mark the player's one scored attempt at today's daily board (R2.13): an attempt row is
     * written the first time the board is dealt, and never again that day.
     *
     * @return true if this is the first attempt today (the row was written), false if the player
     *         already had theirs (later plays are practice)
     */
    public boolean markDailyAttempt(UUID player, String game, long day, long seed, String data, long now)
            throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO game_rounds(player, game, day, stake, payout, state, seed, data, "
                            + "created_at, touched_at) VALUES(?,?,?,0,0,'DAILY',?,?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, game);
                ps.setLong(3, day);
                ps.setLong(4, seed);
                ps.setString(5, data);
                ps.setLong(6, now);
                ps.setLong(7, now);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /** Whether the player already had their scored attempt at this game's daily board today. */
    public boolean dailyAttempt(UUID player, String game, long day) throws SQLException {
        return count("SELECT COUNT(*) FROM game_rounds WHERE player = ? AND game = ? AND day = ? AND state = 'DAILY'",
                player.toString(), game, day) > 0;
    }

    // ---- rewards ------------------------------------------------------------------------------

    /**
     * Pay a skill reward, in one transaction: the caps, the reward row and the tokens.
     *
     * <ul>
     *   <li>A {@link RewardKind#capped() capped} kind pays at most what is left of the game's
     *       daily cap and of the server-wide cap (maybe only part of {@code tokens}, maybe 0).
     *       A first clear is not capped.</li>
     *   <li>A {@code once} reward needs a stable, non-empty {@code ref}; the database refuses a
     *       second row for the same (player, game, kind, ref), so it pays at most once. A kind
     *       that is {@link RewardKind#acrossGames() once across games} is stored under
     *       {@link #ACROSS_GAMES}. A repeatable reward is stored with an empty ref.</li>
     *   <li>Nothing is written for a reward that pays 0, so a one-time reward capped away today
     *       can still be earned another day.</li>
     * </ul>
     *
     * @param capGame the game's {@code daily_cap}, or -1 for none
     * @param capAll  {@code games.skill_daily_cap}, or -1 for none
     * @return the tokens actually paid, 0 to {@code tokens}
     */
    public int payReward(UUID player, String game, TokenService.Source source, long day, RewardKind kind,
                         String ref, int tokens, int capGame, int capAll, boolean once, String detail,
                         long now) throws SQLException {
        if (tokens <= 0) {
            return 0;
        }
        String r = ref == null ? "" : ref.trim();
        if (once && r.isEmpty()) {
            throw new IllegalArgumentException("a one-time reward needs a ref: " + kind);
        }
        String stored = once ? r : "";
        String key = kind.acrossGames() ? ACROSS_GAMES : game;
        return database.transaction(c -> {
            int pay = tokens;
            if (kind.capped()) {
                if (capGame >= 0) {
                    pay = Math.min(pay, capGame - cappedSum(c, player, day, game));
                }
                if (capAll >= 0) {
                    pay = Math.min(pay, capAll - cappedSum(c, player, day, null));
                }
            }
            if (pay <= 0) {
                return 0;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    (once ? "INSERT OR IGNORE" : "INSERT")
                            + " INTO game_rewards(player, game, played, day, kind, ref, tokens, at) "
                            + "VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, key);
                ps.setString(3, game);
                ps.setLong(4, day);
                ps.setString(5, kind.name());
                ps.setString(6, stored);
                ps.setInt(7, pay);
                ps.setLong(8, now);
                if (ps.executeUpdate() != 1) {
                    return 0; // a one-time reward that was already paid
                }
            }
            this.tokens.change(player, pay, source.name(), plain(detail), now);
            return pay;
        });
    }

    /** Capped rewards paid to the player today across every game (what the server-wide cap counts). */
    public int rewardsToday(UUID player, long day) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return cappedSum(c, player, day, null);
        }
    }

    /** Capped rewards paid to the player today by this game (what its {@code daily_cap} counts). */
    public int rewardsToday(UUID player, String game, long day) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return cappedSum(c, player, day, game);
        }
    }

    /** Whether a one-time reward was already paid (for "today's reward: done" on a tile). */
    public boolean rewardPaid(UUID player, String game, RewardKind kind, String ref) throws SQLException {
        return count("SELECT COUNT(*) FROM game_rewards WHERE player = ? AND game = ? AND kind = ? AND ref = ?",
                player.toString(), kind.acrossGames() ? ACROSS_GAMES : game, kind.name(),
                ref == null ? "" : ref.trim()) > 0;
    }

    // ---- scores -------------------------------------------------------------------------------

    /**
     * Record a score: keep it if it is the player's best on the board, count the run either way.
     * A tie with someone else's best leaves the record with whoever got there first.
     */
    public ScoreResult submit(UUID player, String game, String board, long score, boolean lowerIsBetter, long now)
            throws SQLException {
        return database.transaction(c -> {
            Long prev = best(c, player, game, board);
            boolean pb = prev == null || (lowerIsBetter ? score < prev : score > prev);
            if (prev == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO game_scores(player, game, board, score, at, runs) VALUES(?,?,?,?,?,1)")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, game);
                    ps.setString(3, board);
                    ps.setLong(4, score);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(pb
                        ? "UPDATE game_scores SET score = ?, at = ?, runs = runs + 1 WHERE player = ? AND game = ? AND board = ?"
                        : "UPDATE game_scores SET runs = runs + 1 WHERE player = ? AND game = ? AND board = ?")) {
                    int i = 1;
                    if (pb) {
                        ps.setLong(i++, score);
                        ps.setLong(i++, now);
                    }
                    ps.setString(i++, player.toString());
                    ps.setString(i++, game);
                    ps.setString(i, board);
                    ps.executeUpdate();
                }
            }
            int rank = rank(c, player, game, board, lowerIsBetter);
            return new ScoreResult(pb, prev, pb && rank == 1, rank);
        });
    }

    /** The player's best on the board, or {@code null}. */
    public Long best(UUID player, String game, String board) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return best(c, player, game, board);
        }
    }

    /** The board's top {@code limit} rows, best first (ties: whoever got there first). */
    public List<ScoreRow> top(String game, String board, boolean lowerIsBetter, int limit) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player, score, at FROM game_scores WHERE game = ? AND board = ? ORDER BY score "
                            + (lowerIsBetter ? "ASC" : "DESC") + ", at ASC, player ASC LIMIT ?")) {
                ps.setString(1, game);
                ps.setString(2, board);
                ps.setInt(3, Math.max(1, limit));
                List<ScoreRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID p = uuid(rs.getString(1));
                        if (p != null) {
                            out.add(new ScoreRow(p, rs.getLong(2), rs.getLong(3)));
                        }
                    }
                }
                return out;
            }
        }
    }

    /** The board's record, or {@code null} when nobody has played it. */
    public ScoreRow record(String game, String board, boolean lowerIsBetter) throws SQLException {
        List<ScoreRow> top = top(game, board, lowerIsBetter, 1);
        return top.isEmpty() ? null : top.get(0);
    }

    /** The boards this game has scores on. */
    public List<String> boards(String game) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT board FROM game_scores WHERE game = ? ORDER BY board")) {
                ps.setString(1, game);
                List<String> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            }
        }
    }

    /**
     * Clear scores of a game: every board, or one ({@code board} non-null), for everyone or one
     * player ({@code player} non-null).
     *
     * @return rows removed
     */
    public int resetScores(String game, String board, UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            String sql = "DELETE FROM game_scores WHERE game = ?" + (board != null ? " AND board = ?" : "")
                    + (player != null ? " AND player = ?" : "");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, game);
                if (board != null) {
                    ps.setString(i++, board);
                }
                if (player != null) {
                    ps.setString(i, player.toString());
                }
                return ps.executeUpdate();
            }
        }
    }

    // ---- Take a break -------------------------------------------------------------------------

    /** The player's break settings, or {@link BreakRow#empty} when they never set any. */
    public BreakRow breakRow(UUID player) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM game_breaks WHERE player = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return BreakRow.empty(player);
                    }
                    return new BreakRow(player, rs.getInt("daily_tokens"), rs.getInt("pending_tokens"),
                            rs.getLong("pending_day"), rs.getLong("paused_until"), rs.getInt("admin_tokens"),
                            rs.getLong("admin_paused_until"), rs.getLong("updated_at"));
                }
            }
        }
    }

    /** Write the player's break settings (insert or replace). */
    public void saveBreak(BreakRow row) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO game_breaks(player, daily_tokens, pending_tokens, pending_day, paused_until, "
                            + "admin_tokens, admin_paused_until, updated_at) VALUES(?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(player) DO UPDATE SET daily_tokens = excluded.daily_tokens, "
                            + "pending_tokens = excluded.pending_tokens, pending_day = excluded.pending_day, "
                            + "paused_until = excluded.paused_until, admin_tokens = excluded.admin_tokens, "
                            + "admin_paused_until = excluded.admin_paused_until, updated_at = excluded.updated_at")) {
                ps.setString(1, row.player().toString());
                ps.setInt(2, row.dailyTokens());
                ps.setInt(3, row.pendingTokens());
                ps.setLong(4, row.pendingDay());
                ps.setLong(5, row.pausedUntil());
                ps.setInt(6, row.adminTokens());
                ps.setLong(7, row.adminPausedUntil());
                ps.setLong(8, row.updatedAt());
                ps.executeUpdate();
            }
        }
    }

    // ---- saved state (world games) ------------------------------------------------------------

    /**
     * Save a player's state on entering a world game. A plain INSERT, never an upsert: one row per
     * session ({@code session_id}), and the partial unique index allows only ONE live (not DONE)
     * row per player — so a player who already has one (a session, or one still sending them
     * back) is refused, and the entry aborts before anything about the player has changed (R2.2).
     *
     * @return false if the player already has a live row (or the session id is taken)
     */
    public boolean saveState(SavedState s) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO game_saved_state(session_id, player, game, ref, phase, session_world, items, carry, "
                            + "xp_level, xp_progress, xp_total, health, food, saturation, exhaustion, fire_ticks, air, "
                            + "game_mode, allow_flight, flying, walk_speed, fly_speed, absorption, effects, world, "
                            + "x, y, z, yaw, pitch, created_at, done_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int i = 1;
                ps.setString(i++, s.sessionId());
                ps.setString(i++, s.player().toString());
                ps.setString(i++, s.gameId());
                ps.setString(i++, s.ref() == null ? "" : s.ref());
                ps.setString(i++, s.phase());
                ps.setString(i++, s.sessionWorld());
                ps.setBytes(i++, s.items());
                ps.setBytes(i++, s.carry());
                ps.setInt(i++, s.xpLevel());
                ps.setDouble(i++, s.xpProgress());
                ps.setInt(i++, s.xpTotal());
                ps.setDouble(i++, s.health());
                ps.setInt(i++, s.food());
                ps.setDouble(i++, s.saturation());
                ps.setDouble(i++, s.exhaustion());
                ps.setInt(i++, s.fireTicks());
                ps.setInt(i++, s.air());
                ps.setString(i++, s.gameMode());
                ps.setInt(i++, s.allowFlight() ? 1 : 0);
                ps.setInt(i++, s.flying() ? 1 : 0);
                ps.setDouble(i++, s.walkSpeed());
                ps.setDouble(i++, s.flySpeed());
                ps.setDouble(i++, s.absorption());
                ps.setString(i++, s.effects());
                ps.setString(i++, s.world());
                ps.setDouble(i++, s.x());
                ps.setDouble(i++, s.y());
                ps.setDouble(i++, s.z());
                ps.setDouble(i++, s.yaw());
                ps.setDouble(i++, s.pitch());
                ps.setLong(i++, s.createdAt());
                if (s.doneAt() == null) {
                    ps.setNull(i, Types.INTEGER);
                } else {
                    ps.setLong(i, s.doneAt());
                }
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if ((e.getErrorCode() & 0xff) == 19) { // SQLITE_CONSTRAINT: a live row already exists
                    return false;
                }
                throw e;
            }
        }
    }

    /** The player's LIVE saved state (ACTIVE or RETURN), or {@code null}. */
    public SavedState loadState(UUID player) throws SQLException {
        List<SavedState> rows = states("SELECT * FROM game_saved_state WHERE player = ? AND phase <> 'DONE'",
                player.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Move the live row of THIS session ({@code sessionId}) to {@code phase} (ACTIVE or RETURN) —
     * so a late call from an old session can never touch a newer one, and a finished session is
     * never revived. Use {@link #finishState} to finish it.
     *
     * @return false if there is no such live row
     */
    public boolean setPhase(UUID player, String sessionId, String phase) throws SQLException {
        if (SavedState.DONE.equals(phase)) {
            throw new IllegalArgumentException("finish a session with finishState");
        }
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE game_saved_state SET phase = ? WHERE player = ? AND session_id = ? AND phase <> 'DONE'")) {
                ps.setString(1, phase);
                ps.setString(2, player.toString());
                ps.setString(3, sessionId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /** Store items that could not be handed back yet, for arrival or the next join (R2.5). */
    public boolean setCarry(UUID player, String sessionId, byte[] carry) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE game_saved_state SET carry = ? WHERE player = ? AND session_id = ? AND phase <> 'DONE'")) {
                ps.setBytes(1, carry);
                ps.setString(2, player.toString());
                ps.setString(3, sessionId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /**
     * The player is home: mark THIS session's row DONE (it is kept a week for support, then
     * pruned). The player may then enter again.
     *
     * @return false if there is no such live row
     */
    public boolean finishState(UUID player, String sessionId, long now) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE game_saved_state SET phase = 'DONE', done_at = ? "
                            + "WHERE player = ? AND session_id = ? AND phase <> 'DONE'")) {
                ps.setLong(1, now);
                ps.setString(2, player.toString());
                ps.setString(3, sessionId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /** Delete THIS session's row for good — only for an admin's {@code discard confirm}. */
    public boolean deleteState(UUID player, String sessionId) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM game_saved_state WHERE player = ? AND session_id = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, sessionId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /** Every row in this phase, oldest first. */
    public List<SavedState> statesInPhase(String phase) throws SQLException {
        return states("SELECT * FROM game_saved_state WHERE phase = ? ORDER BY created_at", phase);
    }

    /** Every player with a LIVE row (the recovery listener's in-memory set, §7.6). */
    public List<UUID> livePlayers() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player FROM game_saved_state WHERE phase <> 'DONE'");
                 ResultSet rs = ps.executeQuery()) {
                List<UUID> out = new ArrayList<>();
                while (rs.next()) {
                    UUID p = uuid(rs.getString(1));
                    if (p != null) {
                        out.add(p);
                    }
                }
                return out;
            }
        }
    }

    /** Delete DONE rows finished before {@code before} (kept a week for support). */
    public int pruneDone(long before) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM game_saved_state WHERE phase = 'DONE' AND done_at IS NOT NULL AND done_at < ?")) {
                ps.setLong(1, before);
                return ps.executeUpdate();
            }
        }
    }

    // ---- courses ------------------------------------------------------------------------------

    /** One course, or {@code null}. */
    public CourseRow course(String id) throws SQLException {
        List<CourseRow> rows = courses("SELECT * FROM game_courses WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Every course of a game, by id. */
    public List<CourseRow> courses(String game) throws SQLException {
        return courses("SELECT * FROM game_courses WHERE game = ? ORDER BY id", game);
    }

    /** Insert or replace a course as given (its {@code created_at} is kept when it already exists). */
    public void saveCourse(CourseRow row) throws SQLException {
        saveCourse(row, false);
    }

    /**
     * Insert or replace a course; a {@code geometryEdit} (start, checkpoints, finish, fall, holes)
     * stores the existing {@code rev} + 1 instead of {@code row.rev()}, so a run started on the old
     * layout can tell it is stale (R2.15).
     *
     * @return the stored {@code rev}
     */
    public int saveCourse(CourseRow row, boolean geometryEdit) throws SQLException {
        return database.transaction(c -> {
            int rev = row.rev();
            if (geometryEdit) {
                CourseRow old = course(row.id());
                rev = old == null ? Math.max(1, row.rev()) : old.rev() + 1;
            }
            writeCourse(c, row, rev);
            return rev;
        });
    }

    private static void writeCourse(Connection c, CourseRow row, int rev) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO game_courses(id, game, kind, name, world, enabled, data, rev, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET game = excluded.game, "
                        + "kind = excluded.kind, name = excluded.name, world = excluded.world, "
                        + "enabled = excluded.enabled, data = excluded.data, rev = excluded.rev, "
                        + "updated_at = excluded.updated_at")) {
            ps.setString(1, row.id());
            ps.setString(2, row.game());
            ps.setString(3, row.kind());
            ps.setString(4, row.name());
            ps.setString(5, row.world());
            ps.setInt(6, row.enabled() ? 1 : 0);
            ps.setString(7, row.data());
            ps.setInt(8, rev);
            ps.setLong(9, row.createdAt());
            ps.setLong(10, row.updatedAt());
            ps.executeUpdate();
        }
    }

    /** Delete a course. */
    public boolean deleteCourse(String id) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM game_courses WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() == 1;
            }
        }
    }

    // ---- preferences --------------------------------------------------------------------------

    /** A player's preference, or {@code null} when unset. */
    public String pref(UUID player, String key) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value FROM game_prefs WHERE player = ? AND pref = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    /** Set a player's preference; {@code null} removes it. */
    public void setPref(UUID player, String key, String value) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            if (value == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM game_prefs WHERE player = ? AND pref = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO game_prefs(player, pref, value) VALUES(?,?,?) "
                            + "ON CONFLICT(player, pref) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, player.toString());
                ps.setString(2, key);
                ps.setString(3, value);
                ps.executeUpdate();
            }
        }
    }

    /** Remove a player's preference. */
    public void deletePref(UUID player, String key) throws SQLException {
        setPref(player, key, null);
    }

    /** A player's preferences whose key starts with {@code prefix}, by key (queued notices: {@code notice.}). */
    public Map<String, String> prefsLike(UUID player, String prefix) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pref, value FROM game_prefs WHERE player = ? AND substr(pref, 1, ?) = ? ORDER BY pref")) {
                ps.setString(1, player.toString());
                ps.setInt(2, prefix.length());
                ps.setString(3, prefix);
                Map<String, String> out = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
                return out;
            }
        }
    }

    // ---- the daily-board secret ---------------------------------------------------------------

    /**
     * The server's secret for seeding daily boards (R2.13): a random 64-bit value made once and
     * kept in {@code hcm_meta}, so nobody can compute tomorrow's board from the day and the game
     * id alone. Never logged or published.
     */
    public long secret() throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO hcm_meta(key, value) VALUES(?, ?)")) {
                ps.setString(1, SECRET_KEY);
                ps.setString(2, Long.toString(new SecureRandom().nextLong()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM hcm_meta WHERE key = ?")) {
                ps.setString(1, SECRET_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("the games secret could not be stored");
                    }
                    try {
                        return Long.parseLong(rs.getString(1).trim());
                    } catch (NumberFormatException e) {
                        throw new SQLException("the games secret in hcm_meta is not a number", e);
                    }
                }
            }
        }
    }

    // ---- internals (caller holds the connection lock) -----------------------------------------

    private static long insertRound(Connection c, UUID player, String game, long day, int stake, int payout,
                                    String state, long seed, String data, UUID opponent, long now, Long settledAt)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO game_rounds(player, game, day, stake, payout, state, seed, data, opponent, "
                        + "created_at, touched_at, settled_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.toString());
            ps.setString(2, game);
            ps.setLong(3, day);
            ps.setInt(4, stake);
            ps.setInt(5, payout);
            ps.setString(6, state);
            ps.setLong(7, seed);
            ps.setString(8, data);
            ps.setString(9, opponent == null ? null : opponent.toString());
            ps.setLong(10, now);
            ps.setLong(11, now);
            if (settledAt == null) {
                ps.setNull(12, Types.INTEGER);
            } else {
                ps.setLong(12, settledAt);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("no id for the new round");
                }
                return keys.getLong(1);
            }
        }
    }

    private static Round round(Connection c, long roundId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + ROUND_COLUMNS + " FROM game_rounds WHERE id = ?")) {
            ps.setLong(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRound(rs) : null;
            }
        }
    }

    private static Round openRoundFor(Connection c, UUID player, String game) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + ROUND_COLUMNS + " FROM game_rounds WHERE state = 'OPEN' AND player = ? AND game = ? "
                        + "ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, player.toString());
            ps.setString(2, game);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRound(rs) : null;
            }
        }
    }

    private static Round readRound(ResultSet rs) throws SQLException {
        UUID p = uuid(rs.getString("player"));
        return new Round(rs.getLong("id"), rs.getString("game"), p == null ? new UUID(0, 0) : p,
                rs.getLong("seed"), rs.getInt("stake"), rs.getString("data"), rs.getString("state"));
    }

    private List<Round> rounds(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                List<Round> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readRound(rs));
                    }
                }
                return out;
            }
        }
    }

    private static int tokensIn(Connection c, UUID player, long dayStart) throws SQLException {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < CHANCE_SOURCES.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(-delta), 0) FROM token_ledger WHERE player = ? AND at >= ? AND delta < 0 "
                        + "AND source IN (" + in + ")")) {
            ps.setString(1, player.toString());
            ps.setLong(2, dayStart);
            for (int i = 0; i < CHANCE_SOURCES.size(); i++) {
                ps.setString(3 + i, CHANCE_SOURCES.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (int) Math.min(Integer.MAX_VALUE, rs.getLong(1)) : 0;
            }
        }
    }

    /**
     * Capped rewards today: for one game ({@code played}, its own kinds only), or across all
     * ({@code game} null).
     */
    private static int cappedSum(Connection c, UUID player, long day, String game) throws SQLException {
        StringBuilder uncapped = new StringBuilder();
        for (RewardKind k : RewardKind.values()) {
            if (!k.capped()) {
                uncapped.append(uncapped.isEmpty() ? "'" : ", '").append(k.name()).append('\'');
            }
        }
        String sql = "SELECT COALESCE(SUM(tokens), 0) FROM game_rewards WHERE player = ? AND day = ?"
                + (uncapped.isEmpty() ? "" : " AND kind NOT IN (" + uncapped + ")")
                // Across-games kinds (today's pick, the course of the week) count toward the
                // server-wide cap only, never one game's own (spec §6.1).
                + (game != null ? " AND played = ? AND game <> '" + ACROSS_GAMES + "'" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setLong(2, day);
            if (game != null) {
                ps.setString(3, game);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (int) Math.min(Integer.MAX_VALUE, rs.getLong(1)) : 0;
            }
        }
    }

    private static Long best(Connection c, UUID player, String game, String board) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT score FROM game_scores WHERE player = ? AND game = ? AND board = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, game);
            ps.setString(3, board);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** 1 + everyone strictly ahead of the player's best (a tie ranks whoever got there first). */
    private static int rank(Connection c, UUID player, String game, String board, boolean lowerIsBetter)
            throws SQLException {
        String better = lowerIsBetter ? "<" : ">";
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM game_scores o, game_scores me WHERE me.player = ? AND me.game = ? "
                        + "AND me.board = ? AND o.game = me.game AND o.board = me.board AND o.player <> me.player "
                        + "AND (o.score " + better + " me.score OR (o.score = me.score AND o.at < me.at))")) {
            ps.setString(1, player.toString());
            ps.setString(2, game);
            ps.setString(3, board);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 0;
            }
        }
    }

    private List<SavedState> states(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                List<SavedState> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID p = uuid(rs.getString("player"));
                        if (p == null) {
                            continue;
                        }
                        long doneAt = rs.getLong("done_at");
                        Long done = rs.wasNull() ? null : doneAt;
                        out.add(new SavedState(p, rs.getString("game"), rs.getString("ref"), rs.getString("phase"),
                                rs.getString("session_id"), rs.getString("session_world"), rs.getBytes("items"),
                                rs.getBytes("carry"), rs.getInt("xp_level"), rs.getFloat("xp_progress"),
                                rs.getInt("xp_total"), rs.getDouble("health"), rs.getInt("food"),
                                rs.getFloat("saturation"), rs.getFloat("exhaustion"), rs.getInt("fire_ticks"),
                                rs.getInt("air"), rs.getString("game_mode"), rs.getInt("allow_flight") != 0,
                                rs.getInt("flying") != 0, rs.getFloat("walk_speed"), rs.getFloat("fly_speed"),
                                rs.getDouble("absorption"), rs.getString("effects"), rs.getString("world"),
                                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"),
                                rs.getFloat("pitch"), rs.getLong("created_at"), done));
                    }
                }
                return out;
            }
        }
    }

    private List<CourseRow> courses(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                List<CourseRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new CourseRow(rs.getString("id"), rs.getString("game"), rs.getString("kind"),
                                rs.getString("name"), rs.getString("world"), rs.getInt("enabled") != 0,
                                rs.getString("data"), rs.getInt("rev"), rs.getLong("created_at"),
                                rs.getLong("updated_at")));
                    }
                }
                return out;
            }
        }
    }

    private int count(String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (a instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, a == null ? null : String.valueOf(a));
            }
        }
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void requireNonNegative(int stake, int payout) {
        if (stake < 0 || payout < 0) {
            throw new IllegalArgumentException("tokens in and back are never negative: " + stake + ", " + payout);
        }
    }

    /** Ledger details are stored as plain words (colour codes stripped), as TokenService does. */
    private static String plain(String s) {
        return s == null ? "" : COLOUR.matcher(s).replaceAll("").trim();
    }
}
