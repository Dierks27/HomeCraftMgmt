package com.dierks.homecraft.graves;

import com.dierks.homecraft.graves.GravePlacement.Cell;
import com.dierks.homecraft.graves.GravePlacement.Spot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a grave goes (HCM-GRAVES §2), each unsafe case in turn, against a small made-up world: stone up to y 63,
 * air above, unless a test says otherwise.
 */
class GravePlacementTest {

    private static final int MIN = -64;
    private static final int MAX = 320;

    /** A world: a default by height, and single cells set over it. */
    private static final class World implements GravePlacement.Grid {
        final Map<Long, Cell> cells = new HashMap<>();
        int groundTop = 63; // stone up to and including this row
        boolean voidWorld;

        @Override
        public Cell at(int x, int y, int z) {
            Cell c = cells.get(key(x, y, z));
            if (c != null) {
                return c;
            }
            if (voidWorld) {
                return Cell.AIR;
            }
            return y <= groundTop ? Cell.SOLID : Cell.AIR;
        }

        World set(int x, int y, int z, Cell c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        World fill(int x0, int y0, int z0, int x1, int y1, int z1, Cell c) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, c);
                    }
                }
            }
            return this;
        }

        static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }
    }

    private static Spot find(World w, int x, int y, int z) {
        return GravePlacement.find(w, x, y, z, MIN, MAX);
    }

    @Test
    void onOpenGroundTheGraveIsWhereThePlayerFell() {
        assertEquals(new Spot(5, 64, 5), find(new World(), 5, 64, 5));
    }

    @Test
    void grassFlowersAndSnowAreReplaced() {
        World w = new World().set(5, 64, 5, Cell.SOFT);
        assertEquals(new Spot(5, 64, 5), find(w, 5, 64, 5), "a flower at the spot is replaced");
    }

    @Test
    void waterIsASafeSpot() {
        World w = new World().fill(0, 60, 0, 10, 66, 10, Cell.WATER);
        assertEquals(new Spot(5, 62, 5), find(w, 5, 62, 5), "a drowned player's grave is where they drowned");
        assertEquals(new Spot(5, 66, 5), find(w, 5, 66, 5), "at the surface too: a player can swim to it");
    }

    @Test
    void insideABlockItGoesUpFirst() {
        World w = new World().fill(5, 64, 5, 5, 66, 5, Cell.SOLID); // a 3-high sand column the player choked in
        assertEquals(new Spot(5, 67, 5), find(w, 5, 64, 5), "the first cell above the column");
    }

    @Test
    void inATwoHighTunnelTheSpotItselfIsGood() {
        World w = new World();
        w.groundTop = 80; // solid rock with a tunnel carved at y 64-65
        w.fill(0, 64, 5, 20, 65, 5, Cell.AIR);
        assertEquals(new Spot(10, 64, 5), find(w, 10, 64, 5));
    }

    @Test
    void inMidAirItFallsToTheGround() {
        assertEquals(new Spot(5, 64, 5), find(new World(), 5, 140, 5), "an arrow while jumping off a cliff");
    }

    @Test
    void inMidAirOverWaterItStopsAtTheWater() {
        World w = new World().fill(0, 60, 0, 10, 63, 10, Cell.WATER);
        Spot s = find(w, 5, 100, 5);
        assertNotNull(s);
        assertEquals(63, s.y(), "the top of the water: a cell a player can swim to");
    }

    @Test
    void inALavaPoolItGoesOnTheShore() {
        World w = new World().fill(0, 60, 0, 6, 63, 6, Cell.LAVA); // a 7x7 pool, its surface at 63
        Spot s = find(w, 3, 62, 3);
        assertNotNull(s);
        assertNotEquals(Cell.LAVA, w.at(s.x(), s.y(), s.z()), "never in the lava");
        assertEquals(Cell.SOLID, w.at(s.x(), s.y() - 1, s.z()), "on ground a player can stand on");
        assertEquals(64, s.y(), "on the shore's top: the nearest dry ground, up first");
    }

    @Test
    void aLavaSourceAtTheFeetGoesNextToIt() {
        World w = new World().set(5, 64, 5, Cell.LAVA);
        Spot s = find(w, 5, 64, 5);
        assertNotNull(s);
        assertNotEquals(new Spot(5, 64, 5), s);
        assertEquals(64, s.y());
        assertTrue(Math.abs(s.x() - 5) + Math.abs(s.z() - 5) == 1, "right beside it");
    }

    @Test
    void anOceanOfLavaStillGetsAGrave() {
        World w = new World().fill(-20, 40, -20, 20, 63, 20, Cell.LAVA);
        Spot s = find(w, 0, 63, 0);
        assertNotNull(s, "a grave is still made (restore reaches it from anywhere)");
        assertEquals(Cell.AIR, w.at(s.x(), s.y(), s.z()), "over the lava, never in it, when anything else is there");
    }

    @Test
    void atTheBuildLimitItComesDown() {
        assertEquals(new Spot(5, 64, 5), find(new World(), 5, 400, 5), "above the limit: down to the top row, then the ground");
        World w = new World();
        w.groundTop = MAX - 2; // solid right up to the row below the top
        assertEquals(new Spot(5, MAX - 1, 5), find(w, 5, MAX + 10, 5), "the top row itself");
    }

    @Test
    void belowTheWorldItComesUp() {
        // The void is the caller's (last safe spot, else spawn); a spot below the floor is still brought inside.
        World w = new World();
        w.groundTop = MIN + 4;
        assertEquals(new Spot(5, MIN + 5, 5), find(w, 5, MIN - 30, 5), "up from the bottom row to the first air");
    }

    @Test
    void anEmptyColumnOverTheVoidLooksAround() {
        World w = new World();
        w.voidWorld = true;
        w.fill(8, 99, 0, 8, 99, 0, Cell.SOLID); // one block of a sky island 8 away
        w.fill(3, 99, 0, 3, 99, 0, Cell.SOLID);
        Spot s = find(w, 0, 100, 0);
        assertEquals(new Spot(3, 100, 0), s, "the nearest cell with something under it");
        World empty = new World();
        empty.voidWorld = true;
        assertNotNull(find(empty, 0, 100, 0), "nothing to stand on anywhere: still somewhere, floating");
    }

    @Test
    void neverReplacesAContainerASignABedAPortalOrAnHcmBlock() {
        // The caller marks these NEVER (or SOLID): a chest where the player fell is never replaced.
        World w = new World().set(5, 64, 5, Cell.NEVER);
        Spot s = find(w, 5, 64, 5);
        assertNotNull(s);
        assertEquals(new Spot(5, 65, 5), s, "on top of it, up first");
        World full = new World().fill(-10, 64, -10, 10, 100, 10, Cell.NEVER);
        assertNull(find(full, 0, 64, 0), "nowhere at all: null, and the drops stay on the ground");
    }

    @Test
    void anotherGraveIsNeverReplaced() {
        World w = new World().set(5, 64, 5, Cell.NEVER); // a grave already there (the caller marks it)
        Spot s = find(w, 5, 64, 5);
        assertNotEquals(new Spot(5, 64, 5), s);
    }

    @Test
    void scoreSearchesUpBeforeDown() {
        assertTrue(GravePlacement.score(0, 2, 0) < GravePlacement.score(0, -2, 0));
        assertEquals(0, GravePlacement.score(0, 0, 0));
    }
}
