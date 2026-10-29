package com.dierks.homecraft.games.clubhouse;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The Clubhouse boards' cache (0.38.3; the owner's rule: every database read off the main thread, and cached).
 *
 * <p><b>When.</b> Every {@value #EVERY_MS} ms while anyone is in the Clubhouse, and sooner after an arrival or a
 * finish ({@link #soon}, {@link #want}): no sooner than {@value #SOON_MS} ms after the last read began. Nobody in,
 * no reads. Never per tick: the boards draw from what is cached here.
 *
 * <p><b>How.</b> One read at a time. Its snapshot is made on the main thread ({@link Source#snapshot}); the
 * database is read on the async executor ({@link Source#read}: the DAOs only, no server, no config); what was read
 * comes back to the main executor, where the names are resolved and the cache replaced ({@link Source#result}).
 * A read that comes back after {@link #stop} or {@link #clear} is dropped (its generation is old). One that fails
 * keeps the old cache and is one WARN a minute at most: a board is decoration.
 *
 * <p>Every field is touched on the main thread only: the async side sees the snapshot it was handed, and hands
 * back what it read.
 */
final class ClubReads {

    /** How often the boards are read while anyone is in the Clubhouse (ms). */
    static final long EVERY_MS = 30_000L;
    /** How soon after the last read an arrival or a finish may ask for another (ms). */
    static final long SOON_MS = 5_000L;
    /** At most one WARN this often (ms). */
    static final long WARN_EVERY_MS = 60_000L;

    /** The server's side: what to read, the reading, and what it shows. */
    interface Source {
        /** On the main thread: what to read, from memory ({@code viewers}: whose own stats). */
        ClubData.Snapshot snapshot(Set<UUID> viewers);

        /** On the async executor: the database only. */
        ClubData.Raw read(ClubData.Snapshot snapshot) throws Exception;

        /** On the main thread: what the boards show (names resolved). */
        Result result(ClubData.Snapshot snapshot, ClubData.Raw raw);
    }

    /** What the boards show: this week's best, and each viewer's own numbers. */
    record Result(FameBoard.Fame fame, Map<UUID, StatsCard.Mine> mine) {

        Result {
            mine = mine == null ? Map.of() : Map.copyOf(mine);
        }
    }

    private final Source source;
    private final Executor async;
    private final Executor main;
    private final Consumer<String> warn;

    private FameBoard.Fame fame;
    private final Map<UUID, StatsCard.Mine> mine = new HashMap<>();
    private final Set<UUID> wanted = new LinkedHashSet<>();
    private long started = Long.MIN_VALUE / 2;
    private boolean soon;
    private boolean inFlight;
    private int generation;
    private long warnedAt = Long.MIN_VALUE / 2;
    private int reads;

    /**
     * @param source the server's side
     * @param async  where the database is read (live: the scheduler's async pool)
     * @param main   where the result is applied (live: the next tick, while the plugin is enabled)
     * @param warn   a WARN line (at most one a minute)
     */
    ClubReads(Source source, Executor async, Executor main, Consumer<String> warn) {
        this.source = source;
        this.async = async;
        this.main = main;
        this.warn = warn == null ? line -> { } : warn;
    }

    /**
     * Once a second, on the main thread: start a read when one is due and {@code inside} (the Clubhouse's
     * visitors, whose own stats it reads) isn't empty.
     */
    void second(long now, Collection<UUID> inside) {
        if (inside == null || inside.isEmpty() || inFlight) {
            return;
        }
        long since = now - started;
        if (since < EVERY_MS && !(soon && since >= SOON_MS)) {
            return;
        }
        Set<UUID> viewers = new LinkedHashSet<>(inside);
        viewers.addAll(wanted);
        ClubData.Snapshot snap;
        try {
            snap = source.snapshot(viewers);
        } catch (RuntimeException e) {
            warn(now, "Clubhouse boards: could not make what to read: " + e);
            started = now; // try again at the next interval
            soon = false;
            return;
        }
        started = now;
        soon = false;
        wanted.clear();
        inFlight = true;
        reads++;
        int gen = generation;
        try {
            async.execute(() -> {
                ClubData.Raw raw;
                try {
                    raw = source.read(snap);
                } catch (Exception | LinkageError e) {
                    main.execute(() -> failed(gen, now, e));
                    return;
                }
                main.execute(() -> apply(gen, now, snap, raw));
            });
        } catch (RuntimeException e) { // the plugin is stopping: no new task
            inFlight = false;
            warn(now, "Clubhouse boards: could not start a read: " + e);
        }
    }

    /** On the main thread: what was read, unless it is from before a stop or a clear. */
    private void apply(int gen, long now, ClubData.Snapshot snap, ClubData.Raw raw) {
        if (gen != generation) {
            return; // stopped or cleared meanwhile: dropped
        }
        inFlight = false;
        Result r;
        try {
            r = source.result(snap, raw);
        } catch (RuntimeException e) {
            warn(now, "Clubhouse boards: could not show what was read: " + e);
            return;
        }
        fame = r.fame();
        mine.putAll(r.mine());
    }

    private void failed(int gen, long now, Throwable e) {
        if (gen != generation) {
            return;
        }
        inFlight = false;
        warn(now, "Clubhouse boards: could not read the boards (the last ones stay up): " + e);
    }

    private void warn(long now, String line) {
        if (now - warnedAt >= WARN_EVERY_MS) {
            warnedAt = now;
            warn.accept(line);
        }
    }

    /** An arrival or a finish: a read soon ({@value #SOON_MS} ms after the last at the earliest). */
    void soon() {
        soon = true;
    }

    /** {@code viewer}'s own stats in the next read, soon (they arrived, or tapped My stats). */
    void want(UUID viewer) {
        if (viewer != null) {
            wanted.add(viewer);
        }
        soon();
    }

    /** {@code viewer} left the Clubhouse: their numbers go. */
    void forget(UUID viewer) {
        mine.remove(viewer);
        wanted.remove(viewer);
    }

    /** Everything goes (the room closed or moved): a read on its way is dropped, the next one comes at once. */
    void clear() {
        generation++;
        inFlight = false;
        fame = null;
        mine.clear();
        wanted.clear();
        started = Long.MIN_VALUE / 2;
        soon = false;
    }

    /** The Clubhouse stopped: as {@link #clear}. */
    void stop() {
        clear();
    }

    /** This week's best as last read, or {@code null} before the first read. */
    FameBoard.Fame fame() {
        return fame;
    }

    /** {@code viewer}'s own numbers as last read, or {@code null}. */
    StatsCard.Mine mine(UUID viewer) {
        return mine.get(viewer);
    }

    /** Whether a read is on its way (for the tests). */
    boolean inFlight() {
        return inFlight;
    }

    /** How many reads were started (for the tests). */
    int reads() {
        return reads;
    }

    /** Whose numbers are cached (for the tests). */
    Set<UUID> cached() {
        return new HashSet<>(mine.keySet());
    }
}
