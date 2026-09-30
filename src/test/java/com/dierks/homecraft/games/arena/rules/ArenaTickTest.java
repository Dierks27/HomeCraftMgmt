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
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.at;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.p;
import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-tick driver the game and the simulation share: the floors see this tick's feet before
 * the round settles this tick's outs, every position a player passed through this tick marks the
 * floor, the void is out whatever the feet say, every round starts on whole floors and ends even if
 * the feet never come down, and a new week's layout never changes a round already going.
 */
class ArenaTickTest {

    private static ArenaTick soloAtGo(FloorLayout l, UUID who) {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), l.spawns().size());
        ArenaTick t = new ArenaTick(r, l, RoundSettings.defaults());
        verify(r, true);
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
        ArenaTick.Output o = t.tick(at(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(), false);
        assertEquals(List.of(new FloorWrite(0, CX, TOP_Y, CZ, CellState.RED)), o.writes(), "play tick 0 marks the spawn");
        assertEquals(1, t.round().playTicks(), "and the round moved on a tick");
    }

    @Test
    void aPlayersTimeIsTheTickTheirFeetWentBelowOut() {
        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        for (int i = 0; i < 37; i++) {
            t.tick(at(p(1), new Feet(CX + 0.5, 150 + OUT_Y, CZ + 0.5, -0.5)), Set.of(), false);
        }
        ArenaTick.Output o = t.tick(at(p(1), new Feet(CX + 0.5, OUT_Y - 0.5, CZ + 0.5, -1)), Set.of(), false);
        RoundEvent.Out out = (RoundEvent.Out) o.events().get(0);
        assertEquals(37, out.survivedTicks(), "seen below out on play tick 37: lasted 37 ticks, not 38");
        assertTrue(o.events().get(1) instanceof RoundEvent.Ended, "and the solo round ended on that tick");
        assertNull(t.floors(), "the floors are dropped with the round");
    }

    @Test
    void aLandingSeenOnlyBetweenTwoTicksStillMarksTheFloor() {
        int x = CX + 2;
        int z = CZ + 2;
        int top = TOP_Y + 1;
        // A player holding jump is on the floor for one client tick. Both that packet and the next
        // take-off were handled before this server tick, so getLocation() would only show the take-off.
        Feet landing = new Feet(x + 0.5, top, z + 0.5, -0.12);
        Feet takeOff = new Feet(x + 0.5, top + 0.42, z + 0.5, 0.42);

        ArenaTick onlyNow = soloAtGo(ArenaFixtures.small(), p(1));
        assertTrue(onlyNow.tick(at(p(1), takeOff), Set.of(), false).writes().isEmpty(),
                "the take-off alone marks nothing: this is the landing a once-a-tick read misses");

        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        ArenaTick.Output o = t.tick(Map.of(p(1), List.of(landing, takeOff)), Set.of(), false);
        assertEquals(List.of(new FloorWrite(0, x, TOP_Y, z, CellState.RED)), o.writes(),
                "given every position, the landing marks its cell though the player is already rising again");
        assertEquals(CellState.RED, t.floors().state(0, x, z), "so a hopper leaves holes like everyone else");
    }

    @Test
    void theLastPositionOfATickIsWhereThePlayerIsNow() {
        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        List<Feet> falling = List.of(new Feet(CX + 0.5, OUT_Y + 0.6, CZ + 0.5, -2.5),
                new Feet(CX + 0.5, OUT_Y - 0.4, CZ + 0.5, -1.0));
        ArenaTick.Output o = t.tick(Map.of(p(1), falling), Set.of(), false);
        assertTrue(o.events().get(0) instanceof RoundEvent.Out, "the latest position is below out_y: out this tick");
        ArenaTick up = soloAtGo(ArenaFixtures.small(), p(1));
        assertTrue(up.tick(Map.of(p(1), List.of()), Set.of(), false).events().isEmpty(),
                "an empty list is no feet this tick, not an out");
    }

    @Test
    void aSoloRoundWhoseFeetNeverComeStillEndsOnceTheFloorsAreGone() {
        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        t.tick(Map.of(), Set.of(), false);
        FloorRules floors = t.floors();
        long endsAt = floors.goneBy() + ArenaTick.FALL_TICKS;
        RoundEvent.Out out = null;
        for (int i = 0; i < 100_000 && out == null; i++) {
            for (RoundEvent e : t.tick(Map.of(), Set.of(), false).events()) {
                if (e instanceof RoundEvent.Out o) {
                    out = o;
                }
            }
        }
        assertNotNull(out, "the round ended though the game never saw the player's feet");
        assertEquals(endsAt, out.survivedTicks(), "exactly " + ArenaTick.FALL_TICKS + " ticks after the last cell went");
        assertTrue(floors.allGone(), "by then every floor was air");
        assertEquals(OutReason.FELL, out.reason(), "out as if they fell");
        assertEquals(ArenaRound.Phase.RESET, t.round().phase(), "and the arena went on to the reset");
    }

    @Test
    void playersHoveringAboveEveryFloorAreOutTogetherOnceTheFloorsAreGone() {
        ArenaRound r = new ArenaRound(RoundSettings.defaults(), 2);
        ArenaTick t = new ArenaTick(r, ArenaFixtures.small(), RoundSettings.defaults());
        verify(r, true);
        r.join(p(1));
        r.join(p(2));
        r.ready(p(1), true);
        r.ready(p(2), true);
        for (int i = 0; i < 1000 && r.phase() != ArenaRound.Phase.PLAYING; i++) {
            t.tick(Map.of(), Set.of(), false);
        }
        assertEquals(ArenaRound.Phase.PLAYING, r.phase(), "the round is being played");
        Map<UUID, List<Feet>> hovering = Map.of(p(1), List.of(new Feet(CX + 0.5, TOP_Y + 30, CZ + 0.5, 0)),
                p(2), List.of(new Feet(CX + 3.5, TOP_Y + 30, CZ + 3.5, 0)));
        t.tick(hovering, Set.of(), false);
        long endsAt = t.floors().goneBy() + ArenaTick.FALL_TICKS;
        RoundResult result = null;
        for (int i = 0; i < 100_000 && result == null; i++) {
            for (RoundEvent e : t.tick(hovering, Set.of(), false).events()) {
                if (e instanceof RoundEvent.Ended en) {
                    result = en.result();
                }
            }
        }
        assertNotNull(result, "a round two players never fall out of still ends");
        assertEquals(endsAt, result.ticks(), "at the same bound: the last ring's fade and " + ArenaTick.FALL_TICKS);
        assertEquals(List.of(1, 1), result.standings().stream().map(Standing::place).toList(),
                "out on the same tick, they share the place");
    }

    @Test
    void theVoidIsOutWhateverTheFeetSayAndMissingFeetAreNot() {
        ArenaTick t = soloAtGo(ArenaFixtures.small(), p(1));
        ArenaTick.Output quiet = t.tick(new HashMap<>(), Set.of(), false);
        assertTrue(quiet.events().isEmpty(), "no feet this tick (a teleport in flight): nobody is out for it");
        ArenaTick.Output voided = t.tick(at(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(p(1)), false);
        assertTrue(voided.events().get(0) instanceof RoundEvent.Out, "onVoid: out, even standing on the floor");
    }

    @Test
    void everyRoundStartsOnWholeFloors() {
        FloorLayout l = ArenaFixtures.small();
        ArenaTick t = soloAtGo(l, p(1));
        t.tick(at(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(), false);
        FloorRules first = t.floors();
        assertEquals(CellState.RED, first.state(0, CX, CZ), "round 1 marked the spawn");
        t.tick(at(p(1), new Feet(CX, OUT_Y - 1, CZ, -1)), Set.of(), false);
        verify(t.round(), true);
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
        outs.add(t.tick(at(p(1), ArenaFixtures.on(CX, CZ, TOP_Y + 1)), Set.of(), false));
        assertSame(small, t.layout(), "mid-round the old floors stay");
        assertSame(small, t.floors().layout(), "and the round plays on them");
        outs.add(t.tick(at(p(1), new Feet(CX, OUT_Y - 1, CZ, -1)), Set.of(), false));
        assertTrue(all(outs).stream().anyMatch(e -> e instanceof RoundEvent.Ended), "the round ended");
        t.tick(Map.of(), Set.of(), false);
        assertSame(discs, t.layout(), "then the new week's floors are in force");
        assertEquals(12, t.round().spawnCount(), "with their twelve spawns, not the old two");
        verify(t.round(), true);
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
