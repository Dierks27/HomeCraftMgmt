package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.PlanInput;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.games.gen.golf.AdventureKit;
import com.dierks.homecraft.games.gen.golf.GolfPlanner;
import com.dierks.homecraft.games.gen.golf.GolfValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heal path's pond scan through the real engine (Course Variety §1.3; its final gate): a Tiny
 * Golf layout of version 3 with a pond to look at beside its hole — two columns clear of the bounds,
 * as an Easy POND_SIDE draws it — is built, then checked at boot by a planner of version 4, which
 * can't derive it again, so it gets the structural check. The check scans the hole's whole plot in
 * the half the layout stands in and seals a pond by the plan's own test: the sealed pond opens (with
 * the older-version WARN), and a gap in its rim, or a slab in it, keeps the course closed.
 */
class GolfPondHealTest {

    private static final String TINY = Slots.TINY_GOLF.id();

    /** A 5-wide straight: the tee near one end, the cup near the other. */
    private static final String[] STRAIGHT = {
            "#######",
            "#00000#",
            "#00t00#",
            "#00000#",
            "#00000#",
            "#00000#",
            "#00000#",
            "#00c00#",
            "#00000#",
            "#######"};

    private Host host;
    private GenService gen;
    /** The half the planner was last given (the engine's own), so the test never assumes an origin. */
    private Box half;

    @AfterEach
    void tearDown() throws Exception {
        if (gen != null) {
            gen.stop();
        }
        if (host != null) {
            host.connection.close();
        }
    }

    /** The straight in {@code half}'s first plot. */
    private static AdventureKit.Drawn hole(Box half) {
        int[] p = GolfPlanner.plot(half, 0);
        return AdventureKit.draw(p[0], p[1], half.minY() + GolfPlanner.TURF_ABOVE_FLOOR, STRAIGHT);
    }

    /**
     * The pond to look at beside {@code d}: 3 x 4 of still water at T - 1 over blue concrete, from three
     * columns past the bounds, rimmed with moss at T - 1 (as {@code HoleTemplate}'s decorative pond).
     */
    private static List<Map.Entry<int[], String>> pond(AdventureKit.Drawn d, int turf) {
        List<Map.Entry<int[], String>> out = new ArrayList<>();
        int x0 = d.bounds().maxX() + 3;
        int z0 = d.bounds().minZ() + 2;
        for (int x = x0 - 1; x <= x0 + 3; x++) {
            for (int z = z0 - 1; z <= z0 + 4; z++) {
                boolean water = x >= x0 && x <= x0 + 2 && z >= z0 && z <= z0 + 3;
                if (water) {
                    out.add(Map.entry(new int[]{x, turf - 2, z}, "minecraft:blue_concrete"));
                    out.add(Map.entry(new int[]{x, turf - 1, z}, "minecraft:water[level=0]"));
                } else {
                    out.add(Map.entry(new int[]{x, turf - 1, z}, Palette.MOSS));
                }
            }
        }
        return out;
    }

    /** The rim block on the pond's far side, beside its first water column: {x, y, z}. */
    private static int[] rim(Box half) {
        AdventureKit.Drawn d = hole(half);
        int turf = half.minY() + GolfPlanner.TURF_ABOVE_FLOOR;
        return new int[]{d.bounds().maxX() + 6, turf - 1, d.bounds().minZ() + 2};
    }

    /** One hole with a pond to look at, at the version its planner says; it remembers the half it was given. */
    private final class PondViewPlanner implements Planner {
        private final int algo;

        PondViewPlanner(int algo) {
            this.algo = algo;
        }

        @Override
        public String id() {
            return Slots.GOLF;
        }

        @Override
        public int algo() {
            return algo;
        }

        @Override
        public Plan plan(PlanInput in) {
            half = in.half();
            return plan(in.slot(), in.half(), in.seed(), algo);
        }

        Plan plan(Slots.Def slot, Box box, long seed, int version) {
            AdventureKit.Drawn d = hole(box);
            return AdventureKit.plan(slot, box, seed, version, List.of(d), List.of(d.line()),
                    pond(d, box.minY() + GolfPlanner.TURF_ABOVE_FLOOR));
        }

        @Override
        public Plan rederive(PlanInput in, GenTag tag) {
            return plan(in);
        }
    }

    private void boot(int algo) {
        if (gen != null) {
            gen.stop();
        }
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.BOAT, Slots.DROPPER)) {
            planners.put(id, new GenKit.FakePlanner(id));
        }
        planners.put(Slots.GOLF, new PondViewPlanner(algo));
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
    }

    private void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    /** Version 3 builds the layout and opens it: its live tag. */
    private GenTag built() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, TINY);
        boot(3);
        for (int s = 0; s < 30 * 60; s++) {
            drive(1);
            GenTag tag = gen.liveTag(TINY);
            if (tag != null && gen.live(TINY, tag)) {
                assertEquals(3, tag.algo(), "(a version-3 layout)");
                assertNotNull(half, "(the planner was given its half)");
                assertEquals(List.of(), GolfValidator.problems(new PondViewPlanner(3).plan(Slots.TINY_GOLF, half,
                        tag.seed(), 3)), "a sound Adventure layout, its pond to look at included");
                assertEquals(Palette.MOSS, host.world().at(rim(half)[0], rim(half)[1], rim(half)[2]),
                        "(its rim is built)");
                return tag;
            }
        }
        throw new AssertionError("the version-3 layout never opened: " + host.logged(Level.WARNING, ""));
    }

    /** A boot with golf's planner at version 4: the layout can only be checked for its structure. */
    private void healedByAnOlderCheck() {
        boot(4);
        drive(3);
    }

    @Test
    void aSealedPondToLookAtPassesTheStructuralCheckAndAGapInItsRimKeepsTheCourseClosed() {
        GenTag tag = built();
        healedByAnOlderCheck();
        assertTrue(gen.live(TINY, tag), "the sealed pond passes: the layout opens");
        assertEquals(1, host.logged(Level.WARNING, "older version"), "with the older-version WARN");

        int[] r = rim(half);
        host.world().blocks.remove(GenKit.pos(r[0], r[1], r[2])); // a gap in the rim of the pond to look at
        healedByAnOlderCheck();
        assertFalse(gen.live(TINY, tag), "a pond to look at that isn't sealed keeps the course closed");
        assertEquals(1, host.logged(Level.SEVERE, "a pond isn't sealed"), "and the log says why");
    }

    @Test
    void aPondToLookAtWalledByASlabKeepsTheCourseClosed() {
        GenTag tag = built();
        int[] r = rim(half);
        host.world().put(r[0], r[1], r[2], "minecraft:smooth_stone_slab[type=bottom]"); // a slab can hold water
        healedByAnOlderCheck();
        assertFalse(gen.live(TINY, tag), "a slab doesn't seal a pond: the course stays closed");
        assertEquals(1, host.logged(Level.SEVERE, "a pond isn't sealed"), "and the log says why");
    }
}
