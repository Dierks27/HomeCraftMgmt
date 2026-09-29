package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;

/**
 * When a slot gets a new layout (GEN-SPEC §3.2), as one pure function: {@link #decide}.
 *
 * <p>The engine asks every second, for each slot in turn, and does what the answer says. Keeping
 * the decision pure keeps every rule in one place a test can walk through with a fake clock: a
 * slot builds when its live layout isn't today's (or an admin rerolled it, or it couldn't be
 * vouched for at boot, when its replacement, pinned or not, gets the next reroll and so a fresh
 * day board), and only while it is switched on, nothing else is being built, no scheduled
 * restart is due within {@code avoid_before_restart_minutes}, fewer than
 * {@code max_tries_per_day} tries were made today, and the last one was at least
 * {@code retry_minutes} ago. A pinned seed whose layout already stands is only restamped for the
 * new day (a new day board, no blocks). A job still running two minutes before a restart is given
 * up ({@link #abandon}): nothing flips, and the half converges again after the boot.
 *
 * <p>No secret, no build: when the database can't give the seed secret the slot keeps its layout
 * and waits. It never falls back to a temporary secret, which would change a layout mid-day.
 */
public final class GenScheduler {

    /** A job this close to a restart is abandoned. */
    public static final long ABANDON_MS = 2 * 60_000L;

    private GenScheduler() {
    }

    /** What to do. */
    public enum Kind {
        /** Nothing: the live layout is today's, or the slot is off. */
        NONE,
        /** Build a new layout ({@link Decision#day()}, {@link Decision#reroll()}, {@link Decision#seed()}). */
        BUILD,
        /** A pinned layout that already stands: new day, no blocks. */
        RESTAMP,
        /** Empty the idle half (yesterday's layout, or leftovers after a boot). */
        CLEAR_OLD,
        /** A build is due but can't start yet ({@link Decision#reason()}). */
        WAIT
    }

    /**
     * The answer.
     *
     * @param kind   what to do
     * @param day    the course day to build or restamp for
     * @param reroll the reroll to build
     * @param seed   the seed to build from
     * @param reason why it waits (admin words), or {@code ""}
     */
    public record Decision(Kind kind, long day, int reroll, long seed, String reason) {

        static Decision none() {
            return new Decision(Kind.NONE, 0, 0, 0, "");
        }

        static Decision clearOld() {
            return new Decision(Kind.CLEAR_OLD, 0, 0, 0, "");
        }

        static Decision waiting(String why) {
            return new Decision(Kind.WAIT, 0, 0, 0, why);
        }

        static Decision build(long day, int reroll, long seed) {
            return new Decision(Kind.BUILD, day, reroll, seed, "");
        }

        static Decision restamp(long day) {
            return new Decision(Kind.RESTAMP, day, 0, 0, "");
        }
    }

    /**
     * A pinned seed ({@code gen.<slot>.pin} = {@code seed:algo:until}).
     *
     * @param seed  the seed
     * @param algo  the planner version it was pinned under; another version ignores it
     * @param until the last course day it holds (inclusive), or 0 for no end
     */
    public record Pin(long seed, int algo, long until) {

        /** Whether it holds on course day {@code day}. */
        public boolean activeOn(long day) {
            return until <= 0 || day <= until;
        }

        /**
         * Whether it applies on course day {@code day} with planner version {@code plannerAlgo}:
         * not expired, and made for that version (GEN-SPEC §4.0); otherwise the daily seed is used.
         */
        public boolean appliesOn(long day, int plannerAlgo) {
            return activeOn(day) && algo == plannerAlgo;
        }

        /** As stored. */
        public String text() {
            return GenSeed.hex(seed) + ":" + algo + ":" + until;
        }

        /** A stored pin, or {@code null} when it can't be read. */
        public static Pin parse(String text) {
            if (text == null) {
                return null;
            }
            String[] p = text.trim().split(":");
            if (p.length != 3) {
                return null;
            }
            Long seed = GenSeed.parse(p[0]);
            try {
                return seed == null ? null : new Pin(seed, Integer.parseInt(p[1]), Long.parseLong(p[2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * One slot as the scheduler sees it.
     *
     * @param slot        its id
     * @param enabled     switched on (config, then the admin's override) and its region is usable
     * @param busy        a job (any slot's: one at a time) is running
     * @param live        the live layout's tag, or {@code null} for none yet
     * @param liveOk      the live layout could be vouched for (false after a failed boot check)
     * @param liveMix     the tier or mix the live layout was made with
     * @param mix         the tier or mix a build would use now
     * @param reroll      today's reroll count ({@code gen.<slot>.reroll.<day>})
     * @param pin         a pinned seed, or {@code null}
     * @param plannerAlgo the planner's version now
     * @param triesDay    the course day {@code tries} counts
     * @param tries       failed tries that day
     * @param lastTryAt   when the last try started (epoch ms)
     * @param oldDirty    the idle half may still hold blocks
     * @param secret      the seed secret, or {@code null} when the database couldn't give it
     */
    public record SlotView(String slot, boolean enabled, boolean busy, GenTag live, boolean liveOk, String liveMix,
                           String mix, int reroll, Pin pin, int plannerAlgo, long triesDay, int tries,
                           long lastTryAt, boolean oldDirty, Long secret) {
    }

    /**
     * What {@code v} should do at {@code now}.
     *
     * @param readyAt the first moment anything may be built (the worlds were ready, plus
     *                {@code startup_delay_seconds})
     */
    public static Decision decide(SlotView v, long now, long readyAt, DailySettings s, RestartHold hold,
                                  Edition edition) {
        if (!v.enabled()) {
            return Decision.none();
        }
        if (now < readyAt) {
            return Decision.waiting("starting up");
        }
        if (v.busy()) {
            return Decision.waiting("another course is being built");
        }
        long day = edition.day(now);
        Pin pin = v.pin() != null && v.pin().appliesOn(day, v.plannerAlgo()) ? v.pin() : null;
        int reroll = pin != null ? 0 : Math.max(0, v.reroll());
        GenTag live = v.live();
        if (live != null && !v.liveOk() && live.day() == day) {
            // Today's layout couldn't be vouched for: its replacement may come out different (a
            // new tier, a new fall depth, a pinned seed's too), so it goes on a fresh board, never
            // on the old one's.
            reroll = Math.max(reroll, live.reroll() + 1);
        }
        boolean due = live == null || live.day() != day || live.reroll() < reroll || !v.liveOk();
        if (!due) {
            return v.oldDirty() ? Decision.clearOld() : Decision.none();
        }
        if (pin != null && live != null && v.liveOk() && live.seed() == pin.seed() && live.algo() == pin.algo()
                && same(v.liveMix(), v.mix()) && live.day() != day) {
            return Decision.restamp(day);
        }
        if (nearRestart(now, hold, s.avoidBeforeRestartMinutes())) {
            return Decision.waiting("a restart is coming at " + hold.clock(hold.next(now)));
        }
        if (v.triesDay() == day && v.tries() >= s.maxTriesPerDay()) {
            return Decision.waiting("gave up for today after " + v.tries() + " tries");
        }
        long retryAt = v.lastTryAt() + s.retryMinutes() * 60_000L;
        if (v.triesDay() == day && v.tries() > 0 && now < retryAt) {
            return Decision.waiting("next try at " + hold.clock(retryAt));
        }
        if (pin != null) {
            return Decision.build(day, reroll, pin.seed());
        }
        if (v.secret() == null) {
            return Decision.waiting("the seed secret can't be read");
        }
        return Decision.build(day, reroll, GenSeed.seed(v.secret(), day, v.slot(), reroll));
    }

    /** Whether a scheduled restart is due within {@code minutes} of {@code now} (0: never). */
    public static boolean nearRestart(long now, RestartHold hold, int minutes) {
        if (hold == null || minutes <= 0) {
            return false;
        }
        long next = hold.next(now);
        return next >= 0 && now >= next - minutes * 60_000L;
    }

    /** Whether a running job must be given up: a restart is due within two minutes. */
    public static boolean abandon(long now, RestartHold hold) {
        if (hold == null) {
            return false;
        }
        long next = hold.next(now);
        return next >= 0 && now >= next - ABANDON_MS;
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
}
