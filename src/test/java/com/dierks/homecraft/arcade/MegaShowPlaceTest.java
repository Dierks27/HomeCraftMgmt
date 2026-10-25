package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Spot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a Mega Firework Show fires from (0.38.1): the nearest launchers within range of whoever uses it, anyone's, in
 * their world only, at most the row's {@code launchers}, the same ones every time; none in range is none; and they are
 * ordered left to right as the user faces them, whichever way that is.
 */
class MegaShowPlaceTest {

    private static Spot at(int x, int y, int z) {
        return new Spot("world", x, y, z);
    }

    @Test
    void theThreeNearestInRangeAnyonesInThisWorldOnly() {
        // the user stands at the middle of the block above (0, 64, 0)'s, so a block's distance is exact
        double x = 0.5;
        double y = 64.5;
        double z = 0.5;
        Spot near = at(3, 64, 0);
        Spot nearer = at(1, 64, 0);
        Spot mid = at(0, 64, 10);
        Spot edge = at(0, 64, 32);                 // its middle is exactly 32 away: counts
        Spot past = new Spot("world", 0, 64, -33); // 33 away
        Spot otherWorld = new Spot("world_nether", 1, 64, 1);
        List<Spot> all = List.of(edge, past, mid, near, otherWorld, nearer);
        assertEquals(List.of(nearer, near, mid), MegaShow.nearest(all, "world", x, y, z, 32, 3),
                "the three nearest, nearest first, whoever placed them");
        assertEquals(List.of(nearer, near, mid, edge), MegaShow.nearest(all, "world", x, y, z, 32, 10),
                "32 blocks counts; 33 doesn't, nor another world");
        assertEquals(List.of(nearer), MegaShow.nearest(all, "world", x, y, z, 32, 1), "a row of 1 takes one");
        assertEquals(List.of(), MegaShow.nearest(List.of(edge), "world", x, y, z - 0.1, 32, 3),
                "32.1 blocks doesn't count");
        assertEquals(List.of(edge), MegaShow.nearest(List.of(edge), "world", x, y, z, 32, 3));
        assertEquals(List.of(), MegaShow.nearest(List.of(otherWorld), "world", 1.5, 64.5, 1.5, 32, 3),
                "a launcher in another world is never used, however close its coordinates");

        // a tie (both 2 blocks off) is broken by x, then y, then z: the same launchers every time
        Spot east = at(2, 64, 0);
        Spot west = at(-2, 64, 0);
        Spot south = at(0, 64, 2);
        Spot north = at(0, 64, -2);
        List<Spot> ties = new ArrayList<>(List.of(east, west, south, north));
        List<Spot> once = MegaShow.nearest(ties, "world", x, y, z, 32, 3);
        assertEquals(List.of(west, north, south), once);
        for (int i = 0; i < 5; i++) {
            Collections.shuffle(ties, new java.util.Random(i));
            assertEquals(once, MegaShow.nearest(ties, "world", x, y, z, 32, 3), "whatever order the records come in");
        }
    }

    @Test
    void nothingInRangeIsNone() {
        assertEquals(List.of(), MegaShow.nearest(List.of(), "world", 0, 64, 0, 32, 3), "no launcher at all");
        assertEquals(List.of(), MegaShow.nearest(List.of(at(100, 64, 100)), "world", 0, 64, 0, 32, 3),
                "one too far away");
        assertEquals(List.of(), MegaShow.nearest(List.of(at(1, 64, 1)), "world", 0, 64, 0, 32, 0), "a row of none");
        assertEquals(List.of(), MegaShow.nearest(List.of(at(0, 64, 0)), "world", 0, 200, 0, 32, 3),
                "range is in three dimensions: 135 blocks straight up is out of it");
    }

    @Test
    void leftToRightAsTheUserFacesThem() {
        // three launchers in a row along x at z = 10, and three along z at x = 10
        Spot a = at(-4, 64, 10);
        Spot b = at(0, 64, 10);
        Spot c = at(4, 64, 10);
        List<Spot> row = List.of(b, c, a);
        // yaw 0 faces south (+z): east (+x) is on the left
        assertEquals(List.of(c, b, a), MegaShow.leftToRight(row, 0.5, 0.5, 0f), "facing south");
        // yaw 180 faces north (-z): from the other side of the row, west (-x) is on the left
        assertEquals(List.of(a, b, c), MegaShow.leftToRight(row, 0.5, 20.5, 180f), "facing north");
        Spot d = at(10, 64, -4);
        Spot e = at(10, 64, 0);
        Spot f = at(10, 64, 4);
        List<Spot> col = List.of(f, d, e);
        // yaw 270 (or -90) faces east (+x): north (-z) is on the left
        assertEquals(List.of(d, e, f), MegaShow.leftToRight(col, 0.5, 0.5, 270f), "facing east");
        assertEquals(List.of(d, e, f), MegaShow.leftToRight(col, 0.5, 0.5, -90f), "-90 is east too");
        // yaw 90 faces west (-x): from the far side, south (+z) is on the left
        assertEquals(List.of(f, e, d), MegaShow.leftToRight(col, 20.5, 0.5, 90f), "facing west");
        // two straight ahead at once: the nearer one first, so the lanes are always the same
        Spot ahead = at(0, 64, 5);
        Spot further = at(0, 64, 9);
        assertEquals(List.of(ahead, further), MegaShow.leftToRight(List.of(further, ahead), 0.5, 0.5, 0f));
        assertEquals(List.of(), MegaShow.leftToRight(List.of(), 0, 0, 0f));
    }
}
