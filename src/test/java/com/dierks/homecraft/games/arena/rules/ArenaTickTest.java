package com.dierks.homecraft.games.arena.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.CX;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.CZ;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.OUT_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.TOP_Y;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-tick driver the game and the simulation share: the floors see this tick's feet before
 * the round settles this tick's outs, the void is out whatever the feet say, every round starts on
 * whole floors, and a new week's layout never changes a round already going.
 */
class ArenaTickTest {

    private static ArenaTick soloAtGo(FloorLayout l, UUID who) {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), l.spawns().size());
        ArenaTick t = new ArenaTick(r, l, RoundSettings.defaults());
        r.resetDone(true);
        r.join(who);
        r.solo(who, false);
        for (int i = 0; i < 100 && r.phase() != ArenaRound.Phase.PLAYING; i++) {
            t.tick(Map.of(), Set.of(), false);
        }
        assertEquals(ArenaRound.Phase.PLAYING, r.phase(), "the solo round is being played");
        return t;
    }

    private static List<RoundEvent> all(List<ArenaTick.Output> outs) {
        List<RoundEvent> e = new ArrayList<>();
        for (ArenaTick.Output o : outs) {
            e.addAll(o.events());
        }
        return e;
    }

    @Test
    void aPlayerStandingOnTheTopTurnsItRedOnTheFirstTickOfPlay() {
        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        ArenaTick.Output o = t.tick(Map.of(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(), false);
        assertEquals(List.of(new FloorWrite(0, CX, TOP_Y, CZ, CellState.RED)), o.writes(), "play tick 0 marks the spawn");
        assertEquals(1, t.round().playTicks(), "and the round moved on a tick");
    }

    @Test
    void aPlayersTimeIsTheTickTheirFeetWentBelowOut() {
        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        for (int i = 0; i < 37; i++) {
            t.tick(Map.of(p(1), new Feet(CX + 0.5, 150 + OUT_Y, CZ + 0.5, -0.5)), Set.of(), false);
        }
        ArenaTick.Output o = t.tick(Map.of(p(1), new Feet(CX + 0.5, OUT_Y - 0.5, CZ + 0.5, -1)), Set.of(), false);
        RoundEvent.Out out = (RoundEvent.Out) o.events().get(0);
        assertEquals(37, out.survivedTicks(), "seen below out on play tick 37: lasted 37 ticks, not 38");
        assertTrue(o.events().get(1) instanceof RoundEvent.Ended, "and the solo round ended on that tick");
        assertNull(t.floors(), "the floors are dropped with the round");
    }

    @Test
    void theVoidIsOutWhateverTheFeetSayAndMissingFeetAreNot() {
        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        ArenaTick.Output quiet = t.tick(new HashMap<>(), Set.of(), false);
        assertTrue(quiet.events().isEmpty(), "no feet this tick (a teleport in flight): nobody is out for it");
        ArenaTick.Output voided = t.tick(Map.of(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(p(1)), false);
        assertTrue(voided.events().get(0) instanceof RoundEvent.Out, "onVoid: out, even standing on the floor");
    }

    @Test
    void everyRoundStartsOnWholeFloors() {
        FloorLayout l = ArenaFixtures.small();
        ArenaTick t = soloAtGo(l, p(1));
        t.tick(Map.of(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(), false);
        FloorRules first = t.floors();
        assertEquals(CellState.RED, first.state(0, CX, CZ), "round 1 marked the spawn");
        t.tick(Map.of(p(1), new Feet(CX, OUT_Y - 1, CZ, -1)), Set.of(), false);
        t.round().resetDone(true);
        t.round().solo(p(1), false);
        for (int i = 0; i < 100 && t.round().phase() != ArenaRound.Phase.PLAYING; i++) {
            t.tick(Map.of(), Set.of(), false);
        }
        t.tick(Map.of(), Set.of(), false);
        assertNotNull(t.floors(), "round 2 has floors");
        assertTrue(first != t.floors(), "new ones");
        assertEquals(CellState.SOLID, t.floors().state(0, CX, CZ), "whole again: the reset put them back");
    }

    @Test
    void aNewWeeksLayoutWaitsForTheRoundGoingToEnd() {
        FloorLayout small = ArenaFixtures.small();
        FloorLayout discs = ArenaFixtures.discs();
        ArenaTick t = soloAtGo(small, p(1));
        t.layout(discs);
        List<ArenaTick.Output> outs = new ArrayList<>();
        outs.add(t.tick(Map.of(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(), false));
        assertSame(small, t.layout(), "mid-round the old floors stay");
        assertSame(small, t.floors().layout(), "and the round plays on them");
        outs.add(t.tick(Map.of(p(1), new Feet(CX, OUT_Y - 1, CZ, -1)), Set.of(), false));
        assertTrue(all(outs).stream().anyMatch(e -> e instanceof RoundEvent.Ended), "the round ended");
        t.tick(Map.of(), Set.of(), false);
        assertSame(discs, t.layout(), "then the new week's floors are in force");
        assertEquals(12, t.round().spawnCount(), "with their twelve spawns, not the old two");
        t.round().resetDone(true);
        t.round().solo(p(1), false);
        List<RoundEvent> start = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            start.addAll(t.tick(Map.of(), Set.of(), false).events());
        }
        RoundEvent.TeleportTo tp = (RoundEvent.TeleportTo) start.stream().filter(e -> e instanceof RoundEvent.TeleportTo)
                .findFirst().orElseThrow();
        assertEquals(1, tp.spawn(), "round 2 of one player turns to spawn 1 of the new layout");
    }
}
