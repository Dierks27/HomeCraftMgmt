package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenFailed;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanCodec;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.PlanShift;
import com.dierks.homecraft.games.gen.api.PlannedGolf;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.golf.LiveBlocks;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.storage.GenArchiveDao;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Keeping a favourite course for good (GEN-SPEC-KEEP §4, §5): building an archived edition into a
 * plot of the keep area, registering it as a NORMAL course with its records, and clearing a plot
 * again.
 *
 * <p><b>The same guarantees as a build.</b> A plot is built in only right after it was found empty
 * (or cleared with {@code claim plot <n> confirm}), like a half the first time. Unlike a half, a
 * free plot is NOT guarded ({@link GenRegionGuard} leaves the keep area alone: it is hand-built
 * territory, and admins may build there; only the plot of a Dropper or a golf course is guarded
 * against its water flowing out, see {@link #wetPending}), so a plot found empty once proves
 * nothing later: every keep scans its plot first. Only a keep the server stopped halfway skips the
 * scan: its {@code gen.keep.pending} record proves the blocks there are its own. No plot is scanned, cleared
 * or built while keeping is off, while it overlaps another plot's kept course (the area moved), or
 * near a registered course ({@link #plotProblem}). The plan is the archived one moved into the plot
 * ({@link PlanShift}), never planned again (unless an admin asked for a course made again from its
 * seed). It is built with {@link BuildJob}, whose writer throws outside the plot, budgeted like
 * every build, verified, and for golf its witness lines replayed on the real blocks. Only then is
 * the course row written — in one transaction with its copied records, the archive's "kept as" and
 * the plot's record — so nothing half-built is ever registered or open. An edition is kept once:
 * its archive row names one kept course.
 *
 * <p><b>Crash safety.</b> Before the first block, the job is written to {@code gen.keep.pending}. A
 * stop at any point leaves a plot that isn't registered and isn't open; the next start finishes the
 * keep (converging is idempotent) or, when it can't any more (the id was taken meanwhile, the
 * archive row is gone), clears the plot to air. A {@code clear-plot} that stopped halfway is
 * finished the same way. After the keep, the generator never writes into that plot again: only an
 * admin's {@code clear-plot} does.
 *
 * <p>One writer at a time: {@link GenService} runs this between its own jobs and never beside one,
 * never before a live course's boot check, and starts none within
 * {@code avoid_before_restart_minutes} of a restart. A re-made course's plan runs under the same
 * guards as the engine's own: the online throttle, and a kill after {@link GenService#PLAN_KILL_MS}.
 */
final class KeepService {

    /** What a plot job does. */
    enum Kind {
        /** Build a kept course into a plot, then register it. */
        KEEP,
        /** Empty a plot (clear-plot, a failed keep's leftovers, a claim with confirm). */
        CLEAR,
        /** Count what isn't air in a plot, and claim it when there is nothing. */
        SCAN
    }

    private enum Stage {
        START, PLANNING, SCAN, CONVERGE
    }

    /** One plot job. */
    final class PlotJob {
        final Kind kind;
        final int plot;
        final String world;
        final Box box;
        final Consumer<String> report;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final BuildBudget budget = new BuildBudget(host::nanoTime);
        Stage stage = Stage.START;
        BuildJob build;
        boolean resumed;
        long writes;
        long planStarted;
        // KEEP
        GenArchiveDao.Row row;
        Slots.Def def;
        String id;
        String name;
        boolean fresh;
        boolean remade;
        /** A re-made keep: the planner and size it is made with ({@link Planner#remake}), set by {@link #prepare}. */
        Planner.Remake again;
        Plan plan;
        // CLEAR
        String courseId;
        String courseGame;
        /** The plot may hold water (a Dropper's, golf's): the clear drains it all before any wall goes. */
        boolean wet;

        PlotJob(Kind kind, int plot, String world, Box box, Consumer<String> report) {
            this.kind = kind;
            this.plot = plot;
            this.world = world;
            this.box = box;
            this.report = report == null ? line -> { } : report;
        }

        /** As kept in {@code gen.keep.pending}. */
        String pending() {
            String where = plot + "|" + world + "|" + KeptPlot.boxText(box);
            if (kind != Kind.KEEP) {
                return "clear|" + where + (wet ? "|wet" : "");
            }
            return "keep|" + where + "|" + row.slot() + "|" + row.edition() + "|" + id + "|" + (fresh ? 1 : 0) + "|"
                    + (remade ? "1" : "0") + "|" + (name == null ? "" : name);
        }
    }

    private final GenService gen;
    private final GenHost host;
    private final Map<String, Planner> planners;
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<PlotJob> queue = new ArrayDeque<>();
    private PlotJob job;

    KeepService(GenService gen, GenHost host, Map<String, Planner> planners) {
        this.gen = gen;
        this.host = host;
        this.planners = planners;
    }

    // ---- lifecycle -----------------------------------------------------------------------------------

    /** The worlds are up: a keep or clear-plot a stop cut short is finished (or its plot cleaned). */
    void worldsReady() {
        String text;
        try {
            text = host.store().meta(GenAdminKeys.KEEP_PENDING);
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not read an unfinished keep", e);
            return;
        }
        if (text == null) {
            return;
        }
        String[] p = text.split("\\|", -1);
        Box box = p.length >= 4 ? KeptPlot.box(p[3]) : null;
        int n;
        try {
            n = p.length >= 2 ? Integer.parseInt(p[1].trim()) : -1;
        } catch (NumberFormatException e) {
            n = -1;
        }
        if (box == null || n < 1) {
            host.logger().warning("Fresh Courses: an unfinished keep can't be read (" + text + ") - forgotten");
            forgetPending();
            return;
        }
        String world = p[2];
        if (p[0].equals("keep") && p.length >= 10) {
            PlotJob j = new PlotJob(Kind.KEEP, n, world, box, null);
            j.resumed = true;
            try {
                GenArchiveDao.Row row = host.store().edition(p[4], p[5]);
                String why = row == null ? "its archived course is gone" : null;
                if (why == null) {
                    j.row = row;
                    j.def = Slots.of(row.slot());
                    j.id = p[6];
                    j.fresh = p[7].equals("1");
                    j.remade = p[8].equals("1");
                    String typed = String.join("|", java.util.Arrays.copyOfRange(p, 9, p.length));
                    j.name = typed.isEmpty() ? null : typed;
                    why = prepare(j);
                }
                if (why == null && host.store().course(j.id) != null) {
                    why = "there's a course called " + j.id + " now";
                }
                if (why == null) {
                    host.logger().info("Fresh Courses: finishing the keep of " + row.code() + " as " + j.id + " in plot "
                            + n + " (the server stopped halfway).");
                    queue.addFirst(j);
                    return;
                }
                host.logger().warning("Fresh Courses: the keep in plot " + n + " that the server stopped halfway can't"
                        + " be finished (" + why + ") - its plot is cleared.");
            } catch (SQLException | RuntimeException e) {
                host.logger().log(Level.WARNING, "Fresh Courses: the unfinished keep in plot " + n + " can't be"
                        + " finished - its plot is cleared", e);
            }
        } else {
            host.logger().info("Fresh Courses: finishing the clearing of plot " + n + " (the server stopped halfway).");
        }
        PlotJob c = new PlotJob(Kind.CLEAR, n, world, box, null);
        c.resumed = true;
        c.wet = p[0].equals("keep") ? mayHoldWater(p.length >= 5 ? p[4] : null) : p.length >= 5 && p[4].equals("wet");
        queue.addFirst(c);
    }

    /** Stop: the running job is given up (its pending record stays: the next start finishes it). */
    void stop() {
        PlotJob j = job;
        if (j != null) {
            end(j);
            j.cancelled.set(true);
            job = null;
        }
        queue.clear();
        inbox.clear();
    }

    /**
     * A restart is close: the running job is given up, and the jobs that haven't started are
     * dropped ({@link #holdForRestart}). A job that may have changed blocks is recorded in
     * {@code gen.keep.pending}, so it goes on after the restart; one that changed nothing (a scan, a
     * keep still checking its plot) is not, and its admin is told to ask again.
     */
    void cancel(String why) {
        PlotJob j = job;
        if (j != null) {
            end(j);
            j.cancelled.set(true);
            job = null;
            boolean resumes = j.kind != Kind.SCAN && j.stage != Stage.START
                    && (j.kind != Kind.KEEP || pendingWritten(j));
            host.logger().info("Fresh Courses: " + j.kind.name().toLowerCase(Locale.ROOT) + " of plot " + j.plot
                    + " stopped - " + why + (resumes ? " (it goes on after the restart)" : " (nothing was changed)"));
            if (resumes) {
                queue.addFirst(copyForResume(j));
                j.report.accept("&7Plot " + j.plot + ": stopped - " + why + ". It goes on after the restart.");
            } else {
                j.report.accept("&7Plot " + j.plot + ": stopped - " + why + ". Nothing was changed; run it again"
                        + " after the restart.");
            }
        }
        holdForRestart(why);
    }

    /**
     * A restart is close: every waiting job that nothing records is dropped, and its admin told (the
     * queue doesn't outlive a restart, and none may start this close to one). A job finishing what
     * a stop cut short stays: its {@code gen.keep.pending} record brings it back anyway.
     */
    void holdForRestart(String why) {
        queue.removeIf(q -> {
            if (q.resumed) {
                return false;
            }
            host.logger().info("Fresh Courses: " + q.kind.name().toLowerCase(Locale.ROOT) + " of plot " + q.plot
                    + " not started - " + why);
            q.report.accept("&7Plot " + q.plot + ": not started - " + why + ". Nothing was changed; run it again"
                    + " after the restart.");
            return true;
        });
    }

    private boolean pendingWritten(PlotJob j) {
        return j.stage == Stage.CONVERGE || j.stage == Stage.PLANNING;
    }

    private PlotJob copyForResume(PlotJob j) {
        PlotJob c = new PlotJob(j.kind, j.plot, j.world, j.box, j.report);
        c.resumed = true;
        c.row = j.row;
        c.def = j.def;
        c.id = j.id;
        c.name = j.name;
        c.fresh = j.fresh;
        c.remade = j.remade;
        c.again = j.again;
        c.plan = j.plan;
        c.courseId = j.courseId;
        c.courseGame = j.courseGame;
        c.wet = j.wet;
        return c;
    }

    /** Whether a plot job is running. */
    boolean busy() {
        return job != null;
    }

    /** Whether a plot job is waiting to start. */
    boolean hasWork() {
        return job == null && !queue.isEmpty();
    }

    /** Start the next plot job. */
    void begin() {
        PlotJob j = queue.poll();
        if (j == null) {
            return;
        }
        job = j;
        try {
            WorldPort port = host.world(j.world);
            if (port == null) {
                fail(j, "the world " + j.world + " isn't loaded");
                return;
            }
            if (!j.resumed && j.courseId == null) {
                // Checked again as it starts: config may have changed while it waited.
                String why = plotProblem(j.plot, j.world, j.box);
                if (why != null) {
                    fail(j, "it can't be used: " + why);
                    return;
                }
            }
            switch (j.kind) {
                case SCAN -> {
                    j.stage = Stage.SCAN;
                    j.build = new BuildJob(port, j.box, null, BuildJob.Mode.SCAN);
                }
                case CLEAR -> beginClear(j, port);
                case KEEP -> {
                    if (!j.resumed) {
                        // Every keep checks its plot is empty first: nothing guards a free plot.
                        j.stage = Stage.SCAN;
                        j.build = new BuildJob(port, j.box, null, BuildJob.Mode.SCAN);
                    } else {
                        startBuild(j, port);
                    }
                }
            }
        } catch (RuntimeException e) {
            host.logger().log(Level.SEVERE, "Fresh Courses: plot " + j.plot + " couldn't start", e);
            fail(j, "it couldn't start (" + e + ")");
        }
    }

    private void beginClear(PlotJob j, WorldPort port) {
        if (!j.resumed) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(GenAdminKeys.KEEP_PENDING, j.pending());
            if (j.courseId != null) {
                meta.put(GenAdminKeys.plot(j.plot), null);
                for (Person p : host.people()) {
                    if (p.playing(j.courseId)) {
                        host.endRun(p.id());
                        host.tell(p.id(), "&7That course is being taken down. &7Your things are back.");
                    }
                }
            }
            try {
                if (j.courseId != null) {
                    host.store().dropCourse(j.courseId, meta);
                    host.coursesChanged(j.courseGame == null ? Slots.GAME_TRIALS : j.courseGame);
                } else {
                    for (Map.Entry<String, String> e : meta.entrySet()) {
                        host.store().meta(e.getKey(), e.getValue());
                    }
                }
            } catch (SQLException e) {
                host.logger().log(Level.WARNING, "Fresh Courses: could not take down plot " + j.plot + "'s course", e);
                fail(j, "the database refused (" + e.getMessage() + ")");
                return;
            }
        }
        j.stage = Stage.CONVERGE;
        j.build = new BuildJob(port, j.box, null, BuildJob.Mode.CONVERGE, j.wet);
    }

    private void startBuild(PlotJob j, WorldPort port) {
        try {
            host.store().meta(GenAdminKeys.KEEP_PENDING, j.pending());
        } catch (SQLException e) {
            fail(j, "the database refused (" + e.getMessage() + ")");
            return;
        }
        if (j.plan == null) {
            planRemade(j);
            return;
        }
        if (j.def != null && j.def.mayHoldWater()) {
            proveMoved(j);
            return;
        }
        j.stage = Stage.CONVERGE;
        j.build = new BuildJob(port, j.box, j.plan, BuildJob.Mode.CONVERGE);
    }

    /**
     * The archived plan of a course that may hold water (a Dropper's pools, golf's ponds), moved into
     * its plot, is proven again where it will stand ({@link PlanCheck#movedProblems}: a Dropper's whole
     * validator, sealed and solvable; golf's quick check, its ponds sealed and every witness line
     * replayed there) before a block is set, on the planner thread like any plan, under the same kill
     * rule; refused, the keep fails and nothing is built.
     */
    private void proveMoved(PlotJob j) {
        Plan moved = j.plan;
        Slots.Def def = j.def;
        j.stage = Stage.PLANNING;
        j.planStarted = host.now();
        host.planner().execute(() -> {
            List<String> refused = List.of();
            Throwable error = null;
            try {
                refused = PlanCheck.movedProblems(moved, def);
            } catch (Throwable e) {
                error = e;
            }
            List<String> checked = refused;
            Throwable failure = error;
            inbox.add(() -> movedProven(j, checked, failure));
        });
    }

    private void movedProven(PlotJob j, List<String> refused, Throwable error) {
        if (job != j || j.stage != Stage.PLANNING) {
            return; // cancelled or killed meanwhile
        }
        if (error != null || !refused.isEmpty()) {
            fail(j, error != null ? "its plan couldn't be checked (" + error + ")"
                    : "its plan was refused: " + String.join("; ", refused));
            return;
        }
        WorldPort port = host.world(j.world);
        if (port == null) {
            fail(j, "the world " + j.world + " isn't loaded");
            return;
        }
        j.stage = Stage.CONVERGE;
        j.build = new BuildJob(port, j.box, j.plan, BuildJob.Mode.CONVERGE);
    }

    /**
     * Make a course again from its seed, straight into its place in the plot: by the version that made it when
     * its planner keeps it (an algo-3 golf edition: the same holes), else by today's ({@link #prepare}).
     */
    private void planRemade(PlotJob j) {
        Planner p = j.again == null ? null : j.again.planner();
        if (p == null) {
            fail(j, "there is no " + j.def.generator() + " generator");
            return;
        }
        Edition.Key key = Edition.Key.parse(j.row.edition());
        Box build = buildBox(j);
        PlanInput in = new PlanInput(j.def, build, 'A', j.row.day(), key == null ? 0 : key.reroll(), j.row.seed(),
                j.row.tierOrMix(), host.fallDepth(), GenService.WORK.getOrDefault(j.def.generator(), 200_000L),
                gen.cancelled(j.cancelled));
        j.stage = Stage.PLANNING;
        j.planStarted = host.now();
        host.planner().execute(() -> {
            Plan made = null;
            Throwable error = null;
            try {
                made = p.plan(in);
            } catch (Throwable e) {
                error = e;
            }
            Plan result = made;
            Throwable failure = error;
            inbox.add(() -> remadePlanned(j, result, failure));
        });
    }

    private void remadePlanned(PlotJob j, Plan plan, Throwable error) {
        if (job != j || j.stage != Stage.PLANNING) {
            return;
        }
        if (error != null) {
            fail(j, error instanceof GenFailed ? String.valueOf(error.getMessage()) : "the planner threw " + error);
            return;
        }
        List<String> problems = PlanCheck.problems(plan, j.def, buildBox(j));
        if (!problems.isEmpty()) {
            fail(j, "the course made again was refused: " + String.join("; ", problems));
            return;
        }
        j.plan = plan;
        WorldPort port = host.world(j.world);
        if (port == null) {
            fail(j, "the world " + j.world + " isn't loaded");
            return;
        }
        j.stage = Stage.CONVERGE;
        j.build = new BuildJob(port, j.box, j.plan, BuildJob.Mode.CONVERGE);
    }

    /**
     * Where the course stands in its plot, {@value KeepArea#MARGIN} in from the plot's corner: the
     * archived plan's own half once it is moved there (its size as it was made, whatever the slot's
     * size is now), or, for a course made again from its seed, the size it is made in ({@link Planner#remake}:
     * an Adventure Golf edition's own, else the slot's today).
     */
    static Box buildBox(PlotJob j) {
        if (j.plan != null) {
            return j.plan.half();
        }
        return j.again != null ? at(j.box, j.again.sizeX(), j.again.sizeY(), j.again.sizeZ())
                : at(j.box, j.def.sizeX(), j.def.sizeY(), j.def.sizeZ());
    }

    /** A box of this size {@value KeepArea#MARGIN} in from {@code plot}'s corner. */
    private static Box at(Box plot, int sx, int sy, int sz) {
        return Box.sized(plot.minX() + KeepArea.MARGIN, plot.minY(), plot.minZ() + KeepArea.MARGIN, sx, sy, sz);
    }

    /** Every tick: plans that came back, then a tick of building. */
    void tick() {
        Runnable r;
        while ((r = inbox.poll()) != null) {
            r.run();
        }
        PlotJob j = job;
        if (j != null && j.stage == Stage.PLANNING && host.now() - j.planStarted > GenService.PLAN_KILL_MS) {
            // The engine's own rule for a plan that hangs: give it up, never wait on it.
            j.cancelled.set(true);
            if (host.planner() instanceof PlannerThread t) {
                t.restart();
            }
            fail(j, "planning took longer than " + GenService.PLAN_KILL_MS / 1000 + " seconds");
            return;
        }
        if (j == null || j.build == null || (j.stage != Stage.SCAN && j.stage != Stage.CONVERGE)) {
            return;
        }
        try {
            tickJob(j);
        } catch (RuntimeException e) {
            host.logger().log(Level.SEVERE, "Fresh Courses: plot " + j.plot + " failed", e);
            if (job == j) {
                fail(j, "it threw " + e);
            }
        }
    }

    private void tickJob(PlotJob j) {
        DailySettings.Budget cfg = host.settings().budget();
        if (!j.budget.begin(cfg, host.anyoneOnline(), host.mspt())) {
            return;
        }
        List<Person> here = new ArrayList<>();
        for (Person p : host.people()) {
            if (p.world() != null && p.world().equalsIgnoreCase(j.world)) {
                here.add(p);
            }
        }
        try {
            j.build.tick(j.budget, cfg.chunkLoadsInFlight(), here, host.now());
        } finally {
            j.budget.end();
        }
        for (UUID stuck : j.build.stuckPeople()) {
            for (Person p : here) {
                if (p.id().equals(stuck)) {
                    gen.moveOut(j.world, p);
                }
            }
        }
        if (j.build.failed()) {
            fail(j, j.build.error());
            return;
        }
        if (!j.build.done()) {
            return;
        }
        j.writes += j.build.writes();
        if (j.stage == Stage.SCAN) {
            scanned(j);
            return;
        }
        switch (j.kind) {
            case KEEP -> built(j);
            case CLEAR -> cleared(j);
            default -> end(j);
        }
    }

    /** Every check while a plot is converged: anyone in it (or within 8) is moved out at once. */
    void check() {
        PlotJob j = job;
        if (j == null || j.stage != Stage.CONVERGE) {
            return;
        }
        Box area = j.box.expand(Evacuator.MARGIN);
        for (Person p : host.people()) {
            if (p.in(j.world, area)) {
                gen.moveOut(j.world, p);
            }
        }
    }

    private void scanned(PlotJob j) {
        long found = j.build.found();
        List<String> first = j.build.firstFound();
        j.build.release();
        j.build = null;
        if (found > 0) {
            end(j);
            String why = "Plot " + j.plot + " has " + String.format(Locale.ROOT, "%,d", found) + (found == 1
                    ? " block that isn't" : " blocks that aren't") + " Fresh Courses' (first at "
                    + (first.isEmpty() ? "?" : first.get(0)) + ") - /hcm games gen claim plot " + j.plot
                    + " confirm clears them";
            host.logger().warning("Fresh Courses: " + why);
            j.report.accept("&c" + why);
            return;
        }
        if (j.kind == Kind.SCAN) {
            end(j);
            j.report.accept("&aPlot " + j.plot + " is empty and ready for a kept course.");
            return;
        }
        WorldPort port = host.world(j.world);
        if (port == null) {
            fail(j, "the world " + j.world + " isn't loaded");
            return;
        }
        startBuild(j, port);
    }

    /**
     * The blocks are the plan: prove golf on the real blocks, read as the kept course will play them
     * (an Adventure Golf layout's sand as sand, as its par line was planned; Course Variety review),
     * then register the course (one transaction).
     */
    private void built(PlotJob j) {
        WorldPort port = host.world(j.world);
        if (j.plan.course() instanceof PlannedGolf g) {
            List<String> problems = port == null ? List.of("the world isn't loaded")
                    : LiveProof.replay(port.ballBlocks(LiveBlocks.sandPlays(j.plan.algo())), g.course().holes(),
                    g.witness());
            if (!problems.isEmpty()) {
                fail(j, "the live replay failed: " + problems.get(0));
                return;
            }
        }
        long now = host.now();
        String name = j.name == null ? KeptCourses.name(null, j.id) : j.name;
        GamesDao.CourseRow row = KeptCourses.row(j.id, name, j.world, j.plan, now);
        List<String> problems = KeptCourses.problems(row, host.gamesWorlds());
        if (!problems.isEmpty()) {
            fail(j, "the kept course wouldn't open: " + String.join("; ", problems));
            return;
        }
        String game = row.game();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(GenAdminKeys.plot(j.plot), new KeptPlot(j.plot, j.id, j.world, j.box, j.row.slot(), j.row.edition())
                .text());
        meta.put(GenAdminKeys.KEEP_PENDING, null);
        int copied;
        try {
            copied = host.store().keep(row, j.row.board(), j.fresh ? null : KeptCourses.board(game, j.id), j.row.slot(),
                    j.row.edition(), meta);
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not register the kept course " + j.id, e);
            fail(j, "the database refused the course (" + e.getMessage() + ")");
            return;
        }
        end(j);
        host.coursesChanged(game);
        host.logger().info("Fresh Courses: kept " + j.row.code() + " (" + j.row.slot() + " " + j.row.edition() + ")"
                + (j.remade ? " (re-made)" : "") + " as " + j.id + " in plot " + j.plot + " - " + j.writes
                + " blocks, " + copied + " records copied.");
        j.report.accept("&aKept! &f" + name + " &7(" + j.id + ") is a normal course now, in plot " + j.plot
                + (j.remade ? " &7(re-made)" : "") + ". " + (j.fresh ? "Its board starts empty." : copied + " record"
                + (copied == 1 ? " was" : "s were") + " copied onto its board.") + " &e/hcm play " + j.id);
    }

    private void cleared(PlotJob j) {
        end(j);
        forgetPending();
        host.logger().info("Fresh Courses: plot " + j.plot + " is empty (" + j.writes + " blocks cleared).");
        j.report.accept("&aPlot " + j.plot + " is cleared" + (j.courseId == null ? "" : " and " + j.courseId
                + " is gone") + ".");
    }

    private void end(PlotJob j) {
        if (j.build != null) {
            j.build.release();
        }
        if (job == j) {
            job = null;
        }
    }

    /** A job failed; a keep that had started building leaves its plot cleaned up (never registered). */
    private void fail(PlotJob j, String why) {
        end(j);
        j.cancelled.set(true);
        host.logger().warning("Fresh Courses: " + j.kind.name().toLowerCase(Locale.ROOT) + " of plot " + j.plot
                + " failed - " + why);
        j.report.accept("&cPlot " + j.plot + ": &7" + why);
        if (j.kind == Kind.KEEP && (j.stage == Stage.CONVERGE || j.stage == Stage.PLANNING)) {
            PlotJob c = new PlotJob(Kind.CLEAR, j.plot, j.world, j.box, j.report);
            c.wet = j.def != null && j.def.mayHoldWater();
            try {
                host.store().meta(GenAdminKeys.KEEP_PENDING, c.pending());
                c.resumed = true;
            } catch (SQLException e) {
                host.logger().log(Level.WARNING, "Fresh Courses: could not record plot " + j.plot + "'s cleanup", e);
            }
            queue.addFirst(c);
        }
    }

    private void forgetPending() {
        try {
            host.store().meta(GenAdminKeys.KEEP_PENDING, null);
        } catch (SQLException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not forget the finished keep job", e);
        }
    }

    // ---- what admins ask (through GenService) --------------------------------------------------------

    /**
     * Keep {@code row} as {@code id}: every check first (the area, the id, a free plot, the plan and
     * the course it makes), then — with {@code confirm} — the job.
     */
    void keep(GenArchiveDao.Row row, boolean remade, String id, String name, boolean fresh, boolean confirm,
              Consumer<String> report) {
        DailySettings.Archive a = host.settings().archive();
        String off = keepOff(a);
        if (off != null) {
            report.accept("&cKeeping is off: &7" + off + ". Move games.fresh.keep.area and /hcm reload.");
            return;
        }
        String world = gen.genWorld();
        if (world.isBlank() || host.world(world) == null) {
            report.accept("&cThe world " + world + " isn't loaded.");
            return;
        }
        Slots.Def def = Slots.of(row.slot());
        if (def == null) {
            report.accept("&c" + row.code() + " was made for a course that no longer exists.");
            return;
        }
        String already = keptAs(row);
        if (already != null) {
            // One kept course a set: its archive row names one ("kept as", the website's "kept"),
            // and clearing that course's plot must never leave another copy unnamed.
            report.accept("&c" + row.code() + " is already kept as " + already + ". &7Play it with &e/hcm play "
                    + already + "&7; to keep it again under another id, clear its plot first (&e/hcm games gen"
                    + " plots&7).");
            return;
        }
        String key = id.toLowerCase(Locale.ROOT);
        boolean exists;
        try {
            exists = host.store().course(key) != null;
        } catch (SQLException e) {
            report.accept("&cCouldn't reach the database - see the console.");
            return;
        }
        String problem = KeptCourses.idProblem(key, def.golf(), host::playIdTaken, exists || taken(key));
        if (problem != null) {
            report.accept("&c" + problem);
            return;
        }
        List<String> skipped = new ArrayList<>();
        int n = freePlot(a.keep(), world, skipped);
        if (n < 1) {
            if (!skipped.isEmpty()) {
                report.accept("&cNo free plot can be used: &7" + skipped.get(0) + (skipped.size() > 1 ? " (and "
                        + (skipped.size() - 1) + " more)" : "") + ".");
                return;
            }
            report.accept("&cThe keep area is full (" + a.keep().maxPlots() + " plots). &7Clear one with &e/hcm games"
                    + " gen clear-plot <n> confirm&7, or raise games.fresh.keep.max_plots.");
            return;
        }
        PlotJob j = new PlotJob(Kind.KEEP, n, world, a.keep().plot(n), report);
        j.row = row;
        j.def = def;
        j.id = key;
        j.name = name == null ? null : KeptCourses.name(name, key);
        j.fresh = fresh;
        j.remade = remade;
        String why = prepare(j);
        if (why != null) {
            report.accept("&c" + row.code() + " can't be kept: &7" + why);
            return;
        }
        int records = 0;
        try {
            records = host.store().boardStats(def.game(), row.board()).players();
        } catch (SQLException e) {
            // said as 0
        }
        String shown = j.name == null ? KeptCourses.name(null, key) : j.name;
        String what = row.code() + " (" + row.name() + ", " + GenService.editionName(Edition.Key.parse(row.edition()),
                row.day()) + ")" + (remade ? " made again from its seed (re-made)" : "") + " as &f" + shown + " &7("
                + key + ") in plot " + n + " (" + j.box.describe() + ")";
        // FX-GOLF (GOLF03/04): made by another version than the one that made it, it is another course
        String other = j.again != null && !j.again.exact() ? "&eIt is " + GenCopy.remadeOther(def,
                j.again.planner().algo()) + " as " + row.code() + ", so its board starts empty." : null;
        if (!confirm) {
            report.accept("&eThis keeps " + what + "&e for good: it becomes a normal course with a lasting board"
                    + (j.fresh ? ", starting empty." : records > 0 ? ", and its " + records + " record"
                    + (records == 1 ? " is" : "s are") + " copied onto it." : "."));
            if (other != null) {
                report.accept(other);
            }
            report.accept("&7Add &econfirm &7at the end to do it.");
            return;
        }
        queue.add(j);
        report.accept("&7Keeping " + what + "&7...");
        if (other != null) {
            report.accept(other);
        }
    }

    /**
     * Get a keep job's plan ready (the archived one moved into its plot, unless it is made again from
     * its seed) and check the course it makes as the course games would; {@code null} when fine.
     */
    private String prepare(PlotJob j) {
        if (j.def == null) {
            return "it was made for a course that no longer exists";
        }
        j.plan = null;
        if (j.remade) {
            // By the version that made it, in its own size, when its planner keeps it (an algo-3 golf edition:
            // GolfPlanner.v3(), the same holes, so its records still belong to it); by today's it is another
            // course from the same seed, which starts on an empty board (FX-GOLF, GOLF03/04)
            Planner p = planners.get(j.def.generator());
            j.again = p == null ? null : p.remake(j.def, j.row.algoVersion());
            if (j.again != null && !j.again.exact()) {
                j.fresh = true;
            }
            Box made = buildBox(j);
            if (!KeepArea.fits(made) || !j.box.contains(made)) {
                return tooBig(j.def, made);
            }
            if (p == null) {
                return "there is no " + j.def.generator() + " generator";
            }
            return null;
        }
        PlanCodec.Head head = PlanCodec.head(j.row.plan());
        if (head != null && !KeepArea.fits(head.half())) {
            return tooBig(j.def, head.half()); // told from its first bytes: a Mountain Run's row is never read here (F11)
        }
        PlanCodec.Read read = PlanCodec.decode(j.row.plan());
        if (!read.ok()) {
            return "its stored plan can't be read (" + read.problem() + "). Make it again from its seed with today's"
                    + " generator: /hcm games gen keep " + j.row.slot() + " seed:" + GenSeed.hex(j.row.seed()) + " "
                    + j.id;
        }
        // Sized by the plan's own half, not the slot's today: a generator that grew since can't
        // make an old course unbuildable.
        Box half = read.plan().half();
        Box build = at(j.box, half.sizeX(), half.sizeY(), half.sizeZ());
        if (!KeepArea.fits(half) || !j.box.contains(build)) {
            return tooBig(j.def, half);
        }
        Plan moved = PlanShift.to(read.plan(), build);
        // (a moved Dropper or golf course is proven again in its plot too, on the planner thread as the
        // job starts building: proveMoved)
        List<String> problems = PlanCheck.problems(moved, j.def, build);
        if (!problems.isEmpty()) {
            return "its plan was refused: " + String.join("; ", problems);
        }
        GamesDao.CourseRow row = KeptCourses.row(j.id, j.name == null ? KeptCourses.name(null, j.id) : j.name, j.world,
                moved, host.now());
        List<String> course = KeptCourses.problems(row, host.gamesWorlds());
        if (!course.isEmpty()) {
            return "the kept course wouldn't open: " + String.join("; ", course);
        }
        j.plan = moved;
        return null;
    }

    /**
     * Why a course whose half is {@code half} can't be kept: it doesn't fit a plot (pinned at 144 x 176 x
     * 336, {@link KeepArea#fits}). A Mountain Run v2 (an ice boat course bigger than a plot) says so in the
     * owner's words: it stays in the archive and on its boards (MOUNTAIN-V2-SPEC D6).
     */
    static String tooBig(Slots.Def def, Box half) {
        if (def != null && Slots.BOAT.equals(def.generator())) {
            return "Mountain Run v2 courses are too big to keep; they stay in the archive";
        }
        return "it doesn't fit a plot (its area is " + (half == null ? "?" : half.sizeX() + " x " + half.sizeY() + " x "
                + half.sizeZ()) + "; a plot holds " + (KeepArea.PLOT_X - 2 * KeepArea.MARGIN) + " x " + KeepArea.PLOT_Y
                + " x " + (KeepArea.PLOT_Z - 2 * KeepArea.MARGIN) + ")";
    }

    /**
     * The course {@code row}'s edition is kept as (its archive row, a plot's record, or a keep in
     * flight), or {@code null} when it isn't.
     */
    private String keptAs(GenArchiveDao.Row row) {
        if (row.keptAs() != null) {
            return row.keptAs();
        }
        for (KeptPlot p : kept().values()) {
            if (row.slot().equals(p.slot()) && row.edition().equals(p.edition())) {
                return p.courseId();
            }
        }
        List<PlotJob> jobs = new ArrayList<>(queue);
        if (job != null) {
            jobs.add(job);
        }
        for (PlotJob q : jobs) {
            if (q.kind == Kind.KEEP && q.row != null && row.slot().equals(q.row.slot())
                    && row.edition().equals(q.row.edition())) {
                return q.id;
            }
        }
        return null;
    }

    /** Whether a keep waiting or running already takes {@code id}. */
    private boolean taken(String id) {
        if (job != null && id.equals(job.id)) {
            return true;
        }
        for (PlotJob q : queue) {
            if (id.equals(q.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The lowest plot with no course, no keep in flight, nothing waiting for it and nothing in its
     * way ({@link #plotProblem}); -1 when there is none. Each plot passed over for being in the way
     * is added to {@code skipped}, said as admins read it.
     */
    private int freePlot(KeepArea area, String world, List<String> skipped) {
        Map<Integer, KeptPlot> used = kept();
        String pending = null;
        try {
            pending = host.store().meta(GenAdminKeys.KEEP_PENDING);
        } catch (SQLException e) {
            // judged by the jobs below
        }
        List<Regions.Area> courses = courseAreas();
        for (int n = 1; n <= area.maxPlots(); n++) {
            if (used.containsKey(n) || busyPlot(n) || (pending != null && pending.split("\\|", -1).length > 1
                    && pending.split("\\|", -1)[1].equals(Integer.toString(n)))) {
                continue;
            }
            String why = plotProblem(n, world, area.plot(n), used, courses);
            if (why != null) {
                skipped.add("plot " + n + ": " + why);
                continue;
            }
            return n;
        }
        return -1;
    }

    /**
     * Why plot {@code n} (standing in {@code box}) must not be scanned, cleared or built in, or
     * {@code null}. The keep area is hand-built territory that nothing guards, so this is all that
     * stands between a clear and someone's course: keeping is off (the area overlaps a generator
     * or Classics half, or comes within {@value Regions#CLEARANCE} blocks of one: config's check);
     * the plot doesn't fit the world (past its border or height, or within {@value Regions#CLEARANCE}
     * blocks of the spawn or the safe spot: {@link Regions#plotWorldProblems}); the box overlaps
     * another plot's kept course (the area moved since it was kept); or a registered course stands in
     * it or within {@value Regions#CLEARANCE} blocks of it (a kept course of another plot only when it
     * stands inside).
     */
    String plotProblem(int n, String world, Box box) {
        return plotProblem(n, world, box, kept(), courseAreas());
    }

    private String plotProblem(int n, String world, Box box, Map<Integer, KeptPlot> used, List<Regions.Area> courses) {
        DailySettings.Archive a = host.settings().archive();
        String off = keepOff(a);
        if (off != null) {
            return "keeping is off: " + off;
        }
        WorldPort port = host.world(world);
        if (port != null) {
            List<String> edge = Regions.plotWorldProblems(n, box, new Regions.WorldFacts(port.name(), true,
                    port.minHeight(), port.maxHeight(), port.border(), port.spawn(), host.settings().safeSpot()));
            if (!edge.isEmpty()) {
                return edge.get(0);
            }
        }
        Map<String, Integer> keptIn = new java.util.HashMap<>();
        for (KeptPlot p : used.values()) {
            keptIn.put(p.courseId(), p.n());
            if (p.n() != n && p.world().equalsIgnoreCase(world) && p.box().intersects(box)) {
                return "it overlaps plot " + p.n() + ", where " + p.courseId() + " stands (" + p.box().describe()
                        + "): the keep area moved since that course was kept";
            }
        }
        if (courses == null) {
            return "the courses can't be read to check it";
        }
        for (Regions.Area c : courses) {
            if (c.world() == null || !c.world().equalsIgnoreCase(world)) {
                continue;
            }
            Integer in = keptIn.get(c.courseId());
            int gap = c.box().gap(box);
            if (in != null ? in != n && gap < 0 : gap < Regions.CLEARANCE) {
                return "the course " + c.courseId() + " is " + (gap < 0 ? "inside it" : "only " + gap
                        + " blocks from it") + " (" + c.box().describe() + "; it must be " + Regions.CLEARANCE
                        + " away)";
            }
        }
        return null;
    }

    /** Every registered (non-generated) course's footprint, kept courses included; {@code null} when unreadable. */
    private List<Regions.Area> courseAreas() {
        try {
            List<GamesDao.CourseRow> rows = new ArrayList<>(host.store().courses(Slots.GAME_TRIALS));
            rows.addAll(host.store().courses(Slots.GAME_GOLF));
            return Regions.handBuilt(rows);
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: the courses couldn't be read to check a plot", e);
            return null;
        }
    }

    private boolean busyPlot(int n) {
        if (job != null && job.plot == n) {
            return true;
        }
        for (PlotJob q : queue) {
            if (q.plot == n) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why keeping is off now, in admin words, or {@code null}: the keep area's own problem (read at
     * config load), or an extra box it crowds (the Falling Floors arena: {@link Regions#keepExtrasProblem}),
     * so no plot is scanned, cleared or built next to the arena.
     */
    private String keepOff(DailySettings.Archive a) {
        if (a.keepProblem() != null) {
            return "the keep area " + a.keep().describe() + " " + a.keepProblem();
        }
        return Regions.keepExtrasProblem(a.keep(), gen.extras());
    }

    /** Every plot holding a kept course, by number. */
    Map<Integer, KeptPlot> kept() {
        Map<Integer, KeptPlot> out = new java.util.TreeMap<>();
        try {
            for (Map.Entry<String, String> e : host.store().metaLike(GenAdminKeys.PLOTS).entrySet()) {
                int n = GenAdminKeys.plotOf(e.getKey());
                KeptPlot p = KeptPlot.parse(n, e.getValue());
                if (p != null) {
                    out.put(n, p);
                }
            }
        } catch (SQLException | RuntimeException e) {
            host.logger().log(Level.WARNING, "Fresh Courses: could not read the plots", e);
        }
        return out;
    }

    /** The plots, as admins read them. */
    List<String> plots() {
        DailySettings.Archive a = host.settings().archive();
        Map<Integer, KeptPlot> used = kept();
        List<String> out = new ArrayList<>();
        String off = keepOff(a);
        out.add("&6Kept courses &7- " + used.size() + " of " + a.keep().maxPlots() + " plots used, in "
                + gen.genWorld() + " " + a.keep().describe() + (off == null ? "" : " &c(keeping is off: " + off + ")"));
        for (KeptPlot p : used.values()) {
            String from = p.slot();
            try {
                GenArchiveDao.Row r = host.store().edition(p.slot(), p.edition());
                if (r != null) {
                    from = r.code() + ", " + r.name();
                }
            } catch (SQLException e) {
                // the slot alone
            }
            out.add("&f" + p.n() + ": &e" + p.courseId() + " &7(" + from + ") " + p.box().describe());
        }
        if (job != null) {
            out.add("&e" + job.kind.name() + " plot " + job.plot + ": " + stageLine(job));
        }
        for (PlotJob q : queue) {
            out.add("&7waiting: " + q.kind.name().toLowerCase(Locale.ROOT) + " plot " + q.plot);
        }
        if (used.isEmpty() && job == null && queue.isEmpty()) {
            out.add("&7None yet. &e/hcm games gen keep <code> <new-id> [name] &7keeps one for good.");
        }
        return out;
    }

    private static String stageLine(PlotJob j) {
        if (j.build == null) {
            return j.stage.name().toLowerCase(Locale.ROOT);
        }
        return (j.stage == Stage.SCAN ? "checking it is empty" : "building") + " · " + j.build.waiting()
                + " blocks to go · " + j.build.writes() + " written";
    }

    /** The plot numbers holding a course. */
    List<Integer> usedPlots() {
        return new ArrayList<>(kept().keySet());
    }

    /** clear-plot: the course and its boards go, then the plot is cleared to air. */
    void clearPlot(int n, boolean confirm, Consumer<String> report) {
        KeptPlot p = kept().get(n);
        if (p == null) {
            report.accept("&cPlot " + n + " holds no kept course. &7/hcm games gen plots");
            return;
        }
        if (busyPlot(n)) {
            report.accept("&cPlot " + n + " is busy right now; try in a moment.");
            return;
        }
        if (!confirm) {
            int records = 0;
            try {
                GamesDao.CourseRow row = host.store().course(p.courseId());
                String game = row == null ? Slots.GAME_TRIALS : row.game();
                records = host.store().boardStats(game, KeptCourses.board(game, p.courseId())).players();
            } catch (SQLException e) {
                // said as 0
            }
            report.accept("&eThis deletes the course " + p.courseId() + (records > 0 ? " and its board (" + records
                    + " record" + (records == 1 ? ")" : "s)") : "") + ", moves anyone in plot " + n
                    + " out, and clears the plot to air.");
            report.accept("&7Add &econfirm &7at the end to do it.");
            return;
        }
        PlotJob j = new PlotJob(Kind.CLEAR, n, p.world(), p.box(), report);
        j.courseId = p.courseId();
        j.wet = mayHoldWater(p.slot());
        try {
            GamesDao.CourseRow row = host.store().course(p.courseId());
            j.courseGame = row == null ? Slots.GAME_TRIALS : row.game();
        } catch (SQLException e) {
            j.courseGame = Slots.GAME_TRIALS;
        }
        queue.add(j);
        report.accept("&7Taking down " + p.courseId() + " and clearing plot " + n + "...");
    }

    /**
     * claim plot: count what is in a free plot; with confirm, clear it for a kept course. Refused
     * for a plot with anything in its way ({@link #plotProblem}), so it never clears a course. A plot
     * found empty is not remembered as ours: the next keep scans it again.
     */
    void claimPlot(int n, boolean confirm, Consumer<String> report) {
        DailySettings.Archive a = host.settings().archive();
        if (n < 1 || n > a.keep().maxPlots()) {
            report.accept("&cPlots are 1-" + a.keep().maxPlots() + ".");
            return;
        }
        if (kept().containsKey(n)) {
            report.accept("&cPlot " + n + " holds a kept course. &7/hcm games gen clear-plot " + n + " confirm");
            return;
        }
        if (busyPlot(n)) {
            report.accept("&cPlot " + n + " is busy right now; try in a moment.");
            return;
        }
        String world = gen.genWorld();
        if (host.world(world) == null) {
            report.accept("&cThe world " + world + " isn't loaded.");
            return;
        }
        Box box = a.keep().plot(n);
        String why = plotProblem(n, world, box);
        if (why != null) {
            report.accept("&cPlot " + n + " can't be used: &7" + why + ". Nothing was changed.");
            return;
        }
        queue.add(new PlotJob(confirm ? Kind.CLEAR : Kind.SCAN, n, world, box, report));
        report.accept(confirm ? "&7Clearing plot " + n + " (" + box.describe() + ")..."
                : "&7Counting what is in plot " + n + "...");
    }

    /** Where {@code tp plot <n>} goes: a kept course's plot corner, or the middle of a free plot. */
    Box plotBox(int n) {
        KeptPlot p = kept().get(n);
        if (p != null) {
            return p.box();
        }
        DailySettings.Archive a = host.settings().archive();
        return n >= 1 && n <= a.keep().maxPlots() ? a.keep().plot(n) : null;
    }

    // ---- the flow guard ---------------------------------------------------------------------------

    /**
     * Whether the slot {@code id} a course was kept from {@link Slots.Def#mayHoldWater may hold water}
     * (a Dropper, golf), so its plot may too.
     */
    static boolean mayHoldWater(String id) {
        Slots.Def d = id == null ? null : Slots.of(id);
        if (d == null && id != null) {
            d = Slots.classic(id);
        }
        return d != null && d.mayHoldWater();
    }

    /**
     * The plot a {@code gen.keep.pending} record names, {world, {@link Box}}, when it may hold water:
     * a keep of a Dropper or a golf course, or any clearing (whose course isn't in the record). Else
     * {@code null}. The record is written before the first block, so a keep the server stopped
     * halfway is covered until the next start finishes it or clears its plot.
     */
    static Object[] wetPending(String text) {
        if (text == null) {
            return null;
        }
        String[] p = text.split("\\|", -1);
        Box box = p.length >= 4 ? KeptPlot.box(p[3]) : null;
        if (box == null || p[2].isBlank()) {
            return null;
        }
        boolean wet = p[0].equals("clear") || (p[0].equals("keep") && p.length >= 5 && mayHoldWater(p[4]));
        return wet ? new Object[]{p[2], box} : null;
    }

    /**
     * Whether plot job {@code j} may hold water (a keep of a Dropper or a golf course, or any
     * clearing): its plot is guarded from its first block, before any record of it is read back.
     */
    private static boolean wet(PlotJob j) {
        return j != null && j.world != null && j.box != null
                && (j.kind == Kind.CLEAR || (j.kind == Kind.KEEP && j.def != null && j.def.mayHoldWater()));
    }

    /** Whether the running plot job may hold water in {@code world}. */
    boolean wetJobIn(String world) {
        PlotJob j = job;
        return world != null && wet(j) && j.world.equalsIgnoreCase(world);
    }

    /** Whether a block is in the running plot job's plot when it may hold water. */
    boolean inWetJob(String world, int x, int y, int z) {
        PlotJob j = job;
        return world != null && wet(j) && j.world.equalsIgnoreCase(world) && j.box.contains(x, y, z);
    }

    /** For tests: the running job's kind, or {@code null}. */
    Kind jobKind() {
        return job == null ? null : job.kind;
    }
}
