package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;

import java.util.List;

/**
 * A half as the Dropper's physics sees it: every block sorted into what it does to a falling player
 * (EVENTS-DROPPER-SPEC §B.1.6). Built from a plan's own ops, never from the planner's bookkeeping,
 * so the validator proves what will actually stand.
 *
 * <ul>
 *   <li>{@link #WALL}: glass. A body scrapes along it (the plain hitbox, with vanilla's collision);
 *       a scrape isn't a landing.</li>
 *   <li>{@link #LEDGE}: the lime ledge. Collides like a wall; standing on it after leaving is a
 *       landing.</li>
 *   <li>{@link #SOLID}: everything else solid, the obstacle plates, their lights and the floor round
 *       a pool. Touching one with the hitbox grown by the clearance is a bonk.</li>
 *   <li>{@link #WATER}: the pool. Entering it is the splash.</li>
 * </ul>
 * Signs are not in it: they don't stop anyone. Outside the half is air.
 *
 * <p>Mutable only while a planner fills it; a plain byte per block (a 64 x 64 x 16 half is 64 KiB).
 */
public final class DropWorld {

    public static final byte AIR = 0;
    public static final byte WALL = 1;
    public static final byte LEDGE = 2;
    public static final byte SOLID = 3;
    public static final byte WATER = 4;

    private final Box half;
    private final byte[] cells;
    /** Per row, and per column, a bit for every kind there: a quick "nothing here" before a scan. */
    private final int[] rowKinds;
    private final int[] columnKinds;
    /** How many blocks of each kind each row and each column holds (what keeps the bits exact). */
    private final int[] rowCounts;
    private final int[] columnCounts;
    private static final int KINDS = 5;

    public DropWorld(Box half) {
        this.half = half;
        long v = half.volume();
        if (v > 4_000_000) {
            throw new IllegalArgumentException("a dropper half is small: " + half.describe());
        }
        this.cells = new byte[(int) v];
        this.rowKinds = new int[half.sizeY()];
        this.columnKinds = new int[half.sizeX() * half.sizeZ()];
        this.rowCounts = new int[half.sizeY() * KINDS];
        this.columnCounts = new int[half.sizeX() * half.sizeZ() * KINDS];
        for (int y = 0; y < half.sizeY(); y++) {
            rowCounts[y * KINDS] = half.sizeX() * half.sizeZ();
            rowKinds[y] = 1;
        }
        for (int c = 0; c < half.sizeX() * half.sizeZ(); c++) {
            columnCounts[c * KINDS] = half.sizeY();
            columnKinds[c] = 1;
        }
    }

    /** The half's blocks from a plan's ops, sorted by {@link DropBlocks#kind}. */
    public static DropWorld of(Plan plan) {
        return of(plan.half(), plan.palette(), plan.ops());
    }

    /** The same, from the parts. Ops outside the half are left out (the validator reports them). */
    public static DropWorld of(Box half, List<String> palette, List<BlockOp> ops) {
        DropWorld w = new DropWorld(half);
        byte[] kinds = new byte[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            kinds[i] = DropBlocks.kind(palette.get(i));
        }
        for (BlockOp op : ops) {
            if (op.state() < kinds.length) {
                w.set(op.x(), op.y(), op.z(), kinds[op.state()]);
            }
        }
        return w;
    }

    public Box half() {
        return half;
    }

    /** What block (x, y, z) is; {@link #AIR} outside the half. */
    public byte get(int x, int y, int z) {
        if (!half.contains(x, y, z)) {
            return AIR;
        }
        return cells[index(x, y, z)];
    }

    /** Set block (x, y, z); ignored outside the half. */
    public void set(int x, int y, int z, byte kind) {
        if (!half.contains(x, y, z)) {
            return;
        }
        int i = index(x, y, z);
        byte old = cells[i];
        if (old == kind) {
            return;
        }
        cells[i] = kind;
        int row = y - half.minY();
        int col = (x - half.minX()) * half.sizeZ() + (z - half.minZ());
        if (--rowCounts[row * KINDS + old] == 0) {
            rowKinds[row] &= ~(1 << old);
        }
        if (rowCounts[row * KINDS + kind]++ == 0) {
            rowKinds[row] |= 1 << kind;
        }
        if (--columnCounts[col * KINDS + old] == 0) {
            columnKinds[col] &= ~(1 << old);
        }
        if (columnCounts[col * KINDS + kind]++ == 0) {
            columnKinds[col] |= 1 << kind;
        }
    }

    /** Whether block (x, y, z) is solid (a wall, the ledge or an obstacle). */
    public boolean solid(int x, int y, int z) {
        byte k = get(x, y, z);
        return k == WALL || k == LEDGE || k == SOLID;
    }

    /**
     * The first block of kind {@code kind} that the box [x1, x2] x [y1, y2] x [z1, z2] overlaps (a
     * face touching a block doesn't), as {x, y, z}; {@code null} when none does.
     */
    public int[] overlap(double x1, double y1, double z1, double x2, double y2, double z2, byte kind) {
        return overlapAny(x1, y1, z1, x2, y2, z2, 1 << kind);
    }

    /**
     * The first block whose kind is in {@code kinds} (a bit per kind: {@code 1 << WALL | 1 << LEDGE})
     * that the box overlaps, as {x, y, z}; {@code null} when none does.
     */
    public int[] overlapAny(double x1, double y1, double z1, double x2, double y2, double z2, int kinds) {
        int bx1 = (int) Math.floor(x1);
        int by1 = (int) Math.floor(y1);
        int bz1 = (int) Math.floor(z1);
        int bx2 = (int) Math.ceil(x2) - 1;
        int by2 = (int) Math.ceil(y2) - 1;
        int bz2 = (int) Math.ceil(z2) - 1;
        // only the part inside the half can hold anything
        bx1 = Math.max(bx1, half.minX());
        by1 = Math.max(by1, half.minY());
        bz1 = Math.max(bz1, half.minZ());
        bx2 = Math.min(bx2, half.maxX());
        by2 = Math.min(by2, half.maxY());
        bz2 = Math.min(bz2, half.maxZ());
        int sx = half.sizeX();
        int sz = half.sizeZ();
        boolean rows = false;
        for (int y = by1; y <= by2 && !rows; y++) {
            rows = (rowKinds[y - half.minY()] & kinds) != 0;
        }
        if (!rows) {
            return null;
        }
        boolean columns = false;
        for (int x = bx1; x <= bx2 && !columns; x++) {
            for (int z = bz1; z <= bz2 && !columns; z++) {
                columns = (columnKinds[(x - half.minX()) * sz + (z - half.minZ())] & kinds) != 0;
            }
        }
        if (!columns) {
            return null;
        }
        for (int y = by1; y <= by2; y++) {
            int rowBase = (y - half.minY()) * sx;
            for (int x = bx1; x <= bx2; x++) {
                int base = (rowBase + (x - half.minX())) * sz - half.minZ();
                for (int z = bz1; z <= bz2; z++) {
                    if ((kinds & (1 << cells[base + z])) != 0) {
                        return new int[]{x, y, z};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Whether nothing but air can be in the box: no row it spans holds any of {@code rowKinds}, and no
     * column it spans holds any of {@code columnKinds}. A quick test before the exact ones.
     */
    public boolean quiet(double x1, double y1, double z1, double x2, double y2, double z2, int rowKinds,
                         int columnKinds) {
        int by1 = Math.max((int) Math.floor(y1), half.minY());
        int by2 = Math.min((int) Math.ceil(y2) - 1, half.maxY());
        for (int y = by1; y <= by2; y++) {
            if ((this.rowKinds[y - half.minY()] & rowKinds) != 0) {
                return false;
            }
        }
        int bx1 = Math.max((int) Math.floor(x1), half.minX());
        int bx2 = Math.min((int) Math.ceil(x2) - 1, half.maxX());
        int bz1 = Math.max((int) Math.floor(z1), half.minZ());
        int bz2 = Math.min((int) Math.ceil(z2) - 1, half.maxZ());
        int sz = half.sizeZ();
        for (int x = bx1; x <= bx2; x++) {
            for (int z = bz1; z <= bz2; z++) {
                if ((this.columnKinds[(x - half.minX()) * sz + (z - half.minZ())] & columnKinds) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the box overlaps a wall or the ledge (the blocks a body collides with). */
    public boolean blocks(double x1, double y1, double z1, double x2, double y2, double z2) {
        return overlapAny(x1, y1, z1, x2, y2, z2, COLLIDES) != null;
    }

    /** The kinds a body collides with: walls and the ledge. */
    public static final int COLLIDES = 1 << WALL | 1 << LEDGE;

    private int index(int x, int y, int z) {
        return ((y - half.minY()) * half.sizeX() + (x - half.minX())) * half.sizeZ() + (z - half.minZ());
    }
}
