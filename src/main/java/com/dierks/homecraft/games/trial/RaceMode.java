package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.NoPush;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.games.world.Session;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Race mode inside Time Trials (EVENTS-DROPPER-SPEC §A.4.11): the server side of a racer's run.
 * Race Night (WP-R2) and party races ({@link PartyRaces}, owner decision D4) seat racers through
 * {@code TimeTrials.race} and get their news back through their {@link RaceLink}; this class is
 * where TimeTrials' hooks land, so the engine itself only gains a line at each place a race run
 * differs from a normal one.
 *
 * <p><b>What differs from a normal run</b> ({@link RaceRun} holds the state, pure and tested):
 * <ol>
 *   <li>It is an ordinary {@code trials} session with ref = the course id, entered at the grid
 *       spot, so Fresh Courses sees a player on the slot and waits (still standing, CLEAR_OLD).</li>
 *   <li>The countdown ends at the link's shared go tick, not after 60 ticks: boats are held at zero
 *       speed every tick until then, runners by the countdown hold. Every clock starts at one shared
 *       instant ({@link RaceRun.Clock}), so a racer seated late starts at once on the shared clock.</li>
 *   <li>Staleness is judged on the base course (its rev and layout, and the still-standing rule),
 *       never the course derived from the grid spot and the laps.</li>
 *   <li>At the line it calls {@link RaceLink#finished}, and never the course's normal finish unless
 *       the link says it is also a normal run (a party race), and then exactly once: a grid start's
 *       time isn't comparable with a solo one, so Race Night records nothing on the course. A
 *       counted race finish still tells the quests and achievements once (E4 FINISH_COURSE).</li>
 *   <li>The session ending (Leave game, a quit) calls {@link RaceLink#left}, unless the coordinator
 *       ended it itself.</li>
 *   <li>A run whose link is no longer {@link RaceLink#alive()} ends at its next tick with
 *       {@link RaceLink#calledOffLine()}: that covers Race Night being switched off or failing.</li>
 *   <li>{@link RaceRun.Holds} refuses new solo runs on a course held for a race.</li>
 * </ol>
 * Finishers are parked on the stand (the boat goes, the run idles) or, with no stand, sent home at
 * the line; race mode is the stand's one keeper (a racer who wanders off it is put back once, here,
 * within the link's {@link RaceLink#standRadius()}). A racer's collisions are off for the whole race
 * run (runners share the start spot, finishers the stand), and a parkour or elytra racer is on the
 * games' no-push team ({@link NoPush}) from seating until the run ends by any way at all, so nobody
 * shoves anybody; boats still bump, as the owner chose. Every teleport a finish causes happens on the
 * next trial tick, never inside the move event that saw the line. A racer the coordinator ends while
 * still on the way in goes straight home on arrival; a racer whose entry is dropped on the way (hands
 * not free, hurt, can't be reached: nothing tells the game) is swept once a tick and the race hears
 * {@link RaceLink#left}, so it never waits on a ghost; and a race's end never touches anybody's solo
 * run.
 *
 * <p><b>A coordinator can't break Time Trials.</b> Every call into a {@link RaceLink} is caught
 * here: a link that throws is logged once and treated as over, so its racers go home and every
 * solo run carries on.
 */
final class RaceMode {

    /** What a parked racer reads on the action bar. */
    static final String PARKED_BAR = "&7You're done - watch the others finish!";
    /** What a parked racer who wandered off reads. */
    static final String BACK_TO_STAND = "&7Please watch from the stand.";
    /** What a racer who left their grid spot before Go reads (they are put back on it). */
    static final String BACK_TO_SPOT = "&eStay on your grid spot until it says Go!";

    private final TimeTrials trials;
    private final RaceRun.Clock clock = new RaceRun.Clock();
    private final RaceRun.Holds holds = new RaceRun.Holds();
    /** Links that threw once: treated as over from then on. */
    private final Set<RaceLink> broken = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Racers on their way in (their session is being entered): the race that asked for them. The
     * coordinator may end a racer before they arrive; their arrival then sends them straight home,
     * and nobody else's run (a solo run started later) is ever touched by a race's end.
     */
    private final Map<UUID, Arrival> arriving = new HashMap<>();
    /** The no-push team a test passes; {@code null}: the games' own ({@code GamesService.noPush()}). */
    private NoPush pushes;

    /** A racer on the way in: their race, and whether (and how) the race sent them home meanwhile. */
    private static final class Arrival {
        final RaceLink link;
        boolean sentHome;
        EndReason why;
        String line;

        Arrival(RaceLink link) {
            this.link = link;
        }
    }

    RaceMode(TimeTrials trials) {
        this.trials = trials;
    }

    RaceRun.Holds holds() {
        return holds;
    }

    // ---- seating ------------------------------------------------------------------------------

    /** {@code TimeTrials.race}: seat a racer (see there). {@code null} when they are on their way in. */
    Refusal race(Player p, Course base, Course raced, Course.Spot grid, Location stand, RaceLink link) {
        if (p == null || !p.isOnline() || base == null || raced == null || link == null) {
            return Refusal.of("That racer isn't here.");
        }
        if (!alive(link)) {
            return Refusal.of("That race is over.");
        }
        World world = base.ready() ? Bukkit.getWorld(base.world()) : null;
        if (world == null || raced.start() == null) {
            return Refusal.of("That course isn't ready right now.");
        }
        ClubDoor club = door(); // WP-CH: a racer waiting in the Clubhouse is seated from there, in their session
        if (club != null && club.seatable(p.getUniqueId())) {
            return ClubRaces.seat(trials, this, club, p, base, raced, grid, stand, link, world);
        }
        Refusal closed = trials.sessions().entryRefusal(trials);
        if (closed != null) {
            return closed;
        }
        if (trials.sessions().session(p) != null || !trials.sessions().home(p)) {
            return Refusal.IN_SESSION;
        }
        long now = Bukkit.getCurrentTick();
        boolean warm = call(link, () -> link.warmupUntil() > now, false);
        Course.Spot spot = grid != null ? grid : raced.start();
        Course.Spot at = entrySpot(base, spot, warm);
        Point standAt = stand != null && stand.getWorld() != null && stand.getWorld().equals(world)
                ? TimeTrials.point(stand) : null;
        Location to = new Location(world, at.x(), at.y(), at.z(), at.yaw(), at.pitch());
        UUID id = p.getUniqueId();
        expect(id, link);
        boolean in = trials.sessions().enter(p, trials, base.id(), to,
                q -> seated(q, new RaceRun(link, base, spot, standAt, warm), raced));
        if (!in) {
            arriving.remove(id);
            return Refusal.of("Stand still somewhere safe to join the race.");
        }
        return null;
    }

    /**
     * Where a racer's world session enters them: their grid spot, or for the shared warm-up the
     * course's start, except a boat, which warms up from its own grid spot too (up to 8 boats spawned
     * on one point would sit inside each other).
     */
    static Course.Spot entrySpot(Course base, Course.Spot spot, boolean warm) {
        return warm && base.kind() != TrialKind.BOAT ? base.start() : spot;
    }

    /** A racer is on the way in for {@code link}'s race (their entry started). */
    void expect(UUID id, RaceLink link) {
        arriving.put(id, new Arrival(link));
    }

    /** Whether {@code id} is on the way in (for the tests). */
    boolean arriving(UUID id) {
        return arriving.containsKey(id);
    }

    /** Whether {@code id} is on the way in for {@code link}'s race. */
    boolean arrivingFor(UUID id, RaceLink link) {
        Arrival a = id == null ? null : arriving.get(id);
        return a != null && a.link == link;
    }

    /**
     * Once a tick (Time Trials' tick): a racer whose entry was dropped on the way in never arrives,
     * and nothing tells the game (the hands weren't free at the depart, they were hurt or jumped, the
     * start couldn't be reached, a saved-state row turned up). Anyone on the way in who is offline or
     * no longer entering a Time Trials session is forgotten, and unless their race already sent them
     * home it hears {@link RaceLink#left} (ADMIN), once: a party race or Race Night never waits on a
     * ghost. A session still ENTERING is still coming, so there is no false alarm.
     */
    void sweepArrivals() {
        if (!arriving.isEmpty()) {
            sweepArrivals(this::stillComing);
        }
    }

    /** {@link #sweepArrivals()} with who is still on the way in decided by {@code stillComing}. */
    void sweepArrivals(Predicate<UUID> stillComing) {
        for (UUID id : new ArrayList<>(arriving.keySet())) {
            if (stillComing.test(id)) {
                continue;
            }
            Arrival a = arriving.remove(id);
            if (a != null && !a.sentHome) {
                call(a.link, () -> {
                    a.link.left(id, EndReason.ADMIN);
                    return null;
                }, null);
            }
        }
    }

    private boolean stillComing(UUID id) {
        Player p = online(id);
        if (p == null || !p.isOnline()) {
            return false;
        }
        Session s = trials.sessions().session(p);
        return s != null && trials.id().equals(s.gameId()) && s.phase() == Session.Phase.ENTERING;
    }

    /**
     * In, saved, cleared: the kit, the boat, and the grid (or the shared warm-up's free laps). A racer
     * the race sent home while they were on the way goes straight back; one who arrives after the
     * shared warm-up ended goes straight to their grid spot.
     */
    private void seated(Player p, RaceRun rr, Course raced) {
        trials.end(p);
        Arrival a = arriving.remove(p.getUniqueId());
        if (a == null || a.link != rr.link || a.sentHome || !alive(rr.link)) {
            String line = a != null && a.sentHome ? a.line : call(rr.link, rr.link::calledOffLine,
                    "&7The race was called off.");
            if (line != null && !line.isBlank()) {
                p.sendMessage(Text.of(line));
            }
            trials.sessions().leave(p, a != null && a.sentHome && a.why != null ? a.why : EndReason.ADMIN);
            return;
        }
        long now = Bukkit.getCurrentTick();
        long until = call(rr.link, rr.link::warmupUntil, 0L);
        boolean warm = rr.state == RaceRun.State.WARMUP && until > now;
        TrialRun run = new TrialRun(p.getUniqueId(), warm ? rr.base : raced, false, 0);
        run.race = rr;
        trials.replaceRun(run);
        trials.giveKit(p, run.course.kind());
        p.setFallDistance(0f);
        inRace(p, rr);
        if (run.course.kind() == TrialKind.BOAT) {
            trials.seat(p, run, p.getLocation());
        }
        if (warm && run.beginWarmup(until)) {
            run.progress = new Progress(run.course, TimeTrials.position(p, run), System.nanoTime());
            run.phase = TrialRun.Phase.RUNNING;
            p.getInventory().setItem(Warmup.KIT_SLOT, KitItems.item(trials, Warmup.READY, Material.LIME_DYE,
                    Warmup.READY_NAME, "&7Tap when you're set.", "&7The race starts when the warm-up",
                    "&7ends, or everyone is ready."));
            long left = Warmup.secondsLeft(now, run.warmupEnds);
            p.sendMessage(Text.of("&b" + rr.base.name() + " &7- race warm-up"));
            p.sendMessage(Text.of(Warmup.started((int) left)));
            p.sendMessage(Text.of(Warmup.HOW_TO_READY));
            return;
        }
        boolean late = rr.state == RaceRun.State.WARMUP; // the warm-up ended while they were on the way
        rr.state = RaceRun.State.GRID;
        p.sendMessage(Text.of("&b" + rr.base.name() + " &7- on the grid. Wait for Go!"));
        boolean atStart = late && entrySpot(rr.base, rr.grid, true) != rr.grid; // a boat warms up on its spot
        Location spot = atStart ? spot(raced.world(), rr.grid) : null;
        if (spot != null) {
            trials.move(p, run, spot); // they arrived at the course's start: onto their grid spot
        }
    }

    // ---- the tick -------------------------------------------------------------------------------

    /**
     * A race run's tick, before the normal one. True when it was handled here (the grid, the stand,
     * a called-off race, a pending park or trip home); false lets the normal tick run it (racing, and
     * the shared warm-up's free laps).
     */
    boolean tick(Player p, TrialRun run, long now) {
        RaceRun rr = run.race;
        switch (rr.next(alive(rr.link))) {
            case ENDED -> {
                return true; // on the way home
            }
            case HOME -> {
                rr.due = RaceRun.Due.NONE;
                rr.ended = true;
                if (rr.toClub && ClubRaces.toClub(trials, this, door(), p, run, rr.line)) {
                    return true; // WP-CH: to the Clubhouse instead of home
                }
                if (rr.line != null && !rr.line.isBlank()) {
                    p.sendMessage(Text.of(rr.line));
                }
                trials.sessions().leave(p, rr.why == null ? EndReason.FINISH : rr.why);
                return true;
            }
            case CALLED_OFF -> {
                rr.ended = true;
                p.sendMessage(Text.of(call(rr.link, rr.link::calledOffLine, "&7The race was called off.")));
                trials.sessions().leave(p, EndReason.ADMIN);
                return true;
            }
            case PARK -> {
                rr.due = RaceRun.Due.NONE;
                parkNow(p, run);
                return true;
            }
            case GRID -> {
                grid(p, run, now);
                return true;
            }
            case STAND -> {
                if (run.ticks % 10 == 0) {
                    onStand(p, run); // race mode is the stand's one keeper (Race Night's radius comes through its link)
                }
                return true;
            }
            case RACE -> {
                if (run.running() && run.ticks % RaceRun.PROGRESS_EVERY == 0) {
                    report(p, run);
                }
                return false;
            }
            default -> {
                return false; // the shared warm-up's free laps run as a normal run with the warm-up bar
            }
        }
    }

    /** On the grid: held still until the shared go tick, 3-2-1, then every clock starts at one instant. */
    private void grid(Player p, TrialRun run, long now) {
        RaceRun rr = run.race;
        boolean boat = run.course.kind() == TrialKind.BOAT;
        if (boat) {
            if (TimeTrials.seated(p, run)) {
                run.boat.setVelocity(new Vector());
            } else if (now >= run.reseatUntil && run.expect == null) {
                trials.seat(p, run, p.getLocation());
            }
        }
        boolean arrived = boat ? TimeTrials.seated(p, run) : run.expect == null;
        boolean offSpot = arrived && rr.offSpot(TimeTrials.position(p, run));
        RaceRun.Release r = call(rr.link, () -> rr.release(now, arrived, offSpot, clock, System::nanoTime),
                new RaceRun.Release(true, 0, false, 0));
        if (r.back()) {
            backToSpot(p, run); // drifted off the spot: back on it, and never released from where it drifted to
        }
        if (r.hold()) {
            if (r.count() > 0) {
                TimeTrials.title(p, "&e" + r.count(), "&7Get ready", 20);
                TimeTrials.ping(p, 1.0f);
            }
            return;
        }
        if (!r.go()) {
            return; // still arriving (or going back to the spot): starts once in, on the shared clock
        }
        run.progress = new Progress(run.course, TimeTrials.position(p, run), r.nanos());
        run.phase = TrialRun.Phase.RUNNING;
        rr.started();
        TimeTrials.title(p, "&aGo!", "", 15);
        TimeTrials.ping(p, 2.0f);
        trials.watch(p, run);
        if (clock.size() > 16) {
            clock.keepOnly(liveLinks());
        }
    }

    /** A racer off their grid spot goes back onto it (a boat is re-seated there), once the last move landed. */
    private void backToSpot(Player p, TrialRun run) {
        Location at = spot(run.course.world(), run.race.grid);
        if (at != null && run.expect == null && trials.move(p, run, at)) {
            p.sendActionBar(Text.of(BACK_TO_SPOT));
        }
    }

    /** Where the racer is, for the coordinator's live positions. */
    private void report(Player p, TrialRun run) {
        RaceRun rr = run.race;
        Progress pr = run.progress;
        int reached = pr.reachedTargets();
        List<Course.Mark> targets = run.course.targets();
        double toNext = 0;
        if (reached < targets.size()) {
            Course.Mark next = targets.get(reached);
            toNext = Math.max(0, TimeTrials.position(p, run).distance(next.center()) - next.radius());
        }
        long[] times = pr.times();
        long at = reached > 0 ? times[reached - 1] : pr.startNanos();
        double d = toNext;
        call(rr.link, () -> {
            rr.link.progress(p.getUniqueId(), reached, d, at);
            return null;
        }, null);
    }

    /** A parked racer: the action bar, and back onto the stand if they wandered off it. */
    private void onStand(Player p, TrialRun run) {
        RaceRun rr = run.race;
        if (run.ticks % 20 == 0) {
            p.sendActionBar(Text.of(PARKED_BAR));
        }
        double radius = call(rr.link, rr.link::standRadius, RaceRun.STAND_RADIUS);
        if (run.expect == null && rr.offStand(TimeTrials.point(p.getLocation()), radius)) {
            toStand(p, run);
            p.sendMessage(Text.of(BACK_TO_STAND));
        }
        if (rr.stand != null) { // final gate, group B (#15): their rider is held to the same stand, same rule
            Location stand = new Location(p.getWorld(), rr.stand.x(), rr.stand.y(), rr.stand.z());
            trials.riders().onStand(p.getUniqueId(), at -> rr.offStand(TimeTrials.point(at), radius), stand);
        }
    }

    // ---- the line -------------------------------------------------------------------------------

    /**
     * A racer crossed the line: judged as a solo run is (fair play, the shortest believable time, the
     * speed check from the grid spot), stale only when the base course changed; reported to the link;
     * for a party race, also the course's normal finish, once; then parked, or home at the line.
     */
    void finish(Player p, TrialRun run, long nanos) {
        RaceRun rr = run.race;
        run.phase = TrialRun.Phase.DONE;
        long ms = run.elapsedMs(nanos);
        TimeTrialsSettings s = trials.settings();
        boolean stale = rr.stale(trials.course(rr.base.id()), trials.generated()::standing);
        int tooFast = FairPlay.tooFast(run.course, run.progress.startNanos(), run.progress.times(),
                run.progress.reachedTargets(), run.stalls);
        FairPlay.Verdict verdict = FairPlay.judge(false, run.voided, stale, ms,
                run.course.minSecondsOr(s.minSeconds()), tooFast);
        // WP-CH (the review's #12): with rider_runs_count false a ride with a rider is just for fun: the
        // place in the race stands, but it is never the course's normal run (no board, record, rewards,
        // Cup time or quest step)
        boolean fun = verdict.counts() && trials.justForFun(p, run);
        boolean normalRun = call(rr.link, rr.link::normalRun, false); // guarded: a link that throws is over
        RaceRun.Line line = rr.line(verdict.counts(), normalRun && !fun);
        if (!line.report()) {
            return; // this race's line was crossed already
        }
        p.sendMessage(Text.of("&b" + rr.base.name() + ": &f" + TrialText.time(ms)));
        if (!verdict.counts()) {
            p.sendMessage(Text.of(voidLine(verdict.reason())));
            TimeTrials.title(p, "&f" + TrialText.time(ms), "&cThat race didn't count", 40);
            Sounds.miss(p);
        } else if (fun) {
            p.sendMessage(Text.of(Riders.FUN_RACE));
        }
        if (line.normal()) {
            trials.settleCounted(p, run, ms, verdict); // boards, rewards, the Cup and E4, as a solo run
        } else if (line.e4() && !fun) {
            Course c = rr.base;
            trials.games().tellProgress(g -> g.courseFinished(p, c.id(), c.generated(), false)); // E4, once
        }
        UUID id = p.getUniqueId();
        call(rr.link, () -> {
            rr.link.finished(id, ms, verdict.counts(), verdict.reason());
            return null;
        }, null);
        if (trials.run(id) == run && !rr.ended && rr.due == RaceRun.Due.NONE) {
            if (line.next() == RaceRun.Due.PARK) {
                rr.due = RaceRun.Due.PARK;
            } else {
                rr.home(EndReason.FINISH, null); // no stand to wait on: home at the line
                rr.toClub = call(rr.link, rr.link::clubhouseAfter, false); // WP-CH: or the Clubhouse
            }
        }
    }

    /** "&amp;cThat race didn't count - flying." */
    static String voidLine(String reason) {
        return "&cThat race didn't count &7- " + (reason == null ? "it broke a rule" : reason) + ".";
    }

    // ---- the coordinator's calls ------------------------------------------------------------------

    /** {@code TimeTrials.park}: done with this race; onto the stand on the next tick. */
    void park(Player p) {
        TrialRun run = p == null ? null : trials.run(p.getUniqueId());
        if (run == null || run.race == null || run.race.ended) {
            return;
        }
        if (run.race.state == RaceRun.State.PARKED && run.race.due == RaceRun.Due.NONE) {
            return;
        }
        run.phase = TrialRun.Phase.DONE; // moves stop counting now
        run.backDue = false;
        run.race.due = RaceRun.Due.PARK;
    }

    private void parkNow(Player p, TrialRun run) {
        RaceRun rr = run.race;
        trials.removeBoat(run, p);
        run.phase = TrialRun.Phase.PARKED;
        run.backDue = false;
        rr.parked();
        if (run.warmup) {
            run.endWarmup();
            p.getInventory().setItem(Warmup.KIT_SLOT, null);
        }
        if (rr.stand != null) {
            toStand(p, run);
            inRace(p, rr); // nobody shoves anybody off the stand; restored when the run ends
        }
        p.sendActionBar(Text.of(PARKED_BAR));
    }

    private void toStand(Player p, TrialRun run) {
        RaceRun rr = run.race;
        World w = Bukkit.getWorld(run.course.world());
        if (w == null || rr.stand == null) {
            return;
        }
        Location here = p.getLocation();
        Location at = new Location(w, rr.stand.x(), rr.stand.y(), rr.stand.z(), here.getYaw(), 0f);
        run.expect = at;
        run.suspended = true;
        if (!trials.sessions().teleport(p, at)) {
            run.expect = null;
            run.suspended = false;
        } else {
            p.setVelocity(new Vector());
            p.setFallDistance(0f);
            trials.riders().follow(p, at); // WP-CH: the rider stands on the stand with them
        }
    }

    /** {@code TimeTrials.regrid}: the next race (or the end of the shared warm-up), a new boat on the spot. */
    void regrid(Player p, Course raced, Course.Spot grid) {
        TrialRun old = p == null ? null : trials.run(p.getUniqueId());
        if (old == null || old.race == null || old.race.ended || raced == null) {
            return;
        }
        World w = Bukkit.getWorld(raced.world());
        Course.Spot spot = grid != null ? grid : raced.start();
        if (w == null || spot == null) {
            return;
        }
        RaceRun rr = old.race;
        trials.removeBoat(old, p);
        if (old.warmup) {
            old.endWarmup();
        }
        p.getInventory().setItem(Warmup.KIT_SLOT, null);
        rr.regrid(spot);
        rr.due = RaceRun.Due.NONE;
        TrialRun run = new TrialRun(p.getUniqueId(), raced, false, 0);
        run.race = rr;
        run.warmupUsed = old.warmupUsed;
        trials.replaceRun(run);
        trials.move(p, run, new Location(w, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()));
    }

    /** {@code TimeTrials.endRace}: home with their things, reading {@code line}, on the next trial tick. */
    void endRace(UUID racer, EndReason why, String line) {
        endRace(racer, why, line, false);
    }

    /** {@link #endRace(UUID, EndReason, String)}, or with {@code toClub} to the Clubhouse instead of home (WP-CH). */
    void endRace(UUID racer, EndReason why, String line, boolean toClub) {
        if (racer == null) {
            return;
        }
        Arrival a = arriving.get(racer);
        if (a != null) {
            a.sentHome = true; // on the way in: their arrival sends them straight home (seated)
            a.why = why;
            a.line = line;
            return;
        }
        TrialRun run = trials.run(racer);
        if (run == null || run.race == null || run.race.ended) {
            return; // not racing (home already, or on a solo run of their own): nothing to end
        }
        run.phase = TrialRun.Phase.DONE;
        run.race.home(why, line);
        run.race.toClub = toClub;
    }

    // ---- sessions ending --------------------------------------------------------------------------

    /** The racer's session ended (Leave game, a quit, a kick): {@link RaceLink#left}, unless the coordinator ended it. */
    void left(Player p, EndReason why) {
        TrialRun run = p == null ? null : trials.run(p.getUniqueId());
        if (run == null || run.race == null) {
            Arrival a = p == null ? null : arriving.remove(p.getUniqueId());
            if (a != null && !a.sentHome) { // called off on arrival (or gone before it): the race hears it once
                UUID id = p.getUniqueId();
                call(a.link, () -> {
                    a.link.left(id, why);
                    return null;
                }, null);
            }
            return;
        }
        RaceRun rr = run.race;
        restore(p.getUniqueId(), p, rr);
        boolean told = rr.ended || rr.due == RaceRun.Due.HOME;
        rr.ended = true;
        rr.due = RaceRun.Due.NONE;
        if (!told) {
            UUID id = p.getUniqueId();
            call(rr.link, () -> {
                rr.link.left(id, why);
                return null;
            }, null);
        }
    }

    /** The run's session vanished without an end (the backstop in the tick). */
    void gone(TrialRun run, Player p) {
        if (run.race == null) {
            return;
        }
        RaceRun rr = run.race;
        restore(run.player, p, rr); // off the no-push team even when they are gone (by id)
        if (!rr.ended && rr.due != RaceRun.Due.HOME) {
            rr.ended = true;
            call(rr.link, () -> {
                rr.link.left(run.player, EndReason.ADMIN);
                return null;
            }, null);
        }
    }

    /** A parked racer fell out of the world or was nudged: back on the stand. True when handled. */
    boolean onVoid(Player p, TrialRun run) {
        if (run.race == null || run.phase == TrialRun.Phase.RUNNING) {
            return false;
        }
        if (run.phase == TrialRun.Phase.PARKED && run.race.stand != null) {
            toStand(p, run);
        } else if (run.race.state == RaceRun.State.GRID && run.expect == null) {
            Location at = spot(run.course.world(), run.race.grid);
            if (at != null) {
                trials.move(p, run, at); // back onto the grid spot, still held
            }
        }
        return true;
    }

    private static Location spot(String worldName, Course.Spot s) {
        World w;
        try {
            w = s == null ? null : Bukkit.getWorld(worldName);
        } catch (RuntimeException | LinkageError e) {
            w = null; // no server (a test): nowhere to put them
        }
        return w == null ? null : new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    }

    /** A course held for a race refuses a new solo run: the holder's line. True when refused. */
    boolean refuseSolo(Player p, String courseId) {
        String line = holds.refusal(courseId);
        if (line == null) {
            return false;
        }
        p.sendMessage(Text.of(line));
        try {
            Sounds.refused(p);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
        return true;
    }

    /**
     * Where a race boat sent back to a checkpoint is re-seated: up to 1.5 blocks sideways for a spot
     * with no other race boat within 2 ({@link RaceSeat}). Anything else goes back exactly as before.
     */
    Location reseat(TrialRun run, Location at) {
        if (at == null || run.race == null || run.course.kind() != TrialKind.BOAT || at.getWorld() == null) {
            return at;
        }
        List<Point> others = new ArrayList<>();
        for (TrialRun r : trials.liveRuns()) {
            if (r != run && r.race != null && r.race.link == run.race.link && r.boat != null && r.boat.isValid()) {
                others.add(TimeTrials.point(r.boat.getLocation()));
            }
        }
        if (others.isEmpty()) {
            return at;
        }
        WorldSurface surface = new WorldSurface(at.getWorld());
        Point p = RaceSeat.clear(TimeTrials.point(at), at.getYaw(), others, q -> RaceGrid.problem(q, surface) == null);
        return new Location(at.getWorld(), p.x(), p.y(), p.z(), at.getYaw(), at.getPitch());
    }

    /** Time Trials is stopping: collisions back on (and off the no-push team) for every racer, nothing held. */
    void stop() {
        for (TrialRun run : new ArrayList<>(trials.liveRuns())) {
            if (run.race != null) {
                restore(run.player, online(run.player), run.race);
            }
        }
        clock.clear();
        holds.clear();
        broken.clear();
        arriving.clear();
    }

    /**
     * Seated in a race (its warm-up or the grid), and again on the stand: nobody shoves anybody
     * ({@link #noShoving}), and a parkour or elytra racer joins the games' no-push team, which is what
     * really stops one player pushing another ({@code setCollidable} only stops mobs). Never a boat
     * race: boats bump, as the owner chose. Every way the run ends {@link #restore}s both.
     */
    void inRace(Player p, RaceRun rr) {
        noShoving(p, rr);
        if (!rr.noPush && rr.base.kind() != TrialKind.BOAT) {
            NoPush np = noPush();
            if (np != null && np.on(p)) {
                rr.noPush = true;
            }
        }
    }

    /** The games' no-push team (or the one a test passed). */
    private NoPush noPush() {
        if (pushes != null) {
            return pushes;
        }
        try {
            GamesService g = trials.games();
            return g == null ? null : g.noPush();
        } catch (RuntimeException e) {
            return null; // no framework (a test): nothing to join
        }
    }

    /** A test's own no-push team (over a fake scoreboard). */
    void pushes(NoPush team) {
        this.pushes = team;
    }

    // ---- WP-CH: the Clubhouse ------------------------------------------------------------------------

    /** The door a test passes; {@code null}: the Clubhouse's own ({@link Clubhouse#door}), if it is open. */
    private ClubDoor clubDoor;
    private boolean clubDoorSet;

    /** The Clubhouse's door, or {@code null} while it is off, closed or not built (every flow as before). */
    ClubDoor door() {
        if (clubDoorSet) {
            return clubDoor;
        }
        try {
            return Clubhouse.door(trials.games());
        } catch (RuntimeException | LinkageError e) {
            return null; // no framework (a test): no Clubhouse
        }
    }

    /** A test's own Clubhouse door ({@code null}: none at all). */
    void door(ClubDoor door) {
        this.clubDoor = door;
        this.clubDoorSet = true;
    }

    /** The player if online, or {@code null} (never a throw: no server in a test). */
    private static Player online(UUID id) {
        try {
            return Bukkit.getPlayer(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Racers never push each other (runners share the start spot; finishers share the stand): the
     * player's collisions are off for the whole race run, and {@link #restore}d when it ends. It
     * isn't saved with the player, so a crash can't leave it stuck. Boats still bump: the boat is
     * its own entity.
     */
    private static void noShoving(Player p, RaceRun rr) {
        if (rr.noShove) {
            return;
        }
        try {
            p.setCollidable(false);
            rr.noShove = true;
        } catch (RuntimeException | LinkageError ignored) {
            // cosmetic
        }
    }

    /**
     * The run ended (left, gone, Time Trials stopping): off the no-push team (by id, so a racer who is
     * already gone comes off too) and collisions back on.
     */
    void restore(UUID id, Player p, RaceRun rr) {
        if (rr.noPush) {
            rr.noPush = false;
            NoPush np = noPush();
            if (np != null) {
                np.off(id, p == null ? null : p.getName());
            }
        }
        if (p != null && rr.noShove) {
            rr.noShove = false;
            try {
                p.setCollidable(true);
            } catch (RuntimeException | LinkageError ignored) {
                // nothing to restore on a player who is gone
            }
        }
    }

    private Set<RaceLink> liveLinks() {
        Set<RaceLink> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TrialRun r : trials.liveRuns()) {
            if (r.race != null) {
                out.add(r.race.link);
            }
        }
        return out;
    }

    // ---- calling a link safely --------------------------------------------------------------------

    /** Whether the link's race still runs (a link that ever threw is over). */
    boolean alive(RaceLink link) {
        return !broken.contains(link) && call(link, link::alive, false);
    }

    /** Call into a link: what it returns, or {@code fallback} (and the link counted as over) if it throws. */
    <T> T call(RaceLink link, Supplier<T> what, T fallback) {
        if (broken.contains(link)) {
            return fallback;
        }
        try {
            return what.get();
        } catch (RuntimeException | LinkageError e) {
            broken.add(link);
            try {
                trials.log().log(Level.WARNING, "Time trials: a race's coordinator failed; its racers go home", e);
            } catch (RuntimeException ignored) {
                // no logger (a test): the link is over all the same
            }
            return fallback;
        }
    }
}
