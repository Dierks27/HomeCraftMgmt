package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Save the Clubhouse" (0.42.0; the owner: "being able to edit the clubhouse will be really nice ... then when I am
 * done, Save the Clubhouse and it simply saves the blocks as is"): the pure part. A save is every block of the room's
 * box as it stands, kept as a plan, so the room is built, verified and put back after a crash from the owner's blocks
 * instead of {@link ClubhousePlanner}'s. Blocks only (entities, the cabinets, the cups and the decoration, are drawn
 * as before).
 *
 * <ul>
 *   <li>{@link #snapshot}: the blocks of a box (from any reader) as a plan.</li>
 *   <li>{@link #text} / {@link #read}: the save's file, offsets from the box's corner, so it follows a moved origin.</li>
 *   <li>{@link #dark}: the light flood {@code ClubhousePlannerTest} runs on the generated room, run on a save: where a
 *       mob could stand in the dark (a warning; the save is the owner's call).</li>
 *   <li>{@link #spotProblems}: every spot the room uses that the owner's blocks now cover or took away.</li>
 * </ul>
 */
public final class ClubSave {

    /** The file's first line. */
    static final String HEADER = "hcm-clubhouse-save 1";
    /** Light a standable block needs (the room's own test asks the same). */
    static final int LIT = 4;

    private ClubSave() {
    }

    /**
     * One save as read: its plan in {@code box}, the generated plan's hash when it was taken, and the planner's regions
     * it covers ({@link ClubhousePlanner#regions}; a region added in a later release isn't in it).
     */
    public record Saved(Plan plan, String generatedHash, java.util.Set<String> regions) {

        public Saved {
            regions = java.util.Set.copyOf(regions == null ? java.util.Set.of() : regions);
        }
    }

    /**
     * A save with every planner region it doesn't cover taken from {@code generated} (0.42.0): a room HCM adds in a
     * later release appears in the owner's saved room, their own blocks everywhere else untouched. {@code regions} is
     * the planner's regions now, by name.
     */
    public static Plan merged(Saved saved, Plan generated, java.util.Map<String, Box> regions) {
        List<Box> fresh = new ArrayList<>();
        for (java.util.Map.Entry<String, Box> r : regions.entrySet()) {
            if (!saved.regions().contains(r.getKey())) {
                fresh.add(r.getValue());
            }
        }
        if (fresh.isEmpty()) {
            return saved.plan();
        }
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        List<BlockOp> ops = new ArrayList<>();
        Plan p = saved.plan();
        for (BlockOp op : p.ops()) {
            if (!inAny(fresh, op)) {
                ops.add(reindex(op, p.blockOf(op), palette, index));
            }
        }
        for (BlockOp op : generated.ops()) {
            if (inAny(fresh, op)) {
                ops.add(reindex(op, generated.blockOf(op), palette, index));
            }
        }
        return Plan.of(p.slot(), p.algo(), 0L, p.half(), palette, ops, List.of(), List.of(), null, p.summary(), 0);
    }

    private static boolean inAny(List<Box> boxes, BlockOp op) {
        for (Box b : boxes) {
            if (b.contains(op.x(), op.y(), op.z())) {
                return true;
            }
        }
        return false;
    }

    private static BlockOp reindex(BlockOp op, String block, List<String> palette, Map<String, Integer> index) {
        Integer s = index.get(block);
        if (s == null) {
            s = palette.size();
            palette.add(block);
            index.put(block, s);
        }
        return new BlockOp(op.x(), op.y(), op.z(), (short) (int) s);
    }

    /** The blocks of {@code box} as {@code at} reads them (full spelling; air is left out) as a plan. */
    public static Plan snapshot(Box box, java.util.function.Function<int[], String> at) {
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        List<BlockOp> ops = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    String b = at.apply(new int[]{x, y, z});
                    if (b == null || isAir(b)) {
                        continue;
                    }
                    Integer s = index.get(b);
                    if (s == null) {
                        s = palette.size();
                        palette.add(b);
                        index.put(b, s);
                    }
                    ops.add(new BlockOp(x, y, z, (short) (int) s));
                }
            }
        }
        return Plan.of(ClubhousePlanner.SLOT, ClubhousePlanner.ALGO, 0L, box, palette, ops, List.of(), List.of(), null,
                List.of("the Clubhouse as saved: " + box.describe()), 0);
    }

    static boolean isAir(String block) {
        String id = ClubhousePlanner.id(block);
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    /** The save's file: the header, the box's size, the generated hash, the palette, then each block's offsets. */
    public static String text(Plan plan, String generatedHash, java.util.Collection<String> regions) {
        Box b = plan.half();
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        out.append("size ").append(b.sizeX()).append(' ').append(b.sizeY()).append(' ').append(b.sizeZ()).append('\n');
        out.append("generated ").append(generatedHash == null ? "-" : generatedHash).append('\n');
        out.append("regions ").append(regions == null || regions.isEmpty() ? "-" : String.join(",",
                new java.util.TreeSet<>(regions))).append('\n');
        out.append("palette ").append(plan.palette().size()).append('\n');
        for (String s : plan.palette()) {
            out.append(s).append('\n');
        }
        out.append("blocks ").append(plan.ops().size()).append('\n');
        for (BlockOp op : plan.ops()) {
            out.append(op.x() - b.minX()).append(' ').append(op.y() - b.minY()).append(' ').append(op.z() - b.minZ())
                    .append(' ').append(op.state()).append('\n');
        }
        return out.toString();
    }

    /**
     * A save's file read into {@code box} (the room's box now: its corner; its width and height must be the save's, its
     * depth may differ).
     *
     * @throws IllegalArgumentException when the text isn't a save, or was taken of a box of another width or height
     */
    public static Saved read(String text, Box box) {
        if (text == null) {
            throw new IllegalArgumentException("no save");
        }
        String[] lines = text.split("\n");
        int n = 0;
        if (lines.length < 5 || !lines[n++].trim().equals(HEADER)) {
            throw new IllegalArgumentException("not a Clubhouse save");
        }
        String[] size = lines[n++].trim().split(" ");
        // 0.45.0: the depth may differ (a save of the main room alone read into the room with its annex, or the other
        // way round): the main room is the first rows of both, so the save still fits; blocks past the box are left
        // out and whatever the save doesn't cover comes from HCM's plan (merged). Before, such a save was refused and
        // HCM's room was built over the owner's.
        if (size.length != 4 || Integer.parseInt(size[1]) != box.sizeX() || Integer.parseInt(size[2]) != box.sizeY()) {
            throw new IllegalArgumentException("the save is of a " + String.join(" x ", size[1], size[2], size[3])
                    + " box, the room is " + box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ());
        }
        int savedDepth = Integer.parseInt(size[3]);
        String gen = lines[n++].trim().substring("generated ".length()).trim();
        java.util.Set<String> regions = new java.util.TreeSet<>();
        if (lines[n].startsWith("regions ")) {
            String r = lines[n++].trim().substring("regions ".length()).trim();
            if (!r.equals("-")) {
                regions.addAll(java.util.Arrays.asList(r.split(",")));
            }
        }
        int pals = Integer.parseInt(lines[n++].trim().substring("palette ".length()).trim());
        List<String> palette = new ArrayList<>();
        for (int i = 0; i < pals; i++) {
            palette.add(lines[n++].trim());
        }
        int count = Integer.parseInt(lines[n++].trim().substring("blocks ".length()).trim());
        List<BlockOp> ops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String[] p = lines[n++].trim().split(" ");
            int state = Integer.parseInt(p[3]);
            if (state < 0 || state >= palette.size()) {
                throw new IllegalArgumentException("a block names no palette entry");
            }
            int x = box.minX() + Integer.parseInt(p[0]);
            int y = box.minY() + Integer.parseInt(p[1]);
            int z = box.minZ() + Integer.parseInt(p[2]);
            if (!box.contains(x, y, z)) {
                if (savedDepth > box.sizeZ() && x >= box.minX() && x <= box.maxX() && y >= box.minY()
                        && y <= box.maxY()) {
                    continue; // a deeper save's annex, which the room doesn't have now
                }
                throw new IllegalArgumentException("a block is outside the room");
            }
            ops.add(new BlockOp(x, y, z, (short) state));
        }
        if (savedDepth < box.sizeZ()) {
            regions.retainAll(java.util.Set.of("main")); // a save of the main room alone covers nothing else
        }
        Plan plan = Plan.of(ClubhousePlanner.SLOT, ClubhousePlanner.ALGO, 0L, box, palette, ops, List.of(), List.of(),
                null, List.of("the Clubhouse as saved: " + box.describe()), 0);
        return new Saved(plan, gen.equals("-") ? null : gen, regions);
    }

    // ---- the light ---------------------------------------------------------------------------------

    /**
     * Every block a mob could stand on in the room's air ({@code site}'s rooms) with block light under {@value #LIT},
     * flooded from {@code plan}'s lights as the generated room's test does ({@link ClubhousePlanner#lightOf},
     * {@link ClubhousePlanner#letsLight}): {x, y, z} each, at most {@code max}.
     */
    public static List<int[]> dark(Plan plan, ClubhouseSite site, int max) {
        Box box = plan.half();
        Map<Long, String> b = new HashMap<>();
        Map<Long, Integer> light = new HashMap<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (BlockOp op : plan.ops()) {
            String s = plan.blockOf(op);
            b.put(key(op.x(), op.y(), op.z()), s);
            int l = ClubhousePlanner.lightOf(s);
            if (l > 0) {
                light.put(key(op.x(), op.y(), op.z()), l);
                q.add(new int[]{op.x(), op.y(), op.z(), l});
            }
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (light.getOrDefault(key(c[0], c[1], c[2]), 0) > c[3] || c[3] <= 1) {
                continue;
            }
            for (int[] d : dirs) {
                int x = c[0] + d[0];
                int y = c[1] + d[1];
                int z = c[2] + d[2];
                if (!box.contains(x, y, z)) {
                    continue;
                }
                String n = b.get(key(x, y, z));
                if (n != null && !ClubhousePlanner.letsLight(n)) {
                    continue;
                }
                if (light.getOrDefault(key(x, y, z), 0) < c[3] - 1) {
                    light.put(key(x, y, z), c[3] - 1);
                    q.add(new int[]{x, y, z, c[3] - 1});
                }
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY() + 1; y <= box.maxY(); y++) {
                    if (!site.in(x + 0.5, y, z + 0.5)) {
                        continue; // outside the rooms' air (over a roof, in a wall)
                    }
                    String here = b.get(key(x, y, z));
                    String below = b.get(key(x, y - 1, z));
                    if (below == null || ClubhousePlanner.noFooting(below)) {
                        continue;
                    }
                    if (here != null && !ClubhousePlanner.id(here).endsWith("_carpet")) {
                        continue;
                    }
                    if (light.getOrDefault(key(x, y, z), 0) < LIT && out.size() < max) {
                        out.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ---- the spots ---------------------------------------------------------------------------------

    /** A spot the room uses, by name, and what must be true there. */
    public record Spot(String name, int x, int y, int z, Need need) {
    }

    /** What a spot needs of the blocks. */
    public enum Need {
        /** Someone stands here: air at the feet and the head, something to stand on. */
        STAND,
        /** Something hangs or floats here: air. */
        AIR,
        /** The block itself must be this kind (the jukebox). */
        JUKEBOX,
        /** A seat: a stair or a slab (a cushion sits on it). */
        SEAT
    }

    /**
     * Every spot whose blocks {@code plan} covered or took away, in words with where it is: "arrival 3 at x, y, z: a
     * block is in the way". Empty when every spot is as the room needs it.
     */
    public static List<String> spotProblems(Plan plan, List<Spot> spots) {
        Map<Long, String> b = new HashMap<>();
        for (BlockOp op : plan.ops()) {
            b.put(key(op.x(), op.y(), op.z()), plan.blockOf(op));
        }
        List<String> out = new ArrayList<>();
        for (Spot s : spots) {
            String here = b.get(key(s.x(), s.y(), s.z()));
            String why = switch (s.need()) {
                case STAND -> {
                    String head = b.get(key(s.x(), s.y() + 1, s.z()));
                    String below = b.get(key(s.x(), s.y() - 1, s.z()));
                    yield blocked(here) || blocked(head) ? "a block is in the way"
                            : below == null ? "there is nothing to stand on" : null;
                }
                case AIR -> blocked(here) ? "a block is in the way" : null;
                case JUKEBOX -> here == null || !ClubhousePlanner.id(here).equals("minecraft:jukebox")
                        ? "the jukebox isn't there" : null;
                case SEAT -> here == null || !(here.contains("_stairs") || here.contains("_slab"))
                        ? "its chair isn't there (the cushion would float)" : null;
            };
            if (why != null) {
                out.add(s.name() + " at " + s.x() + ", " + s.y() + ", " + s.z() + ": " + why);
            }
        }
        return out;
    }

    /** Whether a block stops someone standing or something hanging there (carpets don't). */
    private static boolean blocked(String block) {
        return block != null && !ClubhousePlanner.id(block).endsWith("_carpet");
    }
}
