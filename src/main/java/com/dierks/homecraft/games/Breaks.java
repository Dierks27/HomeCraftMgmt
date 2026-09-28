package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GamesDao.BreakRow;
import com.dierks.homecraft.util.GameClock;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Take a break: the personal limits on games of chance (spec §4.3, R1.9, R1.11-R1.15).
 *
 * <p>A player can set a daily limit on the tokens they put into games of chance, or pause them
 * for 1, 7 or 30 days; a parent or admin can set either on their behalf, and the player can't
 * lift what an admin set. It covers every game of chance in the Arcade — the new games AND the
 * Scratch Ticket, Crates and Card Packs bought with tokens — so it is its own small service
 * (built right after the token service, outside the games' own error isolation) and works even
 * while {@code games.enabled} is false.
 *
 * <p>The rules lean one way on purpose. Making things stricter is instant; making them looser
 * waits: a lower limit applies now, but a higher one (or none) starts only after
 * {@code games.break.raise_delay_days} and the next local midnight, so a limit set in a calm
 * moment still holds in a heated one. A pause can be made longer, never shorter. And if the
 * limits can't be read, games of chance are closed rather than open (it fails closed).
 *
 * <p>The rules themselves are the pure static methods here (tested with a fixed
 * {@link GameClock}, midnight and DST included); the instance methods apply them to the
 * database.
 */
public final class Breaks {

    /** No limit (own or admin). */
    public static final int NO_LIMIT = -1;
    /** No change waiting. */
    public static final int NO_PENDING = -2;
    /** A raise always waits at least this many days, whatever the config says. */
    public static final int MIN_RAISE_DELAY_DAYS = 1;
    /** The permission for every game of chance, the Scratch Ticket, Crates and token Card Packs included. */
    public static final String PERMISSION_CHANCE = "hcm.games.chance";

    private static final long DAY_MS = 86_400_000L;
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("EEE h a", Locale.US);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);

    /** The longest pause anyone can set in one go, in days (a year; longer is renewed, not typed). */
    public static final int MAX_PAUSE_DAYS = 365;

    private final GamesHost host;

    public Breaks(HomeCraftManagement plugin, GamesDao dao) {
        this(GamesHost.live(plugin, dao));
    }

    Breaks(GamesHost host) {
        this.host = host;
    }

    /**
     * Where a player stands today: their limits (with any change that has come due applied), the
     * day's effective limit, and the tokens they have put into games of chance since midnight.
     *
     * @param row      the settled row
     * @param limit    today's effective limit ({@link #effectiveLimit}), or {@link #NO_LIMIT}
     * @param tokensIn tokens put in today (the ledger, R1.10)
     */
    public record Today(BreakRow row, int limit, int tokensIn) {

        /** Whether games of chance are paused at {@code now}. */
        public boolean paused(long now) {
            return Breaks.paused(row, now);
        }

        /** When the later pause ends, or 0 for none. */
        public long pausedUntil() {
            return Breaks.pausedUntil(row);
        }

        /** Whether putting {@code cost} more in today would pass the limit. */
        public boolean over(int cost) {
            return limit >= 0 && (long) tokensIn + Math.max(0, cost) > limit;
        }
    }

    // ---- the rules (pure) ------------------------------------------------------------------

    /**
     * The day's limit on tokens put into games of chance: the lowest of the player's own, the
     * admin's and the server's, ignoring the ones that are not set.
     *
     * @param own    the player's own limit, or {@link #NO_LIMIT}
     * @param admin  the admin's limit for them, or {@link #NO_LIMIT}
     * @param server {@code games.chance_daily_tokens}; 0 = off (pass 0 while games are off, so
     *               the Scratch Ticket and Crates behave exactly as before)
     * @return the limit, or {@link #NO_LIMIT} when none applies
     */
    public static int effectiveLimit(int own, int admin, int server) {
        int limit = NO_LIMIT;
        if (own >= 0) {
            limit = own;
        }
        if (admin >= 0) {
            limit = limit < 0 ? admin : Math.min(limit, admin);
        }
        if (server > 0) {
            limit = limit < 0 ? server : Math.min(limit, server);
        }
        return limit;
    }

    /**
     * When an N-day pause started at {@code now} ends (R1.12): the first local midnight at least
     * N full days away. A one-day pause therefore lasts 24 to 48 hours, and the end is always a
     * midnight the screen can name ("Thu 12 AM"), DST or not.
     */
    public static long pauseEnd(GameClock clock, long now, int days) {
        return clock.startOfDay(clock.dayKeyAt(now + Math.max(1, days) * DAY_MS) + 1);
    }

    /**
     * The local day a raised (or removed) limit starts on (R1.13): the wait, then the next local
     * midnight. The wait is at least {@link #MIN_RAISE_DELAY_DAYS}.
     */
    public static long pendingDay(GameClock clock, long now, int delayDays) {
        return clock.dayKeyAt(now + Math.max(MIN_RAISE_DELAY_DAYS, delayDays) * DAY_MS) + 1;
    }

    /** Whether going from {@code current} to {@code wanted} loosens the player's own limit. */
    public static boolean isRaise(int current, int wanted) {
        if (current < 0) {
            return false; // no limit yet: any limit is stricter, and none -> none changes nothing
        }
        return wanted < 0 || wanted > current;
    }

    /** The row with a waiting change applied once its day has come. */
    public static BreakRow settle(BreakRow row, long today) {
        if (row.pendingTokens() != NO_PENDING && today >= row.pendingDay()) {
            return row.withDailyTokens(row.pendingTokens()).withPending(NO_PENDING, 0);
        }
        return row;
    }

    /**
     * The player picks a limit ({@link #NO_LIMIT} for "No limit of my own"). Lower (or first) —
     * it applies now and clears any waiting raise. Higher or none — it waits (see
     * {@link #pendingDay}); a new raise replaces the waiting one and restarts the wait.
     *
     * @param row a {@link #settle settled} row
     */
    public static BreakRow setOwnLimit(BreakRow row, int wanted, GameClock clock, long now, int raiseDelayDays) {
        int w = wanted < 0 ? NO_LIMIT : wanted;
        if (isRaise(row.dailyTokens(), w)) {
            return row.withPending(w, pendingDay(clock, now, raiseDelayDays));
        }
        return row.withDailyTokens(w).withPending(NO_PENDING, 0);
    }

    /** The player drops a waiting raise; their current limit simply stays. */
    public static BreakRow cancelPending(BreakRow row) {
        return row.withPending(NO_PENDING, 0);
    }

    /** The player pauses until {@code until}: a pause can be made longer, never shorter. */
    public static BreakRow pauseOwn(BreakRow row, long until) {
        return row.withPausedUntil(Math.max(row.pausedUntil(), until));
    }

    /** An admin (or parent) limit; {@link #NO_LIMIT} removes it. The player can't lift it. */
    public static BreakRow adminLimit(BreakRow row, int tokens) {
        return row.withAdminTokens(tokens < 0 ? NO_LIMIT : tokens);
    }

    /** An admin (or parent) pause until {@code until} (made with {@link #pauseEnd}), as set. */
    public static BreakRow adminPause(BreakRow row, long until) {
        return row.withAdminPausedUntil(Math.max(0, until));
    }

    /** {@code /hcm games break <player> clear}: only what an admin set goes (R1.14). */
    public static BreakRow clearAdmin(BreakRow row) {
        return row.withAdminTokens(NO_LIMIT).withAdminPausedUntil(0);
    }

    /**
     * {@code clear-own <player> confirm}: the player's OWN pause, limit and waiting change go; what
     * an admin set stays. The caller logs it at WARNING with the admin's name, so a self-exclusion
     * is never lifted by accident.
     */
    public static BreakRow clearOwn(BreakRow row) {
        return row.withPausedUntil(0).withDailyTokens(NO_LIMIT).withPending(NO_PENDING, 0);
    }

    /** When the later of the two pauses ends, or 0 for none. */
    public static long pausedUntil(BreakRow row) {
        return Math.max(row.pausedUntil(), row.adminPausedUntil());
    }

    /** Whether games of chance are paused for the row at {@code now}. */
    public static boolean paused(BreakRow row, long now) {
        return now < pausedUntil(row);
    }

    /** A pause end as the screen says it: "Thu 12 AM". */
    public static String untilText(GameClock clock, long millis) {
        return UNTIL.format(Instant.ofEpochMilli(millis).atZone(clock.zone()));
    }

    /** A local day as the screen says it: "Tue Oct 6". */
    public static String dateText(long dayKey) {
        return DATE.format(LocalDate.ofEpochDay(dayKey));
    }

    // ---- the service ------------------------------------------------------------------------

    /**
     * The hook every game of chance calls before tokens go in — the new games' gate, the Scratch
     * Ticket, Crates and token Card Packs (R1.11, R1.15). Runs the {@code hcm.games.chance}
     * permission, the pause and the day's token limit (with {@code cost} added), never a game's
     * own daily limit or cooldown.
     *
     * <p>The player's own and the admin's pause and limit always apply, even while
     * {@code games.enabled} is false; the server-wide {@code games.chance_daily_tokens} only while
     * the games are on, so switching them off leaves a player with no break row exactly where they
     * were before the games existed.
     *
     * @return {@code null} to go ahead, or why not; {@link Refusal#CHANCE_CLOSED} if the limits
     *         can't be read (fails closed)
     */
    public Refusal chanceAllowed(Player player, int cost) {
        if (player == null) {
            return Refusal.CHANCE_CLOSED;
        }
        try {
            if (!player.hasPermission(PERMISSION_CHANCE)) {
                return Refusal.NO_CHANCE;
            }
            Today today = today(player.getUniqueId());
            if (today == null) {
                return Refusal.CHANCE_CLOSED;
            }
            long now = host.clock().nowMillis();
            if (today.paused(now)) {
                return Refusal.paused(untilText(host.clock(), today.pausedUntil()));
            }
            if (today.over(cost)) {
                return Refusal.personalLimit(today.limit());
            }
            return null;
        } catch (RuntimeException e) {
            host.logger().log(Level.SEVERE, "Take a break could not be checked - games of chance stay closed", e);
            return Refusal.CHANCE_CLOSED;
        }
    }

    /**
     * Where the player stands today (their settled limits, the effective limit and the tokens put
     * in since local midnight), or {@code null} if it can't be read — callers then refuse.
     */
    public Today today(UUID player) {
        BreakRow row = row(player);
        if (row == null) {
            return null;
        }
        try {
            int in = host.dao().chanceTokensToday(player, host.clock().startOfDay(host.clock().dayKey()));
            return new Today(row, effectiveLimit(row.dailyTokens(), row.adminTokens(), serverLimit()), in);
        } catch (SQLException e) {
            host.logger().log(Level.SEVERE, "Could not read today's tokens in games of chance", e);
            return null;
        }
    }

    /** The player's limits with any due change applied, or {@code null} if they can't be read. */
    public BreakRow row(UUID player) {
        if (player == null) {
            return null;
        }
        try {
            BreakRow stored = host.dao().breakRow(player);
            BreakRow settled = settle(stored, host.clock().dayKey());
            if (!settled.equals(stored)) {
                settled = settled.withUpdatedAt(host.clock().nowMillis());
                host.dao().saveBreak(settled); // the raise has started: from now on it is the limit
            }
            return settled;
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.SEVERE, "Could not read a player's Take a break settings", e);
            return null;
        }
    }

    /**
     * Today's effective limit ({@link #effectiveLimit}), or {@link #NO_LIMIT}. 0 when the limits
     * can't be read: nothing may go in then.
     */
    public int limit(UUID player) {
        BreakRow row = row(player);
        return row == null ? 0 : effectiveLimit(row.dailyTokens(), row.adminTokens(), serverLimit());
    }

    /** Tokens put into games of chance today (the ledger, R1.10); 0 if it can't be read. */
    public int tokensInToday(UUID player) {
        try {
            return host.dao().chanceTokensToday(player, host.clock().startOfDay(host.clock().dayKey()));
        } catch (SQLException e) {
            host.logger().log(Level.SEVERE, "Could not read today's tokens in games of chance", e);
            return 0;
        }
    }

    /**
     * When the player's pause ends (own or admin, whichever is later), or 0 for none — also 0 when
     * it can't be read, which is for showing only: the gate and the hook refuse on their own then.
     */
    public long pausedUntil(UUID player) {
        BreakRow row = row(player);
        return row == null ? 0 : pausedUntil(row);
    }

    /** The player picks their own limit (see {@link #setOwnLimit}). False if it couldn't be saved. */
    public boolean setLimit(UUID player, int tokens) {
        int wanted = tokens < 0 ? NO_LIMIT : Math.min(tokens, GamesConfig.MAX_CHANCE_DAILY_TOKENS);
        return change(player, row -> setOwnLimit(row, wanted, host.clock(), host.clock().nowMillis(),
                host.config().common().breakRaiseDelayDays()));
    }

    /** The player drops their waiting raise. */
    public boolean cancelPending(UUID player) {
        return change(player, Breaks::cancelPending);
    }

    /** The player pauses games of chance for {@code days} (longer only). */
    public boolean pause(UUID player, int days) {
        long until = pauseEnd(host.clock(), host.clock().nowMillis(), days(days));
        return change(player, row -> pauseOwn(row, until));
    }

    /** An admin limit for the player ({@link #NO_LIMIT} = none). */
    public boolean setAdminLimit(UUID player, int tokens) {
        int limit = tokens < 0 ? NO_LIMIT : Math.min(tokens, GamesConfig.MAX_CHANCE_DAILY_TOKENS);
        return change(player, row -> adminLimit(row, limit));
    }

    /** An admin pause for {@code days}, ending by the same rule as the player's own. */
    public boolean setAdminPause(UUID player, int days) {
        long until = pauseEnd(host.clock(), host.clock().nowMillis(), days(days));
        return change(player, row -> adminPause(row, until));
    }

    /** Remove what an admin set (limit and pause). */
    public boolean clearAdmin(UUID player) {
        return change(player, Breaks::clearAdmin);
    }

    /** Remove the player's own pause, limit and waiting change (admin, confirmed, logged at WARNING by the caller). */
    public boolean clearOwn(UUID player) {
        return change(player, Breaks::clearOwn);
    }

    /** The server-wide limit, which only counts while the games are on (R1.9); 0 = none. */
    private int serverLimit() {
        GamesConfig.Parsed cfg = host.config();
        return cfg.enabled() ? cfg.common().chanceDailyTokens() : 0;
    }

    /** Read the settled row, apply {@code rule}, save it. False (and logged) if it couldn't be. */
    private boolean change(UUID player, UnaryOperator<BreakRow> rule) {
        BreakRow row = row(player);
        if (row == null) {
            return false;
        }
        try {
            host.dao().saveBreak(rule.apply(row).withUpdatedAt(host.clock().nowMillis()));
            return true;
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.SEVERE, "Could not save a player's Take a break settings", e);
            return false;
        }
    }

    private static int days(int days) {
        return Math.max(1, Math.min(MAX_PAUSE_DAYS, days));
    }
}
