package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Palette;
import com.dierks.homecraft.games.golf.BallPhysics;

import java.util.List;
import java.util.Locale;

/**
 * The blocks of a golf plan as the ball sees them (GEN-SPEC §4.3): a dense byte grid over a box,
 * one byte per block, read by the real {@link BallPhysics} through {@link BallPhysics.Blocks}.
 *
 * <p><b>Why a model has to be exact.</b> Par, the expert's witness line and the sloppy player's
 * worst case are all worked out on this grid, and the build then replays the witness on the real
 * blocks ({@code LiveBlocks}); a line that holes here must hole there. That is why golf plans use
 * only full blocks and bottom slabs: for those, {@code LiveBlocks}' collision boxes give the same
 * top at every point of the block (1 or 0.5), and the surface is one of five. A test pins every
 * palette entry against {@code LiveBlocks.surface(Material, true)} and the blocks' real heights.
 * Signs are passable, so to the ball they are air, exactly as {@code LiveBlocks} reads them.
 *
 * <p><b>Adventure Golf's blocks (Course Variety §3.4).</b> A pond's still water ({@link #WATER}) is
 * passable and wet: a ball over it drops in, and the round puts it back for a stroke. Smooth
 * sandstone is sand ({@link #SAND}, a full block; {@link #SAND_SLAB}, a sunken bunker's bottom
 * slab), which is SLOW, as {@code LiveBlocks} reads it on a generated course of golf algo 3 or
 * later; a hand-built course reads it as any stone (Course Variety decision 2), and no layout of
 * an older algo has any. Leaves ({@link #LEAVES}) are full, normal blocks to the ball, exactly like
 * logs and moss; they have their own code only so a validator can tell a canopy from a wall.
 *
 * <p><b>Why a byte grid.</b> The planner simulates a few hundred thousand putts a course, each
 * asking the grid a few thousand questions; an array index is the cheapest answer there is.
 * Anything outside the box is air. Not thread-safe while it is being filled; read-only after.
 */
public final class PlanBlocks implements BallPhysics.Blocks {

    /** Nothing solid (air, a sign). */
    public static final byte AIR = 0;
    /** A full block the ball rolls on normally. */
    public static final byte FULL = 1;
    /** A full block of ice (packed or blue). */
    public static final byte ICE = 2;
    /** A full block that slows the ball (soul soil). */
    public static final byte SLOW = 3;
    /** A full slime block (walls only, in golf plans). */
    public static final byte SLIME = 4;
    /** A bottom slab: half a block high, a normal surface. */
    public static final byte SLAB = 5;
    /** A pond's still water: nothing to stand on, and wet (Course Variety §3.4). */
    public static final byte WATER = 6;
    /** Smooth sandstone: a full block of sand, SLOW (a flush bunker). */
    public static final byte SAND = 7;
    /** A smooth sandstone bottom slab: half a block of sand, SLOW (a sunken bunker). */
    public static final byte SAND_SLAB = 8;
    /** Leaves: a full, normal block to the ball, told apart only so a canopy can be checked. */
    public static final byte LEAVES = 9;

    /** Each code's top ({@link #top(byte)}), by code. */
    private static final double[] TOPS = {NONE, 1.0, 1.0, 1.0, 1.0, 0.5, NONE, 1.0, 0.5, 1.0};

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] cells;

    /** An empty grid (all air) over {@code box}. */
    public PlanBlocks(Box box) {
        this.minX = box.minX();
        this.minY = box.minY();
        this.minZ = box.minZ();
        this.sizeX = box.sizeX();
        this.sizeY = box.sizeY();
        this.sizeZ = box.sizeZ();
        long volume = box.volume();
        if (volume > 16_000_000L) {
            throw new IllegalArgumentException("a plan grid is at most 16M blocks: " + box.describe());
        }
        this.cells = new byte[(int) volume];
    }

    /**
     * The grid of a plan's blocks: every op in {@code ops}, read through {@code palette}, over
     * {@code box}. An op outside the box, or a block the golf model doesn't know, throws.
     */
    public static PlanBlocks of(Box box, List<String> palette, List<BlockOp> ops) {
        PlanBlocks g = new PlanBlocks(box);
        byte[] codes = new byte[palette.size()];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = code(palette.get(i));
        }
        for (BlockOp op : ops) {
            if (!box.contains(op.x(), op.y(), op.z())) {
                throw new IllegalArgumentException("a block at " + op.x() + " " + op.y() + " " + op.z()
                        + " is outside " + box.describe());
            }
            g.cells[g.index(op.x(), op.y(), op.z())] = codes[op.state()];
        }
        return g;
    }

    /**
     * What a block is to the ball, from its block-data text: a bottom slab (stone or sand), ice,
     * soul soil, sand, slime, leaves, a sign (air: passable), a pond's still water, or any other
     * allowed block (a full, normal block). A block that isn't on the {@link Palette#ALLOWED} list
     * (other than {@link Palette#POOL_WATER}), or a slab that isn't a bottom slab, throws: the model
     * can't promise to match it.
     */
    public static byte code(String blockData) {
        if (Palette.poolWater(blockData)) {
            return WATER;
        }
        if (!Palette.allowed(blockData)) {
            throw new IllegalArgumentException("not a golf block: " + blockData);
        }
        String id = Palette.id(blockData);
        return switch (id) {
            case "minecraft:smooth_stone_slab", "minecraft:smooth_sandstone_slab" -> {
                String states = blockData.toLowerCase(Locale.ROOT).replace(" ", "");
                if (!states.contains("type=bottom")) {
                    throw new IllegalArgumentException("golf slabs are bottom slabs: " + blockData);
                }
                yield id.equals("minecraft:smooth_stone_slab") ? SLAB : SAND_SLAB;
            }
            case "minecraft:packed_ice", "minecraft:blue_ice" -> ICE;
            case "minecraft:soul_soil" -> SLOW;
            case "minecraft:smooth_sandstone" -> SAND;
            case "minecraft:slime_block" -> SLIME;
            case "minecraft:oak_sign", "minecraft:oak_wall_sign" -> AIR;
            default -> Palette.isLeaves(blockData) ? LEAVES : FULL;
        };
    }

    /** How high a block of this code reaches ({@link BallPhysics.Blocks#NONE} for air and water). */
    public static double top(byte code) {
        return code >= 0 && code < TOPS.length ? TOPS[code] : 1.0;
    }

    /**
     * What a block of this code is to the ball (air is {@code NORMAL}, as {@code LiveBlocks} reads
     * it; sand is {@code SLOW}, as it reads it on a generated course of golf algo 3 or later).
     */
    public static BallPhysics.Surface surface(byte code) {
        return switch (code) {
            case ICE -> BallPhysics.Surface.ICE;
            case SLOW, SAND, SAND_SLAB -> BallPhysics.Surface.SLOW;
            case SLIME -> BallPhysics.Surface.SLIME;
            case WATER -> BallPhysics.Surface.WATER;
            default -> BallPhysics.Surface.NORMAL;
        };
    }

    /** Whether a block of this code is a bottom slab (stone or sand): its top is half a block up. */
    public static boolean slab(byte code) {
        return code == SLAB || code == SAND_SLAB;
    }

    /** Put a block (by its code) at (x, y, z); outside the box throws. */
    public void set(int x, int y, int z, byte code) {
        if (!inside(x, y, z)) {
            throw new IllegalArgumentException("outside the grid: " + x + " " + y + " " + z);
        }
        cells[index(x, y, z)] = code;
    }

    /** The code at (x, y, z); air outside the box. */
    public byte get(int x, int y, int z) {
        return inside(x, y, z) ? cells[index(x, y, z)] : AIR;
    }

    /** Whether (x, y, z) is solid (not air, a sign or water). */
    public boolean solid(int x, int y, int z) {
        return top(get(x, y, z)) != NONE;
    }

    /** The box this grid covers. */
    public Box box() {
        return Box.sized(minX, minY, minZ, sizeX, sizeY, sizeZ);
    }

    @Override
    public double top(int x, int y, int z, double px, double pz) {
        int dx = x - minX;
        int dy = y - minY;
        int dz = z - minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
            return NONE;
        }
        return top(cells[(dy * sizeZ + dz) * sizeX + dx]);
    }

    @Override
    public BallPhysics.Surface surface(int x, int y, int z) {
        return surface(get(x, y, z));
    }

    private boolean inside(int x, int y, int z) {
        int dx = x - minX;
        int dy = y - minY;
        int dz = z - minZ;
        return dx >= 0 && dy >= 0 && dz >= 0 && dx < sizeX && dy < sizeY && dz < sizeZ;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }
}
