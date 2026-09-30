package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout that keeps every place out of every other's sight (LAYOUT-SPEC §1.5-§1.6), checked with
 * {@link Sight} and against a brute force: nothing is in sight of anything at view distance 32, the
 * closest two places are 36 chunks apart, every half stays in the number range the golf ball and the
 * pilots were proven in (x, z 4096..8191), and nothing meets or sees a 0.35 spot.
 *
 * <p>The new spots are the spec's table, written out here; they are not the shipped ones yet (that
 * switch comes with its config migration), so this pins the table itself. The 0.35 layout, measured
 * the same way, has exactly the pairs in sight the spec counted (312 at view distance 10, 844 at 32):
 * the check and the spec's arithmetic agree.
 */
class LayoutSightTest {

    /** The spec's table: each slot's and Classic's new origin. */
    static final Map<Slots.Def, int[]> NEW = new LinkedHashMap<>();

    static {
        int w = 6080;
        int e = 7488;
        NEW.put(Slots.SKY_RINGS, new int[]{w, 128, 4096});
        NEW.put(Slots.CLASSIC_RINGS, new int[]{w, 128, 4992});
        NEW.put(Slots.ICE_BOAT, new int[]{w, 160, 5888});
        NEW.put(Slots.DAILY_PARKOUR_EASY, new int[]{w, 160, 6592});
        NEW.put(Slots.DAILY_PARKOUR_MEDIUM, new int[]{w, 160, 7232});
        NEW.put(Slots.DAILY_PARKOUR_HARD, new int[]{w, 160, 7872});
        NEW.put(Slots.DAILY_GOLF, new int[]{e, 160, 4096});
        NEW.put(Slots.CLASSIC_GOLF, new int[]{e, 160, 4800});
        NEW.put(Slots.TINY_GOLF, new int[]{e, 160, 5504});
        NEW.put(Slots.CLASSIC_PARKOUR, new int[]{e, 160, 6128});
        NEW.put(Slots.EASY_DROPPER, new int[]{e, 160, 6768});
        NEW.put(Slots.FRESH_DROPPER, new int[]{e, 160, 7360});
        NEW.put(Slots.CLASSIC_DROPPER, new int[]{e, 160, 7952});
    }

    static final Box NEW_CLUBHOUSE = Box.sized(6080, 160, 8544, 32, 16, 32);
    static final Box NEW_ARENA = Box.sized(6688, 176, 8544, 48, 40, 48);
    static final KeepArea NEW_KEEP = new KeepArea(1760, 128, 7296, 24, Sight.GAP);

    /** Every box of the new layout by name: 26 halves, the Clubhouse, the arena and 24 plots. */
    static Map<String, Box> newLayout() {
        Map<String, Box> out = new LinkedHashMap<>();
        for (Map.Entry<Slots.Def, int[]> e : NEW.entrySet()) {
            int[] o = e.getValue();
            for (char h : new char[]{'A', 'B'}) {
                out.put(e.getKey().id() + ":" + h, e.getKey().half(o[0], o[1], o[2], h, Sight.GAP));
            }
        }
        out.put("clubhouse", NEW_CLUBHOUSE);
        out.put("falling_floors", NEW_ARENA);
        for (int n = 1; n <= NEW_KEEP.maxPlots(); n++) {
            out.put("plot " + n, NEW_KEEP.plot(n));
        }
        return out;
    }

    /** The same at 0.35's spots. */
    static Map<String, Box> legacyLayout() {
        Map<String, Box> out = new LinkedHashMap<>();
        for (Slots.Def d : NEW.keySet()) {
            for (char h : new char[]{'A', 'B'}) {
                out.put(d.id() + ":" + h, LegacyBoxes.half(d, h));
            }
        }
        out.put("clubhouse", LegacyBoxes.clubhouse());
        out.put("falling_floors", LegacyBoxes.fallingFloors());
        KeepArea keep = LegacyBoxes.keep();
        for (int n = 1; n <= keep.maxPlots(); n++) {
            out.put("plot " + n, keep.plot(n));
        }
        return out;
    }

    private static List<Sight.Spot> spots(Map<String, Box> layout) {
        List<Sight.Spot> out = new ArrayList<>();
        layout.forEach((name, box) -> out.add(new Sight.Spot(name, "games", box, Sight.REACH)));
        return out;
    }

    /** Ordered pairs (P, Q) where a player within reach of P is sent a chunk of Q, by {@link Sight}. */
    private static int orderedInSight(Map<String, Box> layout, int view) {
        int n = 0;
        for (Map.Entry<String, Box> p : layout.entrySet()) {
            for (Map.Entry<String, Box> q : layout.entrySet()) {
                if (!p.getKey().equals(q.getKey()) && Sight.inSight(p.getValue(), Sight.REACH, q.getValue(), view)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The same count, walking every chunk a player can stand in and every chunk sent to them. */
    private static int bruteInSight(Map<String, Box> layout, int view) {
        int n = 0;
        for (Map.Entry<String, Box> p : layout.entrySet()) {
            for (Map.Entry<String, Box> q : layout.entrySet()) {
                if (!p.getKey().equals(q.getKey()) && brute(p.getValue(), q.getValue(), view)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean brute(Box on, Box seen, int view) {
        int r = Sight.REACH;
        for (int cx = Math.floorDiv(on.minX() - r, 16); cx <= Math.floorDiv(on.maxX() + r, 16); cx++) {
            for (int cz = Math.floorDiv(on.minZ() - r, 16); cz <= Math.floorDiv(on.maxZ() + r, 16); cz++) {
                // the chunks sent to a player in chunk (cx, cz): |dx|, |dz| <= V + 1; any of seen's among them?
                boolean xs = Math.floorDiv(seen.minX(), 16) <= cx + view + 1
                        && Math.floorDiv(seen.maxX(), 16) >= cx - view - 1;
                boolean zs = Math.floorDiv(seen.minZ(), 16) <= cz + view + 1
                        && Math.floorDiv(seen.maxZ(), 16) >= cz - view - 1;
                if (xs && zs) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void nothingOfTheNewLayoutIsInSightOfAnythingElseAtViewDistance32() {
        Map<String, Box> layout = newLayout();
        assertEquals(52, layout.size(), "26 halves, the Clubhouse, the arena and 24 plots");
        assertEquals(List.of(), Sight.pairs(spots(layout), Sight.DESIGN_VIEW), "no pair in sight at 32");
        assertEquals(0, orderedInSight(layout, Sight.DESIGN_VIEW), "not one way round either");
        assertEquals(0, bruteInSight(layout, Sight.DESIGN_VIEW), "and the brute force agrees: nothing sent anywhere");
        assertEquals(0, bruteInSight(layout, 34), "clear up to 34: two chunks to spare");
        assertTrue(bruteInSight(layout, 35) > 0, "and at 35 the closest ones would show: the gap is exactly 36");
        Sight.Pair near = Sight.nearest(spots(layout));
        assertEquals(36, near.chunks(), "the closest two places are 36 chunks apart");
    }

    @Test
    void everyLiveHalfIs36ChunksFromItsOwnSpareHalf() {
        for (Map.Entry<Slots.Def, int[]> e : NEW.entrySet()) {
            int[] o = e.getValue();
            Box a = e.getKey().half(o[0], o[1], o[2], 'A', Sight.GAP);
            Box b = e.getKey().half(o[0], o[1], o[2], 'B', Sight.GAP);
            assertEquals(36, Sight.chunksApart(a, Sight.REACH, b), e.getKey().id() + ": A to B");
            assertEquals(36, Sight.chunksApart(b, Sight.REACH, a), e.getKey().id() + ": B to A");
        }
    }

    @Test
    void theNewLayoutFitsTheNumberRangeTheChunkGridAndTheWorld() {
        Map<String, Box> layout = newLayout();
        List<Box> all = new ArrayList<>(layout.values());
        for (Map.Entry<String, Box> e : layout.entrySet()) {
            Box b = e.getValue();
            assertTrue(b.minX() % 16 == 0 && b.minZ() % 16 == 0 && (b.maxX() + 1) % 16 == 0
                    && (b.maxZ() + 1) % 16 == 0, e.getKey() + " is chunk-aligned: " + b.describe());
            if (e.getKey().contains(":")) {
                assertTrue(b.minX() >= 4096 && b.maxX() <= 8191 && b.minZ() >= 4096 && b.maxZ() <= 8191,
                        e.getKey() + " stays in x, z 4096..8191, where every 0.35 half was: " + b.describe());
            }
            assertTrue(b.minX() >= 1760 && b.maxX() <= 8191 && b.minZ() >= 4096 && b.maxZ() <= 10367
                    && b.minY() >= 128 && b.maxY() <= 303, e.getKey() + " is inside the footprint x 1760..8191,"
                    + " z 4096..10367, y 128..303: " + b.describe());
            assertTrue(Math.max(Math.abs(b.minX()), Math.abs(b.maxX() + 1)) <= 10_500
                    && Math.max(Math.abs(b.minZ()), Math.abs(b.maxZ() + 1)) <= 10_500,
                    e.getKey() + " fits inside a world border 21,000 across");
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertFalse(all.get(i).intersects(all.get(j)), "no two new boxes meet: " + all.get(i).describe()
                        + " / " + all.get(j).describe());
                assertTrue(all.get(i).gap(all.get(j)) >= Regions.APART, "and each keeps the build rule's "
                        + Regions.APART + " blocks");
            }
        }
        Box spawn = new Box(0, 100, 0, 0, 100, 0);
        for (Box b : all) {
            assertTrue(Sight.chunksApart(spawn, 0, b) >= 128, "the world's spawn at 0,0 is far away: " + b.describe());
        }
    }

    @Test
    void nothingNewMeetsOrSeesAnOldSpotAtViewDistance32() {
        for (Map.Entry<String, Box> n : newLayout().entrySet()) {
            for (Map.Entry<String, Box> o : legacyLayout().entrySet()) {
                assertFalse(n.getValue().intersects(o.getValue()), n.getKey() + " is clear of 0.35's " + o.getKey());
                int d = Math.min(Sight.chunksApart(n.getValue(), Sight.REACH, o.getValue()),
                        Sight.chunksApart(o.getValue(), Sight.REACH, n.getValue()));
                assertTrue(d >= 34, n.getKey() + " is " + d + " chunks from 0.35's " + o.getKey()
                        + ": an old spot never shows from a new one, even before it is tidied");
            }
        }
    }

    @Test
    void the035LayoutHasExactlyThePairsInSightTheSpecCounted() {
        Map<String, Box> old = legacyLayout();
        assertEquals(312, orderedInSight(old, 10), "0.35 at view distance 10 (LAYOUT-SPEC §1.6)");
        assertEquals(844, orderedInSight(old, Sight.DESIGN_VIEW), "and at 32");
        assertEquals(312, bruteInSight(old, 10), "the brute force counts the same at 10");
        assertEquals(844, bruteInSight(old, Sight.DESIGN_VIEW), "and at 32");
        assertEquals(0, Sight.nearest(spots(old)).chunks(), "0.35's kept plots touch");
    }
}
