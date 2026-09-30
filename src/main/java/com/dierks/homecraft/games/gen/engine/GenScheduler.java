package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;

/**
 * When a slot gets a new layout (GEN-SPEC §3.2, weekly addendum §1), as two pure functions:
 * {@link #target} (which edition a slot should show now) and {@link #decide} (what to do about it).
 *
 * <p>The engine asks every second, for each slot in turn, and does what the answer says. Keeping
 * the decision pure keeps every rule in one place a test can walk through with a fake clock: a
 * slot builds when its live layout isn't the current edition's (or an admin rerolled it, or it
 * couldn't be vouched for at boot, when its replacement, pinned or not, gets the next reroll and
 * so fresh boards), and only while it is switched on, nothing else is being built, no scheduled
 * restart is due within {@code avoid_before_restart_minutes}, fewer than
 * {@code max_tries_per_day} tries were made on this course day, and the last one was at least
 * {@code retry_minutes} ago. A pinned seed whose layout already stands is only restamped for the
 * new edition (new boards, no blocks). An admin's pick ({@link Choice}) never is (round 2, G2 #2): its
 * promise is the layout that was tried, and the live layout of the same seed can be another one (made
 * at another fall depth, which the boot check keeps), so the pick is built, into the spare half where
 * its tried preview usually still stands, which writes nothing. A job still running two minutes
 * before a restart is given up ({@link #abandon}): nothing flips, and the half converges again after
 * the boot.
 *
 * <p><b>A cadence change never rebuilds mid-edition.</b> A live layout made under another cadence
 * (or another {@code rebuild_day}) is off the current grid. It stays until the earlier of its own
 * natural end and the first start of the new schedule after the change was first seen: switching
 * weekly to daily keeps this week's courses until the next 4:00 AM, then they change daily;
 * switching daily to weekly keeps today's until the next 4:00 AM, then the week's set goes up. The
 * moment the change was seen is kept by the engine ({@code gen.cadence} in {@code hcm_meta}), so a
 * restart in between doesn't move the switch. Only an admin's {@code reroll} replaces a layout
 * sooner (a new layout of the same edition).
 *
 * <p><b>A moved {@code rebuild_day} never brings back the live key.</b> Keys are {@code N:<index>}
 * with the index a function of the start date alone ({@link Edition#index}), so a grid moved later
 * inside the live edition's N days (weekly, Monday to Friday) makes its next start carry the live
 * key again. That start is not a change: the live layout stays through it, until the first start
 * with a new key (a week later), and that is the change status and the countdown show.
 *
 * <p>No secret, no build: when the database can't give the seed secret the slot keeps its layout
 * and waits. It never falls back to a temporary secret, which would change a layout mid-edition.
 */
public final class GenScheduler {

    /** A job this close to a restart is abandoned. */
    public static final long ABANDON_MS = 2 * 60_000L;

    private GenScheduler() {
    }

    /** What to do. */
    public enum Kind {
        /** Nothing: the live layout is the current edition's, or the slot is off. */
        NONE,
        /**
         * Build a new layout ({@link Decision#day()}, {@link Decision#cadence()}, {@link Decision#reroll()},
         * {@link Decision#seed()}).
         */
        BUILD,
        /** A pinned layout that already stands: a new edition, no blocks. */
        RESTAMP,
        /** Empty the idle half (the last edition's layout, or leftovers after a boot). */
        CLEAR_OLD,
        /** A build is due but can't start yet ({@link Decision#reason()}). */
        WAIT
    }

    /**
     * An edition a slot should show.
     *
     * @param cadence its length in days
     * @param start   its first day (local epoch day)
     * @param endsAt  when the slot moves on from it (epoch ms): its natural end, or for a layout kept
     *                over a cadence change, the new schedule's first start with another key
     * @param kept    a live layout of another schedule, kept until {@code endsAt}
     */
    public record Target(int cadence, long start, long endsAt, boolean kept) {

        /** Its key without a reroll ({@code 7:38}): what rerolls are counted under. */
        public String key() {
            return Edition.editionKey(cadence, start, 0);
        }

        /** Whether {@code tag} is a layout of this edition (any reroll). */
        public boolean holds(GenTag tag) {
            return tag != null && tag.cadence() == cadence && tag.day() == start;
        }
    }

    /**
     * The answer.
     *
     * @param kind    what to do
     * @param day     the first day of the edition to build or restamp for
     * @param reroll  the reroll to build
     * @param seed    the seed to build from
     * @param reason  why it waits (admin words), or {@code ""}
     * @param cadence the edition's length in days
     */
    public record Decision(Kind kind, long day, int reroll, long seed, String reason, int cadence) {

        static Decision none() {
            return new Decision(Kind.NONE, 0, 0, 0, "", 0);
        }

        static Decision clearOld() {
            return new Decision(Kind.CLEAR_OLD, 0, 0, 0, "", 0);
        }

        static Decision waiting(String why) {
            return new Decision(Kind.WAIT, 0, 0, 0, why, 0);
        }

        static Decision build(Target t, int reroll, long seed) {
            return new Decision(Kind.BUILD, t.start(), reroll, seed, "", t.cadence());
        }

        static Decision restamp(Target t) {
            return new Decision(Kind.RESTAMP, t.start(), 0, 0, "", t.cadence());
        }
    }

    /**
     * A pinned seed ({@code gen.<slot>.pin} = {@code seed:algo:until}), or an admin's choice for one
     * set ({@link Choice#pin}, WP-ADM): the same pin, held only for editions that start from
     * {@code from} to {@code until}, so it is the next set's course and the set after goes back to
     * its own seed, with no flip path of its own.
     *
     * @param seed  the seed
     * @param algo  the planner version it was pinned under; another version ignores it
     * @param until the last course day it holds for (inclusive: an edition starting on or before it
     *              keeps the pin), or 0 for no end
     * @param from  the first course day it holds for (an edition starting before it doesn't use it), or
     *              0 for no start: every plain pin
     */
    public record Pin(long seed, int algo, long until, long from) {

        /** A plain pin: from now on, until {@code until} (0: no end). */
        public Pin(long seed, int algo, long until) {
            this(seed, algo, until, 0);
        }

        /** A choice for the one set that starts on {@code day} ({@code choose}). */
        public static Pin oneSet(long seed, int algo, long day) {
            return new Pin(seed, algo, day, day);
        }

        /**
         * Whether it is an admin's pick for one set ({@link Choice#pin}) rather than a plain pin: never
         * restamped over the live layout, since that is the course that was tried only if it was built
         * from the tried preview (round 2, G2 #2).
         */
        public boolean pick() {
            return from > 0;
        }

        /** Whether it holds for an edition that starts on local day {@code day}. */
        public boolean activeOn(long day) {
            return (until <= 0 || day <= until) && (from <= 0 || day >= from);
        }

        /** Whether it is over for an edition that starts on local day {@code day} (it never holds again). */
        public boolean endedBy(long day) {
            return until > 0 && day > until;
        }

        /**
         * Whether it applies to an edition starting on local day {@code day} with planner version
         * {@code plannerAlgo}: not expired, and made for that version (GEN-SPEC §4.0); otherwise the
         * edition's own seed is used.
         */
        public boolean appliesOn(long day, int plannerAlgo) {
            return activeOn(day) && algo == plannerAlgo;
        }

        /** As stored: three parts for a plain pin (as it always was), four for a choice. */
        public String text() {
            return GenSeed.hex(seed) + ":" + algo + ":" + until + (from > 0 ? ":" + from : "");
        }

        /** A stored pin or choice, or {@code null} when it can't be read. */
        public static Pin parse(String text) {
            if (text == null) {
                return null;
            }
            String[] p = text.trim().split(":");
            if (p.length != 3 && p.length != 4) {
                return null;
            }
            Long seed = GenSeed.parse(p[0]);
            try {
                return seed == null ? null : new Pin(seed, Integer.parseInt(p[1]), Long.parseLong(p[2]),
                        p.length == 4 ? Long.parseLong(p[3]) : 0);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * An admin's pick for one set ({@code choose}, WP-ADM; {@code gen.<slot>.choose}): the tried
     * preview's seed for exactly the set that starts on {@code from} and lasts {@code cadence} days,
     * as it was tried: at {@code mix}, and at {@code fallDepth} for a course whose layout depends on it.
     *
     * <p>Why it carries more than a {@link Pin} (fix2-D, D0-D2): "the course you tested is the
     * course that goes live". A pick is a seed, but the course is the seed plus the tier or mix and
     * (for medium and hard parkour) the fall depth its layout is shaped by, and its set is a first
     * day plus a length. So a pick
     * applies only to the set it names (the same first day and cadence: around a cadence change a
     * kept set can start later than the next one, or on the same day) and only while the settings
     * are the ones it was tried with. The engine drops it, and says so, once either stops being
     * true, instead of building the seed into a course nobody tried or promising a set that never
     * comes. To the scheduler it is a {@link #pin one-set pin}.
     *
     * <p>Stored as {@code seed:algo:until:from:cadence:mix:fallDepth} (a one-set pin's four fields,
     * then the rest). One stored before these were kept ({@code seed:algo:until:from}) reads with
     * {@code cadence} 0, {@code mix} {@code null} and {@code fallDepth} 0: matched by its first day
     * alone and never checked against the settings, as it always was.
     *
     * @param seed      the seed
     * @param algo      the planner version it was tried under; another version ignores it
     * @param from      its set's first day (local epoch day)
     * @param cadence   its set's length in days, or 0 when unknown
     * @param mix       the tier or mix it was tried at, or {@code null} when unknown
     * @param fallDepth the fall depth its layout was shaped by ({@code ParkourPlanner.designDepth}:
     *                  {@code trials.fall_depth} up to 6, for medium and hard parkour), or 0 when the
     *                  layout doesn't depend on it (easy parkour, the other planners) or it is unknown
     */
    public record Choice(long seed, int algo, long from, int cadence, String mix, int fallDepth) {

        /** As the scheduler builds it: a pin held for the one set starting on {@link #from}. */
        public Pin pin() {
            return Pin.oneSet(seed, algo, from);
        }

        /** Whether it is the set that starts on {@code start} and lasts {@code days} days. */
        public boolean isSet(long start, int days) {
            return from == start && (cadence <= 0 || cadence == days);
        }

        /**
         * Whether its course comes out as tried at {@code mixNow} and a design fall depth of
         * {@code fallDepthNow} (worked out as {@link #fallDepth} was, not the raw setting).
         */
        public boolean fits(String mixNow, int fallDepthNow) {
            return (mix == null || mix.equals(mixNow)) && (fallDepth <= 0 || fallDepth == fallDepthNow);
        }

        /** As stored. */
        public String text() {
            return pin().text() + ":" + cadence + ":" + (mix == null ? "" : mix) + ":" + fallDepth;
        }

        /** A stored pick, or {@code null} when it can't be read. */
        public static Choice parse(String text) {
            if (text == null) {
                return null;
            }
            String[] p = text.trim().split(":", -1);
            if (p.length != 4 && p.length != 7) {
                return null;
            }
            Pin pin = Pin.parse(String.join(":", p[0], p[1], p[2], p[3]));
            if (pin == null || pin.from() <= 0) {
                return null;
            }
            try {
                return p.length == 4 ? new Choice(pin.seed(), pin.algo(), pin.from(), 0, null, 0)
                        : new Choice(pin.seed(), pin.algo(), pin.from(), Integer.parseInt(p[4]),
                        p[5].isEmpty() ? null : p[5], Integer.parseInt(p[6]));
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
     * @param reroll      the current edition's reroll count ({@code gen.<slot>.reroll.<edition>})
     * @param pin         a pinned seed, or {@code null}
     * @param plannerAlgo the planner's version now
     * @param triesDay    the course day {@code tries} counts
     * @param tries       failed tries that day
     * @param lastTryAt   when the last try started (epoch ms)
     * @param oldDirty    the idle half may still hold blocks
     * @param secret      the seed secret, or {@code null} when the database couldn't give it
     * @param since       when the current schedule (cadence and rebuild day) was first seen (epoch ms),
     *                    or 0 when unknown: a layout of another schedule then stays until its own end
     */
    public record SlotView(String slot, boolean enabled, boolean busy, GenTag live, boolean liveOk, String liveMix,
                           String mix, int reroll, Pin pin, int plannerAlgo, long triesDay, int tries,
                           long lastTryAt, boolean oldDirty, Long secret, long since) {

        /** A view with the schedule's start unknown. */
        public SlotView(String slot, boolean enabled, boolean busy, GenTag live, boolean liveOk, String liveMix,
                        String mix, int reroll, Pin pin, int plannerAlgo, long triesDay, int tries, long lastTryAt,
                        boolean oldDirty, Long secret) {
            this(slot, enabled, busy, live, liveOk, liveMix, mix, reroll, pin, plannerAlgo, triesDay, tries, lastTryAt,
                    oldDirty, secret, 0);
        }
    }

    /**
     * The edition a slot with {@code live} should show at {@code now}:
     * <ul>
     *   <li>normally the edition due now ({@link Edition#editionStart}); a live layout from a later
     *       edition than that (a {@code rebuild_at} moved later) is kept, never replaced by an
     *       older one;</li>
     *   <li>a live layout of another cadence or rebuild day stays until the earlier of its own end
     *       and the new schedule's first start after {@code since}; with {@code since} unknown (0),
     *       until its own end;</li>
     *   <li>and when the new schedule's edition due at that moment has the live layout's own key
     *       (the same cadence and index: a {@code rebuild_day} moved later inside the index's N
     *       days, Monday to Friday for a weekly set), it <em>is</em> the live edition carried on,
     *       so the layout stays through it, until the first start with a new key. Building it would
     *       give the same seed, board, stars and first-finish reward: nothing would really change,
     *       and the countdown would have named a day on which nothing new goes up.</li>
     * </ul>
     */
    public static Target target(GenTag live, long now, Edition ed, long since) {
        long due = ed.editionStart(now);
        Target current = new Target(ed.cadenceDays(), due, ed.endOf(due), false);
        if (live == null) {
            return current;
        }
        boolean onGrid = live.cadence() == ed.cadenceDays() && ed.starts(live.day());
        if (onGrid) {
            return live.day() > due ? new Target(live.cadence(), live.day(), ed.endOf(live.day()), false) : current;
        }
        long ownEnd = ed.startOf(live.endDay());
        long newStart = since > 0 ? ed.nextChangeAt(Math.max(since, ed.startOf(live.day()))) : Long.MAX_VALUE;
        long keepUntil = Math.min(ownEnd, newStart);
        long takeover = ed.editionStart(keepUntil);
        if (live.cadence() == ed.cadenceDays()
                && Edition.index(ed.cadenceDays(), takeover) == Edition.index(live.cadence(), live.day())) {
            keepUntil = ed.endOf(takeover); // the next start has a greater index: floorDiv moves on by one
        }
        return now < keepUntil ? new Target(live.cadence(), live.day(), keepUntil, true) : current;
    }

    /**
     * What {@code v} should do at {@code now}.
     *
     * @param readyAt the first moment anything may be built (the worlds were ready, plus
     *                {@code startup_delay_seconds})
     * @param edition the current schedule ({@link DailySettings#edition})
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
        Target t = target(v.live(), now, edition, v.since());
        Pin pin = v.pin() != null && v.pin().appliesOn(t.start(), v.plannerAlgo()) ? v.pin() : null;
        int reroll = pin != null ? 0 : Math.max(0, v.reroll());
        GenTag live = v.live();
        boolean current = t.holds(live);
        if (current && !v.liveOk()) {
            // This edition's layout couldn't be vouched for: its replacement may come out different
            // (a new tier, a new fall depth, a pinned seed's too), so it goes on fresh boards, never
            // on the old one's.
            reroll = Math.max(reroll, live.reroll() + 1);
        }
        boolean due = !current || live.reroll() < reroll || !v.liveOk();
        if (!due) {
            return v.oldDirty() ? Decision.clearOld() : Decision.none();
        }
        // round 2, G2 #2: a pick is always built (as tried), never restamped over a live layout of its seed
        if (pin != null && !pin.pick() && live != null && v.liveOk() && live.seed() == pin.seed()
                && live.algo() == pin.algo() && same(v.liveMix(), v.mix()) && !current) {
            return Decision.restamp(t);
        }
        if (nearRestart(now, hold, s.avoidBeforeRestartMinutes())) {
            return Decision.waiting("a restart is coming at " + hold.clock(hold.next(now)));
        }
        if (v.triesDay() == day && v.tries() >= s.maxTriesPerDay()) {
            return Decision.waiting("gave up until tomorrow after " + v.tries() + " tries");
        }
        long retryAt = v.lastTryAt() + s.retryMinutes() * 60_000L;
        if (v.triesDay() == day && v.tries() > 0 && now < retryAt) {
            return Decision.waiting("next try at " + hold.clock(retryAt));
        }
        if (pin != null) {
            return Decision.build(t, reroll, pin.seed());
        }
        if (v.secret() == null) {
            return Decision.waiting("the seed secret can't be read");
        }
        return Decision.build(t, reroll, GenSeed.seed(v.secret(), t.cadence(), t.start(), v.slot(), reroll));
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
