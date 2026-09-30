package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.V2Fixtures;
import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.golf.AdventureKit;
import com.dierks.homecraft.games.gen.golf.PlanBlocks;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smooth sandstone plays as sand only where it was meant to (Course Variety §3.4, decision 2): on
 * a generated golf course whose layout the golf planner made at version 3 or later. A hand-built
 * course — and a kept one, which is hand-built from then on — reads every block exactly as it
 * always did, so an owner's own course with a sandstone floor never plays differently; and every
 * older layout's witness line replays on the real blocks either way, since none has any sandstone.
 */
class LiveBlocksSandTest {

    private static final Set<Material> SAND = EnumSet.of(Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE_SLAB);

    private static GenTag tag(String generator, int algo) {
        return new GenTag(Slots.DAILY_GOLF.id(), generator, algo, 20_000, 0, 7, 'A', "h", 0, 0, 0, List.of(),
                List.of(), 0);
    }

    private static GolfCourse course(GenTag gen) {
        return new GolfCourse("c", "C", "w", true, 1, List.of(), gen);
    }

    @Test
    void onlySmoothSandstoneChangesAndOnlyWhenSandPlays() {
        for (Material m : Material.values()) {
            assertEquals(LiveBlocks.surface(m, false), LiveBlocks.surface(m),
                    m + ": a hand-built course reads it as it always did");
            if (!SAND.contains(m)) {
                assertEquals(LiveBlocks.surface(m, false), LiveBlocks.surface(m, true),
                        m + " reads the same with sand on: only smooth sandstone is sand");
            }
        }
        for (Material m : SAND) {
            assertEquals(BallPhysics.Surface.NORMAL, LiveBlocks.surface(m), m + " is stone on a hand-built course");
            assertEquals(BallPhysics.Surface.SLOW, LiveBlocks.surface(m, true), m + " is sand on Adventure Golf");
        }
        for (Material m : List.of(Material.SANDSTONE, Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE_STAIRS,
                Material.CUT_SANDSTONE_SLAB)) {
            assertEquals(BallPhysics.Surface.NORMAL, LiveBlocks.surface(m, true),
                    m + " stays stone even there (the owner's cut-sandstone fallback, C-G0)");
        }
    }

    @Test
    void sandPlaysOnlyOnAGeneratedGolfLayoutOfVersionThreeOrLater() {
        assertFalse(LiveBlocks.sandPlays((GolfCourse) null), "no course, no sand");
        assertFalse(LiveBlocks.sandPlays(course(null)), "a hand-built (or kept) course: never");
        assertFalse(LiveBlocks.sandPlays(course(tag(Slots.GOLF, 2))), "a generated layout of version 2: no");
        assertTrue(LiveBlocks.sandPlays(course(tag(Slots.GOLF, 3))), "Adventure Golf (version 3): yes");
        assertTrue(LiveBlocks.sandPlays(course(tag(Slots.GOLF, 4))), "and every later version");
        assertFalse(LiveBlocks.sandPlays(course(tag(Slots.BOAT, 3))), "only the golf planner's layouts");
        assertEquals(3, LiveBlocks.FIRST_SAND_ALGO, "pinned: golf's sand starts at version 3");
        World w = new BlockWorld().world();
        assertFalse(LiveBlocks.forCourse(w, course(null)).sand(), "a round of a hand-built course reads no sand");
        assertTrue(LiveBlocks.forCourse(w, course(tag(Slots.GOLF, 3))).sand(), "a round of Adventure Golf does");
        assertFalse(new LiveBlocks(w).sand(), "and the reading every other caller makes is the old one");
    }

    /** A lane of lime concrete 3 wide, x 0-40, with smooth sandstone at x 10-14 ({@code sand} false: all concrete). */
    private static BlockWorld strip(boolean sandstone) {
        BlockWorld w = new BlockWorld();
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 2; z++) {
                w.set(x, 63, z, sandstone && x >= 10 && x <= 14 ? Palette.SAND : Palette.TURF_LIGHT);
            }
        }
        return w;
    }

    private static GolfShot.Result putt(BallPhysics.Blocks blocks) {
        BallPhysics.Hole area = BallPhysics.Hole.of(60, 63, 1, -1, 60, -1, 41, 70, 3);
        return GolfShot.play(blocks, area, new BallPhysics.Ball(8.5, 64, 1.5), new Putt(-90, 4));
    }

    @Test
    void aHandBuiltCourseRollsOverSmoothSandstoneExactlyAsOverAnyStone() {
        GolfShot.Result stone = putt(new LiveBlocks(strip(false).world()));
        GolfShot.Result hand = putt(LiveBlocks.forCourse(strip(true).world(), course(null)));
        assertEquals(stone, hand, "a hand-built course's sandstone is just a floor: the same putt, the same rest");
        GolfShot.Result old = putt(LiveBlocks.forCourse(strip(true).world(), course(tag(Slots.GOLF, 2))));
        assertEquals(stone, old, "and on an older generated layout too");
        GolfShot.Result sand = putt(LiveBlocks.forCourse(strip(true).world(), course(tag(Slots.GOLF, 3))));
        assertTrue(sand.x() < stone.x() - 1, "on Adventure Golf the sand slows it: " + sand.x() + " < " + stone.x());
        PlanBlocks model = new PlanBlocks(com.dierks.homecraft.games.gen.api.Box.sized(0, 63, 0, 41, 1, 3));
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 2; z++) {
                model.set(x, 63, z, PlanBlocks.code(x >= 10 && x <= 14 ? Palette.SAND : Palette.TURF_LIGHT));
            }
        }
        assertEquals(sand, putt(model), "exactly as the planner's model rolls it (PlanBlocks' SAND)");
    }

    /** Every block of {@code plan} in a world of its own. */
    private static BlockWorld world(Plan plan) {
        BlockWorld w = new BlockWorld();
        for (BlockOp op : plan.ops()) {
            w.set(op.x(), op.y(), op.z(), plan.palette().get(op.state()));
        }
        return w;
    }

    @Test
    void everyFrozenVersionTwoWitnessReplaysOnTheNewLiveBlocksEitherWay() {
        for (V2Fixtures.Fixture f : V2Fixtures.golf()) {
            BlockWorld w = world(f.plan());
            List<GolfCourse.Hole> holes = f.golfCourse().course().holes();
            for (boolean sand : new boolean[]{false, true}) {
                for (int i = 0; i < holes.size(); i++) {
                    List<Putt> line = f.tag().witness().get(i);
                    GolfShot.Replay r = GolfShot.replay(new LiveBlocks(w.world(), sand), holes.get(i), line);
                    assertTrue(r.holed() && r.putts() == line.size() && r.strokes() == line.size(),
                            f + " hole " + (i + 1) + (sand ? " (sand on)" : " (sand off)")
                                    + ": the stored line holes out in exactly its putts on the real blocks");
                }
            }
            assertTrue(f.plan().palette().stream().noneMatch(p -> p.contains("sandstone")),
                    f + ": (and it has no sandstone at all, so sand can't move it)");
        }
    }

    @Test
    void anAdventureLineWithSandReplaysWhereSandPlaysAndTheReadingMatters() {
        AdventureKit.Drawn d = AdventureKit.draw(0,
                "#######",
                "#00000#",
                "#00t00#",
                "#00000#",
                "#00000#",
                "#sssss#",
                "#sssss#",
                "#sssss#",
                "#sssss#",
                "#00000#",
                "#00000#",
                "#00c00#",
                "#00000#",
                "#######");
        List<Putt> line = d.line();
        assertNotNull(line, "the hole has a line");
        BlockWorld w = new BlockWorld();
        for (Map.Entry<int[], String> e : d.placed()) {
            w.set(e.getKey()[0], e.getKey()[1], e.getKey()[2], e.getValue());
        }
        GolfCourse.Hole h = d.hole(line.size() + 1);
        GolfShot.Replay sand = GolfShot.replay(new LiveBlocks(w.world(), true), h, line);
        assertTrue(sand.holed() && sand.strokes() == line.size(),
                "the build's replay with sand (a generated algo-3 layout) holes the planner's line: " + line);
        BallPhysics.Ball onSand = GolfShot.tee(new LiveBlocks(w.world(), true), h);
        BallPhysics.Ball onStone = GolfShot.tee(new LiveBlocks(w.world(), false), h);
        GolfShot.Result a = GolfShot.play(new LiveBlocks(w.world(), true), GolfShot.area(new LiveBlocks(w.world(),
                true), h), onSand, line.get(0));
        GolfShot.Result b = GolfShot.play(new LiveBlocks(w.world(), false), GolfShot.area(new LiveBlocks(w.world(),
                false), h), onStone, line.get(0));
        assertNotEquals(a, b, "read as stone the same putt rolls differently: the reading matters, so the build"
                + " reads a generated algo-3 layout with sand, as its rounds do");
    }
}
