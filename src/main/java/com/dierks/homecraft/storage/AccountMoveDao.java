package com.dierks.homecraft.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@code /hcm account move <from> <to>} (SHOP-V2-SPEC §3.17, D40): every economy record of one account moved to
 * another, in ONE transaction. A Bedrock player linked to a Java account through Floodgate joins with the Java
 * account's UUID, and an unlinked Java account is a new UUID too, so the daughter's own Java account later is a new
 * account to this plugin either way: this carries her tokens, her unlocks and claims (golf balls, collectors,
 * Keepers and their numbers), her looks, her settings and her Minis.
 *
 * <p>What moves is {@link #MOVED}, in that order; every other table with a player or owner column is in
 * {@link #NOT_MOVED} with its reason (a test pins that the two lists cover the schema). The move is refused, and
 * nothing changes, when {@code to} already has a lifetime claim, a look or a Keeper number of an id {@code from}
 * has, or owns a Mini: two of one thing can't be merged. Items are the players' to move: the old account's things
 * go by the trade window, before linking.
 *
 * <p>Two columns stay where they are on purpose: {@code playtime_tokens} counts the playtime tokens paid against
 * that account's own play statistic, so adding it to {@code to} would hold back the new account's own and zeroing
 * it would pay the old account's whole playtime again at its next join; and {@code from}'s
 * {@code last_streak_day}, so the old account can't claim today's streak a second time.
 */
public final class AccountMoveDao {

    /** The tables moved, in the order the move runs. */
    public static final List<String> MOVED = List.of("arcade_tokens", "token_ledger", "prize_purchases",
            "cosmetics_owned", "arcade_state", "game_prefs", "achievements_unlocked", "quest_progress",
            "player_counters", "binder_cards", "mini_individuals");

    /** Every other table with a player or owner column, and why it stays with the old account. */
    public static final Map<String, String> NOT_MOVED = notMoved();

    private static Map<String, String> notMoved() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("placed_blocks", "a block standing in the world stays its placer's (an admin can break it)");
        m.put("displays", "an economy display standing in the world");
        m.put("sound_mufflers", "a Sound Muffler standing in the world");
        m.put("mini_listings", "a Display Case listing belongs to its block: take it back before the move");
        m.put("mini_vending_listings", "a Vending listing belongs to its machine: take it back before the move");
        m.put("pallet_listings", "a Pallet listing belongs to its block: take it back before the move");
        m.put("mini_auctions", "an auction runs to its end on the old account");
        m.put("mini_sales", "sales: history");
        m.put("mini_notifications", "messages for the old account, shown when it joins");
        m.put("mini_pending", "Minis owed to the old account, handed over when it joins");
        m.put("deliveries", "Mailbox items: collect them on the old account and trade them over");
        m.put("market_orders", "market orders: history");
        m.put("market_daily_sells", "today's market tallies: history");
        m.put("market_daily_buys", "today's market tallies: history");
        m.put("market_news_seen", "where the market news was read up to");
        m.put("courier_jobs", "Courier runs: history");
        m.put("courier_debt", "Courier: history");
        m.put("courier_sites", "Courier buildings: history");
        m.put("hunt_lures", "an armed Mini Lure: a moment, not a belonging");
        m.put("quest_assignments", "today's quest picks are made again");
        m.put("quest_biomes", "this period's biomes for a quest: made again");
        m.put("player_biomes", "biomes seen: history");
        m.put("game_rounds", "games of chance: history");
        m.put("game_breaks", "Take a break: set it again for the new account");
        m.put("game_scores", "high scores: history");
        m.put("game_splits", "checkpoint splits of a best run: history, with its score");
        m.put("game_rewards", "game rewards paid: history");
        m.put("game_saved_state", "a world game in progress (the move is refused while either player is in one)");
        m.put("cup_entries", "the Weekly Cup: history");
        m.put("game_event_entries", "race nights and events: history");
        m.put("game_event_races", "race nights and events: history");
        m.put("trades", "the trade log: history");
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * What a move would do, or did: how many of each record, and the overlap that refuses it (empty when it may
     * go on).
     */
    public record Plan(int tokens, int ledgerLines, int purchases, int looks, int keeperNumbers, int prefs,
                       int unlocks, int quests, int counters, int binderCards, int minis, List<String> overlap) {
        public boolean refused() {
            return !overlap.isEmpty();
        }

        /** One line each, for the admin's dry run. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add(tokens + " tokens (" + ledgerLines + " ledger lines)");
            out.add(purchases + " prize purchases and claims (golf balls, collectors, Keepers, limits)");
            out.add(looks + " looks (trails, rented hats and caps, Keepers)");
            out.add(keeperNumbers + " Keeper numbers");
            out.add(prefs + " settings");
            out.add(unlocks + " achievements, " + quests + " quest rows, " + counters + " counters");
            out.add(binderCards + " Cards in the binder");
            out.add(minis + " Minis");
            return out;
        }
    }

    private final Database database;

    public AccountMoveDao(Database database) {
        this.database = database;
    }

    /** The dry run: what would move, and what refuses it. Reads only. */
    public Plan plan(UUID from, UUID to) throws SQLException {
        return database.transaction(c -> plan(c, from, to));
    }

    /**
     * The move, as one transaction: re-planned inside the lock, refused (nothing written) on any overlap, else every
     * table of {@link #MOVED}. Anything that fails rolls the whole move back.
     *
     * @return what moved, or the refused plan
     */
    public Plan move(UUID from, UUID to, long now, String fromName, String toName) throws SQLException {
        return database.transaction(c -> {
            Plan plan = plan(c, from, to);
            if (plan.refused()) {
                return plan;
            }
            String f = from.toString();
            String t = to.toString();
            // arcade_tokens: the balance added, the streak the larger, from's balance and streak zeroed.
            int[] mine = tokens(c, f);
            int[] theirs = tokens(c, t);
            int after = theirs[0] + mine[0];
            ensureTokens(c, t);
            update(c, "UPDATE arcade_tokens SET tokens = ?, streak = MAX(streak, ?), "
                    + "last_streak_day = MAX(last_streak_day, ?) WHERE player = ?", after, mine[1], (long) mine[2], t);
            update(c, "UPDATE arcade_tokens SET tokens = 0, streak = 0 WHERE player = ?", f);
            // token_ledger: the history moves; one line each side names the move.
            update(c, "UPDATE token_ledger SET player = ? WHERE player = ?", t, f);
            ledger(c, f, -mine[0], 0, "Account move to " + toName, now);
            ledger(c, t, mine[0], after, "Account move from " + fromName, now);
            // prize_purchases: every row; a limit period both have counts both.
            update(c, "INSERT INTO prize_purchases(player, prize_id, period_key, count) "
                    + "SELECT ?, prize_id, period_key, count FROM prize_purchases WHERE player = ? "
                    + "ON CONFLICT(player, prize_id, period_key) DO UPDATE SET "
                    + "count = prize_purchases.count + excluded.count", t, f);
            update(c, "DELETE FROM prize_purchases WHERE player = ?", f);
            // cosmetics_owned: every row (no id overlaps); one trail on at a time, so to's stays the one on.
            if (count(c, "SELECT COUNT(*) FROM cosmetics_owned WHERE player = ? AND kind = 'TRAIL' AND enabled = 1",
                    t) > 0) {
                update(c, "UPDATE cosmetics_owned SET enabled = 0 WHERE player = ? AND kind = 'TRAIL'", f);
            }
            update(c, "UPDATE cosmetics_owned SET player = ? WHERE player = ?", t, f);
            update(c, "UPDATE cosmetics_owned SET given_by = ? WHERE given_by = ?", t, f);
            // arcade_state: keeper_no:<id>:<from> becomes keeper_no:<id>:<to> (the Crown keeps its "No. N").
            for (String key : keeperKeys(c, f)) {
                update(c, "UPDATE arcade_state SET key = ? WHERE key = ?", key.substring(0, key.length() - f.length()) + t,
                        key);
            }
            // game_prefs: every pref, from's wins.
            update(c, "INSERT INTO game_prefs(player, pref, value) SELECT ?, pref, value FROM game_prefs WHERE player = ? "
                    + "ON CONFLICT(player, pref) DO UPDATE SET value = excluded.value", t, f);
            update(c, "DELETE FROM game_prefs WHERE player = ?", f);
            // achievements: the earlier unlock stays.
            update(c, "INSERT INTO achievements_unlocked(player, achievement, unlocked_at) "
                    + "SELECT ?, achievement, unlocked_at FROM achievements_unlocked WHERE player = ? "
                    + "ON CONFLICT(player, achievement) DO UPDATE SET unlocked_at = "
                    + "MIN(achievements_unlocked.unlocked_at, excluded.unlocked_at)", t, f);
            update(c, "DELETE FROM achievements_unlocked WHERE player = ?", f);
            // quest_progress: the larger progress, claimed if either claimed; a statistic quest watches the new
            // account's own statistic from now (stat_mark -1 = never seen).
            update(c, "INSERT INTO quest_progress(player, quest_id, period_key, progress, claimed, stat_mark) "
                    + "SELECT ?, quest_id, period_key, progress, claimed, -1 FROM quest_progress WHERE player = ? "
                    + "ON CONFLICT(player, quest_id, period_key) DO UPDATE SET "
                    + "progress = MAX(quest_progress.progress, excluded.progress), "
                    + "claimed = MAX(quest_progress.claimed, excluded.claimed)", t, f);
            update(c, "DELETE FROM quest_progress WHERE player = ?", f);
            // player_counters: the larger count.
            update(c, "INSERT INTO player_counters(player, counter, value) "
                    + "SELECT ?, counter, value FROM player_counters WHERE player = ? "
                    + "ON CONFLICT(player, counter) DO UPDATE SET value = MAX(player_counters.value, excluded.value)",
                    t, f);
            update(c, "DELETE FROM player_counters WHERE player = ?", f);
            // binder_cards: Cards are real things kept in the binder, so both binders' Cards add up.
            update(c, "INSERT INTO binder_cards(player, card_id, count) SELECT ?, card_id, count FROM binder_cards "
                    + "WHERE player = ? ON CONFLICT(player, card_id) DO UPDATE SET "
                    + "count = binder_cards.count + excluded.count", t, f);
            update(c, "DELETE FROM binder_cards WHERE player = ?", f);
            // mini_individuals: every Mini she owns.
            update(c, "UPDATE mini_individuals SET owner = ? WHERE owner = ?", t, f);
            return plan;
        });
    }

    private static Plan plan(Connection c, UUID from, UUID to) throws SQLException {
        String f = from.toString();
        String t = to.toString();
        List<String> overlap = new ArrayList<>();
        if (from.equals(to)) {
            overlap.add("the same account");
        }
        String life = "SELECT prize_id FROM prize_purchases WHERE player = ? AND period_key = 'life'";
        Set<String> lifeBoth = both(ids(c, life, f), ids(c, life, t));
        if (!lifeBoth.isEmpty()) {
            overlap.add("both have " + String.join(", ", lifeBoth) + " (prize claims)");
        }
        Set<String> looksBoth = both(ids(c, "SELECT cosmetic_id FROM cosmetics_owned WHERE player = ?", f),
                ids(c, "SELECT cosmetic_id FROM cosmetics_owned WHERE player = ?", t));
        if (!looksBoth.isEmpty()) {
            overlap.add("both have " + String.join(", ", looksBoth) + " (looks)");
        }
        Set<String> numbersBoth = both(keeperIds(keeperKeys(c, f), f), keeperIds(keeperKeys(c, t), t));
        if (!numbersBoth.isEmpty()) {
            overlap.add("both have a number for " + String.join(", ", numbersBoth) + " (Keepers)");
        }
        int theirMinis = count(c, "SELECT COUNT(*) FROM mini_individuals WHERE owner = ?", t);
        if (theirMinis > 0) {
            overlap.add("the new account owns " + theirMinis + (theirMinis == 1 ? " Mini" : " Minis") + " already"
                    + " (trade them back to the old account, move, then trade them over)");
        }
        return new Plan(tokens(c, f)[0], count(c, "SELECT COUNT(*) FROM token_ledger WHERE player = ?", f),
                count(c, "SELECT COUNT(*) FROM prize_purchases WHERE player = ?", f),
                count(c, "SELECT COUNT(*) FROM cosmetics_owned WHERE player = ?", f),
                keeperKeys(c, f).size(),
                count(c, "SELECT COUNT(*) FROM game_prefs WHERE player = ?", f),
                count(c, "SELECT COUNT(*) FROM achievements_unlocked WHERE player = ?", f),
                count(c, "SELECT COUNT(*) FROM quest_progress WHERE player = ?", f),
                count(c, "SELECT COUNT(*) FROM player_counters WHERE player = ?", f),
                count(c, "SELECT COALESCE(SUM(count), 0) FROM binder_cards WHERE player = ?", f),
                count(c, "SELECT COUNT(*) FROM mini_individuals WHERE owner = ?", f),
                List.copyOf(overlap));
    }

    /** The {@code arcade_state} keys of a player's Keeper numbers: {@code keeper_no:<id>:<uuid>}. */
    private static List<String> keeperKeys(Connection c, String player) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT key FROM arcade_state WHERE key LIKE ? ORDER BY key")) {
            ps.setString(1, "keeper_no:%:" + player);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static Set<String> keeperIds(List<String> keys, String player) {
        Set<String> out = new TreeSet<>();
        for (String k : keys) {
            out.add(k.substring("keeper_no:".length(), k.length() - player.length() - 1));
        }
        return out;
    }

    private static Set<String> ids(Connection c, String sql, String player) throws SQLException {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static Set<String> both(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String s : a) {
            if (b.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** {tokens, streak, last_streak_day} of a player's row, zeros for none. */
    private static int[] tokens(Connection c, String player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT tokens, streak, last_streak_day FROM arcade_tokens WHERE player = ?")) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[] {rs.getInt(1), rs.getInt(2), (int) rs.getLong(3)} : new int[3];
            }
        }
    }

    private static void ensureTokens(Connection c, String player) throws SQLException {
        update(c, "INSERT INTO arcade_tokens(player) VALUES(?) ON CONFLICT(player) DO NOTHING", player);
    }

    private static void ledger(Connection c, String player, int delta, int after, String detail, long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO token_ledger(player, delta, balance_after, source, detail, at) VALUES(?,?,?,'ADMIN',?,?)")) {
            ps.setString(1, player);
            ps.setInt(2, delta);
            ps.setInt(3, after);
            ps.setString(4, detail);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    private static int count(Connection c, String sql, String player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
