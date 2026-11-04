package com.dierks.homecraft.arcade.feast;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.config.FeastConfig;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.SkillRewards;
import com.dierks.homecraft.storage.AchievementDao;
import com.dierks.homecraft.storage.FeastDao;
import com.dierks.homecraft.storage.GamesDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Family Feast's bookkeeping (0.38.6): the gifts, the fill, the reward, each helper's own moment and their Turkey
 * Mini, behind {@link FeastHost} so it runs against a real database in a test.
 *
 * <p><b>Gifts.</b> The points go on the edition's board ({@code feast:<ed>}) under the day's cap in one transaction
 * ({@code GamesDao.addPointsCapped}, the day on {@code feast:<ed>:d<day>}); a cap of 0 means no limit and uses
 * {@code addPoints}, because a capped add under 0 adds nothing. The items taken are worked out from what was really
 * added, after it was added: a failed write takes nothing. A first gift adds the helper to the order list, which the
 * Thankful line follows.
 *
 * <p><b>The fill</b>, once: the {@code done} marker is written first, then every helper is paid (offline ones too:
 * the reward is a once-only row, {@code GamesDao.payReward} with {@code event:feast-<ed>}), then every helper who is
 * home gets their Turkey Mini and their moment, everyone online gets the Thankful line, and the show over the pot
 * follows its own marker. A helper who wasn't home, or gave after the fill, is settled the first time they are home
 * inside the window: paid, their Mini, their moment. Each marker is written before the thing it guards, so a crash
 * can at worst skip a moment, never give one twice.
 *
 * <p><b>The cache.</b> The total, the helpers, the order and the markers live in memory, read at start, at reload and
 * after each gift, so the Clubhouse's once-a-second {@link #view()} never reads the database.
 */
public final class FeastCore {

    /** What a tap with food did. */
    public enum Outcome {
        /** Points added; {@code taken} items come out of the hand. */
        TAKEN,
        /** Nothing: the day's points are used up (or the food is worth more than is left). */
        FULL,
        /** Nothing: the Feast isn't open. */
        CLOSED,
        /** Nothing: the database said no. */
        FAILED
    }

    /** A tap's result: what happened, the items to take out of the hand, and the points added. */
    public record Gift(Outcome outcome, int taken, long added) {
    }

    /** A WARN of the same kind at most this often (a broken database retried each second). */
    static final long WARN_EVERY_MS = 60_000L;

    private final FeastHost host;
    private final GamesDao games;
    private final AchievementDao counters;
    private final FeastDao meta;

    private String edition;
    private long total;
    private final Set<UUID> helpers = new LinkedHashSet<>();
    private List<UUID> order = List.of();
    private boolean done;
    private boolean homeShown;
    /** Helpers with nothing more to pay now (paid, or a reward of 0); a gift takes them out again. */
    private final Set<UUID> settled = new HashSet<>();
    /** Per-player markers read once and kept: seen, room seen, Mini given. */
    private final Map<UUID, Boolean> seen = new HashMap<>();
    private final Map<UUID, Boolean> roomSeen = new HashMap<>();
    private final Map<UUID, Boolean> minted = new HashMap<>();
    private long warnedAt = Long.MIN_VALUE / 2;

    public FeastCore(FeastHost host, GamesDao games, AchievementDao counters, FeastDao meta) {
        this.host = host;
        this.games = games;
        this.counters = counters;
        this.meta = meta;
    }

    // ---- the cache ----------------------------------------------------------------------------------

    /**
     * Read the edition's total, helpers, order and markers again. A new edition (a reload that switched it) starts
     * every per-player memory afresh. @return whether it could be read
     */
    public boolean refresh() {
        String ed = host.settings().edition();
        if (!ed.equals(edition)) {
            edition = ed;
            settled.clear();
            seen.clear();
            roomSeen.clear();
            minted.clear();
            total = 0;
            helpers.clear();
            order = List.of();
            done = false;
            homeShown = false;
        }
        try {
            long sum = 0;
            Set<UUID> who = new LinkedHashSet<>();
            for (GamesDao.ScoreRow r : games.top(FeastRules.GAME, FeastRules.board(ed), false, 100)) {
                if (r.score() > 0) {
                    sum += r.score();
                    who.add(r.player());
                }
            }
            List<UUID> o = meta.order(ed);
            boolean d = meta.get(FeastRules.doneKey(ed)) != null;
            boolean h = meta.get(FeastRules.homeShownKey(ed)) != null;
            total = sum;
            helpers.clear();
            helpers.addAll(who);
            order = o;
            done = d;
            homeShown = h;
            return true;
        } catch (SQLException e) {
            warn("The Family Feast could not read its table: " + e.getMessage());
            return false;
        }
    }

    // ---- gifts --------------------------------------------------------------------------------------

    /**
     * A tap at the pot with food the pot takes ({@link FeastRules#refusal} said yes): the points, under the day's cap.
     * The caller takes {@link Gift#taken()} items out of the hand only for {@link Outcome#TAKEN}, then calls
     * {@link #afterGift}.
     *
     * @param each     one item's points
     * @param held     the items in the hand
     * @param sneaking a sneak-tap: the stack
     * @param creative the player is in Creative (a gift outside it is counted, for {@code creative_helpers_paid})
     */
    public Gift give(UUID player, int each, int held, boolean sneaking, boolean creative) {
        if (!host.open() || player == null || each <= 0) {
            return new Gift(Outcome.CLOSED, 0, 0);
        }
        FeastConfig.Feast c = host.settings();
        String ed = c.edition();
        String board = FeastRules.board(ed);
        String day = FeastRules.dayBoard(ed, host.epochDay());
        int cap = c.dailyPointsPerPlayer();
        long added;
        boolean first;
        try {
            long left = -1;
            if (cap > 0) {
                Long used = games.best(player, FeastRules.GAME, day);
                left = Math.max(0, cap - (used == null ? 0 : used));
            }
            int want = FeastRules.want(held, each, sneaking, left);
            if (want <= 0) {
                return new Gift(Outcome.FULL, 0, 0);
            }
            first = games.best(player, FeastRules.GAME, board) == null;
            long points = (long) want * each;
            long now = host.now();
            if (cap > 0) {
                added = games.addPointsCapped(player, FeastRules.GAME, board, day, points, cap, now);
            } else {
                games.addPoints(player, FeastRules.GAME, board, points, now);
                added = points;
            }
        } catch (SQLException e) {
            warn("The Family Feast could not add a gift: " + e.getMessage());
            return new Gift(Outcome.FAILED, 0, 0);
        }
        if (added <= 0) {
            return new Gift(Outcome.FULL, 0, 0);
        }
        // the gift is in: the rest is bookkeeping, and its failure never undoes it
        if (first) {
            try {
                meta.appendOrder(ed, player);
            } catch (SQLException e) {
                warn("The Family Feast could not note a first gift's order: " + e.getMessage());
            }
        }
        if (!creative) {
            try {
                counters.addCounter(player, FeastRules.survivalKey(ed), 1);
            } catch (SQLException e) {
                warn("The Family Feast could not note a gift: " + e.getMessage());
            }
        }
        settled.remove(player);
        refresh();
        return new Gift(Outcome.TAKEN, FeastRules.taken(added, each), added);
    }

    /** After a gift's items came out of the hand: the fill when this gift filled the table, or a late helper's turn. */
    public void afterGift(UUID player) {
        if (!done && FeastRules.filled(total, host.settings().goal())) {
            fill();
        } else if (done) {
            settle(player);
        }
    }

    // ---- the fill -----------------------------------------------------------------------------------

    /**
     * The tables are full, once: the marker first (not written, not run), every helper paid, every helper who is home
     * given their Mini and their moment, the Thankful line to everyone, then the show over the pot.
     */
    public void fill() {
        if (done || !host.open()) {
            return;
        }
        try {
            meta.set(FeastRules.doneKey(edition), Long.toString(host.now()));
        } catch (SQLException e) {
            warn("The Family Feast could not mark its table full; trying again: " + e.getMessage());
            return;
        }
        done = true;
        List<UUID> all = FeastRules.ordered(order, helpers);
        for (UUID id : all) {
            pay(id);
        }
        String line = thankfulLine();
        for (UUID id : all) {
            if (host.home(id)) {
                giveMini(id);
                if (markSeen(id)) {
                    host.moment(id, false, line);
                }
            }
        }
        host.broadcast(line);
        host.info("The Family Feast " + edition + " is full: " + total + " / " + host.settings().goal() + ", "
                + all.size() + " helper" + (all.size() == 1 ? "" : "s") + ".");
        showHome();
    }

    /** The show over the pot, once: its marker first. */
    void showHome() {
        if (homeShown) {
            return;
        }
        try {
            meta.set(FeastRules.homeShownKey(edition), Long.toString(host.now()));
        } catch (SQLException e) {
            warn("The Family Feast could not mark its show: " + e.getMessage());
            return;
        }
        homeShown = true;
        host.homeShow();
    }

    /**
     * Once a second while open: a table filled some other way (a lower goal after a reload) fills now, a show a
     * restart cut off is given, and every online helper is settled.
     */
    public void second() {
        if (!host.open()) {
            return;
        }
        if (!done && FeastRules.filled(total, host.settings().goal())) {
            fill();
        }
        if (!done) {
            return;
        }
        if (!homeShown) {
            showHome();
        }
        for (UUID id : host.online()) {
            settle(id);
        }
    }

    /**
     * A helper's turn after the fill, inside the window: paid (once; offline helpers were paid at the fill), and when
     * home their Turkey Mini, then their own moment. Nothing for anyone else.
     */
    public void settle(UUID player) {
        if (!done || player == null || !helpers.contains(player) || !host.open()) {
            return;
        }
        pay(player);
        if (!host.home(player)) {
            return;
        }
        giveMini(player);
        if (!marker(seen, player, FeastRules.seenKey(edition)) && markSeen(player)) {
            host.moment(player, true, thankfulLine());
        }
    }

    /** Pay a helper the Feast's tokens, once (the database refuses a second row); a Creative-only one by the knob. */
    void pay(UUID player) {
        if (settled.contains(player)) {
            return;
        }
        FeastConfig.Feast c = host.settings();
        try {
            if (!FeastRules.paid(c.creativeHelpersPaid(), counters.counter(player, FeastRules.survivalKey(edition)))) {
                settled.add(player); // Creative only, and the owner pays only those who gave outside it: a later
                return;              // gift of theirs takes them out of settled and asks again
            }
            if (c.tokens() > 0) {
                int paid = games.payReward(player, FeastRules.GAME, TokenService.Source.EVENT_FEAST, host.epochDay(),
                        RewardKind.EVENT_PRIZE, SkillRewards.eventRef(FeastRules.eventId(edition)), c.tokens(), -1, -1,
                        true, "Family Feast", host.now());
                if (paid > 0) {
                    host.paid(player, paid);
                }
            }
            settled.add(player);
        } catch (SQLException e) {
            warn("The Family Feast could not pay a helper; trying again: " + e.getMessage());
        }
    }

    /**
     * The helper's Turkey Mini, once: only with an empty slot (else it waits, and {@link #second()} tries again each
     * second), then its mark, then the mint (which keeps the Mini's own window and cap). A mint that fails after the
     * mark is given back by an admin with {@code /hcm mini give}, inside the window.
     */
    void giveMini(UUID player) {
        String miniId = FeastRules.miniId(edition);
        if (miniId == null || marker(minted, player, FeastRules.miniKey(edition))) {
            return;
        }
        if (!host.hasRoom(player)) {
            return; // owed, not marked: never dropped at her feet
        }
        boolean first;
        try {
            first = counters.addCounter(player, FeastRules.miniKey(edition), 1) == 1;
        } catch (SQLException e) {
            warn("The Family Feast could not mark a Turkey Mini; trying again: " + e.getMessage());
            return;
        }
        minted.put(player, true);
        if (!first) {
            return;
        }
        boolean ok = host.mintMini(player, miniId);
        String name = host.name(player);
        String who = name == null ? player.toString() : name;
        host.info("The Family Feast: " + who + (ok ? " got their " + miniId + "." : " could not get their " + miniId
                + " (closed, at its cap, or the mint failed). If it should have come, /hcm mini give " + miniId + " "
                + who + " gives it back inside its window."));
    }

    private boolean markSeen(UUID player) {
        boolean first = firstTime(player, FeastRules.seenKey(edition));
        seen.put(player, true);
        return first;
    }

    // ---- what the Clubhouse reads -------------------------------------------------------------------

    /** The tables now, from memory. */
    public FeastRules.FeastView view() {
        int goal = host.settings().goal();
        return new FeastRules.FeastView(host.open(), total, goal, FeastRules.plates(total, goal, FeastRules.CELLS),
                done, helpers.size());
    }

    /** Whether {@code player} helped fill the full table and hasn't seen the room's ceremony yet. */
    public boolean unseenHelper(UUID player) {
        return done && player != null && helpers.contains(player)
                && !marker(roomSeen, player, FeastRules.roomSeenKey(edition));
    }

    /** {@code player} has seen the room's ceremony (written before it plays). */
    public void markRoomSeen(UUID player) {
        if (player != null && helpers.contains(player)) {
            firstTime(player, FeastRules.roomSeenKey(edition));
            roomSeen.put(player, true);
        }
    }

    /** "Thankful for: Dad, Sam, Lee and Lily!": the helpers in the order of their first gift. */
    public String thankfulLine() {
        List<String> names = new ArrayList<>();
        for (UUID id : FeastRules.ordered(order, helpers)) {
            String n = host.name(id);
            if (n != null) {
                names.add(n);
            }
        }
        return FeastRules.thankfulLine(names);
    }

    // ---- for the admin ------------------------------------------------------------------------------

    /** The edition read now. */
    public String edition() {
        return edition;
    }

    /** Whether the tables were filled. */
    public boolean done() {
        return done;
    }

    /** Whether the show over the pot was given. */
    public boolean homeShown() {
        return homeShown;
    }

    /** The helpers in the order of their first gift. */
    public List<UUID> helpers() {
        return FeastRules.ordered(order, helpers);
    }

    /** The family's points. */
    public long total() {
        return total;
    }

    /** A player's own points on the edition's board (0 for none). */
    public long mine(UUID player) throws SQLException {
        Long p = games.best(player, FeastRules.GAME, FeastRules.board(host.settings().edition()));
        return p == null ? 0 : p;
    }

    // ---- markers ------------------------------------------------------------------------------------

    /** A per-player marker, read once and kept; a read that fails counts as set (no moment twice). */
    private boolean marker(Map<UUID, Boolean> cache, UUID player, String key) {
        Boolean b = cache.get(player);
        if (b != null) {
            return b;
        }
        try {
            b = counters.counter(player, key) > 0;
        } catch (SQLException e) {
            warn("The Family Feast could not read a marker: " + e.getMessage());
            return true; // not kept: read again next time
        }
        cache.put(player, b);
        return b;
    }

    /** {@code key} for {@code player} for the first time (it is written now). */
    private boolean firstTime(UUID player, String key) {
        try {
            return counters.addCounter(player, key, 1) == 1;
        } catch (SQLException e) {
            warn("The Family Feast could not write a marker: " + e.getMessage());
            return false;
        }
    }

    private void warn(String line) {
        long now = host.now();
        if (now - warnedAt >= WARN_EVERY_MS) {
            warnedAt = now;
            host.warn(line);
        }
    }
}
