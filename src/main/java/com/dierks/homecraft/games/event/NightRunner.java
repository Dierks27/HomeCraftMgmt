package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;
import com.dierks.homecraft.storage.EventDao;

import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One Race Night, live (EVENTS-DROPPER-SPEC §A.2-§A.4, §A.9; EVENTS-RECONCILED 1-2, D3): it runs
 * the {@link EventMachine}, does its actions through Time Trials' race mode, and is the night's
 * {@link RaceLink}. Everything it needs from the server comes through {@link NightPorts}, so a
 * whole night runs start to finish in the tests with a fake clock and fake racers.
 *
 * <p><b>The racers.</b> Joining writes an entry (IN). At the grid call every joined, free racer is
 * taken to the track, two a tick, each into an ordinary Time Trials run in race mode: into the
 * shared warm-up (free laps, never timed; "Ready" skips the rest) or straight onto the grid. Anyone
 * not free is tried again every second until just before Go, then is DNS for that race ("you'll be
 * in the next one"). At the line a racer's place is final at once and they are parked on the stand;
 * when the race ends anyone still racing is parked too and scores the still-racing point. Leave
 * game or {@code /hcm leave} is leaving for good (LEFT); a disconnect scores 0 in that race and,
 * back and free before the next grid, is pulled back in.
 *
 * <p><b>Storing.</b> Each race is stored in one transaction ({@link EventDao#storeRace}): its rows,
 * the night's points and the season board. Settling stores the places and prizes in one
 * transaction, then pays through the {@link PayLoop} (once per racer by the ref).
 *
 * <p><b>Stopping.</b> {@link #callOff} ends it at once: racers go home with their things, and a
 * night with races stored settles on them (points stand; prizes are paid or owed if it held a
 * prize slot).
 */
public final class NightRunner implements RaceLink {

    /** How many racers are taken to (or sent home from) the track each tick. */
    public static final int PER_TICK = 2;
    /** Two finishes this close are a photo finish (ms). */
    static final long PHOTO_MS = 200;
    /** A seating retry line at most this often per racer (ms). */
    static final long NAG_MS = 5_000;

    /** A racer's part in the current race. */
    public enum Leg {
        /** Not at the track (not seated yet, DNS, or out after a disconnect). */
        AWAY,
        /** At the track: in the warm-up or on the grid, not released yet. */
        WAITING,
        RACING,
        FINISHED,
        VOID,
        /** Still racing when the race ended: parked. */
        STILL,
        /** Out of this race (disconnected, their session ended): 0 points. */
        OUT
    }

    /** One racer tonight. */
    public static final class Racer {
        final UUID id;
        String name;
        final long joinedAt;
        boolean left;
        boolean seated;
        boolean ready;
        boolean sentHome;
        boolean everSeated;
        boolean finishedAny;
        Leg leg = Leg.AWAY;
        long ms;
        int reached;
        double toNext;
        long reachedAt;
        int points;
        final List<Integer> places = new ArrayList<>();
        String refused;
        long naggedAt = Long.MIN_VALUE / 2;

        Racer(UUID id, String name, long joinedAt) {
            this.id = id;
            this.name = name;
            this.joinedAt = joinedAt;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean left() {
            return left;
        }

        public boolean seated() {
            return seated;
        }

        public Leg leg() {
            return leg;
        }

        public int points() {
            return points;
        }

        public boolean ready() {
            return ready;
        }

        /** Targets reached in the race on now. */
        public int reached() {
            return reached;
        }

        /** Whether they finished (counted) at least one race tonight. */
        public boolean finishedAny() {
            return finishedAny;
        }
    }

    /** The track a night races on. {@code stand} {@code null}: one race, finishers go home. */
    public record Track(Course base, String name, List<Course.Spot> grid, Point stand) {

        public Track {
            grid = List.copyOf(grid);
        }
    }

    private final EventPlan plan;
    private final Track track;
    private final NightRules rules;
    private final EventDao dao;
    private final NightPorts ports;
    private final PayLoop pay;
    private final ZoneId zone;
    private final String seasonBoard;
    private final EventMachine.Timing timing;
    private final int laps;
    private final Map<UUID, Racer> racers = new LinkedHashMap<>();
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final Map<UUID, Course.Spot> spots = new HashMap<>();
    private EventMachine.State state;
    private long goTick;
    private long warmupTick;
    private long firstFinishAt = -1;
    private long lastFinishMs = -1;
    private String lastFinisher;
    private int finishedThisRace;
    private int started = -1;
    private boolean prizeNight;
    private boolean released;
    private Consumer<NightRunner> onEnd = r -> {
    };

    /**
     * @param state       where the night is ({@link EventMachine.State#scheduled()}, or OPEN for a new
     *                    admin night or one resumed after a restart)
     * @param seasonBoard the season board ({@code rnseason:2026-10}), or {@code null} with the season off
     */
    public NightRunner(EventPlan plan, Track track, EventDao dao, NightPorts ports, PayLoop pay, ZoneId zone,
                       String seasonBoard, int announceMinutes, EventMachine.State state) {
        this.plan = plan;
        this.track = track;
        this.rules = plan.rules();
        this.dao = dao;
        this.ports = ports;
        this.pay = pay;
        this.zone = zone;
        this.seasonBoard = seasonBoard;
        this.timing = EventMachine.Timing.of(plan, announceMinutes);
        this.laps = RaceTrack.laps(track.base(), rules.laps());
        this.state = state;
    }

    /** Called once when the night is over (DONE or CALLED_OFF), after the racers were sent home. */
    public void onEnd(Consumer<NightRunner> listener) {
        this.onEnd = listener == null ? r -> {
        } : listener;
    }

    // ---- what the screens, the hub and the feed read ---------------------------------------------

    public EventPlan plan() {
        return plan;
    }

    public Track track() {
        return track;
    }

    public EventMachine.Phase phase() {
        return state.phase();
    }

    /** The race on now or next (0 before race 1). */
    public int race() {
        return state.race();
    }

    /** How many laps each race is. */
    public int laps() {
        return laps;
    }

    /** How many targets (checkpoints and the finish) each race has. */
    public int targets() {
        return raced(track.grid().isEmpty() ? null : track.grid().get(0)).targets().size();
    }

    /** Whether it holds a prize slot (known from race 1's Go; before that, whether it will ask for one). */
    public boolean prizeNight() {
        return started >= 0 ? prizeNight : plan.prizesAllowed();
    }

    /** Racers on the list (not left), in join order. */
    public List<Racer> joined() {
        List<Racer> out = new ArrayList<>();
        for (Racer r : racers.values()) {
            if (!r.left) {
                out.add(r);
            }
        }
        return out;
    }

    /** The racer, or {@code null}. */
    public Racer racer(UUID id) {
        return racers.get(id);
    }

    /** Whether the player is on tonight's list. */
    public boolean in(UUID id) {
        Racer r = racers.get(id);
        return r != null && !r.left;
    }

    /** The most racers tonight: the rules' limit and the grid's spots. */
    public int maxRacers() {
        return Math.min(rules.maxRacers(), Math.max(1, track.grid().size()));
    }

    /** The night's standings so far, best first. */
    public List<NightStandings.Ranked> standings() {
        Map<UUID, Integer> points = new LinkedHashMap<>();
        Map<UUID, List<Integer>> places = new HashMap<>();
        for (Racer r : racers.values()) {
            if (r.everSeated || r.points > 0) {
                points.put(r.id, r.points);
                places.put(r.id, r.places);
            }
        }
        return NightStandings.rank(points, places);
    }

    /** The live order of the race on now, first place first. */
    public List<LivePlaces.Row> live() {
        List<LivePlaces.Row> rows = new ArrayList<>();
        for (Racer r : racers.values()) {
            if (!r.everSeated && r.leg == Leg.AWAY) {
                continue;
            }
            LivePlaces.State s = switch (r.leg) {
                case RACING, WAITING -> LivePlaces.State.RACING;
                case FINISHED -> LivePlaces.State.FINISHED;
                default -> LivePlaces.State.OUT;
            };
            rows.add(new LivePlaces.Row(r.id, s, r.reached, r.toNext, s == LivePlaces.State.FINISHED ? r.ms : r.reachedAt));
        }
        return LivePlaces.rank(rows);
    }

    /** The leader's name now (the live race's, else the night's), or {@code null}. */
    public String leader() {
        if (state.phase() == EventMachine.Phase.RACING) {
            List<LivePlaces.Row> live = live();
            if (!live.isEmpty() && live.get(0).state() != LivePlaces.State.OUT) {
                Racer r = racers.get(live.get(0).player());
                return r == null ? null : r.name;
            }
        }
        List<NightStandings.Ranked> s = standings();
        if (s.isEmpty() || s.get(0).points() <= 0) {
            return null;
        }
        Racer r = racers.get(s.get(0).player());
        return r == null ? null : r.name;
    }

    // ---- joining ------------------------------------------------------------------------------

    /** Why the player can't join now, or {@code null} when they can. */
    public String joinProblem(UUID id) {
        EventMachine.Phase p = state.phase();
        Racer r = racers.get(id);
        if (r != null && !r.left) {
            return "You're already in tonight's Race Night.";
        }
        if (r != null && r.left && started >= 0) {
            return "You left tonight's Race Night - see you next time!";
        }
        boolean open = p == EventMachine.Phase.OPEN || p == EventMachine.Phase.WARMUP
                || (p == EventMachine.Phase.GRID && state.race() <= 1 && started < 0);
        if (!open) {
            return p == EventMachine.Phase.SCHEDULED ? "Joining opens at " + EventCopy.clock(plan.joinAt(), zone) + "."
                    : "Race Night has started - watch it, and join the next one!";
        }
        if (joined().size() >= maxRacers()) {
            return "Race Night is full (" + maxRacers() + "). Tap Watch to see it.";
        }
        return null;
    }

    /** The player joins. @return why not, or {@code null} when they are in */
    public String join(UUID id, String name) {
        String why = joinProblem(id);
        if (why != null) {
            return why;
        }
        long now = ports.now();
        try {
            if (!dao.join(plan.id(), id, name, now)) {
                return "Race Night isn't open right now.";
            }
        } catch (SQLException e) {
            ports.log("Race Night: could not add a racer: " + e.getMessage(), true);
            return "Couldn't join right now - try again in a moment.";
        }
        Racer r = racers.get(id);
        if (r == null) {
            r = new Racer(id, name, now);
            racers.put(id, r);
        } else {
            r.left = false;
            r.name = name;
        }
        ports.tell(id, "&aYou're in Race Night! &7It starts at " + EventCopy.clock(plan.startsAt(), zone)
                + ". Keep playing - we'll take you to the track.", false);
        if (state.phase() == EventMachine.Phase.WARMUP) {
            Racer in = r;
            queue.add(() -> seat(in, spotFor(in)));
        }
        ports.changed();
        return null;
    }

    /**
     * The player takes their name off the list (the screen's Leave): before the racing it never
     * happened; once at the track it is leaving for good (home with their things).
     */
    public void leave(UUID id) {
        Racer r = racers.get(id);
        if (r == null || r.left) {
            return;
        }
        if (!r.everSeated && started < 0) {
            racers.remove(id);
            try {
                dao.unjoin(plan.id(), id);
            } catch (SQLException e) {
                ports.log("Race Night: could not take a racer off the list: " + e.getMessage(), true);
            }
            ports.tell(id, "&7You're off the Race Night list.", false);
            ports.changed();
            return;
        }
        leftForGood(r);
        if (r.seated) {
            sendHome(r, EndReason.QUIT_ITEM, "&7You left Race Night. Your things are back.");
        }
    }

    /** Admin {@code go}: close the window now and start in 15 seconds. @return why not, or {@code null} */
    public String goNow() {
        if (state.phase() != EventMachine.Phase.OPEN && state.phase() != EventMachine.Phase.SCHEDULED) {
            return "It has already started.";
        }
        if (joined().size() < rules.minRacers()) {
            return "It needs " + rules.minRacers() + " racers (" + joined().size() + " in).";
        }
        long now = ports.now();
        long at = now + EventMachine.GRID_LEAD_MS;
        try {
            dao.setStart(plan.id(), Math.min(plan.joinAt(), now), at);
        } catch (SQLException e) {
            ports.log("Race Night: could not move the start: " + e.getMessage(), true);
        }
        moved = plan.withStart(at);
        if (state.phase() == EventMachine.Phase.SCHEDULED) {
            openRow();
            state = EventMachine.State.open(now);
        }
        state = new EventMachine.State(state.phase(), state.race(), state.since(), state.goAt(), state.warmupEnds(),
                state.flags() | EventMachine.LAST_CALL_DONE | EventMachine.SOLO_DONE);
        ports.reserve(track.base().id(), this, reservedLine());
        ports.endSoloRuns(track.base().id(), racers.keySet(), "&eRace Night is starting on this track now.");
        return null;
    }

    /**
     * Write the night's row (its window is open). Idempotent: a night already written keeps its row.
     */
    public void openRow() {
        try {
            dao.open(new EventDao.EventRow(plan.id(), track.base().id(), plan.joinAt(), plan.startsAt(),
                    EventDao.OPEN, rules.encode(), 0, false, "", plan.madeBy(), ports.now(), null, ""));
        } catch (SQLException e) {
            ports.log("Race Night: could not write " + plan.id() + ": " + e.getMessage(), true);
        }
    }

    /** The start after an admin's {@code go} (else the plan's). */
    private EventPlan moved;

    private EventMachine.Timing timing() {
        return moved == null ? timing : new EventMachine.Timing(moved.joinAt(), moved.startsAt(), timing.announceMs(),
                timing.races(), timing.minRacers(), timing.warmupMs(), timing.finishWindowMs(), timing.maxRaceMs(),
                timing.breakMs());
    }

    /** The start as it stands (moved by an admin's {@code go}). */
    public long startsAt() {
        return moved == null ? plan.startsAt() : moved.startsAt();
    }

    // ---- the clock ----------------------------------------------------------------------------

    /** Every tick: seat or send home two racers, and every 5th tick step the night. */
    public void tick() {
        for (int i = 0; i < PER_TICK && !queue.isEmpty(); i++) {
            queue.poll().run();
        }
        if (ports.tick() % 5 == 0) {
            step();
        }
        if (ports.tick() % 10 == 0) {
            bars();
        }
    }

    /** Run everything queued now (the tests, and a stop). */
    public void drain() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    /** One step of the machine, and its actions. */
    public void step() {
        if (state.phase().over()) {
            return;
        }
        long now = ports.now();
        EventMachine.Facts f = new EventMachine.Facts(now, joined().size(), seatedCount(), racingCount(), firstFinishAt,
                allReady());
        EventMachine.Step s = EventMachine.step(state, timing(), f);
        EventMachine.Phase before = state.phase();
        state = s.state();
        for (EventMachine.Action a : s.actions()) {
            act(a);
            if (state.phase().over()) {
                break;
            }
        }
        if (before != state.phase()) {
            if (state.phase().running() && !before.running()) {
                try {
                    dao.setState(plan.id(), EventDao.RUNNING, "", null);
                } catch (SQLException e) {
                    ports.log("Race Night: could not mark " + plan.id() + " running: " + e.getMessage(), true);
                }
            }
            ports.log("Race Night " + plan.id() + ": " + before + " -> " + state.phase(), false);
            ports.changed();
        }
    }

    private void act(EventMachine.Action a) {
        switch (a.what()) {
            case HEADS_UP -> ports.announce(Announcer.Line.HEADS_UP,
                    EventCopy.headsUp(startsAt(), plan.joinAt(), track.name(), zone), racers.keySet());
            case OPEN -> {
                openRow();
                ports.announce(Announcer.Line.JOIN_OPEN, EventCopy.joinOpen(startsAt(), track.name(), zone),
                        racers.keySet());
            }
            case LAST_CALL -> {
                ports.announce(Announcer.Line.LAST_CALL, EventCopy.lastCall(joined().size()), racers.keySet());
                if (!ports.reserve(track.base().id(), this, reservedLine())) {
                    ports.log("Race Night: " + track.base().id() + " is held by something else", true);
                }
                ports.warnSoloRuns(track.base().id(), racers.keySet(), "&eRace Night needs this track in 1 minute.");
            }
            case END_SOLO_RUNS -> {
                ports.endSoloRuns(track.base().id(), racers.keySet(),
                        "&7Race Night is on this track now - watch it or join the next one!");
                for (Racer r : joined()) {
                    if (ports.online(r.id) && !ports.free(r.id)) {
                        ports.tell(r.id, "&eRace Night starts in 1 minute &7- finish up or use Leave game.", false);
                    }
                }
            }
            case SEAT -> {
                if (rules.warmupSeconds() > 0) {
                    warmupTick = ports.tick() + rules.warmupSeconds() * 20L;
                } else {
                    goTick = ports.tick() + Math.max(0, (state.goAt() - ports.now()) / 50);
                }
                assignSpots(1);
                for (Racer r : joined()) {
                    inQueue.add(r.id);
                    queue.add(() -> seat(r, spots.get(r.id)));
                }
            }
            case RETRY_SEATS -> {
                for (Racer r : joined()) {
                    if (!r.seated && r.refused != null && !queued(r)) {
                        inQueue.add(r.id);
                        queue.add(() -> seat(r, spotFor(r)));
                    }
                }
            }
            case GRID -> grid(a.race(), a.at());
            case GO -> go(a.race());
            case END_RACE -> endRace(a.race());
            case SETTLE -> settle(a.why(), false);
            case CALL_OFF -> callOff(callOffLine(a.why()), a.why());
        }
    }

    private final java.util.Set<UUID> inQueue = new java.util.HashSet<>();

    private boolean queued(Racer r) {
        return inQueue.contains(r.id);
    }

    // ---- seating ------------------------------------------------------------------------------

    /** Hand out grid spots for race {@code race}: race 1 by season points, later races by tonight's. */
    private void assignSpots(int race) {
        List<UUID> order = new ArrayList<>();
        Map<UUID, Long> points = new HashMap<>();
        for (Racer r : joined()) {
            order.add(r.id);
            points.put(r.id, race <= 1 ? (seasonBoard == null ? 0L : ports.points(r.id, seasonBoard)) : (long) r.points);
        }
        List<UUID> grid = NightStandings.grid(order, points);
        spots.clear();
        for (int i = 0; i < grid.size() && i < track.grid().size(); i++) {
            spots.put(grid.get(i), track.grid().get(i));
        }
    }

    private Course.Spot spotFor(Racer r) {
        Course.Spot s = spots.get(r.id);
        if (s != null) {
            return s;
        }
        for (Course.Spot free : track.grid()) {
            if (!spots.containsValue(free)) {
                spots.put(r.id, free);
                return free;
            }
        }
        return null;
    }

    private void seat(Racer r, Course.Spot spot) {
        inQueue.remove(r.id);
        if (r.left || r.seated || state.phase().over() || spot == null) {
            return;
        }
        if (!ports.online(r.id)) {
            r.refused = "offline";
            return;
        }
        String why = ports.free(r.id) ? ports.seat(r.id, track.base(), raced(spot), spot, track.stand(), this)
                : "busy";
        if (why == null) {
            r.seated = true;
            r.everSeated = true;
            r.refused = null;
            r.ready = false;
            r.leg = state.phase() == EventMachine.Phase.RACING ? Leg.RACING : Leg.WAITING;
            if (state.phase() == EventMachine.Phase.WARMUP) {
                ports.tell(r.id, "&bWarm-up laps &7- not counted. Tap &aReady &7when you're set.", false);
            } else {
                gridTitle(r, spot);
            }
            ports.changed();
            return;
        }
        r.refused = why;
        long now = ports.now();
        if (now - r.naggedAt >= NAG_MS) {
            r.naggedAt = now;
            ports.tell(r.id, "&eRace Night is starting! &7Stand still, or use Leave game, to join.", false);
        }
    }

    private Course raced(Course.Spot spot) {
        return RaceTrack.raced(track.base(), spot, rules.laps());
    }

    private void gridTitle(Racer r, Course.Spot spot) {
        int pos = track.grid().indexOf(spot) + 1;
        ports.title(r.id, "&6Race " + Math.max(1, state.race()) + " of " + rules.races(),
                "&7" + track.name() + " · " + laps + (laps == 1 ? " lap" : " laps")
                        + (pos > 0 ? " · you start " + NightStandings.ordinal(pos) : ""));
    }

    /** Everyone to their spot for race {@code race}; the countdown ends at {@code goAt}. */
    private void grid(int race, long goAt) {
        goTick = ports.tick() + Math.max(0, (goAt - ports.now()) / 50);
        warmupTick = 0;
        firstFinishAt = -1;
        lastFinishMs = -1;
        finishedThisRace = 0;
        assignSpots(race);
        for (Racer r : joined()) {
            r.reached = 0;
            r.toNext = 0;
            r.reachedAt = 0;
            r.ms = 0;
            Course.Spot spot = spots.get(r.id);
            if (spot == null) {
                continue;
            }
            if (r.seated) {
                r.leg = Leg.WAITING;
                queue.add(() -> {
                    if (r.seated && !r.left) {
                        ports.regrid(r.id, raced(spot), spot);
                        gridTitle(r, spot);
                    }
                });
            } else if (ports.online(r.id) && ports.free(r.id)) {
                inQueue.add(r.id);
                queue.add(() -> seat(r, spot)); // back after a disconnect: pulled in for this race
            }
        }
        if (race > 1) {
            ports.watchers("&bRace " + race + " of " + rules.races() + " &7is on the grid.");
        }
    }

    // ---- racing -------------------------------------------------------------------------------

    private void go(int race) {
        if (race <= 1) {
            started = seatedCount();
            prizeNight = plan.prizesAllowed() && claimSlot();
        }
        String sub = race <= 1 ? "&7" + RacePrizes.line(rules.prizes(), rules.finisherPrize(), prizeNight) : "&7Go go go!";
        for (Racer r : joined()) {
            if (r.seated) {
                r.leg = Leg.RACING;
                if (race <= 1) {
                    ports.title(r.id, "&aGo!", sub);
                }
            } else {
                r.leg = Leg.AWAY;
                if (ports.online(r.id)) {
                    ports.tell(r.id, "&7You weren't free when the race began - you'll be in the next one.", false);
                }
            }
        }
        ports.watchers("&bRace " + race + " of " + rules.races() + " &7- go!");
        ports.log("Race Night " + plan.id() + ": race " + race + " go with " + seatedCount() + " racer(s)"
                + (race <= 1 ? (prizeNight ? ", a prize night" : ", just for fun") : ""), false);
    }

    private boolean claimSlot() {
        try {
            return dao.claimPrizeSlot(plan.id(), Long.toString(week), perWeek);
        } catch (SQLException e) {
            ports.log("Race Night: could not claim a prize slot, so tonight is just for fun: " + e.getMessage(), true);
            return false;
        }
    }

    private long week;
    private int perWeek = 3;

    /** The prize-night week key and the week's limit (the runner claims a slot at race 1's Go). */
    public void prizeWeek(long weekKey, int prizeEventsPerWeek) {
        this.week = weekKey;
        this.perWeek = prizeEventsPerWeek;
    }

    @Override
    public boolean alive() {
        return !state.phase().over();
    }

    @Override
    public long goTick() {
        return goTick;
    }

    @Override
    public long warmupUntil() {
        return state.phase() == EventMachine.Phase.WARMUP ? warmupTick : 0L;
    }

    @Override
    public void ready(UUID racer) {
        Racer r = racers.get(racer);
        if (r == null || !r.seated || state.phase() != EventMachine.Phase.WARMUP) {
            return;
        }
        r.ready = true;
        ports.tell(racer, "&aReady! &7Waiting for the others...", false);
        ports.changed();
    }

    @Override
    public void progress(UUID racer, int reachedTargets, double toNext, long nanos) {
        Racer r = racers.get(racer);
        if (r == null || r.leg != Leg.RACING) {
            return;
        }
        if (reachedTargets != r.reached) {
            r.reachedAt = nanos;
        }
        r.reached = reachedTargets;
        r.toNext = toNext;
    }

    @Override
    public void finished(UUID racer, long raceMs, boolean counted, String voidReason) {
        Racer r = racers.get(racer);
        if (r == null || r.leg != Leg.RACING || state.phase() != EventMachine.Phase.RACING) {
            return;
        }
        long now = ports.now();
        if (firstFinishAt < 0) {
            firstFinishAt = now;
        }
        if (!counted) {
            r.leg = Leg.VOID;
            ports.tell(racer, "&cThat race didn't count - " + (voidReason == null ? "a rule was broken" : voidReason)
                    + ". &7You'll race again next time.", false);
        } else {
            r.leg = Leg.FINISHED;
            r.ms = raceMs;
            finishedThisRace++;
            int place = finishedThisRace;
            ports.tell(racer, "&6You came " + NightStandings.ordinal(place) + "! &f" + EventCopy.time(raceMs), false);
            ports.watchers("&e" + NightStandings.ordinal(place) + " &f" + r.name + " &7" + EventCopy.time(raceMs));
            if (lastFinishMs >= 0 && Math.abs(raceMs - lastFinishMs) <= PHOTO_MS && lastFinisher != null) {
                String line = "&ePhoto finish! &f" + lastFinisher + " by " + String.format(java.util.Locale.ROOT, "%.2f",
                        Math.abs(raceMs - lastFinishMs) / 1000.0) + " s";
                ports.tell(racer, line, false);
                ports.watchers(line);
            }
            lastFinishMs = raceMs;
            lastFinisher = r.name;
        }
        afterLine(r);
        ports.changed();
    }

    /** At the line: parked on the stand (or, with no stand, home). */
    private void afterLine(Racer r) {
        if (track.stand() == null) {
            r.seated = false;
            sendHome(r, EndReason.FINISH, "&7Race Night: that was the race. Your things are back.");
            return;
        }
        queue.add(() -> {
            if (r.seated && !r.left) {
                ports.park(r.id);
            }
        });
    }

    @Override
    public void left(UUID racer, EndReason why) {
        Racer r = racers.get(racer);
        if (r == null) {
            return;
        }
        boolean wasSeated = r.seated;
        r.seated = false;
        r.ready = false;
        if (r.sentHome) {
            return; // we sent them home: nothing more to do
        }
        if (why == EndReason.QUIT_ITEM || why == EndReason.COMMAND) {
            leftForGood(r);
        } else if (r.leg == Leg.RACING || r.leg == Leg.WAITING) {
            r.leg = Leg.OUT;
        }
        if (wasSeated) {
            ports.changed();
        }
    }

    private void leftForGood(Racer r) {
        r.left = true;
        if (r.leg == Leg.RACING || r.leg == Leg.WAITING) {
            r.leg = Leg.OUT;
        }
        try {
            dao.setStatus(plan.id(), r.id, EventDao.LEFT);
        } catch (SQLException e) {
            ports.log("Race Night: could not mark a racer as left: " + e.getMessage(), true);
        }
        ports.tell(r.id, "&7You left Race Night. Points you won so far still count.", true);
        ports.changed();
    }

    private void endRace(int race) {
        List<NightStandings.Row> rows = new ArrayList<>();
        for (Racer r : racers.values()) {
            if (!r.everSeated && r.leg == Leg.AWAY && r.left) {
                continue;
            }
            NightStandings.Result result = switch (r.leg) {
                case FINISHED -> NightStandings.Result.FINISHED;
                case VOID -> NightStandings.Result.VOID;
                case RACING, WAITING -> r.seated ? NightStandings.Result.STILL_RACING : NightStandings.Result.LEFT;
                case OUT, STILL -> NightStandings.Result.LEFT;
                case AWAY -> NightStandings.Result.DNS;
            };
            if (result == NightStandings.Result.STILL_RACING) {
                r.leg = Leg.STILL;
                ports.tell(r.id, "&7Race over - you still get a point. Great racing!", false);
                queue.add(() -> {
                    if (r.seated && !r.left) {
                        ports.park(r.id);
                    }
                });
            }
            rows.add(new NightStandings.Row(r.id, result, r.ms, r.reached));
        }
        List<NightStandings.Scored> scored = NightStandings.score(rows, rules.points(), rules.finishPoints(),
                rules.stillRacingPoints());
        List<EventDao.RaceRow> stored = new ArrayList<>();
        for (NightStandings.Scored s : scored) {
            stored.add(new EventDao.RaceRow(plan.id(), race, s.player(), s.place(),
                    s.result() == NightStandings.Result.FINISHED || s.result() == NightStandings.Result.VOID ? s.ms() : null,
                    s.targets(), s.points(), s.result().name()));
        }
        try {
            dao.storeRace(plan.id(), race, stored, seasonBoard, ports.now());
        } catch (SQLException e) {
            ports.log("Race Night: could not store race " + race + " of " + plan.id() + " - its points are lost: "
                    + e.getMessage(), true);
            return;
        }
        for (NightStandings.Scored s : scored) {
            Racer r = racers.get(s.player());
            if (r == null) {
                continue;
            }
            r.points += s.points();
            if (s.place() != null) {
                r.places.add(s.place());
                r.finishedAny = true;
            }
        }
        racesDone = race;
        if (race < rules.races()) {
            String line = standingsLine();
            for (Racer r : joined()) {
                if (ports.online(r.id)) {
                    ports.tell(r.id, "&6After race " + race + ": " + line, false);
                }
            }
            ports.watchers("&6After race " + race + ": " + line);
        }
        ports.changed();
    }

    private int racesDone;

    /** "&amp;61. Sam 18 &amp;7· &amp;62. Ava 16 ..." (at most 8). */
    public String standingsLine() {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (NightStandings.Ranked s : standings()) {
            if (n++ >= 8) {
                break;
            }
            Racer r = racers.get(s.player());
            if (b.length() > 0) {
                b.append(" &7· ");
            }
            b.append("&6").append(s.place()).append(". &f").append(r == null ? "a racer" : r.name).append(" &7")
                    .append(s.points());
        }
        return b.length() == 0 ? "&7no points yet" : b.toString();
    }

    // ---- the end ------------------------------------------------------------------------------

    /**
     * Settle on the races stored: places, prizes and the night's board in one transaction, then pay
     * (owed when a racer can't be paid now), then everyone home. {@code calledOff}: it ends as
     * CALLED_OFF, else DONE.
     */
    private void settle(String why, boolean calledOff) {
        List<NightStandings.Ranked> ranked = standings();
        List<UUID> finishers = new ArrayList<>();
        for (Racer r : racers.values()) {
            if (r.finishedAny) {
                finishers.add(r.id);
            }
        }
        Map<UUID, RacePrizes.Prize> prizes = RacePrizes.plan(ranked, Math.max(0, started), finishers, rules.prizes(),
                rules.finisherPrize(), prizeNight && racesDone >= 1);
        List<EventDao.Placed> placed = new ArrayList<>();
        for (NightStandings.Ranked s : ranked) {
            RacePrizes.Prize p = prizes.get(s.player());
            placed.add(new EventDao.Placed(s.player(), s.place(), s.points(), p == null ? 0 : p.tokens()));
        }
        long now = ports.now();
        try {
            dao.settle(plan.id(), placed, EventCopy.nightBoard(plan.id()), now);
            if (calledOff) {
                dao.setState(plan.id(), EventDao.SETTLING, "called off: " + (why == null ? "" : why), null);
            }
        } catch (SQLException e) {
            ports.log("Race Night: could not settle " + plan.id() + ": " + e.getMessage(), true);
        }
        int owed = pay.payNight(plan.id());
        for (Map.Entry<UUID, RacePrizes.Prize> e : prizes.entrySet()) {
            if (!ports.online(e.getKey()) && owed > 0) {
                ports.tell(e.getKey(), PayLoop.WAITING, true);
            }
        }
        String results = resultsLine(ranked);
        if (!ranked.isEmpty() && racesDone >= 1) {
            for (Racer r : racers.values()) {
                ports.tell(r.id, results, true);
            }
            ports.watchers(results);
            ports.announce(Announcer.Line.RESULTS, results, racers.keySet());
            for (NightStandings.Ranked s : ranked) {
                Racer r = racers.get(s.player());
                if (r != null && r.everSeated) {
                    ports.progress(r.id, s.place() == 1 && s.points() > 0);
                }
            }
        }
        StringBuilder log = new StringBuilder("Race Night " + plan.id() + " results:");
        for (NightStandings.Ranked s : ranked) {
            RacePrizes.Prize p = prizes.get(s.player());
            Racer r = racers.get(s.player());
            log.append(' ').append(s.place()).append(". ").append(r == null ? s.player() : r.name).append(' ')
                    .append(s.points()).append("pt").append(p == null ? "" : " +" + p.tokens());
        }
        ports.log(log.toString() + (prizeNight ? "" : " (just for fun)"), false);
        finish(calledOff ? EventDao.CALLED_OFF : EventDao.DONE, why, calledOff ? EventMachine.Phase.CALLED_OFF
                : EventMachine.Phase.DONE, "&7Race Night is over - great racing! Your things are back.");
    }

    /** "&amp;6Race Night winner: Sam! &amp;72nd Ava, 3rd Lee." */
    String resultsLine(List<NightStandings.Ranked> ranked) {
        if (ranked.isEmpty() || ranked.get(0).points() <= 0) {
            return "&7Race Night is over - nobody scored tonight.";
        }
        List<String> winners = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        for (NightStandings.Ranked s : ranked) {
            Racer r = racers.get(s.player());
            String name = r == null ? "a racer" : r.name;
            if (s.place() == 1) {
                winners.add(name);
            } else if (s.place() <= 3) {
                rest.append(rest.length() == 0 ? " &7" : ", ").append(NightStandings.ordinal(s.place())).append(' ')
                        .append(name);
            }
        }
        return "&6Race Night winner" + (winners.size() > 1 ? "s" : "") + ": " + String.join(" and ", winners) + "!"
                + rest + (rest.length() > 0 ? "." : "");
    }

    /**
     * Call the night off now ({@code line} to the racers): racers go home with their things; a
     * night with races stored settles on them.
     */
    public void callOff(String line, String why) {
        if (state.phase().over()) {
            return;
        }
        ports.log("Race Night " + plan.id() + " called off: " + why, false);
        if (racesDone >= 1) {
            for (Racer r : racers.values()) {
                ports.tell(r.id, line, true);
            }
            settle(why, true);
            return;
        }
        for (Racer r : racers.values()) {
            if (!r.left) {
                ports.tell(r.id, line, true);
            }
        }
        finish(EventDao.CALLED_OFF, why, EventMachine.Phase.CALLED_OFF, null);
    }

    /** A {@link #callOff} that sends everyone home at once (the game is stopping: no ticks follow). */
    public void stopNow(String line, String why) {
        callOff(line, why);
        drain();
    }

    private void finish(String stored, String why, EventMachine.Phase end, String homeLine) {
        long now = ports.now();
        state = state.ended(end, now);
        try {
            dao.setState(plan.id(), stored, why == null ? "" : (end == EventMachine.Phase.CALLED_OFF ? "called off: " : "")
                    + why, now);
        } catch (SQLException e) {
            ports.log("Race Night: could not close " + plan.id() + ": " + e.getMessage(), true);
        }
        for (Racer r : racers.values()) {
            if (r.seated) {
                sendHome(r, EndReason.FINISH, homeLine == null ? calledOffLine() : homeLine);
            }
            ports.bar(r.id, null, 0, false);
        }
        release();
        ports.changed();
        onEnd.accept(this);
    }

    private void release() {
        if (!released) {
            released = true;
            ports.release(track.base().id(), this);
        }
    }

    private void sendHome(Racer r, EndReason why, String line) {
        r.sentHome = true;
        r.seated = false;
        queue.add(() -> ports.home(r.id, why, line));
    }

    private String callOffLine(String why) {
        if (why != null && why.startsWith("too few")) {
            return "&7Race Night needs " + rules.minRacers() + " racers - see you next time!";
        }
        return calledOffLine();
    }

    private String reservedLine() {
        return "&7Race Night is on this track - watch or join!";
    }

    // ---- the bossbars ---------------------------------------------------------------------------

    private void bars() {
        EventMachine.Phase p = state.phase();
        if (p == EventMachine.Phase.RACING) {
            List<LivePlaces.Row> live = live();
            int of = 0;
            for (LivePlaces.Row row : live) {
                if (row.state() != LivePlaces.State.OUT) {
                    of++;
                }
            }
            int targets = Math.max(1, raced(track.grid().isEmpty() ? null : track.grid().get(0)).targets().size());
            for (int i = 0; i < live.size(); i++) {
                LivePlaces.Row row = live.get(i);
                Racer r = racers.get(row.player());
                if (r == null || !r.seated) {
                    continue;
                }
                int lap = LivePlaces.lap(row.reached(), targets, laps);
                float progress = Math.max(0f, Math.min(1f, row.reached() / (float) targets));
                ports.bar(r.id, LivePlaces.bar(i + 1, Math.max(of, i + 1), lap, laps), progress, laps > 1 && lap >= laps);
            }
        } else if (p == EventMachine.Phase.WARMUP) {
            long left = Math.max(0, state.warmupEnds() - ports.now());
            for (Racer r : joined()) {
                if (r.seated) {
                    ports.bar(r.id, "&bWarm-up &f" + EventCopy.countdown(left) + " &7left - not counted"
                            + (r.ready ? " &a(ready)" : ""), left / (float) Math.max(1, rules.warmupSeconds() * 1000L),
                            false);
                }
            }
        } else if (p == EventMachine.Phase.BREAK) {
            for (Racer r : joined()) {
                if (r.seated) {
                    ports.bar(r.id, "&7Break &8· &7race " + (state.race() + 1) + " of " + rules.races() + " next",
                            1f, false);
                }
            }
        }
    }

    // ---- counts -------------------------------------------------------------------------------

    private int seatedCount() {
        int n = 0;
        for (Racer r : racers.values()) {
            if (r.seated && !r.left) {
                n++;
            }
        }
        return n;
    }

    private int racingCount() {
        int n = 0;
        for (Racer r : racers.values()) {
            if (r.seated && !r.left && r.leg == Leg.RACING) {
                n++;
            }
        }
        return n;
    }

    private boolean allReady() {
        boolean any = false;
        for (Racer r : racers.values()) {
            if (r.seated && !r.left) {
                any = true;
                if (!r.ready) {
                    return false;
                }
            }
        }
        return any;
    }

    /** Racers seated at race 1's Go (-1 before it). */
    public int started() {
        return started;
    }

    /** Races stored so far. */
    public int racesDone() {
        return racesDone;
    }

    /** The machine's state (for status and the tests). */
    public EventMachine.State state() {
        return state;
    }

    /**
     * Load a night's entries from its rows (a resumed OPEN night keeps its sign-ups). Names come
     * from the rows.
     */
    public void restore(List<EventDao.EntryRow> rows) {
        for (EventDao.EntryRow e : rows) {
            Racer r = new Racer(e.player(), e.name(), e.joinedAt());
            r.left = EventDao.LEFT.equals(e.status());
            racers.put(e.player(), r);
        }
    }

    /** Every racer ever on tonight's list (ids), for status and the tests. */
    public Set<UUID> ids() {
        return racers.keySet();
    }
}
