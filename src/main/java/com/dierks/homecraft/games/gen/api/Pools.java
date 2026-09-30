package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared sealed-pool rule (Course Variety §1.3): water a plan places may never move.
 *
 * <p>A plan's water is only ever {@link Palette#POOL_WATER} (still sources), and every such block
 * must be:
 * <ul>
 *   <li><b>sealed</b>: water or a {@link #seals full solid block} on all four sides and below, so
 *       nothing can flow out sideways or down;</li>
 *   <li><b>shallow</b>: at most {@code maxDepth} blocks of water in its column (golf: exactly 1, so
 *       never water over water), with air or a plan block above the top one;</li>
 *   <li><b>in a pool</b>: inside one of the declared pool {@code boxes}.</li>
 * </ul>
 * With the builder writing water last and draining it first ({@code BuildJob}) and the area guard
 * blocking flow into and out of every half, a sealed pool is still water forever.
 *
 * <p>It is the Dropper's own rule ({@code DropperValidator}, "water only in pool boxes", "sealed"),
 * generalised to any plan. The Dropper keeps its own copy for now, so its pinned behaviour can't
 * move; a later tidy-up may switch it over. What seals is stricter here than the Dropper's "any
 * solid": slabs, signs and leaves are not full blocks (they can hold water), so a pond is never
 * walled with them.
 *
 * <p>Pure: no Bukkit. {@code LiveProof.pools} is its twin on the real blocks.
 */
public final class Pools {

    /** A pond's depth on golf (§3.8 rule 2: 1 deep, at T - 1 over a solid block). */
    public static final int GOLF_DEPTH = 1;

    private static final byte AIR = 0;
    private static final byte WATER = 1;
    private static final byte SEAL = 2;
    private static final byte OTHER = 3;

    private Pools() {
    }

    /**
     * What is wrong with {@code plan}'s water as pools (§1.3): each problem once, with how many
     * blocks it concerns; empty when every water block is sealed, shallow enough and in a pool box
     * (or when there is no water).
     *
     * @param boxes    where pools may be (block positions, both ends included)
     * @param maxDepth the most water blocks one column may hold, at least 1
     */
    public static List<String> problems(Plan plan, List<Box> boxes, int maxDepth) {
        if (plan == null) {
            return List.of();
        }
        return problems(plan.palette(), plan.ops(), boxes, maxDepth);
    }

    /** {@link #problems(Plan, List, int)} from a plan's parts. */
    public static List<String> problems(List<String> palette, List<BlockOp> ops, List<Box> boxes, int maxDepth) {
        List<String> out = new ArrayList<>();
        if (palette == null || ops == null) {
            return out;
        }
        byte[] kinds = new byte[palette.size()];
        boolean anyWater = false;
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = kind(palette.get(i));
            anyWater |= kinds[i] == WATER;
        }
        int fluids = 0;
        for (String p : palette) {
            if (p != null && !Palette.poolWater(p) && fluid(p)) {
                fluids++;
            }
        }
        if (fluids > 0) {
            out.add(fluids + " palette entr" + (fluids == 1 ? "y is" : "ies are") + " water that isn't still: only"
                    + " still sources may be placed");
        }
        if (!anyWater) {
            return out;
        }
        Map<Long, Byte> at = new HashMap<>();
        for (BlockOp op : ops) {
            if (op.state() >= 0 && op.state() < kinds.length) {
                at.put(key(op.x(), op.y(), op.z()), kinds[op.state()]);
            }
        }
        int stray = 0;
        int open = 0;
        int deep = 0;
        String firstOpen = null;
        for (BlockOp op : ops) {
            if (op.state() < 0 || op.state() >= kinds.length || kinds[op.state()] != WATER) {
                continue;
            }
            int x = op.x();
            int y = op.y();
            int z = op.z();
            boolean inBox = false;
            if (boxes != null) {
                for (Box b : boxes) {
                    if (b != null && b.contains(x, y, z)) {
                        inBox = true;
                        break;
                    }
                }
            }
            if (!inBox) {
                stray++;
            }
            int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
            for (int[] d : around) {
                byte k = at.getOrDefault(key(x + d[0], y + d[1], z + d[2]), AIR);
                if (k != WATER && k != SEAL) {
                    open++;
                    if (firstOpen == null) {
                        firstOpen = x + " " + y + " " + z;
                    }
                    break;
                }
            }
            // the top of a column of water: count it down
            if (at.getOrDefault(key(x, y + 1, z), AIR) != WATER) {
                int depth = 1;
                while (at.getOrDefault(key(x, y - depth, z), AIR) == WATER) {
                    depth++;
                }
                if (depth > Math.max(1, maxDepth)) {
                    deep++;
                }
            }
        }
        if (stray > 0) {
            out.add(stray + " water block" + (stray == 1 ? " is" : "s are") + " outside every pool box");
        }
        if (open > 0) {
            out.add(open + " water block" + (open == 1 ? " has" : "s have") + " air, a slab, a sign or leaves beside"
                    + " or under it (first at " + firstOpen + "): pools must be sealed");
        }
        if (deep > 0) {
            out.add(deep + " pool column" + (deep == 1 ? " is" : "s are") + " more than " + Math.max(1, maxDepth)
                    + " deep");
        }
        return out;
    }

    /**
     * Whether a block seals a pool on the side or underneath: an allowed, full block that no water can
     * get into. Not water, and not a slab, a sign or leaves (they can hold water, and a slab's top
     * half is open).
     */
    public static boolean seals(String blockData) {
        return kind(blockData) == SEAL;
    }

    private static byte kind(String blockData) {
        if (blockData == null) {
            return OTHER;
        }
        if (Palette.poolWater(blockData)) {
            return WATER;
        }
        if (!Palette.allowed(blockData)) {
            return OTHER;
        }
        String id = Palette.id(blockData);
        if (id.endsWith("_slab") || id.endsWith("_sign") || id.endsWith("_leaves")) {
            return OTHER;
        }
        return SEAL;
    }

    private static boolean fluid(String blockData) {
        String id = Palette.id(blockData);
        return id.equals("minecraft:water") || id.equals("minecraft:lava") || id.equals("minecraft:bubble_column");
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
