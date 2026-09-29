package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.EndReason;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One party race (owner decision D4: "so it's not always lonely"): friends racing any time-trial
 * course together, run by race mode like a Race Night race, but free and just for fun. Pure: the
 * clock is a tick supplier and what it says goes to a callback, so every rule is tested without a
 * server; {@link PartyRaces} seats the racers and shows the results.
 *
 * <p><b>Its life.</b> {@link State#SEATING} while the racers are taken in (two a tick), then the
 * optional shared warm-up ({@link State#WARMUP}: free laps from the course's start, "Ready" to be
 * done early), then everyone to the grid and one go tick for all ({@link State#GRID}), then
 * {@link State#RACING} until everyone is in (finished, left or voided), or
 * {@value #FINISH_WINDOW_SECONDS} seconds after the first finisher, or
 * {@value #MAX_RACE_MINUTES} minutes after Go; then {@link State#DONE} and the group's results.
 *
 * <p><b>Fair and free.</b> Everyone starts on the same tick with the same clock (race mode's shared
 * Go). Positions are {@link RaceStandings}'. Each racer's finish is ALSO a normal counted run on the
 * course ({@link #normalRun()}): the course's boards, its normal rewards and a Weekly Cup time,
 * exactly once, under every fair-play rule. There are no party prizes and no fees: nothing here
 * touches tokens. Anyone can leave (or drop out) at any time; that is a DNF, and the others carry on.
 */
public final class PartyRace implements RaceLink {

    /** Where the race is. */
    enum State {
        SEATING,
        WARMUP,
        GRID,
        RACING,
        DONE
    }

    /** How a racer's race ended, for the group's results. */
    public enum Result {
        FINISHED,
        STILL_RACING,
        NOT_COUNTED,
        LEFT,
        NOT_STARTED
    }

    /** What the coordinator must do after a {@link #tick}. */
    enum Step {
        NONE,
        /** The warm-up is over: send every racer still in to their grid spot. */
        TO_GRID,
        /** The race is over: everyone home, then the results. */
        END
    }

    /** After the grid call, this long before the 3-2-1 begins (the teleports land). */
    static final long GRID_SETTLE = 40;
    /** From the grid to Go: 5 seconds, the last 3 counted down. */
    static final long GO_DELAY = 100;
    /** The race ends this long after the first finisher. */
    static final int FINISH_WINDOW_SECONDS = 120;
    /** ...or this long after Go. */
    static final int MAX_RACE_MINUTES = 10;

    /** A racer, in grid order (pole first). */
    record Racer(UUID id, String name) {
    }

    /**
     * One line of the group's results.
     *
     * @param rank   the finishing place, 1 up, or 0 for someone who didn't finish
     * @param ms     the race time, or -1
     */
    public record Line(int rank, UUID id, String name, long ms, Result result) {
    }

    private static final class Entry {
        final UUID id;
        final String name;
        final int order;
        Course.Spot grid;
        boolean seated;
        boolean ready;
        RaceStandings.State state = RaceStandings.State.RACING;
        Result result;
        int reached;
        /** Unknown until the first report: behind anyone who reported on the same target count. */
        double toNext = Double.MAX_VALUE;
        long at;
        long ms = -1;

        Entry(UUID id, String name, int order) {
            this.id = id;
            this.name = name;
            this.order = order;
        }
    }

    private final long lobbyId;
    private final Course base;
    private final int perLap;
    private final int laps;
    private final LongSupplier ticks;
    private final Consumer<String> say;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final int warmupSeconds;
    private State state = State.SEATING;
    private long goTick = Long.MAX_VALUE;
    private long warmupUntil;
    private long firstFinish = -1;

    /**
     * A race on {@code base} for {@code racers} (grid order), with a shared warm-up of
     * {@code warmupSeconds} (0: straight to the grid).
     *
     * @param ticks the server tick
     * @param say   what every racer reads (the finishes, who left, who is ready)
     */
    PartyRace(long lobbyId, Course base, List<Racer> racers, int warmupSeconds, LongSupplier ticks,
              Consumer<String> say) {
        this.lobbyId = lobbyId;
        this.base = base;
        this.perLap = Laps.period(base.checkpoints());
        this.laps = Laps.natural(base);
        this.ticks = ticks;
        this.say = say == null ? line -> {
        } : say;
        int i = 0;
        for (Racer r : racers) {
            entries.putIfAbsent(r.id(), new Entry(r.id(), r.name(), i++));
        }
        this.warmupSeconds = Math.max(0, warmupSeconds);
        if (this.warmupSeconds > 0) {
            // counted from after the seating, so the last racer in gets it all too
            warmupUntil = ticks.getAsLong() + seatingTicks(entries.size()) + this.warmupSeconds * 20L;
        }
    }

    /** How long seating {@code n} racers two a tick takes, with a little room. */
    static long seatingTicks(int n) {
        return (n + 1) / 2 + 2;
    }

    long lobbyId() {
        return lobbyId;
    }

    Course base() {
        return base;
    }

    State state() {
        return state;
    }

    /** Whether it has a shared warm-up. */
    boolean warmsUp() {
        return warmupSeconds > 0;
    }

    /** Every racer, in grid order. */
    List<UUID> racers() {
        return new ArrayList<>(entries.keySet());
    }

    /** Whether {@code id} is in this race and still racing (not finished, left or out). */
    boolean racing(UUID id) {
        Entry e = entries.get(id);
        return e != null && e.state == RaceStandings.State.RACING && e.result == null;
    }

    /** The racer's grid spot, or {@code null}. */
    Course.Spot grid(UUID id) {
        Entry e = entries.get(id);
        return e == null ? null : e.grid;
    }

    // ---- seating ----------------------------------------------------------------------------------

    /** The racer is in, with their grid spot. */
    void seated(UUID id, Course.Spot spot) {
        Entry e = entries.get(id);
        if (e != null && e.result == null) {
            e.seated = true;
            e.grid = spot;
        }
    }

    /** The racer couldn't be seated (busy, not standing still): they sit this race out. */
    void notSeated(UUID id) {
        Entry e = entries.get(id);
        if (e != null && e.result == null) {
            e.state = RaceStandings.State.OUT;
            e.result = Result.NOT_STARTED;
        }
    }

    /**
     * Everyone was asked in. With fewer than 2 seated the race is off ({@link State#DONE}, false);
     * otherwise the warm-up starts, or everyone is on the grid with Go {@value #GO_DELAY} ticks away.
     */
    boolean seatingDone() {
        if (state != State.SEATING) {
            return state != State.DONE;
        }
        if (in() < PartyLobby.MIN_PLAYERS) {
            end();
            return false;
        }
        long now = ticks.getAsLong();
        if (warmupSeconds > 0 && warmupUntil > now) {
            state = State.WARMUP;
        } else {
            state = State.GRID;
            goTick = now + GO_DELAY;
        }
        return true;
    }

    // ---- the clock --------------------------------------------------------------------------------

    /** Move the race on at server tick {@code now}: what the coordinator must do. */
    Step tick(long now) {
        return tick(now, false);
    }

    /**
     * {@link #tick(long)}, knowing whether a restart is due soon ({@code restartHeld}, the restart
     * hold): the shared warm-up then ends at once and everyone goes to the grid, so the race itself
     * isn't eaten by free laps.
     */
    Step tick(long now, boolean restartHeld) {
        switch (state) {
            case WARMUP -> {
                if (in() == 0) {
                    end();
                    return Step.END;
                }
                if (now >= warmupUntil || allReady() || restartHeld) {
                    state = State.GRID;
                    goTick = now + GRID_SETTLE + GO_DELAY;
                    return Step.TO_GRID;
                }
            }
            case GRID -> {
                if (in() == 0) {
                    end();
                    return Step.END;
                }
                if (now >= goTick) {
                    state = State.RACING;
                }
            }
            case RACING -> {
                boolean window = firstFinish >= 0 && now >= firstFinish + FINISH_WINDOW_SECONDS * 20L;
                if (in() == 0 || window || now >= goTick + MAX_RACE_MINUTES * 60L * 20L) {
                    end();
                    return Step.END;
                }
            }
            default -> {
                // seating is driven by the coordinator; a finished race does nothing
            }
        }
        return Step.NONE;
    }

    /** Call the race off now (the course closed, the coordinator stopping): {@link State#DONE}. */
    void end() {
        state = State.DONE;
        for (Entry e : entries.values()) {
            if (e.result == null) {
                e.result = e.seated ? Result.STILL_RACING : Result.NOT_STARTED;
            }
        }
    }

    /** How many racers are still in the race (seated or being seated, not finished, left or out). */
    int in() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.state == RaceStandings.State.RACING && e.result == null) {
                n++;
            }
        }
        return n;
    }

    private boolean allReady() {
        boolean any = false;
        for (Entry e : entries.values()) {
            if (e.state == RaceStandings.State.RACING && e.result == null && e.seated) {
                any = true;
                if (!e.ready) {
                    return false;
                }
            }
        }
        return any;
    }

    // ---- RaceLink: what race mode tells it ------------------------------------------------------------

    @Override
    public boolean alive() {
        return state != State.DONE;
    }

    @Override
    public long goTick() {
        return state == State.GRID || state == State.RACING ? goTick : Long.MAX_VALUE;
    }

    @Override
    public long warmupUntil() {
        return state == State.SEATING || state == State.WARMUP ? warmupUntil : 0L;
    }

    @Override
    public boolean normalRun() {
        return true; // each racer's run is also a normal counted run on the course, once
    }

    @Override
    public void progress(UUID racer, int reachedTargets, double toNext, long nanos) {
        Entry e = entries.get(racer);
        if (e != null && e.state == RaceStandings.State.RACING && e.result == null) {
            e.reached = reachedTargets;
            e.toNext = toNext;
            e.at = nanos;
        }
    }

    @Override
    public void finished(UUID racer, long raceMs, boolean counted, String voidReason) {
        Entry e = entries.get(racer);
        if (e == null || e.state != RaceStandings.State.RACING || e.result != null) {
            return;
        }
        e.ms = raceMs;
        if (!counted) {
            e.state = RaceStandings.State.OUT;
            e.result = Result.NOT_COUNTED;
            say.accept("&7" + e.name + " crossed the line, but that race didn't count.");
            return;
        }
        e.state = RaceStandings.State.FINISHED;
        e.result = Result.FINISHED;
        e.at = raceMs;
        e.reached = base.targets().size();
        if (firstFinish < 0) {
            firstFinish = ticks.getAsLong();
        }
        int place = finishers();
        say.accept("&6" + e.name + " came " + RaceStandings.ordinal(place) + "! &f" + TrialText.time(raceMs));
        Entry ahead = place > 1 ? finisher(place - 1) : null;
        if (ahead != null && raceMs - ahead.ms <= PHOTO_FINISH_MS) {
            say.accept(photoFinish(ahead.name, raceMs - ahead.ms));
        }
    }

    /** Two finishes this close (ms) are a photo finish. */
    static final long PHOTO_FINISH_MS = 200;

    /** "&amp;ePhoto finish! &amp;fSam by 0.12 s". */
    static String photoFinish(String winner, long byMs) {
        return "&ePhoto finish! &f" + winner + " by " + String.format(java.util.Locale.ROOT, "%.2f", byMs / 1000.0)
                + " s";
    }

    /** The finisher in {@code place} (1 up, by finish time), or {@code null}. */
    private Entry finisher(int place) {
        List<Entry> in = new ArrayList<>();
        for (Entry o : entries.values()) {
            if (o.result == Result.FINISHED) {
                in.add(o);
            }
        }
        in.sort(Comparator.comparingLong((Entry o) -> o.ms).thenComparingInt(o -> o.order));
        return place >= 1 && place <= in.size() ? in.get(place - 1) : null;
    }

    @Override
    public void left(UUID racer, EndReason why) {
        Entry e = entries.get(racer);
        if (e == null || e.state != RaceStandings.State.RACING || e.result != null) {
            return;
        }
        e.state = RaceStandings.State.OUT;
        e.result = Result.LEFT;
        if (state != State.DONE) {
            say.accept("&7" + e.name + " left the race.");
        }
    }

    @Override
    public void ready(UUID racer) {
        Entry e = entries.get(racer);
        if (e == null || e.ready || (state != State.WARMUP && state != State.SEATING)) {
            return;
        }
        e.ready = true;
        int ready = 0;
        for (Entry o : entries.values()) {
            if (o.ready && o.state == RaceStandings.State.RACING && o.result == null) {
                ready++;
            }
        }
        say.accept("&a" + e.name + " is ready. &7(" + ready + " of " + in() + ")");
    }

    @Override
    public String calledOffLine() {
        return "&7The party race is over.";
    }

    /** WP-CH: its racers go to the Clubhouse when done with the race (set at the start). */
    private boolean clubhouse;

    /** WP-CH: racers done with this race go to the Clubhouse instead of home. */
    void clubhouse(boolean on) {
        this.clubhouse = on;
    }

    @Override
    public boolean clubhouseAfter() {
        return clubhouse;
    }

    // ---- positions and results --------------------------------------------------------------------

    private int finishers() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.result == Result.FINISHED) {
                n++;
            }
        }
        return n;
    }

    /** The live standings of everyone who started. */
    List<RaceStandings.Place> standings() {
        List<RaceStandings.Row> rows = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.result == Result.NOT_STARTED) {
                continue;
            }
            rows.add(new RaceStandings.Row(e.id, e.state, e.reached, e.toNext, e.at, e.order));
        }
        return RaceStandings.rank(rows);
    }

    /** How many started (for "2nd of 5"). */
    int started() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.result != Result.NOT_STARTED) {
                n++;
            }
        }
        return n;
    }

    /**
     * What the racer's bar says: "&amp;e2nd &amp;7of 5 · Lap 1/2" (no lap on a one-lap course), or
     * {@code null} when they aren't racing or finished (left, didn't count, sat it out, or it's over).
     */
    String bar(UUID racer) {
        Entry e = entries.get(racer);
        if (e == null || state == State.DONE || (e.result != null && e.result != Result.FINISHED)) {
            return null;
        }
        int place = RaceStandings.placeOf(standings(), racer);
        String lap = laps > 1 ? " &7· Lap " + Laps.lapOf(e.reached, perLap, laps) + "/" + laps : "";
        return "&e" + RaceStandings.ordinal(place) + " &7of " + started() + lap;
    }

    /** The lap the racer is on (1 up; 1 on a one-lap course). */
    int lap(UUID racer) {
        Entry e = entries.get(racer);
        return e == null ? 1 : Laps.lapOf(e.reached, perLap, laps);
    }

    /** How many laps the race is. */
    int laps() {
        return laps;
    }

    /** The share of the course the racer has done (the bar's fill), 0 to 1. */
    float share(UUID racer) {
        Entry e = entries.get(racer);
        int targets = Math.max(1, base.targets().size());
        return e == null ? 0f : Math.max(0f, Math.min(1f, (float) e.reached / targets));
    }

    /**
     * The group's results: finishers by time (their place), then those still racing when it ended
     * (furthest first), then the races that didn't count, then who left, then who sat it out.
     */
    List<Line> results() {
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparingInt((Entry e) -> rankGroup(e.result))
                .thenComparingLong(e -> e.result == Result.FINISHED ? e.ms : 0)
                .thenComparing((a, b) -> Integer.compare(b.reached, a.reached))
                .thenComparingDouble(e -> e.toNext)
                .thenComparingInt(e -> e.order));
        List<Line> out = new ArrayList<>(all.size());
        int place = 0;
        for (Entry e : all) {
            Result r = e.result == null ? (e.seated ? Result.STILL_RACING : Result.NOT_STARTED) : e.result;
            out.add(new Line(r == Result.FINISHED ? ++place : 0, e.id, e.name, r == Result.FINISHED ? e.ms : -1, r));
        }
        return out;
    }

    private static int rankGroup(Result r) {
        if (r == null) {
            return 1;
        }
        return switch (r) {
            case FINISHED -> 0;
            case STILL_RACING -> 1;
            case NOT_COUNTED -> 2;
            case LEFT -> 3;
            case NOT_STARTED -> 4;
        };
    }

    /** One results line for chat: "&amp;e1st &amp;fSam &amp;70:41.2", "&amp;fLee &amp;7- still racing". */
    public static String chatLine(Line l) {
        return switch (l.result()) {
            case FINISHED -> "&e" + RaceStandings.ordinal(l.rank()) + " &f" + l.name() + " &7" + TrialText.time(l.ms());
            case STILL_RACING -> "&f" + l.name() + " &7- still racing";
            case NOT_COUNTED -> "&f" + l.name() + " &7- didn't count";
            case LEFT -> "&f" + l.name() + " &7- left";
            case NOT_STARTED -> "&f" + l.name() + " &7- sat this one out";
        };
    }
}
