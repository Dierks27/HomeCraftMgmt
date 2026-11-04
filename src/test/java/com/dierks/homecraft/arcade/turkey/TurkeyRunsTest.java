package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.arcade.turkey.TurkeyRules.Kind;
import com.dierks.homecraft.arcade.turkey.TurkeyRuns.Offer;
import com.dierks.homecraft.arcade.turkey.TurkeyRuns.Run;
import com.dierks.homecraft.arcade.turkey.TurkeyRuns.Tap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The turkey runs with a fake host: every player gets their own turkey and everyone catches once a run with the
 * same prize, a repeat tap clucks, a run ends once, and a claim written before a restart means no turkey that
 * can't be caught.
 */
class TurkeyRunsTest {

    /** A host with a database in maps. */
    private static final class FakeHost implements TurkeyHost {
        final Map<String, Long> counters = new HashMap<>();
        final List<UUID> spawned = new ArrayList<>();
        final Set<UUID> removed = new HashSet<>();
        final List<String> prizes = new ArrayList<>();
        final List<UUID> clucks = new ArrayList<>();
        boolean noSpot;

        private long add(String key) {
            return counters.merge(key, 1L, Long::sum);
        }

        @Override
        public UUID spawn(UUID player, String runId) {
            if (noSpot) {
                return null;
            }
            UUID t = UUID.randomUUID();
            spawned.add(t);
            return t;
        }

        @Override
        public void remove(UUID turkey) {
            removed.add(turkey);
        }

        @Override
        public boolean claimed(UUID player, String runId) {
            return counters.getOrDefault(player + "|" + runId, 0L) > 0;
        }

        @Override
        public boolean claim(UUID player, String runId) {
            return add(player + "|" + runId) == 1;
        }

        @Override
        public boolean welcomeGiven(UUID player) {
            return counters.getOrDefault(player + "|welcome", 0L) > 0;
        }

        @Override
        public boolean markWelcome(UUID player) {
            return add(player + "|welcome") == 1;
        }

        @Override
        public void caught(UUID player, UUID turkey, String runId, boolean test) {
            prizes.add(player + "|" + runId + (test ? "|test" : ""));
        }

        @Override
        public void cluck(UUID player, UUID turkey) {
            clucks.add(player);
        }
    }

    private static final UUID DAD = UUID.randomUUID();
    private static final UUID UNCLE = UUID.randomUUID();
    private static final UUID LITTLE = UUID.randomUUID();

    @Test
    void everyoneCatchesOnceARunAndTheTurkeyStays() {
        FakeHost host = new FakeHost();
        TurkeyRuns runs = new TurkeyRuns(host);
        Run r = runs.start("s1", Kind.SCHEDULED, null, 0, 180_000, false);
        for (UUID p : List.of(DAD, UNCLE, LITTLE)) {
            assertEquals(Offer.SPAWNED, runs.offer("s1", p), "each their own turkey");
        }
        assertEquals(3, r.turkeys().size());
        UUID hers = r.turkeys().get(LITTLE);
        assertEquals(LITTLE, runs.ownerOf(hers));
        assertEquals(Tap.CAUGHT, runs.tap(UNCLE, hers), "an uncle catches hers");
        assertEquals(Tap.CAUGHT, runs.tap(DAD, hers));
        assertFalse(host.removed.contains(hers), "it stays for her");
        assertEquals(Tap.CAUGHT, runs.tap(LITTLE, hers), "and she still catches it");
        assertEquals(List.of(UNCLE + "|s1", DAD + "|s1", LITTLE + "|s1"), host.prizes, "the same prize, once each");
        assertEquals(List.of(UNCLE, DAD, LITTLE), new ArrayList<>(r.catchers()));
        assertEquals(Offer.HAS_ONE, runs.offer("s1", LITTLE));
    }

    @Test
    void aRepeatTapClucksAndPaysNothing() {
        FakeHost host = new FakeHost();
        TurkeyRuns runs = new TurkeyRuns(host);
        runs.start("s1", Kind.SCHEDULED, null, 0, 180_000, false);
        runs.offer("s1", LITTLE);
        runs.offer("s1", DAD);
        UUID hers = runs.run("s1").turkeys().get(LITTLE);
        UUID his = runs.run("s1").turkeys().get(DAD);
        assertEquals(Tap.CAUGHT, runs.tap(LITTLE, hers));
        assertEquals(Tap.CLUCK, runs.tap(LITTLE, hers), "already caught: a cluck, never silence");
        assertEquals(Tap.CLUCK, runs.tap(LITTLE, his), "once a run, whichever turkey");
        assertEquals(1, host.prizes.size());
        assertEquals(List.of(LITTLE, LITTLE), host.clucks);
        assertEquals(1L, host.counters.get(LITTLE + "|s1"), "one claim");
    }

    @Test
    void theRunEndsAfterItsMinutesExactlyOnce() {
        FakeHost host = new FakeHost();
        TurkeyRuns runs = new TurkeyRuns(host);
        Run r = runs.start("s1", Kind.SCHEDULED, null, 1_000, 180_000, false);
        runs.offer("s1", DAD);
        runs.offer("s1", LITTLE);
        assertEquals(1_000 + 180_000, r.endsAt());
        assertEquals(List.of(), runs.expire(180_999), "a millisecond early");
        assertEquals(1, r.msLeft(180_999));
        assertEquals(List.of("s1"), runs.expire(181_000), "3 minutes exactly");
        assertEquals(List.of(), runs.expire(181_001), "once");
        assertEquals(Set.copyOf(host.spawned), host.removed, "every turkey waddles home");
        assertTrue(runs.isEmpty());
        assertNull(runs.start("s1", Kind.SCHEDULED, null, 181_500, 180_000, false), "a reload can't start it again");
        assertEquals(Offer.NOT_LIVE, runs.offer("s1", DAD));
    }

    @Test
    void aClaimWrittenBeforeARestartGivesNoTurkeyForThatRun() {
        FakeHost host = new FakeHost();
        host.claim(LITTLE, "s1"); // caught before the restart
        TurkeyRuns runs = new TurkeyRuns(host);
        runs.start("s1", Kind.SCHEDULED, null, 0, 180_000, false);
        assertEquals(Offer.CAUGHT, runs.offer("s1", LITTLE), "no turkey she can't catch");
        assertTrue(host.spawned.isEmpty());
        assertEquals(Offer.SPAWNED, runs.offer("s1", DAD));
        UUID his = runs.run("s1").turkeys().get(DAD);
        assertEquals(Tap.CLUCK, runs.tap(LITTLE, his), "and a tap on another's clucks");
        assertTrue(host.prizes.isEmpty());
    }

    @Test
    void sixTurkeysARunAtMost() {
        FakeHost host = new FakeHost();
        TurkeyRuns runs = new TurkeyRuns(host);
        runs.start("s1", Kind.SCHEDULED, null, 0, 180_000, false);
        for (int i = 0; i < TurkeyRules.MAX_TURKEYS_PER_RUN; i++) {
            assertEquals(Offer.SPAWNED, runs.offer("s1", UUID.randomUUID()));
        }
        assertEquals(Offer.CAP, runs.offer("s1", UUID.randomUUID()));
    }

    @Test
    void aWelcomeTurkeyIsItsOwnersAndOnceADay() {
        FakeHost host = new FakeHost();
        TurkeyRuns runs = new TurkeyRuns(host);
        Run w = runs.start("w1-aaaa", Kind.WELCOME, LITTLE, 0, 180_000, false);
        assertEquals(LITTLE, w.owner());
        assertEquals(Offer.NOT_YOURS, runs.offer("w1-aaaa", DAD));
        assertEquals(Offer.SPAWNED, runs.offer("w1-aaaa", LITTLE));
        assertEquals(1L, host.counters.get(LITTLE + "|welcome"), "marked as it appears");
        UUID hers = w.turkeys().get(LITTLE);
        assertEquals(Tap.NOT_YOURS, runs.tap(DAD, hers), "only she can catch it");
        assertEquals(List.of(DAD), host.clucks);
        assertEquals(Tap.CAUGHT, runs.tap(LITTLE, hers));

        TurkeyRuns afterRestart = new TurkeyRuns(host);
        afterRestart.start("w1-aaaa", Kind.WELCOME, LITTLE, 0, 180_000, false);
        assertEquals(Offer.CAUGHT, afterRestart.offer("w1-aaaa", LITTLE), "a restart the same day: none");
        afterRestart.start("w1-bbbb", Kind.WELCOME, LITTLE, 0, 180_000, false);
        assertEquals(Offer.WELCOME_GIVEN, afterRestart.offer("w1-bbbb", LITTLE), "the welcome mark stops a second");
    }

    @Test
    void aWelcomeTurkeyWithNoSpotIsTriedAgain() {
        FakeHost host = new FakeHost();
        host.noSpot = true;
        TurkeyRuns runs = new TurkeyRuns(host);
        runs.start("w1-aaaa", Kind.WELCOME, LITTLE, 0, 180_000, false);
        assertEquals(Offer.NO_SPOT, runs.offer("w1-aaaa", LITTLE));
        assertFalse(host.counters.containsKey(LITTLE + "|welcome"), "no mark without a turkey");
        runs.abandon("w1-aaaa");
        host.noSpot = false;
        assertNotNull(runs.start("w1-aaaa", Kind.WELCOME, LITTLE, 5_000, 180_000, false), "free to start again");
        assertEquals(Offer.SPAWNED, runs.offer("w1-aaaa", LITTLE));
    }

    @Test
    void aLostTurkeyGoesAndAScheduledOneCanComeBack() {
        FakeHost host = new FakeHost();
        TurkeyRuns runs = new TurkeyRuns(host);
        runs.start("s1", Kind.SCHEDULED, null, 0, 180_000, false);
        runs.offer("s1", LITTLE);
        UUID first = runs.run("s1").turkeys().get(LITTLE);
        runs.lost(first);
        assertTrue(host.removed.contains(first));
        assertFalse(runs.hasTurkey(LITTLE));
        assertEquals(Tap.NOT_OURS, runs.tap(LITTLE, first));
        assertEquals(Offer.SPAWNED, runs.offer("s1", LITTLE), "a new one while the run lasts");

        Run w = runs.start("w1-aaaa", Kind.WELCOME, DAD, 0, 180_000, false);
        runs.offer("w1-aaaa", DAD);
        runs.lost(w.turkeys().get(DAD));
        assertNull(runs.run("w1-aaaa"), "a welcome run ends with its turkey");

        runs.quit(LITTLE);
        assertFalse(runs.hasTurkey(LITTLE), "a quit takes theirs");
    }

    @Test
    void aTestRunWritesNothing() {
        FakeHost host = new FakeHost();
        host.claim(LITTLE, "t9"); // even a stray claim is not read in a test run
        TurkeyRuns runs = new TurkeyRuns(host);
        Run t = runs.start("t9", Kind.SCHEDULED, null, 0, 180_000, true);
        assertTrue(t.test());
        assertEquals(Offer.SPAWNED, runs.offer("t9", DAD));
        assertEquals(Offer.SPAWNED, runs.offer("t9", LITTLE));
        UUID his = t.turkeys().get(DAD);
        assertEquals(Tap.CAUGHT, runs.tap(DAD, his));
        assertEquals(Tap.CLUCK, runs.tap(DAD, his), "once a run in a test too");
        assertFalse(host.counters.containsKey(DAD + "|t9"), "no claim written");
        assertEquals(List.of(DAD + "|t9|test"), host.prizes, "the moment only");
    }

    @Test
    void clearEndsEverything() {
        FakeHost host = new FakeHost();
        TurkeyRuns runs = new TurkeyRuns(host);
        Run r = runs.start("s1", Kind.SCHEDULED, null, 0, 180_000, false);
        assertSame(r, runs.start("s1", Kind.SCHEDULED, null, 10, 180_000, false), "the run going on");
        runs.offer("s1", DAD);
        runs.offer("s1", LITTLE);
        assertEquals(2, runs.clear());
        assertTrue(runs.isEmpty());
        assertTrue(runs.turkeys().isEmpty());
        assertEquals(2, host.removed.size());
    }
}
