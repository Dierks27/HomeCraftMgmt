package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world as {@link BallPhysics} sees it: each block's real collision shape (so a slab, a
 * stair's low half, a carpet and a fence post are what they look like) and what it is made of.
 * The one thin piece between the tested physics and the server; nothing here is worth testing
 * without one.
 *
 * <p>A block in a chunk that isn't loaded reads as nothing: the ball falls, goes out of bounds and
 * comes back to its last spot, rather than a lookup loading the chunk. One is made per ball per
 * tick and remembers each block it read, since a tick's sub-steps ask about the same few blocks
 * many times.
 *
 * <p>Public for Fresh Courses (GEN-SPEC §4.3): its golf builds replay each hole's witness line on
 * these, the real blocks, and its planner's block model is checked against {@link #surface}.
 *
 * <p><b>Sand (Course Variety §3.4, decision 2).</b> Adventure Golf's bunkers are smooth sandstone,
 * which plays as sand ({@code SLOW}) only on a GENERATED golf course of golf planner version
 * {@value #FIRST_SAND_ALGO} or later ({@link #sandPlays}): the ones whose par, witness line and
 * sloppy-player proof were worked out with sand. Everywhere else — every hand-built course, a kept
 * course (it is a hand-built one from then on), a layout of an older version — smooth sandstone is
 * any stone, exactly as before, so a course someone built by hand never plays differently. So a
 * reading is made either way ({@link #LiveBlocks(World, boolean)}), and a round picks its course's
 * ({@link #forCourse}).
 */
public final class LiveBlocks implements BallPhysics.Blocks {

    /** The first golf planner version whose smooth sandstone plays as sand (Course Variety §3.4). */
    public static final int FIRST_SAND_ALGO = 3;

    private static final double EDGE = 1e-6;

    /** A block as read once: its material and its solid boxes (none when passable). */
    private record Cell(Material type, Collection<BoundingBox> boxes) {
    }

    private static final Cell NOTHING = new Cell(Material.AIR, List.of());

    private final World world;
    /** Whether smooth sandstone is sand here ({@link #sandPlays}). */
    private final boolean sand;
    private final Map<Long, Cell> seen = new HashMap<>();

    /** The world as a hand-built course sees it: smooth sandstone is any stone. */
    public LiveBlocks(World world) {
        this(world, false);
    }

    /**
     * The world as a course sees it: with {@code sand}, smooth sandstone (full or slab) is sand
     * ({@code SLOW}); without, it is any stone, as it always was.
     */
    public LiveBlocks(World world, boolean sand) {
        this.world = world;
        this.sand = sand;
    }

    /** The world as {@code course} plays it: sand only when {@link #sandPlays} says so. */
    public static LiveBlocks forCourse(World world, GolfCourse course) {
        return new LiveBlocks(world, sandPlays(course));
    }

    /**
     * Whether smooth sandstone plays as sand on {@code course}: only a generated one (a Fresh
     * Courses golf slot, a Classics recall) whose layout the golf planner made at version
     * {@value #FIRST_SAND_ALGO} or later. A hand-built or kept course never ({@code null} neither).
     */
    public static boolean sandPlays(GolfCourse course) {
        return course != null && course.generated() && Slots.GOLF.equals(course.gen().generator())
                && sandPlays(course.gen().algo());
    }

    /** Whether a golf layout made at planner version {@code algo} has sand ({@value #FIRST_SAND_ALGO} on). */
    public static boolean sandPlays(int algo) {
        return algo >= FIRST_SAND_ALGO;
    }

    /** Whether this reading plays smooth sandstone as sand. */
    public boolean sand() {
        return sand;
    }

    @Override
    public double top(int x, int y, int z, double px, double pz) {
        Cell c = cell(x, y, z);
        if (c.boxes().isEmpty()) {
            return NONE;
        }
        double fx = px - x;
        double fz = pz - z;
        double best = NONE;
        for (BoundingBox box : c.boxes()) {
            if (fx >= box.getMinX() - EDGE && fx <= box.getMaxX() + EDGE
                    && fz >= box.getMinZ() - EDGE && fz <= box.getMaxZ() + EDGE && box.getMaxY() > best) {
                best = box.getMaxY();
            }
        }
        return best;
    }

    @Override
    public BallPhysics.Surface surface(int x, int y, int z) {
        return surface(cell(x, y, z).type(), sand);
    }

    /** What a block is to the ball on a hand-built course (smooth sandstone is any stone). */
    public static BallPhysics.Surface surface(Material m) {
        return surface(m, false);
    }

    /**
     * What a block is to the ball; with {@code sand} ({@link #sandPlays}), smooth sandstone and its
     * slab are sand, as slow as soul sand.
     */
    public static BallPhysics.Surface surface(Material m, boolean sand) {
        return switch (m) {
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE -> BallPhysics.Surface.ICE;
            case SOUL_SAND, SOUL_SOIL, HONEY_BLOCK -> BallPhysics.Surface.SLOW;
            case SMOOTH_SANDSTONE, SMOOTH_SANDSTONE_SLAB -> sand ? BallPhysics.Surface.SLOW
                    : BallPhysics.Surface.NORMAL;
            case SLIME_BLOCK -> BallPhysics.Surface.SLIME;
            case WATER, BUBBLE_COLUMN, SEAGRASS, TALL_SEAGRASS, KELP, KELP_PLANT -> BallPhysics.Surface.WATER;
            case LAVA -> BallPhysics.Surface.LAVA;
            default -> BallPhysics.Surface.NORMAL;
        };
    }

    private Cell cell(int x, int y, int z) {
        long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        Cell c = seen.get(key);
        if (c == null) {
            c = read(x, y, z);
            seen.put(key, c);
        }
        return c;
    }

    private Cell read(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return NOTHING;
        }
        Block b = world.getBlockAt(x, y, z);
        return new Cell(b.getType(), b.isPassable() ? List.of() : b.getCollisionShape().getBoundingBoxes());
    }
}
