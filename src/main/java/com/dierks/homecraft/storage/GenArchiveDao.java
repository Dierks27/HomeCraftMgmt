package com.dierks.homecraft.storage;

import com.dierks.homecraft.games.gen.api.CourseCode;
import com.dierks.homecraft.games.gen.api.GenBoards;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fresh Courses' archive, {@code gen_editions} (schema v35, GEN-SPEC-KEEP §1, §8): one row per
 * edition that was ever playable, with its course code, its seed, its dates and its whole plan.
 *
 * <p><b>Written with the flip.</b> {@link #archive} runs inside the flip's transaction (it joins
 * {@link Database#transaction}), so the course row, the admin's bookkeeping and the archive row land
 * together or not at all: every edition that was ever live is archived, and nothing that never went
 * live is. The same call ends the slot's previous edition ({@code ends_at} = the flip).
 *
 * <p><b>Codes are never reused.</b> A code is the slot's word and the next number:
 * {@code max(the highest number ever handed out, the highest still stored) + 1}. The highest ever
 * handed out is kept in {@code hcm_meta} ({@code gen.<slot>.codes}, same transaction), so pruning
 * old rows can't bring a number back, and the unique index refuses a duplicate outright.
 *
 * <p>Also here, because they read the games' tables the history and a kept course need: what an
 * edition's board holds ({@link #stats}) and copying its rows onto a kept course's board
 * ({@link #copyBoard}). Runs on the main thread against the plugin's one connection, like every DAO.
 */
public final class GenArchiveDao {

    /**
     * One archived edition.
     *
     * @param slot      the slot it was made for
     * @param edition   its key, the board's ({@code 7:40}, {@code 7:40r1})
     * @param code      its course code ({@code HARD-40})
     * @param seq       the number in its code
     * @param day       its first local day
     * @param seed      its seed
     * @param algo      the generator and its version ({@code parkour/1})
     * @param kind      {@code parkour}, {@code elytra}, {@code golf} or {@code boat}
     * @param tierOrMix the tier or golf mix it was made with
     * @param name      the course's name then ("Hard Parkour")
     * @param startsAt  when it went live (epoch ms)
     * @param endsAt    when the next flip replaced it, or {@code null} while live
     * @param plan      the stored plan ({@code PlanCodec}), or {@code null} when not read (lists)
     * @param goldMs    its 3-star time then (0 for golf)
     * @param silverMs  its 2-star time then
     * @param builtAt   when its blocks were verified
     * @param keptAs    the course it was kept as, or {@code null}
     */
    public record Row(String slot, String edition, String code, int seq, long day, long seed, String algo, String kind,
                      String tierOrMix, String name, long startsAt, Long endsAt, byte[] plan, long goldMs,
                      long silverMs, long builtAt, String keptAs) {

        /** Its edition board: {@code gfresh:<slot>:<edition>}. */
        public String board() {
            return GenBoards.day(slot, edition);
        }

        /** The generator ({@code parkour}) from {@link #algo}. */
        public String generator() {
            int slash = algo.indexOf('/');
            return slash < 0 ? algo : algo.substring(0, slash);
        }

        /** The generator's version from {@link #algo}, or 0 when unreadable. */
        public int algoVersion() {
            int slash = algo.indexOf('/');
            try {
                return slash < 0 ? 0 : Integer.parseInt(algo.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Whether it is live (no flip has replaced it yet). */
        public boolean live() {
            return endsAt == null;
        }

        /** The same row with its stored plan. */
        public Row withPlan(byte[] p) {
            return new Row(slot, edition, code, seq, day, seed, algo, kind, tierOrMix, name, startsAt, endsAt, p,
                    goldMs, silverMs, builtAt, keptAs);
        }
    }

    /**
     * What an edition's board holds.
     *
     * @param players how many players have a time (or score) on it
     * @param plays   the counted runs on it, all players together
     */
    public record BoardStats(int players, long plays) {
    }

    private static final String COLUMNS = "slot, edition, code, seq, day, seed, algo, kind, tier_or_mix, name, "
            + "starts_at, ends_at, gold_ms, silver_ms, built_at, kept_as";

    private final Database database;
    private final GenMetaDao meta;

    public GenArchiveDao(Database database) {
        this.database = database;
        this.meta = new GenMetaDao(database);
    }

    /** The {@code hcm_meta} key holding the highest code number ever handed out for {@code slot}. */
    public static String codesKey(String slot) {
        return "gen." + slot + ".codes";
    }

    // ---- writing -----------------------------------------------------------------------------------

    /**
     * Archive the edition that is going live now, and end the slot's previous one, in one
     * transaction (joining the caller's: the flip). An edition already archived (the same key
     * flipped again, after its layout couldn't be vouched for) keeps its code and first start and
     * is live again with the new plan. {@code entry}'s code and number are ignored: the next free
     * ones are handed out here.
     *
     * @return the row as stored, with its code
     */
    public Row archive(Row entry, long now) throws SQLException {
        String word = CourseCode.slotCode(entry.slot());
        if (word == null) {
            throw new SQLException("slot " + entry.slot() + " has no course code");
        }
        return database.transaction(c -> {
            Row old = get(c, entry.slot(), entry.edition(), false);
            Row stored;
            if (old != null) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE gen_editions SET day = ?, seed = ?, algo = ?, "
                        + "kind = ?, tier_or_mix = ?, name = ?, ends_at = NULL, plan = ?, gold_ms = ?, silver_ms = ?, "
                        + "built_at = ? WHERE slot = ? AND edition = ?")) {
                    ps.setLong(1, entry.day());
                    ps.setLong(2, entry.seed());
                    ps.setString(3, entry.algo());
                    ps.setString(4, entry.kind());
                    ps.setString(5, entry.tierOrMix());
                    ps.setString(6, entry.name());
                    blob(ps, 7, entry.plan());
                    ps.setLong(8, entry.goldMs());
                    ps.setLong(9, entry.silverMs());
                    ps.setLong(10, entry.builtAt());
                    ps.setString(11, entry.slot());
                    ps.setString(12, entry.edition());
                    ps.executeUpdate();
                }
                stored = new Row(entry.slot(), entry.edition(), old.code(), old.seq(), entry.day(), entry.seed(),
                        entry.algo(), entry.kind(), entry.tierOrMix(), entry.name(), old.startsAt(), null, entry.plan(),
                        entry.goldMs(), entry.silverMs(), entry.builtAt(), old.keptAs());
            } else {
                int seq = nextSeq(c, entry.slot());
                String code = word + "-" + seq;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO gen_editions(" + COLUMNS + ", plan) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, entry.slot());
                    ps.setString(2, entry.edition());
                    ps.setString(3, code);
                    ps.setInt(4, seq);
                    ps.setLong(5, entry.day());
                    ps.setLong(6, entry.seed());
                    ps.setString(7, entry.algo());
                    ps.setString(8, entry.kind());
                    ps.setString(9, entry.tierOrMix());
                    ps.setString(10, entry.name());
                    ps.setLong(11, entry.startsAt());
                    ps.setNull(12, Types.INTEGER);
                    ps.setLong(13, entry.goldMs());
                    ps.setLong(14, entry.silverMs());
                    ps.setLong(15, entry.builtAt());
                    ps.setNull(16, Types.VARCHAR);
                    blob(ps, 17, entry.plan());
                    ps.executeUpdate();
                }
                meta.set(codesKey(entry.slot()), Integer.toString(seq));
                stored = new Row(entry.slot(), entry.edition(), code, seq, entry.day(), entry.seed(), entry.algo(),
                        entry.kind(), entry.tierOrMix(), entry.name(), entry.startsAt(), null, entry.plan(),
                        entry.goldMs(), entry.silverMs(), entry.builtAt(), null);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE gen_editions SET ends_at = ? "
                    + "WHERE slot = ? AND ends_at IS NULL AND edition <> ?")) {
                ps.setLong(1, now);
                ps.setString(2, entry.slot());
                ps.setString(3, entry.edition());
                ps.executeUpdate();
            }
            return stored;
        });
    }

    private int nextSeq(Connection c, String slot) throws SQLException {
        int max = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT MAX(seq) FROM gen_editions WHERE slot = ?")) {
            ps.setString(1, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    max = rs.getInt(1);
                }
            }
        }
        String counter = meta.get(codesKey(slot));
        if (counter != null) {
            try {
                max = Math.max(max, Integer.parseInt(counter.trim()));
            } catch (NumberFormatException e) {
                // the stored rows still count
            }
        }
        return max + 1;
    }

    /** Record the course an edition was kept as ({@code null}: none any more). @return whether a row changed */
    public boolean setKept(String slot, String edition, String courseId) throws SQLException {
        return database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE gen_editions SET kept_as = ? WHERE slot = ? AND edition = ?")) {
                ps.setString(1, courseId);
                ps.setString(2, slot);
                ps.setString(3, edition);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Forget which edition a course was kept from (the course is gone). @return rows changed */
    public int clearKept(String courseId) throws SQLException {
        return database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE gen_editions SET kept_as = NULL WHERE kept_as = ?")) {
                ps.setString(1, courseId);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Remove archived editions that ended before {@code endedBefore} (the {@code archive.keep}
     * retention), except those kept as a course and those named in {@code spared}
     * ({@code slot|edition}: recalled now). A live edition is never removed. @return rows removed
     */
    public int prune(long endedBefore, Set<String> spared) throws SQLException {
        return database.transaction(c -> {
            List<String[]> old = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT slot, edition FROM gen_editions "
                    + "WHERE ends_at IS NOT NULL AND ends_at < ? AND kept_as IS NULL")) {
                ps.setLong(1, endedBefore);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String slot = rs.getString(1);
                        String edition = rs.getString(2);
                        if (spared == null || !spared.contains(slot + "|" + edition)) {
                            old.add(new String[]{slot, edition});
                        }
                    }
                }
            }
            int removed = 0;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM gen_editions WHERE slot = ? AND edition = ?")) {
                for (String[] k : old) {
                    ps.setString(1, k[0]);
                    ps.setString(2, k[1]);
                    removed += ps.executeUpdate();
                }
            }
            return removed;
        });
    }

    // ---- reading -----------------------------------------------------------------------------------

    /** One edition with its plan, or {@code null}. */
    public Row get(String slot, String edition) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            return get(c, slot, edition, true);
        }
    }

    /** The edition with this course code (any case), with its plan, or {@code null}. */
    public Row byCode(String code) throws SQLException {
        if (code == null) {
            return null;
        }
        List<Row> rows = rows(true, "SELECT " + COLUMNS + ", plan FROM gen_editions WHERE code = ?",
                code.trim().toUpperCase(Locale.ROOT));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A slot's editions (every slot's when {@code slot} is null), newest first, without plans. */
    public List<Row> list(String slot, int offset, int limit) throws SQLException {
        int lim = Math.max(1, limit);
        int off = Math.max(0, offset);
        return slot == null
                ? rows(false, "SELECT " + COLUMNS + " FROM gen_editions ORDER BY starts_at DESC, slot, seq DESC "
                + "LIMIT ? OFFSET ?", lim, off)
                : rows(false, "SELECT " + COLUMNS + " FROM gen_editions WHERE slot = ? ORDER BY starts_at DESC, "
                + "seq DESC LIMIT ? OFFSET ?", slot, lim, off);
    }

    /** How many editions a slot has archived (every slot's when {@code slot} is null). */
    public int count(String slot) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(slot == null ? "SELECT COUNT(*) FROM gen_editions"
                    : "SELECT COUNT(*) FROM gen_editions WHERE slot = ?")) {
                if (slot != null) {
                    ps.setString(1, slot);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /**
     * The newest edition of {@code slot} that went live at or before {@code at} and was still live
     * after {@code after} (the edition up on a date), without its plan, or {@code null}.
     */
    public Row liveBetween(String slot, long after, long at) throws SQLException {
        List<Row> rows = rows(false, "SELECT " + COLUMNS + " FROM gen_editions WHERE slot = ? AND starts_at <= ? "
                + "AND (ends_at IS NULL OR ends_at > ?) ORDER BY starts_at DESC, seq DESC LIMIT 1", slot, at, after);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A slot's editions whose seed's hex starts with {@code hexPrefix} (lower case), newest first. */
    public List<Row> bySeed(String slot, String hexPrefix) throws SQLException {
        String p = hexPrefix == null ? "" : hexPrefix.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        for (Row r : slot == null ? rows(false, "SELECT " + COLUMNS + " FROM gen_editions ORDER BY starts_at DESC")
                : rows(false, "SELECT " + COLUMNS + " FROM gen_editions WHERE slot = ? ORDER BY starts_at DESC", slot)) {
            if (!p.isEmpty() && String.format(Locale.ROOT, "%016x", r.seed()).startsWith(p)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Every archived edition's board ({@code gfresh:<slot>:<edition>}): pruning keeps these. */
    public Set<String> boards() throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT slot, edition FROM gen_editions");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(GenBoards.day(rs.getString(1), rs.getString(2)));
                }
            }
        }
        return out;
    }

    /** How many players and counted runs {@code board} of {@code game} has. */
    public BoardStats stats(String game, String board) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(runs), 0) FROM game_scores WHERE game = ? AND board = ?")) {
                ps.setString(1, game);
                ps.setString(2, board);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new BoardStats(rs.getInt(1), rs.getLong(2)) : new BoardStats(0, 0);
                }
            }
        }
    }

    /**
     * Copy every row of board {@code from} onto board {@code to} of the same game (a kept course's
     * all-time board, GEN-SPEC-KEEP §4 step 6): each player's best, when they set it, and their run
     * count. A player already on {@code to} keeps their row. Joins the caller's transaction.
     *
     * @return rows copied
     */
    public int copyBoard(String game, String from, String to) throws SQLException {
        return database.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO game_scores(player, game, board, "
                    + "score, at, runs) SELECT player, game, ?, score, at, runs FROM game_scores "
                    + "WHERE game = ? AND board = ?")) {
                ps.setString(1, to);
                ps.setString(2, game);
                ps.setString(3, from);
                return ps.executeUpdate();
            }
        });
    }

    // ---- internals ---------------------------------------------------------------------------------

    private Row get(Connection c, String slot, String edition, boolean withPlan) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + (withPlan ? ", plan" : "")
                + " FROM gen_editions WHERE slot = ? AND edition = ?")) {
            ps.setString(1, slot);
            ps.setString(2, edition);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs, withPlan) : null;
            }
        }
    }

    private List<Row> rows(boolean withPlan, String sql, Object... args) throws SQLException {
        Connection c = database.connection();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (a instanceof Integer n) {
                        ps.setInt(i + 1, n);
                    } else if (a instanceof Long n) {
                        ps.setLong(i + 1, n);
                    } else {
                        ps.setString(i + 1, (String) a);
                    }
                }
                List<Row> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs, withPlan));
                    }
                }
                return out;
            }
        }
    }

    private static Row read(ResultSet rs, boolean withPlan) throws SQLException {
        long ends = rs.getLong(12);
        Long endsAt = rs.wasNull() ? null : ends;
        return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5), rs.getLong(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11), endsAt,
                withPlan ? rs.getBytes(17) : null, rs.getLong(13), rs.getLong(14), rs.getLong(15), rs.getString(16));
    }

    private static void blob(PreparedStatement ps, int i, byte[] b) throws SQLException {
        if (b == null) {
            ps.setNull(i, Types.BLOB);
        } else {
            ps.setBytes(i, b);
        }
    }
}
