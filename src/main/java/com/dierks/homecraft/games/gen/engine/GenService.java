package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlannedCourse;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.PlannedTrial;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.CourseCodec;
import com.dierks.homecraft.storage.GamesDao;

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
    /** The counted work each generator may use per plan. */
    static final Map<String, Long> WORK = Map.of(Slots.PARKOUR, 200_000L, Slots.RINGS, 200_000L, Slots.GOLF,
            2_500_000L, Slots.BOAT, 200_000L);

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
        DECOMMISSION
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
     * @param planners each generator's planner by its id ({@code parkour}, {@code rings}, {@code golf},
     *                 {@code boat}); a slot whose planner is missing never builds
     */
    public GenService(GenHost host, Map<String, Planner> planners) {
        this.host = host;
        this.planners = Map.copyOf(planners);
        for (Slots.Def d : Slots.ALL) {
            slots.put(d.id(), new SlotState(d));
        }
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
            if (s.live == null || !s.on()) {
                continue;
            }
            if (s.claimed) {
                s.oldDirty = true; // unknown after a restart: emptied once nothing else is due
                queue.add(new Job(Kind.HEAL, s, null));
            } else {
                s.healFailed = true;
                warnOnce(s, "Fresh Courses: " + s.def.id() + "'s live course isn't in its claimed region "
                        + "- a new one will be built");
            }
        }
    }

    /** Stop: give up the running job (its tickets go), forget the queue. The blocks stay as they are. */
    public void stop() {
        running = false;
        if (job != null) {
            cancel(job, "Fresh Courses stopped");
        }
        queue.clear();
        inbox.clear();
        areas = List.of();
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
        RestartHold hold = host.restartHold();
        if (GenScheduler.abandon(now, hold)) {
            if (job != null) {
                cancel(job, "a restart is less than 2 minutes away - it goes on after the restart");
            }
            return; // nothing starts this close to a restart
        }
        if (job != null) {
            evacuate(job);
            return;
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
            // the boot check): check it now, before anything else.
            if (s.on() && s.live != null && !s.verified && !s.healFailed && s.claimed) {
                begin(new Job(Kind.HEAL, s, null));
                return;
            }
        }
        for (SlotState s : slots.values()) {
            if (!s.on()) {
                s.waiting = null;
                continue;
            }
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
                        if (!Evacuator.anyone(host.people(), s.world, s.half(s.idleHalf()))) {
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
        if (clear != null) {
            begin(new Job(Kind.CLEAR_OLD, clear, null));
        }
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
        for (SlotState s : slots.values()) {
            DailySettings.SlotConfig c = st.slot(s.def.id());
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
                    warnOnce(s, "Fresh Courses: " + id + " was claimed at another place ("
                            + (old == null ? claim : Regions.describe(s.def, old)) + "). Those blocks are left"
                            + " as they are: clear them by hand. The new region is checked before it is used.");
                }
                s.claimed = claimed;
            }
            if (s.wanted() || s.claimed) {
                for (char h : new char[]{'A', 'B'}) {
                    kept.add(new Object[]{world, s.half(h)});
                }
            }
        }
        areas = List.copyOf(kept);
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
                + ". The old halves were not cleared (use /hcm games gen clear before moving a course).");
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
            if (tag == null || !s.def.id().equals(tag.slot())) {
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
                    j.steps.add(new Step('A', null, BuildJob.Mode.SCAN));
                    j.steps.add(new Step('B', null, BuildJob.Mode.SCAN));
                    j.stage = Stage.SCANNING;
                }
                case CLAIM, DECOMMISSION -> {
                    j.steps.add(new Step('A', null, BuildJob.Mode.CONVERGE));
                    j.steps.add(new Step('B', null, BuildJob.Mode.CONVERGE));
                    j.stage = Stage.EVACUATE;
                    j.evacStart = host.now();
                }
            }
        } catch (RuntimeException e) {
            fail(j, "it couldn't start (" + e + ")");
        }
    }

    private void beginHeal(Job j) {
        SlotState s = j.slot;
        String why = vet(s, handBuilt());
        if (why != null || s.live == null) {
            end(j);
            j.report.accept("&c" + s.def.name() + " can't be checked: &7" + (why == null ? "it has no course" : why));
            return;
        }
        j.tag = s.live;
        j.half = s.live.half();
        j.day = s.live.day();
        j.cadence = s.live.cadence();
        j.reroll = s.live.reroll();
        j.seed = s.live.seed();
        j.mix = s.liveMix;
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
        Box half = s.half(j.half);
        PlanInput in = new PlanInput(s.def, half, j.half, j.day, j.reroll, j.seed, j.mix, host.fallDepth(),
                WORK.getOrDefault(s.def.generator(), 200_000L), cancelled(j.cancelled));
        j.stage = Stage.PLANNING;
        j.planStarted = host.now();
        boolean heal = j.kind == Kind.HEAL;
        GenTag tag = j.tag;
        host.planner().execute(() -> {
            long t0 = System.nanoTime();
            Plan made = null;
            Throwable error = null;
            try {
                made = heal ? p.rederive(in, tag) : p.plan(in);
            } catch (Throwable e) {
                error = e;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            Plan result = made;
            Throwable failure = error;
            inbox.add(() -> planned(j, result, failure, ms));
        });
    }

    /**
     * The planner's "stop now" check, which also gives the server room: while anyone is online the
     * planner sleeps 2 ms after every 10 ms of work (§3.3 step 1).
     */
    private BooleanSupplier cancelled(AtomicBoolean flag) {
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

    private void planned(Job j, Plan plan, Throwable error, long ms) {
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
        List<String> problems = PlanCheck.problems(plan, s.def, s.half(j.half));
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
            plan(j, planners.get(s.def.generator()));
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
            host.logger().warning("Fresh Courses: " + s.def.id() + "'s course was made by an older version of its"
                    + " planner, so only its structure was checked; the new version builds from the next set.");
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
        try {
            GamesDao.CourseRow row = host.store().course(s.def.id());
            if (row == null) {
                return List.of("its row is gone");
            }
            if (s.def.golf()) {
                return LiveProof.structure(com.dierks.homecraft.games.golf.CourseCodec.fromRow(row), solid);
            }
            return LiveProof.structure(CourseCodec.decode(row.id(), row.data()).course(), solid);
        } catch (SQLException | RuntimeException e) {
            return List.of("its row can't be read (" + e.getMessage() + ")");
        }
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
        long gold = def.golf() ? 0 : Stars.threshold(ref, factors.gold(j.mix));
        long silver = def.golf() ? 0 : Stars.threshold(ref, factors.silver(j.mix));
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
            if (j.kind == Kind.PROMOTE) {
                meta.put(GenAdminKeys.reroll(def.id(), tag.edition()), Integer.toString(j.reroll));
            }
            rev = host.store().flip(row, meta);
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
        if (j.kind == Kind.PROMOTE) {
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

    /** The row for a new layout: the planned course with the slot's id and name, in the gen world. */
    static GamesDao.CourseRow row(Slots.Def def, String world, PlannedCourse pc, GenTag tag,
                                  GamesDao.CourseRow old, long now) {
        long created = old == null ? now : old.createdAt();
        int rev = old == null ? 1 : old.rev() + 1;
        String name = GenCopy.slotName(def, tag.cadence());
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
            rev = host.store().flip(row, Map.of());
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
        try {
            int removed = host.store().pruneBoards(Math.min(keepFrom, lastEditions), oldestWeek);
            removed += host.store().dropEditionBoards(oldEditionBoards(host.store().editionBoards(), keepFrom,
                    KEEP_EDITIONS));
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
                if (b.day() < keepFrom && !recent.contains(b.edition())) {
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
        for (char h : halves) {
            Box half = s.half(h);
            boolean holdsRun = j.stage == Stage.EVACUATE && (j.kind == Kind.BUILD || j.kind == Kind.PREVIEW)
                    && s.previous != null && !s.clearing && s.previous.half() == h;
            for (Evacuator.Action a : j.evac.step(people, s.world, half, s.def.id(), holdsRun, now, deadline)) {
                act(s, people, a);
            }
            waiting |= Evacuator.waiting(people, s.world, half, s.def.id(), holdsRun);
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
        SlotState s = slots.get(tag.slot());
        if (s == null) {
            return false;
        }
        if (s.live != null && s.live.sameLayout(tag)) {
            return true;
        }
        return s.previous != null && !s.clearing && s.previous.sameLayout(tag);
    }

    @Override
    public String closedLine(String courseId) {
        SlotState s = courseId == null ? null : slots.get(courseId.trim().toLowerCase(Locale.ROOT));
        if (s == null) {
            return GenCopy.closed("That course");
        }
        boolean building = s.on() && ((job != null && job.slot == s && (job.kind == Kind.HEAL || s.live == null))
                || (s.live != null && !s.verified && !s.healFailed) || (s.live == null && s.claimed));
        return building ? GenCopy.building(s.def.name()) : GenCopy.closed(s.def.name());
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
            if (s.on() && s.live != null) {
                next = Math.min(next, target(s).endsAt());
            }
        }
        return next == Long.MAX_VALUE ? edition().nextChangeAt(host.now()) : next;
    }

    @Override
    public boolean inArea(String world, int x, int y, int z) {
        if (world == null) {
            return false;
        }
        for (Object[] a : areas) {
            if (((String) a[0]).equalsIgnoreCase(world) && ((Box) a[1]).contains(x, y, z)) {
                return true;
            }
        }
        return false;
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
            if (s.on()) {
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
    public List<Integer> starGoals() {
        return DailyStars.stars(goals(thisWeek()));
    }

    /** What reaching {@code goal} stars pays this week (0 when it isn't one of this week's goals). */
    public int starGoalReward(int goal) {
        return DailyStars.tokens(goals(thisWeek()), goal);
    }

    /** The most star-goal tokens a player earns a day ({@code games.fresh.daily_cap}). */
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
        return out;
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
            kept |= s.on() && s.live != null && target(s).kept();
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
            if (s.on() && s.live != null && target(s).kept()) {
                return s.live.cadence();
            }
        }
        return edition().cadenceDays();
    }

    /** What is wrong with a slot for the summary, or {@code null}. */
    private String trouble(SlotState s, long today) {
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
            if (slotId != null) {
                GenScheduler.Target target = target(s);
                out.add("  &7edition " + target.key() + " (" + editionName(target.cadence(), target.start())
                        + ") until " + GenCopy.whenDated(target.endsAt(), host.zone())
                        + (target.kept() ? " - kept from the old setting" : ""));
                out.add("  &7region " + s.world + " " + Regions.describe(s.def, s.origin) + (s.claimed ? " (claimed)"
                        : " (not claimed yet)"));
                if (s.pin != null) {
                    out.add("  &7pinned seed " + GenSeed.hex(s.pin.seed()) + (s.pin.until() > 0 ? " until "
                            + date(s.pin.until()) : ""));
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
        if (s.pin != null) {
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
        if (s.pin != null) {
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
