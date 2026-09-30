package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.VoidWorld;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout this version SHIPS (LAYOUT-SPEC §1.5-§1.7), read from the code's defaults and the bundled
 * config.yml: the spec's table exactly; not one pair of places in sight of each other at view distance
 * 32 (Paper's largest) for all 26 halves, the Clubhouse, the arena and a keep area at its largest (100
 * plots), which a brute force over every chunk agrees with; every half in the number range the golf
 * ball and the pilots were proven in (x, z 4096..8191); every box on the chunk grid; nothing
 * overlapping or within the build rule's 32 blocks; nothing near a 0.35 spot or the world's spawn.
 * A new place or a moved default has to pass all of it.
 */
class ShippedLayoutTest {

    private static final int BIG_KEEP = 100;

    /** Every shipped place by name: each course's and Classic's halves, the extras, and {@code plots} plots. */
    static Map<String, Box> shipped(int plots) {
        Map<String, Box> out = new LinkedHashMap<>();
        List<Slots.Def> all = new ArrayList<>(Slots.ALL);
        all.addAll(Slots.CLASSICS);
        for (Slots.Def d : all) {
            for (char h : new char[]{'A', 'B'}) {
                out.put(d.id() + ":" + h, d.half(h));
            }
        }
        out.put("clubhouse", ClubhouseSettings.defaults().box());
        out.put("falling_floors", FallingFloorsSettings.defaults().box());
        KeepArea k = DailySettings.Archive.shipped().keep();
        KeepArea keep = new KeepArea(k.x(), k.y(), k.z(), plots, k.gap());
        for (int n = 1; n <= plots; n++) {
            out.put("plot " + n, keep.plot(n));
        }
        return out;
    }

    private static List<Sight.Spot> spots(Map<String, Box> layout) {
        List<Sight.Spot> out = new ArrayList<>();
        layout.forEach((name, box) -> out.add(new Sight.Spot(name, "games", box, Sight.REACH)));
        return out;
    }

    private static int bruteInSight(Map<String, Box> layout, int view) {
        int n = 0;
        for (Map.Entry<String, Box> p : layout.entrySet()) {
            for (Map.Entry<String, Box> q : layout.entrySet()) {
                if (!p.getKey().equals(q.getKey()) && LayoutSightTest.brute(p.getValue(), q.getValue(), view)) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void theShippedSpotsAreTheSpecsTableAndConfigYmlSaysTheSame() {
        for (Map.Entry<Slots.Def, int[]> e : LayoutSightTest.NEW.entrySet()) {
            assertArrayEquals(e.getValue(), e.getKey().origin(), e.getKey().id() + ": §1.5's origin");
        }
        assertEquals(13, LayoutSightTest.NEW.size(), "nine courses and four Classics");
        assertEquals(LayoutSightTest.NEW_CLUBHOUSE, ClubhouseSettings.defaults().box(), "the Clubhouse's box");
        assertEquals(LayoutSightTest.NEW_ARENA, FallingFloorsSettings.defaults().box(), "the arena's box");
        assertEquals(LayoutSightTest.NEW_KEEP, DailySettings.Archive.shipped().keep(), "24 plots from x 1760, z 7296");
        assertEquals(Sight.GAP, Slots.HALF_GAP, "a course's halves stand 576 apart by default");
        assertEquals(Sight.GAP, KeepArea.DEFAULT_GAP, "and so do kept plots");
        assertEquals(576, Sight.GAP, "36 chunk columns");
        assertEquals(32, Regions.APART, "the build rule stays 32: sight is a WARN, never a refusal");
        Map<String, List<Box>> read = LayoutFixtures.shippedPlaces();
        Map<String, Box> code = shipped(24);
        for (Slots.Def d : LayoutSightTest.NEW.keySet()) {
            assertEquals(List.of(code.get(d.id() + ":A"), code.get(d.id() + ":B")), read.get(d.id()),
                    d.id() + ": the bundled config.yml reads as the code's default");
        }
        assertEquals(List.of(code.get("clubhouse")), read.get("clubhouse"), "the Clubhouse from config.yml");
        assertEquals(List.of(code.get("falling_floors")), read.get("falling_floors"), "the arena from config.yml");
        assertEquals(24, read.get("keep").size(), "24 plots shipped");
        for (int n = 1; n <= 24; n++) {
            assertEquals(code.get("plot " + n), read.get("keep").get(n - 1), "plot " + n + " from config.yml");
        }
    }

    @Test
    void nothingShippedIsInSightOfAnythingElseAtViewDistance32EvenWithAHundredKeptPlots() {
        Map<String, Box> layout = shipped(BIG_KEEP);
        assertEquals(26 + 2 + BIG_KEEP, layout.size(), "26 halves, 2 extras and 100 plots");
        assertEquals(List.of(), Sight.pairs(spots(layout), Sight.DESIGN_VIEW), "no pair in sight at 32");
        assertEquals(0, bruteInSight(layout, Sight.DESIGN_VIEW), "the brute force agrees: no chunk of one is sent"
                + " to anyone on another");
        assertEquals(0, bruteInSight(layout, Sight.DESIGN_VIEW + 2), "two chunks to spare");
        assertTrue(bruteInSight(layout, Sight.DESIGN_VIEW + 3) > 0, "and no more: the closest are exactly 36 apart");
        assertEquals(36, Sight.nearest(spots(layout)).chunks(), "the closest two places are 36 chunks apart");
    }

    @Test
    void everyHalfStaysInTheNumberRangeAndEveryBoxOnTheChunkGrid() {
        Map<String, Box> layout = shipped(BIG_KEEP);
        for (Map.Entry<String, Box> e : layout.entrySet()) {
            Box b = e.getValue();
            assertTrue(b.minX() % 16 == 0 && b.minZ() % 16 == 0 && (b.maxX() + 1) % 16 == 0
                    && (b.maxZ() + 1) % 16 == 0, e.getKey() + " is chunk-aligned: " + b.describe());
            assertTrue(b.minY() >= 128 && b.maxY() <= 303, e.getKey() + " keeps 0.35's heights: " + b.describe());
            if (e.getKey().contains(":")) {
                assertTrue(b.minX() >= 4096 && b.maxX() <= 8191 && b.minZ() >= 4096 && b.maxZ() <= 8191,
                        e.getKey() + " stays in x, z 4096..8191: " + b.describe());
            }
        }
        for (Map.Entry<String, Box> e : shipped(24).entrySet()) {
            Box b = e.getValue();
            assertTrue(b.minX() >= 1760 && b.maxX() <= 8191 && b.minZ() >= 4096 && b.maxZ() <= 10367,
                    e.getKey() + " is inside the owner's footprint x 1760..8191, z 4096..10367: " + b.describe());
        }
    }

    @Test
    void noTwoPlacesOverlapOrComeWithinTheBuildRule() {
        List<Map.Entry<String, Box>> all = new ArrayList<>(shipped(BIG_KEEP).entrySet());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Box a = all.get(i).getValue();
                Box b = all.get(j).getValue();
                assertFalse(a.intersects(b), all.get(i).getKey() + " and " + all.get(j).getKey() + " don't meet");
                assertTrue(a.gap(b) >= Sight.GAP, all.get(i).getKey() + " and " + all.get(j).getKey() + " are "
                        + Sight.GAP + " or more apart (" + a.gap(b) + ")");
            }
        }
    }

    @Test
    void theShippedLayoutPassesEveryBuildCheckInsideABorder21000Across() {
        List<String> warns = new ArrayList<>();
        LayoutFixtures.parse(LayoutFixtures.bundled(), warns);
        assertEquals(List.of(), warns, "the bundled block reads without a WARN");
        DailySettings d = DailySettings.defaults();
        List<DailySettings.SlotConfig> on = new ArrayList<>();
        d.slots().forEach(c -> on.add(c.withEnabled(true)));
        d.archive().classics().forEach(c -> on.add(c.withEnabled(true)));
        assertEquals(on, Regions.validate(on, warns::add, "games.fresh.slots"), "every course and Classic, all on, fits");
        Regions.WorldFacts w = new Regions.WorldFacts("games", true, -64, 320, new Box(-10_500, -64, -10_500,
                10_499, 319, 10_499), new int[]{0, 100, 0}, null);
        for (DailySettings.SlotConfig c : on) {
            assertEquals(List.of(), Regions.worldProblems(c.def(), c.origin(), c.halfGap(), w),
                    c.id() + " fits a Games world whose border is 21,000 across");
        }
        assertEquals(Map.of(), Regions.keepWorldProblems(d.archive().keep(), w), "so do the 24 shipped plots");
        KeepArea hundred = new KeepArea(d.archive().keep().x(), d.archive().keep().y(), d.archive().keep().z(),
                BIG_KEEP, d.archive().keep().gap());
        assertTrue(Regions.keepWorldProblems(hundred, w).containsKey(BIG_KEEP),
                "plot 100 reaches z 22223: past that border, and the per-plot check says so");
        assertNull(d.archive().keep().problem(new ArrayList<>(shipped(24).entrySet().stream()
                        .filter(e -> e.getKey().contains(":")).map(Map.Entry::getValue).toList())),
                "the keep area is clear of every half");
    }

    @Test
    void nothingShippedMeetsOrSeesA035SpotOrTheWorldsSpawn() {
        Map<String, Box> old = LayoutSightTest.legacyLayout();
        for (Map.Entry<String, Box> n : shipped(BIG_KEEP).entrySet()) {
            for (Map.Entry<String, Box> o : old.entrySet()) {
                assertFalse(n.getValue().intersects(o.getValue()), n.getKey() + " is clear of 0.35's " + o.getKey());
                int dist = Math.min(Sight.chunksApart(n.getValue(), Sight.REACH, o.getValue()),
                        Sight.chunksApart(o.getValue(), Sight.REACH, n.getValue()));
                assertTrue(dist >= 34, n.getKey() + " is " + dist + " chunks from 0.35's " + o.getKey());
            }
            for (VoidWorld.Block b : VoidWorld.platform()) {
                Box at = new Box(b.x(), b.y(), b.z(), b.x(), b.y(), b.z());
                assertTrue(Sight.chunksApart(at, Sight.REACH, n.getValue()) >= 100, n.getKey()
                        + " is far from the void world's spawn platform");
            }
        }
    }
}
