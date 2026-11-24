package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The Floor Is Lava's playroom as the game reads it: the plan the box is built and verified to (or, 0.57.0, the
 * furniture's layout of the moment), and everything the round needs that follows from the plan's blocks alone: where
 * a player can stand, where the lava goes at each level, which standing places are dry then, where stars may float,
 * and whether a pair of feet is in the lava.
 *
 * <p>Why it is worked out from the blocks and not written down beside them: "the nearest safe spot" must always be
 * a real, standable, dry place, and the cells the game fills with lava must be exactly the cells the plan leaves
 * empty. Made from the plan, neither can drift from it.
 *
 * <p><b>The model.</b> Every block of the plan is a whole block or a bottom slab ({@link LavaPlanner} uses nothing
 * else), so a block's top is one block or half a block over its floor. A <i>stand</i> is a block top with two
 * blocks of air over it. The <i>reach</i> is every stand a player can walk to from where they arrive, stepping up
 * half a block at a time (no jump needed) and down any drop: the play area. The gallery, glassed in, is outside it.
 *
 * <p><b>Lava.</b> Level {@code n} is lava in every air cell of the room's first {@code n} layers over the floor.
 * Lava's top is {@value #LAVA_TOP} of a block, so feet a whole block over the top layer's floor are dry:
 * a stand is safe at level {@code n} when its feet are {@code n + 1} or more over the floor block.
 *
 * <p><b>0.57.0: hops and pieces.</b> The furniture moves ({@link LavaLayout}), so a site also knows which piece each
 * furniture block belongs to ({@link #pieceOf}), and besides the walk there is the hop ({@link #hops}): a jump of up to
 * {@value #HOP_REACH} blocks across and {@value #JUMP_UP} up with nothing in the way, which is how the grown-ups get
 * from piece to piece while the floor is lava. {@link #hopReach} is everything walks and hops get to;
 * {@link #hopComponent} is what a player on dry ground can get to without touching the lava.
 *
 * <p>Pure and immutable (its hop look-ups are worked out on first use; main thread only).
 */
public final class LavaSite {

    /** How high a lava block's surface is. A little over the game's 8/9, so a touch is never missed. */
    public static final double LAVA_TOP = 0.9;
    /** Half a player's width, and their height. */
    static final double HALF_WIDTH = 0.3;
    static final double HEIGHT = 1.8;
    /** The highest step a player walks up without jumping. */
    public static final double STEP = 0.5;
    /** 0.57.0: a hop: how far across (between the middles of two blocks) and how far up a jump surely goes. */
    public static final double HOP_REACH = 3.2;
    public static final double JUMP_UP = 1.0;

    /** Which cells are lava now ({@link #touches(double, double, double, Lava)}). */
    @FunctionalInterface
    public interface Lava {
        boolean at(int x, int y, int z);
    }

    /**
     * A place to put a player: the feet and which way they face.
     *
     * @param yaw Minecraft yaw (0 = south, -90 = east)
     */
    public record Spot(double x, double y, double z, float yaw) {
    }

    /**
     * A block top a player can stand on.
     *
     * @param x the block column
     * @param z ...
     * @param y the feet's height (the block's top)
     */
    public record Stand(int x, int z, double y) {
    }

    private final Plan plan;
    private final Box box;
    private final Box inside;
    private final Box galleryBox;
    private final Map<Long, String> blocks = new HashMap<>();
    private final List<Spot> spawns;
    private final List<Spot> gallery;
    private final List<Stand> stands = new ArrayList<>();
    private final List<Stand> reach = new ArrayList<>();
    private final List<List<int[]>> layers = new ArrayList<>();
    private final List<int[]> glow = new ArrayList<>();
    private final List<Stand> starStands = new ArrayList<>();
    private final int flood;
    private final Map<Long, Integer> owners;
    private final List<LavaLayout.Placed> layout;
    private final Map<Long, List<Stand>> byColumn = new HashMap<>();
    private List<Stand> hopReach;
    private final Map<Stand, List<Stand>> hopsFrom = new HashMap<>();

    /**
     * @param plan       every block of the box
     * @param spawns     where players arrive (each on a stand)
     * @param gallery    where Challenge's watchers stand
     * @param galleryBox the gallery's air
     * @param lamp       the floor's lamp block: the one floor block that never glows
     */
    LavaSite(Plan plan, List<Spot> spawns, List<Spot> gallery, Box galleryBox, String lamp) {
        this(plan, spawns, gallery, galleryBox, lamp, Map.of(), List.of());
    }

    /**
     * @param owners each furniture block's piece (its slot in {@code layout}), by {@link #key}
     * @param layout where the furniture stands ({@code null} pieces are gone)
     */
    LavaSite(Plan plan, List<Spot> spawns, List<Spot> gallery, Box galleryBox, String lamp, Map<Long, Integer> owners,
             List<LavaLayout.Placed> layout) {
        if (plan == null || spawns == null || spawns.isEmpty() || gallery == null || gallery.isEmpty()) {
            throw new IllegalArgumentException("a playroom needs its plan, its arrival spots and its gallery");
        }
        this.owners = Map.copyOf(owners);
        this.layout = Collections.unmodifiableList(new ArrayList<>(layout));
        this.plan = plan;
        this.box = plan.half();
        this.inside = new Box(box.minX() + 1, box.minY() + 1, box.minZ() + 1, box.maxX() - 1, box.maxY() - 1,
                box.maxZ() - 1);
        this.galleryBox = galleryBox;
        this.spawns = List.copyOf(spawns);
        this.gallery = List.copyOf(gallery);
        for (BlockOp op : plan.ops()) {
            blocks.put(key(op.x(), op.y(), op.z()), plan.blockOf(op));
        }
        findStands();
        findReach();
        double top = box.minY() + 1;
        for (Stand s : reach) {
            top = Math.max(top, s.y());
        }
        this.flood = (int) Math.ceil(top - box.minY() - 1e-9);
        for (int l = 1; l <= flood; l++) {
            List<int[]> cells = new ArrayList<>();
            int y = box.minY() + l;
            for (int x = inside.minX(); x <= inside.maxX(); x++) {
                for (int z = inside.minZ(); z <= inside.maxZ(); z++) {
                    if (air(x, y, z)) {
                        cells.add(new int[]{x, y, z});
                    }
                }
            }
            layers.add(List.copyOf(cells));
        }
        for (int x = inside.minX(); x <= inside.maxX(); x++) {
            for (int z = inside.minZ(); z <= inside.maxZ(); z++) {
                String floor = block(x, box.minY(), z);
                if (floor != null && !floor.equals(lamp) && air(x, box.minY() + 1, z)) {
                    glow.add(new int[]{x, box.minY(), z});
                }
            }
        }
        for (Stand s : reach) {
            int i = s.x() - box.minX();
            int k = s.z() - box.minZ();
            if (s.y() == box.minY() + 1 && i >= 3 && i <= box.sizeX() - 4 && k >= 3 && k <= box.sizeZ() - 4
                    && air(s.x(), box.minY() + 3, s.z()) && air(s.x(), box.minY() + 4, s.z())) {
                starStands.add(s);
            }
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ---- the blocks -------------------------------------------------------------------------------

    /** The plan's block at a position, or {@code null} for air. */
    public String block(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    /** Whether the plan leaves a cell empty. */
    public boolean air(int x, int y, int z) {
        return !blocks.containsKey(key(x, y, z));
    }

    /** Whether a block is a bottom slab (its top is half a block up). */
    static boolean slab(String block) {
        return block != null && block.contains("_slab");
    }

    private void findStands() {
        for (int x = inside.minX(); x <= inside.maxX(); x++) {
            for (int z = inside.minZ(); z <= inside.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY() - 3; y++) {
                    String b = block(x, y, z);
                    if (b != null && air(x, y + 1, z) && air(x, y + 2, z)) {
                        Stand s = new Stand(x, z, slab(b) ? y + 0.5 : y + 1);
                        stands.add(s);
                        byColumn.computeIfAbsent(key(x, 0, z), k -> new ArrayList<>()).add(s);
                    }
                }
            }
        }
    }

    /** Every stand a player can walk to from an arrival spot: up half a block at a time, down any drop. */
    private void findReach() {
        Set<Stand> seen = new HashSet<>();
        ArrayDeque<Stand> queue = new ArrayDeque<>();
        for (Spot sp : spawns) {
            Stand at = standAt((int) Math.floor(sp.x()), (int) Math.floor(sp.z()), sp.y());
            if (at == null) {
                throw new IllegalArgumentException("an arrival spot isn't on a stand: " + sp);
            }
            if (seen.add(at)) {
                queue.add(at);
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Stand a = queue.poll();
            for (int[] d : dirs) {
                for (Stand b : column(a.x() + d[0], a.z() + d[1])) {
                    if (!seen.contains(b) && walks(a, b)) {
                        seen.add(b);
                        queue.add(b);
                    }
                }
            }
        }
        for (Stand s : stands) { // in the stands' own order: by column, bottom up
            if (seen.contains(s)) {
                reach.add(s);
            }
        }
    }

    private Stand standAt(int x, int z, double y) {
        for (Stand s : column(x, z)) {
            if (Math.abs(s.y() - y) < 1e-6) {
                return s;
            }
        }
        return null;
    }

    /** Whether a player on {@code a} can step onto the neighbouring {@code b}: up a step, or down with nothing in the way. */
    public boolean walks(Stand a, Stand b) { // stage 2: NPC players walk by the same rule
        if (b.y() > a.y() + STEP + 1e-9) {
            return false;
        }
        if (b.y() >= a.y()) {
            return true;
        }
        // down: b's column must be open from b's own headroom up to where the walker's head is on a
        for (int y = (int) Math.floor(b.y()) + (b.y() % 1 == 0 ? 0 : 1); y <= (int) Math.floor(a.y() + HEIGHT - 1e-9); y++) {
            if (!air(b.x(), y, b.z())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 0.57.0: whether a player on {@code a} can jump onto {@code b}: no more than {@value #HOP_REACH} across and
     * {@value #JUMP_UP} up, with room over {@code a} to jump and nothing in the way between them at the height the
     * jump goes. A walk to a neighbour ({@link #walks}) is a hop too.
     */
    public boolean hops(Stand a, Stand b) {
        int dx = b.x() - a.x();
        int dz = b.z() - a.z();
        if (Math.abs(dx) + Math.abs(dz) == 1 && walks(a, b)) {
            return true;
        }
        if ((dx == 0 && dz == 0) || dx * dx + dz * dz > HOP_REACH * HOP_REACH || b.y() > a.y() + JUMP_UP + 1e-9) {
            return false;
        }
        int ay = (int) Math.floor(a.y() + 1e-9);
        if (!air(a.x(), ay + 2, a.z())) {
            return false; // no room over a to jump (under the table)
        }
        double top = Math.max(a.y(), b.y());
        int y0 = (int) Math.floor(top + 1e-9);
        int y1 = (int) Math.floor(top + HEIGHT + 0.5);
        int steps = Math.max(Math.abs(dx), Math.abs(dz)) * 2;
        for (int n = 1; n < steps; n++) {
            double t = n / (double) steps;
            int cx = (int) Math.floor(a.x() + 0.5 + dx * t);
            int cz = (int) Math.floor(a.z() + 0.5 + dz * t);
            if ((cx == a.x() && cz == a.z()) || (cx == b.x() && cz == b.z())) {
                continue;
            }
            for (int y = y0; y <= y1; y++) {
                if (!air(cx, y, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The stands one hop (or a walk) from {@code a} lands on, worked out once. */
    public List<Stand> hopsFrom(Stand a) {
        List<Stand> cached = hopsFrom.get(a);
        if (cached != null) {
            return cached;
        }
        List<Stand> out = new ArrayList<>();
        double floor = box.minY() + 1;
        int r = (int) Math.ceil(HOP_REACH);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (Stand b : column(a.x() + dx, a.z() + dz)) {
                    boolean next = Math.abs(dx) + Math.abs(dz) == 1;
                    if (!next && a.y() <= floor + 1e-9 && b.y() <= floor + 1e-9) {
                        continue; // floor to floor is a walk
                    }
                    if (hops(a, b)) {
                        out.add(b);
                    }
                }
            }
        }
        List<Stand> done = List.copyOf(out);
        hopsFrom.put(a, done);
        return done;
    }

    /** 0.57.0: every stand a player can get to from an arrival spot by walking and jumping: the stools too. */
    public List<Stand> hopReach() {
        if (hopReach == null) {
            Set<Stand> seen = new HashSet<>(reach);
            ArrayDeque<Stand> queue = new ArrayDeque<>(reach);
            while (!queue.isEmpty()) {
                Stand a = queue.poll();
                for (Stand b : hopsFrom(a)) {
                    if (seen.add(b)) {
                        queue.add(b);
                    }
                }
            }
            List<Stand> out = new ArrayList<>();
            for (Stand s : stands) {
                if (seen.contains(s) && !inGallery(s.x() + 0.5, s.y(), s.z() + 0.5)) {
                    out.add(s);
                }
            }
            hopReach = List.copyOf(out);
        }
        return hopReach;
    }

    /**
     * 0.57.0: every stand dry at {@code level} that a player on one of {@code from} can get to by walks and hops over
     * dry stands only (never touching the lava).
     */
    public Set<Stand> hopComponent(int level, Collection<Stand> from) {
        Set<Stand> seen = new HashSet<>();
        ArrayDeque<Stand> queue = new ArrayDeque<>();
        for (Stand s : from) {
            if (s != null && dry(s, level) && seen.add(s)) {
                queue.add(s);
            }
        }
        while (!queue.isEmpty()) {
            Stand a = queue.poll();
            for (Stand b : hopsFrom(a)) {
                if (dry(b, level) && !inGallery(b.x() + 0.5, b.y(), b.z() + 0.5) && seen.add(b)) {
                    queue.add(b);
                }
            }
        }
        return seen;
    }

    /** 0.57.0: the piece (its layout slot) whose block {@code s} stands on, or -1 (the floor, the bookcase). */
    public int pieceOf(Stand s) {
        int under = s.y() % 1 == 0 ? (int) s.y() - 1 : (int) Math.floor(s.y());
        return pieceAt(s.x(), under, s.z());
    }

    /** The piece a block belongs to, or -1. */
    public int pieceAt(int x, int y, int z) {
        Integer o = owners.get(key(x, y, z));
        return o == null ? -1 : o;
    }

    /** Where the furniture stands in this room ({@code null} for a piece that is gone). */
    public List<LavaLayout.Placed> layout() {
        return layout;
    }

    /** The stands of one column, bottom up. */
    public List<Stand> column(int x, int z) {
        return byColumn.getOrDefault(key(x, 0, z), List.of());
    }

    /**
     * 0.57.0: the stand feet at (x, y, z) are on: in their column, at most 0.6 under them and never over them by more
     * than a hair; {@code null} in mid-air.
     */
    public Stand standUnder(double x, double y, double z) {
        Stand best = null;
        for (Stand s : column((int) Math.floor(x), (int) Math.floor(z))) {
            if (s.y() <= y + 0.05 && y - s.y() <= 0.6 && (best == null || s.y() > best.y())) {
                best = s;
            }
        }
        return best;
    }

    /** 0.57.0: whether a player with feet at (x, y, z) would be inside a block of this room. */
    public boolean collides(double x, double y, double z) {
        double e = 1e-4;
        for (int bx = (int) Math.floor(x - HALF_WIDTH + e); bx <= (int) Math.floor(x + HALF_WIDTH - e); bx++) {
            for (int bz = (int) Math.floor(z - HALF_WIDTH + e); bz <= (int) Math.floor(z + HALF_WIDTH - e); bz++) {
                for (int by = (int) Math.floor(y); by <= (int) Math.floor(y + HEIGHT - e); by++) {
                    String b = block(bx, by, bz);
                    if (b != null && (slab(b) ? by + 0.5 : by + 1) > y + 0.01) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- places -----------------------------------------------------------------------------------

    public Plan plan() {
        return plan;
    }

    /** The room's box (the plan's half). */
    public Box box() {
        return box;
    }

    /** The room's air: the floor, the walls and the roof are outside it. */
    public Box inside() {
        return inside;
    }

    /** The gallery's air (Challenge's watchers; the lava never comes up to it). */
    public Box galleryBox() {
        return galleryBox;
    }

    public List<Spot> spawns() {
        return spawns;
    }

    /** Arrival spot {@code n}, round and round. */
    public Spot spawn(int n) {
        return spawns.get(Math.floorMod(n, spawns.size()));
    }

    public List<Spot> gallerySpots() {
        return gallery;
    }

    /** Gallery spot {@code n}, round and round. */
    public Spot gallery(int n) {
        return gallery.get(Math.floorMod(n, gallery.size()));
    }

    /** Whether feet at (x, y, z) are in the gallery. */
    public boolean inGallery(double x, double y, double z) {
        return galleryBox.contains(x, y, z);
    }

    /** Every block top with room to stand, the gallery's and the unreachable ones too. */
    public List<Stand> stands() {
        return List.copyOf(stands);
    }

    /** Every stand a player can walk to: the play area. */
    public List<Stand> reach() {
        return List.copyOf(reach);
    }

    /**
     * The lava level that covers the whole play area (its highest stand's feet): Challenge's last. The level below
     * it is the highest with dry ground.
     */
    public int flood() {
        return flood;
    }

    /** The air cells of layer {@code level} (1 is the layer the floor's walkers stand in), each {x, y, z}. */
    public List<int[]> layer(int level) {
        return level < 1 || level > layers.size() ? List.of() : layers.get(level - 1);
    }

    /** The floor blocks that glow before the lava comes (every bare one but the lamps), each {x, y, z}. */
    public List<int[]> glowCells() {
        return List.copyOf(glow);
    }

    /** Where a star may float: open floor of the play area, away from the walls and never under anything. */
    public List<Stand> starStands() {
        return List.copyOf(starStands);
    }

    /**
     * 0.57.0: Little's stars on the furniture: the low tops and steps of the play area (feet {@code 1.5} to
     * {@code top} over the floor block), a walk from the floor, never the bookcase's shelves.
     */
    public List<Stand> lowStands(double top) {
        List<Stand> out = new ArrayList<>();
        for (Stand s : reach) {
            double h = s.y() - box.minY();
            if (h >= 1.5 - 1e-9 && h <= top + 1e-9 && pieceOf(s) >= 0) {
                out.add(s);
            }
        }
        return out;
    }

    // ---- lava and safety ---------------------------------------------------------------------------

    /** Whether a stand is dry with the lava at {@code level}. */
    public boolean dry(Stand s, int level) {
        return s.y() >= box.minY() + 1 + level - 1e-9;
    }

    /** The play area's dry stands with the lava at {@code level}. */
    public List<Stand> safe(int level) {
        List<Stand> out = new ArrayList<>();
        for (Stand s : reach) {
            if (dry(s, level)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The nearest dry stand of the play area to feet at (x, y, z) with the lava at {@code level}, as a place to put
     * a player (the block's middle, facing the middle of the room); {@code null} when the lava covers everything
     * (Challenge's last level).
     */
    public Spot nearestSafe(int level, double x, double y, double z) {
        Stand best = nearestDry(level, x, y, z);
        return best == null ? null : spot(best);
    }

    /** The stand {@link #nearestSafe} puts a player on, or {@code null}. */
    public Stand nearestDry(int level, double x, double y, double z) {
        Stand best = null;
        double least = Double.MAX_VALUE;
        for (Stand s : reach) {
            if (!dry(s, level)) {
                continue;
            }
            double dx = s.x() + 0.5 - x;
            double dz = s.z() + 0.5 - z;
            double dy = (s.y() - y) * 0.5; // a spot a little higher is nearer than one across the room
            double d = dx * dx + dz * dz + dy * dy;
            if (d < least) {
                least = d;
                best = s;
            }
        }
        return best;
    }

    /** A stand as a place to put a player: its middle, facing the middle of the room. */
    public Spot spot(Stand s) {
        double x = s.x() + 0.5;
        double z = s.z() + 0.5;
        return new Spot(x, s.y(), z, facing(x, z));
    }

    /** Where a player joining with the lava at {@code level} arrives: arrival spot {@code n} while it is dry. */
    public Spot entry(int level, int n) {
        Spot s = spawn(n);
        if (s.y() >= box.minY() + 1 + level) {
            return s;
        }
        Spot safe = nearestSafe(level, s.x(), s.y(), s.z());
        return safe == null ? gallery(n) : safe;
    }

    /** The yaw that looks from (x, z) at the middle of the room. */
    public float facing(double x, double z) {
        double dx = (box.minX() + box.maxX() + 1) / 2.0 - x;
        double dz = (box.minZ() + box.maxZ() + 1) / 2.0 - z;
        return dx == 0 && dz == 0 ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * Whether a player with their feet at (x, y, z) is in the lava when it is {@code level} deep: their body meets a
     * lava cell below its surface.
     */
    public boolean touches(int level, double x, double y, double z) {
        if (level <= 0) {
            return false;
        }
        int top = box.minY() + Math.min(level, layers.size());
        return touches(x, y, z, (bx, by, bz) -> by <= top && air(bx, by, bz));
    }

    /**
     * 0.57.0: whether a player with feet at (x, y, z) is in the lava, where {@code lava} says which cells are lava
     * now (it may be up on one side of the room only: {@link LavaWriter#lavaAt}).
     */
    public boolean touches(double x, double y, double z, Lava lava) {
        int top = box.minY() + layers.size();
        int y0 = Math.max(box.minY() + 1, (int) Math.floor(y));
        int y1 = Math.min(top, (int) Math.floor(y + HEIGHT));
        double e = 1e-4;
        for (int bx = (int) Math.floor(x - HALF_WIDTH + e); bx <= (int) Math.floor(x + HALF_WIDTH - e); bx++) {
            for (int bz = (int) Math.floor(z - HALF_WIDTH + e); bz <= (int) Math.floor(z + HALF_WIDTH - e); bz++) {
                if (bx < inside.minX() || bx > inside.maxX() || bz < inside.minZ() || bz > inside.maxZ()) {
                    continue;
                }
                for (int by = y0; by <= y1; by++) {
                    if (lava.at(bx, by, bz) && y < by + LAVA_TOP) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code count} places for stars, the same for the same {@code seed}: spread over the open floor, no two close
     * together while there is room.
     */
    public List<Spot> starSpots(int count, long seed) {
        return starSpots(starStands, count, seed, 5);
    }

    /**
     * 0.57.0: {@code count} places for stars among {@code from}, the same for the same seed, no two within
     * {@code apartMost} blocks while there is room (fewer only when {@code from} runs out).
     */
    public static List<Spot> starSpots(Collection<Stand> from, int count, long seed, int apartMost) {
        List<Stand> pool = new ArrayList<>(from);
        pool.sort(Comparator.comparingInt(Stand::x).thenComparingInt(Stand::z).thenComparingDouble(Stand::y));
        Collections.shuffle(pool, new Random(seed));
        List<Stand> picked = new ArrayList<>();
        for (int apart = apartMost; apart >= 0 && picked.size() < count; apart--) {
            for (Stand s : pool) {
                if (picked.size() >= count) {
                    break;
                }
                boolean far = !picked.contains(s);
                for (Stand p : picked) {
                    far &= Math.max(Math.abs(p.x() - s.x()), Math.abs(p.z() - s.z())) >= apart;
                }
                if (far) {
                    picked.add(s);
                }
            }
        }
        List<Spot> out = new ArrayList<>();
        for (Stand s : picked) {
            out.add(new Spot(s.x() + 0.5, s.y(), s.z() + 0.5, 0f));
        }
        return out;
    }
}
