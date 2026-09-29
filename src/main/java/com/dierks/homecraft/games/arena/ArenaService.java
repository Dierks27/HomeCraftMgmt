package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.ArenaRound;
import com.dierks.homecraft.games.arena.rules.ArenaText;
import com.dierks.homecraft.games.arena.rules.ArenaTick;
import com.dierks.homecraft.games.arena.rules.Feet;
import com.dierks.homecraft.games.arena.rules.FloorLayout;
import com.dierks.homecraft.games.arena.rules.FloorRules;
import com.dierks.homecraft.games.arena.rules.OutReason;
import com.dierks.homecraft.games.arena.rules.RoundEvent;
import com.dierks.homecraft.games.arena.rules.RoundResult;
import com.dierks.homecraft.games.arena.rules.RoundSettings;
import com.dierks.homecraft.games.arena.rules.Standing;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.engine.BuildBudget;
import com.dierks.homecraft.games.gen.engine.BuildJob;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Falling Floors arena, running (EVENTS-DROPPER-SPEC §B.3): the week's floors, the pure round
 * rules driven once a tick, the reset between rounds, and what every round event means for the
 * players in the arena.
 *
 * <p><b>The gate.</b> A round is only ever played on a box verified equal to this week's plan. The
 * rules start in RESET, and the reset is Fresh Courses' own converge-and-verify ({@link BuildJob}
 * over the box, with its own {@link BuildBudget}): after a crash or a restart it simply puts the
 * floors back, which is also how the boot heals whatever a round left behind. Nobody may enter
 * until a verify has passed in this run, so nobody is ever put into a gallery that isn't there.
 *
 * <p><b>The claim</b> (Fresh Courses' idea): the first time the box is used it must be empty. An
 * unclaimed box is only counted ({@link BuildJob.Mode#SCAN}); empty, it is claimed
 * ({@code gen.floors.claim}) and built; anything in it closes the game, touching nothing, until an
 * admin's {@code /hcm games floors claim confirm}. Before anything is written the box must also be
 * clear of every Fresh Courses area, the kept courses and hand-built courses
 * ({@link ArenaHost#regionProblems}).
 *
 * <p><b>Who is where.</b> Everyone in the arena is in a world session (ref {@value #REF}) and in the
 * gallery, except the round's players on the floors. Before every reset anyone else inside the box
 * is moved out of the way: arena players to the gallery, anyone else to safety. Collisions are off
 * for a round's players while they are on the floors and back on for every way off them (out, the
 * end, leaving, quitting, the game closing or stopping), whatever happened.
 *
 * <p>Everything runs on the main thread, inside the game's guard. Pure apart from the
 * {@link ArenaHost}, so the tests run it tick by tick on a fake world.
 */
public final class ArenaService {

    /** The world session's ref (and the game's id). */
    public static final String REF = "falling_floors";
    /** Where the claim is kept in {@code hcm_meta}. */
    public static final String CLAIM_KEY = "gen.floors.claim";
    /** The reset's time budget a tick, and the snapshots and chunk loads it may use. */
    static final int RESET_MS = 3;
    static final int RESET_SNAPSHOTS = 3;
    static final int RESET_LOADS = 4;
    /** The reset pauses above this average tick time (and resumes a quarter lower). */
    static final int PAUSE_MSPT = 40;
    /** How often the countdown bar moves. */
    static final int BAR_EVERY = 10;

    private final ArenaHost host;
    private final Box box;
    private final String world;
    private final RoundSettings rs;
    private final BuildBudget budget;

    private ArenaSite site;
    private ArenaRound round;
    private ArenaTick arena;
    private FloorWriter writer;
    private WorldPort writerPort;

    private BuildJob job;
    private int jobTicket;
    private boolean jobScan;
    private long jobStarted;
    private int pendingTicket = -1;
    private String waitingFor;
    private boolean verified;
    private boolean hold;
    private String planProblem;
    private long plannedWeek = Long.MIN_VALUE;
    private long lastPlanTry = Long.MIN_VALUE;

    private final Map<UUID, List<Feet>> moves = new HashMap<>();
    private final Set<UUID> voided = new HashSet<>();
    private final Set<UUID> uncollided = new LinkedHashSet<>();
    private final Set<UUID> barShown = new LinkedHashSet<>();
    private final Map<UUID, ArenaHost.Kit> kits = new HashMap<>();
    private int galleryNext;
    private long roundWeek;
    private long ticks;

    // for status
    private int resets;
    private int failedResets;
    private long resetWrites;
    private long resetTicks;
    private String lastResetError;
    private String closedNote;

    /** An arena with the settings as they are now: the box and the round knobs are fixed until it stops. */
    public ArenaService(ArenaHost host) {
        this.host = host;
        FallingFloorsSettings s = host.settings();
        this.box = s.box();
        this.rs = s.round();
        String w = host.worldName();
        this.world = w == null ? "" : w.trim();
        this.budget = new BuildBudget(host::nanoTime);
    }

    // ---- life -----------------------------------------------------------------------------------

    /** Make this week's floors; the rules start in RESET, so the first thing that happens is the boot verify. */
    public void start() {
        hold = host.holding();
        plan(host.weekKey(host.now()));
    }

    /** Stop: drop the reset, put everyone's collisions back and take the bar away. */
    public void stop() {
        cancelJob();
        if (writer != null) {
            writer.clear();
        }
        for (UUID p : new ArrayList<>(uncollided)) {
            host.collidable(p, true);
        }
        uncollided.clear();
        for (UUID p : new ArrayList<>(barShown)) {
            host.hideBar(p);
        }
        barShown.clear();
        moves.clear();
        voided.clear();
        kits.clear();
    }

    /** Make the week's site; on the first success the round starts (in RESET). */
    private boolean plan(long week) {
        lastPlanTry = host.now();
        Long secret = host.secret();
        if (secret == null) {
            planProblem = "the seed secret can't be read from the database yet";
            return false;
        }
        plannedWeek = week;
        ArenaSite s;
        try {
            s = ArenaPlanner.plan(box, ArenaPlanner.seed(secret, week), week);
        } catch (RuntimeException e) {
            planProblem = "this week's floors could not be made: " + e.getMessage();
            host.logger().log(Level.WARNING, "Falling Floors: " + planProblem, e);
            return false;
        }
        List<String> problems = ArenaValidator.problems(s);
        if (!problems.isEmpty()) {
            planProblem = "this week's floors failed their check: " + problems.get(0);
            host.logger().warning("Falling Floors: " + planProblem + (site == null ? "" : " - last week's stay up"));
            return false;
        }
        planProblem = null;
        site = s;
        if (round == null) {
            round = new ArenaRound(rs, s.layout().spawns().size());
            arena = new ArenaTick(round, s.layout(), rs);
        } else {
            arena.layout(s.layout());
            round.requestReset();
        }
        host.logger().info("Falling Floors: the week of " + LocalDate.ofEpochDay(week) + " is " + s.shapes()
                + " (seed " + GenSeed.shortHex(s.plan().seed()) + "..., plan " + s.plan().hash() + ")");
        return true;
    }

    // ---- every tick -----------------------------------------------------------------------------

    /** One tick: the reset's share of work, then the round (feet in, blocks and events out). */
    public void tick() {
        ticks++;
        if (round == null) {
            return;
        }
        driveReset();
        Map<UUID, List<Feet>> feet = feet();
        ArenaTick.Output out = arena.tick(feet, Set.copyOf(voided), hold);
        voided.clear();
        if (writer != null) {
            writer.queue(out.writes());
            writer.flush();
        }
        for (RoundEvent e : out.events()) {
            act(e);
        }
        tickUi();
    }

    /** Once a second: the restart hold, a new week's shape, a reset waiting for its world, the bars and kits. */
    public void check() {
        hold = host.holding();
        long now = host.now();
        if (round == null) {
            if (now - lastPlanTry >= 30_000L || lastPlanTry == Long.MIN_VALUE) {
                plan(host.weekKey(now));
            }
            return;
        }
        long week = host.weekKey(now);
        if (week != site.week() && (week != plannedWeek || (planProblem != null && now - lastPlanTry >= 60_000L))) {
            plan(week);
        }
        tryStartReset();
        secondUi();
    }

    /** Every position a round player's move reached this tick (the floors are marked by each). */
    public void moved(UUID player, double x, double y, double z, double vy) {
        if (round == null || round.phase() != ArenaRound.Phase.PLAYING || !round.isAlive(player)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(vy)) {
            return;
        }
        moves.computeIfAbsent(player, k -> new ArrayList<>()).add(new Feet(x, y, z, vy));
    }

    /** The void took a round player: out, whatever their feet say. */
    public void voided(UUID player) {
        if (round != null && round.isAlive(player)) {
            voided.add(player);
        }
    }

    /** Whether a player is held in place (moved to a spawn, waiting for Go): their moves are undone. */
    public boolean held(UUID player) {
        if (round == null) {
            return false;
        }
        ArenaRound.Phase ph = round.phase();
        return (ph == ArenaRound.Phase.TELEPORT || ph == ArenaRound.Phase.HOLD) && round.starters().contains(player);
    }

    private Map<UUID, List<Feet>> feet() {
        if (round.phase() != ArenaRound.Phase.PLAYING) {
            moves.clear();
            return Map.of();
        }
        Map<UUID, List<Feet>> out = new LinkedHashMap<>();
        for (UUID id : round.alive()) {
            List<Feet> seen = moves.remove(id);
            if (seen == null || seen.isEmpty()) {
                Feet now = host.feet(id);
                if (now == null) {
                    continue; // gone: a quit comes as a leave
                }
                seen = List.of(new Feet(now.x(), now.y(), now.z(), 0));
            }
            out.put(id, seen);
        }
        moves.clear();
        return out;
    }

    // ---- the events -----------------------------------------------------------------------------

    private void act(RoundEvent e) {
        switch (e) {
            case RoundEvent.CountdownStarted c -> {
                for (UUID p : round.members()) {
                    showBar(p);
                }
            }
            case RoundEvent.CountdownCancelled c -> {
                hideBars();
                String line = FloorsText.countdownStopped(c.why(), host.heldFor());
                if (line != null) {
                    tellMembers(line);
                }
            }
            case RoundEvent.RoundStarting s -> roundStarting(s);
            case RoundEvent.TeleportTo t -> {
                moves.remove(t.player());
                host.teleport(t.player(), world, site.spawn(arena.layout(), t.spawn()));
            }
            case RoundEvent.HoldStarted h -> {
                // the 3-2-1 is shown tick by tick (tickUi)
            }
            case RoundEvent.Go g -> {
                for (UUID p : round.alive()) {
                    host.title(p, ArenaText.go(), "", 30);
                    host.sound(p, ArenaHost.Cue.GO);
                }
            }
            case RoundEvent.SuddenDeath s -> tellMembers(ArenaText.suddenDeath());
            case RoundEvent.Out o -> out(o);
            case RoundEvent.Ended en -> ended(en.result());
            case RoundEvent.ResetNeeded r -> {
                cancelJob();
                if (writer != null) {
                    writer.clear();
                }
                pendingTicket = r.ticket();
                tryStartReset();
            }
            case RoundEvent.LobbyOpen l -> {
                refreshKits();
                lobbyBar();
            }
            case RoundEvent.Closed c -> closed(c.reason());
        }
    }

    private void roundStarting(RoundEvent.RoundStarting s) {
        hideBars();
        roundWeek = site.week();
        for (UUID p : s.players()) {
            if (uncollided.add(p)) {
                host.collidable(p, false);
            }
            giveKit(p, new ArenaHost.Kit(ArenaHost.KitKind.ROUND, false, false));
            host.tell(p, FloorsText.roundStarting(s.round(), s.players().size(), s.solo()));
        }
        for (UUID p : round.waiting()) {
            giveKit(p, new ArenaHost.Kit(ArenaHost.KitKind.WATCH, false, false));
            host.tell(p, FloorsText.ROUND_WITHOUT_YOU);
        }
    }

    private void out(RoundEvent.Out o) {
        UUID p = o.player();
        moves.remove(p);
        voided.remove(p);
        restoreCollision(p);
        if (o.reason() == OutReason.LEFT) {
            return; // they left: the session has taken them home
        }
        host.teleport(p, world, nextGallerySpot());
        host.tell(p, ArenaText.outLine(o));
        host.sound(p, ArenaHost.Cue.OUT);
        giveKit(p, new ArenaHost.Kit(ArenaHost.KitKind.WATCH, false, false));
    }

    private void ended(RoundResult r) {
        if (writer != null) {
            writer.clear();
        }
        hideBars();
        for (UUID p : new ArrayList<>(uncollided)) {
            restoreCollision(p);
        }
        if (round.phase() != ArenaRound.Phase.CLOSED) {
            toGallery(); // the one left standing (or a round called off on its spawns) watches from there
        }
        if (r.calledOff()) {
            if (round.phase() != ArenaRound.Phase.CLOSED) {
                tellMembers(ArenaText.calledOff());
            }
            return;
        }
        for (Standing s : r.standings()) {
            String line = ArenaText.standingLine(r, s);
            if (line != null) {
                host.tell(s.player(), line);
            }
        }
        List<String> lines = ArenaText.results(r, host::name);
        for (UUID p : round.members()) {
            for (String line : lines) {
                host.tell(p, line);
            }
            host.sound(p, ArenaHost.Cue.END);
        }
        host.scored(r, roundWeek);
    }

    private void closed(String reason) {
        cancelJob();
        pendingTicket = -1;
        verified = false; // opening again starts with a reset, like a boot
        if (writer != null) {
            writer.clear();
        }
        hideBars();
        for (UUID p : new ArrayList<>(uncollided)) {
            restoreCollision(p);
        }
        host.logger().warning("Falling Floors closed: " + reason + (closedNote == null ? "" : " (" + closedNote + ")"));
        for (UUID p : round.members()) {
            host.tell(p, ArenaText.closed());
            host.endSession(p);
        }
    }

    // ---- the reset ------------------------------------------------------------------------------

    /** Start the reset a ResetNeeded asked for, once the world is there and the box may be used. */
    private void tryStartReset() {
        if (pendingTicket < 0 || round == null || round.phase() != ArenaRound.Phase.RESET) {
            return;
        }
        if (world.isEmpty()) {
            waitingFor = "there is no Games world (games.worlds, or games.fresh.world)";
            return;
        }
        WorldPort port = host.world(world);
        if (port == null) {
            waitingFor = "the world " + world + " isn't loaded";
            return;
        }
        List<String> problems = host.regionProblems(box, world);
        if (problems != null && !problems.isEmpty()) {
            pendingTicket = -1;
            waitingFor = null;
            round.close("its box can't be used: " + problems.get(0) + " - move it with games.falling_floors.origin");
            return;
        }
        String claim;
        try {
            claim = host.claim();
        } catch (Exception e) {
            waitingFor = "its claim can't be read from the database";
            return;
        }
        waitingFor = null;
        int ticket = pendingTicket;
        pendingTicket = -1;
        ensureWriter(port);
        moveStrays();
        boolean claimed = claimText().equals(claim);
        if (!claimed && claim != null) {
            host.logger().info("Falling Floors: the box moved from " + claim + " - the old one's blocks are left"
                    + " where they are");
        }
        startJob(port, ticket, !claimed);
    }

    private void startJob(WorldPort port, int ticket, boolean scan) {
        job = new BuildJob(port, box, scan ? null : site.plan(), scan ? BuildJob.Mode.SCAN : BuildJob.Mode.CONVERGE);
        jobTicket = ticket;
        jobScan = scan;
        jobStarted = ticks;
    }

    private void driveReset() {
        if (job == null) {
            return;
        }
        if (host.world(world) == null) {
            host.logger().warning("Falling Floors: the world " + world + " went away during a reset");
            cancelJob();
            failedResets++;
            verified = false;
            lastResetError = "the world " + world + " went away";
            round.resetDone(jobTicket, false);
            return;
        }
        budget.begin(budget(), host.anyoneOnline(), host.mspt());
        try {
            job.tick(budget, RESET_LOADS, peopleHere(), host.now());
        } finally {
            budget.end();
        }
        for (UUID stuck : job.stuckPeople()) {
            moveStray(stuck, true);
        }
        if (job.done()) {
            BuildJob done = job;
            job = null;
            done.release();
            if (jobScan) {
                claimScanned(done);
                return;
            }
            resets++;
            resetWrites = done.writes();
            resetTicks = ticks - jobStarted;
            verified = true;
            closedNote = null;
            round.resetDone(jobTicket, true);
        } else if (job.failed()) {
            BuildJob failed = job;
            job = null;
            failed.release();
            failedResets++;
            verified = false; // nobody new comes in until a verify passes again
            lastResetError = failed.error();
            closedNote = failed.firstFound().isEmpty() ? null : "first at " + String.join(" ", failed.firstFound());
            host.logger().warning("Falling Floors: the reset failed (try " + round.resetAttempt() + " of "
                    + RoundSettings.RESET_ATTEMPTS + "): " + lastResetError);
            round.resetDone(jobTicket, false);
        }
    }

    /** The claim's count is in: an empty box is claimed and built; anything in it closes the game. */
    private void claimScanned(BuildJob scan) {
        if (scan.found() > 0) {
            closedNote = scan.firstFound().isEmpty() ? null : "first at " + String.join(" ", scan.firstFound());
            round.close("its box has " + scan.found() + " block" + (scan.found() == 1 ? "" : "s") + " that aren't"
                    + " Falling Floors' - /hcm games floors claim confirm clears them, or move it with"
                    + " games.falling_floors.origin");
            return;
        }
        try {
            host.claim(claimText());
        } catch (Exception e) {
            host.logger().log(Level.WARNING, "Falling Floors: could not record its claim", e);
            pendingTicket = jobTicket;
            waitingFor = "its claim can't be written to the database";
            return;
        }
        host.logger().info("Falling Floors: claimed its empty box (" + box.describe() + ")");
        WorldPort port = host.world(world);
        if (port == null) {
            pendingTicket = jobTicket;
            return;
        }
        startJob(port, jobTicket, false);
    }

    private void cancelJob() {
        if (job != null) {
            job.release();
            job = null;
        }
    }

    private DailySettings.Budget budget() {
        int perTick = host.settings().resetBlocksPerTick();
        return new DailySettings.Budget(perTick, perTick, RESET_MS, RESET_SNAPSHOTS, RESET_LOADS, PAUSE_MSPT);
    }

    private void ensureWriter(WorldPort port) {
        if (writer == null || writerPort != port) {
            writerPort = port;
            writer = new FloorWriter(port, box, this::roundFloors, site.floorBlocks());
        }
    }

    /** The floors of the round being played (the round's own), or the ones the next round starts on. */
    private FloorLayout roundFloors() {
        FloorRules f = arena.floors();
        return f != null ? f.layout() : arena.layout();
    }

    /** The claim's text: the world, the box's corner and its size. */
    String claimText() {
        return world.toLowerCase(Locale.ROOT) + "," + box.minX() + "," + box.minY() + "," + box.minZ() + ","
                + box.sizeX() + "," + box.sizeY() + "," + box.sizeZ();
    }

    /** Everyone in the arena's world (the reset waits for a write next to one of them). */
    private List<Person> peopleHere() {
        List<Person> out = new ArrayList<>();
        for (Person p : host.people()) {
            if (p.world() != null && p.world().equalsIgnoreCase(world)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Every arena player on or over the floors goes to the gallery (a round ended). */
    private void toGallery() {
        for (Person p : host.people()) {
            if (round.isMember(p.id()) && p.in(world, box) && !site.inGallery(p.x(), p.y(), p.z())) {
                moves.remove(p.id());
                host.teleport(p.id(), world, nextGallerySpot());
            }
        }
    }

    /** Before a reset: anyone in the box who isn't in the gallery is moved out of its way. */
    private void moveStrays() {
        for (Person p : host.people()) {
            if (!p.in(world, box) || site.inGallery(p.x(), p.y(), p.z())) {
                continue;
            }
            moveStray(p.id(), false);
        }
    }

    private void moveStray(UUID id, boolean stuck) {
        if (round.isMember(id)) {
            moves.remove(id);
            host.teleport(id, world, nextGallerySpot());
        } else {
            host.toSafety(id, world);
        }
        if (stuck) {
            host.logger().info("Falling Floors: moved someone who was in the reset's way for 30 s");
        }
    }

    private ArenaSite.Spot nextGallerySpot() {
        return site.gallery(galleryNext++);
    }

    // ---- players --------------------------------------------------------------------------------

    /**
     * Why {@code player} can't come in now ({@code &}-coded, for chat), or {@code null}: the floors
     * aren't verified yet, the arena is closed or full, or they are in already.
     */
    public String joinRefusal(UUID player) {
        if (round == null || site == null || !verified) {
            return round != null && round.phase() == ArenaRound.Phase.CLOSED ? ArenaText.closed() : FloorsText.FIXING;
        }
        if (round.phase() == ArenaRound.Phase.CLOSED) {
            return ArenaText.closed();
        }
        if (round.isMember(player)) {
            return ArenaText.join(ArenaRound.Join.ALREADY_IN, rs.maxPlayers());
        }
        if (round.members().size() >= rs.maxPlayers()) {
            return ArenaText.join(ArenaRound.Join.FULL, rs.maxPlayers());
        }
        return null;
    }

    /** Where someone coming in is put: the next gallery spot. {@code null} before the floors are made. */
    public ArenaSite.Spot entrySpot() {
        return site == null ? null : nextGallerySpot();
    }

    /**
     * A player's session is ready: they are in the gallery. Joins the round; {@code null} when they
     * are in, or why not (their session should then end).
     */
    public String joined(UUID player) {
        if (round == null || site == null) {
            return FloorsText.FIXING;
        }
        ArenaRound.Join j = round.join(player);
        if (j != ArenaRound.Join.JOINED && j != ArenaRound.Join.ALREADY_IN) {
            return ArenaText.join(j, rs.maxPlayers());
        }
        kits.remove(player);
        host.tell(player, FloorsText.WELCOME);
        String week = FloorsText.thisWeek(ArenaPlanner.shapesText(site.shapes()));
        if (week != null) {
            host.tell(player, week);
        }
        ArenaRound.Phase ph = round.phase();
        if (ph.inRound()) {
            host.tell(player, FloorsText.WATCH);
        } else if (ph == ArenaRound.Phase.RESET) {
            host.tell(player, FloorsText.FIXING);
        } else {
            host.tell(player, FloorsText.lobbyHint(soloOffered(player)));
        }
        refreshKits();
        if (ph == ArenaRound.Phase.COUNTDOWN) {
            showBar(player);
        }
        return null;
    }

    /** A player left the arena (Leave game, {@code /hcm leave}, a quit, their session ending any way). */
    public void left(UUID player) {
        if (round != null) {
            round.leave(player);
        }
        moves.remove(player);
        voided.remove(player);
        kits.remove(player);
        restoreCollision(player);
        if (barShown.remove(player)) {
            host.hideBar(player);
        }
        if (round != null && !round.members().isEmpty()) {
            refreshKits(); // the one left alone may now play solo
        }
    }

    /** The kit's Ready: on, or off again. */
    public void ready(UUID player) {
        if (round == null || !round.isMember(player)) {
            return;
        }
        ArenaRound.Phase ph = round.phase();
        if (ph != ArenaRound.Phase.LOBBY && ph != ArenaRound.Phase.COUNTDOWN) {
            host.actionBar(player, ph == ArenaRound.Phase.RESET ? FloorsText.FIXING : FloorsText.READY_LATER);
            return;
        }
        boolean on = !round.isReady(player);
        if (round.ready(player, on)) {
            host.actionBar(player, on ? FloorsText.READY_ON : FloorsText.READY_OFF);
            refreshKits();
        }
    }

    /** The kit's Play solo. */
    public void solo(UUID player) {
        if (round == null) {
            return;
        }
        ArenaRound.Solo s = round.solo(player, hold);
        String why = s == ArenaRound.Solo.HELD ? ArenaText.hold(host.heldFor() == null ? "soon" : host.heldFor())
                : ArenaText.solo(s);
        if (why != null) {
            host.tell(player, why);
        }
    }

    private boolean soloOffered(UUID player) {
        return rs.solo() && round.members().size() == 1 && round.isMember(player);
    }

    private void restoreCollision(UUID player) {
        if (uncollided.remove(player)) {
            host.collidable(player, true);
        }
    }

    // ---- the bits on screen ---------------------------------------------------------------------

    private void tickUi() {
        ArenaRound.Phase ph = round.phase();
        if (ph == ArenaRound.Phase.COUNTDOWN) {
            int left = round.countdownLeft();
            if (left % BAR_EVERY == 0) {
                float progress = Math.max(0f, Math.min(1f, left / (float) RoundSettings.COUNTDOWN_TICKS));
                for (UUID p : round.members()) {
                    host.bar(p, ArenaText.countdown(left), progress);
                    barShown.add(p);
                    if (left > 0 && left <= 3 * RoundSettings.TICKS_PER_SECOND
                            && left % RoundSettings.TICKS_PER_SECOND == 0) {
                        host.sound(p, ArenaHost.Cue.COUNT);
                    }
                }
            }
        } else if (ph == ArenaRound.Phase.HOLD) {
            int left = round.holdLeft();
            if (left > 0 && left % RoundSettings.TICKS_PER_SECOND == 0) {
                int seconds = left / RoundSettings.TICKS_PER_SECOND;
                for (UUID p : round.starters()) {
                    host.title(p, FloorsText.holdNumber(seconds), FloorsText.HOLD_SMALL, 25);
                    host.sound(p, ArenaHost.Cue.COUNT);
                }
            }
        }
    }

    private void secondUi() {
        ArenaRound.Phase ph = round.phase();
        switch (ph) {
            case LOBBY -> lobbyBar();
            case RESET -> {
                for (UUID p : round.members()) {
                    host.actionBar(p, FloorsText.FIXING);
                }
            }
            case PLAYING -> {
                int in = round.alive().size();
                for (UUID p : round.members()) {
                    host.actionBar(p, round.isAlive(p) ? FloorsText.playing(round.playTicks(), in, round.solo())
                            : FloorsText.watching(in));
                }
            }
            default -> {
                // the countdown has its bar; the 3-2-1 its titles
            }
        }
        refreshKits();
    }

    private void lobbyBar() {
        List<UUID> members = round.members();
        String line = hold ? ArenaText.hold(host.heldFor() == null ? "soon" : host.heldFor())
                : ArenaText.lobby(members.size(), round.readyCount(), rs.maxPlayers(), round.autoStartIn(), rs.solo());
        for (UUID p : members) {
            host.actionBar(p, line);
        }
    }

    /** Give everyone the kit for where they are now (only a kit that changed is given again). */
    private void refreshKits() {
        ArenaRound.Phase ph = round.phase();
        for (UUID p : round.members()) {
            ArenaHost.Kit k;
            if (ph == ArenaRound.Phase.LOBBY || ph == ArenaRound.Phase.COUNTDOWN) {
                k = new ArenaHost.Kit(ArenaHost.KitKind.LOBBY, round.isReady(p), soloOffered(p));
            } else if (ph.inRound() && round.isAlive(p)) {
                k = new ArenaHost.Kit(ArenaHost.KitKind.ROUND, false, false);
            } else {
                k = new ArenaHost.Kit(ArenaHost.KitKind.WATCH, false, false);
            }
            giveKit(p, k);
        }
    }

    private void giveKit(UUID p, ArenaHost.Kit k) {
        if (!k.equals(kits.get(p))) {
            kits.put(p, k);
            host.kit(p, k);
        }
    }

    private void showBar(UUID p) {
        int left = round.countdownLeft();
        host.bar(p, ArenaText.countdown(left), Math.max(0f, Math.min(1f, left / (float) RoundSettings.COUNTDOWN_TICKS)));
        barShown.add(p);
    }

    private void hideBars() {
        for (UUID p : new ArrayList<>(barShown)) {
            host.hideBar(p);
        }
        barShown.clear();
    }

    private void tellMembers(String line) {
        for (UUID p : round.members()) {
            host.tell(p, line);
        }
    }

    // ---- admin ----------------------------------------------------------------------------------

    /**
     * {@code /hcm games floors reset}: rebuild the floors now (from the lobby or a countdown; a round
     * going finishes first). A closed arena is opened again, starting with a reset.
     */
    public String requestReset() {
        if (round == null) {
            return "&cThe floors aren't made yet: " + (planProblem == null ? "starting" : planProblem);
        }
        if (round.phase() == ArenaRound.Phase.CLOSED) {
            closedNote = null;
            round.reopen();
            return "&aFalling Floors opens again: the floors are being put back and checked.";
        }
        return round.requestReset() ? "&aThe floors are being put back and checked."
                : "&eA round is going: the floors are put back when it ends.";
    }

    /**
     * {@code /hcm games floors claim [confirm]}: say whether the box is claimed; with
     * {@code confirm}, claim it (the next reset empties everything else in it) and open again.
     */
    public List<String> claim(boolean confirm) {
        List<String> out = new ArrayList<>();
        String claim;
        try {
            claim = host.claim();
        } catch (Exception e) {
            out.add("&cThe claim can't be read from the database right now.");
            return out;
        }
        boolean claimed = claimText().equals(claim);
        if (!confirm) {
            out.add(claimed ? "&aFalling Floors' box is claimed (" + box.describe() + ")."
                    : "&eFalling Floors' box isn't claimed yet (" + box.describe() + "): it is claimed at its first"
                    + " build when it is empty.");
            if (!claimed) {
                out.add("&7/hcm games floors claim confirm claims it now: the next reset clears anything in it.");
            }
            return out;
        }
        List<String> problems = world.isEmpty() ? List.of("there is no Games world") : host.regionProblems(box, world);
        if (problems != null && !problems.isEmpty()) {
            out.add("&cThe box can't be claimed: " + problems.get(0));
            return out;
        }
        if (!claimed) {
            try {
                host.claim(claimText());
            } catch (Exception e) {
                out.add("&cThe claim can't be written to the database right now.");
                return out;
            }
            host.logger().warning("Falling Floors: an admin claimed its box (" + box.describe() + "); the next reset"
                    + " clears anything in it");
        }
        out.add("&aClaimed.");
        out.add(requestReset());
        return out;
    }

    /** A gallery spot, for {@code /hcm games floors tp}; {@code null} before the floors are made. */
    public ArenaSite.Spot tpSpot() {
        return site == null ? null : site.gallery(0);
    }

    /** The lines under the game in {@code /hcm games status} (and {@code floors status}). */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("box " + box.describe() + " in " + (world.isEmpty() ? "(no world)" : world));
        if (round == null) {
            out.add("not started: " + (planProblem == null ? "making this week's floors" : planProblem));
            return out;
        }
        out.add("this week (" + LocalDate.ofEpochDay(site.week()) + "): " + site.shapes() + ", " + cells(site.layout())
                + ", " + site.layout().spawns().size() + " spawns, sudden death up to " + site.layout().maxRings() * 2
                + " s, plan " + site.plan().hash());
        if (planProblem != null) {
            out.add("next shape: " + planProblem);
        }
        ArenaRound.Phase ph = round.phase();
        String phase = ph.name().toLowerCase(Locale.ROOT);
        out.add(phase + ": " + round.members().size() + "/" + rs.maxPlayers() + " here, " + round.readyCount()
                + " ready" + (round.autoStartIn() >= 0 ? ", starts by itself in " + ArenaText.clock(round.autoStartIn())
                : "") + (hold ? ", held for a restart" : "") + (verified ? "" : ", not verified yet"));
        if (ph.inRound()) {
            out.add("round " + round.roundNo() + (round.solo() ? " (solo)" : "") + ": " + round.alive().size() + "/"
                    + round.starters().size() + " still in, " + ArenaText.clock(round.playTicks()) + " played"
                    + (round.suddenDeath() ? ", sudden death" : ""));
        }
        FloorRules f = arena.floors();
        if (f != null) {
            out.add("floors: " + f.marks() + " turned red, " + f.fades() + " gone");
        }
        if (writer != null) {
            out.add("writer: " + writer.waiting() + " waiting, " + writer.written() + " written (max " + writer.maxTick()
                    + " a tick of " + FloorWriter.MAX_PER_TICK + ")" + (writer.refused() > 0 ? ", " + writer.refused()
                    + " refused" : ""));
        }
        if (ph == ArenaRound.Phase.RESET) {
            if (job != null) {
                out.add((jobScan ? "claim scan" : "reset") + " try " + round.resetAttempt() + ": pass " + job.pass()
                        + ", " + job.waiting() + " to write" + (budget.paused() ? " (paused: the server is busy)" : ""));
            } else if (waitingFor != null) {
                out.add("reset waiting: " + waitingFor);
            }
        }
        out.add("resets: " + resets + " done, " + failedResets + " failed; last " + resetWrites + " blocks in "
                + resetTicks + " ticks (max " + String.format(Locale.ROOT, "%.1f", budget.maxMillis()) + " ms)"
                + (lastResetError == null ? "" : "; last failure: " + lastResetError));
        if (ph == ArenaRound.Phase.CLOSED) {
            out.add("closed: " + round.closedReason() + (closedNote == null ? "" : " (" + closedNote + ")")
                    + " - /hcm games floors reset opens it again");
        }
        return out;
    }

    private static String cells(FloorLayout l) {
        List<String> n = new ArrayList<>();
        for (int i = 0; i < l.layerCount(); i++) {
            n.add(Integer.toString(l.cellCount(i)));
        }
        return String.join("/", n) + " blocks";
    }

    // ---- reading it -----------------------------------------------------------------------------

    /** The round rules, or {@code null} before the first site. */
    public ArenaRound round() {
        return round;
    }

    /** This week's site, or {@code null} before the first. */
    public ArenaSite site() {
        return site;
    }

    /** The arena's box. */
    public Box box() {
        return box;
    }

    /** The arena's world ({@code ""} for none). */
    public String world() {
        return world;
    }

    /** Whether a verify has passed in this run (players may come in). */
    public boolean verified() {
        return verified;
    }

    /** How many are in the arena. */
    public int playing() {
        return round == null ? 0 : round.members().size();
    }

    /** The reset running now, or {@code null}. */
    BuildJob job() {
        return job;
    }

    /** Why a reset is waiting to start, or {@code null}. */
    String waitingFor() {
        return waitingFor;
    }

    /** The floor writer, once there is a world to write in. */
    FloorWriter writer() {
        return writer;
    }

    /** Players whose collisions are off now. */
    Set<UUID> uncollided() {
        return Set.copyOf(uncollided);
    }
}
