package com.dierks.homecraft.games.golf;

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
 * <p>Public for Daily Courses (GEN-SPEC §4.3): its golf builds replay each hole's witness line on
 * these, the real blocks, and its planner's block model is checked against {@link #surface}.
 */
public final class LiveBlocks implements BallPhysics.Blocks {

    private static final double EDGE = 1e-6;

    /** A block as read once: its material and its solid boxes (none when passable). */
    private record Cell(Material type, Collection<BoundingBox> boxes) {
    }

    private static final Cell NOTHING = new Cell(Material.AIR, List.of());

    private final World world;
    private final Map<Long, Cell> seen = new HashMap<>();

    public LiveBlocks(World world) {
        this.world = world;
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
        return surface(cell(x, y, z).type());
    }

    /** What a block is to the ball. */
    public static BallPhysics.Surface surface(Material m) {
        return switch (m) {
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE -> BallPhysics.Surface.ICE;
            case SOUL_SAND, SOUL_SOIL, HONEY_BLOCK -> BallPhysics.Surface.SLOW;
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
