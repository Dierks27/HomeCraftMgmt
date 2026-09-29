package com.dierks.homecraft.games.arena.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole rounds with fake players, tick by tick (EVENTS-DROPPER-SPEC §B.3.3, §B.4): every round
 * ends, standing still loses to moving, players out on the same tick share their place, a full
 * arena stays under the writer's cap, and the same round replays the same, byte for byte.
 */
class ArenaSimTest {

    /** Sudden death after 30 s (the least config allows), so bounded rounds are quick to run. */
    private static final RoundSettings QUICK = new RoundSettings(10, 2, 12, true, 30);

    /** A round's worst case: sudden death, every ring, a fade, and a fall through all three floors. */
    private static long bound(RoundSettings s, FloorLayout l) {
        return s.roundTicks() + (long) l.maxRings() * RoundSettings.RING_TICKS + s.fadeTicks() + 200;
    }

    private static ArenaSim readySim(FloorLayout l, RoundSettings s, List<ArenaSim.Bot> bots) {
        ArenaSim sim = new ArenaSim(l, s);
        for (ArenaSim.Bot b : bots) {
            sim.add(b);
        }
        assertTrue(sim.runUntil(ArenaRound.Phase.LOBBY, 5), "the boot verify passes and the lobby opens");
        sim.readyAll();
        return sim;
    }

    @Test
    void everyRoundEndsWhateverThePlayersDo() {
        FloorLayout l = ArenaFixtures.discs();
        List<List<ArenaSim.Bot>> mixes = new ArrayList<>();
        List<ArenaSim.Bot> runners = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            runners.add(ArenaSim.runner());
        }
        mixes.add(runners);
        for (long seed = 1; seed <= 4; seed++) {
            List<ArenaSim.Bot> mix = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                mix.add(ArenaSim.wanderer(seed * 100 + i));
            }
            mix.add(ArenaSim.runner());
            mix.add(ArenaSim.hopper());
            mixes.add(mix);
        }
        for (List<ArenaSim.Bot> mix : mixes) {
            ArenaSim sim = readySim(l, QUICK, mix);
            RoundResult r = sim.runUntilEnd(RoundSettings.COUNTDOWN_TICKS + 100 + bound(QUICK, l));
            assertNotNull(r, "a round of " + mix.size() + " fake players ended within sudden death's bound");
            assertTrue(r.ticks() <= bound(QUICK, l), "within " + bound(QUICK, l) + " play ticks: " + r.ticks());
            assertEquals(mix.size(), r.standings().size(), "everyone who started has a standing");
            assertEquals(1, r.standings().get(0).place(), "someone is 1st");
            assertTrue(r.contested(), "fake players never leave, so it was a real contest");
            assertTrue(sim.runUntil(ArenaRound.Phase.LOBBY, 5), "and it went through the reset back to the lobby");
        }
    }

    @Test
    void aSoloRunnerLastsOverAMinuteOnTheSitesDiscs() {
        FloorLayout l = ArenaFixtures.discs();
        RoundSettings s = RoundSettings.defaults();
        ArenaSim sim = new ArenaSim(l, s);
        UUID id = sim.add(ArenaSim.runner());
        sim.runUntil(ArenaRound.Phase.LOBBY, 5);
        assertEquals(ArenaRound.Solo.STARTED, sim.round().solo(id, false), "alone: play solo");
        RoundResult r = sim.runUntilEnd(100 + bound(s, l));
        assertNotNull(r, "the round ended");
        assertTrue(r.solo(), "a solo round");
        Standing st = r.standings().get(0);
        assertTrue(st.survivedTicks() > 1200, "keeping on fresh glass lasts over a minute: " + st.survivedTicks());
        assertTrue(st.survivedTicks() <= bound(s, l), "and no longer than sudden death allows: " + st.survivedTicks());
        assertEquals(st.survivedTicks() * 50, st.survivalMs(), "survival in ms is ticks x 50");
    }

    @Test
    void suddenDeathEndsRoundsThatWouldOtherwiseGoOn() {
        FloorLayout l = ArenaFixtures.discs();
        for (int players : new int[]{1, 2}) {
            ArenaSim sim = new ArenaSim(l, QUICK);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < players; i++) {
                ids.add(sim.add(ArenaSim.runner()));
            }
            sim.runUntil(ArenaRound.Phase.LOBBY, 5);
            if (players == 1) {
                sim.round().solo(ids.get(0), false);
            } else {
                sim.readyAll();
            }
            RoundResult r = sim.runUntilEnd(RoundSettings.COUNTDOWN_TICKS + 100 + bound(QUICK, l));
            assertNotNull(r, players + " runner(s): the round ended");
            assertTrue(sim.log().stream().anyMatch(line -> line.contains("SuddenDeath[")),
                    players + " runner(s) outlast 30 s, so sudden death came");
            assertTrue(r.ticks() > QUICK.roundTicks(), "the round went past 30 s: " + r.ticks());
            assertTrue(r.ticks() <= bound(QUICK, l), "and the rings ended it within " + bound(QUICK, l) + ": "
                    + r.ticks());
        }
    }

    @Test
    void standingStillIsOutQuicklyAndSurvivalIsCountedFromGo() {
        ArenaSim sim = new ArenaSim(ArenaFixtures.discs(), RoundSettings.defaults());
        UUID id = sim.add(ArenaSim.still());
        sim.runUntil(ArenaRound.Phase.LOBBY, 5);
        sim.round().solo(id, false);
        RoundResult r = sim.runUntilEnd(1000);
        assertNotNull(r, "standing still ends a solo round");
        long t = r.standings().get(0).survivedTicks();
        assertTrue(t >= 60 && t < 120, "three fades and the falls between: 3 to 6 s, whatever the spawn: " + t);
        assertTrue(sim.log().stream().anyMatch(line -> line.contains("Out[") && line.contains("survivedTicks=" + t)),
                "the Out event carries the same time as the result");
    }

    @Test
    void playersOutOnTheSameTickShareTheirPlaceAndTheRunnerWins() {
        ArenaSim sim = readySim(ArenaFixtures.discs(), RoundSettings.defaults(),
                List.of(ArenaSim.runner(), ArenaSim.still(), ArenaSim.still()));
        RoundResult r = sim.runUntilEnd(RoundSettings.COUNTDOWN_TICKS + 100 + 2000);
        assertNotNull(r, "the round ended");
        List<Standing> st = r.standings();
        assertTrue(st.get(0).winner(), "the runner is the last one standing: " + st.get(0));
        assertEquals(List.of(1, 2, 2), st.stream().map(Standing::place).toList(),
                "the two who stood still fell at the same tick on identical floors: joint 2nd");
        assertTrue(st.get(1).tied() && st.get(2).tied(), "marked as sharing");
        assertEquals(st.get(1).survivedTicks(), st.get(0).survivedTicks(),
                "the round ended the tick they went out, with one player left");
    }

    @Test
    void aFullArenaNeverWritesMoreThanTheWriterAllowsBeforeSuddenDeath() {
        FloorLayout l = ArenaFixtures.discs();
        List<ArenaSim.Bot> bots = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            bots.add(i % 2 == 0 ? ArenaSim.runner() : ArenaSim.wanderer(i));
        }
        ArenaSim sim = readySim(l, QUICK, bots);
        int worst = 0;
        long guard = 0;
        while (sim.results().isEmpty() && guard++ < 10_000) {
            ArenaTick.Output o = sim.step();
            if (!sim.round().suddenDeath()) {
                worst = Math.max(worst, o.writes().size());
            }
            for (FloorWrite w : o.writes()) {
                assertTrue(l.isFloorCell(w.x(), w.y(), w.z()), "only planned floor cells are written: " + w);
            }
        }
        assertTrue(worst > 0, "the round really wrote blocks");
        assertTrue(worst <= 12 * 4 * 2, "12 players x 4 cells x 2 writes is the most a tick can need: " + worst);
        assertTrue(worst <= 128, "so the FloorWriter's 128 a tick never queues a fall behind: " + worst);
    }

    @Test
    void theSameRoundReplaysTheSameByteForByte() {
        List<String> a = replay(7);
        List<String> b = replay(7);
        assertEquals(a, b, "the same bots on the same floors give the same log, event for event and block for block");
        assertNotEquals(a, replay(8), "and a different wanderer really does play differently");
        assertTrue(a.size() > 50, "a real round was logged: " + a.size() + " lines");
    }

    private static List<String> replay(long seed) {
        ArenaSim sim = readySim(ArenaFixtures.discs(), QUICK, List.of(ArenaSim.wanderer(seed), ArenaSim.wanderer(seed
                + 1), ArenaSim.runner(), ArenaSim.hopper()));
        sim.runUntilEnd(5000);
        return sim.log();
    }

    @Test
    void failedResetsAreRetriedAndTheThirdClosesTheGame() {
        ArenaSim twice = new ArenaSim(ArenaFixtures.small(), QUICK);
        twice.failResets(2);
        assertTrue(twice.runUntil(ArenaRound.Phase.LOBBY, 10), "two failures, then a pass: the lobby opens");
        ArenaSim thrice = new ArenaSim(ArenaFixtures.small(), QUICK);
        thrice.failResets(3);
        assertTrue(thrice.runUntil(ArenaRound.Phase.CLOSED, 10), "three failures in a row: closed");
    }

    @Test
    void roundsFollowOneAnotherWhilePlayersStay() {
        ArenaSim sim = readySim(ArenaFixtures.small(), QUICK, List.of(ArenaSim.still(), ArenaSim.hopper()));
        RoundResult first = sim.runUntilEnd(2000);
        assertNotNull(first, "round 1");
        RoundResult second = sim.runUntilEnd(RoundSettings.AUTO_START_TICKS + RoundSettings.COUNTDOWN_TICKS + 2000);
        assertNotNull(second, "round 2 starts by itself 20 s after the lobby reopens with two players");
        assertEquals(2, second.round(), "numbered on");
    }
}
