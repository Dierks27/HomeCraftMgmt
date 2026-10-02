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
import com.dierks.homecraft.games.gen.api.Pools;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.SignText;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import com.dierks.homecraft.games.gen.dropper.DropRules;
import com.dierks.homecraft.games.gen.dropper.DropperPlanner;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.parkour.ParkourPlanner;
import com.dierks.homecraft.games.gen.rings.RingsPlanner;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.LiveBlocks;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
 * crash between the database's flip and the world's save (§3.5). The smallest halves are checked first,
 * and a big half (the Mountain Run v2's 1,200 chunks) that the world was saved right after its last full
 * check ({@link GenAdminKeys#onDisk}) is checked by a sample of its chunks, opened, and checked whole later
 * in idle time with the course open; a spare half known to be empty on disk isn't loaded at all (F12). A
 * run keeps counting while the
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
 * only plans are made elsewhere, and they come back through {@link #tick}'s inbox. So does all the
 * work the size of a plan (MOUNTAIN-V2-SPEC F11: a Mountain Run v2 is 350,000 blocks): its checks
 * ({@link PlanCheck#problems}), its archive row and its build index are made with it on the planner
 * thread, a big plan that comes another way (a promote, a recall) has its index and row made there
 * before they are needed, and an admin's {@code history} or {@code keep} reads a big archived row
 * there too; the main thread only stores and builds what comes back.
 */
public final class GenService implements GeneratedCourses, GenOps {

    /** A plan still running after this long is given up (a failed try, never a different plan). */
    public static final long PLAN_KILL_MS = 120_000L;
    /**
     * A plan with more blocks than this (a Mountain Run v2: 350,000) never has plan-sized work done on the
     * main thread (MOUNTAIN-V2-SPEC F11): its checks, archive row and build index are made on the planner
     * thread. A smaller one's index and row take a millisecond or two and are made in place, as always.
     */
    static final int OFF_MAIN_OPS = 20_000;
    /** A stored plan bigger than this (gzipped) is read on the planner thread for an admin command (F11). */
    static final int OFF_MAIN_BYTES = 16 * 1024;
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
        UNRECALL,
        /**
         * Empty an OLD area the slot left behind ({@code gen.<slot>.old}): both halves of an old claim, at the
         * sizes it recorded, of Fresh Courses' own blocks only, water first; then mark the claim emptied, and
         * forget it once the world has been saved or a later start finds it still empty (F09)
         * (V4-DECISIONS "One move mechanism"; {@link OldAreas}).
         */
        RETIRE
    }

    /** Where a job is. */
    enum Stage {
        START, PLANNING, SCANNING, EVACUATE, CONVERGE
    }

    /**
     * One pass of a job over one half: the slot's half {@code which}, or {@code box} when it is given (an
     * old area's half, RETIRE: not one of the slot's halves now).
     */
    private record Step(char which, Plan plan, BuildJob.Mode mode, Box box,
                        java.util.function.BiPredicate<Integer, Integer> only) {

        Step(char which, Plan plan, BuildJob.Mode mode, Box box) {
            this(which, plan, mode, box, null);
        }

        Step(char which, Plan plan, BuildJob.Mode mode) {
            this(which, plan, mode, null, null);
        }
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
        /** The {@code trials.fall_depth} its plan was made with (fix2-D: a preview keeps it for choose). */
        int fallDepth;
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
        /** RETIRE: the old claim it empties, and its world (the claim's own, not the slot's now). */
        String retire;
        String world;
        /** RETIRE: the blocks it left because they aren't Fresh Courses', and the first few. */
        long left;
        List<String> leftAt = List.of();
        /**
         * A BUILD that found its region empty and claimed it itself: nothing of the layout before it (if any)
         * stands in this region, so its flip leaves nothing to clear (GOLF-V4-SPEC §5.2 step 4, RePlot).
         */
        boolean claimedHere;
        /**
         * The plan's archive row, written on the planner thread (F11): a BUILD's with its plan, a big
         * PROMOTE's as it starts; {@code archivedReady} once it is (a row that couldn't be written is
         * {@code null}: it reads as unreadable, as before).
         */
        byte[] archived;
        boolean archivedReady;
        /** The next step's build index, made on the planner thread (F11), or {@code null}. */
        BuildIndex.Prepared prepared;
        /** Since when a step waits for its index from the planner thread, or -1. */
        long preparing = -1;
        /**
         * F12: a HEAL at a start of a big half known to be on disk as verified: a SCAN of a sample of its chunks
         * first; the course opens on a clean one, and the whole half is checked later ({@link #background}).
         */
        boolean sampled;
        /** F12: the whole-half check of a course opened on a sample: it runs with the course open. */
        boolean background;
        /**
         * F12 (ENG07): the job's steps so far only read its half against the plan (a {@link #sampled} check, or the
         * {@link #background} whole-half one): nothing is written and the half's on-disk fact stays, unless the
         * read finds a difference ({@link #differs}).
         */
        boolean reading;

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
    /** WorldSaveEvents seen this run, by world (lower case): when an emptied area or a half is known to be on disk. */
    private final Map<String, Integer> saves = new HashMap<>();
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
     * The flow-only boxes {world, {@link Box}}: every plot of a kept Dropper or golf course and a plot
     * job's in flight ({@link #wetPlots}), whose water must never flow out though nothing else there
     * is guarded.
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
    /** ...and no fluid flowing out of {@link #wetPlots} or a keep in flight that may hold water. */
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
     * Where an old claim's halves are, for the guard and for RETIRE. Always {@link #RECORDED}: the sizes the
     * claim recorded. The engine takes it as a parameter only so the mutation proof
     * ({@code RetireMutationTest}) can give it the old bug (today's def sizes) and see the scenarios fail.
     */
    @FunctionalInterface
    interface OldHalves {
        List<Box> of(Slots.Def def, String claim);
    }

    /** An old claim's halves at the sizes, origin and gap it RECORDED (GOLF-V4-SPEC §5.2 step 2, the fix). */
    static final OldHalves RECORDED = (def, claim) -> Regions.claimHalves(claim);

    private final OldHalves oldHalves;

    /**
     * @param planners each generator's planner by its id ({@code parkour}, {@code rings}, {@code golf},
     *                 {@code boat}); a slot whose planner is missing never builds
     */
    public GenService(GenHost host, Map<String, Planner> planners) {
        this(host, planners, RECORDED);
    }

    /** The engine with {@code oldHalves} for where an old claim stands (only the mutation proof passes another). */
    GenService(GenHost host, Map<String, Planner> planners, OldHalves oldHalves) {
        this.host = host;
        this.oldHalves = oldHalves;
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
        List<Job> heals = new ArrayList<>();
        for (SlotState s : slots.values()) {
            if (s.classic && s.live == null && s.claimed) {
                s.bothDirty = true; // a recall or a clearing may have stopped halfway
            }
            if (s.live == null || !s.on()) {
                continue;
            }
            if (s.claimed) {
                // unknown after a restart, so emptied once nothing else is due; unless it was known to be empty
                // when the world was last saved (F12): then nothing is loaded there
                s.oldDirty = s.classic || !EMPTY.equals(s.onDisk.get(s.live.otherHalf()));
                Job heal = new Job(Kind.HEAL, s, null);
                // F12: a big half saved right after its last full check is checked by a sample now, the rest later;
                // only when today's planner can make its plan again (ENG00: an older planner's layout has no plan
                // to compare a sample with, so it gets the quick structure check, as every start gave it)
                Planner p = planners.get(s.def.generator());
                heal.sampled = !s.classic && p != null && p.algo() == s.live.algo()
                        && s.half(s.live.half()).chunkCount() > BIG_HALF_CHUNKS
                        && planFact(s.live.planHash()).equals(s.onDisk.get(s.live.half()));
                heals.add(heal);
            } else {
                unclaimedLive(s);
            }
        }
        // F12: the smallest halves first, so a Mountain Run's check never keeps the droppers closed
        heals.sort(java.util.Comparator.comparingInt(h -> h.slot.half(h.slot.live.half()).chunkCount()));
        queue.addAll(heals);
        for (SlotState s : slots.values()) {
            restoreChosen(s);
        }
        keeper.worldsReady();
    }

    /**
     * WP-ADM: a slot with a course chosen for the next set gets that course back in its spare half
     * after a restart, as a preview (the preview itself isn't stored, and the boot would otherwise
     * empty that half as an old one): it converges from what stands there, which is nothing to write
     * when the chosen preview is still there, the admin can try it again, and the change's build finds
     * its blocks already right. Queued after the boot checks.
     */
    private void restoreChosen(SlotState s) {
        // Only a pick still to come for the next set, at the settings it was tried with (fix2-D): one
        // up now, or no longer the next set's (the schedule moved), holds nothing.
        GenScheduler.Choice c = s.classic ? null : chosenWaiting(s);
        Planner p = planners.get(s.def.generator());
        if (c == null || p == null || c.algo() != p.algo() || !s.on() || !s.claimed || s.live == null) {
            return;
        }
        NextSet n = nextSet(s);
        Job j = new Job(Kind.PREVIEW, s, null);
        j.day = n.day();
        j.cadence = n.cadence();
        j.reroll = 0;
        j.seed = c.seed();
        j.mix = s.mix;
        queue.add(j);
        host.logger().info("Fresh Courses: " + s.def.id() + "'s chosen course for " + editionName(n.cadence(), n.day())
                + " (seed " + GenSeed.hex(c.seed()) + ") is checked in its spare half again.");
    }

    /**
     * A live row whose region isn't claimed (it was cleared, or it moved): nothing there is vouched
     * for, so it is never healed in place. It counts as a failed check, and the next build scans
     * the region (and claims it only when it is empty) before building anew.
     */
    private void unclaimedLive(SlotState s) {
        s.verified = false;
        s.healFailed = true;
        if (movingByVersion(s)) {
            // ENG-R3-01: the move this version makes (announced as news in refresh), not a fault
            sayOnce(s, "Fresh Courses: " + s.def.id() + "'s course from before this version stays closed in its old"
                    + " area; a new one is built in its new area once that is checked.");
            return;
        }
        warnOnce(s, "Fresh Courses: " + s.def.id() + "'s live course isn't in its claimed region "
                + "- a new one will be built once the area is checked");
    }

    /**
     * ENG-R3-01: whether {@code s} is moving to the area this version gave it: not claimed there yet, with an old
     * claim of another size on record (the "area changed with this version" move, its old area emptied by itself).
     * Its course is rebuilt there as expected, which the check shows as on its way, not as a fault.
     */
    private static boolean movingByVersion(SlotState s) {
        return !s.claimed && s.old.stream().anyMatch(c -> Regions.resized(s.def, c));
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
        // An old area a region waits for (the same slot's, resized in place, or another's next to a new
        // spot) is emptied before anything is built there (MOUNTAIN-V2-SPEC §11.2 "Ordering").
        Job first = now >= readyAt ? nextRetire(true) : null;
        if (first != null) {
            begin(first);
            return;
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
                    String old = oldInTheWay(s);
                    if (old != null) {
                        s.waiting = old; // emptied first (above), or held: status and the check say why
                        continue;
                    }
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
                String old = oldInTheWay(s);
                s.waiting = old != null ? old : recallWait(s, now, hold);
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
        // F12: the whole half of a course opened at this start on a sample, with the course open
        for (SlotState s : slots.values()) {
            if (now >= readyAt && s.fullCheckDue && s.on() && s.verified && s.live != null && s.claimed) {
                Job j = new Job(Kind.HEAL, s, null);
                j.background = true;
                begin(j);
                return;
            }
        }
        // Any other old area once nothing is due to be built: so a moved course (Golf of the Week at the
        // update) is down for as short a time as can be, and its old area goes right after its new one is up.
        Job retire = now >= readyAt ? nextRetire(false) : null;
        if (retire != null) {
            begin(retire);
            return;
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
        GenScheduler.Choice chosen = chosenNow(s);
        return chosen != null ? chosen.pin() : ownPin(s, target(s).start());
    }

    /**
     * The slot's own pin ({@code pin}, not a pick) if it applies to the set that starts on {@code day},
     * else {@code null}. fix2-D (D5): what holds once a pick is let go.
     */
    private GenScheduler.Pin ownPin(SlotState s, long day) {
        Planner p = planners.get(s.def.generator());
        return s.pin != null && p != null && s.pin.appliesOn(day, p.algo()) ? s.pin : null;
    }

    /** fix2-D (D5): what the set starting {@code day} gets with no pick: its pinned course, or its own new one. */
    private String ownCourse(SlotState s, long day) {
        GenScheduler.Pin pin = ownPin(s, day);
        return pin == null ? "its own new course" : "its pinned course (seed " + GenSeed.hex(pin.seed()) + ")";
    }

    /**
     * WP-ADM: the admin's choice when it is for the edition the slot should show now (the next set
     * has come), else {@code null}. Over the slot's own pin for that one set, so the build is an
     * ordinary pinned one: no flip path of its own (never a restamp: round 2, G2 #2,
     * {@link GenScheduler.Pin#pick}).
     *
     * <p>fix2-D: "for the edition" is the same first day and the same length (D1: around a cadence
     * change the set kept up can start on, or after, the day of a pick made for the set that follows
     * it); and until its course stands it must still fit the settings it was tried with (D0), so
     * what the change builds is the course that was tried. Once it stands, a tier or fall depth
     * change is for the next build, as on any course.
     */
    private GenScheduler.Choice chosenNow(SlotState s) {
        GenScheduler.Choice c = s.chosen;
        Planner p = planners.get(s.def.generator());
        if (c == null || p == null || c.algo() != p.algo()) {
            return null;
        }
        GenScheduler.Target t = target(s);
        if (!c.isSet(t.start(), t.cadence())) {
            return null;
        }
        boolean stands = t.holds(s.live) && s.live.seed() == c.seed();
        return stands || fits(s, c) ? c : null;
    }

    /**
     * WP-ADM: a choice that is still to come, else {@code null}. fix2-D: only one for the next set
     * (its first day and length: D1, D2) that still fits the settings it was tried with (D0);
     * anything else is dropped at the next check ({@link #chosenUpkeep}), never shown as waiting.
     */
    private GenScheduler.Choice chosenWaiting(SlotState s) {
        GenScheduler.Choice c = s.chosen;
        if (c == null) {
            return null;
        }
        GenScheduler.Target t = target(s);
        NextSet n = nextSet(s);
        return !c.isSet(t.start(), t.cadence()) && c.isSet(n.day(), n.cadence()) && fits(s, c) ? c : null;
    }

    /** fix2-D: whether a pick's course comes out as it was tried, at the settings now (D0). */
    private boolean fits(SlotState s, GenScheduler.Choice c) {
        return c.fits(s.mix, pickDepth(s, host.fallDepth()));
    }

    /**
     * fix2-D: the fall depth a pick of {@code s} keeps and is checked against: only what shapes the
     * layout ({@link ParkourPlanner#designDepth}). Medium and hard parkour are shaped by
     * {@code trials.fall_depth} up to {@link ParkourPlanner#FALL_DESIGN} (a deeper setting makes the
     * same course); easy parkour falls back at a fixed height, and no other planner reads it, so
     * those picks keep 0 and a change of the setting (a global Time Trials one, also for hand-built
     * courses) never drops them.
     */
    private int pickDepth(SlotState s, int depth) {
        return Slots.PARKOUR.equals(s.def.generator()) ? ParkourPlanner.designDepth(s.mix, depth) : 0;
    }

    /** fix2-D: a kept design depth as the owner reads it ("6 or more": any of those makes the course). */
    private static String depthWords(int design) {
        return design >= ParkourPlanner.FALL_DESIGN ? design + " or more" : String.valueOf(design);
    }

    /** fix2-D: the pick's own set, as admins read it. */
    private String pickSet(GenScheduler.Choice c) {
        return editionName(c.cadence() > 0 ? c.cadence() : edition().cadenceDays(), c.from());
    }

    /**
     * WP-ADM: a choice whose set is over is forgotten, with one line (the set after goes back to its
     * own seed); one made for another planner version is said once and not used.
     *
     * <p>fix2-D: so is one that can no longer be the course that was tried, with a warning and a
     * status line: its set is next but the tier or mix, or the fall depth its parkour layout is shaped
     * by ({@link #pickDepth}: not easy's, nor a raise past 6), has changed since
     * (D0: building the seed now would put up a course nobody tried as "the chosen course"); or the
     * schedule moved and its set is no longer the next one (D1, D2: status would promise a set that
     * never comes, or it would go up later as a set of another length). Run at every check once the
     * worlds are up (so before a chosen preview is put back at a boot), and at once by {@code tier}.
     *
     * @return what the admin reads when a pick was just dropped for a change, else {@code null}
     */
    private String chosenUpkeep(SlotState s) {
        GenScheduler.Choice c = s.chosen;
        if (c == null || s.classic) {
            return null;
        }
        GenScheduler.Target t = target(s);
        NextSet n = nextSet(s);
        boolean now = c.isSet(t.start(), t.cadence());
        boolean next = !now && c.isSet(n.day(), n.cadence());
        Planner p = planners.get(s.def.generator());
        if ((now || next) && p != null && c.algo() != p.algo()) {
            warnOnce(s, "Fresh Courses: " + s.def.id() + "'s chosen seed " + GenSeed.hex(c.seed()) + " was made for "
                    + s.def.generator() + " planner v" + c.algo() + " and this is v" + p.algo() + " - the set's own seed"
                    + " is used (/hcm games gen unchoose " + s.def.id() + " forgets it)");
            return null;
        }
        if (now ? chosenNow(s) != null : next && fits(s, c)) {
            return null; // it holds
        }
        boolean over = !now && !next && c.from() <= t.start(); // its set came (or its day passed)
        String why = over ? null : now || next ? misfit(s, c) : "the schedule changed, and the next set is "
                + editionName(n.cadence(), n.day()) + " now";
        try {
            host.store().meta(GenAdminKeys.choose(s.def.id()), null);
        } catch (SQLException e) {
            return null; // kept until the database takes the change; it applies to no set meanwhile
        }
        s.chosen = null;
        String id = s.def.id();
        String set = pickSet(c);
        if (over) {
            host.logger().info("Fresh Courses: " + id + "'s chosen seed " + GenSeed.hex(c.seed()) + " was for " + set
                    + "; each set's own seed is used from now on.");
            return null;
        }
        dropNote(s, new SlotState.DroppedPick(c.seed(), c.from(), c.cadence(), why)); // round 2, G2 #3
        host.logger().warning("Fresh Courses: " + id + "'s pick for " + set + " (seed " + GenSeed.hex(c.seed())
                + ") is dropped: " + why + ". Choose again: /hcm games gen preview " + id + " next, try it, then"
                + " choose.");
        return "&cYour pick for " + set + " (seed " + GenSeed.hex(c.seed()) + ") is dropped: &7" + why
                + ". Build one to try: &e/hcm games gen preview " + id + " next";
    }

    /**
     * Round 2, G2 #3: the note that a pick was dropped ({@code null}: forgotten), in memory and in
     * {@code hcm_meta}, so a restart keeps it; it stays through the flips of the set it was for (the
     * usual drop is at the restart at the change, a minute before that set's own build flips) and goes
     * once that set is over ({@link #droppedUpkeep}) or at the next pick or cancel.
     */
    private void dropNote(SlotState s, SlotState.DroppedPick note) {
        if (s.pickDropped == null && note == null) {
            return;
        }
        s.pickDropped = note;
        try {
            host.store().meta(GenAdminKeys.dropped(s.def.id()), note == null ? null : note.text());
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not store why " + s.def.id() + "'s pick was dropped",
                    e);
        }
    }

    /** Round 2, G2 #3: a dropped pick's note is forgotten once the set it was for is over. */
    private void droppedUpkeep(SlotState s) {
        if (s.pickDropped != null && s.pickDropped.over(target(s).start())) {
            dropNote(s, null);
        }
    }

    /** Round 2, G2 #3: the set a dropped pick was for, as admins read it. */
    private String droppedSet(SlotState.DroppedPick d) {
        return editionName(d.cadence() > 0 ? d.cadence() : edition().cadenceDays(), d.from());
    }

    /** fix2-D: why a pick no longer comes out as it was tried, in the owner's terms. */
    private String misfit(SlotState s, GenScheduler.Choice c) {
        if (c.mix() != null && !c.mix().equals(s.mix)) {
            return "it was tried as " + c.mix() + ", and that set will be " + s.mix + ", so it would come out"
                    + " different";
        }
        return "it was tried with fall_depth " + depthWords(c.fallDepth()) + ", and it is " + host.fallDepth() + " now,"
                + " so it would come out different";
    }

    /** fix2-D: whether {@code day} and {@code cadence} are the next set's, not the one the slot shows now. */
    private boolean isNextSet(SlotState s, long day, int cadence) {
        GenScheduler.Target t = target(s);
        NextSet n = nextSet(s);
        return day == n.day() && cadence == n.cadence() && !(day == t.start() && cadence == t.cadence());
    }

    /** The next set: its first day and length (what {@code plan <course> next} plans for). */
    record NextSet(long day, int cadence) {
    }

    private NextSet nextSet(SlotState s) {
        Edition ed = edition();
        return new NextSet(ed.editionStart(target(s).endsAt()), ed.cadenceDays());
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
        host.coursesChanged(s.def.game());
        if (!s.claimed) {
            // ENG01: never converge a region Fresh Courses doesn't hold (as forgetRecall): the closed course stood
            // in the area it was claimed in before (the update moved it), and nothing of it stands here
            boolean byItself = s.old.stream().anyMatch(c -> Regions.resized(s.def, c));
            host.logger().info("Fresh Courses: " + s.def.id() + " is closed (" + why + "). Its area isn't claimed, so"
                    + " nothing is cleared there: the course stood in its old area" + (byItself
                    ? ", which is emptied by itself." : "."));
            return;
        }
        s.bothDirty = true;
        s.lastClearCheck = 0;
        host.logger().info("Fresh Courses: " + s.def.id() + " is closed (" + why + "); its halves are cleared once"
                + " nobody is on them.");
    }

    private GenScheduler.SlotView view(SlotState s) {
        Planner p = planners.get(s.def.generator());
        GenScheduler.Choice chosen = chosenNow(s); // WP-ADM: the next set's pick, once it has come
        return new GenScheduler.SlotView(s.def.id(), s.on() && p != null, job != null, s.live, !s.healFailed,
                s.liveMix, s.mix, s.reroll, chosen != null ? chosen.pin() : s.pin, p == null ? 0 : p.algo(), s.triesDay,
                s.tries, s.lastTryAt, s.oldDirty, secret(), scheduleSince, style(s));
    }

    /**
     * The Mountain Run v2 style a new layout of {@code s} must have ({@link StyleSeed#want}): Ice Boat's
     * configured {@code style}, or the Winding Road while Race Night is on; {@code null} for the edition's own
     * seed (every other slot, an Ice Boat still in the 0.36 box, a random week with no Race Night).
     */
    private BoatStyle style(SlotState s) {
        DailySettings.SlotConfig c = s.classic ? null : host.settings().slot(s.def.id());
        boolean night;
        try {
            night = host.raceNightOn();
        } catch (RuntimeException e) {
            night = false;
        }
        return StyleSeed.want(s.def, s.half('A'), c == null ? null : c.style(), night);
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
        String configWorld = st.world().isBlank() ? first(host.gamesWorlds()) : st.world();
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
            String world = configWorld;
            int[] origin = c.origin();
            int gap = c.halfGap();
            boolean was = s.unplaced;
            String wasWhy = unplacedWhy(s);
            boolean wasHeld = s.heldWhy != null; // F10: its place was its old claim, never an owner's choice
            s.unplaced = !c.placed();
            s.heldWhy = s.unplaced ? c.held() : null;
            if (s.unplaced && (!was || !wasWhy.equals(unplacedWhy(s)))) {
                vet(s, null); // off at once, and why said once
            } else if (was && !s.unplaced && wasWhy.equals(s.problem)) {
                s.problem = null; // config reads again: the next check says whether anything else is in the way
            }
            if (s.unplaced) {
                // Config can't say where it stands (an origin or half_gap it can't read): it stays where it
                // was claimed, else where it was, so this is never a move (a reroll at a spot nobody chose,
                // the built course left standing), and it is off until config is fixed (SlotState#wanted).
                String claim = meta == null ? null : meta.get(GenAdminKeys.claim(s.def.id()));
                boolean known = Regions.claimOrigin(claim) != null && Regions.claimWorld(claim) != null;
                world = known ? Regions.claimWorld(claim) : s.world;
                origin = known ? Regions.claimOrigin(claim) : s.origin;
                gap = known ? Regions.claimGap(claim) : s.gap;
            }
            if (!s.sameRegion(world, origin, gap) && (s.claimed || s.live != null) && !s.world.isBlank()
                    && moved(s, world, origin, gap, !s.classic && !s.claimed && meta != null
                    && meta.get(GenAdminKeys.claim(s.def.id())) == null, wasHeld && !s.unplaced,
                    meta == null ? null : meta.get(GenAdminKeys.claim(s.def.id()))) && meta != null) {
                meta = new HashMap<>(meta); // an emptied Classic let its old claim go: nothing to remember as wet
                meta.remove(GenAdminKeys.claim(s.def.id()));
            }
            s.world = world;
            s.origin = origin;
            s.gap = gap;
            s.configOn = c.enabled();
            if (s.classic) {
                s.mix = s.def.tierOrMix();
                if (meta != null) {
                    s.want = ClassicWant.parse(meta.get(GenAdminKeys.recall(s.def.id())));
                    s.claimed = Regions.claim(s.def, world, origin, gap)
                            .equals(meta.get(GenAdminKeys.claim(s.def.id())));
                }
                oldRegions(s, meta, world, origin, gap, kept);
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
                s.chosen = GenScheduler.Choice.parse(meta.get(GenAdminKeys.choose(id)));
                s.pickDropped = SlotState.DroppedPick.parse(meta.get(GenAdminKeys.dropped(id))); // round 2, G2 #3
                s.reroll = GenAdminKeys.whole(meta.get(GenAdminKeys.reroll(id, target(s).key())));
                String claim = meta.get(GenAdminKeys.claim(id));
                boolean claimed = Regions.claim(s.def, world, origin, gap).equals(claim);
                if (claim != null && !claimed && s.heldWhy == null) {
                    if (Regions.resized(s.def, claim) && Regions.claimWorld(claim) != null) {
                        // This version changed the slot's size: its old area is emptied by itself (RETIRE).
                        sayOnce(s, "Fresh Courses: " + id + "'s area changed with this version: its halves are "
                                + s.def.sizeX() + " x " + s.def.sizeY() + " x " + s.def.sizeZ() + " now, at "
                                + Regions.describe(s.def, origin, gap) + ". Its old area (" + Regions.describeClaim(claim)
                                + ") is emptied by itself" + (s.def.mayHoldWater() ? ", the " + water(s) + " drained first"
                                : "") + "; the new one is checked before it is used.");
                    } else {
                        // The claim's own sizes, never today's (the old area is where it was built).
                        String where = Regions.claimOrigin(claim) == null ? claim : Regions.describeClaim(claim);
                        warnOnce(s, "Fresh Courses: " + id + " was claimed at another place (" + where + "). "
                                + (s.def.mayHoldWater() ? drainFirst(s) : "Those blocks are left as they are: clear"
                                + " them by hand.") + " The new region is checked before it is used.");
                    }
                }
                s.claimed = claimed;
                s.onDisk.clear();
                if (claimed) {
                    s.onDisk.putAll(diskFacts(meta.get(GenAdminKeys.onDisk(id)), claim));
                } else {
                    s.pendingDisk.clear(); // what was reached in another region says nothing of this one
                    if (meta.get(GenAdminKeys.onDisk(id)) != null) {
                        try {
                            host.store().meta(GenAdminKeys.onDisk(id), null); // nor if it is ever claimed there again
                        } catch (SQLException | RuntimeException e) {
                            // read only for the claim it names: a claim made there again finds the scan's word first
                        }
                    }
                }
            }
            oldRegions(s, meta, world, origin, gap, kept);
            if (s.wanted() || s.claimed) {
                for (char h : new char[]{'A', 'B'}) {
                    kept.add(new Object[]{world, s.half(h)});
                }
            }
        }
        areas = List.copyOf(kept);
        if (readyAt >= 0) {
            // fix2-D: a pick the settings or the schedule moved away from is dropped, and said, at once
            for (SlotState s : slots.values()) {
                chosenUpkeep(s);
                droppedUpkeep(s);
            }
        }
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
     * The old regions a slot left behind ({@link GenAdminKeys#old}), added to the guarded {@code kept}: the
     * claim it held when its region changed is recorded the first time it is seen, before a claim at the new
     * place can overwrite it, with the world, origin, half size and gap it RECORDED. Recorded are:
     * <ul>
     *   <li>a claim made at another half size than the slot's now: this version changed its shape (Golf v4,
     *       the Mountain Run v2), so what stands there is the plugin's own and is emptied by itself (RETIRE,
     *       {@link #nextRetire});</li>
     *   <li>any old claim of a slot that {@link Slots.Def#mayHoldWater may hold water} (a Dropper's pools,
     *       golf's ponds), as 0.35's {@code wet} list was, which is read into this once: an owner's move
     *       keeps it guarded (nothing changes there, and nothing flows out) until an admin empties it
     *       ({@code /hcm games gen tidy}) or it is claimed there again. A dry slot's owner move is left as
     *       0.35 left it (a WARN: clear it by hand).</li>
     * </ul>
     * Every recorded region is guarded at its claim's own sizes ({@link #oldHalves}, the fix of GOLF-V4-SPEC
     * §5.2: never the slot's size now, which would guard the wrong boxes after a resize).
     */
    private void oldRegions(SlotState s, Map<String, String> meta, String world, int[] origin, int gap,
                            List<Object[]> kept) {
        if (meta != null) {
            String key = GenAdminKeys.old(s.def.id());
            String wetKey = GenAdminKeys.wet(s.def.id());
            List<String> stored = Regions.oldClaims(meta.get(key));
            Map<String, Long> marks = Regions.oldEmptied(meta.get(key));
            List<String> legacy = Regions.oldClaims(meta.get(wetKey));
            List<String> now = new ArrayList<>(stored);
            for (String c : legacy) {
                if (!now.contains(c)) {
                    now.add(c); // 0.35's wet list, read into the old list once
                }
            }
            String here = Regions.claim(s.def, world, origin, gap);
            String claim = meta.get(GenAdminKeys.claim(s.def.id()));
            if (claim != null && !claim.equals(here) && Regions.claimOrigin(claim) != null
                    && Regions.claimWorld(claim) != null && !now.contains(claim)
                    && (s.def.mayHoldWater() || Regions.resized(s.def, claim))) {
                now.add(claim);
            }
            if (s.claimed) {
                now.remove(here); // claimed here again: the claim guards it, and a clear drains it
            }
            marks.keySet().retainAll(now);
            boolean saved = true;
            if (!now.equals(stored)) {
                try {
                    host.store().meta(key, Regions.oldText(now, marks));
                } catch (SQLException | RuntimeException e) {
                    saved = false;
                    host.logger().log(Level.WARNING, "Fresh Courses: could not record " + s.def.id()
                            + "'s old region", e);
                }
            }
            if (saved && meta.get(wetKey) != null) {
                try {
                    host.store().meta(wetKey, null);
                } catch (SQLException | RuntimeException e) {
                    // read into the old list again next time: the same claims, nothing lost
                }
            }
            s.oldUnsaved = !saved;
            s.old = List.copyOf(now);
            s.emptied.clear();
            s.emptied.putAll(marks);
            s.emptiedSaves.keySet().retainAll(s.emptied.keySet());
            long boot = host.bootedAt();
            for (Map.Entry<String, Long> m : marks.entrySet()) {
                // ENG-R3-00: one emptied earlier in this server process (the engine started again in it) may be
                // in memory only: it waits for the world's saves too, and is never let go on a look at memory
                if (m.getValue() >= boot && !s.emptiedSaves.containsKey(m.getKey())) {
                    s.emptiedSaves.put(m.getKey(), saves.getOrDefault(
                            String.valueOf(Regions.claimWorld(m.getKey())).toLowerCase(Locale.ROOT), 0));
                }
            }
            s.retireHeld.keySet().retainAll(s.old);
            s.tidyAsked.retainAll(s.old);
        }
        for (String c : s.old) {
            String w = Regions.claimWorld(c);
            List<Box> halves = oldHalves.of(s.def, c);
            if (w != null && halves != null) {
                for (Box h : halves) {
                    kept.add(new Object[]{w, h});
                }
            }
        }
    }

    /** "ponds" (golf) or "pools" (a Dropper). */
    private static String water(SlotState s) {
        return s.def.golf() ? "ponds" : "pools";
    }

    /**
     * What the admin of a moved slot that may hold water reads: a Dropper's pools (golf's ponds) may
     * still stand in the old place, which stays guarded until it is emptied.
     */
    private static String drainFirst(SlotState s) {
        String water = water(s);
        return "Its " + water + " may still be there, so that area stays guarded: drain first - empty it with"
                + " /hcm games gen tidy " + s.def.id() + " confirm (it drains the " + water + " before anything else,"
                + " and takes away only Fresh Courses' own blocks).";
    }

    /**
     * The flow-only boxes {world, {@link Box}} {@code meta} names: every plot holding a kept course
     * that may hold water (a Dropper, golf: {@link KeepService#mayHoldWater}), and a plot job the
     * server stopped halfway ({@link KeepService#wetPending}). The keep area is hand-built ground the
     * guard leaves alone, but a pool's or a pond's water must never flow out of its plot.
     */
    static List<Object[]> wetPlots(Map<String, String> meta) {
        List<Object[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (!e.getKey().startsWith(GenAdminKeys.PLOTS)) {
                continue;
            }
            KeptPlot p = KeptPlot.parse(GenAdminKeys.plotOf(e.getKey()), e.getValue());
            if (p != null && KeepService.mayHoldWater(p.slot())) {
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

    /**
     * A slot's region moved (config): nothing at the old place is vouched for or cleared. A slot that
     * was {@code cleared} first (its claim given up: README "Moving an area by hand") left nothing there,
     * so that is said as an INFO, not the "not cleared" WARN. So did a Classic closed first (unrecalled:
     * a Classic keeps its claim, and {@code clear} doesn't take one) whose halves have been emptied since;
     * its old claim is let go here, as a clear lets a course's go, so a Golf or Dropper Classic's old
     * place isn't remembered as one that may still hold water.
     *
     * <p>A slot {@code released} from the F10 hold (config.yml saved at last, read by {@code /hcm reload}) is no
     * owner's move (ENG08): it stood at its old claim only while held, and goes where revision 21 put it, as a
     * restart would. Nothing is said here (the "area changed with this version" line and RETIRE say it), and its
     * old claim stays, so its old area is recorded and emptied by itself.
     *
     * @param claim the claim stored now, or {@code null}: the old place is described at its recorded sizes when
     *              it is that claim's
     * @return whether it was such an emptied Classic and its old claim is gone
     */
    private boolean moved(SlotState s, String world, int[] origin, int gap, boolean cleared, boolean released,
                          String claim) {
        boolean emptied = !released && s.classic && s.want == null && s.live == null && s.previous == null
                && !s.bothDirty && !s.oldDirty && !s.clearing && (job == null || job.slot != s);
        if (emptied) {
            try {
                host.store().meta(GenAdminKeys.claim(s.def.id()), null);
            } catch (SQLException | RuntimeException e) {
                host.logger().log(Level.WARNING, "Fresh Courses: could not let " + s.def.id() + "'s old claim go", e);
                emptied = false; // still claimed there: its old place stays guarded
            }
        }
        cleared |= emptied;
        if (job != null && job.slot == s) {
            cancel(job, "its region moved");
        }
        queue.removeIf(j -> j.slot == s);
        // the old place as it was built: the claim's recorded sizes when it is that place's (ENG08)
        boolean recorded = Regions.claimHalves(claim) != null && Arrays.equals(Regions.claimOrigin(claim), s.origin)
                && s.world.equalsIgnoreCase(Regions.claimWorld(claim));
        String line = "Fresh Courses: " + s.def.id() + " moved from " + s.world + " "
                + (recorded ? Regions.describeClaim(claim) : Regions.describe(s.def, s.origin, s.gap)) + " to " + world
                + " " + Regions.describe(s.def, origin, gap);
        if (released) {
            // ENG08: out of the F10 hold: the resize line and RETIRE say what happens, as after a restart
        } else if (!cleared) {
            host.logger().warning(line + ". The old halves were not cleared (" + (s.classic
                    ? "close it first with /hcm games gen unrecall " + s.def.id() + " confirm and wait until its"
                    + " halves are empty" : "use /hcm games gen clear before moving a course") + ")."
                    + (s.def.mayHoldWater() ? " " + drainFirst(s) : ""));
        } else {
            host.logger().info(line + ". Its old halves were emptied first, so nothing is left there; the new area is"
                    + " checked before it is used.");
        }
        s.verified = false;
        s.healFailed = s.live != null;
        s.previous = null;
        s.clearing = false;
        s.oldDirty = false;
        s.bothDirty = false; // a Classic's halves are the new ones now, which nothing holds yet: never cleared unscanned
        s.preview = null;
        s.claimed = false;
        return emptied;
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
        if (s.unplaced) {
            why = unplacedWhy(s);
        } else if (s.wanted()) {
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
    /** Why a slot whose origin or half_gap config can't read is off ({@link SlotState#unplaced}). */
    public static final String UNPLACED = "its origin or half_gap in config.yml can't be read (the WARN at the last start or"
            + " /hcm reload says which), so it stays where it was built and is off until that is fixed";

    /** Why an {@link SlotState#unplaced} slot is off: config.yml held back at an older revision (F10), or unreadable. */
    private static String unplacedWhy(SlotState s) {
        return s.heldWhy != null ? s.heldWhy : UNPLACED;
    }

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
        List<String> world = Regions.worldProblems(s.def, s.origin, s.gap, facts(port));
        if (!world.isEmpty()) {
            return world.get(0);
        }
        List<DailySettings.SlotConfig> others = new ArrayList<>();
        for (SlotState o : slots.values()) {
            if (o != s && (o.wanted() || o.claimed)) {
                DailySettings.SlotConfig oc = DailySettings.SlotConfig.shipped(o.def).withOrigin(o.origin)
                        .withHalfGap(o.gap);
                others.add(oc);
            }
        }
        String apart = Regions.apartProblem(DailySettings.SlotConfig.shipped(s.def).withOrigin(s.origin)
                .withHalfGap(s.gap), others);
        if (apart != null) {
            return apart;
        }
        String extra = Regions.extrasProblem(s.def, s.origin, s.gap, extras());
        if (extra != null) {
            return extra; // the Falling Floors arena: neither is ever built into the other
        }
        if (built != null) {
            String near = Regions.handBuiltProblem(s.def, s.origin, s.gap, s.world, built);
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

    /**
     * Whether the live row's course stands in the half its tag names, where config puts the slot now: a time
     * trial's start, every golf hole's tee. False only when the row says it stands elsewhere (an older
     * place); a row that can't be read is left to the heal itself, which then fails as before.
     */
    private boolean standsIn(SlotState s) {
        if (s.live == null) {
            return true;
        }
        Box half = s.half(s.live.half());
        try {
            GamesDao.CourseRow row = host.store().course(s.def.id());
            if (row == null) {
                return true;
            }
            if (s.def.golf()) {
                for (GolfCourse.Hole h : com.dierks.homecraft.games.golf.CourseCodec.fromRow(row).holes()) {
                    if (h.tee() != null && !half.contains((int) Math.floor(h.tee().x()), (int) Math.floor(h.tee().y()),
                            (int) Math.floor(h.tee().z()))) {
                        return false;
                    }
                }
                return true;
            }
            Course c = CourseCodec.decode(row.id(), row.data()).course();
            return c == null || c.start() == null || half.contains((int) Math.floor(c.start().x()),
                    (int) Math.floor(c.start().y()), (int) Math.floor(c.start().z()));
        } catch (SQLException | RuntimeException e) {
            return true;
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

    // ---- old areas (RETIRE: V4-DECISIONS "One move mechanism") ----------------------------------------

    /**
     * Whether RETIRE empties old claim {@code c} of {@code s} by itself: this version changed the slot's size
     * (the move of an area), or an admin asked ({@code tidy confirm}). An owner's move of origin, gap or world
     * alone waits for an admin, as 0.35 left it.
     */
    private boolean retireDue(SlotState s, String c) {
        if (s.emptied.containsKey(c)) {
            // F09: emptied in an earlier run: looked at once more (writing nothing when it stayed empty) before
            // it is let go; emptied in this run: let go once the world has been saved (worldSaved)
            return !s.emptiedSaves.containsKey(c) || s.tidyAsked.contains(c);
        }
        return Regions.resized(s.def, c) || s.tidyAsked.contains(c);
    }

    /**
     * Whether old claim {@code c} of {@code s} was emptied in this run (F09). One marked emptied in an earlier run
     * (a hard stop before the world was saved may have brought its blocks back) still stands until its re-look
     * (ENG06): it is emptied before a region it crowds is scanned, so its blocks are never taken for foreign ones.
     */
    private static boolean emptiedThisRun(SlotState s, String c) {
        return s.emptied.containsKey(c) && s.emptiedSaves.containsKey(c);
    }

    /**
     * The next old area to empty, or {@code null}: with {@code urgent} only one that a region waiting to be
     * built crowds ({@link #wantedUnclaimed}); else any that is due. One whose world isn't loaded waits for
     * it; one held by something in its way is looked at again every {@value #VET_EVERY_MS} ms.
     */
    private Job nextRetire(boolean urgent) {
        long now = host.now();
        for (SlotState s : slots.values()) {
            if (busyWith(s) || s.heldWhy != null) {
                continue; // F10: config.yml is still below the revision that moved it: nothing is done there yet
            }
            for (String c : s.old) {
                if (!retireDue(s, c) || host.world(worldOf(s, c)) == null) {
                    continue;
                }
                if (s.retireHeld.containsKey(c) && now - s.retireHeldAt < VET_EVERY_MS) {
                    continue;
                }
                List<Box> halves = oldHalves.of(s.def, c);
                if (urgent && (emptiedThisRun(s, c) || !OldAreas.crowds(halves, wantedUnclaimed(worldOf(s, c))))) {
                    continue; // one emptied in this run crowds nothing (ENG06: one emptied before may be back)
                }
                Job j = new Job(Kind.RETIRE, s, s.tidyAsked.contains(c) ? s.tidyReport : null);
                j.retire = c;
                return j;
            }
        }
        return null;
    }

    /**
     * The halves of every slot and Classic that is switched on but not claimed where config puts it now
     * (in {@code world}): nothing of theirs stands there yet, and their next build scans them.
     */
    private List<Box> wantedUnclaimed(String world) {
        List<Box> out = new ArrayList<>();
        for (SlotState o : slots.values()) {
            if (o.wanted() && !o.claimed && !o.unplaced && o.world.equalsIgnoreCase(world)) {
                out.add(o.half('A'));
                out.add(o.half('B'));
            }
        }
        return out;
    }

    /**
     * Why {@code s}'s region must wait before it is scanned, claimed or built: an old area still standing
     * within {@value Regions#APART} blocks of it (its own, resized in place, or another's), which is emptied
     * first; {@code null} when none is, or when it is claimed already. Also while its own old claim couldn't
     * be recorded ({@link SlotState#oldUnsaved}).
     */
    private String oldInTheWay(SlotState s) {
        if (s.claimed) {
            return null;
        }
        if (s.oldUnsaved) {
            return "its old area couldn't be recorded in the database yet; it is tried again every second";
        }
        List<Box> mine = List.of(s.half('A'), s.half('B'));
        for (SlotState o : slots.values()) {
            for (String c : o.old) {
                String w = Regions.claimWorld(c);
                if (emptiedThisRun(o, c)) {
                    continue; // emptied already (F09: still recorded until it is known to be on disk)
                }
                if (w != null && w.equalsIgnoreCase(s.world) && OldAreas.crowds(oldHalves.of(o.def, c), mine)) {
                    String held = o.retireHeld.get(c);
                    return (o == s ? "its old area" : o.def.name() + "'s old area") + " (" + Regions.describeClaim(c)
                            + ") is next to it and is emptied first" + (held != null ? ", but it can't be now: " + held
                            : retireDue(o, c) ? "" : " - /hcm games gen tidy " + o.def.id() + " confirm");
                }
            }
        }
        return null;
    }

    /**
     * What is in the way of emptying {@code s}'s old claim {@code c} ({@code halves} in {@code world}), or
     * {@code null}: checked at the start of every RETIRE ({@link OldAreas#inTheWay}). The obstacles are every
     * slot's and Classic's claimed halves (and an older claim of another, and every other old area still
     * standing), the Clubhouse and the arena, the keep area and every kept plot, every hand-built course, the
     * world's spawn and the safe spot. A region switched on but not claimed is no obstacle: nothing stands
     * there yet (that is how a slot resized in place has its old area emptied first).
     */
    private String retireProblem(SlotState s, String c, List<Box> parts, List<Character> which, String world,
                                 WorldPort port, boolean cut) {
        List<OldAreas.Obstacle> in = new ArrayList<>();
        Map<String, String> meta;
        try {
            meta = host.store().metaLike("gen.");
        } catch (SQLException | RuntimeException e) {
            return "the database can't be read, so what is next to it can't be checked";
        }
        for (SlotState o : slots.values()) {
            // the slot's own course, cut out of the parts looked at ({@code cut}): nothing is written in it
            boolean own = cut && o == s && o.claimed;
            if (o.claimed && !own) {
                in.add(new OldAreas.Obstacle(o.world, o.half('A'), o.def.name() + "'s half A"));
                in.add(new OldAreas.Obstacle(o.world, o.half('B'), o.def.name() + "'s half B"));
            }
            String claim = meta.get(GenAdminKeys.claim(o.def.id()));
            if (own && Regions.claim(o.def, o.world, o.origin, o.gap).equals(claim)) {
                claim = null;
            }
            if (claim != null && !claim.equals(c) && Regions.claimHalves(claim) != null) {
                for (Box h : Regions.claimHalves(claim)) {
                    in.add(new OldAreas.Obstacle(Regions.claimWorld(claim), h, o.def.name() + "'s claimed area"));
                }
            }
            for (String other : o.old) {
                if (o == s && other.equals(c)) {
                    continue;
                }
                List<Box> oh = oldHalves.of(o.def, other);
                for (Box h : oh == null ? List.<Box>of() : oh) {
                    in.add(new OldAreas.Obstacle(Regions.claimWorld(other), h, o.def.name() + "'s other old area"));
                }
            }
        }
        for (Regions.Extra e : extras()) {
            in.add(new OldAreas.Obstacle(genWorld(), e.box(), e.name().replace('_', ' ')));
        }
        KeepArea keep = host.settings().archive().keep();
        if (keep != null && keep.maxPlots() > 0) {
            in.add(new OldAreas.Obstacle(genWorld(), keep.area(), "the kept courses' area"));
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            int n = GenAdminKeys.plotOf(e.getKey());
            KeptPlot p = n < 1 ? null : KeptPlot.parse(n, e.getValue());
            if (p != null) {
                in.add(new OldAreas.Obstacle(p.world(), p.box(), "kept plot " + n));
            }
        }
        List<Regions.Area> built = handBuilt();
        if (built == null) {
            return "the courses can't be read from the database, so the hand-built ones can't be checked";
        }
        for (Regions.Area a : built) {
            in.add(new OldAreas.Obstacle(a.world(), a.box(), "the hand-built course " + a.courseId()));
        }
        return OldAreas.inTheWay(parts, which, world, in, port.spawn(), host.settings().safeSpot());
    }

    /** A RETIRE starts: the safety check, then both old halves, A then B, after moving anyone there out. */
    private void beginRetire(Job j) {
        SlotState s = j.slot;
        String c = j.retire;
        String world = worldOf(s, c);
        List<Box> halves = oldHalves.of(s.def, c);
        WorldPort port = world == null ? null : host.world(world);
        if (halves == null || port == null) {
            end(j); // unreadable (never recorded) or its world isn't loaded: it waits
            return;
        }
        // F09: the slot's own course, where it is claimed again over part of its old area (resized in place),
        // is cut out: only the rest is looked at and emptied (the course's own checks keep the part it covers)
        List<Box> own = s.claimed && s.world.equalsIgnoreCase(world) ? List.of(s.half('A'), s.half('B')) : List.of();
        List<Step> steps = new ArrayList<>();
        List<Box> parts = new ArrayList<>();
        List<Character> which = new ArrayList<>();
        for (int i = 0; i < halves.size(); i++) {
            for (Box part : OldAreas.minus(halves.get(i), own)) {
                steps.add(new Step(i == 0 ? 'A' : 'B', null, BuildJob.Mode.CONVERGE, part));
                parts.add(part);
                which.add(i == 0 ? 'A' : 'B');
            }
        }
        String why = retireProblem(s, c, parts, which, world, port, !own.isEmpty());
        if (why != null) {
            end(j);
            s.retireHeld.put(c, why);
            s.retireHeldAt = host.now();
            warnOnce(s, "Fresh Courses: " + s.def.id() + "'s old area (" + Regions.describeClaim(c) + ") isn't emptied: "
                    + why + ". It stays guarded and nothing there was changed; /hcm games check lists it.");
            j.report.accept("&c" + s.def.name() + "'s old area can't be emptied: &7" + why);
            return;
        }
        s.retireHeld.remove(c);
        j.world = world;
        j.steps.addAll(steps);
        j.stage = Stage.EVACUATE;
        j.evacStart = host.now();
        host.logger().info("Fresh Courses: emptying " + s.def.id() + "'s old area in " + world + " ("
                + Regions.describeClaim(c) + ")" + (s.def.mayHoldWater() ? ", the " + water(s) + " first" : "")
                + "; only Fresh Courses' own blocks are taken away.");
    }

    /**
     * Both old halves were converged to nothing of ours and verified (a pass that writes nothing).
     *
     * <p><b>Two phases (F09).</b> That verify read chunks in memory: until they are written to disk, a hard stop
     * (a kill, a crash, a power cut) brings the old course and its ponds back. So the claim isn't forgotten here:
     * it is marked {@code emptied@T} ({@link Regions#oldEmptied}), stays recorded and guarded, and the outcome is
     * recorded for the check. It is let go ({@link #forget}) once the world has been saved twice since
     * ({@link #worldSaved}), or when a later start looks at it again and finds nothing of ours there (this method
     * again, with nothing written). A later start that finds blocks back empties them again, says so, and marks
     * it anew. The record stays until then, so a stop anywhere before just runs it again (writing nothing more).
     */
    private void retired(Job j) {
        SlotState s = j.slot;
        String c = j.retire;
        String id = s.def.id();
        end(j);
        boolean again = s.emptied.containsKey(c) && !s.emptiedSaves.containsKey(c); // emptied in an earlier run
        String where = Regions.claimWorld(c) + " (" + Regions.describeClaim(c) + ")";
        if (again && j.writes == 0) {
            if (forget(s, c)) {
                host.logger().info("Fresh Courses: " + id + "'s old area in " + where + " was still empty at this start,"
                        + " so it was saved that way: it is let go.");
                j.report.accept("&a" + s.def.name() + "'s old area is empty: &7nothing more to take away.");
            }
            return;
        }
        long now = host.now();
        OldAreas.Retired done = new OldAreas.Retired(now, c, j.writes, j.left, j.leftAt);
        try {
            if (c.equals(host.store().meta(GenAdminKeys.claim(id)))) {
                host.store().meta(GenAdminKeys.claim(id), null); // its claim was this old one: nothing holds it now
            }
            String stored = host.store().meta(GenAdminKeys.old(id));
            List<String> claims = new ArrayList<>(Regions.oldClaims(stored));
            if (!claims.contains(c)) {
                claims.add(c);
            }
            Map<String, Long> marks = new LinkedHashMap<>(Regions.oldEmptied(stored));
            marks.put(c, now);
            host.store().meta(GenAdminKeys.old(id), Regions.oldText(claims, marks));
            host.store().meta(GenAdminKeys.retired(id), done.text());
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: " + id + "'s old area is empty, but the database couldn't"
                    + " be told; it is checked again (nothing more to do) at the next check", e);
            return;
        }
        s.emptied.put(c, now);
        s.emptiedSaves.put(c, saves.getOrDefault(String.valueOf(Regions.claimWorld(c)).toLowerCase(Locale.ROOT), 0));
        s.tidyAsked.remove(c);
        s.retireHeld.remove(c);
        String blocks = String.format(Locale.ROOT, "%,d", j.writes) + " block" + (j.writes == 1 ? "" : "s");
        if (again) {
            host.logger().warning("Fresh Courses: " + id + "'s old area in " + where + " had " + blocks + " of its own"
                    + " back at this start (the world wasn't saved after it was emptied): emptied again.");
        }
        String line = "Fresh Courses: " + id + "'s old area in " + where + " is empty now: " + blocks + " taken away"
                + (s.def.mayHoldWater() ? ", the " + water(s) + " drained first" : "") + ". " + id + " builds at "
                + Regions.describe(s.def, s.origin, s.gap) + ". The area stays guarded until the world has been saved"
                + " (or the next start finds it still empty).";
        if (j.left > 0) {
            host.logger().warning(line + " " + String.format(Locale.ROOT, "%,d", j.left) + " block" + (j.left == 1
                    ? " that isn't" : "s that aren't") + " Fresh Courses' " + (j.left == 1 ? "was" : "were") + " left"
                    + " there as they are (first at " + String.join("; ", j.leftAt) + "): they are yours to remove by"
                    + " hand.");
        } else {
            host.logger().info(line);
        }
        j.report.accept("&a" + s.def.name() + "'s old area is empty: &7" + j.writes + " blocks taken away"
                + (j.left > 0 ? ", " + j.left + " that aren't Fresh Courses' left as they are (first at "
                + j.leftAt.get(0) + ")." : "."));
        if (s.tidyAsked.isEmpty()) {
            s.tidyReport = null;
        }
    }

    /**
     * F09's second phase: an emptied old claim known to be on disk is let go: out of {@link GenAdminKeys#old}
     * (its outcome stays in {@link GenAdminKeys#retired} for the check), and the guard lets its halves go.
     *
     * @return whether it was (the database may refuse: then it is tried again at the next save or start)
     */
    private boolean forget(SlotState s, String c) {
        String id = s.def.id();
        try {
            String stored = host.store().meta(GenAdminKeys.old(id));
            List<String> claims = new ArrayList<>(Regions.oldClaims(stored));
            claims.remove(c);
            Map<String, Long> marks = new LinkedHashMap<>(Regions.oldEmptied(stored));
            marks.remove(c);
            host.store().meta(GenAdminKeys.old(id), Regions.oldText(claims, marks));
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: " + id + "'s emptied old area couldn't be let go in the"
                    + " database; it stays guarded and is tried again", e);
            return false;
        }
        List<String> old = new ArrayList<>(s.old);
        old.remove(c);
        s.old = List.copyOf(old);
        s.emptied.remove(c);
        s.emptiedSaves.remove(c);
        s.tidyAsked.remove(c);
        s.retireHeld.remove(c);
        refresh(); // the guard lets the old halves go
        return true;
    }

    // ---- what is known to be on disk (F12) ----------------------------------------------------------

    /** A big half (the Mountain Run v2's 1,200 chunks): one known to be on disk is checked by a sample at a start. */
    static final int BIG_HALF_CHUNKS = 256;
    /** A sampled check reads every chunk on a lattice this many chunks apart, and every chunk with a sign. */
    static final int SAMPLE_EVERY = 4;
    /** The fact of a half known to be empty. */
    static final String EMPTY = "empty";

    /** The fact of a half verified against {@code planHash}. */
    static String planFact(String planHash) {
        return "plan:" + planHash;
    }

    /** The facts stored for {@code claim} ({@link GenAdminKeys#onDisk}); none when they were stored for another. */
    static Map<Character, String> diskFacts(String stored, String claim) {
        Map<Character, String> out = new HashMap<>();
        if (stored == null || claim == null) {
            return out;
        }
        String[] p = stored.split(";");
        if (p.length == 0 || !p[0].equals("claim=" + claim)) {
            return out;
        }
        for (int i = 1; i < p.length; i++) {
            if (p[i].length() > 2 && (p[i].charAt(0) == 'A' || p[i].charAt(0) == 'B') && p[i].charAt(1) == '=') {
                out.put(p[i].charAt(0), p[i].substring(2));
            }
        }
        return out;
    }

    /** {@link #diskFacts}'s text for {@code s}'s claim now, or {@code null} for none. */
    private static String diskText(SlotState s, Map<Character, String> facts) {
        if (facts.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("claim=").append(Regions.claim(s.def, s.world, s.origin, s.gap));
        for (char h : new char[]{'A', 'B'}) {
            if (facts.containsKey(h)) {
                b.append(';').append(h).append('=').append(facts.get(h));
            }
        }
        return b.toString();
    }

    /**
     * Before anything is written into half {@code h} of {@code s}: its fact goes, first from the database (so a
     * fact left after any stop is still true on disk).
     *
     * @return false when the database refused (nothing may be written then)
     */
    private boolean unsettle(SlotState s, char h) {
        s.pendingDisk.remove(h);
        if (!s.onDisk.containsKey(h)) {
            return true;
        }
        Map<Character, String> rest = new HashMap<>(s.onDisk);
        rest.remove(h);
        try {
            host.store().meta(GenAdminKeys.onDisk(s.def.id()), diskText(s, rest));
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not record that " + s.def.id() + "'s half " + h
                    + " is about to change", e);
            return false;
        }
        s.onDisk.remove(h);
        return true;
    }

    /** Half {@code h} of {@code s} holds {@code fact} in memory now: on disk after the next saves ({@link #settle}). */
    private void pend(SlotState s, char h, String fact) {
        if (!s.classic && s.claimed) {
            s.pendingDisk.put(h, new SlotState.DiskFact(fact, saves.getOrDefault(s.world.toLowerCase(Locale.ROOT), 0)));
        }
    }

    /** {@code s}'s facts that world {@code w} has saved twice since ({@value #SAVES_TO_SETTLE}), recorded. */
    private void settle(SlotState s, String w, int n) {
        if (s.pendingDisk.isEmpty() || !s.world.equalsIgnoreCase(w) || !s.claimed) {
            return;
        }
        Map<Character, String> facts = new HashMap<>(s.onDisk);
        List<Character> done = new ArrayList<>();
        for (Map.Entry<Character, SlotState.DiskFact> e : s.pendingDisk.entrySet()) {
            boolean writing = job != null && job.slot == s && job.kind != Kind.RETIRE;
            if (!writing && n >= e.getValue().saves() + SAVES_TO_SETTLE) {
                facts.put(e.getKey(), e.getValue().fact());
                done.add(e.getKey());
            }
        }
        if (done.isEmpty()) {
            return;
        }
        try {
            host.store().meta(GenAdminKeys.onDisk(s.def.id()), diskText(s, facts));
        } catch (SQLException | RuntimeException e) {
            return; // tried again at the next save
        }
        s.onDisk.clear();
        s.onDisk.putAll(facts);
        done.forEach(s.pendingDisk::remove);
    }

    /**
     * The chunks a sampled check of {@code half} reads (F12): every {@value #SAMPLE_EVERY}th chunk each way from
     * the half's corner, and every chunk holding one of the plan's signs (a course's start and finish).
     */
    static java.util.function.BiPredicate<Integer, Integer> sample(Box half, Plan plan) {
        Set<Long> signs = new HashSet<>();
        for (SignText t : plan.signs()) {
            signs.add(((long) (t.x() >> 4) << 32) | ((t.z() >> 4) & 0xFFFFFFFFL));
        }
        int cx0 = half.minX() >> 4;
        int cz0 = half.minZ() >> 4;
        return (cx, cz) -> (Math.floorMod(cx - cx0, SAMPLE_EVERY) == 0 && Math.floorMod(cz - cz0, SAMPLE_EVERY) == 0)
                || signs.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL));
    }

    /**
     * How many saves of a world must follow an emptied old area before it is let go ({@link #worldSaved}): Paper
     * fires the event as a save BEGINS, and an autosave writes chunks over the ticks after it, so the first one
     * after the verify may still be writing; by the second (an autosave interval later) it has finished.
     */
    static final int SAVES_TO_SETTLE = 2;

    /**
     * A world was saved ({@code WorldSaveEvent}, the plugin's listener): an emptied old area in it that has seen
     * {@value #SAVES_TO_SETTLE} saves since it was emptied is on disk, and is let go (F09); the halves known to
     * be right are recorded for the next start (F12, {@link #settle}).
     */
    public void worldSaved(String world) {
        if (!running || world == null) {
            return;
        }
        String w = world.toLowerCase(Locale.ROOT);
        int n = saves.merge(w, 1, Integer::sum);
        for (SlotState s : slots.values()) {
            for (Map.Entry<String, Integer> e : List.copyOf(s.emptiedSaves.entrySet())) {
                String c = e.getKey();
                boolean busy = job != null && job.kind == Kind.RETIRE && job.slot == s && c.equals(job.retire);
                if (!busy && n >= e.getValue() + SAVES_TO_SETTLE && w.equalsIgnoreCase(Regions.claimWorld(c))
                        && forget(s, c)) {
                    host.logger().info("Fresh Courses: " + s.def.id() + "'s old area in " + Regions.claimWorld(c) + " ("
                            + Regions.describeClaim(c) + ") has been saved empty: it is let go.");
                }
            }
            settle(s, w, n);
        }
    }

    /** Every old area still standing, slot by slot, as status and the check read them. Read-only. */
    public List<OldAreas.Area> oldAreas() {
        List<OldAreas.Area> out = new ArrayList<>();
        for (SlotState s : slots.values()) {
            for (String c : s.old) {
                String world = worldOf(s, c);
                OldAreas.State state;
                String detail = null;
                int percent = 0;
                if (job != null && job.slot == s && job.kind == Kind.RETIRE && c.equals(job.retire)) {
                    state = OldAreas.State.RUNNING;
                    percent = retirePercent(job);
                } else if (s.heldWhy != null) {
                    state = OldAreas.State.HELD;
                    detail = s.heldWhy;
                } else if (s.retireHeld.containsKey(c)) {
                    state = OldAreas.State.HELD;
                    detail = s.retireHeld.get(c);
                } else if (s.emptied.containsKey(c) && !retireDue(s, c)) {
                    state = OldAreas.State.EMPTIED; // F09: let go once it is known to be on disk
                } else if (world == null || host.world(world) == null) {
                    state = OldAreas.State.ELSEWHERE;
                    detail = world;
                } else {
                    state = retireDue(s, c) ? OldAreas.State.WAITING : OldAreas.State.MANUAL;
                }
                out.add(new OldAreas.Area(s.def.id(), s.def.name(), s.classic, c, Regions.describeClaim(c), state,
                        detail, percent));
            }
        }
        return out;
    }

    /** How far a RETIRE is, 0-100: its halves in turn. */
    private static int retirePercent(Job j) {
        if (j.steps.isEmpty()) {
            return 0;
        }
        double done = j.stepIndex + (j.build == null ? 0 : j.build.progress());
        return (int) Math.min(99, Math.floor(100 * done / j.steps.size()));
    }

    /**
     * The last old area each slot had emptied ({@link GenAdminKeys#retired}), by slot id, for the check;
     * empty when the database can't be read.
     */
    public Map<String, OldAreas.Retired> retiredAreas() {
        Map<String, OldAreas.Retired> out = new LinkedHashMap<>();
        try {
            Map<String, String> meta = host.store().metaLike("gen.");
            for (SlotState s : slots.values()) {
                OldAreas.Retired r = OldAreas.Retired.parse(meta.get(GenAdminKeys.retired(s.def.id())));
                if (r != null) {
                    out.put(s.def.id(), r);
                }
            }
        } catch (SQLException | RuntimeException e) {
            // nothing to say
        }
        return out;
    }

    @Override
    public void tidy(String slotId, boolean confirm, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (s == null) {
            report.accept("&cNo course called " + slotId + ".");
            return;
        }
        OldAreas.Retired last = retiredAreas().get(s.def.id());
        List<String> toEmpty = new ArrayList<>(s.old);
        toEmpty.removeIf(s.emptied::containsKey);
        if (toEmpty.isEmpty()) {
            report.accept("&7" + s.def.name() + " has no old area to empty." + (last == null ? "" : " The last one ("
                    + Regions.describeClaim(last.claim()) + ") was emptied " + GenCopy.whenDated(last.at(), host.zone())
                    + ": " + last.removed() + " blocks taken away" + (last.left() > 0 ? ", " + last.left()
                    + " that aren't Fresh Courses' left as they are" : "") + "." + (s.old.isEmpty() ? ""
                    : " It stays guarded until the world has been saved (or the next start finds it still empty).")));
            return;
        }
        for (OldAreas.Area a : oldAreas()) {
            if (a.slot().equals(s.def.id())) {
                report.accept("&7" + s.def.name() + ": " + OldAreas.line(a));
            }
        }
        if (s.heldWhy != null) {
            return; // F10: its line above says why nothing is done there yet
        }
        if (!confirm) {
            report.accept("&7Add &econfirm &7to empty " + (toEmpty.size() == 1 ? "it" : "them") + " now: only Fresh"
                    + " Courses' own blocks go (water first); anything else stays as it is and is listed.");
            return;
        }
        if (!running || readyAt < 0) {
            report.accept("&cFresh Courses is still starting; try in a moment.");
            return;
        }
        s.tidyAsked.addAll(toEmpty);
        s.retireHeld.clear(); // looked at again as it starts
        s.tidyReport = report;
        report.accept("&7Emptying " + s.def.name() + "'s old area: it starts once nothing else is being built (a"
                + " minute or so). &e/hcm games gen status " + s.def.id() + " &7shows how far.");
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
                    archiveOffMain(j); // ready long before its verify pass is done
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
                case RETIRE -> beginRetire(j);
                case UNRECALL -> {
                    if (!s.claimed) {
                        // ENG01's safety net: never converge a region Fresh Courses doesn't hold (beginHeal's rule);
                        // nothing of the slot's stands there, and its next recall scans the region first
                        s.previous = null;
                        s.clearing = false;
                        s.bothDirty = false;
                        s.oldDirty = false;
                        end(j);
                        host.logger().info("Fresh Courses: " + s.def.id() + "'s halves aren't claimed, so they"
                                + " aren't cleared; its next recall checks them first.");
                        return;
                    }
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
        String why = vetProblem(s, built);
        return why != null ? why : oldInTheWay(s);
    }

    private void beginHeal(Job j) {
        SlotState s = j.slot;
        String why = vet(s, handBuilt());
        if (why != null || s.live == null) {
            end(j);
            j.report.accept("&c" + s.def.name() + " can't be checked: &7" + (why == null ? "it has no course" : why));
            return;
        }
        if (!s.claimed || !standsIn(s)) {
            // Never converge a region Fresh Courses doesn't hold: the next build scans it first. Nor heal a live
            // row whose course stands somewhere else (it was made at an old place, and the region here was
            // claimed after it, for its replacement): its blocks are in an old area, which RETIRE empties.
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
            // An older planner made it: it can't be derived again, so it gets the quick check. Never by a sample
            // (ENG00): there is no plan to compare one with, and a plan-less converge would empty the half.
            j.sampled = false;
            j.steps.add(new Step(j.half, null, BuildJob.Mode.SCAN));
            j.stage = Stage.CONVERGE;
            return;
        }
        plan(j, p);
    }

    private void beginBuild(Job j) {
        SlotState s = j.slot;
        String why = vet(s, handBuilt());
        if (why == null && !s.wanted()) {
            // CV final gate: a preview of a slot that is off (vet checks only one that is on) passes §2.4 as a
            // build does before it scans, claims or writes anything
            why = claimProblem(s);
        }
        if (why == null) {
            why = oldInTheWay(s); // an old area next to its region is emptied first
        }
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
        j.fallDepth = host.fallDepth();
        PlanInput in = new PlanInput(def, half, j.half, j.day, j.reroll, j.seed, j.mix, j.fallDepth,
                WORK.getOrDefault(def.generator(), 200_000L), cancelled(j.cancelled));
        j.stage = Stage.PLANNING;
        j.planStarted = host.now();
        boolean heal = j.kind == Kind.HEAL;
        boolean archive = j.kind == Kind.BUILD;
        Box build = s.half(j.half); // where its one step builds it (a remade recall's plan may be smaller)
        GenTag tag = j.tag;
        host.planner().execute(() -> {
            long t0 = System.nanoTime();
            Plan made = null;
            List<String> refused = List.of();
            Throwable error = null;
            byte[] row = null;
            BuildIndex.Prepared index = null;
            try {
                made = heal ? p.rederive(in, tag) : p.plan(in);
                List<String> generator = PlanCheck.generator(p, made, in, heal); // §3.3 step 2, today's settings
                // Plan-sized work stays off the main thread (F11): the shared checks, the archive row, the index.
                List<String> problems = new ArrayList<>(PlanCheck.problems(made, def, half));
                problems.addAll(generator);
                refused = problems;
                if (problems.isEmpty()) {
                    row = archive ? encode(made) : null;
                    index = BuildIndex.prepare(build, made);
                }
            } catch (Throwable e) {
                error = e;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            Plan result = made;
            List<String> checked = refused;
            Throwable failure = error;
            byte[] archived = row;
            BuildIndex.Prepared ready = index;
            inbox.add(() -> planned(j, result, checked, failure, ms, half, archived, ready));
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

    /**
     * The plan came back from the planner thread with everything plan-sized already done there (F11): its
     * problems ({@link PlanCheck#problems} for {@code checked}, then the generator's own), a BUILD's archive
     * row and its build index. Here, on the main thread, only what can have changed since is looked at:
     * the half it is for.
     */
    private void planned(Job j, Plan plan, List<String> refused, Throwable error, long ms, Box checked,
                         byte[] archived, BuildIndex.Prepared index) {
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
        List<String> problems = new ArrayList<>(refused);
        Box half = j.planBox != null ? j.planBox : s.half(j.half);
        if (plan != null && !half.equals(checked) && !half.equals(plan.half())) {
            // the slot moved while it was planning: the plan was checked for where it was
            problems.add(0, "the plan is for " + (plan.half() == null ? "no half" : plan.half().describe())
                    + ", not " + half.describe());
        }
        if (!problems.isEmpty()) {
            fail(j, "its plan was refused: " + String.join("; ", problems));
            return;
        }
        if (j.kind == Kind.HEAL && !plan.hash().equals(j.tag.planHash())) {
            fail(j, "its layout can't be made again (the plan came out different)");
            return;
        }
        j.plan = plan;
        j.archived = archived;
        j.archivedReady = j.kind == Kind.BUILD;
        j.prepared = index;
        if (j.kind == Kind.HEAL && j.sampled) {
            j.reading = true;
            j.steps.add(new Step(j.half, plan, BuildJob.Mode.SCAN, null, sample(s.half(j.half), plan)));
        } else if (j.kind == Kind.HEAL && j.background) {
            // ENG07: the whole half is read first, its on-disk fact left alone; it is written (and the fact goes)
            // only when that read finds a difference, so a clean check keeps the next start's sample
            j.reading = true;
            j.steps.add(new Step(j.half, plan, BuildJob.Mode.SCAN));
        } else {
            j.steps.add(new Step(j.half, plan, BuildJob.Mode.CONVERGE));
        }
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
        String world = worldOf(j);
        WorldPort port = host.world(world);
        if (port == null) {
            fail(j, "the world " + world + " isn't loaded");
            return;
        }
        if (j.build == null) {
            if (j.stepIndex >= j.steps.size()) {
                stepsDone(j);
                return;
            }
            Step st = j.steps.get(j.stepIndex);
            Box box = st.box() != null ? st.box() : s.half(st.which());
            BuildIndex.Prepared ready = st.plan() == null ? null : readyIndex(j, st.plan(), box, now);
            if (ready == null && st.plan() != null && st.plan().ops().size() > OFF_MAIN_OPS) {
                return; // its index is being made on the planner thread (F11)
            }
            if (st.mode() == BuildJob.Mode.CONVERGE && st.box() == null && !unsettle(s, st.which())) {
                fail(j, "the database can't be written, so half " + st.which() + " can't be changed");
                return;
            }
            try {
                // a half that may hold water (a Dropper's pools, golf's ponds): drained before any wall
                // goes, even when clearing; an old area keeps whatever isn't Fresh Courses' (RETIRE)
                Predicate<String> leave = j.kind == Kind.RETIRE ? OldAreas::foreign : null;
                j.build = ready != null ? BuildJob.ready(port, box, ready, st.mode(), s.def.mayHoldWater(), leave)
                        : new BuildJob(port, box, st.plan(), st.mode(), s.def.mayHoldWater(), leave);
                if (st.only() != null) {
                    j.build.only(st.only()); // F12: a sampled check reads only part of its half
                }
            } catch (IllegalArgumentException e) {
                fail(j, e.getMessage());
                return;
            }
            j.prepared = null;
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
            if (p.world() != null && p.world().equalsIgnoreCase(world)) {
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
            if (p != null && !(j.kind == Kind.HEAL && p.playing(s.def.id())) && !onOwnHalves(j, p, world)) {
                moveToSafety(world, p);
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
                if (j.build.left() > 0) {
                    j.left += j.build.left(); // RETIRE: what stays because it isn't ours
                    List<String> at = new ArrayList<>(j.leftAt);
                    for (String a : j.build.leftAt()) {
                        if (at.size() < BuildJob.NAMED) {
                            at.add(a);
                        }
                    }
                    j.leftAt = List.copyOf(at);
                }
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

    /**
     * A step's build index when it is ready (F11): the one the planner thread made with the plan, or, for a
     * big plan that came another way (a promote, a recall, a boot check), one asked of the planner thread
     * now and ready a tick or so later; {@code null} meanwhile (the job waits, at most {@link #PLAN_KILL_MS}),
     * and for a small plan, whose index is made in place as always.
     */
    private BuildIndex.Prepared readyIndex(Job j, Plan plan, Box box, long now) {
        BuildIndex.Prepared p = j.prepared;
        if (p != null && p.plan() == plan && p.half().equals(box)) {
            return p;
        }
        if (plan.ops().size() <= OFF_MAIN_OPS) {
            return null;
        }
        if (j.preparing < 0) {
            j.preparing = now;
            host.planner().execute(() -> {
                BuildIndex.Prepared made = BuildIndex.prepare(box, plan);
                inbox.add(() -> {
                    if (job == j) {
                        j.preparing = -1;
                        j.prepared = made;
                    }
                });
            });
        } else if (now - j.preparing > PLAN_KILL_MS) {
            fail(j, "its build couldn't be made ready in " + PLAN_KILL_MS / 1000 + " seconds");
        }
        return null;
    }

    /**
     * A big plan's archive row, written on the planner thread as the job starts (a PROMOTE: its plan was
     * made by the preview), so the flip only stores it (F11). A small plan's is written at the flip.
     */
    private void archiveOffMain(Job j) {
        Plan plan = j.plan;
        if (plan == null || j.archivedReady || plan.ops().size() <= OFF_MAIN_OPS) {
            return;
        }
        host.planner().execute(() -> {
            byte[] row = encode(plan);
            inbox.add(() -> {
                if (job == j && j.plan == plan) {
                    j.archived = row;
                    j.archivedReady = true;
                }
            });
        });
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
                    s.preview = new SlotState.Preview(j.half, j.plan, j.day, j.seed, j.mix, j.cadence, j.reroll,
                            j.fallDepth);
                    s.oldDirty = true;
                    end(j);
                    host.logger().info("Fresh Courses: a preview of " + s.def.id() + " (seed " + GenSeed.hex(j.seed)
                            + ") stands in half " + j.half + ".");
                    j.report.accept(previewReady(s, j));
                }
            }
            case RETIRE -> retired(j);
            case CLEAR_OLD -> {
                s.oldDirty = false;
                s.previous = null;
                s.clearing = false;
                end(j);
                pend(s, j.steps.get(0).which(), EMPTY); // F12: known empty on disk after the next saves
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

    /**
     * What an admin reads when a preview is up: how to walk it or try it (a test run; golf is
     * walked), and to use it now ({@code promote}) or, for the next set's, then ({@code choose}).
     */
    private String previewReady(SlotState s, Job j) {
        String id = s.def.id();
        boolean next = isNextSet(s, j.day, j.cadence); // fix2-D: a set's first day and length (D1)
        String look = s.def.golf() ? "Walk it: &e/hcm games gen tp " + id + " idle" : "Try it: &e/hcm games gen test "
                + id + " &7(or walk it: &e/hcm games gen tp " + id + " idle&7)";
        if (!s.classic && !s.wanted()) { // CV final gate: a slot that is off has nothing to promote or choose it for
            return "&aThe preview of " + s.def.name() + (next ? " for " + editionName(j.cadence, j.day) : "") + " is ready"
                    + " in half " + j.half + " (seed " + GenSeed.hex(j.seed) + "). &7" + look + "." + offNote(s);
        }
        return "&aThe preview of " + s.def.name() + (next ? " for " + editionName(j.cadence, j.day) : "") + " is ready in"
                + " half " + j.half + " (seed " + GenSeed.hex(j.seed) + "). &7" + look + "; " + (next ? "use it for that"
                + " set: &e/hcm games gen choose " + id : "make it the current course: &e/hcm games gen promote " + id
                + " &7or next set's: &e/hcm games gen choose " + id);
    }

    /** A claim scan finished: an empty region is claimed; anything else stays refused. */
    private void scanned(Job j) {
        SlotState s = j.slot;
        if (j.found == 0) {
            claimed(s);
            j.claimedHere = s.claimed; // both halves found empty: nothing of an older layout stands here
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
            host.store().meta(GenAdminKeys.claim(s.def.id()), Regions.claim(s.def, s.world, s.origin, s.gap));
            s.claimed = true;
            if (s.problem != null && s.problem.startsWith(FOREIGN)) {
                s.problem = null;
            }
            host.logger().info("Fresh Courses: " + s.def.id() + " claimed " + s.world + " "
                    + Regions.describe(s.def, s.origin, s.gap));
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not record " + s.def.id() + "'s claim", e);
        }
    }

    private void healed(Job j) {
        SlotState s = j.slot;
        if (j.reading && j.plan != null) {
            // F12: what was read against the plan (a sample, or the whole half in the background). Never a read
            // with no plan (ENG00: an older planner's layout), which gets the structure check below.
            j.reading = false;
            if (j.found > 0) {
                differs(j);
                return;
            }
            if (j.sampled) {
                sampledCheck(j);
                return;
            }
            // the whole half in the background, and nothing differs: it ends below, its on-disk fact as it was
        }
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
        s.fullCheckDue = false;
        if (j.plan != null && !planFact(j.plan.hash()).equals(s.onDisk.get(j.half))) {
            pend(s, j.half, planFact(j.plan.hash())); // F12: known on disk after the next saves
        }
        if (!j.background) {
            s.builtAt = host.now();
        }
        String line = (j.background ? "checked the whole of " : "checked ") + s.def.id() + " in half " + j.half + " - "
                + j.writes + " blocks healed";
        if (j.writes > 0 && j.background) {
            // ENG02: it opened on its sample and was never closed; the world was saved (that is why it was sampled)
            host.logger().severe("Fresh Courses: " + line + " (something edited it after its last full check, where"
                    + " the sample at the start didn't look); it stayed open.");
        } else if (j.writes > 0) {
            host.logger().severe("Fresh Courses: " + line + " (the world wasn't saved after the last change, or"
                    + " something edited it); it is open again.");
        } else {
            host.logger().info("Fresh Courses: " + line + "; it is open.");
        }
        s.lastLine = lastLine(j);
        j.report.accept("&a" + s.def.name() + " checked: &7" + j.writes + " blocks healed.");
    }

    /**
     * F12: the sample of a big half known to be on disk as verified is read, and nothing differs: the course
     * opens now, and the whole half is checked once nothing else is being built ({@link SlotState#fullCheckDue}),
     * with the course open.
     */
    private void sampledCheck(Job j) {
        SlotState s = j.slot;
        int read = j.build == null ? 0 : j.build.chunkCount();
        int of = s.half(j.half).chunkCount();
        end(j);
        s.verified = true;
        s.healFailed = false;
        s.fullCheckDue = true;
        s.builtAt = host.now();
        host.logger().info("Fresh Courses: checked " + s.def.id() + " in half " + j.half + " by a sample of " + read
                + " of its " + of + " chunks (it was saved right after its last full check) - it is open; the whole"
                + " half is checked once nothing else is being built.");
        s.lastLine = lastLine(j);
        j.report.accept("&a" + s.def.name() + " checked (a sample of " + read + " chunks): &7open; the whole half is"
                + " checked later.");
    }

    /**
     * F12: a read of a half against its plan found something that differs (the world was edited, or a backup put
     * back). A sample's: the whole half is converged now, closed, as every start checked it before. The background
     * whole-half check's (ENG07): the half is converged now with the course open, its on-disk fact dropped first
     * (as before any write); what it heals is said when it ends.
     */
    private void differs(Job j) {
        SlotState s = j.slot;
        if (j.sampled) {
            host.logger().warning("Fresh Courses: " + s.def.id() + "'s check by a sample found " + j.found + " block"
                    + (j.found == 1 ? "" : "s") + " that differ (first at " + String.join(" ", j.firstFound) + "): the"
                    + " whole half is checked now, before it opens.");
        }
        if (j.build != null) {
            j.build.release();
            j.build = null;
        }
        j.sampled = false;
        j.found = 0;
        j.firstFound = List.of();
        j.steps.clear();
        j.stepIndex = 0;
        j.steps.add(new Step(j.half, j.plan, BuildJob.Mode.CONVERGE));
        j.stage = Stage.CONVERGE;
    }

    /** The golf witness replay on the real blocks (§3.3 step 6); true when proven (or not golf). */
    private boolean proven(Job j) {
        SlotState s = j.slot;
        if (!s.def.golf() || !(j.plan.course() instanceof PlannedGolf g)) {
            return true;
        }
        WorldPort port = host.world(s.world);
        List<List<Putt>> witness = j.kind == Kind.HEAL ? j.tag.witness() : g.witness();
        List<String> problems = port == null ? List.of("the world isn't loaded") // sand plays from golf algo 3
                : LiveProof.replay(port.ballBlocks(LiveBlocks.sandPlays(j.plan.algo())), g.course().holes(), witness);
        if (!problems.isEmpty()) {
            host.logger().severe("Fresh Courses: " + s.def.id() + "'s golf didn't replay on the real blocks: "
                    + String.join("; ", problems));
            fail(j, "the live replay failed: " + problems.get(0));
            return false;
        }
        j.proof = "replay ok " + witness.size() + "/" + witness.size();
        return true;
    }

    /**
     * Structural check for a layout an older planner made: read the row, then the blocks. Each chunk
     * is snapshotted once for the whole check (golf's pond scan reads every block of every hole's plot).
     */
    private List<String> structure(Job j) {
        SlotState s = j.slot;
        WorldPort port = host.world(s.world);
        if (port == null) {
            return List.of("the world isn't loaded");
        }
        Map<Long, Optional<WorldPort.ChunkView>> views = new HashMap<>();
        java.util.function.BiFunction<Integer, Integer, WorldPort.ChunkView> view = (cx, cz) -> views.computeIfAbsent(
                ((long) cx << 32) ^ (cz & 0xFFFFFFFFL), k -> Optional.ofNullable(port.snapshot(cx, cz))).orElse(null);
        LiveProof.Solid solid = (x, y, z) -> {
            WorldPort.ChunkView v = view.apply(x >> 4, z >> 4);
            return v != null && !v.air(x, y, z);
        };
        LiveProof.Solid water = (x, y, z) -> water(view.apply(x >> 4, z >> 4), x, y, z);
        LiveProof.Solid seals = (x, y, z) -> seals(view.apply(x >> 4, z >> 4), x, y, z);
        try {
            GamesDao.CourseRow row = host.store().course(s.def.id());
            if (row == null) {
                return List.of("its row is gone");
            }
            if (s.def.golf()) { // every hole's whole plot, ponds to look at included (§1.3)
                return LiveProof.structure(com.dierks.homecraft.games.golf.CourseCodec.fromRow(row), s.half(j.half),
                        solid, seals, water);
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

    /**
     * Whether block (x, y, z) of a chunk snapshot seals a pond (golf's pond scan,
     * {@link LiveProof#pools}): the plan's own seal test ({@link Pools#seals}), a full block — not a
     * slab, a sign or leaves, which can hold water; false for air or no snapshot.
     */
    static boolean seals(WorldPort.ChunkView v, int x, int y, int z) {
        return v != null && !v.air(x, y, z) && Pools.seals(v.block(x, y, z));
    }

    // ---- the flip -------------------------------------------------------------------------------

    /** One database write makes the new layout live (§3.3 step 7). A failure leaves the old one live. */
    private void flip(Job j) {
        SlotState s = j.slot;
        Slots.Def def = s.def;
        long now = host.now();
        PlannedCourse pc = j.plan.course();
        GenTag tag = tagFor(def, planners.get(def.generator()).id(), j.plan, j.day, j.reroll, j.seed, j.half, j.mix,
                host.settings().stars(), now, j.cadence);
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
            // The archive row lands in the same transaction: every edition that goes live is archived. It was
            // written on the planner thread with the plan (F11); only a small plan's (or a promote's whose
            // planner thread was busy all through its verify pass) is written here.
            byte[] plan = j.archivedReady ? j.archived : encode(j.plan);
            GenStore.Flipped f = host.store().flip(row, meta, archiveEntry(def, tag, plan, j.mix, now), now);
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
        // RePlot (GOLF-V4-SPEC §5.2 step 4): a build that found its region empty and claimed it itself put the
        // first layout there, so the layout before it (an older version's, at its old place) never stood in
        // this region. Nothing of it can be run on here and nothing here needs clearing; its own old area is
        // emptied by RETIRE.
        boolean replot = j.claimedHere;
        s.previous = !replot && beforeStood && before.half() != tag.half() ? before : null;
        s.clearing = false;
        s.oldDirty = !replot && before != null;
        s.preview = null;
        s.fullCheckDue = false;
        pend(s, tag.half(), planFact(j.plan.hash())); // F12: converged and verified: on disk after the next saves
        if (replot) {
            pend(s, tag.otherHalf(), EMPTY); // its claim scan found it empty, and nothing was written there since
        }
        // round 2, G2 #3: a dropped pick's note stays through the flips of the set it was for (the usual
        // drop is at the restart at the change, a minute before this set's own build); droppedUpkeep ends it
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
        if (replot && before != null) {
            host.logger().info("Fresh Courses: " + def.id() + " stands in its new area now ("
                    + Regions.describe(def, s.origin, s.gap) + "): a new course for " + editionName(j.cadence, j.day)
                    + " on a fresh board; its first-finish reward isn't paid twice.");
        }
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

    /**
     * The tag a layout goes live with (its star times from the plan's expert time), for the flip and
     * for an admin's test run of a preview (WP-ADM), so the two can't differ.
     */
    static GenTag tagFor(Slots.Def def, String generator, Plan plan, long day, int reroll, long seed, char half,
                         String mix, DailySettings.Stars factors, long now, int cadence) {
        PlannedCourse pc = plan.course();
        long ref = pc instanceof PlannedTrial t ? t.refMs() : 0;
        long gold = def.golf() ? 0 : Stars.threshold(ref, factors.gold(starTier(def, mix)));
        long silver = def.golf() ? 0 : Stars.threshold(ref, factors.silver(starTier(def, mix)));
        List<Integer> attempts = pc instanceof PlannedGolf g ? g.attempts() : List.of();
        List<List<Putt>> witness = pc instanceof PlannedGolf g ? g.witness() : List.of();
        return new GenTag(def.id(), generator, plan.algo(), day, reroll, seed, half, plan.hash(), ref, gold, silver,
                attempts, witness, now, cadence);
    }

    /**
     * A planned trial as the course the flip makes of it: the slot's id and {@code name}, in
     * {@code world}, with {@code tag} (the row's course, and WP-ADM's test run of a preview).
     */
    static Course trialCourse(Slots.Def def, String world, Course planned, GenTag tag, int rev, boolean pinned,
                              String name) {
        return new Course(def.id(), planned.kind(), name, planned.tier(), world, planned.start(),
                planned.checkpoints(), planned.finish(), planned.fallY(), planned.minSeconds(), true, pinned, rev, tag);
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
        Course c = trialCourse(def, world, planned, tag, rev, pinned, name);
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
            case RETIRE -> {
                // the old area stays recorded and guarded; it is tried again in a few minutes
                s.retireHeld.put(j.retire, "the last try failed (" + why + ")");
                s.retireHeldAt = host.now();
                host.logger().warning("Fresh Courses: emptying " + id + "'s old area failed - " + why + ". It stays"
                        + " guarded and is tried again in " + VET_EVERY_MS / 60_000 + " minutes.");
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
        if (j.kind == Kind.RETIRE) {
            // An old area: nobody plays there, so anyone in it (or within 8 of it) is moved out at once. Never anyone
            // on the slot's own halves (ENG05): a slot resized in place has its course next to (or over) its old
            // area, which beginRetire cut out of the parts, and nothing is written there.
            String world = worldOf(j);
            List<Person> near = people.stream().filter(p -> !onOwnHalves(j, p, world)).toList();
            for (Step st : j.steps) {
                for (Evacuator.Action a : j.evac.step(near, world, st.box(), s.def.id(), false, null, now, deadline)) {
                    act(world, people, a);
                }
            }
            if (j.stage == Stage.EVACUATE) {
                j.stage = Stage.CONVERGE;
            }
            return;
        }
        Box live = s.live == null ? null : s.half(s.live.half());
        for (char h : halves) {
            Box half = s.half(h);
            boolean holdsRun = j.stage == Stage.EVACUATE && (j.kind == Kind.BUILD || j.kind == Kind.PREVIEW
                    || j.kind == Kind.RECALL) && s.previous != null && !s.clearing && s.previous.half() == h;
            for (Evacuator.Action a : j.evac.step(people, s.world, half, s.def.id(), holdsRun, live, now, deadline)) {
                act(s.world, people, a);
            }
            waiting |= Evacuator.waiting(people, s.world, half, s.def.id(), holdsRun, live);
        }
        if (j.stage == Stage.EVACUATE && !waiting) {
            if (s.previous != null && halves.contains(s.previous.half())) {
                s.clearing = true; // its blocks are about to change: it no longer stands
            }
            // fix2-D (D3): so are the preview's, unless this job builds the preview's own plan there
            // (a chosen pick at the change): if it fails or is stopped halfway, a test run or a
            // promote must not start on a half that holds bits of two courses.
            SlotState.Preview pv = s.preview;
            if (pv != null && halves.contains(pv.half())
                    && (j.plan == null || !j.plan.hash().equals(pv.plan().hash()))) {
                s.preview = null;
                s.oldDirty = true; // whatever this job leaves there is emptied once nothing else is due
            }
            j.stage = Stage.CONVERGE;
        }
    }

    /**
     * ENG05: whether {@code p} stands on the halves a RETIRE's slot is claimed in now (in {@code world}): a slot
     * resized in place has its course next to, or over, its old area. Those halves are cut out of what is emptied
     * ({@link #beginRetire}), so nobody there is moved; a write next to them waits for them instead.
     */
    private static boolean onOwnHalves(Job j, Person p, String world) {
        SlotState s = j.slot;
        return j.kind == Kind.RETIRE && s.claimed && s.world.equalsIgnoreCase(world)
                && (p.in(world, s.half('A')) || p.in(world, s.half('B')));
    }

    private void act(String world, List<Person> people, Evacuator.Action a) {
        try {
            switch (a.kind()) {
                case MOVE -> {
                    Person p = find(people, a.player());
                    if (p != null) {
                        moveToSafety(world, p);
                    }
                }
                case TELL -> host.tell(a.player(), a.line());
                case BAR -> host.actionBar(a.player(), a.line());
                case END -> host.endRun(a.player());
            }
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not move a player out of the way in " + world, e);
        }
    }

    /** To {@code safe_spot}, or {@code world}'s spawn (§6.3). */
    private void moveToSafety(String world, Person p) {
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

    /**
     * ENG03: someone joined with no world session and nothing on its way home (a saved-state row sends them home
     * by itself; the host asks a moment after the join, and never for someone flying). An old area's RETIRE, a
     * clear and an unrecall move only who is online, so someone who logged out on a course, or on an old area
     * (the owner walking Golf of the Week before the update), may come back over nothing in a void world. When
     * their feet are in, or within {@value Evacuator#MARGIN} of, a place Fresh Courses empties (any slot's half,
     * an old area, or the last one emptied, whose record stays) and nothing is under them down to the world's
     * floor, they are moved to safety at once, before they fall.
     *
     * @return whether they were moved
     */
    public boolean joined(Person p) {
        if (!running || p == null || p.world() == null || p.world().isBlank() || p.sessionGame() != null) {
            return false;
        }
        String w = p.world();
        WorldPort port = emptiedHere(w, p.x(), p.y(), p.z()) ? host.world(w) : null;
        if (port == null || !overNothing(port, p)) {
            return false;
        }
        moveToSafety(w, p);
        host.logger().info("Fresh Courses: " + p.name() + " joined over an area that was emptied while they were"
                + " away (" + w + " " + (int) Math.floor(p.x()) + "," + (int) Math.floor(p.y()) + ","
                + (int) Math.floor(p.z()) + "): moved to safety before they fell.");
        return true;
    }

    /** Whether (x, y, z) in {@code world} is in, or next to, a place Fresh Courses empties (ENG03). */
    private boolean emptiedHere(String world, double x, double y, double z) {
        List<Box> boxes = new ArrayList<>();
        for (Object[] a : areas) {
            if (world.equalsIgnoreCase((String) a[0])) {
                boxes.add((Box) a[1]); // every slot's and Classic's halves, and every old area still recorded
            }
        }
        for (OldAreas.Retired r : retiredAreas().values()) {
            List<Box> h = Regions.claimHalves(r.claim()); // an old area let go (F09) keeps its record
            if (h != null && world.equalsIgnoreCase(Regions.claimWorld(r.claim()))) {
                boxes.addAll(h);
            }
        }
        for (Box b : boxes) {
            if (b.expand(Evacuator.MARGIN).contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether nothing but air is under a player's feet, in every column their 0.6-wide body stands over, down to
     * the world's floor (ENG03). A chunk that isn't loaded says nothing: nobody is moved on a guess.
     */
    private static boolean overNothing(WorldPort port, Person p) {
        int floor = port.minHeight();
        int top = (int) Math.floor(p.y());
        Map<Long, WorldPort.ChunkView> views = new HashMap<>();
        for (int bx = (int) Math.floor(p.x() - Person.HALF_WIDTH); bx <= (int) Math.floor(p.x() + Person.HALF_WIDTH);
             bx++) {
            for (int bz = (int) Math.floor(p.z() - Person.HALF_WIDTH); bz <= (int) Math.floor(p.z() + Person.HALF_WIDTH);
                 bz++) {
                long key = ((long) (bx >> 4) << 32) | ((bz >> 4) & 0xFFFFFFFFL);
                WorldPort.ChunkView v = views.computeIfAbsent(key, k -> port.snapshot((int) (k >> 32), (int) (long) k));
                if (v == null) {
                    return false;
                }
                for (int y = top; y >= floor; y--) {
                    if (v.sectionEmpty(y)) {
                        y = (y >> 4) << 4; // the rest of an empty section is skipped
                    } else if (!v.air(bx, y, bz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The world an old claim of {@code s} was made in, spelled as the slot's world is when they are the
     * same one (a claim records it in lower case), else as the claim has it; {@code null} when unreadable.
     */
    private static String worldOf(SlotState s, String claim) {
        String w = Regions.claimWorld(claim);
        return w != null && w.equalsIgnoreCase(s.world) ? s.world : w;
    }

    /** The world a job works in: an old area's own (RETIRE), else its slot's. */
    private String worldOf(Job j) {
        return j.world != null ? j.world : j.slot.world;
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
     * Whether a block is in a flow-only box: a kept Dropper's or golf course's plot, a plot job the
     * server stopped halfway, or the plot a keep that may hold water (or any clearing) is working in now.
     */
    boolean inWet(String world, int x, int y, int z) {
        return in(wetPlots, world, x, y, z) || keeper.inWetJob(world, x, y, z);
    }

    /** The host's extra boxes ({@link GenHost#extras}); none when they can't be read. */
    List<Regions.Extra> extras() {
        try {
            List<Regions.Extra> e = host.extras();
            return e == null ? List.of() : e;
        } catch (RuntimeException ex) {
            return List.of();
        }
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

    /**
     * Every change refused: every wanted or claimed half, and the old ones of a moved slot that may hold
     * water ({@link GenRegionGuard}).
     */
    public GenRegionGuard.Area guardArea() {
        return guardArea;
    }

    /**
     * No fluid flowing out: the plots of kept Droppers and golf courses, and such a keep in flight
     * ({@link GenRegionGuard}).
     */
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

    /**
     * The command that must come before a reroll of {@code slotId} can go ahead ({@link #reroll} refuses while
     * either holds the set): {@code /hcm games gen unchoose <id>} while an admin's pick does, {@code /hcm games
     * gen unpin <id>} while a pin does; {@code null} when nothing holds it (audit M11: Race Night's Slalom line
     * never offers a reroll that would be refused).
     */
    public String rerollHeldBy(String slotId) {
        SlotState s = slots.get(slotId);
        if (s == null || activePin(s) == null) {
            return null;
        }
        return (chosenNow(s) != null ? "/hcm games gen unchoose " : "/hcm games gen unpin ") + s.def.id();
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
     * @param moving    ENG-R3-01: it is moving to the area this version gave it, its course rebuilt there
     *                  ({@link #movingByVersion}): expected, not a fault
     */
    public record SlotReport(String id, boolean classic, boolean wanted, boolean on, String problem, boolean claimed,
                             String world, GenTag live, boolean verified, boolean current, boolean building,
                             String lastError, boolean healFailed, boolean moving) {
    }

    /** Every slot, then every Classics slot, as they stand now. Read-only. */
    public List<SlotReport> report() {
        List<SlotReport> out = new ArrayList<>();
        for (SlotState s : slots.values()) {
            boolean current = s.live != null && s.verified && (s.classic ? s.want != null && holds(s, s.want)
                    : target(s).holds(s.live));
            // building: not an old area being emptied (RETIRE), nor the whole-half check of a course that stays
            // open while it runs (ENG02: the course is live, as trouble() and the status line say)
            out.add(new SlotReport(s.def.id(), s.classic, s.wanted(), s.on(), s.problem, s.claimed, s.world, s.live,
                    s.verified, current, job != null && job.slot == s && job.kind != Kind.RETIRE && !job.background,
                    s.lastError, s.healFailed, movingByVersion(s)));
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
        for (OldAreas.Area a : oldAreas()) {
            out.add(a.slot() + ": " + OldAreas.line(a));
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
        if (job != null && job.slot == s && job.kind != Kind.RETIRE && !job.background) {
            return "being built"; // emptying its old area isn't building it (its own line says so)
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
            String pick = chosenLine(s);
            if (pick != null) {
                out.add("  &7" + pick);
            }
            for (OldAreas.Area a : oldAreas()) {
                if (a.slot().equals(s.def.id())) {
                    out.add("  " + (a.state() == OldAreas.State.HELD ? "&c" : a.state() == OldAreas.State.RUNNING ? "&e"
                            : "&7") + OldAreas.line(a));
                }
            }
            if (slotId != null && s.classic) {
                out.add("  &7region " + s.world + " " + Regions.describe(s.def, s.origin, s.gap)
                        + (s.claimed ? " (claimed)"
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
                out.add("  &7region " + s.world + " " + Regions.describe(s.def, s.origin, s.gap)
                        + (s.claimed ? " (claimed)"
                        : " (not claimed yet)"));
                String style = styleLine(s);
                if (style != null) {
                    out.add("  &7" + style);
                }
                if (s.pin != null) {
                    String ignored = pinIgnored(s);
                    out.add("  &7pinned seed " + GenSeed.hex(s.pin.seed()) + (s.pin.until() > 0 ? " until "
                            + date(s.pin.until()) : "") + (ignored == null ? "" : " &c(not used: " + ignored + ")"));
                }
                if (s.preview != null) {
                    out.add("  &7preview in half " + s.preview.half() + ", seed " + GenSeed.hex(s.preview.seed())
                            + (s.preview.day() != target.start() || s.preview.cadence() != target.cadence() ? " (for "
                            + editionName(s.preview.cadence(), s.preview.day()) + ")" : ""));
                }
            }
        }
        if (out.size() <= (slotId == null ? 1 : 0)) {
            out.add("&cNo slot called " + slotId + ".");
        }
        return out;
    }

    /**
     * WP-ADM: "next set: chosen seed 3f2a91c07d1e55b0 (Mon 5 Oct-Sun 11 Oct)" while a choice waits,
     * "this set: chosen seed ..." once it is up; {@code null} for none.
     */
    private String chosenLine(SlotState s) {
        GenScheduler.Choice c = s.chosen;
        if (s.classic) {
            return null;
        }
        if (c == null) {
            // fix2-D: a pick dropped for a change says so; round 2, G2 #3: until its set is over or a new pick
            SlotState.DroppedPick d = s.pickDropped;
            return d == null ? null : "&cpick for " + droppedSet(d) + " dropped: " + d.why() + ". Choose again.";
        }
        Planner p = planners.get(s.def.generator());
        String unused = p != null && c.algo() != p.algo() ? " &c(not used: made for planner v" + c.algo() + ")" : "";
        if (chosenWaiting(s) != null) {
            return "next set: chosen seed " + GenSeed.hex(c.seed()) + " (" + pickSet(c) + ")" + unused;
        }
        return chosenNow(s) != null ? "this set: chosen seed " + GenSeed.hex(c.seed()) : null;
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
        if (job != null && job.slot == s && job.kind != Kind.CLEAR_OLD && job.kind != Kind.RETIRE && !job.background) {
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
        if (j.kind == Kind.RETIRE) {
            return "RETIRE: emptying its old area (" + Regions.describeClaim(j.retire) + ") · " + retirePercent(j) + "%";
        }
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
        return j.kind.name() + (j.background ? " (the whole half, open)" : j.sampled ? " (a sample)" : "") + ": " + what;
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
        BoatStyle want = style(s); // a Mountain Run v2 week's seed is its first of the wanted style, as a build's
        if (arg != null && (arg.equalsIgnoreCase("next") || arg.equalsIgnoreCase("tomorrow"))) {
            day = ed.editionStart(t.endsAt());
            cadence = ed.cadenceDays();
            if (secret() == null) {
                report.accept("&cThe seed secret can't be read right now.");
                return;
            }
            seed = StyleSeed.seed(secret, cadence, day, s.def.id(), 0, want);
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
            seed = StyleSeed.seed(secret, cadence, day, s.def.id(), s.reroll, want);
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
        preview(slotId, seedText, null, report);
    }

    /**
     * {@link #preview(String, String, Consumer)} of the style {@code style} ({@code style:road|slalom}, Ice Boat
     * only): with no seed, the first of this set's next-reroll seeds of that style, by the same search a
     * build makes; {@code null} for the style a build would pick (Ice Boat's {@code style}, or the Winding
     * Road while Race Night is on). A typed seed is used as given.
     */
    @Override
    public void preview(String slotId, String seedText, BoatStyle style, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (!styleFits(s, style, report) || !readyToTry(s, report)) { // CV final gate: an off slot can be previewed
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
            seed = StyleSeed.seed(secret, t.cadence(), t.start(), s.def.id(), s.reroll + 1,
                    style != null ? style : style(s));
        }
        Job j = new Job(Kind.PREVIEW, s, report);
        j.day = t.start();
        j.cadence = t.cadence();
        j.reroll = s.reroll + 1;
        j.seed = seed;
        j.mix = s.mix;
        queue.add(j);
        report.accept("&7A preview of " + s.def.name() + " (seed " + GenSeed.hex(seed) + ") is on its way into half "
                + s.idleHalf() + "." + styleNote(s, seed, style) + choiceStays(s) + offNote(s));
    }

    /**
     * Ice Boat's style line for its status (MOUNTAIN-V2-SPEC §5.1): "style random: the Winding Road every week
     * while Race Night is on" and the like; {@code null} for every other slot and for Ice Boat still in its old
     * box (the spiral has no styles).
     */
    private String styleLine(SlotState s) {
        if (!Slots.BOAT.equals(s.def.generator()) || !BoatPlanner.mountain(s.half('A'))) {
            return null;
        }
        DailySettings.SlotConfig c = host.settings().slot(s.def.id());
        BoatStyle config = c == null ? null : c.style();
        BoatStyle want = style(s);
        BoatStyle got = s.live == null || s.live.algo() < BoatPlanner.ALGO ? null : BoatStyle.of(s.live.seed());
        String live = got == null ? "" : "; this set's is " + StyleSeed.words(got);
        if (got != null && want != null && got != want) {
            // audit M10: made before the rule applied (Race Night or the style switched on after the set was built,
            // or an admin's seed); never rerolled silently, so say how
            String held = rerollHeldBy(s.def.id());
            live += held != null ? " &e(held: " + held + ", then /hcm games gen reroll " + s.def.id() + " confirm makes"
                    + " it " + StyleSeed.words(want) + ")"
                    : " &e(made before; the next set is " + StyleSeed.words(want) + ", or /hcm games gen reroll "
                    + s.def.id() + " confirm makes this one now)";
        }
        if (config != null) {
            return "style " + config.id() + ": " + StyleSeed.words(config) + " every week" + live;
        }
        return "style random: " + (want == BoatStyle.ROAD ? "the Winding Road every week while Race Night is on"
                : "each week's own (Winding Road or Slalom)") + live;
    }

    /**
     * Whether {@code style:} can be asked of {@code s}: none asked, or Ice Boat in a Mountain Run v2 area. Says
     * why not otherwise.
     */
    private boolean styleFits(SlotState s, BoatStyle style, Consumer<String> report) {
        if (style == null) {
            return true;
        }
        if (s == null || !Slots.BOAT.equals(s.def.generator())) {
            report.accept("&cOnly Ice Boat has styles (style:road or style:slalom).");
            return false;
        }
        if (!BoatPlanner.mountain(s.half('A'))) {
            report.accept("&c" + s.def.name() + " is still in its old 128-block area, where it makes the spiral"
                    + " Mountain Run: that has no styles.");
            return false;
        }
        return true;
    }

    /**
     * " It is the Winding Road." after a Mountain Run v2 preview's line (its style, read off the seed), with a
     * note when a typed seed isn't the {@code asked} style; {@code ""} for any other slot.
     */
    private String styleNote(SlotState s, long seed, BoatStyle asked) {
        if (!Slots.BOAT.equals(s.def.generator()) || !BoatPlanner.mountain(s.half('A'))) {
            return "";
        }
        BoatStyle got = BoatStyle.of(seed);
        return " &7It is " + StyleSeed.words(got) + (asked != null && asked != got ? " (that seed is used as typed,"
                + " so it isn't " + StyleSeed.words(asked) + ")." : ".");
    }

    /** WP-ADM: what a preview, reroll or promote adds while a choice waits: it stays chosen. */
    private String choiceStays(SlotState s) {
        GenScheduler.Choice c = chosenWaiting(s);
        return c == null ? "" : " &7Your pick for " + pickSet(c) + " (seed "
                + GenSeed.hex(c.seed()) + ") stays chosen; it is built again at the change.";
    }

    /** WP-ADM: why a pinned slot can't be rerolled or promoted, naming the pin or the choice. */
    private String pinnedLine(SlotState s) {
        String id = s.def.id();
        return chosenNow(s) != null ? "&c" + s.def.name() + " is on the seed you chose for this set. &7/hcm games gen"
                + " unchoose " + id + " first." : "&c" + s.def.name() + " is pinned. &7/hcm games gen unpin " + id
                + " first.";
    }

    @Override
    public void promote(String slotId, boolean confirm, Consumer<String> report) {
        promote(slotId, null, confirm, report);
    }

    /**
     * Round 2, G2 #1: a "Sure?" screen's Yes (or a typed seed) acts only on the preview it showed. The
     * screen can stay open while a whole new preview is built, by another admin, the console, or the
     * admin's own one still planning when the tools were painted; the preview that stands when Yes is
     * pressed then is a course nobody tried.
     *
     * @return the line refusing it, or {@code null} when {@code pv} is still the preview {@code shown}
     */
    private String changedSince(SlotState s, SlotState.Preview pv, Shown shown) {
        if (shown == null || pv == null || pv.seed() == shown.preview()) {
            return null;
        }
        return "&eThe preview of " + s.def.name() + " changed while that was open: it is seed " + GenSeed.hex(pv.seed())
                + " now, not " + GenSeed.hex(shown.preview()) + ". &7Nothing was done. Look at it (and try it) again"
                + " first.";
    }

    @Override
    public void promote(String slotId, Shown shown, boolean confirm, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        String off = offUse(s, "promote"); // CV final gate: previews, not promotes, while it is off
        if (off != null) {
            report.accept(off);
            return;
        }
        if (!ready(s, report)) {
            return;
        }
        SlotState.Preview pv = s.preview;
        GenScheduler.Target t = target(s);
        if (pv == null) {
            report.accept("&cThere is no preview of " + s.def.name() + ". &7/hcm games gen preview " + slotId);
            return;
        }
        String changed = changedSince(s, pv, shown);
        if (changed != null) {
            report.accept(changed);
            return;
        }
        if (isNextSet(s, pv.day(), pv.cadence())) {
            report.accept("&cThat preview is for " + editionName(pv.cadence(), pv.day()) + ". &7Use it then: &e/hcm games"
                    + " gen choose " + slotId);
            return;
        }
        if (pv.day() != t.start() || pv.cadence() != t.cadence()) {
            report.accept("&cThat preview was made for " + editionName(pv.cadence(), pv.day()) + ". &7Make a new one.");
            return;
        }
        if (activePin(s) != null) {
            report.accept(pinnedLine(s));
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
        report.accept("&7Making the preview of " + s.def.name() + " the current course..." + choiceStays(s));
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
            report.accept(pinnedLine(s));
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
                + " its own board." + choiceStays(s));
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
        boolean wasOff = !s.wanted();
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
            report.accept("&a" + s.def.name() + " is on." + (s.problem == null ? onOverPreview(s, wasOff)
                    : " &cBut: &7" + s.problem));
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

    /**
     * CV final gate: what switching on a Fresh slot with a preview standing does to it (the owner's preview
     * made while it was off): with no course of this set up, the set's own is built next, in the spare half
     * the preview stands in, and opens once it is built. {@code ""} otherwise. A preview still on its way
     * there ({@code wasOff}: asked for while it was off, queued or being built) is treated the same: the
     * build would write over it the moment it was ready, so it is dropped now ({@link #dropPreview}) and
     * never said to be ready to try or promote.
     */
    private String onOverPreview(SlotState s, boolean wasOff) {
        SlotState.Preview pv = s.preview;
        GenScheduler.Target t = target(s);
        if (s.classic || t.holds(s.live)) {
            return "";
        }
        boolean dropped = wasOff && dropPreview(s);
        if (pv == null && !dropped) {
            return "";
        }
        return " &7" + (dropped ? "The preview on its way is dropped: its" : "Its") + " course for "
                + editionName(t.cadence(), t.start()) + " is built next" + (pv != null && pv.half() == s.idleHalf()
                ? ", in half " + pv.half() + " over the preview there," : dropped ? ", in half " + s.idleHalf() + ","
                : "") + " and opens once it's built.";
    }

    /**
     * CV final gate: stop the preview queued or being built for {@code s} (one at most: a preview waits for
     * nothing else of its slot's), as {@code off} stops its jobs. @return whether there was one
     */
    private boolean dropPreview(SlotState s) {
        boolean queued = queue.removeIf(j -> j.slot == s && j.kind == Kind.PREVIEW);
        if (job != null && job.slot == s && job.kind == Kind.PREVIEW) {
            cancel(job, "it was switched on before the preview was ready");
            return true;
        }
        return queued;
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
        String dropped = chosenUpkeep(s); // fix2-D (D0): a pick tried at another tier would come out different
        report.accept("&a" + s.def.name() + " will be " + t + " &7from its next build (the next set, or &e/hcm games"
                + " gen reroll " + slotId + "&7)." + (dropped == null ? "" : " " + dropped));
    }

    @Override
    public void pin(String slotId, String seedText, int days, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        Planner p = planners.get(s.def.generator());
        long seed;
        String remade = null;
        if (seedText.equalsIgnoreCase("live") || seedText.equalsIgnoreCase("today")) {
            if (s.live == null) {
                report.accept("&c" + s.def.name() + " has no course to pin yet.");
                return;
            }
            if (p != null && s.live.algo() != p.algo()) {
                // CV final gate: a pin is the seed for today's planner, so on a layout an older one made (the
                // upgrade's algo-2 sets) it would put up a different course at the next set, "pinned"
                report.accept("&c" + s.def.name() + "'s course up now was made by an older planner (" + s.def.generator()
                        + " v" + s.live.algo() + "; this is v" + p.algo() + "), so it can't be made again after the"
                        + " update: a pin would build a different course from its seed. &7Nothing was pinned. It stays"
                        + " up until its set (" + editionName(s.live.cadence(), s.live.day()) + ") ends; to keep it for"
                        + " good: &e/hcm games gen keep " + slotId + " current <new-id> confirm");
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
            remade = p == null ? null : olderEdition(s, seed, p.algo());
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
        if (remade != null) {
            report.accept(remade);
        }
    }

    /**
     * CV final gate: the warning for a pin on a seed that an archived edition of {@code s} was made from by
     * another planner version (copied from {@code history}, say): the pin makes a new course from that seed
     * with today's planner, not that one; and how to have that one as it was ({@link #asItWas}). {@code null}
     * when no such edition is archived (or it can't be read).
     */
    private String olderEdition(SlotState s, long seed, int algo) {
        try {
            for (GenArchiveDao.Row r : host.store().editionsBySeed(s.def.id(), GenSeed.hex(seed))) {
                if (r.seed() == seed && r.algoVersion() != algo) {
                    return "&eSeed " + GenSeed.hex(seed) + " was " + r.code() + " (" + r.name() + ", " + dates(r)
                            + "), made by " + s.def.generator() + " planner v" + r.algoVersion() + ". &7This pin makes a"
                            + " new course from that seed with planner v" + algo + ", not that one. " + asItWas(r);
                }
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not look the pinned seed up in the archive", e);
        }
        return null;
    }

    /**
     * How to have an archived edition back as it was, by a command {@link #recall} or {@link #keep} takes: the
     * course up now can't be recalled, so, as {@code pin live} says, it stays until its set ends and {@code keep
     * <course> current} keeps it; one whose set is over is recalled when its Classics slot is on, else (Ice Boat
     * has none, or that slot is off) kept by its code.
     */
    private String asItWas(GenArchiveDao.Row r) {
        if (r.live()) {
            return r.code() + " stays up until its set (" + editionName(Edition.Key.parse(r.edition()), r.day())
                    + ") ends; to keep it for good: &e/hcm games gen keep " + r.slot() + " current <new-id> confirm";
        }
        Slots.Def classic = Slots.classicFor(Slots.of(r.slot()));
        SlotState c = classic == null ? null : slots.get(classic.id());
        if (c == null || !c.on()) {
            return "To keep " + r.code() + " as it was, for good: &e/hcm games gen keep " + r.code()
                    + " <new-id> confirm";
        }
        return "To bring " + r.code() + " back as it was: &e/hcm games gen recall " + r.code();
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

    // ---- picking a good course: preview next, try it, choose it (WP-ADM) --------------------------------

    @Override
    public void previewNext(String slotId, String seedText, Consumer<String> report) {
        previewNext(slotId, seedText, null, report);
    }

    /**
     * {@link #previewNext(String, String, Consumer)} of the style {@code style} ({@code style:road|slalom}, Ice
     * Boat only): a random candidate of that style ({@code null}: of the style a build would pick). A typed
     * seed is used as given.
     */
    @Override
    public void previewNext(String slotId, String seedText, BoatStyle style, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (!styleFits(s, style, report) || !readyToTry(s, report)) { // CV final gate: an off slot can be previewed
            return;
        }
        Long seed = seedText == null ? null : GenSeed.parse(seedText);
        if (seedText != null && seed == null) {
            report.accept("&cA seed is up to 16 hex digits, like 3f2a91c07d1e55b0.");
            return;
        }
        if (seed == null) { // a random candidate (of the style the next build would have, or the one asked)
            seed = StyleSeed.random(java.util.concurrent.ThreadLocalRandom.current()::nextLong,
                    style != null ? style : style(s));
        }
        NextSet n = nextSet(s);
        Job j = new Job(Kind.PREVIEW, s, report);
        j.day = n.day();
        j.cadence = n.cadence();
        j.reroll = 0; // exactly as the next set's build would make it (a pinned build is never a reroll)
        j.seed = seed;
        j.mix = s.mix; // what the next build uses: config, or the admin's tier or mix
        queue.add(j);
        report.accept("&7A preview of " + s.def.name() + " for " + editionName(n.cadence(), n.day()) + " (" + s.mix
                + ", seed " + GenSeed.hex(seed) + ") is on its way into half " + s.idleHalf() + "."
                + styleNote(s, seed, style)
                + choiceStays(s) // fix2-D (D6): building another candidate doesn't replace the pick
                + offNote(s));
    }

    @Override
    public void choose(String slotId, boolean confirm, Consumer<String> report) {
        choose(slotId, null, confirm, report);
    }

    @Override
    public void choose(String slotId, Shown shown, boolean confirm, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (s == null || s.classic) {
            report.accept("&cA Classics slot holds a course brought back with recall; choose is for Fresh Courses.");
            return;
        }
        String off = offUse(s, "choose"); // CV final gate: previews, not picks, while it is off
        if (off != null) {
            report.accept(off);
            return;
        }
        if (!s.on()) {
            report.accept("&c" + s.def.name() + " is off" + (s.problem == null ? "." : ": &7" + s.problem));
            return;
        }
        if (busyWith(s)) {
            report.accept("&c" + s.def.name() + " is being built right now; try when it's done.");
            return;
        }
        SlotState.Preview pv = s.preview;
        Planner p = planners.get(s.def.generator());
        if (pv == null || p == null) {
            report.accept("&cNo preview yet - /hcm games gen preview " + slotId + " next first");
            return;
        }
        String changed = changedSince(s, pv, shown); // round 2, G2 #1
        if (changed != null) {
            report.accept(changed);
            return;
        }
        NextSet n = nextSet(s);
        String set = editionName(n.cadence(), n.day());
        if (!pv.mix().equals(s.mix)) {
            report.accept("&cThat preview was made as " + pv.mix() + ", and " + set + " will be " + s.mix + ", so it would"
                    + " come out different. &7Make a new one: &e/hcm games gen preview " + slotId + " next");
            return;
        }
        // fix2-D (D0): the pick keeps what shapes its course, so a later change drops it instead of
        // building the seed into something nobody tried; a preview made at a fall depth that shapes the
        // layout differently is refused as one made at another tier is (one only the play-time fall
        // floor differs for is the same course, and is taken).
        int depth = pickDepth(s, host.fallDepth());
        if (pv.fallDepth() > 0 && pickDepth(s, pv.fallDepth()) != depth) {
            report.accept("&cThat preview was made with fall_depth " + pv.fallDepth() + ", and it is "
                    + host.fallDepth() + " now, so it would come out different. &7Make a new one: &e/hcm games gen"
                    + " preview " + slotId + " next");
            return;
        }
        GenScheduler.Choice was = chosenWaiting(s);
        // round 2, G2 #1: a Sure screen's confirm replaces only the pick it showed (or none); any other
        // pick, made while it was open, gets this warning as if nothing had been confirmed
        boolean replaces = confirm && (shown == null || !shown.pickShown()
                || java.util.Objects.equals(shown.pick(), was == null ? null : was.seed()));
        if (!replaces && was != null && was.seed() != pv.seed()) {
            report.accept("&e" + s.def.name() + " already has seed " + GenSeed.hex(was.seed()) + " chosen for "
                    + pickSet(was) + ". &7Type &e/hcm games gen choose " + slotId + " confirm &7to"
                    + " use this preview's seed " + GenSeed.hex(pv.seed()) + " instead.");
            return;
        }
        GenScheduler.Choice pick = new GenScheduler.Choice(pv.seed(), p.algo(), n.day(), n.cadence(), s.mix, depth);
        try {
            host.store().meta(GenAdminKeys.choose(slotId), pick.text());
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            host.logger().log(Level.WARNING, "Fresh Courses: could not store a chosen seed", e);
            return;
        }
        s.chosen = pick;
        dropNote(s, null);
        host.logger().info("Fresh Courses: " + slotId + "'s course for " + set + " is chosen: seed "
                + GenSeed.hex(pick.seed()) + ".");
        report.accept("&a" + s.def.name() + "'s course for " + set + " is this preview (seed " + GenSeed.hex(pick.seed())
                + "). &7It goes up at the change on fresh boards, with its own course code; the set after goes back"
                + " to normal." + (s.pin != null ? " Its pin comes back after it." : "") + " &e/hcm games gen unchoose "
                + slotId + " &7cancels it.");
    }

    @Override
    public void unchoose(String slotId, Consumer<String> report) {
        SlotState s = slots.get(slotId);
        if (s == null || s.classic || s.chosen == null) {
            report.accept("&7" + (s == null ? "That course" : s.def.name()) + " has no chosen course.");
            return;
        }
        boolean upNow = chosenNow(s) != null;
        try {
            host.store().meta(GenAdminKeys.choose(slotId), null);
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            return;
        }
        s.chosen = null;
        dropNote(s, null);
        // fix2-D (D5): once the pick up now is let go, reroll and promote are refused while the slot's own
        // pin holds for this set (choose may pick over a pin), so they are offered only when none does
        String next = ownCourse(s, nextSet(s).day());
        String then = !upNow ? "The next set gets " + next + "."
                : "The chosen course that is up now stays until the next set, which gets " + next + "; "
                + (activePin(s) == null ? "&e/hcm games gen regenerate " + slotId + " &7and &epromote &7work on it now."
                : s.def.name() + " is pinned too, so regenerate and promote wait for &e/hcm games gen unpin " + slotId
                + "&7.");
        report.accept("&a" + s.def.name() + "'s pick is cancelled. &7" + then);
    }

    @Override
    public PreviewRun previewRun(String slotId) {
        SlotState s = slots.get(slotId);
        if (s == null || s.classic) {
            return PreviewRun.refused("&cOnly a Fresh Course has previews.");
        }
        if (!running || readyAt < 0) {
            return PreviewRun.refused("&cFresh Courses is still starting; try in a moment.");
        }
        if (s.problem != null) { // CV final gate: switched off with nothing in the way, its preview can be tried
            return PreviewRun.refused("&c" + s.def.name() + " is off: &7" + s.problem);
        }
        if (busyWith(s)) {
            return PreviewRun.refused("&c" + s.def.name() + " is being built right now; try when it's done.");
        }
        SlotState.Preview pv = s.preview;
        if (pv == null) {
            return PreviewRun.refused("&cNo preview yet - /hcm games gen preview " + slotId + " first");
        }
        if (s.def.golf() || !(pv.plan().course() instanceof PlannedTrial)) { // no golf test round exists (yet)
            return PreviewRun.refused("&cWalk it with /hcm games gen tp " + slotId + " idle - golf previews can't be"
                    + " test-played yet.");
        }
        return new PreviewRun(previewCourse(s, pv), null);
    }

    /**
     * The preview as the course its flip would make: the same conversion ({@link #tagFor},
     * {@link #trialCourse}), in the slot's world and the idle half the preview stands in, under the
     * name of its set. It lives only for an admin's test run: no row, no board, never live
     * ({@link #live} refuses its tag), so nothing a run on it does is recorded.
     */
    Course previewCourse(SlotState s, SlotState.Preview pv) {
        Planner p = planners.get(s.def.generator());
        GenTag tag = tagFor(s.def, p == null ? s.def.generator() : p.id(), pv.plan(), pv.day(), pv.reroll(), pv.seed(),
                pv.half(), pv.mix(), host.settings().stars(), host.now(), pv.cadence());
        return trialCourse(s.def, s.world, ((PlannedTrial) pv.plan().course()).course(), tag, 1, false,
                GenCopy.slotName(s.def, pv.cadence()));
    }

    @Override
    public Tools tools(String slotId) {
        SlotState s = slotId == null ? null : slots.get(slotId);
        if (s == null || s.classic || !running) {
            return null;
        }
        SlotState.Preview pv = s.preview;
        // fix2-D: the pick up now or the one still to come, and which (D5); the preview's set by its
        // first day and length (D1)
        GenScheduler.Choice up = chosenNow(s);
        GenScheduler.Choice c = up != null ? up : chosenWaiting(s);
        int cadence = edition().cadenceDays();
        SlotState.DroppedPick d = s.chosen == null ? s.pickDropped : null; // round 2, G2 #3
        return new Tools(s.on(), s.def.golf(), cadence, pv == null ? null : pv.seed(),
                pv != null && isNextSet(s, pv.day(), pv.cadence()), c == null ? null : c.seed(),
                c == null ? null : pickSet(c), busyWith(s), up != null, ownPin(s, target(s).start()) != null,
                d == null ? null : "Your pick for " + droppedSet(d) + " (seed " + GenSeed.hex(d.seed()) + ") was"
                        + " dropped: " + d.why() + ".");
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
        report.accept(confirm ? "&7Clearing " + s.def.name() + "'s area ("
                + Regions.describe(s.def, s.origin, s.gap)
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

    /**
     * A preview may start: as {@link #ready}, and on a Fresh slot that is switched off too (CV final gate:
     * the owner's "Gate 0 and a preview before switching it on", Ice Boat ships off). Off, it must pass the
     * checks a build passes (§2.4: {@link #claimProblem}) or its standing problem (foreign blocks) is said;
     * the preview's job then scans and claims an unclaimed area first, like a build, builds into the spare
     * half only and never flips, so nothing opens to players ({@link #offNote}).
     */
    private boolean readyToTry(SlotState s, Consumer<String> report) {
        if (s.classic || s.wanted()) {
            return ready(s, report);
        }
        if (!running || readyAt < 0) {
            report.accept("&cFresh Courses is still starting; try in a moment.");
            return false;
        }
        String why = s.problem != null ? s.problem : claimProblem(s);
        if (why != null) {
            report.accept("&c" + s.def.name() + " can't be previewed: &7" + why);
            return false;
        }
        if (busyWith(s)) {
            report.accept("&c" + s.def.name() + " is being built right now; try when it's done.");
            return false;
        }
        return true;
    }

    /**
     * CV final gate: what a preview of a Fresh slot that is switched off adds to its replies: it stays off
     * (only an admin's test run plays it, nothing is recorded), and what switching it on does instead.
     * {@code ""} for a slot that is on.
     */
    private String offNote(SlotState s) {
        if (s.classic || s.wanted()) {
            return "";
        }
        return " &7" + s.def.name() + " stays off: only an admin's test run can play the preview, and nothing is"
                + " recorded or paid. Switching it on (&e/hcm games gen on " + s.def.id() + "&7) opens its own course"
                + " for this set, not the preview.";
    }

    /**
     * CV final gate: why a preview of a Fresh slot that is switched off can't be put up ({@code promote}) or
     * picked ({@code choose}), and what to do instead; {@code null} for a slot that is on (or a Classic).
     * A pick kept while it is off would not be what opens at the switch-on (the set's own course is built
     * then), so neither is taken: previews and test runs are what an owner does before switching it on.
     */
    private String offUse(SlotState s, String verb) {
        if (s == null || s.classic || s.wanted()) {
            return null;
        }
        String id = s.def.id();
        return "&c" + s.def.name() + " is off, so no preview can be " + (verb.equals("choose") ? "picked for a set"
                : "made its course") + ". &7Previews and test runs work while it's off. &e/hcm games gen on " + id
                + " &7opens it with its own course for this set; then &e/hcm games gen preview " + id + " next&7, try"
                + " it and &echoose &7it for the next set, or &epreview&7, try and &epromote &7one now.";
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
        if (!orig.mayHoldWater()) {
            recallProven(j, moved, List.of(), null);
            return;
        }
        // A moved plan that may hold water (a dropper, golf) is proven again where it stands
        // (PlanCheck.movedProblems: its sealed pools or ponds, its solvability or witness lines), on the
        // planner thread (like PlanCheck.generator), the answer back through the inbox.
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
            Long to = pinnedForever(s) ? null : target(s).endsAt();
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
            return s == null || s.live == null || pinnedForever(s) ? null : target(s).endsAt();
        }, recalledNow(), per, host.now(), showNames, host::playerName);
    }

    /**
     * Whether the live course stays for good: pinned with no end, and no choice (WP-ADM) is up now
     * or waiting (a chosen set changes the course, and the set after it again).
     */
    private boolean pinnedForever(SlotState s) {
        return s.pin != null && s.pin.until() <= 0 && s.chosen == null;
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
        historyOf(slotId, which, out::add, false);
        return out;
    }

    /** As {@link #historyOf(String, GenArgs.Which)}, a big stored plan read on the planner thread (F11). */
    @Override
    public void historyOf(String slotId, GenArgs.Which which, Consumer<String> report) {
        historyOf(slotId, which, report, true);
    }

    private void historyOf(String slotId, GenArgs.Which which, Consumer<String> report, boolean offMain) {
        String slot = slotId != null ? slotId : which.slot();
        GenArchiveDao.Row r = resolve(slot, which, report);
        if (r == null) {
            return;
        }
        readStored(r.plan(), offMain, read -> historyLines(r, read).forEach(report));
    }

    /** One past course's lines: what it was, its plan ({@code read}), its players and top 5. */
    private List<String> historyLines(GenArchiveDao.Row r, PlanCodec.Read read) {
        List<String> out = new ArrayList<>();
        Slots.Def d = Slots.of(r.slot());
        String game = d == null ? Slots.GAME_TRIALS : d.game();
        Edition.Key key = Edition.Key.parse(r.edition());
        out.add("&6" + r.code() + " &7- &f" + r.name() + " &7(" + editionName(key, r.day()) + ", edition "
                + r.edition() + ")" + flags(r));
        out.add("&7Up " + dates(r) + " · " + r.kind() + " " + r.tierOrMix() + " · seed " + GenSeed.hex(r.seed())
                + " · " + r.algo() + " · built " + GenCopy.whenDated(r.builtAt(), host.zone()));
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
        // ENG01: an area it doesn't hold is never cleared, so the admin isn't told it will be
        report.accept("&a" + c.name() + " is closed. &7" + (s.claimed ? "Its halves are cleared once nobody is on them."
                : "Its area isn't claimed, so nothing is cleared there."));
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
        if (which.remade()) {
            keeper.keep(row, true, id, name, freshBoard, confirm, report);
            return;
        }
        readStored(row.plan(), true, read -> { // a Mountain Run's row is read on the planner thread (F11)
            if (!read.ok()) {
                report.accept("&c" + row.code() + "'s stored plan can't be read (" + read.problem() + "), so it can't"
                        + " be kept as it was.");
                report.accept("&7Make it again from its seed with today's generator (marked re-made): &e/hcm games gen"
                        + " keep " + row.slot() + " seed:" + GenSeed.hex(row.seed()) + " " + id);
                return;
            }
            keeper.keep(row, false, id, name, freshBoard, confirm, report);
        });
    }

    /**
     * Read a stored plan, then go on with {@code then} on the main thread: a big row (over
     * {@value #OFF_MAIN_BYTES} bytes, a Mountain Run v2's) on the planner thread when {@code offMain}, its
     * answer back through the inbox (F11); a small one here and now, as always.
     */
    private void readStored(byte[] stored, boolean offMain, Consumer<PlanCodec.Read> then) {
        if (!offMain || stored == null || stored.length <= OFF_MAIN_BYTES) {
            then.accept(PlanCodec.decode(stored));
            return;
        }
        host.planner().execute(() -> {
            PlanCodec.Read read = PlanCodec.decode(stored);
            inbox.add(() -> then.accept(read));
        });
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

    /** {@link #warnOnce}, as an INFO line: news, not a problem. */
    private void sayOnce(SlotState s, String line) {
        if (said.add(s.def.id() + "|" + line)) {
            host.logger().info(line);
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
