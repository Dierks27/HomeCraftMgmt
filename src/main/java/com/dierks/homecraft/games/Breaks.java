package com.dierks.homecraft.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GamesDao.BreakRow;
import com.dierks.homecraft.util.GameClock;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

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

    private static final long DAY_MS = 86_400_000L;
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("EEE h a", Locale.US);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);

    private final HomeCraftManagement plugin;
    private final GamesDao dao;

    public Breaks(HomeCraftManagement plugin, GamesDao dao) {
        this.plugin = plugin;
        this.dao = dao;
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
     * @return {@code null} to go ahead, or why not; {@link Refusal#CHANCE_CLOSED} if the limits
     *         can't be read (fails closed)
     */
    public Refusal chanceAllowed(Player player, int cost) {
        // F1b: permission hcm.games.chance -> NO_CHANCE; row(player) null -> CHANCE_CLOSED;
        // paused -> Refusal.paused(untilText(...)); limit(player) >= 0 && tokensInToday + cost >
        // limit -> personalLimit(limit). The server limit only counts while games.enabled (R1.9).
        return Refusal.CHANCE_CLOSED;
    }

    /** The player's limits with any due change applied, or {@code null} if they can't be read. */
    public BreakRow row(UUID player) {
        // F1b: settle(dao.breakRow(player), clock.dayKey()), saving it if a pending change applied.
        return null;
    }

    /** Today's effective limit ({@link #effectiveLimit}), or {@link #NO_LIMIT}. */
    public int limit(UUID player) {
        // F1b
        return 0;
    }

    /** Tokens put into games of chance today (the ledger, R1.10). */
    public int tokensInToday(UUID player) {
        // F1b: dao.chanceTokensToday(player, clock.startOfDay(clock.dayKey())).
        return 0;
    }

    /** When the player's pause ends (own or admin, whichever is later), or 0 for none. */
    public long pausedUntil(UUID player) {
        // F1b
        return 0;
    }

    /** The player picks their own limit (see {@link #setOwnLimit}). False if it couldn't be saved. */
    public boolean setLimit(UUID player, int tokens) {
        // F1b: save(setOwnLimit(row, tokens, clock, now, config raise_delay_days)).
        return false;
    }

    /** The player drops their waiting raise. */
    public boolean cancelPending(UUID player) {
        // F1b
        return false;
    }

    /** The player pauses games of chance for {@code days} (longer only). */
    public boolean pause(UUID player, int days) {
        // F1b: save(pauseOwn(row, pauseEnd(clock, now, days))).
        return false;
    }

    /** An admin limit for the player ({@link #NO_LIMIT} = none). */
    public boolean setAdminLimit(UUID player, int tokens) {
        // F1b
        return false;
    }

    /** An admin pause for {@code days}. */
    public boolean setAdminPause(UUID player, int days) {
        // F1b
        return false;
    }

    /** Remove what an admin set (limit and pause). */
    public boolean clearAdmin(UUID player) {
        // F1b
        return false;
    }

    /** Remove the player's own pause, limit and waiting change (admin, confirmed, logged at WARNING by the caller). */
    public boolean clearOwn(UUID player) {
        // F1b
        return false;
    }
}
