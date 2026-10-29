package com.dierks.homecraft.games.clubhouse;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boards' cache (0.38.3; the owner's rule: every database read off the main thread, and cached): the snapshot
 * on the main thread, the database on the async executor, the result back on the main one; one read at a time;
 * every 30 s while anyone is in, sooner (5 s after the last) after an arrival; nothing while nobody is in; a read
 * that comes back after a stop is dropped; a read that fails keeps what was cached and warns once a minute.
 */
class ClubReadsTest {

    private static final UUID SAM = UUID.randomUUID();
    private static final UUID LEE = UUID.randomUUID();

    /** Two queues standing in for the async pool and the next tick, drained by hand. */
    private final Deque<Runnable> async = new ArrayDeque<>();
    private final Deque<Runnable> main = new ArrayDeque<>();
    /** Which side each step ran on: "main:snapshot", "async:read", "main:result". */
    private final List<String> steps = new ArrayList<>();
    private final List<String> warns = new ArrayList<>();
    private String side = "main";
    private boolean failing;

    private final ClubReads.Source source = new ClubReads.Source() {
        @Override
        public ClubData.Snapshot snapshot(Set<UUID> viewers) {
            steps.add(side + ":snapshot");
            return new ClubData.Snapshot(List.of(), List.of(), 0, -1, "Sky Rings", 0, viewers);
        }

        @Override
        public ClubData.Raw read(ClubData.Snapshot snapshot) throws Exception {
            steps.add(side + ":read");
            if (failing) {
                throw new java.sql.SQLException("the database is busy");
            }
            Map<UUID, ClubData.Personal> personal = new java.util.HashMap<>();
            for (UUID v : snapshot.viewers()) {
                personal.put(v, new ClubData.Personal(List.of(), List.of(), Map.of(), List.of()));
            }
            return new ClubData.Raw(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), personal);
        }

        @Override
        public ClubReads.Result result(ClubData.Snapshot snapshot, ClubData.Raw raw) {
            steps.add(side + ":result");
            Map<UUID, StatsCard.Mine> mine = new java.util.HashMap<>();
            raw.personal().forEach((id, p) -> mine.put(id, StatsCard.build(snapshot, p)));
            return new ClubReads.Result(new FameBoard.Fame(List.of(), null, snapshot.pick(), 0, List.of(), List.of(),
                    List.of()), mine);
        }
    };

    private final ClubReads reads = new ClubReads(source, async::add, main::add, warns::add);

    private void drainAsync() {
        side = "async";
        while (!async.isEmpty()) {
            async.poll().run();
        }
        side = "main";
    }

    private void drainMain() {
        while (!main.isEmpty()) {
            main.poll().run();
        }
    }

    private void readNow() {
        drainAsync();
        drainMain();
    }

    @Test
    void theDatabaseIsReadOffTheMainThreadAndTheResultAppliedOnIt() {
        reads.second(1_000, List.of(SAM));
        assertEquals(List.of("main:snapshot"), steps, "the snapshot is made on the main thread");
        assertTrue(reads.inFlight(), "a read on its way");
        assertNull(reads.fame(), "nothing cached yet");
        drainAsync();
        assertEquals(List.of("main:snapshot", "async:read"), steps, "the database is read on the async executor");
        assertNull(reads.fame(), "still nothing: the result is applied on the main thread");
        drainMain();
        assertEquals(List.of("main:snapshot", "async:read", "main:result"), steps, "applied on the main thread");
        assertNotNull(reads.fame(), "cached");
        assertNotNull(reads.mine(SAM), "the visitor's own numbers");
        assertNull(reads.mine(LEE), "nobody else's");
        assertFalse(reads.inFlight(), "done");
    }

    @Test
    void aViewerWhoLeavesDuringAReadIsNotCachedWhenItComesBack() {
        reads.second(1_000, List.of(SAM, LEE));
        drainAsync();
        reads.forget(LEE); // left the Clubhouse while the database was being read
        drainMain();
        assertNull(reads.mine(LEE), "left during the read: not cached when it comes back (R383-B2)");
        assertFalse(reads.cached().contains(LEE), "their numbers went");
        assertNotNull(reads.mine(SAM), "the others' numbers came");
    }

    @Test
    void oneReadAtATimeEveryThirtySecondsAndNothingWhileNobodyIsIn() {
        reads.second(0, List.of());
        assertEquals(0, reads.reads(), "nobody in: nothing read");
        reads.second(0, List.of(SAM));
        reads.second(1_000, List.of(SAM));
        reads.second(40_000, List.of(SAM));
        assertEquals(1, reads.reads(), "one read at a time, however long it takes");
        readNow();
        reads.second(29_999, List.of(SAM));
        assertEquals(1, reads.reads(), "not at 29.999 s");
        reads.second(30_000, List.of(SAM));
        assertEquals(2, reads.reads(), "every 30 s while anyone is in");
        readNow();
        reads.second(100_000, List.of());
        assertEquals(2, reads.reads(), "everyone left: no more reads");
        assertNotNull(reads.fame(), "what was read stays up");
    }

    @Test
    void anArrivalAsksForAReadSoonButNoSoonerThanFiveSecondsAfterTheLast() {
        reads.second(0, List.of(SAM));
        readNow();
        reads.want(LEE); // Lee arrives (or taps My stats)
        reads.second(4_999, List.of(SAM, LEE));
        assertEquals(1, reads.reads(), "not within 5 s of the last read");
        reads.second(5_000, List.of(SAM, LEE));
        assertEquals(2, reads.reads(), "5 s after the last: soon");
        readNow();
        assertNotNull(reads.mine(LEE), "their numbers came with it");
        reads.second(10_000, List.of(SAM, LEE));
        assertEquals(2, reads.reads(), "and then every 30 s again");
        reads.forget(LEE);
        assertNull(reads.mine(LEE), "gone with them");
        assertNotNull(reads.mine(SAM), "the others' stay");
    }

    @Test
    void aReadThatComesBackAfterAStopIsDropped() {
        reads.second(0, List.of(SAM));
        drainAsync();
        reads.stop(); // the Clubhouse stopped (or the room closed) meanwhile
        drainMain();
        assertNull(reads.fame(), "dropped: nothing cached after a stop");
        assertNull(reads.mine(SAM), "nothing");
        assertEquals(List.of("main:snapshot", "async:read"), steps, "its result was never made");
        reads.second(1, List.of(SAM));
        assertEquals(2, reads.reads(), "and the next one comes at once");
    }

    @Test
    void aFailingReadKeepsWhatWasCachedAndWarnsOnceAMinute() {
        reads.second(0, List.of(SAM));
        readNow();
        FameBoard.Fame before = reads.fame();
        failing = true;
        reads.second(30_000, List.of(SAM));
        readNow();
        assertEquals(before, reads.fame(), "the old boards stay up");
        assertNotNull(reads.mine(SAM), "and the old numbers");
        assertEquals(1, warns.size(), "one WARN: " + warns);
        assertFalse(reads.inFlight(), "and the next read may start");
        reads.second(60_000, List.of(SAM));
        readNow();
        assertEquals(1, warns.size(), "not again within the minute");
        reads.second(90_000, List.of(SAM));
        readNow();
        assertEquals(2, warns.size(), "a minute on: again");
    }

    @Test
    void anAsyncPoolThatRefusesLeavesNothingWaiting() {
        ClubReads refusing = new ClubReads(source, r -> {
            throw new IllegalStateException("the plugin is stopping");
        }, main::add, warns::add);
        refusing.second(0, List.of(SAM));
        assertFalse(refusing.inFlight(), "nothing on its way");
        assertEquals(1, warns.size(), warns.toString());
    }
}
