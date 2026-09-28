package com.dierks.homecraft.muffler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What happens to one sound when mufflers overlap, are switched off, or are in another world. */
class MufflerZonesTest {

    private static final String CLUCK = "minecraft:entity.chicken.ambient";
    private static final UUID ME = UUID.randomUUID();
    private static final UUID NEIGHBOUR = UUID.randomUUID();

    private static Muffler at(int x, UUID owner) {
        return Muffler.placed(new MufflerPos("world", x, 64, 0), owner, 4, 25);
    }

    @Test
    void aSoundOutsideEveryBoxIsLeftAlone() {
        List<Muffler> zones = List.of(at(0, ME).withGroup("chickens", MuffleLevel.SILENT));
        List<String> heard = new ArrayList<>();
        MufflerZones.Verdict v = MufflerZones.decide(zones, 50, 64, 0, CLUCK, (m, k) -> heard.add(k));
        assertSame(MufflerZones.Verdict.NONE, v);
        assertTrue(heard.isEmpty(), "a muffler only hears its own box");
        assertFalse(MufflerZones.anyContains(zones, 50, 64, 0));
    }

    @Test
    void silentDropsAndQuieterTurnsDown() {
        assertTrue(MufflerZones.decide(List.of(at(0, ME).withGroup("chickens", MuffleLevel.SILENT)),
                0.5, 64.5, 0.5, CLUCK, null).silent());
        MufflerZones.Verdict v = MufflerZones.decide(List.of(at(0, ME).withGroup("chickens", MuffleLevel.QUIETER)),
                0.5, 64.5, 0.5, CLUCK, null);
        assertFalse(v.silent());
        assertEquals(0.25f, v.factor(), 1e-6);
        assertTrue(v.changes());
    }

    @Test
    void whereTwoOverlapTheStrongestWins() {
        Muffler quiet10 = at(0, ME).withQuietPercent(10).withGroup("chickens", MuffleLevel.QUIETER);
        Muffler quiet50 = at(2, NEIGHBOUR).withQuietPercent(50).withGroup("chickens", MuffleLevel.QUIETER);
        Muffler silent = at(3, NEIGHBOUR).withGroup("chickens", MuffleLevel.SILENT);

        MufflerZones.Verdict v = MufflerZones.decide(List.of(quiet50, quiet10), 1.5, 64.5, 0.5, CLUCK, null);
        assertEquals(0.10f, v.factor(), 1e-6, "two Quieters: the quieter of the two");
        assertTrue(MufflerZones.decide(List.of(quiet10, silent), 1.5, 64.5, 0.5, CLUCK, null).silent(),
                "Silent beats Quieter, whichever muffler is asked first");
    }

    @Test
    void aNeighboursAlwaysPlayCannotUndoMySilent() {
        Muffler mine = at(0, ME).withGroup("chickens", MuffleLevel.SILENT);
        Muffler theirs = at(1, NEIGHBOUR).withSound(CLUCK, MuffleLevel.ALLOW);
        assertTrue(MufflerZones.decide(List.of(theirs, mine), 0.5, 64.5, 0.5, CLUCK, null).silent());
    }

    @Test
    void aSwitchedOffMufflerStillListensButHushesNothing() {
        Muffler off = at(0, ME).withGroup("chickens", MuffleLevel.SILENT).withEnabled(false);
        List<String> heard = new ArrayList<>();
        MufflerZones.Verdict v = MufflerZones.decide(List.of(off), 0.5, 64.5, 0.5, CLUCK, (m, k) -> heard.add(k));
        assertSame(MufflerZones.Verdict.NONE, v);
        assertEquals(List.of(CLUCK), heard, "Heard nearby keeps filling while it is off, so it can be set up");
    }

    @Test
    void everyMufflerWhoseBoxHoldsTheSoundHearsIt() {
        List<Muffler> who = new ArrayList<>();
        MufflerZones.decide(List.of(at(0, ME), at(3, NEIGHBOUR), at(40, ME)), 2.5, 64.5, 0.5, CLUCK,
                (m, k) -> who.add(m));
        assertEquals(2, who.size());
    }

    @Test
    void mufflersAreSortedIntoTheirWorlds() {
        Muffler here = at(0, ME);
        Muffler nether = Muffler.placed(new MufflerPos("world_nether", 0, 64, 0), ME, 4, 25);
        MufflerZones zones = MufflerZones.of(List.of(here, nether));
        assertEquals(List.of(here), zones.in("world"));
        assertEquals(List.of(nether), zones.in("world_nether"));
        assertTrue(zones.in("world_the_end").isEmpty());
        assertTrue(zones.in(null).isEmpty());
        assertSame(MufflerZones.EMPTY, MufflerZones.of(List.of()));
        assertTrue(MufflerZones.EMPTY.isEmpty());
    }
}
