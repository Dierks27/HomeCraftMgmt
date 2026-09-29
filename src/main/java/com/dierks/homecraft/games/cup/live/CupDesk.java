package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupLayout;
import com.dierks.homecraft.games.cup.CupOptIn;
import com.dierks.homecraft.games.cup.CupPayout;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.CupText;
import com.dierks.homecraft.games.cup.CupWatch;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.storage.CupDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Weekly Cup's desk (EVENTS-OWNER-DECISIONS §D2): who is in which Cup, entering, Cup times,
 * paying out at the rollover, and calling a Cup off. It holds no Bukkit type, so the tests drive it
 * against a real SQLite with a movable clock; {@link WeeklyCup} wires it to the server.
 *
 * <p><b>The week</b> is the quests' week turning at the Fresh Courses' 04:00 ({@link CupRules#week}),
 * so a Cup is paid at the same moment the week's Fresh Courses change. {@link #tick} runs once a
 * minute and once at every start: it first settles every Cup whose seven days are over
 * ({@link CupRules#due}: the rollover itself, or one a crash or downtime missed), oldest first, and
 * only then looks at this week's Cups, so a new week's flip can never touch last week's Cup.
 *
 * <p><b>Calling a Cup off.</b> Once a minute {@link #watch} compares each of this week's running Cups
 * with its course ({@link CupWatch}): the row gone, the course or its Fresh slot closed by an admin,
 * or a new layout, and every entry comes back with the reason. The admin commands that delete, edit
 * or close a course call {@link #voidNow} on top of it, so the refund is at once.
 *
 * <p><b>Every line is kept before it is said.</b> A settlement writes each entrant's result line in
 * its own transaction ({@link CupDao.Settled#notices()}); a player who is online reads it at once
 * (and it is forgotten), anyone else at their next join. So a crash can lose neither the tokens nor
 * the reason.
 *
 * <p>Never throws out of {@link #tick}: a database error is one WARN (a minute later it tries again),
 * and a plan that breaks a rule is refused with one WARN per Cup and stays unsettled.
 */
public final class CupDesk {

    /** The {@code game_prefs} key of {@code /hcm play cup off}: {@code off} hides the Cup prompts. */
    public static final String PREF_PROMPTS = "cup_prompts";

    /** What the desk needs from the server. */
    public interface Host {

        /** Now (epoch ms): the plugin's clock. */
        long now();

        /** The Fresh Courses' schedule: the zone, the 04:00 rollover and the quests' week. */
        Edition edition();

        /** {@code games.cup}, live. */
        CupSettings settings();

        /**
         * The time-trial course {@code id} as its row is now (open or closed), or {@code null} when it
         * has no row.
         *
         * @throws SQLException when it can't be read (the watch then leaves the Cup alone)
         */
        Course course(String id) throws SQLException;

        /** Whether Fresh Courses has slot {@code id} switched on; {@code null} when it can't tell. */
        Boolean slotWanted(String id);

        /** Say {@code line} ({@code &}-coded) to {@code player} now if they are online; whether it was said. */
        boolean tellNow(UUID player, String line);

        Logger logger();
    }

    /**
     * A Cup that just ended.
     *
     * @param name the course's name as the players read it
     */
    public record Closed(CupKey key, String name, CupPlan plan) {
    }

    /**
     * One course's Cup as a player sees it this week.
     *
     * @param key       this week's Cup on the course
     * @param on        the course runs a Cup ({@link CupOptIn})
     * @param open      new entries are on ({@code games.cup.enabled})
     * @param fee       the entry
     * @param pool      the pool now ("Cup pool: 35 tokens · 5 in")
     * @param mine      the viewer's entry, or {@code null}
     * @param settledAs how this week's Cup ended already (called off, or settled early), or {@code null}
     * @param endsAt    when it is paid (epoch ms)
     * @param refusal   why the viewer can't enter, or {@code null} when they can
     */
    public record View(CupKey key, boolean on, boolean open, int fee, CupRules.LivePool pool, CupEntry mine,
                       CupPlan.Outcome settledAs, long endsAt, CupRefusal refusal) {

        /** Whether the viewer is in this week's Cup. */
        public boolean in() {
            return mine != null;
        }

        /** Whether the Cup is worth showing: it runs on the course and is taking entries, or already has some. */
        public boolean shown() {
            return on && settledAs == null && (open || pool.in() > 0);
        }
    }

    private final CupDao dao;
    private final Host host;
    /** Cups whose plan broke a rule: said once, then left unsettled. */
    private final Set<CupKey> unsound = new HashSet<>();
    private long lastDbWarn;

    public CupDesk(CupDao dao, Host host) {
        this.dao = dao;
        this.host = host;
    }

    public CupDao dao() {
        return dao;
    }

    // ---- the week -------------------------------------------------------------------------------

    /** The Cup week now. */
    public long week() {
        return CupRules.week(host.edition(), host.now());
    }

    /** This week's Cup on {@code courseId}. */
    public CupKey key(String courseId) {
        return new CupKey(courseId, week());
    }

    /** When this week's Cups are paid (epoch ms). */
    public long endsAt() {
        return CupRules.settlesAt(host.edition(), week());
    }

    /** When {@code key}'s Cup is paid (epoch ms). */
    public long endsAt(CupKey key) {
        return CupRules.settlesAt(host.edition(), key.week());
    }

    /** Whether Fresh Courses keep one layout for each whole Cup week ({@link CupRules#freshEligible}). */
    public boolean freshEligible() {
        return CupRules.freshEligible(host.edition());
    }

    // ---- which courses ------------------------------------------------------------------------

    /** The time-trial course {@code id} as its row is now, or {@code null} ({@link Host#course}). */
    public Course course(String id) throws SQLException {
        return host.course(id);
    }

    /** The course as the opt-in sees it. */
    public static CupOptIn.Course optInView(Course c) {
        return new CupOptIn.Course(c.generated(), c.generated() && c.gen().recalled(), c.kind().id());
    }

    /** The layout a Cup on {@code c} is raced on. */
    public static CupLayout layout(Course c) {
        return c.generated() ? CupLayout.fresh(c.gen()) : CupLayout.handBuilt(c.kind().id(), c.layoutHash());
    }

    /** Whether {@code c} runs a Cup (the admin's switch, else the default). */
    public boolean runsCup(Course c) throws SQLException {
        return c != null && CupOptIn.on(dao.chosen(c.id()), optInView(c), freshEligible());
    }

    /** {@code c}'s Cup this week as {@code viewer} sees it ({@code viewer} null: nobody in particular). */
    public View view(Course c, UUID viewer) throws SQLException {
        CupSettings s = host.settings();
        long week = week();
        CupKey key = new CupKey(c.id(), week);
        boolean on = runsCup(c);
        List<CupEntry> entries = dao.entries(key);
        CupEntry mine = null;
        if (viewer != null) {
            for (CupEntry e : entries) {
                if (e.player().equals(viewer)) {
                    mine = e;
                }
            }
        }
        CupPlan.Outcome settled = dao.settledAs(key);
        int balance = viewer == null ? 0 : dao.balance(viewer);
        CupRefusal refusal = CupRules.refusal(s.enabled(), on, key, week, settled, mine != null, s.entry(), balance);
        return new View(key, on, s.enabled(), s.entry(), CupRules.livePool(entries, s.serverTopup()), mine, settled,
                endsAt(key), refusal);
    }

    // ---- entering -------------------------------------------------------------------------------

    /**
     * Enter {@code player} in this week's Cup on {@code c}: the rules first, then the debit and the
     * entry row in one transaction (which checks everything again).
     *
     * @return {@code null} when they are in, or why not
     */
    public CupRefusal enter(UUID player, Course c) throws SQLException {
        View v = view(c, player);
        if (v.refusal() != null) {
            return v.refusal();
        }
        return dao.enter(v.key(), player, v.fee(), c.name(), host.now(), v.key().week(), layout(c).encode());
    }

    // ---- Cup times ------------------------------------------------------------------------------

    /**
     * A counted, timed run of {@code ms} that finished at {@code finishedAt} on {@code ranOn} (the
     * course as the run kept it when it started): it sets a Cup time in each open Cup the player is in
     * whose week the run counts for ({@link CupRules#runWeeks}). The caller passes counted runs only.
     *
     * @return whether a Cup time changed
     */
    public boolean counted(UUID player, Course ranOn, long ms, long finishedAt) throws SQLException {
        if (ranOn == null || ms <= 0) {
            return false;
        }
        CupRules.Weeks weeks = CupRules.runWeeks(host.edition(), finishedAt - ms, finishedAt, ranOn.gen());
        return !weeks.isEmpty() && dao.run(ranOn.id(), player, ms, finishedAt, weeks) > 0;
    }

    // ---- settling and calling off ---------------------------------------------------------------

    /** The minute's work: settle what is due, then watch this week's Cups. Never throws. */
    public List<Closed> tick() {
        List<Closed> out = new ArrayList<>(settleDue());
        out.addAll(watch());
        return out;
    }

    /**
     * Settle every Cup whose seven days are over, oldest first: at the rollover, and at a start after
     * a rollover the server missed. Never throws.
     */
    public List<Closed> settleDue() {
        List<Closed> out = new ArrayList<>();
        List<CupKey> due;
        try {
            due = CupRules.due(dao.openKeys(), host.edition().day(host.now()));
        } catch (SQLException | RuntimeException e) {
            dbWarn("could not read the running Cups", e);
            return out;
        }
        for (CupKey key : due) {
            Closed c = settle(key);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Settle {@code key}'s Cup now with the configured top-up (at its rollover, or an admin's
     * {@code settle}): paid once, with every entrant's line. {@code null} when it was already settled
     * or couldn't be (said in the log).
     */
    public Closed settle(CupKey key) {
        String name = name(key.course());
        try {
            CupDao.Settled s = dao.settle(key, host.settings().serverTopup(), name, host.now(), words(name));
            return done(key, name, s);
        } catch (CupDao.Unsound e) {
            if (unsound.add(key)) {
                host.logger().warning("Weekly Cup: " + e.getMessage() + " - nothing was paid; it stays unsettled");
            }
        } catch (SQLException | RuntimeException e) {
            dbWarn("could not settle the Cup on " + key.course() + " (week " + Edition.date(key.week()) + ")", e);
        }
        return null;
    }

    /**
     * Call this week's Cup on {@code courseId} off now, every entry back with {@code reason} (the
     * course was deleted, edited or closed; an admin stopped it). {@code null} when there was nothing
     * to call off.
     *
     * @param name the course's name for the players (it may already be gone)
     */
    public Closed voidNow(String courseId, CupPlan.VoidReason reason, String name) {
        return voidCup(key(courseId), reason, name == null ? name(courseId) : name);
    }

    private Closed voidCup(CupKey key, CupPlan.VoidReason reason, String name) {
        try {
            CupDao.Settled s = dao.voidCup(key, reason, name, host.now(), words(name));
            return done(key, name, s);
        } catch (CupDao.Unsound e) {
            if (unsound.add(key)) {
                host.logger().warning("Weekly Cup: " + e.getMessage() + " - nothing was refunded; it stays open");
            }
        } catch (SQLException | RuntimeException e) {
            dbWarn("could not call off the Cup on " + key.course(), e);
        }
        return null;
    }

    /**
     * Compare each of this week's running Cups with its course ({@link CupWatch}) and call it off when
     * the course is gone, closed or re-made. Never throws.
     */
    public List<Closed> watch() {
        List<Closed> out = new ArrayList<>();
        List<CupKey> running = new ArrayList<>();
        long week;
        try {
            week = week();
            for (CupKey k : dao.openKeys()) {
                if (k.week() == week) {
                    running.add(k);
                }
            }
        } catch (SQLException | RuntimeException e) {
            dbWarn("could not read the running Cups", e);
            return out;
        }
        for (CupKey key : running) {
            try {
                Course c = host.course(key.course());
                boolean freshSlot = Slots.reserved(key.course());
                CupLayout now = c == null ? null : layout(c);
                CupWatch.Seen seen = c == null ? CupWatch.Seen.gone(freshSlot)
                        : new CupWatch.Seen(true, c.enabled(), freshSlot || c.generated(),
                        freshSlot || c.generated() ? host.slotWanted(c.id()) : null, now);
                CupWatch.Verdict v = CupWatch.judge(CupLayout.decode(dao.layout(key)), seen, key.week());
                switch (v.action()) {
                    case ADOPT -> dao.layout(key, now.encode());
                    case VOID -> {
                        Closed closed = voidCup(key, v.reason(), c == null ? name(key.course()) : c.name());
                        if (closed != null) {
                            host.logger().info("Weekly Cup: the Cup on " + key.course() + " was called off ("
                                    + CupText.because(v.reason()) + "); " + closed.plan().paidOut()
                                    + " tokens went back to " + closed.plan().payouts().size() + " player(s).");
                            out.add(closed);
                        }
                    }
                    case NONE -> {
                        // still standing
                    }
                }
            } catch (SQLException | RuntimeException e) {
                dbWarn("could not check the course of the Cup on " + key.course(), e);
            }
        }
        return out;
    }

    /** Log a Cup that ended, say each online entrant's line, and forget the lines they read. */
    private Closed done(CupKey key, String name, CupDao.Settled s) {
        if (s == null) {
            return null;
        }
        unsound.remove(key);
        CupPlan p = s.plan();
        if (p.outcome() != CupPlan.Outcome.VOIDED) {
            host.logger().info("Weekly Cup: " + key.course() + " (week of " + Edition.date(key.week()) + ") - "
                    + p.outcome() + ", " + p.lines().size() + " in, pool " + p.pool() + " (top-up " + p.topup()
                    + "), " + p.paidOut() + " tokens paid.");
        }
        for (CupDao.Notice n : s.notices()) {
            try {
                if (host.tellNow(n.player(), n.line())) {
                    dao.read(n);
                }
            } catch (SQLException | RuntimeException e) {
                dbWarn("could not mark a Cup line as read", e); // it is said again at their next join
            }
        }
        return new Closed(key, name, p);
    }

    /** Each entrant's line: their place and tokens, or their refund and why. */
    static CupDao.Words words(String name) {
        return (plan, line) -> colour(line) + CupText.result(plan, line, name);
    }

    private static String colour(CupPayout line) {
        return switch (line.kind()) {
            case PRIZE -> "&6";
            case REFUND -> "&e";
            case NONE -> "&7";
        };
    }

    /** A course's name for the players: its row's, a Fresh slot's, or its id. */
    String name(String courseId) {
        try {
            Course c = host.course(courseId);
            if (c != null && c.name() != null && !c.name().isBlank()) {
                return c.name();
            }
        } catch (SQLException | RuntimeException e) {
            // its id will do
        }
        Slots.Def d = Slots.of(courseId);
        return d != null ? d.name() : courseId;
    }

    // ---- players' own switch ----------------------------------------------------------------------

    /** Whether the player hid the Cup prompts ({@code /hcm play cup off}). */
    public static boolean hidden(String prefValue) {
        return "off".equalsIgnoreCase(prefValue);
    }

    private void dbWarn(String what, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - lastDbWarn > 60_000L) {
            lastDbWarn = now;
            host.logger().log(Level.WARNING, "Weekly Cup: " + what + " - tried again in a minute", e);
        }
    }
}
