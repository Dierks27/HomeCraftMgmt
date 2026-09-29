package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenArgs;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.CourseCode;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedCourse;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.dropper.DropRules;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The Fresh Courses engine (GEN-SPEC §3, weekly addendum §1): the schedule, the builds, and the gate
 * the course engines ask ({@link GeneratedCourses}).
 *
 * <p><b>Editions.</b> Everything here works on editions, not days: a slot shows the edition
 * {@link GenScheduler#target} names (weekly by default, on the fixed grid of {@link Edition}), its
 * rerolls, pins, boards and status are per edition, and a cadence change on reload keeps the live
 * layouts until the new schedule's first start (never rebuilt mid-edition just because the
 * setting changed). When the engine first sees a schedule it remembers the moment in
 * {@code hcm_meta} ({@code gen.cadence}), so a restart in between doesn't move the switch. Tries are
 * still counted per course day, so a failed build is tried again the next morning rather than a
 * week later.
 *
 * <p><b>The gate (R5).</b> A generated course is playable only while this engine vouches for its
 * blocks: its live half was verified against its plan in this run. At every start the gate is
 * shut and every live half is verified first — derived again from its tag, converged, and for
 * golf its witness lines replayed on the real blocks — which is also how the world heals after a
 * crash between the database's flip and the world's save (§3.5). A run keeps counting while the
 * layout it started on still stands ({@link #standing}): the live layout, or the one before it
 * until its half starts being cleared.
 *
 * <p><b>A build (R1-R4).</b> One slot at a time, all slots in one queue: plan on the planner thread
 * (pure, from the seed), check the plan, move people out of the idle half, converge it a few blocks
 * a tick ({@link BuildJob}), verify, replay golf witnesses, then flip with one database write. The
 * old half stays standing until nobody is on it, then is emptied (CLEAR_OLD). Nothing about a job
 * is stored: a stop at any point leaves the database before or after the flip, and the next start
 * converges from there.
 *
 * <p><b>Failure isolation (S8).</b> The engine runs inside the {@code fresh_courses} game's guard; a planner
 * exception is caught on its thread and becomes a failed try; a failed build leaves the old layout
 * up and says why in status. Nothing here ever reaches Time Trials or Mini Golf except through the
 * gate and {@code coursesChanged}.
 *
 * <p><b>The archive, the Classics and kept courses (GEN-SPEC-KEEP).</b> Every flip that makes an
 * edition live also archives it — its course code, dates, seed and whole plan — in the same
 * transaction, and pruning keeps an archived edition's board as long as its row. The three
 * Classics slots are slots too, but the schedule never builds one: an admin's {@code recall}
 * records what a Classics slot should hold ({@link ClassicWant}), and the engine makes it so with
 * the same converge, verify and flip, from the archived plan moved into its half (never planned
 * again). Its row carries the ORIGINAL edition's tag, so its board and first-finish reward are the
 * original's. Kept courses are built by {@link KeepService}, between this engine's jobs.
 *
 * <p>Driven by the game: {@link #start} and {@link #stop}, {@link #worldsReady} a tick after enable,
 * {@link #tick} every tick and {@link #check} every second. Everything runs on the main thread;
 * only plans are made elsewhere, and they come back through {@link #tick}'s inbox.
 */
public final class GenService implements GeneratedCourses, GenOps {

    /** A plan still running after this long is given up (a failed try, never a different plan). */
    public static final long PLAN_KILL_MS = 120_000L;
    /** CLEAR_OLD looks for an empty half this often. */
    public static final long CLEAR_EVERY_MS = 10_000L;
    /** A missing seed secret is logged at most this often. */
    public static final long SECRET_WARN_MS = 3_600_000L;
    /** The world checks of §2.4 are run again this often (and before every build). */
    public static final long VET_EVERY_MS = 300_000L;
    /** Star Charts are kept this many weeks. */
    public static final int KEEP_WEEKS = 12;
    /** Each course always keeps its last this many editions' boards, however old. */
    public static final int KEEP_EDITIONS = 8;
    /**
     * The counted work each generator may use per plan: each planner's own bound, which a plan
     * never needs more than (a smaller one would fail the same seed at every try of the day).
     */
    static final Map<String, Long> WORK = Map.of(Slots.PARKOUR, ParkourPlanner.WORK_BUDGET, Slots.RINGS,
            RingsPlanner.WORK_BUDGET, Slots.GOLF, GolfPlanner.COURSE_BUDGET, Slots.BOAT, BoatPlanner.WORK_BUDGET,
            Slots.DROPPER, DropperPlanner.WORK_BUDGET);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter CLOCK_S = DateTimeFormatter.ofPattern("h:mm:ss", Locale.US);

    /** What a job does. */
    enum Kind {
        /** Verify and heal the live half (every boot; {@code rebuild}). */
        HEAL,
        /** A new layout into the idle half, then the flip. */
        BUILD,
        /** A new layout into the idle half, no flip. */
        PREVIEW,
        /** The preview becomes live. */
        PROMOTE,
        /** Empty the idle half. */
        CLEAR_OLD,
        /** Count what isn't air in both halves (and claim them when there is nothing). */
        SCAN,
        /** Empty both halves, then claim them. */
        CLAIM,
        /** Empty both halves; the slot is off. */
        DECOMMISSION,
        /** An archived course into a Classics slot's idle half, then the flip (no planner). */
        RECALL,
        /** Empty both halves of a Classics slot that holds nothing any more. */
        UNRECALL
    }

    /** Where a job is. */
    enum Stage {
        START, PLANNING, SCANNING, EVACUATE, CONVERGE
    }

    /** One pass of a job over one half. */
    private record Step(char which, Plan plan, BuildJob.Mode mode) {
    }

    /** A job: one slot, one kind, run to the end or failed. */
    private final class Job {
        final Kind kind;
        final SlotState slot;
        final Consumer<String> report;
        final long started;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<Step> steps = new ArrayList<>();
        final Evacuator evac = new Evacuator();
        final BuildBudget budget = new BuildBudget(host::nanoTime);
        Stage stage = Stage.START;
        /** The first day of the edition it builds for. */
        long day;
        /** That edition's length in days. */
        int cadence = Edition.DAILY;
        int reroll;
        long seed;
        String mix = "";
        char half = 'A';
        GenTag tag;
        Plan plan;
        long planStarted;
        long planMs;
        int stepIndex;
        BuildJob build;
        long evacStart;
        long writes;
        long found;
        List<String> firstFound = List.of();
        int chunks;
        boolean pauseWarned;
        boolean deferWarned;
        String proof = "verify ok";
        /** A recall: what it brings back, and the archive row it comes from. */
        ClassicWant want;
        GenArchiveDao.Row source;
        /** The slot the plan is made for, and where (a recall's are the original slot, moved). */
        Slots.Def planDef;
        Box planBox;

        Job(Kind kind, SlotState slot, Consumer<String> report) {
            this.kind = kind;
            this.slot = slot;
            this.report = report == null ? line -> { } : report;
            this.started = host.now();
        }
    }

    private final GenHost host;
    private final Map<String, Planner> planners;
    private final Map<String, SlotState> slots = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Set<String> said = new HashSet<>();
    private Job job;
    private volatile boolean online;
    private boolean running;
    private long readyAt = -1;
    private Long secret;
    private long lastSecretWarn = Long.MIN_VALUE / 2;
    private long lastVet = Long.MIN_VALUE / 2;
    private long prunedDay = Long.MIN_VALUE;
    /** The edition the build queue last started for (one INFO line an edition). */
    private String queueEdition = "";
    /** The schedule ({@code <cadence>|<rebuild day>}) as last read, and when it was first seen (epoch ms). */
    private String scheduleSig = "";
    private long scheduleSince;
    /** Each week's Star Chart goals once fixed ({@link #goals}), by the week's first day. */
    private final Map<Long, List<DailyStars.Goal>> weekGoals = new HashMap<>();
    private volatile List<Object[]> areas = List.of();
    /**
     * The flow-only boxes {world, {@link Box}}: every kept Dropper's plot and a plot job's in flight
     * ({@link #wetPlots}), whose water must never flow out though nothing else there is guarded.
     */
    private volatile List<Object[]> wetPlots = List.of();
    /** Every world {@link #areas} and {@link #wetPlots} touch: a flow anywhere else is a quick no. */
    private volatile List<String> guardWorlds = List.of();
    /** The guard's areas as {@link GenRegionGuard} asks them: every change refused in {@link #areas}... */
    private final GenRegionGuard.Area guardArea = new GenRegionGuard.Area() {
        @Override
        public boolean in(String world, int x, int y, int z) {
            return inArea(world, x, y, z);
        }

        @Override
        public boolean covers(String world) {
            return guarded(world);
        }
    };
    /** ...and no fluid flowing out of {@link #wetPlots} or a Dropper keep in flight. */
    private final GenRegionGuard.Area wetArea = new GenRegionGuard.Area() {
        @Override
        public boolean in(String world, int x, int y, int z) {
            return inWet(world, x, y, z);
        }

        @Override
        public boolean covers(String world) {
            return guarded(world) || keeper.wetJobIn(world);
        }
    };
    /** Kept courses and their plots (GEN-SPEC-KEEP §4); it builds only while this engine doesn't. */
    private final KeepService keeper;
    /** Course codes already looked up, by {@code slot|edition}. */
    private final Map<String, String> codes = new HashMap<>();
    /** The archive is pruned at most once a course day. */
    private long archivePrunedDay = Long.MIN_VALUE;

    /**
     * @param planners each generator's planner by its id ({@code parkour}, {@code rings}, {@code golf},
     *                 {@code boat}); a slot whose planner is missing never builds
     */
    public GenService(GenHost host, Map<String, Planner> planners) {
        this.host = host;
        this.planners = Map.copyOf(planners);
        for (Slots.Def d : Slots.ALL) {
            slots.put(d.id(), new SlotState(d));
        }
        for (Slots.Def d : Slots.CLASSICS) {
            slots.put(d.id(), new SlotState(d));
        }
        this.keeper = new KeepService(this, host, this.planners);
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Start (enable, or {@code fresh_courses} opened): read every live row; the gate is shut until verified. */
    public void start() {
        running = true;
        readyAt = -1;
        refresh();
        for (SlotState s : slots.values()) {
            readRow(s);
        }
        host.logger().info("Fresh Courses: started (" + host.settings().cadenceName() + ") - every live course stays"
                + " closed until its blocks are checked.");
    }

    /**
     * The worlds are up: world rules, the §2.4 checks, and a verify of every live half (first, before
     * anything is built). The schedule starts {@code startup_delay_seconds} from now.
     */
    public void worldsReady() {
        if (!running) {
            return;
        }
        DailySettings st = host.settings();
        readyAt = host.now() + st.startupDelaySeconds() * 1000L;
        refresh();
        vetAll();
        if (st.worldRules()) {
            Set<String> done = new HashSet<>();
            for (SlotState s : slots.values()) {
                WorldPort port = s.wanted() && done.add(s.world.toLowerCase(Locale.ROOT)) ? host.world(s.world) : null;
                if (port != null) {
                    try {
                        port.worldRules(line -> host.logger().info("Fresh Courses: " + port.name() + ": " + line));
                    } catch (RuntimeException e) {
                        host.logger().log(Level.WARNING, "Fresh Courses: the world rules could not be set", e);
                    }
                }
            }
        }
        for (SlotState s : slots.values()) {
            if (s.classic && s.live == null && s.claimed) {
                s.bothDirty = true; // a recall or a clearing may have stopped halfway
            }
            if (s.live == null || !s.on()) {
                continue;
            }
            if (s.claimed) {
                s.oldDirty = true; // unknown after a restart: emptied once nothing else is due
                queue.add(new Job(Kind.HEAL, s, null));
            } else {
                unclaimedLive(s);
            }
        }
        keeper.worldsReady();
    }

    /**
     * A live row whose region isn't claimed (it was cleared, or it moved): nothing there is vouched
     * for, so it is never healed in place. It counts as a failed check, and the next build scans
     * the region (and claims it only when it is empty) before building anew.
     */
    private void unclaimedLive(SlotState s) {
        s.verified = false;
        s.healFailed = true;
        warnOnce(s, "Fresh Courses: " + s.def.id() + "'s live course isn't in its claimed region "
                + "- a new one will be built once the area is checked");
    }

    /** Stop: give up the running job (its tickets go), forget the queue. The blocks stay as they are. */
    public void stop() {
        running = false;
        if (job != null) {
            cancel(job, "Fresh Courses stopped");
        }
        keeper.stop();
        queue.clear();
        inbox.clear();
        areas = List.of();
        wetPlots = List.of();
        guardWorlds = List.of();
    }

    /** Whether it is running. */
    public boolean running() {
        return running;
    }

    // ---- the ticks ------------------------------------------------------------------------------

    /** Every tick: plans that came back, then a tick of the running job's building. */
    public void tick() {
        Runnable r;
        while ((r = inbox.poll()) != null) {
            r.run();
        }
        if (running && keeper.busy()) {
            keeper.tick();
            return;
        }
        if (!running || job == null) {
            return;
        }
        Job j = job;
        try {
            tickJob(j);
        } catch (RuntimeException e) {
            host.logger().log(Level.SEVERE, "Fresh Courses: building " + j.slot.def.id() + " failed", e);
            if (job == j) {
                fail(j, "it threw " + e);
            }
        }
    }

    /** Every second: settings and overrides, people out of the way, and the schedule. */
    public void check() {
        if (!running) {
            return;
        }
        refresh();
        online = host.anyoneOnline();
        if (readyAt < 0) {
            return;
        }
        long now = host.now();
        if (now - lastVet >= VET_EVERY_MS) {
            vetAll();
        }
        classicUpkeep(now);
        RestartHold hold = host.restartHold();
        if (GenScheduler.abandon(now, hold)) {
            if (job != null) {
                cancel(job, "a restart is less than 2 minutes away - it goes on after the restart");
            }
            keeper.cancel("a restart is less than 2 minutes away");
            return; // nothing starts this close to a restart
        }
        if (job != null) {
            evacuate(job);
            return;
        }
        if (keeper.busy()) {
            keeper.check();
            return;
        }
        // A plot job waits for every live course's boot check (they are shut until then), and none
        // starts this close to a restart (the ones nothing records are dropped, their admins told).
        if (keeper.hasWork() && !healDue()) {
            if (!GenScheduler.nearRestart(now, hold, host.settings().avoidBeforeRestartMinutes())) {
                keeper.begin();
                return;
            }
            keeper.holdForRestart("a restart is due at " + hold.clock(hold.next(now)));
        }
        Job next = queue.poll();
        if (next != null) {
            begin(next);
            return;
        }
        schedule(now, hold);
    }

    private void schedule(long now, RestartHold hold) {
        DailySettings st = host.settings();
        Edition ed = edition();
        SlotState clear = null;
        for (SlotState s : slots.values()) {
            // A live course nobody has checked this run (the slot was off or its world missing at
            // the boot check): check it now, before anything else; one outside a claimed region
            // can't be checked, so it is built anew.
            if (s.on() && s.live != null && !s.verified && !s.healFailed) {
                if (!s.claimed) {
                    unclaimedLive(s);
                    continue;
                }
                begin(new Job(Kind.HEAL, s, null));
                return;
            }
        }
        for (SlotState s : slots.values()) {
            if (s.classic) {
                continue; // built only when an admin recalls something (below)
            }
            if (!s.on()) {
                s.waiting = null;
                continue;
            }
            warnIgnoredPin(s);
            GenScheduler.Decision d = GenScheduler.decide(view(s), now, readyAt, st, hold, ed);
            s.waiting = d.kind() == GenScheduler.Kind.WAIT ? d.reason() : null;
            switch (d.kind()) {
                case BUILD -> {
                    String edition = Edition.editionKey(d.cadence(), d.day(), 0);
                    if (!queueEdition.equals(edition)) {
                        queueEdition = edition;
                        host.logger().info("Fresh Courses: building the courses for "
                                + editionName(d.cadence(), d.day()) + " (" + edition + ").");
                    }
                    Job j = new Job(Kind.BUILD, s, null);
                    j.day = d.day();
                    j.cadence = d.cadence();
                    j.reroll = d.reroll();
                    j.seed = d.seed();
                    j.mix = s.mix;
                    begin(j);
                    return;
                }
                case RESTAMP -> restamp(s, d.cadence(), d.day());
                case CLEAR_OLD -> {
                    if (clear == null && s.preview == null && now - s.lastClearCheck >= CLEAR_EVERY_MS) {
                        s.lastClearCheck = now;
                        List<Person> people = host.people();
                        // A runner on the standing previous layout may be off both halves for a
                        // while (a glider): anyone playing the slot off the live half is waited for.
                        boolean stray = s.previous != null && !s.clearing && s.live != null
                                && Evacuator.strayRunner(people, s.world, s.def.id(), s.half(s.live.half()));
                        if (!stray && !Evacuator.anyone(people, s.world, s.half(s.idleHalf()))) {
                            clear = s;
                        }
                    }
                }
                case WAIT -> {
                    if (secret == null && d.reason().contains("secret") && now - lastSecretWarn >= SECRET_WARN_MS) {
                        lastSecretWarn = now;
                        host.logger().warning("Fresh Courses: the seed secret can't be read from the database - "
                                + "every course keeps its current layout until it can");
                    }
                }
                default -> {
                    // nothing to do
                }
            }
        }
        for (SlotState s : slots.values()) {
            if (!s.classic || !s.on()) {
                continue;
            }
            ClassicWant w = s.want;
            if (w != null && (!holds(s, w) || s.healFailed)) {
                s.waiting = recallWait(s, now, hold);
                if (s.waiting == null) {
                    Job j = new Job(Kind.RECALL, s, s.recallReport);
                    j.want = w;
                    begin(j);
                    return;
                }
                continue;
            }
            s.waiting = null;
            if (clear == null && now - s.lastClearCheck >= CLEAR_EVERY_MS && (s.bothDirty || s.oldDirty)) {
                s.lastClearCheck = now;
                List<Person> people = host.people();
                // As for CLEAR_OLD above: a runner on the standing previous layout may be off both
                // halves for a while (a glider), so anyone playing the slot off the live half (any
                // of them, once the slot is closed) is waited for.
                boolean stray = s.previous != null && !s.clearing && Evacuator.strayRunner(people, s.world,
                        s.def.id(), s.live == null ? null : s.half(s.live.half()));
                if (s.live == null && s.bothDirty) {
                    if (!stray && !Evacuator.anyone(people, s.world, s.half('A'))
                            && !Evacuator.anyone(people, s.world, s.half('B'))) {
                        begin(new Job(Kind.UNRECALL, s, null));
                        return;
                    }
                } else if (s.live != null && s.oldDirty && !stray
                        && !Evacuator.anyone(people, s.world, s.half(s.idleHalf()))) {
                    clear = s;
                }
            }
        }
        if (clear != null) {
            begin(new Job(Kind.CLEAR_OLD, clear, null));
        }
    }

    /**
     * The slot's pin if it applies to the edition it should show now (not expired, made for the
     * planner running now), else {@code null}.
     */
    private GenScheduler.Pin activePin(SlotState s) {
        Planner p = planners.get(s.def.generator());
        return s.pin != null && p != null && s.pin.appliesOn(target(s).start(), p.algo()) ? s.pin : null;
    }

    /** Why a stored pin is not used, or {@code null} when there is none or it applies. */
    private String pinIgnored(SlotState s) {
        if (s.pin == null || activePin(s) != null) {
            return null;
        }
        Planner p = planners.get(s.def.generator());
        if (p != null && s.pin.algo() != p.algo()) {
            return "it was made for " + s.def.generator() + " planner v" + s.pin.algo() + " and this is v" + p.algo();
        }
        return "it ended on " + date(s.pin.until());
    }

    /**
     * §4.0: a pin made for another planner version is logged (once a day) and the edition's own
     * seed is used; a pin whose days are over is forgotten, with one line.
     */
    private void warnIgnoredPin(SlotState s) {
        String why = pinIgnored(s);
        Planner p = planners.get(s.def.generator());
        if (why != null && p != null && s.pin.algo() == p.algo()) {
            try {
                host.store().meta(GenAdminKeys.pin(s.def.id()), null);
                host.logger().info("Fresh Courses: " + s.def.id() + "'s pinned seed " + GenSeed.hex(s.pin.seed())
                        + " ended on " + date(s.pin.until()) + "; each set's own seed is used from now on.");
                s.pin = null;
                return;
            } catch (SQLException e) {
                // kept (and logged below) until the database takes the change
            }
        }
        if (why != null) {
            GenScheduler.Target t = target(s);
            warnOnce(s, "Fresh Courses: " + s.def.id() + "'s pinned seed " + GenSeed.hex(s.pin.seed())
                    + " is not used for " + editionName(t.cadence(), t.start()) + " - " + why + "; the set's own seed"
                    + " is used (/hcm games gen unpin " + s.def.id() + " forgets it)");
        }
    }

    /**
     * Whether a live course still waits for its check: a HEAL queued (every boot queues one per
     * live half), or one {@link #schedule} would begin first. Those go before any plot job.
     */
    private boolean healDue() {
        for (Job q : queue) {
            if (q.kind == Kind.HEAL) {
                return true;
            }
        }
        for (SlotState s : slots.values()) {
            if (s.on() && s.live != null && !s.verified && !s.healFailed && s.claimed) {
                return true;
            }
        }
        return false;
    }

    /** Why a due recall can't start yet, or {@code null} when it can (the restart hold, the day's tries). */
    private String recallWait(SlotState s, long now, RestartHold hold) {
        DailySettings st = host.settings();
        if (GenScheduler.nearRestart(now, hold, st.avoidBeforeRestartMinutes())) {
            return "a restart is coming at " + hold.clock(hold.next(now));
        }
        long day = edition().day(now);
        if (s.triesOn(day) >= st.maxTriesPerDay()) {
            return "gave up until tomorrow after " + s.tries + " tries";
        }
        if (s.triesOn(day) > 0 && now < s.lastTryAt + st.retryMinutes() * 60_000L) {
            return "next try at " + clock(s.lastTryAt + st.retryMinutes() * 60_000L);
        }
        return null;
    }

    /** Whether a Classics slot's live layout is the recall {@code w} (the same request, not only the same course). */
    static boolean holds(SlotState s, ClassicWant w) {
        GenTag live = s.live;
        return live != null && w != null && live.recall() != null && live.slot().equals(w.slot())
                && live.editionKey().equals(w.edition()) && live.recall().from() == w.from();
    }

    /**
     * Every check, whatever else runs: a recall whose time is up is closed, and a Classics slot whose
     * recall is gone (unrecalled) closes: its row goes, the layout stands until its half is cleared
     * (runs on it finish there), and both halves are cleared once nobody is on them.
     */
    private void classicUpkeep(long now) {
        for (SlotState s : slots.values()) {
            if (!s.classic) {
                continue;
            }
            if (s.want != null && s.want.expired(now)) {
                ClassicWant gone = s.want;
                try {
                    host.store().meta(GenAdminKeys.recall(s.def.id()), null);
                } catch (SQLException e) {
                    host.logger().log(Level.WARNING, "Fresh Courses: could not end " + s.def.id() + "'s recall", e);
                    continue;
                }
                s.want = null;
                host.logger().info("Fresh Courses: " + s.def.id() + "'s recall of " + gone.slot() + " "
                        + gone.edition() + " is over.");
                if (s.live == null) {
                    forgetRecall(s, "its recall is over");
                }
            }
            if (s.want == null && s.live != null) {
                closeClassic(s, "its recall is over");
            }
        }
    }

    /**
     * A Classics slot that holds nothing live lost its recall (unrecalled, or its time is up): a
     * recall still being built stops, and — since it may have set blocks, or failed after some —
     * both halves are cleared once nobody is on them, like a closed one's.
     */
    private void forgetRecall(SlotState s, String why) {
        if (job != null && job.slot == s && job.kind == Kind.RECALL) {
            cancel(job, why);
        }
        queue.removeIf(j -> j.slot == s && j.kind == Kind.RECALL);
        s.prior = null;
        if (s.claimed) {
            s.bothDirty = true;
            s.lastClearCheck = 0;
        }
    }

    /** A Classics slot closes: its row goes; runs on it finish there; both halves are cleared once empty. */
    private void closeClassic(SlotState s, String why) {
        if (job != null && job.slot == s) {
            cancel(job, why);
        }
        queue.removeIf(j -> j.slot == s);
        try {
            host.store().closeCourse(s.def.id(), Map.of());
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not close " + s.def.id(), e);
            return;
        }
        GenTag was = s.live;
        s.previous = s.verified && was != null ? was : null;
        s.clearing = false;
        s.live = null;
        s.verified = false;
        s.healFailed = false;
        s.oldDirty = false;
        s.bothDirty = true;
        s.lastClearCheck = 0;
        host.coursesChanged(s.def.game());
        host.logger().info("Fresh Courses: " + s.def.id() + " is closed (" + why + "); its halves are cleared once"
                + " nobody is on them.");
    }

    private GenScheduler.SlotView view(SlotState s) {
        Planner p = planners.get(s.def.generator());
        return new GenScheduler.SlotView(s.def.id(), s.on() && p != null, job != null, s.live, !s.healFailed,
                s.liveMix, s.mix, s.reroll, s.pin, p == null ? 0 : p.algo(), s.triesDay, s.tries, s.lastTryAt,
                s.oldDirty, secret(), scheduleSince);
    }

    /** The edition a slot should show now ({@link GenScheduler#target}). */
    private GenScheduler.Target target(SlotState s) {
        return GenScheduler.target(s.live, host.now(), edition(), scheduleSince);
    }

    /** The seed secret (kept once read), or {@code null} while the database can't give it. */
    private Long secret() {
        if (secret == null) {
            try {
                secret = host.store().secret();
            } catch (SQLException | RuntimeException e) {
                return null;
            }
        }
        return secret;
    }

    // ---- settings, overrides and the §2.4 checks -------------------------------------------------

    /** Read config and the admin's overrides into every slot; notice a region that moved. */
    private void refresh() {
        DailySettings st = host.settings();
        Map<String, String> meta;
        try {
            meta = host.store().metaLike("gen.");
        } catch (SQLException | RuntimeException e) {
            meta = null;
        }
        String world = st.world().isBlank() ? first(host.gamesWorlds()) : st.world();
        noticeSchedule(meta);
        List<Object[]> kept = new ArrayList<>();
        if (meta != null) {
            wetPlots = List.copyOf(wetPlots(meta));
        }
        for (SlotState s : slots.values()) {
            DailySettings.SlotConfig c = s.classic ? st.archive().classic(s.def.id()) : st.slot(s.def.id());
            if (c == null) {
                continue;
            }
            int[] origin = c.origin();
            if (!s.sameRegion(world, origin) && (s.claimed || s.live != null) && !s.world.isBlank()) {
                moved(s, world, origin);
            }
            s.world = world;
            s.origin = origin;
            s.configOn = c.enabled();
            if (s.classic) {
                s.mix = s.def.tierOrMix();
                if (meta != null) {
                    s.want = ClassicWant.parse(meta.get(GenAdminKeys.recall(s.def.id())));
                    s.claimed = Regions.claim(s.def, world, origin).equals(meta.get(GenAdminKeys.claim(s.def.id())));
                }
                wetRegions(s, meta, world, origin, kept);
                if (s.wanted() || s.claimed) {
                    for (char h : new char[]{'A', 'B'}) {
                        kept.add(new Object[]{world, s.half(h)});
                    }
                }
                continue;
            }
            if (meta != null) {
                String id = s.def.id();
                s.override = GenAdminKeys.bool(meta.get(GenAdminKeys.enabled(id)));
                String tier = meta.get(GenAdminKeys.tier(id));
                s.mix = tier != null && s.def.tierProblem(tier) == null ? s.def.normalise(tier) : c.tierOrMix();
                s.pin = GenScheduler.Pin.parse(meta.get(GenAdminKeys.pin(id)));
                s.reroll = GenAdminKeys.whole(meta.get(GenAdminKeys.reroll(id, target(s).key())));
                String claim = meta.get(GenAdminKeys.claim(id));
                boolean claimed = Regions.claim(s.def, world, origin).equals(claim);
                if (claim != null && !claimed) {
                    int[] old = Regions.claimOrigin(claim);
                    String where = old == null ? claim : Regions.describe(s.def, old);
                    warnOnce(s, "Fresh Courses: " + id + " was claimed at another place (" + where + "). "
                            + (s.def.dropper() ? drainFirst(s) : "Those blocks are left as they are: clear them by"
                            + " hand.") + " The new region is checked before it is used.");
                }
                s.claimed = claimed;
            }
            wetRegions(s, meta, world, origin, kept);
            if (s.wanted() || s.claimed) {
                for (char h : new char[]{'A', 'B'}) {
                    kept.add(new Object[]{world, s.half(h)});
                }
            }
        }
        areas = List.copyOf(kept);
        List<String> worlds = new ArrayList<>();
        for (List<Object[]> boxes : List.of(areas, wetPlots)) {
            for (Object[] a : boxes) {
                String w = (String) a[0];
                if (worlds.stream().noneMatch(w::equalsIgnoreCase)) {
                    worlds.add(w);
                }
            }
        }
        guardWorlds = List.copyOf(worlds);
    }

    /**
     * A Dropper slot's old regions that may still hold its pools though it stands elsewhere now
     * ({@link GenAdminKeys#wet}), added to the guarded {@code kept}: the claim it held when its
     * origin (or the world) moved is remembered the first time it is seen, before a claim at the new
     * place can overwrite it. Each stays guarded (nothing changes there, and nothing flows out) until
     * the slot is claimed there again, which puts it under the claim's guard and lets a {@code clear}
     * drain it (a CLEAR empties the pools before anything else). Any other slot: nothing.
     */
    private void wetRegions(SlotState s, Map<String, String> meta, String world, int[] origin, List<Object[]> kept) {
        if (!s.def.dropper()) {
            return;
        }
        if (meta != null) {
            String key = GenAdminKeys.wet(s.def.id());
            List<String> stored = Regions.wetClaims(meta.get(key));
            List<String> now = new ArrayList<>(stored);
            String here = Regions.claim(s.def, world, origin);
            String claim = meta.get(GenAdminKeys.claim(s.def.id()));
            if (claim != null && !claim.equals(here) && Regions.claimOrigin(claim) != null && !now.contains(claim)) {
                now.add(claim);
            }
            if (s.claimed) {
                now.remove(here); // claimed here again: the claim guards it, and a clear drains it
            }
            if (!now.equals(stored)) {
                try {
                    host.store().meta(key, Regions.wetText(now));
                } catch (SQLException | RuntimeException e) {
                    host.logger().log(Level.WARNING, "Fresh Courses: could not record " + s.def.id()
                            + "'s old region", e);
                }
            }
            s.wet = List.copyOf(now);
        }
        for (String c : s.wet) {
            String w = Regions.claimWorld(c);
            int[] o = Regions.claimOrigin(c);
            if (w != null && o != null) {
                for (Box h : Regions.halves(s.def, o)) {
                    kept.add(new Object[]{w, h});
                }
            }
        }
    }

    /**
     * What a moved Dropper's admin reads: its pools may still stand in the old place, which stays
     * guarded until they are drained there.
     */
    private static String drainFirst(SlotState s) {
        return "Its pools may still be there, so that area stays guarded: drain first - move it back and use"
                + " /hcm games gen clear " + s.def.id() + " (it empties the pools before anything else).";
    }

    /**
     * The flow-only boxes {world, {@link Box}} {@code meta} names: every plot holding a kept Dropper,
     * and a plot job the server stopped halfway ({@link KeepService#wetPending}). The keep area is
     * hand-built ground the guard leaves alone, but a Dropper's water must never flow out of its plot.
     */
    static List<Object[]> wetPlots(Map<String, String> meta) {
        List<Object[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (!e.getKey().startsWith(GenAdminKeys.PLOTS)) {
                continue;
            }
            KeptPlot p = KeptPlot.parse(GenAdminKeys.plotOf(e.getKey()), e.getValue());
            if (p != null && KeepService.dropper(p.slot())) {
                out.add(new Object[]{p.world(), p.box()});
            }
        }
        Object[] pending = KeepService.wetPending(meta.get(GenAdminKeys.KEEP_PENDING));
        if (pending != null) {
            out.add(pending);
        }
        return out;
    }

    /**
     * The schedule ({@code cadence} and the resolved {@code rebuild_day}) as the engine sees it now,
     * and when it was first seen: read from {@code gen.cadence} when it matches, else recorded now.
     * That moment is how long a layout of the old schedule is kept ({@link GenScheduler#target}).
     */
    private void noticeSchedule(Map<String, String> meta) {
        Edition ed = edition();
        String sig = ed.cadenceDays() + "|" + ed.rebuildDay();
        if (sig.equals(scheduleSig)) {
            return;
        }
        long now = host.now();
        long since = now;
        boolean announce = false;
        String stored = meta == null ? null : meta.get(GenAdminKeys.schedule());
        int bar = stored == null ? -1 : stored.lastIndexOf('|');
        String was = bar > 0 ? stored.substring(0, bar) : null;
        if (sig.equals(was)) {
            try {
                since = Long.parseLong(stored.substring(bar + 1));
            } catch (NumberFormatException e) {
                since = now;
            }
        } else if (meta != null) {
            try {
                host.store().meta(GenAdminKeys.schedule(), sig + "|" + now);
            } catch (SQLException | RuntimeException e) {
                host.logger().log(Level.WARNING, "Fresh Courses: could not record the new schedule", e);
            }
            announce = was != null;
        }
        scheduleSig = sig;
        scheduleSince = since;
        if (announce) {
            // When the courses really change: the new schedule's first start, their own end if sooner,
            // or a week on when that start would carry the live key again (GenScheduler#target).
            long next = nextChangeAt();
            host.logger().info("Fresh Courses: the courses now change " + GenCopy.cadenceName(ed.cadenceDays())
                    + ". The ones up now stay until " + GenCopy.whenDated(next > 0 ? next : ed.nextChangeAt(now),
                    host.zone()) + ".");
        }
    }

    /** A slot's region moved (config): nothing at the old place is vouched for or cleared. */
    private void moved(SlotState s, String world, int[] origin) {
        if (job != null && job.slot == s) {
            cancel(job, "its region moved");
        }
        queue.removeIf(j -> j.slot == s);
        host.logger().warning("Fresh Courses: " + s.def.id() + " moved from " + s.world + " "
                + Regions.describe(s.def, s.origin) + " to " + world + " " + Regions.describe(s.def, origin)
                + ". The old halves were not cleared (use /hcm games gen clear before moving a course)."
                + (s.def.dropper() ? " " + drainFirst(s) : ""));
        s.verified = false;
        s.healFailed = s.live != null;
        s.previous = null;
        s.clearing = false;
        s.oldDirty = false;
        s.preview = null;
        s.claimed = false;
    }

    private void vetAll() {
        lastVet = host.now();
        List<Regions.Area> built = handBuilt();
        for (SlotState s : slots.values()) {
            vet(s, built);
        }
    }

    /** §2.4 at start and before a build: sets (and logs once) the slot's problem, or clears it. */
    private String vet(SlotState s, List<Regions.Area> built) {
        String why = null;
        if (s.wanted()) {
            why = vetProblem(s, built);
        }
        if (why == null && s.problem != null && s.problem.startsWith(FOREIGN)) {
            why = s.problem; // foreign blocks stay until a claim clears them
        }
        if (why != null && !why.equals(s.problem)) {
            host.logger().severe("Fresh Courses: " + s.def.id() + " is off: " + why);
        }
        s.problem = why;
        return why;
    }

    private static final String FOREIGN = "Region has ";

    private String vetProblem(SlotState s, List<Regions.Area> built) {
        if (planners.get(s.def.generator()) == null) {
            return "no planner for " + s.def.generator();
        }
        if (s.world.isBlank()) {
            return "no world: games.fresh.world is empty and games.worlds lists none";
        }
        String tier = s.def.tierProblem(s.mix);
        if (tier != null) {
            return tier;
        }
        WorldPort port = host.world(s.world);
        if (port == null) {
            return "the world " + s.world + " isn't loaded";
        }
        List<String> world = Regions.worldProblems(s.def, s.origin, facts(port));
        if (!world.isEmpty()) {
            return world.get(0);
        }
        List<DailySettings.SlotConfig> others = new ArrayList<>();
        for (SlotState o : slots.values()) {
            if (o != s && (o.wanted() || o.claimed)) {
                DailySettings.SlotConfig oc = DailySettings.SlotConfig.shipped(o.def).withOrigin(o.origin);
                others.add(oc);
            }
        }
        String apart = Regions.apartProblem(DailySettings.SlotConfig.shipped(s.def).withOrigin(s.origin), others);
        if (apart != null) {
            return apart;
        }
        if (built != null) {
            String near = Regions.handBuiltProblem(s.def, s.origin, s.world, built);
            if (near != null) {
                return near;
            }
        }
        try {
            return Regions.takenByHand(s.def, host.store().course(s.def.id()));
        } catch (SQLException e) {
            return null; // the database will say so at the flip
        }
    }

    private Regions.WorldFacts facts(WorldPort port) {
        boolean listed = false;
        for (String w : host.gamesWorlds()) {
            listed |= w != null && w.equalsIgnoreCase(port.name());
        }
        return new Regions.WorldFacts(port.name(), listed, port.minHeight(), port.maxHeight(), port.border(),
                port.spawn(), host.settings().safeSpot());
    }

    /** Every hand-built course's footprint (null when the rows can't be read). */
    private List<Regions.Area> handBuilt() {
        try {
            List<GamesDao.CourseRow> rows = new ArrayList<>(host.store().courses(Slots.GAME_TRIALS));
            rows.addAll(host.store().courses(Slots.GAME_GOLF));
            return Regions.handBuilt(rows);
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    /** The live row of a slot: its tag, rev and the mix it was made with. */
    private void readRow(SlotState s) {
        s.live = null;
        s.verified = false;
        try {
            GamesDao.CourseRow row = host.store().course(s.def.id());
            if (row == null || !s.def.game().equals(row.game())) {
                return;
            }
            GenTag tag = tagOf(s.def, row);
            if (tag == null || !s.def.id().equals(tag.holder())) {
                return;
            }
            s.live = tag;
            s.rev = row.rev();
            String mix = host.store().meta(GenAdminKeys.mix(s.def.id()));
            s.liveMix = mixFor(tag, mix, s.mix);
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not read " + s.def.id() + "'s course", e);
        }
    }

    /** The tag of a slot's row, or {@code null} for none (or unreadable). */
    static GenTag tagOf(Slots.Def def, GamesDao.CourseRow row) {
        if (def.golf()) {
            return com.dierks.homecraft.games.golf.CourseCodec.readGen(row.data());
        }
        Course c = CourseCodec.decode(row.id(), row.data()).course();
        return c == null ? null : c.gen();
    }

    /** The mix a layout was made with: kept as "plan:mix" at its flip; else the slot's current one. */
    static String mixFor(GenTag tag, String stored, String fallback) {
        if (stored != null) {
            int colon = stored.indexOf(':');
            if (colon > 0 && stored.substring(0, colon).equals(tag.planHash())) {
                return stored.substring(colon + 1);
            }
        }
        return fallback;
    }

    // ---- jobs -------------------------------------------------------------------------------------

    private void begin(Job j) {
        job = j;
        SlotState s = j.slot;
        try {
            switch (j.kind) {
                case HEAL -> beginHeal(j);
                case BUILD, PREVIEW -> beginBuild(j);
                case PROMOTE -> {
                    if (s.preview == null) {
                        end(j);
                        j.report.accept("&cThe preview of " + s.def.name() + " is gone; make a new one.");
                        return;
                    }
                    j.half = s.preview.half();
                    j.plan = s.preview.plan();
                    j.steps.add(new Step(j.half, j.plan, BuildJob.Mode.CONVERGE));
                    j.stage = Stage.CONVERGE;
                }
                case CLEAR_OLD -> {
                    s.clearing = true; // the previous layout stops standing now
                    j.steps.add(new Step(s.idleHalf(), null, BuildJob.Mode.CONVERGE));
                    j.stage = Stage.CONVERGE;
                }
                case SCAN -> {
                    if (claimRefused(j)) {
                        return;
                    }
                    j.steps.add(new Step('A', null, BuildJob.Mode.SCAN));
                    j.steps.add(new Step('B', null, BuildJob.Mode.SCAN));
                    j.stage = Stage.SCANNING;
                }
                case CLAIM, DECOMMISSION -> {
                    if (j.kind == Kind.CLAIM && claimRefused(j)) {
                        return;
                    }
                    j.steps.add(new Step('A', null, BuildJob.Mode.CONVERGE));
                    j.steps.add(new Step('B', null, BuildJob.Mode.CONVERGE));
                    j.stage = Stage.EVACUATE;
                    j.evacStart = host.now();
                }
                case RECALL -> beginRecall(j);
                case UNRECALL -> {
                    s.clearing = true; // the closed layout stops standing now
                    j.steps.add(new Step('A', null, BuildJob.Mode.CONVERGE));
                    j.steps.add(new Step('B', null, BuildJob.Mode.CONVERGE));
                    j.stage = Stage.CONVERGE;
                }
            }
        } catch (RuntimeException e) {
            fail(j, "it couldn't start (" + e + ")");
        }
    }

    /** A claim job whose region fails §2.4 now ends at once, having touched nothing. */
    private boolean claimRefused(Job j) {
        String why = claimProblem(j.slot);
        if (why == null) {
            return false;
        }
        end(j);
        j.report.accept("&c" + j.slot.def.name() + "'s area can't be claimed: &7" + why);
        return true;
    }

    /**
     * Why a slot's region may not be scanned or cleared for a claim, or {@code null}: the checks
     * of §2.4 (the world, the spawn and safe spot, other slots, hand-built courses), which a claim
     * must pass like a build. {@code claim confirm} clears only blocks nobody else has a course on.
     */
    private String claimProblem(SlotState s) {
        List<Regions.Area> built = handBuilt();
        if (built == null) {
            return "the courses can't be read from the database, so the area can't be checked";
        }
        return vetProblem(s, built);
    }

    private void beginHeal(Job j) {
        SlotState s = j.slot;
        String why = vet(s, handBuilt());
        if (why != null || s.live == null) {
            end(j);
            j.report.accept("&c" + s.def.name() + " can't be checked: &7" + (why == null ? "it has no course" : why));
            return;
        }
        if (!s.claimed) {
            // Never converge a region Fresh Courses doesn't hold: the next build scans it first.
            end(j);
            unclaimedLive(s);
            j.report.accept("&e" + s.def.name() + "'s area isn't claimed, so it isn't healed in place. &7A new"
                    + " course is built there once the area is checked and found empty.");
            return;
        }
        j.tag = s.live;
        j.half = s.live.half();
        j.day = s.live.day();
        j.cadence = s.live.cadence();
        j.reroll = s.live.reroll();
        j.seed = s.live.seed();
        j.mix = s.liveMix;
        if (s.classic) {
            healClassic(j);
            return;
        }
        Planner p = planners.get(s.def.generator());
        if (p.algo() != j.tag.algo()) {
            // An older planner made it: it can't be derived again, so it gets the quick check.
            j.steps.add(new Step(j.half, null, BuildJob.Mode.SCAN));
            j.stage = Stage.CONVERGE;
            return;
        }
        plan(j, p);
    }

    private void beginBuild(Job j) {
        SlotState s = j.slot;
        String why = vet(s, handBuilt());
        if (why != null) {
            end(j);
            j.report.accept("&c" + s.def.name() + " can't be built: &7" + why);
            return;
        }
        j.half = s.idleHalf();
        if (!s.claimed) {
            j.steps.add(new Step('A', null, BuildJob.Mode.SCAN));
            j.steps.add(new Step('B', null, BuildJob.Mode.SCAN));
            j.stage = Stage.SCANNING;
            return;
        }
        plan(j, planners.get(s.def.generator()));
    }

    /** Hand the plan to the planner thread; the result comes back through the inbox. */
    private void plan(Job j, Planner p) {
        SlotState s = j.slot;
        Box half = j.planBox != null ? j.planBox : s.half(j.half);
        Slots.Def def = j.planDef != null ? j.planDef : s.def;
        PlanInput in = new PlanInput(def, half, j.half, j.day, j.reroll, j.seed, j.mix, host.fallDepth(),
                WORK.getOrDefault(def.generator(), 200_000L), cancelled(j.cancelled));
        j.stage = Stage.PLANNING;
        j.planStarted = host.now();
        boolean heal = j.kind == Kind.HEAL;
        GenTag tag = j.tag;
        host.planner().execute(() -> {
            long t0 = System.nanoTime();
            Plan made = null;
            List<String> refused = List.of();
            Throwable error = null;
            try {
                made = heal ? p.rederive(in, tag) : p.plan(in);
                refused = PlanCheck.generator(p, made, in, heal); // §3.3 step 2, with today's settings
            } catch (Throwable e) {
                error = e;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            Plan result = made;
            List<String> checked = refused;
            Throwable failure = error;
            inbox.add(() -> planned(j, result, checked, failure, ms));
        });
    }

    /**
     * The planner's "stop now" check, which also gives the server room: while anyone is online the
     * planner sleeps 2 ms after every 10 ms of work (§3.3 step 1). A keep's re-made plan uses it too.
     */
    BooleanSupplier cancelled(AtomicBoolean flag) {
        return new BooleanSupplier() {
            private long mark = System.nanoTime();

            @Override
            public boolean getAsBoolean() {
                if (flag.get() || Thread.currentThread().isInterrupted()) {
                    return true;
                }
                if (online && System.nanoTime() - mark >= 10_000_000L) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return true;
                    }
                    mark = System.nanoTime();
                }
                return flag.get();
            }
        };
    }

    private void planned(Job j, Plan plan, List<String> refused, Throwable error, long ms) {
        if (job != j || j.stage != Stage.PLANNING) {
            return; // cancelled, killed or superseded: dropped
        }
        j.planMs = ms;
        SlotState s = j.slot;
        if (error != null) {
            if (!(error instanceof GenFailed)) {
                host.logger().log(Level.SEVERE, "Fresh Courses: the " + s.def.generator() + " planner threw", error);
            }
            fail(j, error instanceof GenFailed ? String.valueOf(error.getMessage()) : "the planner threw " + error);
            return;
        }
        List<String> problems = new ArrayList<>(PlanCheck.problems(plan, j.planDef != null ? j.planDef : s.def,
                j.planBox != null ? j.planBox : s.half(j.half)));
        problems.addAll(refused);
        if (!problems.isEmpty()) {
            fail(j, "its plan was refused: " + String.join("; ", problems));
            return;
        }
        if (j.kind == Kind.HEAL && !plan.hash().equals(j.tag.planHash())) {
            fail(j, "its layout can't be made again (the plan came out different)");
            return;
        }
        j.plan = plan;
        j.steps.add(new Step(j.half, plan, BuildJob.Mode.CONVERGE));
        if (j.kind == Kind.HEAL) {
            j.stage = Stage.CONVERGE;
        } else {
            j.stage = Stage.EVACUATE;
            j.evacStart = host.now();
            evacuate(j);
        }
    }

    private void tickJob(Job j) {
        long now = host.now();
        if (j.stage == Stage.PLANNING) {
            if (now - j.planStarted > PLAN_KILL_MS) {
                j.cancelled.set(true);
                if (host.planner() instanceof PlannerThread t) {
                    t.restart();
                }
                fail(j, "planning took longer than " + PLAN_KILL_MS / 1000 + " seconds");
            }
            return;
        }
        if (j.stage != Stage.CONVERGE && j.stage != Stage.SCANNING) {
            return;
        }
        SlotState s = j.slot;
        WorldPort port = host.world(s.world);
        if (port == null) {
            fail(j, "the world " + s.world + " isn't loaded");
            return;
        }
        if (j.build == null) {
            if (j.stepIndex >= j.steps.size()) {
                stepsDone(j);
                return;
            }
            Step st = j.steps.get(j.stepIndex);
            try {
                j.build = new BuildJob(port, s.half(st.which()), st.plan(), st.mode());
            } catch (IllegalArgumentException e) {
                fail(j, e.getMessage());
                return;
            }
        }
        DailySettings.Budget cfg = host.settings().budget();
        if (!j.budget.begin(cfg, online, host.mspt())) {
            if (!j.pauseWarned) {
                j.pauseWarned = true;
                host.logger().warning("Fresh Courses: building " + s.def.id() + " paused - the server's ticks are"
                        + " slow (over " + cfg.pauseAboveMspt() + " ms); it goes on below "
                        + (int) cfg.resumeBelowMspt() + " ms");
            }
            return;
        }
        List<Person> here = new ArrayList<>();
        for (Person p : host.people()) {
            if (p.world() != null && p.world().equalsIgnoreCase(s.world)) {
                here.add(p);
            }
        }
        try {
            j.build.tick(j.budget, cfg.chunkLoadsInFlight(), here, now);
        } finally {
            j.budget.end();
        }
        for (UUID stuck : j.build.stuckPeople()) {
            Person p = find(here, stuck);
            if (p != null && !(j.kind == Kind.HEAL && p.playing(s.def.id()))) {
                moveToSafety(s, p);
            }
        }
        if (j.build.deferring() && !j.deferWarned) {
            j.deferWarned = true;
            host.logger().warning("Fresh Courses: building " + s.def.id() + " is waiting for someone to step away");
        }
        if (j.build.failed()) {
            String why = j.build.error();
            fail(j, why);
            return;
        }
        if (j.build.done()) {
            j.writes += j.build.writes();
            if (j.steps.get(j.stepIndex).mode() == BuildJob.Mode.CONVERGE) {
                j.chunks += j.build.chunkCount();
            } else {
                j.found += j.build.found();
                if (j.firstFound.isEmpty()) {
                    j.firstFound = j.build.firstFound();
                }
            }
            j.stepIndex++;
            if (j.stepIndex < j.steps.size()) {
                j.build.release();
                j.build = null;
            } else {
                stepsDone(j);
            }
        }
    }

    /** Every step of the job is done; the last one's chunks are still loaded (for the golf replay). */
    private void stepsDone(Job j) {
        SlotState s = j.slot;
        if (j.stage == Stage.SCANNING) {
            scanned(j);
            return;
        }
        switch (j.kind) {
            case HEAL -> healed(j);
            case BUILD, PROMOTE -> {
                if (proven(j)) {
                    flip(j);
                }
            }
            case RECALL -> {
                if (proven(j)) {
                    flipRecall(j);
                }
            }
            case UNRECALL -> {
                s.previous = null;
                s.clearing = false;
                s.bothDirty = false;
                s.oldDirty = false;
                end(j);
                host.logger().info("Fresh Courses: " + s.def.id() + "'s halves are empty (" + j.writes
                        + " blocks cleared).");
            }
            case PREVIEW -> {
                if (proven(j)) {
                    s.preview = new SlotState.Preview(j.half, j.plan, j.day, j.seed, j.mix, j.cadence);
                    s.oldDirty = true;
                    end(j);
                    host.logger().info("Fresh Courses: a preview of " + s.def.id() + " (seed " + GenSeed.hex(j.seed)
                            + ") stands in half " + j.half + ".");
                    j.report.accept("&aThe preview of " + s.def.name() + " is ready in half " + j.half
                            + ". &7Walk it: &e/hcm games gen tp " + s.def.id() + " idle&7; make it the current course:"
                            + " &e/hcm games gen promote " + s.def.id());
                }
            }
            case CLEAR_OLD -> {
                s.oldDirty = false;
                s.previous = null;
                s.clearing = false;
                end(j);
                host.logger().info("Fresh Courses: " + s.def.id() + "'s old half " + j.steps.get(0).which()
                        + " is empty (" + j.writes + " blocks cleared).");
            }
            case CLAIM -> {
                end(j);
                claimed(s);
                j.report.accept("&a" + s.def.name() + "'s area is cleared and claimed (" + j.writes
                        + " blocks cleared). &7It builds at the next check.");
            }
            case DECOMMISSION -> {
                end(j);
                try {
                    host.store().meta(GenAdminKeys.claim(s.def.id()), null);
                } catch (SQLException e) {
                    host.logger().log(Level.WARNING, "Fresh Courses: could not forget " + s.def.id() + "'s claim", e);
                }
                s.claimed = false;
                s.oldDirty = false;
                s.previous = null;
                s.preview = null;
                s.verified = false;
                s.healFailed = s.live != null; // its blocks are gone: switched on again, it builds anew
                host.logger().info("Fresh Courses: " + s.def.id() + " is cleared (" + j.writes + " blocks) and off.");
                j.report.accept("&a" + s.def.name() + " is cleared and off. &7Move it now if you like, then &e/hcm"
                        + " games gen on " + s.def.id());
            }
            default -> end(j);
        }
    }

    /** A claim scan finished: an empty region is claimed; anything else stays refused. */
    private void scanned(Job j) {
        SlotState s = j.slot;
        if (j.found == 0) {
            claimed(s);
            if (j.kind == Kind.SCAN) {
                end(j);
                j.report.accept("&a" + s.def.name() + "'s area is empty and now claimed.");
                return;
            }
            j.build.release();
            j.build = null;
            j.steps.clear();
            j.stepIndex = 0;
            if (j.kind == Kind.RECALL) {
                recallPlan(j);
            } else {
                plan(j, planners.get(s.def.generator()));
            }
            return;
        }
        end(j);
        String first = j.firstFound.isEmpty() ? "?" : j.firstFound.get(0);
        String why = FOREIGN + String.format(Locale.ROOT, "%,d", j.found) + (j.found == 1 ? " block that isn't"
                : " blocks that aren't") + " Fresh Courses' (first at " + first + ") - /hcm games gen claim "
                + s.def.id() + " confirm clears them";
        if (!why.equals(s.problem)) {
            host.logger().severe("Fresh Courses: " + s.def.id() + " is off: " + why);
        }
        s.problem = why;
        j.report.accept("&c" + s.def.name() + ": &7" + why);
    }

    private void claimed(SlotState s) {
        try {
            host.store().meta(GenAdminKeys.claim(s.def.id()), Regions.claim(s.def, s.world, s.origin));
            s.claimed = true;
            if (s.problem != null && s.problem.startsWith(FOREIGN)) {
                s.problem = null;
            }
            host.logger().info("Fresh Courses: " + s.def.id() + " claimed " + s.world + " "
                    + Regions.describe(s.def, s.origin));
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not record " + s.def.id() + "'s claim", e);
        }
    }

    private void healed(Job j) {
        SlotState s = j.slot;
        if (j.plan == null) {
            // The quick check of a layout an older planner made.
            List<String> problems = structure(j);
            if (!problems.isEmpty()) {
                fail(j, "its layout from an older version failed the check: " + String.join("; ", problems));
                return;
            }
            host.logger().warning("Fresh Courses: " + s.def.id() + (s.classic ? "'s recalled course can't be made"
                    + " again block for block (it was re-made from its seed, or its archived plan is gone), so only"
                    + " its structure was checked." : "'s course was made by an older version of its planner, so"
                    + " only its structure was checked; the new version builds from the next set."));
        } else if (!proven(j)) {
            return;
        }
        end(j);
        s.verified = true;
        s.healFailed = false;
        s.builtAt = host.now();
        String line = "checked " + s.def.id() + " in half " + j.half + " - " + j.writes + " blocks healed";
        if (j.writes > 0) {
            host.logger().severe("Fresh Courses: " + line + " (the world wasn't saved after the last change, or"
                    + " something edited it); it is open again.");
        } else {
            host.logger().info("Fresh Courses: " + line + "; it is open.");
        }
        s.lastLine = lastLine(j);
        j.report.accept("&a" + s.def.name() + " checked: &7" + j.writes + " blocks healed.");
    }

    /** The golf witness replay on the real blocks (§3.3 step 6); true when proven (or not golf). */
    private boolean proven(Job j) {
        SlotState s = j.slot;
        if (!s.def.golf() || !(j.plan.course() instanceof PlannedGolf g)) {
            return true;
        }
        WorldPort port = host.world(s.world);
        List<List<Putt>> witness = j.kind == Kind.HEAL ? j.tag.witness() : g.witness();
        List<String> problems = port == null ? List.of("the world isn't loaded")
                : LiveProof.replay(port.ballBlocks(), g.course().holes(), witness);
        if (!problems.isEmpty()) {
            host.logger().severe("Fresh Courses: " + s.def.id() + "'s golf didn't replay on the real blocks: "
                    + String.join("; ", problems));
            fail(j, "the live replay failed: " + problems.get(0));
            return false;
        }
        j.proof = "replay ok " + witness.size() + "/" + witness.size();
        return true;
    }

    /** Structural check for a layout an older planner made: read the row, then the blocks. */
    private List<String> structure(Job j) {
        SlotState s = j.slot;
        WorldPort port = host.world(s.world);
        if (port == null) {
            return List.of("the world isn't loaded");
        }
        LiveProof.Solid solid = (x, y, z) -> {
            WorldPort.ChunkView v = port.snapshot(x >> 4, z >> 4);
            return v != null && !v.air(x, y, z);
        };
        LiveProof.Solid water = (x, y, z) -> water(port.snapshot(x >> 4, z >> 4), x, y, z);
        try {
            GamesDao.CourseRow row = host.store().course(s.def.id());
            if (row == null) {
                return List.of("its row is gone");
            }
            if (s.def.golf()) {
                return LiveProof.structure(com.dierks.homecraft.games.golf.CourseCodec.fromRow(row), solid);
            }
            return LiveProof.structure(CourseCodec.decode(row.id(), row.data()).course(), solid, water);
        } catch (SQLException | RuntimeException e) {
            return List.of("its row can't be read (" + e.getMessage() + ")");
        }
    }

    /**
     * Whether block (x, y, z) of a chunk snapshot is water (a Dropper's pool, for
     * {@link LiveProof#structure(Course, LiveProof.Solid, LiveProof.Solid)}); false for no snapshot.
     */
    static boolean water(WorldPort.ChunkView v, int x, int y, int z) {
        return v != null && !v.air(x, y, z) && BuildJob.fluid(v.block(x, y, z));
    }

    // ---- the flip -------------------------------------------------------------------------------

    /** One database write makes the new layout live (§3.3 step 7). A failure leaves the old one live. */
    private void flip(Job j) {
        SlotState s = j.slot;
        Slots.Def def = s.def;
        long now = host.now();
        PlannedCourse pc = j.plan.course();
        long ref = pc instanceof PlannedTrial t ? t.refMs() : 0;
        DailySettings.Stars factors = host.settings().stars();
        long gold = def.golf() ? 0 : Stars.threshold(ref, factors.gold(starTier(def, j.mix)));
        long silver = def.golf() ? 0 : Stars.threshold(ref, factors.silver(starTier(def, j.mix)));
        List<Integer> attempts = pc instanceof PlannedGolf g ? g.attempts() : List.of();
        List<List<Putt>> witness = pc instanceof PlannedGolf g ? g.witness() : List.of();
        GenTag tag = new GenTag(def.id(), planners.get(def.generator()).id(), j.plan.algo(), j.day, j.reroll, j.seed,
                j.half, j.plan.hash(), ref, gold, silver, attempts, witness, now, j.cadence);
        int rev;
        try {
            GamesDao.CourseRow old = host.store().course(def.id());
            String taken = Regions.takenByHand(def, old);
            if (taken != null) {
                s.problem = taken;
                fail(j, taken);
                return;
            }
            GamesDao.CourseRow row = row(def, s.world, pc, tag, old, now);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(GenAdminKeys.mix(def.id()), j.plan.hash() + ":" + j.mix);
            if (j.kind == Kind.PROMOTE || j.reroll > s.reroll) {
                // a promote, or a replacement for a layout nobody could vouch for, takes the next reroll
                meta.put(GenAdminKeys.reroll(def.id(), tag.edition()), Integer.toString(j.reroll));
            }
            // The archive row lands in the same transaction: every edition that goes live is archived.
            GenStore.Flipped f = host.store().flip(row, meta, archiveEntry(def, tag, encode(j.plan), j.mix, now), now);
            rev = f.rev();
            if (f.archived() != null) {
                codes.put(def.id() + "|" + tag.editionKey(), f.archived().code());
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: the flip of " + def.id() + " failed", e);
            fail(j, "the database refused the new course (" + e.getMessage() + ")");
            return;
        }
        end(j);
        GenTag before = s.live;
        boolean beforeStood = before != null && s.verified;
        s.live = tag;
        s.rev = rev;
        s.liveMix = j.mix;
        s.verified = true;
        s.healFailed = false;
        s.previous = beforeStood && before.half() != tag.half() ? before : null;
        s.clearing = false;
        s.oldDirty = before != null;
        s.preview = null;
        s.tries = 0;
        s.lastError = null;
        s.builtAt = now;
        if (j.kind == Kind.PROMOTE || j.reroll > s.reroll) {
            s.reroll = j.reroll;
        }
        s.lastLine = lastLine(j);
        host.coursesChanged(def.game());
        host.logger().info("Fresh Courses: built " + def.id() + " for " + editionName(j.cadence, j.day) + " ("
                + tag.editionKey() + ") in half " + j.half + " - seed " + GenSeed.shortHex(j.seed) + ", "
                + s.lastLine.substring("last: ".length()));
        j.report.accept("&a" + def.name() + " is live: &7" + editionName(j.cadence, j.day) + ", half " + j.half + ".");
        prune();
    }

    /**
     * The tier whose star factors a trial's times use: the slot's tier, or a Dropper's mix's rounded
     * mean ({@link DropRules#tier}, EVENTS-DROPPER-SPEC §B.1.8), so EEE takes easy's factors and
     * EEMMH medium's.
     */
    static String starTier(Slots.Def def, String tierOrMix) {
        return def != null && def.dropper() ? DropRules.tier(tierOrMix).id() : tierOrMix;
    }

    /** The row for a new layout: the planned course with the slot's id and name, in the gen world. */
    static GamesDao.CourseRow row(Slots.Def def, String world, PlannedCourse pc, GenTag tag,
                                  GamesDao.CourseRow old, long now) {
        return row(def, world, pc, tag, old, now, GenCopy.slotName(def, tag.cadence()));
    }

    /** The row for a new layout under {@code name} (a recalled course's "Classic: ..."). */
    static GamesDao.CourseRow row(Slots.Def def, String world, PlannedCourse pc, GenTag tag,
                                  GamesDao.CourseRow old, long now, String name) {
        long created = old == null ? now : old.createdAt();
        int rev = old == null ? 1 : old.rev() + 1;
        if (pc instanceof PlannedGolf g) {
            GolfCourse c = new GolfCourse(def.id(), name, world, true, rev, g.course().holes(), tag);
            return com.dierks.homecraft.games.golf.CourseCodec.toRow(c, created, now);
        }
        Course planned = ((PlannedTrial) pc).course();
        boolean pinned = false;
        if (old != null && Slots.GAME_TRIALS.equals(old.game())) {
            Course was = CourseCodec.decode(old.id(), old.data()).course();
            pinned = was != null && was.pinned();
        }
        Course c = new Course(def.id(), planned.kind(), name, planned.tier(), world, planned.start(),
                planned.checkpoints(), planned.finish(), planned.fallY(), planned.minSeconds(), true, pinned, rev, tag);
        return new GamesDao.CourseRow(c.id(), Slots.GAME_TRIALS, c.kind().id(), c.name(), c.world(), true,
                CourseCodec.encode(c), rev, created, now);
    }

    /** A pinned layout for a new edition: new boards, no blocks (§3.2). */
    private void restamp(SlotState s, int cadence, long day) {
        Slots.Def def = s.def;
        GenTag tag = s.live.withEdition(cadence, day, 0);
        int rev;
        try {
            GamesDao.CourseRow old = host.store().course(def.id());
            if (old == null || Regions.takenByHand(def, old) != null) {
                s.failedTry(edition().day(host.now()), host.now(), "its row is missing");
                return;
            }
            GamesDao.CourseRow row;
            if (def.golf()) {
                GolfCourse g = com.dierks.homecraft.games.golf.CourseCodec.fromRow(old).withGen(tag);
                row = com.dierks.homecraft.games.golf.CourseCodec.toRow(g.withRev(old.rev() + 1), old.createdAt(),
                        host.now());
            } else {
                Course c = CourseCodec.decode(old.id(), old.data()).course().withGen(tag).withRev(old.rev() + 1);
                row = new GamesDao.CourseRow(old.id(), old.game(), old.kind(), old.name(), old.world(), old.enabled(),
                        CourseCodec.encode(c), old.rev() + 1, old.createdAt(), host.now());
            }
            // The same layout under a new edition: archived with the plan its last edition was archived with.
            GenArchiveDao.Row was = host.store().edition(def.id(), s.live.editionKey());
            GenStore.Flipped f = host.store().flip(row, Map.of(), archiveEntry(def, tag, was == null ? null : was.plan(),
                    s.liveMix, host.now()), host.now());
            rev = f.rev();
            if (f.archived() != null) {
                codes.put(def.id() + "|" + tag.editionKey(), f.archived().code());
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: the restamp of " + def.id() + " failed", e);
            s.failedTry(edition().day(host.now()), host.now(), "the database refused the new edition (" + e.getMessage()
                    + ")");
            return;
        }
        s.live = tag;
        s.rev = rev;
        s.tries = 0;
        s.lastError = null;
        host.coursesChanged(def.game());
        host.logger().info("Fresh Courses: " + def.id() + " is pinned - the same course for "
                + editionName(cadence, day) + " (" + tag.editionKey() + "), on new boards.");
        prune();
    }

    /**
     * Boards past keeping, once a course day (§5.2, weekly addendum §3): star rows older than
     * {@code keep_days} (but never the current cadence's last {@value #KEEP_EDITIONS} editions), Star
     * Charts older than {@value #KEEP_WEEKS} weeks (and their fixed goals, {@code gen.goals.<week>}),
     * and edition leaderboards older than {@code keep_days} that aren't among their course's last
     * {@value #KEEP_EDITIONS} editions.
     */
    private void prune() {
        Edition ed = edition();
        long now = host.now();
        long today = ed.day(now);
        if (prunedDay == today) {
            return;
        }
        prunedDay = today;
        DailySettings st = host.settings();
        int n = ed.cadenceDays();
        long lastEditions = Edition.firstDayOf(n, Edition.index(n, ed.editionStart(now)) - (KEEP_EDITIONS - 1));
        long keepFrom = today - st.keepDays();
        long oldestWeek = ed.weekKey(today) - KEEP_WEEKS * 7L;
        weekGoals.keySet().removeIf(week -> week < oldestWeek);
        try {
            for (String key : host.store().metaLike(GenAdminKeys.GOALS).keySet()) {
                Long week = GenAdminKeys.goalsWeek(key);
                if (week != null && week < oldestWeek) {
                    host.store().meta(key, null); // a Star Chart's fixed goals go with its chart
                }
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not prune old Star Chart goals", e);
        }
        int archiveDays = st.archive().keepDays();
        if (archiveDays > 0 && archivePrunedDay != today) {
            archivePrunedDay = today;
            try {
                Set<String> spared = recalledNow();
                for (KeptPlot p : keeper.kept().values()) {
                    spared.add(p.slot() + "|" + p.edition()); // a plot's course is kept, whatever kept_as says
                }
                int gone = host.store().pruneArchive(now - archiveDays * 86_400_000L, spared);
                if (gone > 0) {
                    host.logger().info("Fresh Courses: " + gone + " archived course" + (gone == 1 ? "" : "s")
                            + " older than " + archiveDays + " days left the archive.");
                }
            } catch (SQLException | RuntimeException e) {
                host.logger().log(Level.WARNING, "Fresh Courses: could not prune the archive", e);
            }
        }
        try {
            int removed = host.store().pruneBoards(Math.min(keepFrom, lastEditions), oldestWeek);
            removed += host.store().dropEditionBoards(oldEditionBoards(host.store().editionBoards(), keepFrom,
                    KEEP_EDITIONS, host.store().archivedBoards()));
            if (removed > 0) {
                host.logger().info("Fresh Courses: pruned " + removed + " old course-board and star rows.");
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not prune old boards", e);
        }
    }

    /**
     * The edition leaderboards ({@code gfresh:}) past keeping: an edition that may have started
     * before {@code keepFrom} and isn't one of its course's last {@code keep} editions (by start,
     * whatever their cadence). Anything that isn't an edition board is never picked.
     */
    static List<String> oldEditionBoards(List<String> boards, long keepFrom, int keep) {
        return oldEditionBoards(boards, keepFrom, keep, Set.of());
    }

    /**
     * {@link #oldEditionBoards(List, long, int)}, sparing every board in {@code archived}: an
     * archived edition's board is kept as long as its archive row (GEN-SPEC-KEEP §1), so a recall
     * brings back its old records and the history shows them.
     */
    static List<String> oldEditionBoards(List<String> boards, long keepFrom, int keep, Set<String> archived) {
        Set<String> spare = archived == null ? Set.of() : archived;
        Map<String, List<GenBoards.Board>> byCourse = new HashMap<>();
        Map<GenBoards.Board, String> names = new HashMap<>();
        for (String name : boards == null ? List.<String>of() : boards) {
            GenBoards.Board b = GenBoards.parse(name);
            if (b != null && b.kind() == GenBoards.Kind.DAY) {
                byCourse.computeIfAbsent(b.courseId(), k -> new ArrayList<>()).add(b);
                names.put(b, name);
            }
        }
        List<String> out = new ArrayList<>();
        for (List<GenBoards.Board> course : byCourse.values()) {
            List<String> recent = course.stream()
                    .sorted(Comparator.comparingLong(GenBoards.Board::day).reversed()
                            .thenComparing(GenBoards.Board::edition, Comparator.reverseOrder()))
                    .map(GenBoards.Board::edition).distinct().limit(Math.max(0, keep)).toList();
            for (GenBoards.Board b : course) {
                if (b.day() < keepFrom && !recent.contains(b.edition()) && !spare.contains(names.get(b))) {
                    out.add(names.get(b));
                }
            }
        }
        out.sort(null);
        return out;
    }

    // ---- ending a job -----------------------------------------------------------------------------

    /** The job is over (well): its tickets go. */
    private void end(Job j) {
        if (j.build != null) {
            j.build.release();
        }
        if (job == j) {
            job = null;
        }
    }

    /** The job failed: the old layout stays up; a build counts a try. */
    private void fail(Job j, String why) {
        end(j);
        j.cancelled.set(true);
        SlotState s = j.slot;
        String id = s.def.id();
        switch (j.kind) {
            case BUILD -> {
                s.failedTry(edition().day(host.now()), host.now(), why);
                host.logger().warning("Fresh Courses: " + id + " couldn't be built - " + why + " (try " + s.tries
                        + " of " + host.settings().maxTriesPerDay() + ")");
            }
            case HEAL -> {
                s.verified = false;
                s.healFailed = true;
                s.lastError = why;
                host.logger().severe("Fresh Courses: " + id + "'s live course can't be vouched for - " + why
                        + ". It stays closed; a new one is built.");
            }
            case CLEAR_OLD -> {
                s.lastError = why;
                host.logger().warning("Fresh Courses: emptying " + id + "'s old half failed - " + why);
            }
            case RECALL -> {
                s.failedTry(edition().day(host.now()), host.now(), why);
                host.logger().warning("Fresh Courses: " + id + " couldn't be brought back - " + why + " (try " + s.tries
                        + " of " + host.settings().maxTriesPerDay() + ")");
            }
            default -> {
                s.lastError = why;
                host.logger().warning("Fresh Courses: " + j.kind.name().toLowerCase(Locale.ROOT) + " of " + id
                        + " failed - " + why);
            }
        }
        j.report.accept("&c" + s.def.name() + ": &7" + why);
    }

    /** Give the job up without blaming it (a stop, a restart, a moved region): nothing counts. */
    private void cancel(Job j, String why) {
        end(j);
        j.cancelled.set(true);
        if (j.kind == Kind.HEAL && running) {
            queue.addFirst(new Job(Kind.HEAL, j.slot, j.report)); // the gate stays shut until it is done
        }
        host.logger().info("Fresh Courses: " + j.kind.name().toLowerCase(Locale.ROOT) + " of " + j.slot.def.id()
                + " stopped - " + why);
        j.report.accept("&7" + j.slot.def.name() + ": stopped - " + why);
    }

    // ---- people -----------------------------------------------------------------------------------

    /** §6.3 for the running job: every check until its blocks are verified. */
    private void evacuate(Job j) {
        if (j.stage != Stage.EVACUATE && j.stage != Stage.CONVERGE) {
            return;
        }
        if (j.kind == Kind.HEAL || j.kind == Kind.PROMOTE) {
            return; // the live half (or the admin's preview): nobody is moved, writes near people wait
        }
        SlotState s = j.slot;
        List<Person> people = host.people();
        long now = host.now();
        long deadline = j.evacStart + host.settings().clearWaitMinutes() * 60_000L;
        List<Character> halves = new ArrayList<>();
        for (Step st : j.steps) {
            if (!halves.contains(st.which())) {
                halves.add(st.which());
            }
        }
        boolean waiting = false;
        Box live = s.live == null ? null : s.half(s.live.half());
        for (char h : halves) {
            Box half = s.half(h);
            boolean holdsRun = j.stage == Stage.EVACUATE && (j.kind == Kind.BUILD || j.kind == Kind.PREVIEW
                    || j.kind == Kind.RECALL) && s.previous != null && !s.clearing && s.previous.half() == h;
            for (Evacuator.Action a : j.evac.step(people, s.world, half, s.def.id(), holdsRun, live, now, deadline)) {
                act(s, people, a);
            }
            waiting |= Evacuator.waiting(people, s.world, half, s.def.id(), holdsRun, live);
        }
        if (j.stage == Stage.EVACUATE && !waiting) {
            if (s.previous != null && halves.contains(s.previous.half())) {
                s.clearing = true; // its blocks are about to change: it no longer stands
            }
            j.stage = Stage.CONVERGE;
        }
    }

    private void act(SlotState s, List<Person> people, Evacuator.Action a) {
        try {
            switch (a.kind()) {
                case MOVE -> {
                    Person p = find(people, a.player());
                    if (p != null) {
                        moveToSafety(s, p);
                    }
                }
                case TELL -> host.tell(a.player(), a.line());
                case BAR -> host.actionBar(a.player(), a.line());
                case END -> host.endRun(a.player());
            }
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not move a player out of " + s.def.id(), e);
        }
    }

    /** To {@code safe_spot}, or the world's spawn (§6.3). */
    private void moveToSafety(SlotState s, Person p) {
        double[] spot = host.settings().safeSpot();
        if (spot == null) {
            WorldPort port = host.world(s.world);
            int[] spawn = port == null ? null : port.spawn();
            if (spawn == null) {
                return;
            }
            spot = new double[]{spawn[0] + 0.5, spawn[1], spawn[2] + 0.5};
        }
        host.move(p.id(), s.world, spot[0], spot[1], spot[2]);
        host.tell(p.id(), GenCopy.MOVED);
    }

    private static Person find(List<Person> people, UUID id) {
        for (Person p : people) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ---- the gate (GeneratedCourses) --------------------------------------------------------------

    @Override
    public boolean live(String courseId, GenTag tag) {
        if (tag == null) {
            return true;
        }
        SlotState s = courseId == null ? null : slots.get(courseId.trim().toLowerCase(Locale.ROOT));
        if (!running || s == null || !s.on() || !s.verified || s.live == null) {
            return false;
        }
        return s.live.sameLayout(tag) && s.live.sameEdition(tag);
    }

    @Override
    public boolean standing(GenTag tag) {
        if (!running || tag == null) {
            return false;
        }
        SlotState s = slots.get(tag.holder());
        if (s == null) {
            return false;
        }
        if (s.live != null && s.live.sameLayout(tag)) {
            return true;
        }
        return s.previous != null && !s.clearing && s.previous.sameLayout(tag);
    }

    @Override
    public Box half(GenTag tag) {
        if (tag == null) {
            return null;
        }
        SlotState s = slots.get(tag.holder());
        return s == null ? null : s.half(tag.half());
    }

    @Override
    public String closedLine(String courseId) {
        SlotState s = courseId == null ? null : slots.get(courseId.trim().toLowerCase(Locale.ROOT));
        if (s == null) {
            return GenCopy.closed("That course");
        }
        if (s.classic) {
            // Empty until a recall: it is "being built" only while one is on its way or being checked.
            return classicPending(s) || (s.on() && s.live != null && !s.verified && !s.healFailed)
                    ? GenCopy.building(s.def.name()) : GenCopy.closed(s.def.name());
        }
        boolean building = s.on() && ((job != null && job.slot == s && (job.kind == Kind.HEAL || s.live == null
                || (job.kind == Kind.BUILD && s.healFailed)))
                || (s.live != null && !s.verified && !s.healFailed) || (s.live == null && s.claimed));
        return building ? GenCopy.building(s.def.name()) : GenCopy.closed(s.def.name());
    }

    /**
     * Whether a Classics slot has a recall on its way that isn't open yet: being built, waiting for
     * its turn, or tried again later after a failed try ({@link #closedLine} says "being built,
     * back soon"). A tile shows a Classics slot as holding a course when {@link #classic} gives one,
     * as being built when this is true, and as empty otherwise.
     */
    public boolean classicPending(String classicId) {
        SlotState s = slots.get(classicId == null ? "" : classicId.trim().toLowerCase(Locale.ROOT));
        return s != null && s.classic && classicPending(s);
    }

    private boolean classicPending(SlotState s) {
        return s.on() && s.want != null && ((job != null && job.slot == s && job.kind == Kind.RECALL)
                || !holds(s, s.want) || !s.verified);
    }

    /**
     * When the next set of courses goes up (epoch ms): the soonest end among the switched-on
     * courses' current editions (a layout kept over a cadence change ends at the new schedule's
     * first start), or the schedule's next start when nothing is up; -1 while stopped.
     */
    @Override
    public long nextChangeAt() {
        if (!running) {
            return -1;
        }
        long next = Long.MAX_VALUE;
        for (SlotState s : slots.values()) {
            if (!s.classic && s.on() && s.live != null) {
                next = Math.min(next, target(s).endsAt());
            }
        }
        return next == Long.MAX_VALUE ? edition().nextChangeAt(host.now()) : next;
    }

    @Override
    public boolean inArea(String world, int x, int y, int z) {
        return in(areas, world, x, y, z);
    }

    /**
     * Whether a block is in a flow-only box: a kept Dropper's plot, a plot job the server stopped
     * halfway, or the plot a Dropper keep (or any clearing) is working in now.
     */
    boolean inWet(String world, int x, int y, int z) {
        return in(wetPlots, world, x, y, z) || keeper.inWetJob(world, x, y, z);
    }

    /** Whether a world holds anything {@link #areas} or {@link #wetPlots} name. */
    private boolean guarded(String world) {
        if (world == null) {
            return false;
        }
        for (String w : guardWorlds) {
            if (w.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    private static boolean in(List<Object[]> boxes, String world, int x, int y, int z) {
        if (world == null) {
            return false;
        }
        for (Object[] a : boxes) {
            if (((String) a[0]).equalsIgnoreCase(world) && ((Box) a[1]).contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** Every change refused: every wanted or claimed half, and a moved Dropper's old ones ({@link GenRegionGuard}). */
    public GenRegionGuard.Area guardArea() {
        return guardArea;
    }

    /** No fluid flowing out: kept Droppers' plots and a Dropper keep in flight ({@link GenRegionGuard}). */
    public GenRegionGuard.Area wetArea() {
        return wetArea;
    }

    /**
     * The schedule: {@code clock.time_zone}, {@code games.fresh.cadence}, {@code rebuild_at} and
     * {@code rebuild_day} (the quests' week start when empty), and the quests' week for the Star Chart.
     */
    public Edition edition() {
        return host.settings().edition(host.zone(), host.weekStart());
    }

    /**
     * Whether the slot's live layout is its current edition's (a tile says "Last week's course"
     * or the like otherwise: {@link GenCopy#previous}).
     */
    public boolean today(String slotId) {
        SlotState s = slots.get(slotId);
        return s != null && s.live != null && target(s).holds(s.live);
    }

    /** The edition a slot should be showing now (its key, first day, cadence and end). */
    public GenScheduler.Target current(String slotId) {
        SlotState s = slots.get(slotId);
        return s == null ? GenScheduler.target(null, host.now(), edition(), scheduleSince) : target(s);
    }

    // ---- what a finish pays (the course engines read these; the addendum's §4) ------------------

    /**
     * What the first counted finish of {@code courseId} pays in an edition, at the configured cadence
     * ({@code games.fresh.rewards}, scaled between the daily and weekly ends); 0 for a course that
     * isn't a slot. A finish on a known layout pays by that layout's own cadence instead:
     * {@link #dailyClear(String, int)}.
     */
    @Override
    public int dailyClear(String courseId) {
        return host.settings().dailyClear(courseId);
    }

    /**
     * What the first counted finish of {@code courseId} pays in an edition of {@code cadence} days:
     * pass the run's own {@code tag.cadence()}, so a layout kept over a cadence change pays by the
     * edition it is (a kept daily edition pays the daily amount after a switch to weekly, and the
     * other way round); 0 for a course that isn't a slot.
     */
    public int dailyClear(String courseId, int cadence) {
        return host.settings().dailyClear(courseId, cadence);
    }

    /**
     * The most stars the week starting {@code weekKey} can give: 3 × the courses that are on × the
     * editions that start in it. A cadence longer than a week counts the edition running through a
     * week without a start, so every week has goals.
     */
    public int weekMax(long weekKey) {
        int on = 0;
        for (SlotState s : slots.values()) {
            if (!s.classic && s.on()) {
                on++;
            }
        }
        return DailyStars.weekMax(on, Math.max(1, edition().startsInWeek(weekKey)));
    }

    /**
     * The Star Chart goals of the week starting {@code weekKey}: the cadence's, at most 80% of
     * {@link #weekMax}, <b>fixed for the week</b> the first time they are asked for once it has
     * begun (and the engine is up, its checks done, and something is on). They are kept in
     * {@code hcm_meta} ({@code gen.goals.<week>}), so a restart doesn't move them either.
     *
     * <p>Why fixed: a goal's reward is once a week by its number of stars
     * ({@code ms:gweek:<week>:<stars>}), and worked out afresh at every payment the top goal would
     * move with the courses that are on (clamped 9 with four courses, 7 with three), or with the
     * cadence and {@code star_goals} on a reload, and a player could be paid for it twice under two
     * numbers in one week. A change counts from the next week. A week that hasn't begun, or a
     * moment the goals can't be kept, gets them worked out now, unkept.
     */
    public List<DailyStars.Goal> goals(long weekKey) {
        List<DailyStars.Goal> fixed = weekGoals.get(weekKey);
        if (fixed != null) {
            return fixed;
        }
        String key = GenAdminKeys.goals(weekKey);
        List<DailyStars.Goal> kept;
        try {
            kept = GenAdminKeys.goalsOf(host.store().meta(key));
        } catch (SQLException | RuntimeException e) {
            return host.settings().starGoals(weekMax(weekKey));
        }
        if (kept != null) {
            weekGoals.put(weekKey, kept);
            return kept;
        }
        int max = weekMax(weekKey);
        List<DailyStars.Goal> goals = host.settings().starGoals(max);
        if (running && readyAt >= 0 && max > 0 && weekKey <= thisWeek()) {
            try {
                host.store().meta(key, GenAdminKeys.goalsText(goals));
                weekGoals.put(weekKey, goals);
            } catch (SQLException | RuntimeException e) {
                host.logger().log(Level.WARNING, "Fresh Courses: could not keep this week's Star Chart goals", e);
            }
        }
        return goals;
    }

    /** This week's Star Chart goals, in stars, smallest first. */
    @Override
    public List<Integer> starGoals() {
        return DailyStars.stars(goals(thisWeek()));
    }

    /** What reaching {@code goal} stars pays this week (0 when it isn't one of this week's goals). */
    public int starGoalReward(int goal) {
        return DailyStars.tokens(goals(thisWeek()), goal);
    }

    /**
     * One number for every goal, for a caller that doesn't yet pay each goal its own tokens: this
     * week's smallest goal's ({@code star_goals}; 0 when the week has none). Pay by
     * {@link #starGoalReward(int)} / {@link #goals} where the goal is known.
     */
    @Override
    public int starGoalReward() {
        List<DailyStars.Goal> goals = goals(thisWeek());
        return goals.isEmpty() ? 0 : goals.get(0).tokens();
    }

    /** The most star-goal tokens a player earns a day ({@code games.fresh.daily_cap}). */
    @Override
    public int starGoalCap() {
        return host.settings().dailyCap();
    }

    private long thisWeek() {
        Edition ed = edition();
        return ed.weekKey(ed.day(host.now()));
    }

    /** The live tag of a slot, or {@code null}. */
    public GenTag liveTag(String slotId) {
        SlotState s = slots.get(slotId);
        return s == null ? null : s.live;
    }

    /**
     * One slot as {@code /hcm games check} and the new-courses line read it (EXTRAS E1, E2).
     *
     * @param wanted    switched on (config, or the admin's override)
     * @param on        switched on and nothing in the way
     * @param problem   why it can't be built or opened (its region, a hand-built course, foreign
     *                  blocks: "Region has 1,234 blocks..."), or {@code null}
     * @param claimed   its area is Fresh Courses' own
     * @param live      its live layout, or {@code null}
     * @param current   the live layout is vouched for and is its current edition's
     * @param building  a job is running on it now
     * @param lastError why its last try failed, or {@code null}
     */
    public record SlotReport(String id, boolean classic, boolean wanted, boolean on, String problem, boolean claimed,
                             String world, GenTag live, boolean verified, boolean current, boolean building,
                             String lastError, boolean healFailed) {
    }

    /** Every slot, then every Classics slot, as they stand now. Read-only. */
    public List<SlotReport> report() {
        List<SlotReport> out = new ArrayList<>();
        for (SlotState s : slots.values()) {
            boolean current = s.live != null && s.verified && (s.classic ? s.want != null && holds(s, s.want)
                    : target(s).holds(s.live));
            out.add(new SlotReport(s.def.id(), s.classic, s.wanted(), s.on(), s.problem, s.claimed, s.world, s.live,
                    s.verified, current, job != null && job.slot == s, s.lastError, s.healFailed));
        }
        return out;
    }

    // ---- status (§8.6) ----------------------------------------------------------------------------

    /** The lines under {@code fresh_courses} in {@code /hcm games status}. */
    public List<String> summary() {
        List<String> out = new ArrayList<>();
        long now = host.now();
        long today = edition().day(now);
        int up = 0;
        long first = Long.MAX_VALUE;
        long last = 0;
        GenScheduler.Target shown = null;
        for (SlotState s : slots.values()) {
            if (s.classic) {
                continue;
            }
            GenScheduler.Target t = target(s);
            if (s.on() && s.verified && s.live != null && t.holds(s.live)) {
                up++;
                shown = shown == null ? t : shown;
                if (s.builtAt > 0) {
                    first = Math.min(first, s.builtAt);
                    last = Math.max(last, s.builtAt);
                }
            }
        }
        GenScheduler.Target t = shown == null ? current(null) : shown;
        out.add(up + " course" + (up == 1 ? "" : "s") + " up for " + editionName(t.cadence(), t.start())
                + (last > 0 ? " · built " + clock(first) + (last - first >= 60_000 ? "-" + clock(last) : "") : ""));
        out.add(scheduleLine(now));
        for (SlotState s : slots.values()) {
            String line = trouble(s, today);
            if (line != null) {
                out.add(s.def.id() + ": " + line);
            }
        }
        for (SlotState s : slots.values()) {
            if (s.classic && s.live != null) {
                out.add(s.def.id() + ": " + classicHolds(s));
            }
        }
        for (SlotState s : slots.values()) {
            for (String c : s.wet) {
                int[] o = Regions.claimOrigin(c);
                out.add(s.def.id() + ": its old area in " + Regions.claimWorld(c) + " (" + (o == null ? c
                        : Regions.describe(s.def, o)) + ") is still guarded - drain first: move it back and /hcm"
                        + " games gen clear " + s.def.id());
            }
        }
        return out;
    }

    /** "HARD-40 (Hard Parkour, week of 5 Oct) until Mon 12 Oct 4:02 AM" for a Classics slot that holds one. */
    private String classicHolds(SlotState s) {
        GenTag t = s.live;
        Slots.Def orig = Slots.of(t.slot());
        String code = code(t);
        String until = s.want == null ? "closing" : s.want.until() > 0 ? "until " + GenCopy.whenDated(s.want.until(),
                host.zone()) : "until replaced or unrecalled";
        return (code == null ? t.slot() + " " + t.editionKey() : code) + " (" + (orig == null ? t.slot() : orig.name())
                + ", " + GenCopy.editionDates(t.cadence(), t.day()) + (s.want != null && s.want.remade() ? ", re-made"
                : "") + ") " + until;
    }

    /**
     * The cadence and the next change, as the owner reads it: "weekly (Mondays at 4:00 AM) · next:
     * Mon 5 Oct 4:00 AM (in 6d 14h)", plus when the current courses were made under an older
     * setting and stay until the new one starts.
     */
    private String scheduleLine(long now) {
        Edition ed = edition();
        long next = nextChangeAt();
        String at = "at " + CLOCK.format(ed.rollover());
        String when = ed.cadenceDays() == Edition.WEEKLY
                ? ed.rebuildDay().getDisplayName(TextStyle.FULL, Locale.US) + "s " + at : at;
        boolean kept = false;
        for (SlotState s : slots.values()) {
            kept |= !s.classic && s.on() && s.live != null && target(s).kept();
        }
        int was = keptCadence();
        String made = was == ed.cadenceDays() ? "for the old change day" : GenCopy.cadenceName(was);
        return GenCopy.cadenceName(ed.cadenceDays()) + " (" + when + ") · next: "
                + GenCopy.whenDated(next, host.zone()) + " (in " + GenCopy.span(next - now) + ")"
                + (kept ? " · the current courses were made " + made + " and stay until then" : "");
    }

    /** The cadence a kept layout was made with (the first one found). */
    private int keptCadence() {
        for (SlotState s : slots.values()) {
            if (!s.classic && s.on() && s.live != null && target(s).kept()) {
                return s.live.cadence();
            }
        }
        return edition().cadenceDays();
    }

    /** What is wrong with a slot for the summary, or {@code null}. */
    private String trouble(SlotState s, long today) {
        if (s.classic) {
            return classicTrouble(s, today);
        }
        if (!s.wanted()) {
            return null;
        }
        if (s.problem != null) {
            return "off - " + s.problem;
        }
        if (job != null && job.slot == s) {
            return "being built";
        }
        boolean behind = s.live != null && !target(s).holds(s.live);
        if (behind && s.lastError != null) {
            return "showing the last course - " + s.lastError + " (try " + s.triesOn(today) + " of "
                    + host.settings().maxTriesPerDay() + nextTry(s) + ")";
        }
        if (s.live == null && s.lastError != null) {
            return "no course yet - " + s.lastError + " (try " + s.triesOn(today) + " of "
                    + host.settings().maxTriesPerDay() + nextTry(s) + ")";
        }
        if (s.healFailed) {
            return "closed - " + (s.lastError == null ? "its course couldn't be checked" : s.lastError);
        }
        if (s.waiting != null && (s.live == null || behind)) {
            return "waiting - " + s.waiting;
        }
        return null;
    }

    /** What is wrong with a Classics slot for the summary, or {@code null}. */
    private String classicTrouble(SlotState s, long today) {
        if (s.problem != null && (s.want != null || s.live != null)) {
            return "off - " + s.problem;
        }
        if (job != null && job.slot == s) {
            return job.kind == Kind.RECALL ? "being built" : null;
        }
        if (s.want != null && !holds(s, s.want) && s.lastError != null) {
            return "not up yet - " + s.lastError + " (try " + s.triesOn(today) + " of "
                    + host.settings().maxTriesPerDay() + nextTry(s) + ")";
        }
        if (s.healFailed && s.live != null) {
            return "closed - " + (s.lastError == null ? "its course couldn't be checked" : s.lastError);
        }
        if (s.waiting != null && s.want != null && !holds(s, s.want)) {
            return "waiting - " + s.waiting;
        }
        return null;
    }

    private String nextTry(SlotState s) {
        long at = s.lastTryAt + host.settings().retryMinutes() * 60_000L;
        return at > host.now() ? ", next " + clock(at) : "";
    }

    @Override
    public List<String> status(String slotId) {
        List<String> out = new ArrayList<>();
        long now = host.now();
        long today = edition().day(now);
        if (slotId == null) {
            out.add("&7" + scheduleLine(now));
        }
        for (SlotState s : slots.values()) {
            if (slotId != null && !s.def.id().equals(slotId)) {
                continue;
            }
            out.add(statusLine(s));
            if (job != null && job.slot == s) {
                out.add("  &e" + jobLine(job));
            } else if (s.lastLine != null && s.wanted()) {
                out.add("  &8" + s.lastLine);
            }
            String t = trouble(s, today);
            if (t != null && !(job != null && job.slot == s)) {
                out.add("  &c" + t);
            }
            if (slotId != null && s.classic) {
                out.add("  &7region " + s.world + " " + Regions.describe(s.def, s.origin) + (s.claimed ? " (claimed)"
                        : " (not claimed yet)"));
                if (s.want != null) {
                    out.add("  &7recalled " + s.want.slot() + " " + s.want.edition() + " on " + GenCopy.whenDated(
                            s.want.from(), host.zone()) + (s.want.remade() ? " (re-made)" : ""));
                }
            } else if (slotId != null) {
                GenScheduler.Target target = target(s);
                out.add("  &7edition " + target.key() + " (" + editionName(target.cadence(), target.start())
                        + ") until " + GenCopy.whenDated(target.endsAt(), host.zone())
                        + (target.kept() ? " - kept from the old setting" : ""));
                out.add("  &7region " + s.world + " " + Regions.describe(s.def, s.origin) + (s.claimed ? " (claimed)"
                        : " (not claimed yet)"));
                if (s.pin != null) {
                    String ignored = pinIgnored(s);
                    out.add("  &7pinned seed " + GenSeed.hex(s.pin.seed()) + (s.pin.until() > 0 ? " until "
                            + date(s.pin.until()) : "") + (ignored == null ? "" : " &c(not used: " + ignored + ")"));
                }
                if (s.preview != null) {
                    out.add("  &7preview in half " + s.preview.half() + ", seed " + GenSeed.hex(s.preview.seed()));
                }
            }
        }
        if (out.size() <= (slotId == null ? 1 : 0)) {
            out.add("&cNo slot called " + slotId + ".");
        }
        return out;
    }

    private String statusLine(SlotState s) {
        if (s.classic) {
            StringBuilder c = new StringBuilder("&f").append(pad(s.def.id(), 21));
            if (job != null && job.slot == s && job.kind == Kind.RECALL) {
                return c.append("&eBUILDING &7").append(s.want == null ? "" : s.want.slot() + " " + s.want.edition())
                        .toString();
            }
            if (s.live == null) {
                return c.append(s.want != null ? "&7waiting to bring back " + s.want.slot() + " " + s.want.edition()
                        : s.bothDirty || s.previous != null ? "&7empty &8(being cleared)" : "&7empty").toString();
            }
            return c.append(s.verified ? "&aholds &7" : "&cclosed &7").append(classicHolds(s)).append("  half ")
                    .append(s.live.half()).append("  ").append(playing(s)).append(" playing").toString();
        }
        StringBuilder b = new StringBuilder("&f").append(pad(s.def.id(), 21));
        if (!s.wanted()) {
            return b.append("&7off").toString();
        }
        b.append(s.on() ? "&aon  " : "&coff  ").append("&7").append(pad(s.mix, 11));
        if (job != null && job.slot == s && job.kind != Kind.CLEAR_OLD) {
            return b.append("&eBUILDING").toString();
        }
        if (s.live == null) {
            return b.append("&7no course yet").toString();
        }
        b.append(s.verified ? "live " : "&cclosed &7").append(editionName(s.live.cadence(), s.live.day()))
                .append(" (").append(s.live.editionKey()).append(")");
        b.append("  half ").append(s.live.half()).append("  rev ").append(s.rev).append("  seed ")
                .append(GenSeed.shortHex(s.live.seed())).append("…  ").append(playing(s)).append(" playing");
        return b.toString();
    }

    private String jobLine(Job j) {
        String what = switch (j.stage) {
            case START -> "starting";
            case PLANNING -> "planning (" + (host.now() - j.planStarted) / 1000 + "s)";
            case SCANNING -> "checking the area is empty";
            case EVACUATE -> "waiting for a player to finish on the old course";
            case CONVERGE -> j.build == null ? "converging" : j.build.phase() == BuildJob.Phase.LOAD
                    ? "loading chunks " + j.build.loadedCount() + "/" + j.build.chunkCount()
                    : (j.build.pass() == 1 ? "converging" : "verifying") + " · " + j.build.waiting()
                    + " blocks to go · " + j.build.writes() + " written";
        };
        return j.kind.name() + ": " + what;
    }

    private int playing(SlotState s) {
        int n = 0;
        for (Person p : host.people()) {
            if (p.playing(s.def.id())) {
                n++;
            }
        }
        return n;
    }

    private String lastLine(Job j) {
        long work = j.plan == null ? 0 : j.plan.work();
        return "last: " + CLOCK_S.format(Instant.ofEpochMilli(host.now()).atZone(host.zone())) + " plan "
                + String.format(Locale.ROOT, "%.2fs", j.planMs / 1000.0)
                + (work >= 1000 ? " (" + (work / 1000) + "k " + (j.slot.def.golf() ? "shots" : "steps") + ")" : "")
                + " · " + String.format(Locale.ROOT, "%,d", j.writes) + " ops in " + j.budget.ticks() + " tick"
                + (j.budget.ticks() == 1 ? "" : "s") + " (max " + String.format(Locale.ROOT, "%.1f", j.budget.maxMillis())
                + " ms) · " + j.chunks + " chunks · " + j.proof;
    }

    // ---- admin (GenOps) ---------------------------------------------------------------------------

    @Override
    public String restartSoon() {
        RestartHold hold = host.restartHold();
        long now = host.now();
        return GenScheduler.nearRestart(now, hold, host.settings().avoidBeforeRestartMinutes())
                ? GenCopy.restartSoon(hold.clock(hold.next(now))) : null;
    }

    @Override
    public void plan(String slotId, String arg, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        Planner p = planners.get(s.def.generator());
        Edition ed = edition();
        GenScheduler.Target t = target(s);
        long day = t.start();
        int cadence = t.cadence();
        long seed;
        if (arg != null && (arg.equalsIgnoreCase("next") || arg.equalsIgnoreCase("tomorrow"))) {
            day = ed.editionStart(t.endsAt());
            cadence = ed.cadenceDays();
            if (secret() == null) {
                report.accept("&cThe seed secret can't be read right now.");
                return;
            }
            seed = GenSeed.seed(secret, cadence, day, s.def.id(), 0);
        } else if (arg != null) {
            Long parsed = GenSeed.parse(arg);
            if (parsed == null) {
                report.accept("&cA seed is up to 16 hex digits, like 3f2a91c07d1e55b0.");
                return;
            }
            seed = parsed;
        } else {
            if (secret() == null) {
                report.accept("&cThe seed secret can't be read right now.");
                return;
            }
            seed = GenSeed.seed(secret, cadence, day, s.def.id(), s.reroll);
        }
        char which = s.idleHalf();
        Box half = s.half(which);
        PlanInput in = new PlanInput(s.def, half, which, day, 0, seed, s.mix, host.fallDepth(),
                WORK.getOrDefault(s.def.generator(), 200_000L), cancelled(new AtomicBoolean()));
        String d = editionName(cadence, day);
        report.accept("&7Planning " + s.def.name() + " for " + d + " (seed " + GenSeed.hex(seed) + ")...");
        host.planner().execute(() -> {
            long t0 = System.nanoTime();
            List<String> lines = new ArrayList<>();
            try {
                Plan plan = p.plan(in);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                lines.add("&6" + s.def.name() + " &7- " + d + ", seed " + GenSeed.hex(seed) + ": &f"
                        + plan.ops().size() + " blocks, " + plan.signs().size() + " signs, hash " + plan.hash()
                        + ", " + ms + " ms, work " + plan.work());
                for (String line : plan.summary()) {
                    lines.add("&7  " + line);
                }
                for (String problem : PlanCheck.problems(plan, s.def, half)) {
                    lines.add("&c  " + problem);
                }
            } catch (GenFailed e) {
                lines.add("&c" + s.def.name() + " can't be planned: &7" + e.getMessage());
            } catch (Throwable e) {
                lines.add("&c" + s.def.name() + "'s planner threw: &7" + e);
            }
            inbox.add(() -> lines.forEach(report));
        });
    }

    @Override
    public void preview(String slotId, String seedText, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (!ready(s, report)) {
            return;
        }
        GenScheduler.Target t = target(s);
        Long seed = seedText == null ? null : GenSeed.parse(seedText);
        if (seedText != null && seed == null) {
            report.accept("&cA seed is up to 16 hex digits, like 3f2a91c07d1e55b0.");
            return;
        }
        if (seed == null) {
            if (secret() == null) {
                report.accept("&cThe seed secret can't be read right now.");
                return;
            }
            seed = GenSeed.seed(secret, t.cadence(), t.start(), s.def.id(), s.reroll + 1);
        }
        Job j = new Job(Kind.PREVIEW, s, report);
        j.day = t.start();
        j.cadence = t.cadence();
        j.reroll = s.reroll + 1;
        j.seed = seed;
        j.mix = s.mix;
        queue.add(j);
        report.accept("&7A preview of " + s.def.name() + " (seed " + GenSeed.hex(seed) + ") is on its way into half "
                + s.idleHalf() + ".");
    }

    @Override
    public void promote(String slotId, boolean confirm, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (!ready(s, report)) {
            return;
        }
        SlotState.Preview pv = s.preview;
        GenScheduler.Target t = target(s);
        if (pv == null) {
            report.accept("&cThere is no preview of " + s.def.name() + ". &7/hcm games gen preview " + slotId);
            return;
        }
        if (pv.day() != t.start() || pv.cadence() != t.cadence()) {
            report.accept("&cThat preview was made for " + editionName(pv.cadence(), pv.day()) + ". &7Make a new one.");
            return;
        }
        if (activePin(s) != null) {
            report.accept("&c" + s.def.name() + " is pinned. &7/hcm games gen unpin " + slotId + " first.");
            return;
        }
        if (!confirm && s.live != null && hasScores(s)) {
            report.accept("&e" + s.def.name() + " already has times on this course. &7They stay on their own board."
                    + " Type &e/hcm games gen promote " + slotId + " confirm");
            return;
        }
        Job j = new Job(Kind.PROMOTE, s, report);
        j.day = t.start();
        j.cadence = t.cadence();
        j.reroll = Math.max(s.reroll, t.holds(s.live) ? s.live.reroll() : 0) + 1;
        j.seed = pv.seed();
        j.mix = pv.mix();
        queue.add(j);
        report.accept("&7Making the preview of " + s.def.name() + " the current course...");
    }

    private boolean hasScores(SlotState s) {
        try {
            return host.store().hasScores(s.def.game(), GenBoards.day(s.live));
        } catch (SQLException e) {
            return true;
        }
    }

    @Override
    public void reroll(String slotId, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (!ready(s, report)) {
            return;
        }
        if (activePin(s) != null) {
            report.accept("&c" + s.def.name() + " is pinned. &7/hcm games gen unpin " + slotId + " first.");
            return;
        }
        GenScheduler.Target t = target(s);
        int next = Math.max(s.reroll, t.holds(s.live) ? s.live.reroll() : 0) + 1;
        try {
            host.store().meta(GenAdminKeys.reroll(slotId, t.key()), Integer.toString(next));
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            host.logger().log(Level.WARNING, "Fresh Courses: could not store a reroll", e);
            return;
        }
        s.reroll = next;
        s.tries = 0;
        report.accept("&a" + s.def.name() + " gets a new course for " + editionName(t.cadence(), t.start())
                + " (reroll " + next + "). &7It is built at the next check; anyone on the old one finishes there, on"
                + " its own board.");
    }

    @Override
    public void rebuild(String slotId, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (!ready(s, report)) {
            return;
        }
        if (s.live == null) {
            report.accept("&c" + s.def.name() + " has no course to rebuild yet.");
            return;
        }
        queue.add(new Job(Kind.HEAL, s, report));
        report.accept("&7Checking and healing " + s.def.name() + "'s live half " + s.live.half() + "...");
    }

    @Override
    public void enable(String slotId, boolean on, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        try {
            host.store().meta(GenAdminKeys.enabled(slotId), Boolean.toString(on));
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            host.logger().log(Level.WARNING, "Fresh Courses: could not store an on/off", e);
            return;
        }
        s.override = on;
        if (on) {
            s.problem = null;
            vet(s, handBuilt());
            report.accept("&a" + s.def.name() + " is on." + (s.problem == null ? "" : " &cBut: &7" + s.problem));
            return;
        }
        if (job != null && job.slot == s) {
            cancel(job, "it was switched off");
        }
        queue.removeIf(j -> j.slot == s);
        for (Person p : host.people()) {
            if (p.playing(slotId)) {
                host.endRun(p.id());
                host.tell(p.id(), GenCopy.closed(s.def.name()));
            }
        }
        report.accept("&a" + s.def.name() + " is off. &7Its blocks stay; &e/hcm games gen on " + slotId
                + " &7opens it again.");
    }

    @Override
    public void tier(String slotId, String tierOrMix, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        String problem = s.def.tierProblem(tierOrMix);
        if (problem != null) {
            report.accept("&c" + problem + ".");
            return;
        }
        String t = s.def.normalise(tierOrMix);
        try {
            host.store().meta(GenAdminKeys.tier(slotId), t);
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            return;
        }
        s.mix = t;
        report.accept("&a" + s.def.name() + " will be " + t + " &7from its next build (the next set, or &e/hcm games"
                + " gen reroll " + slotId + "&7).");
    }

    @Override
    public void pin(String slotId, String seedText, int days, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        Planner p = planners.get(s.def.generator());
        long seed;
        if (seedText.equalsIgnoreCase("live") || seedText.equalsIgnoreCase("today")) {
            if (s.live == null) {
                report.accept("&c" + s.def.name() + " has no course to pin yet.");
                return;
            }
            seed = s.live.seed();
        } else {
            Long parsed = GenSeed.parse(seedText);
            if (parsed == null) {
                report.accept("&cA seed is up to 16 hex digits, or &elive&c.");
                return;
            }
            seed = parsed;
        }
        long today = edition().day(host.now());
        GenScheduler.Pin pin = new GenScheduler.Pin(seed, p == null ? 0 : p.algo(), days > 0 ? today + days - 1 : 0);
        try {
            host.store().meta(GenAdminKeys.pin(slotId), pin.text());
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            return;
        }
        s.pin = pin;
        report.accept("&a" + s.def.name() + " is pinned to seed " + GenSeed.hex(seed) + (days > 0 ? " for " + days
                + " day" + (days == 1 ? "" : "s") : " until unpinned") + ". &7Each new set gets fresh boards.");
    }

    @Override
    public void unpin(String slotId, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        try {
            host.store().meta(GenAdminKeys.pin(slotId), null);
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            return;
        }
        s.pin = null;
        report.accept("&a" + s.def.name() + " is unpinned. &7A new course comes with the next set.");
    }

    @Override
    public Spot spot(String slotId, boolean idle) {
        if (slotId != null && slotId.startsWith("plot:")) {
            Box b;
            try {
                b = keeper.plotBox(Integer.parseInt(slotId.substring(5)));
            } catch (NumberFormatException e) {
                b = null;
            }
            return b == null ? null : new Spot(genWorld(), (b.minX() + b.maxX() + 1) / 2.0, b.minY() + b.sizeY() / 2.0,
                    (b.minZ() + b.maxZ() + 1) / 2.0, 0f);
        }
        SlotState s = slots.get(slotId);
        if (s == null || s.world.isBlank()) {
            return null;
        }
        if (!idle && s.live != null) {
            try {
                GamesDao.CourseRow row = host.store().course(slotId);
                if (row != null && s.def.golf()) {
                    GolfCourse g = com.dierks.homecraft.games.golf.CourseCodec.fromRow(row);
                    if (!g.holes().isEmpty() && g.holes().get(0).tee() != null) {
                        GolfCourse.Tee t = g.holes().get(0).tee();
                        return new Spot(s.world, t.x(), t.y(), t.z(), t.yaw());
                    }
                } else if (row != null) {
                    Course c = CourseCodec.decode(row.id(), row.data()).course();
                    if (c != null && c.start() != null) {
                        return new Spot(s.world, c.start().x(), c.start().y(), c.start().z(), c.start().yaw());
                    }
                }
            } catch (SQLException | RuntimeException e) {
                // the middle of the half, below
            }
        }
        if (idle && s.preview != null && s.preview.plan().course() instanceof PlannedTrial t
                && t.course().start() != null) {
            Course.Spot st = t.course().start();
            return new Spot(s.world, st.x(), st.y(), st.z(), st.yaw());
        }
        Box h = s.half(idle ? s.idleHalf() : (s.live == null ? 'A' : s.live.half()));
        return new Spot(s.world, (h.minX() + h.maxX() + 1) / 2.0, h.minY() + h.sizeY() / 2.0,
                (h.minZ() + h.maxZ() + 1) / 2.0, 0f);
    }

    @Override
    public void claim(String slotId, boolean confirm, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (s.claimed) {
            report.accept("&7" + s.def.name() + "'s area is already claimed.");
            return;
        }
        if (s.world.isBlank() || host.world(s.world) == null) {
            report.accept("&cThe world " + s.world + " isn't loaded.");
            return;
        }
        if (busyWith(s)) {
            report.accept("&c" + s.def.name() + " is busy right now; try in a moment.");
            return;
        }
        String why = claimProblem(s);
        if (why != null) {
            report.accept("&c" + s.def.name() + "'s area can't be claimed: &7" + why);
            return;
        }
        queue.add(new Job(confirm ? Kind.CLAIM : Kind.SCAN, s, report));
        report.accept(confirm ? "&7Clearing " + s.def.name() + "'s area (" + Regions.describe(s.def, s.origin)
                + ") and claiming it..." : "&7Counting what is in " + s.def.name() + "'s area...");
    }

    @Override
    public void clear(String slotId, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (!s.claimed) {
            report.accept("&c" + s.def.name() + "'s area isn't claimed, so there is nothing of Fresh Courses' to"
                    + " clear.");
            return;
        }
        enable(slotId, false, line -> { });
        queue.add(new Job(Kind.DECOMMISSION, s, report));
        report.accept("&7Emptying both halves of " + s.def.name() + "; it is off.");
    }

    /** Whether a job for this slot is queued or running. */
    private boolean busyWith(SlotState s) {
        if (job != null && job.slot == s) {
            return true;
        }
        for (Job j : queue) {
            if (j.slot == s) {
                return true;
            }
        }
        return false;
    }

    /** An admin job may start: running, the world is up, nothing else of this slot's is going. */
    private boolean ready(SlotState s, Consumer<String> report) {
        if (!running || readyAt < 0) {
            report.accept("&cFresh Courses is still starting; try in a moment.");
            return false;
        }
        if (!s.on()) {
            report.accept("&c" + s.def.name() + " is off" + (s.problem == null ? "." : ": &7" + s.problem));
            return false;
        }
        if (busyWith(s)) {
            report.accept("&c" + s.def.name() + " is being built right now; try when it's done.");
            return false;
        }
        return true;
    }

    // ---- recalls into the Classics slots (GEN-SPEC-KEEP §3) ------------------------------------------

    /** A recall starts: the Classics slot's region is checked (and claimed the first time), then its plan. */
    private void beginRecall(Job j) {
        SlotState s = j.slot;
        String why = vet(s, handBuilt());
        if (why != null) {
            end(j);
            j.report.accept("&c" + s.def.name() + " can't be used: &7" + why);
            return;
        }
        j.half = s.idleHalf();
        if (!s.claimed) {
            j.steps.add(new Step('A', null, BuildJob.Mode.SCAN));
            j.steps.add(new Step('B', null, BuildJob.Mode.SCAN));
            j.stage = Stage.SCANNING;
            return;
        }
        recallPlan(j);
    }

    /**
     * The recalled edition's plan: the ARCHIVED one moved into the Classics slot's idle half (never
     * planned again), or, for a course made again from its seed, today's generator's.
     */
    private void recallPlan(Job j) {
        SlotState s = j.slot;
        ClassicWant w = j.want;
        GenArchiveDao.Row row;
        try {
            row = host.store().edition(w.slot(), w.edition());
        } catch (SQLException e) {
            fail(j, "the archive can't be read (" + e.getMessage() + ")");
            return;
        }
        Slots.Def orig = row == null ? null : Slots.of(row.slot());
        if (row == null || orig == null) {
            dropWant(j, "that course isn't in the archive any more");
            return;
        }
        if (Slots.classicFor(orig) != s.def) {
            dropWant(j, s.def.name() + " can't hold " + row.name());
            return;
        }
        Edition.Key key = Edition.Key.parse(row.edition());
        if (key == null) {
            dropWant(j, "its edition " + row.edition() + " can't be read");
            return;
        }
        j.source = row;
        j.day = row.day();
        j.cadence = key.cadence();
        j.reroll = key.reroll();
        j.seed = row.seed();
        j.mix = row.tierOrMix();
        Box target = s.half(j.half);
        j.planDef = orig;
        if (w.remade()) {
            Box box = Box.sized(target.minX(), target.minY(), target.minZ(), orig.sizeX(), orig.sizeY(), orig.sizeZ());
            if (!target.contains(box)) {
                dropWant(j, row.name() + " doesn't fit " + s.def.name());
                return;
            }
            Planner p = planners.get(orig.generator());
            if (p == null) {
                dropWant(j, "there is no " + orig.generator() + " generator");
                return;
            }
            j.planBox = box;
            plan(j, p);
            return;
        }
        PlanCodec.Read read = PlanCodec.decode(row.plan());
        if (!read.ok()) {
            dropWant(j, "its stored plan can't be read (" + read.problem() + "). Make it again from its seed: "
                    + "/hcm games gen recall " + s.def.id() + " " + row.slot() + " seed:" + GenSeed.hex(row.seed()));
            return;
        }
        // The plan's own half, as it was made: a slot whose size changed since can't refuse it.
        Box box = at(target, read.plan().half());
        if (!target.contains(box)) {
            dropWant(j, row.name() + " doesn't fit " + s.def.name());
            return;
        }
        j.planBox = box;
        Plan moved = PlanShift.to(read.plan(), box);
        List<String> problems = PlanCheck.problems(moved, orig, box);
        if (!problems.isEmpty()) {
            fail(j, "its plan was refused: " + String.join("; ", problems));
            return;
        }
        if (!orig.dropper()) {
            recallProven(j, moved, List.of(), null);
            return;
        }
        // A moved dropper is proven again where it stands: its whole validator, on the planner thread
        // (like PlanCheck.generator: tens of milliseconds), the answer back through the inbox.
        j.stage = Stage.PLANNING;
        j.planStarted = host.now();
        host.planner().execute(() -> {
            List<String> refused = List.of();
            Throwable error = null;
            try {
                refused = PlanCheck.movedProblems(moved, orig);
            } catch (Throwable e) {
                error = e;
            }
            List<String> checked = refused;
            Throwable failure = error;
            inbox.add(() -> {
                if (job == j && j.stage == Stage.PLANNING) {
                    recallProven(j, moved, checked, failure);
                } // cancelled, killed or superseded meanwhile: dropped
            });
        });
    }

    /** A recall's moved plan came through its checks ({@code refused} empty, no {@code error}): build it. */
    private void recallProven(Job j, Plan moved, List<String> refused, Throwable error) {
        if (error != null) {
            host.logger().log(Level.SEVERE, "Fresh Courses: checking " + j.slot.def.id() + "'s recall threw", error);
            fail(j, "its plan couldn't be checked (" + error + ")");
            return;
        }
        if (!refused.isEmpty()) {
            fail(j, "its plan was refused: " + String.join("; ", refused));
            return;
        }
        j.plan = moved;
        j.steps.add(new Step(j.half, moved, BuildJob.Mode.CONVERGE));
        j.stage = Stage.EVACUATE;
        j.evacStart = host.now();
        evacuate(j);
    }

    /** A box the size of {@code half} at {@code target}'s min corner (where an archived plan is moved to). */
    private static Box at(Box target, Box half) {
        return Box.sized(target.minX(), target.minY(), target.minZ(), half.sizeX(), half.sizeY(), half.sizeZ());
    }

    /** A recall that can never be built: it is forgotten (the slot keeps what it held), and the admin told. */
    private void dropWant(Job j, String why) {
        SlotState s = j.slot;
        end(j);
        ClassicWant back = s.prior != null && holds(s, s.prior) ? s.prior : null;
        try {
            host.store().meta(GenAdminKeys.recall(s.def.id()), back == null ? null : back.text());
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not forget " + s.def.id() + "'s recall", e);
        }
        s.want = back;
        s.prior = null;
        s.lastError = why;
        host.logger().warning("Fresh Courses: the recall into " + s.def.id() + " was dropped - " + why);
        j.report.accept("&c" + s.def.name() + ": &7" + why);
    }

    /**
     * The boot check of a Classics slot: its archived plan moved into its live half again (the same
     * blocks when it hashes the same), converged and verified. A course made again from its seed, or
     * one the archive can no longer give back, gets the quick structural check instead.
     */
    private void healClassic(Job j) {
        SlotState s = j.slot;
        GenTag tag = j.tag;
        Slots.Def orig = Slots.of(tag.slot());
        Box target = s.half(j.half);
        try {
            GenArchiveDao.Row row = orig == null ? null : host.store().edition(tag.slot(), tag.editionKey());
            PlanCodec.Read read = row == null ? null : PlanCodec.decode(row.plan());
            if (read != null && read.ok()) {
                Box box = at(target, read.plan().half());
                Plan moved = PlanShift.to(read.plan(), box);
                if (moved.hash().equals(tag.planHash())) {
                    j.plan = moved;
                    j.planDef = orig;
                    j.planBox = box;
                    j.steps.add(new Step(j.half, moved, BuildJob.Mode.CONVERGE));
                    j.stage = Stage.CONVERGE;
                    return;
                }
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: " + s.def.id() + "'s archived course can't be read", e);
        }
        j.steps.add(new Step(j.half, null, BuildJob.Mode.SCAN));
        j.stage = Stage.CONVERGE;
    }

    /**
     * The recalled course goes live in its Classics slot: one database write. Its tag is the
     * ORIGINAL edition's (slot, edition, seed, star times), so its board is the original board and
     * its first-finish reward the original's; only its half, its plan hash (the blocks here) and the
     * recall differ. No archive row: it is an old edition, not a new one.
     */
    private void flipRecall(Job j) {
        SlotState s = j.slot;
        ClassicWant w = j.want;
        GenArchiveDao.Row src = j.source;
        Slots.Def orig = Slots.of(src.slot());
        long now = host.now();
        PlannedCourse pc = j.plan.course();
        long ref = pc instanceof PlannedTrial t ? t.refMs() : 0;
        long gold = src.goldMs();
        long silver = src.silverMs();
        if (w.remade() && !orig.golf()) {
            DailySettings.Stars factors = host.settings().stars();
            gold = Stars.threshold(ref, factors.gold(starTier(orig, src.tierOrMix())));
            silver = Stars.threshold(ref, factors.silver(starTier(orig, src.tierOrMix())));
        }
        List<Integer> attempts = pc instanceof PlannedGolf g ? g.attempts() : List.of();
        List<List<Putt>> witness = pc instanceof PlannedGolf g ? g.witness() : List.of();
        GenTag.Recall recall = new GenTag.Recall(s.def.id(), w.from(), edition().day(w.from()));
        GenTag tag = new GenTag(src.slot(), orig.generator(), j.plan.algo(), src.day(), j.reroll, src.seed(), j.half,
                j.plan.hash(), ref, gold, silver, attempts, witness, now, j.cadence, recall);
        String name = GenCopy.classicRowName(src.name(), j.cadence, src.day(), w.remade());
        int rev;
        try {
            GamesDao.CourseRow old = host.store().course(s.def.id());
            String taken = Regions.takenByHand(s.def, old);
            if (taken != null) {
                s.problem = taken;
                fail(j, taken);
                return;
            }
            rev = host.store().flip(row(s.def, s.world, pc, tag, old, now, name), Map.of());
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: the recall into " + s.def.id() + " failed", e);
            fail(j, "the database refused the course (" + e.getMessage() + ")");
            return;
        }
        end(j);
        GenTag before = s.live;
        boolean beforeStood = before != null && s.verified;
        s.previous = beforeStood && before.half() != tag.half() ? before : s.previous != null && !s.clearing
                && s.previous.half() != tag.half() ? s.previous : null;
        s.live = tag;
        s.rev = rev;
        s.liveMix = src.tierOrMix();
        s.verified = true;
        s.healFailed = false;
        s.clearing = false;
        s.oldDirty = before != null || s.bothDirty || s.previous != null;
        s.bothDirty = false;
        s.tries = 0;
        s.lastError = null;
        s.builtAt = now;
        s.prior = null;
        s.lastLine = lastLine(j);
        host.coursesChanged(s.def.game());
        String until = w.until() > 0 ? " until " + GenCopy.whenDated(w.until(), host.zone()) : " until it is replaced"
                + " or unrecalled";
        host.logger().info("Fresh Courses: " + src.code() + " (" + src.slot() + " " + src.edition() + ")"
                + (w.remade() ? " (re-made)" : "") + " is back in " + s.def.id() + " (half " + j.half + ")" + until
                + ".");
        j.report.accept("&a" + src.code() + " is back: &f" + GenCopy.classicName(src.name(), j.cadence, src.day(),
                w.remade()) + "&a, " + until.trim() + ". &7Play it: &e/hcm play " + s.def.id());
    }

    // ---- the archive ------------------------------------------------------------------------------------

    /** The archive row for an edition going live at a flip (its code is handed out by the store). */
    private GenArchiveDao.Row archiveEntry(Slots.Def def, GenTag tag, byte[] plan, String mix, long now) {
        return new GenArchiveDao.Row(def.id(), tag.editionKey(), null, 0, tag.day(), tag.seed(),
                tag.generator() + "/" + tag.algo(), def.kind(), mix == null ? "" : mix,
                GenCopy.slotName(def, tag.cadence()), now, null, plan, tag.goldMs(), tag.silverMs(), now, null);
    }

    /** A plan as the archive keeps it; {@code null} (the row reads as unreadable) if it can't be written. */
    private byte[] encode(Plan plan) {
        try {
            return plan == null ? null : PlanCodec.encode(plan);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: a plan couldn't be archived", e);
            return null;
        }
    }

    /** The archived editions recalled into a Classics slot now ({@code slot|edition}): never pruned. */
    private Set<String> recalledNow() {
        Set<String> out = new HashSet<>();
        for (SlotState s : slots.values()) {
            if (!s.classic) {
                continue;
            }
            if (s.want != null) {
                out.add(s.want.slot() + "|" + s.want.edition());
            }
            if (s.live != null) {
                out.add(s.live.slot() + "|" + s.live.editionKey());
            }
            if (s.previous != null && !s.clearing) {
                out.add(s.previous.slot() + "|" + s.previous.editionKey());
            }
        }
        return out;
    }

    /**
     * A course's code: {@code HARD-40} for the edition {@code tag} names (a recalled course's is its
     * original edition's), or {@code null} while it isn't archived (an edition from before the
     * archive).
     */
    public String code(GenTag tag) {
        if (tag == null) {
            return null;
        }
        String k = tag.slot() + "|" + tag.editionKey();
        String c = codes.get(k);
        if (c != null) {
            return c;
        }
        try {
            GenArchiveDao.Row row = host.store().edition(tag.slot(), tag.editionKey());
            if (row != null) {
                codes.put(k, row.code());
                return row.code();
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.FINE, "Fresh Courses: a course code couldn't be read", e);
        }
        return null;
    }

    /**
     * What the website shows for a slot's live course ({@code "fresh":{...}}), or {@code null} when it
     * has none or it isn't archived: its code, short seed, when it went up, when it changes next
     * (left out while pinned forever) and the cadence.
     */
    public FreshFeed.Fresh fresh(String slotId) {
        SlotState s = slots.get(slotId);
        if (s == null || s.classic || s.live == null || !s.verified || !s.on()) {
            return null;
        }
        try {
            GenArchiveDao.Row row = host.store().edition(s.def.id(), s.live.editionKey());
            if (row == null) {
                return null;
            }
            Long to = s.pin != null && s.pin.until() <= 0 ? null : target(s).endsAt();
            return new FreshFeed.Fresh(row.code(), FreshFeed.shortSeed(row.seed()), row.startsAt(), to,
                    s.live.cadence());
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    /**
     * What the website shows for a Classics slot ({@code "classic":{...}}), or {@code null} while it
     * holds nothing open: the recalled course's code and the recall's window ({@code to} left out for
     * "forever").
     */
    public FreshFeed.Classic classic(String classicId) {
        SlotState s = slots.get(classicId == null ? "" : classicId);
        if (s == null || !s.classic || s.live == null || !s.verified || s.want == null || !holds(s, s.want)) {
            return null;
        }
        String c = code(s.live);
        return c == null ? null : new FreshFeed.Classic(c, s.want.from(), s.want.until() > 0 ? s.want.until() : null);
    }

    /**
     * The website's {@code freshHistory}: every archived edition that has been live, at most
     * {@code feed_history} per slot, newest first. Names only when {@code showNames}.
     */
    public List<FreshFeed.Entry> freshHistory(boolean showNames) {
        int per = host.settings().archive().feedHistory();
        List<GenArchiveDao.Row> rows = new ArrayList<>();
        try {
            for (Slots.Def d : Slots.ALL) {
                rows.addAll(host.store().editions(d.id(), 0, Math.max(1, per)));
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: the archive couldn't be read for the website", e);
            return List.of();
        }
        Map<String, FreshFeed.Board> boards = new HashMap<>();
        for (GenArchiveDao.Row r : rows) {
            Slots.Def d = Slots.of(r.slot());
            String game = d == null ? Slots.GAME_TRIALS : d.game();
            try {
                GenArchiveDao.BoardStats st = host.store().boardStats(game, r.board());
                List<GamesDao.ScoreRow> top = host.store().top(game, r.board(), true, FreshFeed.TOP);
                boards.put(r.board(), new FreshFeed.Board(st.plays(), top.isEmpty() ? null : top.get(0), top));
            } catch (SQLException | RuntimeException e) {
                boards.put(r.board(), new FreshFeed.Board(0, null));
            }
        }
        return FreshFeed.history(rows, boards::get, slot -> {
            SlotState s = slots.get(slot);
            return s == null || s.live == null || (s.pin != null && s.pin.until() <= 0) ? null : target(s).endsAt();
        }, recalledNow(), per, host.now(), showNames, host::playerName);
    }

    /** The world the courses are built in ({@code games.fresh.world}, or the first Games world). */
    String genWorld() {
        String w = host.settings().world();
        return w.isBlank() ? first(host.gamesWorlds()) : w;
    }

    /** Move someone to {@code safe_spot} or the world's spawn, and say why (a plot being built or cleared). */
    void moveOut(String world, Person p) {
        double[] spot = host.settings().safeSpot();
        if (spot == null) {
            WorldPort port = host.world(world);
            int[] spawn = port == null ? null : port.spawn();
            if (spawn == null) {
                return;
            }
            spot = new double[]{spawn[0] + 0.5, spawn[1], spawn[2] + 0.5};
        }
        host.move(p.id(), world, spot[0], spot[1], spot[2]);
        host.tell(p.id(), GenCopy.MOVED);
    }

    /** An edition as admins read it from its key and first day ("Mon 28 Sep-Sun 4 Oct"). */
    static String editionName(Edition.Key key, long startDay) {
        return editionName(key == null ? Edition.DAILY : key.cadence(), startDay);
    }

    // ---- admin: history, recall, unrecall, keep, plots (GenOps) -----------------------------------------

    /** History lines come 8 to a page. */
    public static final int HISTORY_PAGE = 8;

    @Override
    public List<String> history(String slotId, int page) {
        List<String> out = new ArrayList<>();
        Slots.Def def = slotId == null ? null : Slots.of(slotId);
        try {
            int total = host.store().editionCount(def == null ? null : def.id());
            int pages = Math.max(1, (total + HISTORY_PAGE - 1) / HISTORY_PAGE);
            int p = Math.max(1, Math.min(page, pages));
            out.add("&6History &7- " + (def == null ? "every course" : def.name()) + " &8(page " + p + " of " + pages
                    + ", " + total + " in all, newest first)");
            if (total == 0) {
                out.add("&7Nothing archived yet: every set is kept here from the moment it goes up.");
                return out;
            }
            for (GenArchiveDao.Row r : host.store().editions(def == null ? null : def.id(), (p - 1) * HISTORY_PAGE,
                    HISTORY_PAGE)) {
                out.add(historyLine(r, def == null));
            }
            if (p < pages) {
                out.add("&7More: &e/hcm games gen history " + (def == null ? "all" : def.id()) + " " + (p + 1));
            }
            out.add("&7One course in full: &e/hcm games gen history <code>&7; bring one back: &e/hcm games gen recall"
                    + " <code>");
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: the history couldn't be read", e);
            out.add("&cCouldn't reach the database - see the console.");
        }
        return out;
    }

    /** One archived edition, one line: code, dates, short seed, record, plays and whether it is kept or back now. */
    private String historyLine(GenArchiveDao.Row r, boolean named) {
        Slots.Def d = Slots.of(r.slot());
        String game = d == null ? Slots.GAME_TRIALS : d.game();
        String record = "&8no finish yet";
        long plays = 0;
        try {
            List<GamesDao.ScoreRow> top = host.store().top(game, r.board(), true, 1);
            if (!top.isEmpty()) {
                record = "&7record &f" + score(game, top.get(0).score()) + " &7by &f" + host.playerName(top.get(0)
                        .player());
            }
            plays = host.store().boardStats(game, r.board()).plays();
        } catch (SQLException | RuntimeException e) {
            record = "&8record unknown";
        }
        return "&f" + r.code() + (named ? " &7" + r.name() : "") + " &7" + dates(r) + " &8seed "
                + FreshFeed.shortSeed(r.seed()) + " " + record + " &7- " + plays + " play" + (plays == 1 ? "" : "s")
                + flags(r);
    }

    private String flags(GenArchiveDao.Row r) {
        StringBuilder b = new StringBuilder();
        if (r.live()) {
            b.append(" &e(up now)");
        }
        if (recalledNow().contains(r.slot() + "|" + r.edition())) {
            b.append(" &b(recalled now)");
        }
        if (r.keptAs() != null) {
            b.append(" &a(kept as ").append(r.keptAs()).append(")");
        }
        return b.toString();
    }

    /** "Mon 5 Oct - Mon 12 Oct", or "Mon 5 Oct - now". */
    private String dates(GenArchiveDao.Row r) {
        return DATE.format(Instant.ofEpochMilli(r.startsAt()).atZone(host.zone())) + " - " + (r.endsAt() == null
                ? "now" : DATE.format(Instant.ofEpochMilli(r.endsAt()).atZone(host.zone())));
    }

    /** A board score as people read it: a time, or golf strokes. */
    private static String score(String game, long score) {
        return Slots.GAME_GOLF.equals(game) ? score + " stroke" + (score == 1 ? "" : "s")
                : com.dierks.homecraft.games.trial.TrialText.time(score);
    }

    @Override
    public List<String> historyOf(String slotId, GenArgs.Which which) {
        List<String> out = new ArrayList<>();
        String slot = slotId != null ? slotId : which.slot();
        GenArchiveDao.Row r = resolve(slot, which, out::add);
        if (r == null) {
            return out;
        }
        Slots.Def d = Slots.of(r.slot());
        String game = d == null ? Slots.GAME_TRIALS : d.game();
        Edition.Key key = Edition.Key.parse(r.edition());
        out.add("&6" + r.code() + " &7- &f" + r.name() + " &7(" + editionName(key, r.day()) + ", edition "
                + r.edition() + ")" + flags(r));
        out.add("&7Up " + dates(r) + " · " + r.kind() + " " + r.tierOrMix() + " · seed " + GenSeed.hex(r.seed())
                + " · " + r.algo() + " · built " + GenCopy.whenDated(r.builtAt(), host.zone()));
        PlanCodec.Read read = PlanCodec.decode(r.plan());
        out.add(read.ok() ? "&7Plan: " + read.plan().ops().size() + " blocks, hash " + read.plan().hash()
                + " &8(it can be brought back exactly)" : "&cPlan: can't be read (" + read.problem()
                + ") &7- it can be made again from its seed: recall ... seed:" + GenSeed.hex(r.seed()));
        try {
            GenArchiveDao.BoardStats st = host.store().boardStats(game, r.board());
            out.add("&7" + st.players() + " player" + (st.players() == 1 ? "" : "s") + ", " + st.plays() + " play"
                    + (st.plays() == 1 ? "" : "s") + (st.players() > 0 ? ". Top 5:" : "."));
            int rank = 0;
            for (GamesDao.ScoreRow row : host.store().top(game, r.board(), true, 5)) {
                rank++;
                out.add("&f  " + rank + ". " + host.playerName(row.player()) + " &7" + score(game, row.score()));
            }
        } catch (SQLException | RuntimeException e) {
            out.add("&cIts board couldn't be read.");
        }
        return out;
    }

    /**
     * The archived edition an admin named, with its plan; {@code null} after telling them why not.
     * {@code last} is the newest edition before the one up now; a date is the one up that day; a
     * seed must match exactly one of the slot's editions.
     */
    private GenArchiveDao.Row resolve(String slot, GenArgs.Which w, Consumer<String> report) {
        Slots.Def def = slot == null ? null : Slots.of(slot);
        try {
            GenArchiveDao.Row row = switch (w.how()) {
                case CODE -> host.store().editionByCode(w.text());
                case NUMBER -> def == null ? null : host.store().editionByCode(CourseCode.format(def.id(), w.n()));
                case KEY -> def == null ? null : host.store().edition(def.id(), w.text());
                case LAST -> {
                    if (def == null) {
                        yield null;
                    }
                    List<GenArchiveDao.Row> two = host.store().editions(def.id(), 0, 2);
                    yield two.isEmpty() ? null : two.get(0).live() ? (two.size() > 1 ? two.get(1) : null) : two.get(0);
                }
                case CURRENT -> {
                    if (def == null) {
                        yield null;
                    }
                    List<GenArchiveDao.Row> one = host.store().editions(def.id(), 0, 1);
                    yield one.isEmpty() || !one.get(0).live() ? null : one.get(0);
                }
                case DATE -> {
                    if (def == null) {
                        yield null;
                    }
                    long from = w.date().atStartOfDay(host.zone()).toInstant().toEpochMilli();
                    long to = w.date().plusDays(1).atStartOfDay(host.zone()).toInstant().toEpochMilli() - 1;
                    yield host.store().editionLive(def.id(), from, to);
                }
                case SEED -> {
                    if (def == null) {
                        yield null;
                    }
                    List<GenArchiveDao.Row> match = host.store().editionsBySeed(def.id(), w.text());
                    if (match.size() > 1) {
                        report.accept("&c" + match.size() + " of " + def.name() + "'s courses have a seed starting "
                                + w.text() + ". &7Give more of its digits.");
                        yield null;
                    }
                    yield match.isEmpty() ? null : match.get(0);
                }
            };
            if (row == null) {
                if (w.how() == GenArgs.How.SEED && def != null) {
                    report.accept("&cNo archived " + def.name() + " has a seed starting " + w.text()
                            + ". &7/hcm games gen history " + def.id());
                } else if (w.how() != GenArgs.How.SEED) {
                    report.accept("&cNo archived course " + (def == null ? "" : "of " + def.name() + " ") + "is "
                            + w.typed() + ". &7/hcm games gen history " + (def == null ? "all" : def.id()));
                }
                return null;
            }
            if (def != null && !row.slot().equals(def.id())) {
                report.accept("&c" + row.code() + " is " + row.name() + ", not " + def.name() + ".");
                return null;
            }
            return row.plan() != null ? row : host.store().edition(row.slot(), row.edition());
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: the archive couldn't be read", e);
            report.accept("&cCouldn't reach the database - see the console.");
            return null;
        }
    }

    @Override
    public void recall(String classicWord, String slotId, GenArgs.Which which, int days, boolean confirm,
                       Consumer<String> report) {
        if (!running || readyAt < 0) {
            report.accept("&cFresh Courses is still starting; try in a moment.");
            return;
        }
        String soon = restartSoon();
        if (soon != null) {
            report.accept(soon);
            return;
        }
        String slot = slotId != null ? slotId : which.slot();
        if (slot == null) {
            report.accept("&cWhich course? A course code like HARD-40, or a course and which one.");
            return;
        }
        GenArchiveDao.Row row = resolve(slot, which, report);
        if (row == null) {
            return;
        }
        if (row.live()) {
            // By its code or its date as much as by "current": the same course open twice would
            // give its stars twice in one week.
            report.accept("&c" + row.code() + " is still " + row.name() + "'s current course. &7Recall an older one:"
                    + " last, a number or a code (&e/hcm games gen history " + row.slot() + "&7).");
            return;
        }
        Slots.Def orig = Slots.of(row.slot());
        Slots.Def fits = Slots.classicFor(orig);
        Slots.Def classic = classicWord != null ? Slots.classicByWord(classicWord) : fits;
        if (fits == null) {
            report.accept("&c" + row.name() + " has no Classics slot. &7Keep it for good instead: &e/hcm games gen keep "
                    + row.code() + " <new-id> [name]");
            return;
        }
        if (classic != fits) {
            report.accept("&c" + classic.name() + " can't hold " + row.name() + ". &7It goes into " + fits.id() + ".");
            return;
        }
        SlotState s = slots.get(classic.id());
        if (!s.on()) {
            report.accept("&c" + classic.name() + " is off" + (s.problem == null ? "." : ": &7" + s.problem));
            return;
        }
        boolean remade = which.remade();
        if (!remade) {
            PlanCodec.Read read = PlanCodec.decode(row.plan());
            if (!read.ok()) {
                report.accept("&c" + row.code() + "'s stored plan can't be read (" + read.problem() + ").");
                report.accept("&7Make it again from its seed with today's generator (marked re-made): &e/hcm games gen"
                        + " recall " + classic.id() + " " + row.slot() + " seed:" + GenSeed.hex(row.seed()));
                return;
            }
        }
        long now = host.now();
        int d = days == GenArgs.FOREVER ? 0 : days == GenArgs.DAYS_DEFAULT ? host.settings().archive().classicDays()
                : days;
        long until = days == GenArgs.FOREVER ? 0 : now + d * 86_400_000L;
        String span = until == 0 ? "until it is replaced or unrecalled" : "until " + GenCopy.whenDated(until,
                host.zone());
        if (!remade && s.want != null && !s.want.remade() && s.want.slot().equals(row.slot())
                && s.want.edition().equals(row.edition())) {
            ClassicWant w = s.want.withUntil(until);
            if (!storeWant(s, w, report)) {
                return;
            }
            s.want = w;
            report.accept("&a" + row.code() + " stays in " + classic.name() + " " + span + ".");
            return;
        }
        if (busyWith(s)) {
            report.accept("&c" + classic.name() + " is being built right now; try when it's done.");
            return;
        }
        int on = playing(s);
        if (s.live != null && on > 0 && !confirm) {
            report.accept("&e" + on + " player" + (on == 1 ? " is" : "s are") + " on " + classic.name() + " now. &7They"
                    + " finish on the course they started; the new one opens beside it.");
            report.accept("&7Add &econfirm &7at the end to do it.");
            return;
        }
        ClassicWant w = new ClassicWant(row.slot(), row.edition(), now, until, remade);
        if (!storeWant(s, w, report)) {
            return;
        }
        s.prior = s.want != null && holds(s, s.want) ? s.want : null;
        s.want = w;
        s.tries = 0;
        s.lastError = null;
        s.recallReport = report;
        host.logger().info("Fresh Courses: " + row.code() + " (" + row.slot() + " " + row.edition() + ")"
                + (remade ? " (re-made)" : "") + " is recalled into " + classic.id() + " " + span + ".");
        report.accept("&7Bringing back &f" + row.code() + " &7(" + row.name() + ", " + editionName(Edition.Key.parse(
                row.edition()), row.day()) + ")" + (remade ? " re-made from its seed" : "") + " into " + classic.name()
                + " " + span + ". &7It opens once it is built and checked; its old records are the ones to beat.");
    }

    private boolean storeWant(SlotState s, ClassicWant w, Consumer<String> report) {
        try {
            host.store().meta(GenAdminKeys.recall(s.def.id()), w == null ? null : w.text());
            return true;
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not store a recall", e);
            report.accept("&cCouldn't reach the database - see the console.");
            return false;
        }
    }

    @Override
    public void unrecall(String classicWord, boolean confirm, Consumer<String> report) {
        Slots.Def c = Slots.classicByWord(classicWord);
        SlotState s = c == null ? null : slots.get(c.id());
        if (s == null) {
            report.accept("&cNo Classics slot called '" + classicWord + "'. &7" + String.join(", ", Slots.classicIds()));
            return;
        }
        if (s.want == null && s.live == null) {
            report.accept("&7" + c.name() + " is already empty.");
            return;
        }
        int on = playing(s);
        if (on > 0 && !confirm) {
            report.accept("&e" + on + " player" + (on == 1 ? " is" : "s are") + " on " + c.name() + " now. &7They"
                    + " finish; its halves are cleared once nobody is on them.");
            report.accept("&7Add &econfirm &7at the end to do it.");
            return;
        }
        if (!storeWant(s, null, report)) {
            return;
        }
        s.want = null;
        s.prior = null;
        if (s.live != null) {
            closeClassic(s, "an admin closed it");
        } else {
            forgetRecall(s, "it was unrecalled");
        }
        report.accept("&a" + c.name() + " is closed. &7Its halves are cleared once nobody is on them.");
    }

    @Override
    public void keep(String slotId, GenArgs.Which which, String id, String name, boolean freshBoard, boolean confirm,
                     Consumer<String> report) {
        if (!running || readyAt < 0) {
            report.accept("&cFresh Courses is still starting; try in a moment.");
            return;
        }
        String soon = restartSoon();
        if (soon != null) {
            report.accept(soon);
            return;
        }
        String slot = slotId != null ? slotId : which.slot();
        GenArchiveDao.Row row = slot == null ? null : resolve(slot, which, report);
        if (row == null) {
            if (slot == null) {
                report.accept("&cWhich course? A course code like HARD-40, or a course and which one.");
            }
            return;
        }
        if (!which.remade()) {
            PlanCodec.Read read = PlanCodec.decode(row.plan());
            if (!read.ok()) {
                report.accept("&c" + row.code() + "'s stored plan can't be read (" + read.problem() + "), so it can't"
                        + " be kept as it was.");
                report.accept("&7Make it again from its seed with today's generator (marked re-made): &e/hcm games gen"
                        + " keep " + row.slot() + " seed:" + GenSeed.hex(row.seed()) + " " + id);
                return;
            }
        }
        keeper.keep(row, which.remade(), id, name, freshBoard, confirm, report);
    }

    @Override
    public List<String> plots() {
        return keeper.plots();
    }

    @Override
    public void clearPlot(int n, boolean confirm, Consumer<String> report) {
        keeper.clearPlot(n, confirm, report);
    }

    @Override
    public void claimPlot(int n, boolean confirm, Consumer<String> report) {
        keeper.claimPlot(n, confirm, report);
    }

    @Override
    public List<Integer> usedPlots() {
        return keeper.usedPlots();
    }

    /** For tests: the keep engine. */
    KeepService keeper() {
        return keeper;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private void warnOnce(SlotState s, String line) {
        if (said.add(s.def.id() + "|" + line)) {
            host.logger().warning(line);
        }
    }

    private String clock(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis).atZone(host.zone()));
    }

    static String date(long day) {
        return DATE.format(LocalDate.ofEpochDay(day));
    }

    /** An edition as admins read it: "Tue 29 Sep" (a day), or "Mon 28 Sep-Sun 4 Oct". */
    static String editionName(int cadence, long startDay) {
        return cadence <= Edition.DAILY ? date(startDay) : date(startDay) + "-" + date(startDay + cadence - 1);
    }

    private static String first(List<String> worlds) {
        return worlds == null || worlds.isEmpty() || worlds.get(0) == null ? "" : worlds.get(0).trim();
    }

    private static String pad(String s, int n) {
        String t = s == null ? "" : s;
        return t.length() >= n ? t + " " : t + " ".repeat(n - t.length());
    }

    /** For tests: the running job's kind, or {@code null}. */
    Kind jobKind() {
        return job == null ? null : job.kind;
    }

    /** For tests and status: a slot's state. */
    SlotState slot(String id) {
        return slots.get(id);
    }
}
