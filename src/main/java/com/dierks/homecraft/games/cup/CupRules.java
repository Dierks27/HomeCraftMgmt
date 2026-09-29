package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Weekly Cup's rules, pure (EVENTS-OWNER-DECISIONS §D2, EVENTS-RECONCILED decision 3): who may
 * enter, how the pool is shared at the week's rollover, how a voided Cup gives everything back, and
 * the ledger proof that the server keeps nothing.
 *
 * <p><b>Why it is a skill contest and nothing else.</b> Places come from Cup times alone (the best
 * counted time of the week), the shares are a fixed table, and every tie is broken by who set the
 * time first. Nothing here draws a random number, so Take a break's chance rules don't apply.
 *
 * <p><b>The table.</b> With 2 or more Cup times the pool is every entry plus the server's top-up,
 * shared 70/30 between two and 50/30/20 among three or more. Each place's amount is rounded down and
 * the remainder goes to 1st. Tied times share their places' amounts equally, and what doesn't divide
 * goes to whichever of them set the time first. With 1 entrant, or fewer than 2 Cup times, nobody
 * raced anybody: every entry comes back and there is no top-up.
 *
 * <p><b>Why an entrant without a Cup time isn't refunded in a contest.</b> The pool published all
 * week ("Cup pool: 35 tokens · 5 in") counts everyone who paid; refunding the ones who never
 * finished would pay the winners less than the screen promised. Not finishing is simply the slowest
 * result. Only when there is no contest at all (fewer than two times) does everybody get their entry
 * back.
 *
 * <p><b>The server keeps nothing.</b> Every plan pays out exactly {@code entries + topup}; the
 * ledger rows of a plan sum to the top-up (a paid Cup) or 0 (a refunded one). {@link #problems}
 * checks this and the rest of the rules for any plan, and the tests run it over random Cups.
 */
public final class CupRules {

    /** The shipped {@code games.cup.entry}. */
    public static final int DEFAULT_ENTRY = 5;
    /** The smallest entry a setting may give (a Cup is never free: free play already is). */
    public static final int MIN_ENTRY = 1;
    /** The largest entry a setting may give. */
    public static final int MAX_ENTRY = 100;
    /** The shipped {@code games.cup.server_topup}. */
    public static final int DEFAULT_TOPUP = 10;
    /** The largest top-up a setting may give. */
    public static final int MAX_TOPUP = 100;
    /** Cup times needed for a contest (and for the top-up). */
    public static final int CONTEST = 2;

    /** Two Cup times: 70/30. */
    private static final int[] TWO = {70, 30};
    /** Three or more: 50/30/20. */
    private static final int[] THREE_OR_MORE = {50, 30, 20};

    /**
     * The order places are given in: the faster Cup time first; on equal times whoever set it first,
     * then whoever entered first, then the player id (so the order never depends on how the rows
     * were read).
     */
    public static final Comparator<CupEntry> BY_CUP_TIME = Comparator
            .comparingLong(CupEntry::bestMs)
            .thenComparingLong(CupEntry::bestAt)
            .thenComparingLong(CupEntry::enteredAt)
            .thenComparing(e -> e.player().toString());

    /** Entry order: whoever entered first, then the player id. */
    public static final Comparator<CupEntry> BY_ENTRY = Comparator
            .comparingLong(CupEntry::enteredAt)
            .thenComparing(e -> e.player().toString());

    private CupRules() {
    }

    // ---- the week -------------------------------------------------------------------------------

    /**
     * The Cup week at {@code now}: the quests' week, turning at the Fresh Courses' rollover (04:00)
     * on its first day, so the Cup is settled at the same moment the Fresh Courses change (§D2). It
     * is NOT the time trials' own {@code weekKey()}, which turns at midnight.
     */
    public static long week(Edition edition, long now) {
        return edition.weekKey(edition.day(now));
    }

    /**
     * When the Cup week {@code week} ends and is settled (epoch ms): the rollover seven days after its
     * first one. It depends on the key's own day alone, never on {@code quests.week_starts_on}, so a
     * Cup that is running when the owner moves the week start still runs its full seven days.
     */
    public static long settlesAt(Edition edition, long week) {
        return edition.startOf(week + 7);
    }

    /**
     * The weeks whose Cups are running at {@code now}: every week whose seven days include now
     * ({@code week <= today < week + 7}), from {@code today - 6} to {@code today}. With the week start
     * unchanged only {@link #week} is a Cup's week in it; after the owner moves
     * {@code quests.week_starts_on} mid-week, the Cup of the old week start is in it too, still
     * running its own seven days ({@link #settlesAt}), so a player already in it isn't let into the
     * new week's Cup on the same course as well.
     */
    public static Weeks liveWeeks(Edition edition, long now) {
        long today = edition.day(now);
        return new Weeks(today - 6, today);
    }

    /**
     * The Cup weeks a counted run may count for: each is a week's first local epoch day, from
     * {@code first} to {@code last} (both included), and the run counts for every Cup the player is in
     * on the course whose week is in that range. A range with no Cup's week in it (every run that
     * counts for none, except the few that give an empty one) matches no entry row.
     *
     * <p><b>Inside the Cup's own seven days.</b> A run counts for Cup week {@code W} only when it
     * started at or after {@code W}'s first rollover and finished before the rollover that ends it
     * ({@link #settlesAt}). So a run that started in one Cup week and finished in the next counts for
     * neither: that week's Cup was settled while it ran, and the new week's may be on a new layout.
     * The range is worked out from each Cup's own days, not from the current week start, so after the
     * owner moves {@code quests.week_starts_on} mid-week the Cup already running still takes its
     * entrants' runs to its own end. With the setting unchanged the range holds at most one week
     * start, the one {@link #week} gives.
     *
     * <p><b>Only on the week's own layout.</b> A Fresh slot's layout does NOT change at the Cup's
     * 04:00: last week's verified layout stays live until the new one is built and flipped (the
     * builds go one slot at a time, wait out a coming restart, and a failed one leaves the old layout
     * live for hours or a day, GEN-SPEC §3). A run on it after the rollover is a run on the layout
     * everyone practised all last week, so it must not set a time in the new week's Cup. A run on a
     * slot's own layout ({@code layout} with no recall) counts only for the Cup weeks its edition was
     * made for: {@code layout.day()} to {@code layout.endDay() - 1}. The scheduled flip into the
     * week's own edition is therefore NOT a void: it is the Cup's layout going up. A hand-built
     * course ({@code layout} null) and an archived course recalled into a Classics slot keep their
     * blocks across the rollover, so their runs count by time alone; changing them is an admin's
     * act, which voids the Cup.
     *
     * @param startedAt  when the timed run started (epoch ms): {@code finishedAt - ms}
     * @param finishedAt when it finished (epoch ms)
     * @param layout     the generated layout the run was on, as the run kept it when it started;
     *                   {@code null} for a hand-built course
     */
    public static Weeks runWeeks(Edition edition, long startedAt, long finishedAt, GenTag layout) {
        if (finishedAt < startedAt) {
            return Weeks.NONE;
        }
        long first = edition.day(finishedAt) - 6;
        long last = edition.day(startedAt);
        if (layout != null && !layout.recalled()) {
            first = Math.max(first, layout.day());
            last = Math.min(last, layout.endDay() - 1);
        }
        return first > last ? Weeks.NONE : new Weeks(first, last);
    }

    /**
     * The Cup weeks a run counts for ({@link #runWeeks}): every Cup whose week (its first day) is from
     * {@code first} to {@code last}, both included; none when {@code first > last}. The range may hold
     * days no Cup starts on: only the weeks of Cups that exist match. The storage code matches it
     * with {@code week BETWEEN first AND last}.
     */
    public record Weeks(long first, long last) {

        /** No Cup week. */
        public static final Weeks NONE = new Weeks(1, 0);

        /** Just {@code week}. */
        public static Weeks of(long week) {
            return new Weeks(week, week);
        }

        /** Whether the run counts for no Cup. */
        public boolean isEmpty() {
            return first > last;
        }

        /** Whether the run counts for the Cup of week {@code week}. */
        public boolean contains(long week) {
            return first <= week && week <= last;
        }
    }

    /**
     * Whether a Fresh Courses slot keeps one layout for each whole Cup week, so it can run a Cup: its
     * editions last whole weeks ({@code games.fresh.cadence} 7, 14, 21 or 28) and start on the quests'
     * week start. Otherwise the layout would change mid-week and void the Cup every time (a daily
     * cadence, or a {@code rebuild_day} that isn't the week start). The flip at the start of an
     * edition (whenever the build gets there after 04:00) is the week's own layout going up, not a
     * change: {@link #runWeeks} only counts runs on it, so it voids nothing.
     */
    public static boolean freshEligible(Edition edition) {
        return edition.cadenceDays() % 7 == 0 && edition.rebuildDay() == edition.weekStart();
    }

    /**
     * The Cups to settle now, oldest first: every unsettled Cup with entries whose own seven days are
     * over ({@code key.week() + 7 <= today}, the instant {@link #settlesAt}). Run at the rollover AND
     * at every boot, so a Cup whose rollover the server missed (down, or crashed mid-settlement before
     * the transaction committed) is settled on the next start. A settled Cup is never in
     * {@code unsettled}, so it is never paid twice.
     *
     * <p>It goes by each Cup's own end, not by comparing keys under the current week start, so moving
     * {@code quests.week_starts_on} mid-week never settles a running Cup early.
     *
     * @param today the course day now, {@code edition.day(now)}
     */
    public static List<CupKey> due(Collection<CupKey> unsettled, long today) {
        Set<CupKey> seen = new LinkedHashSet<>();
        for (CupKey k : unsettled) {
            if (k != null && k.week() + 7 <= today) {
                seen.add(k);
            }
        }
        List<CupKey> out = new ArrayList<>(seen);
        out.sort(Comparator.comparingLong(CupKey::week).thenComparing(CupKey::course));
        return out;
    }

    // ---- entering -------------------------------------------------------------------------------

    /**
     * Whether a player may enter a Cup, or why not ({@code null} = go ahead). The checks, in order:
     * the Cup is on server-wide with a sane entry; the course runs a Cup; the Cup is this week's; this
     * week's Cup on it wasn't voided or settled; the player isn't already in (once per course per
     * week); they can pay.
     *
     * <p>The week check matters between the 04:00 rollover and the settlement tick: a screen built at
     * 03:59 still holds last week's key, and without it an entry clicked at 04:00:30 would go into a
     * week that is over. Its entrant could never set a time there (every later run is the new
     * week's), and their entry would be paid to the others.
     *
     * @param cupsOn      {@code games.cup.enabled}
     * @param courseOn    whether this course runs a Cup (opt-in per course)
     * @param key         the Cup the player asked to enter
     * @param currentWeek the Cup week now, {@link #week}{@code (edition, now)}
     * @param settledAs   how this week's Cup ended, when it already has a settlement row; {@code null}
     *                    while it is open
     * @param alreadyIn   whether the player already has an entry row for this course and week
     * @param fee         {@code games.cup.entry}
     * @param balance     the player's tokens
     */
    public static CupRefusal refusal(boolean cupsOn, boolean courseOn, CupKey key, long currentWeek,
                                     CupPlan.Outcome settledAs, boolean alreadyIn, int fee, int balance) {
        return refusal(cupsOn, courseOn, key, currentWeek, settledAs, alreadyIn, true, fee, balance);
    }

    /**
     * {@link #refusal(boolean, boolean, CupKey, long, CupPlan.Outcome, boolean, int, int)}, and
     * after the player isn't already in: {@link CupRefusal#NOT_UP_YET} while the course's layout
     * isn't the week's own ({@code layoutUp} false: a Fresh slot still on last week's layout after the
     * rollover, whose runs can set no Cup time this week, {@link #runWeeks}).
     *
     * @param layoutUp whether runs on the course as it is now count for this week's Cup
     *                 ({@code CupLayout.covers}); always true for a hand-built or recalled course
     */
    public static CupRefusal refusal(boolean cupsOn, boolean courseOn, CupKey key, long currentWeek,
                                     CupPlan.Outcome settledAs, boolean alreadyIn, boolean layoutUp, int fee,
                                     int balance) {
        if (!cupsOn || fee < MIN_ENTRY || fee > MAX_ENTRY) {
            return CupRefusal.OFF;
        }
        if (!courseOn) {
            return CupRefusal.NOT_ON_THIS_COURSE;
        }
        if (key == null || key.week() != currentWeek) {
            return CupRefusal.WEEK_OVER;
        }
        if (settledAs == CupPlan.Outcome.VOIDED) {
            return CupRefusal.CALLED_OFF;
        }
        if (settledAs != null) {
            return CupRefusal.WEEK_OVER;
        }
        if (alreadyIn) {
            return CupRefusal.ALREADY_IN;
        }
        if (!layoutUp) {
            return CupRefusal.NOT_UP_YET;
        }
        if (balance < fee) {
            return CupRefusal.NOT_ENOUGH_TOKENS;
        }
        return null;
    }

    // ---- the live pool --------------------------------------------------------------------------

    /**
     * The pool as the screens and the feed show it while the week runs: every entry, plus the
     * top-up once 2 or more are in.
     *
     * @param tokens the pool in tokens
     * @param in     how many are in
     */
    public record LivePool(int tokens, int in) {

        /** "Cup pool: 35 tokens · 5 in". */
        public String line() {
            return CupText.poolLine(tokens, in);
        }
    }

    /** The live pool of {@code entries} with a configured top-up of {@code topup}. */
    public static LivePool livePool(Collection<CupEntry> entries, int topup) {
        long sum = 0;
        for (CupEntry e : entries) {
            sum += e.paid();
        }
        int in = entries.size();
        return new LivePool(Math.toIntExact(sum + (in >= CONTEST ? Math.max(0, topup) : 0)), in);
    }

    // ---- the table ------------------------------------------------------------------------------

    /**
     * The percent each place gets with {@code times} Cup times: {@code [70, 30]} for 2,
     * {@code [50, 30, 20]} for 3 or more, and none below 2 (no contest).
     */
    public static int[] shares(int times) {
        if (times < CONTEST) {
            return new int[0];
        }
        return (times == 2 ? TWO : THREE_OR_MORE).clone();
    }

    /**
     * {@code pool} split by {@code percents}: each amount rounded down, and the remainder to the
     * first. The amounts always add up to {@code pool} exactly.
     */
    public static int[] split(int pool, int[] percents) {
        if (pool < 0) {
            throw new IllegalArgumentException("a pool is 0 or more tokens: " + pool);
        }
        int[] out = new int[percents.length];
        if (out.length == 0) {
            return out;
        }
        long given = 0;
        for (int i = 0; i < percents.length; i++) {
            out[i] = (int) ((long) pool * percents[i] / 100);
            given += out[i];
        }
        out[0] += (int) (pool - given);
        return out;
    }

    // ---- settling -------------------------------------------------------------------------------

    /**
     * The settlement plan for a Cup at its week's rollover (§D2), from its entries and their Cup times.
     *
     * <ul>
     *   <li>nobody entered: {@link CupPlan.Outcome#EMPTY}, nothing paid;</li>
     *   <li>one entrant: {@link CupPlan.Outcome#REFUND_ALONE}, their entry back, no top-up;</li>
     *   <li>fewer than 2 Cup times: {@link CupPlan.Outcome#REFUND_NO_CONTEST}, every entry back, no
     *       top-up;</li>
     *   <li>otherwise {@link CupPlan.Outcome#PRIZES}: the pool (entries + top-up) shared by Cup time,
     *       70/30 or 50/30/20, rounded down with the remainder to 1st; ties share their places'
     *       amounts, the remainder to the earliest time; entrants without a time get nothing.</li>
     * </ul>
     *
     * The plan never depends on the order {@code entries} come in.
     *
     * @param topup {@code games.cup.server_topup} (below 0 reads as 0)
     * @throws IllegalArgumentException when a player has two entries (the entry table's key forbids it)
     */
    public static CupPlan settle(CupKey key, Collection<CupEntry> entries, int topup) {
        List<CupEntry> all = distinct(entries);
        int in = sumPaid(all);
        if (all.isEmpty()) {
            return new CupPlan(key, CupPlan.Outcome.EMPTY, null, 0, 0, 0, List.of());
        }
        if (all.size() == 1) {
            return refundAll(key, CupPlan.Outcome.REFUND_ALONE, null, all, in);
        }
        List<CupEntry> timed = new ArrayList<>();
        List<CupEntry> untimed = new ArrayList<>();
        for (CupEntry e : all) {
            (e.hasTime() ? timed : untimed).add(e);
        }
        if (timed.size() < CONTEST) {
            return refundAll(key, CupPlan.Outcome.REFUND_NO_CONTEST, null, all, in);
        }
        timed.sort(BY_CUP_TIME);
        untimed.sort(BY_ENTRY);
        int top = Math.max(0, topup);
        int pool = Math.toIntExact((long) in + top);
        int[] amounts = split(pool, shares(timed.size()));

        List<CupPayout> lines = new ArrayList<>(all.size());
        int i = 0;
        while (i < timed.size()) {
            int j = i;
            while (j < timed.size() && timed.get(j).bestMs() == timed.get(i).bestMs()) {
                j++;
            }
            int group = j - i;
            long total = 0;
            for (int p = i; p < j && p < amounts.length; p++) {
                total += amounts[p];
            }
            long each = total / group;
            long rest = total % group;
            for (int m = i; m < j; m++) {
                CupEntry e = timed.get(m);
                int tokens = (int) (each + (m == i ? rest : 0));
                lines.add(new CupPayout(e.player(), tokens > 0 ? CupPayout.Kind.PRIZE : CupPayout.Kind.NONE,
                        tokens, i + 1, group, e.bestMs(), e.paid()));
            }
            i = j;
        }
        for (CupEntry e : untimed) {
            lines.add(new CupPayout(e.player(), CupPayout.Kind.NONE, 0, 0, 0, CupEntry.NO_TIME, e.paid()));
        }
        return new CupPlan(key, CupPlan.Outcome.PRIZES, null, in, top, pool, lines);
    }

    /**
     * The plan for a Cup voided mid-week (§D2: the course was deleted, changed layout or closed):
     * every entry back in full, no top-up, and the reason to tell each player. It is recorded as the
     * Cup's settlement, so the week can be neither entered nor settled again.
     */
    public static CupPlan voided(CupKey key, Collection<CupEntry> entries, CupPlan.VoidReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("a voided Cup needs a reason to tell the players");
        }
        List<CupEntry> all = distinct(entries);
        return refundAll(key, CupPlan.Outcome.VOIDED, reason, all, sumPaid(all));
    }

    private static CupPlan refundAll(CupKey key, CupPlan.Outcome outcome, CupPlan.VoidReason reason,
                                     List<CupEntry> all, int in) {
        List<CupEntry> sorted = new ArrayList<>(all);
        sorted.sort(BY_ENTRY);
        List<CupPayout> lines = new ArrayList<>(sorted.size());
        for (CupEntry e : sorted) {
            lines.add(new CupPayout(e.player(), e.paid() > 0 ? CupPayout.Kind.REFUND : CupPayout.Kind.NONE,
                    e.paid(), 0, 0, e.bestMs(), e.paid()));
        }
        return new CupPlan(key, outcome, reason, in, 0, in, lines);
    }

    /** The entries with a check that no player appears twice. */
    private static List<CupEntry> distinct(Collection<CupEntry> entries) {
        List<CupEntry> out = new ArrayList<>(entries == null ? List.of() : entries);
        Set<UUID> seen = new HashSet<>();
        for (CupEntry e : out) {
            if (e == null) {
                throw new IllegalArgumentException("a null entry");
            }
            if (!seen.add(e.player())) {
                throw new IllegalArgumentException("two entries for " + e.player() + " in one Cup");
            }
        }
        return out;
    }

    private static int sumPaid(Collection<CupEntry> entries) {
        long sum = 0;
        for (CupEntry e : entries) {
            sum += e.paid();
        }
        return Math.toIntExact(sum);
    }

    // ---- the ledger and its proof ---------------------------------------------------------------

    /**
     * Every token movement of a Cup, entries first (in entry order) and then the plan's payments:
     * {@code -paid} per entry, {@code +tokens} per prize or refund. Their sum is the proof the
     * server keeps nothing: exactly {@code plan.topup()}, which is 0 unless the Cup paid prizes.
     */
    public static List<CupLedgerRow> ledger(Collection<CupEntry> entries, CupPlan plan) {
        List<CupEntry> sorted = new ArrayList<>(entries);
        sorted.sort(BY_ENTRY);
        List<CupLedgerRow> rows = new ArrayList<>();
        for (CupEntry e : sorted) {
            if (e.paid() > 0) {
                rows.add(new CupLedgerRow(e.player(), CupSource.GAMES_CUP_ENTRY, -e.paid()));
            }
        }
        for (CupPayout l : plan.lines()) {
            if (l.pays()) {
                rows.add(new CupLedgerRow(l.player(), l.kind().source(), l.tokens()));
            }
        }
        return rows;
    }

    /** The sum of {@code rows}' deltas. */
    public static long net(Collection<CupLedgerRow> rows) {
        long sum = 0;
        for (CupLedgerRow r : rows) {
            sum += r.delta();
        }
        return sum;
    }

    /**
     * Everything wrong with {@code plan} as the settlement of {@code entries} with a configured top-up
     * of {@code topup}; empty when it is right. It checks the balance ({@code sum(payouts) ==
     * sum(entries) + topup}, and a ledger net of exactly the top-up), that the top-up comes only with
     * a contest, that every entrant has exactly one line and nobody else has any, that the outcome
     * fits the entrants, that a refund gives back exactly what was paid, that nobody without a Cup
     * time is paid in a contest, and that a faster time never gets fewer tokens than a slower one.
     *
     * <p>The settlement code runs it before writing a plan, and refuses (with one WARN, leaving the
     * Cup unsettled to retry) a plan it finds anything wrong with.
     */
    public static List<String> problems(Collection<CupEntry> entries, int topup, CupPlan plan) {
        List<String> out = new ArrayList<>();
        if (plan == null) {
            out.add("no plan");
            return out;
        }
        Map<UUID, CupEntry> byPlayer = new HashMap<>();
        long in = 0;
        int timed = 0;
        for (CupEntry e : entries) {
            if (byPlayer.put(e.player(), e) != null) {
                out.add("two entries for " + e.player());
            }
            in += e.paid();
            if (e.hasTime()) {
                timed++;
            }
        }
        if (plan.entries() != in) {
            out.add("entries are " + in + " but the plan says " + plan.entries());
        }

        CupPlan.Outcome expected;
        if (entries.isEmpty()) {
            expected = CupPlan.Outcome.EMPTY;
        } else if (entries.size() == 1) {
            expected = CupPlan.Outcome.REFUND_ALONE;
        } else if (timed < CONTEST) {
            expected = CupPlan.Outcome.REFUND_NO_CONTEST;
        } else {
            expected = CupPlan.Outcome.PRIZES;
        }
        if (plan.outcome() == CupPlan.Outcome.VOIDED) {
            if (plan.reason() == null) {
                out.add("a voided Cup without a reason");
            }
        } else {
            if (plan.outcome() != expected) {
                out.add("the outcome is " + plan.outcome() + " but " + entries.size() + " entrants with "
                        + timed + " Cup times make it " + expected);
            }
            if (plan.reason() != null) {
                out.add("a reason on a Cup that wasn't voided");
            }
        }

        int expectedTopup = plan.outcome() == CupPlan.Outcome.PRIZES ? Math.max(0, topup) : 0;
        if (plan.topup() != expectedTopup) {
            out.add("the top-up is " + plan.topup() + " but should be " + expectedTopup
                    + (expectedTopup == 0 ? " (only a contest of 2 or more gets one)" : ""));
        }
        if (plan.pool() != in + plan.topup()) {
            out.add("the pool is " + plan.pool() + " but entries + top-up is " + (in + plan.topup()));
        }
        long paidOut = 0;
        for (CupPayout l : plan.lines()) {
            paidOut += l.tokens();
        }
        if (paidOut != in + expectedTopup) {
            out.add("the plan pays out " + paidOut + " but entries + top-up is " + (in + expectedTopup)
                    + (paidOut < in + expectedTopup ? ": the server would keep tokens" : ": tokens from nowhere"));
        }
        long net = 0;
        for (CupPayout l : plan.lines()) {
            net += l.pays() ? l.tokens() : 0;
        }
        net -= in;
        if (net != expectedTopup) {
            out.add("the ledger nets " + net + " but should net exactly the top-up " + expectedTopup);
        }

        Set<UUID> lined = new HashSet<>();
        for (CupPayout l : plan.lines()) {
            CupEntry e = byPlayer.get(l.player());
            if (e == null) {
                out.add("a line for " + l.player() + ", who didn't enter");
                continue;
            }
            if (!lined.add(l.player())) {
                out.add("two lines for " + l.player());
            }
            if (l.tokens() < 0) {
                out.add("a negative payout for " + l.player());
            }
            if ((l.kind() == CupPayout.Kind.NONE) != (l.tokens() == 0)) {
                out.add("a " + l.kind() + " line of " + l.tokens() + " tokens for " + l.player());
            }
            if (plan.outcome().refunds()) {
                if (l.kind() == CupPayout.Kind.PRIZE) {
                    out.add("a prize in a refunded Cup for " + l.player());
                }
                if (l.tokens() != e.paid()) {
                    out.add("a refund of " + l.tokens() + " for " + l.player() + ", who paid " + e.paid());
                }
            } else if (plan.outcome() == CupPlan.Outcome.PRIZES) {
                if (l.kind() == CupPayout.Kind.REFUND) {
                    out.add("a refund in a paid Cup for " + l.player());
                }
                if (!e.hasTime() && l.tokens() > 0) {
                    out.add("a prize for " + l.player() + ", who set no Cup time");
                }
            }
        }
        for (UUID p : byPlayer.keySet()) {
            if (!lined.contains(p)) {
                out.add("no line for entrant " + p);
            }
        }
        if (plan.outcome() == CupPlan.Outcome.PRIZES) {
            for (CupPayout a : plan.lines()) {
                CupEntry ea = byPlayer.get(a.player());
                for (CupPayout b : plan.lines()) {
                    CupEntry eb = byPlayer.get(b.player());
                    if (ea != null && eb != null && ea.hasTime() && eb.hasTime()
                            && ea.bestMs() < eb.bestMs() && a.tokens() < b.tokens()) {
                        out.add("a slower time gets more: " + b.player() + " " + b.tokens() + " over "
                                + a.player() + " " + a.tokens());
                    }
                }
            }
        }
        return out;
    }
}
