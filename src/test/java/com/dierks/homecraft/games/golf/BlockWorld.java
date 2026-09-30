package com.dierks.homecraft.games.golf;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A world of blocks for {@link LiveBlocks} with no server (Course Variety §3.4): each block is its
 * block-data text, read back as a {@link Block} of that {@link Material} whose collision shape is
 * the one Minecraft gives the golf palette's blocks — a full cube, a bottom slab's lower half, or
 * nothing for a passable block (air, water, a sign). What it can't show is that the server really
 * has those shapes; the owner's live checks do (C-G1, C-G2).
 */
public final class BlockWorld {

    private final Map<Long, String> blocks = new HashMap<>();
    private final World world;

    public BlockWorld() {
        world = (World) Proxy.newProxyInstance(BlockWorld.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> "blocks";
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "isChunkLoaded" -> true;
                    case "getBlockAt" -> block((Integer) a[0], (Integer) a[1], (Integer) a[2]);
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "BlockWorld";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** The world to hand to {@link LiveBlocks}. */
    public World world() {
        return world;
    }

    /** Put a block (its block-data text, {@code null} for air) at (x, y, z). */
    public BlockWorld set(int x, int y, int z, String blockData) {
        if (blockData == null) {
            blocks.remove(key(x, y, z));
        } else {
            blocks.put(key(x, y, z), blockData);
        }
        return this;
    }

    private Block block(int x, int y, int z) {
        String data = blocks.get(key(x, y, z));
        String id = data == null ? "minecraft:air" : data.toLowerCase(Locale.ROOT).replaceAll("\\[.*", "");
        Material type = Material.matchMaterial(id);
        if (type == null) {
            throw new IllegalArgumentException("not a block: " + data);
        }
        boolean passable = data == null || id.endsWith("water") || id.endsWith("_sign") || id.endsWith("air");
        boolean slab = id.endsWith("_slab") && data.contains("type=bottom");
        List<BoundingBox> shape = passable ? List.of()
                : List.of(new BoundingBox(0, 0, 0, 1, slab ? 0.5 : 1, 1));
        VoxelShape voxels = (VoxelShape) Proxy.newProxyInstance(BlockWorld.class.getClassLoader(),
                new Class<?>[]{VoxelShape.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "getBoundingBoxes" -> shape;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (Block) Proxy.newProxyInstance(BlockWorld.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getType" -> type;
                    case "isPassable" -> passable;
                    case "getCollisionShape" -> voxels;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
