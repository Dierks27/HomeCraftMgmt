package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Spot;
import com.dierks.homecraft.arcade.ShowMap.Kind;
import com.dierks.homecraft.arcade.ShowMap.Marker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Show Map's markers (0.38.2; the owner: "Is there any way we can see a map that would show them as well?"): a
 * block's place on a scale-2 map (2 blocks a unit, 256 either way of the centre); the linked launchers numbered in set
 * order with "#n", the owner's others plain and never a linked one twice; only the map's own world; a linked one off
 * the map pinned to its edge, an X off the map left out.
 */
class ShowMapTest {

    private static Spot at(int x, int z) {
        return new Spot("world", x, 64, z);
    }

    @Test
    void aBlocksPlaceAtScaleTwo() {
        assertEquals(2, ShowMap.SCALE, "512 blocks across: a city");
        assertEquals(0, ShowMap.coord(0, 0, 2), "the centre block");
        assertEquals(5, ShowMap.coord(10, 0, 2), "2 blocks a unit");
        assertEquals(-5, ShowMap.coord(-11, 0, 2));
        assertEquals(50, ShowMap.coord(1100, 1000, 2), "from the map's own centre");
        assertEquals(127, ShowMap.coord(256, 0, 2), "256 blocks east: the edge");
        assertEquals(127, ShowMap.coord(5000, 0, 2), "and farther pins to it");
        assertEquals(-128, ShowMap.coord(-256, 0, 2));
        assertEquals(-128, ShowMap.coord(-5000, 0, 2));
        assertEquals(10, ShowMap.coord(9, 0, 1), "scale 1 (a small town): a block a unit");
        assertEquals(128, ShowMap.REUSE_BLOCKS);
    }

    @Test
    void linkedOnesAreNumberedInSetOrderAndTheOthersPlain() {
        List<Spot> linked = List.of(at(100, 0), at(-50, 20), at(0, -30));
        List<Spot> mine = List.of(at(-50, 20), at(10, 10), at(200, 200));
        List<Marker> markers = ShowMap.markers(linked, mine, "world", 0, 0, 2);
        List<Marker> flags = markers.stream().filter(m -> m.kind() == Kind.LINKED).toList();
        assertEquals(List.of(1, 2, 3), flags.stream().map(Marker::number).toList(), "#1, #2, #3 in link order");
        assertEquals(List.of("#1", "#2", "#3"), flags.stream().map(Marker::caption).toList());
        assertEquals(50, flags.get(0).x());
        assertEquals(0, flags.get(0).y(), "the map's y is the world's z");
        assertEquals(-15, flags.get(2).y());
        List<Marker> xs = markers.stream().filter(m -> m.kind() == Kind.MINE).toList();
        assertEquals(2, xs.size(), "the owner's other two, and never the linked one twice");
        assertTrue(xs.stream().allMatch(m -> m.caption() == null && m.number() == 0), "plain");
        assertEquals(Kind.MINE, markers.get(0).kind(), "the X's first, so the flags are drawn over them");
        assertEquals(Kind.LINKED, markers.get(markers.size() - 1).kind());
    }

    @Test
    void onlyTheMapsOwnWorldIsDrawnAndAnythingOffItAtTheEdgeOrNot() {
        List<Spot> linked = List.of(new Spot("world_nether", 0, 64, 0), at(1000, 0), at(0, 0));
        List<Spot> mine = List.of(new Spot("world_nether", 5, 64, 5), at(-1000, 0), at(4, 4));
        List<Marker> markers = ShowMap.markers(linked, mine, "world", 0, 0, 2);
        List<Marker> flags = markers.stream().filter(m -> m.kind() == Kind.LINKED).toList();
        assertEquals(List.of(2, 3), flags.stream().map(Marker::number).toList(),
                "#1 is in the Nether: not on this map, and the others keep their numbers");
        Marker far = flags.get(0);
        assertTrue(far.edge(), "a linked one off the map sits on its edge");
        assertEquals(127, far.x(), "the east edge: the way it is");
        assertEquals("#2", far.caption(), "still numbered");
        assertFalse(flags.get(1).edge());
        List<Marker> xs = markers.stream().filter(m -> m.kind() == Kind.MINE).toList();
        assertEquals(1, xs.size(), "an X off the map, or in another world, is left out");
        assertEquals(2, xs.get(0).x());
        assertEquals(List.of(), ShowMap.markers(List.of(), List.of(), "world", 0, 0, 2));
        assertNull(new Marker(Kind.MINE, 0, (byte) 0, (byte) 0, false).caption());
        assertEquals("firework_show_map", ShowMap.PRIZE_ID);
    }
}
