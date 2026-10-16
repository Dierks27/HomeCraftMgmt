package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.BlockOp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Plan;
import com.dierks.homecraft.games.gen.api.SignText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * What {@link BuildJob} must write, chunk by chunk, packed small (MOUNTAIN-V2-SPEC §3.6, §13.6).
 *
 * <p><b>Why.</b> A Mountain Run v2 plan is some 350,000 blocks. The index before this one kept an
 * object per block (its position, its block text and a wait timer) and a {@code HashSet<Long>} entry
 * per block, about 120 bytes a block: 40-50 MB held for the whole build. Here each chunk holds ONE
 * sorted {@code long[]}, a block packed into each long: 8 bytes a block (~3 MB for the mountain).
 * The block texts live once, in the palette's array; signs (a handful) stay as small records.
 *
 * <p><b>A cell</b> (one planned block), from the top bit down: 0 (so a cell is never negative), the
 * height above the half's floor ({@value #Y_BITS} bits), x and z in the chunk (4 bits each), its
 * place in the plan among this chunk's blocks ({@value #ORD_BITS} bits) and its palette index
 * ({@value #STATE_BITS} bits, all a {@link BlockOp} can hold). Sorted, the cells run bottom-up, then
 * along x, then z: exactly the order {@link BuildJob} writes a chunk's blocks in, and exactly the
 * order its scan of the chunk walks it in, so "does the plan name this spot?" is a cursor that only
 * moves forward ({@link Chunk#cursor}), or a binary search ({@link Chunk#names}). Two blocks at one
 * spot (a plan may have them) keep the plan's order between them, as a stable sort would, and the
 * place in the plan lets a pass name the first few differing blocks in plan order, as before. So
 * every write, its order, and every name are what they were (pinned against the old index by
 * {@code BuildJobIndexDifferentialTest}).
 *
 * <p><b>Made in two parts</b> (MOUNTAIN-V2-SPEC F11): {@link #prepare}, the part the size of the plan
 * (every block checked, packed and sorted), on any thread, so the engine makes it on the planner
 * thread with the plan; and {@link Prepared#resolve}, the palette and the signs in the world's
 * spelling, on the main thread (a world port's spelling cache isn't shared). A bad plan is refused
 * exactly as the object index refused it: a palette entry that isn't a block first, then the first
 * bad block in plan order, then the first bad sign.
 *
 * <p>A half taller than {@value #MAX_HEIGHT} blocks (taller than any Minecraft world can be) or a
 * chunk with more than {@value #MAX_PER_CHUNK} planned blocks can't be packed and is refused. Pure:
 * no Bukkit.
 */
final class BuildIndex {

    /** Bits for a palette index: a {@link BlockOp#state()} is a short that is never negative. */
    static final int STATE_BITS = 15;
    /** Bits for a block's place in the plan among its chunk's blocks. */
    static final int ORD_BITS = 28;
    /** Bits for a block's height above the half's floor. */
    static final int Y_BITS = 12;
    /** The tallest half that can be packed (no world is taller: Minecraft's limit is 4,064). */
    static final int MAX_HEIGHT = 1 << Y_BITS;
    /** The most planned blocks one chunk can hold. */
    static final int MAX_PER_CHUNK = 1 << ORD_BITS;

    private static final int ORD_SHIFT = STATE_BITS;
    private static final int Z_SHIFT = ORD_SHIFT + ORD_BITS;
    private static final int X_SHIFT = Z_SHIFT + 4;
    private static final int Y_SHIFT = X_SHIFT + 4;
    /** A cell shifted right by this is its spot: height, x, z ({@link #spotOf}). */
    private static final int SPOT_SHIFT = Z_SHIFT;
    private static final long STATE_MASK = (1L << STATE_BITS) - 1;
    private static final long ORD_MASK = (1L << ORD_BITS) - 1;

    /** A planned sign: the world's spelling of its block, and its four lines. */
    record Sign(int x, int y, int z, String state, List<String> lines) {
    }

    /** One chunk's planned blocks (sorted cells) and signs (in plan order). */
    static final class Chunk {
        private final int cx;
        private final int cz;
        private final long[] cells;
        private final List<Sign> signs;
        /** The signs' spots, sorted. */
        private final long[] signSpots;

        private Chunk(int cx, int cz, long[] cells, List<Sign> signs, int floor) {
            this.cx = cx;
            this.cz = cz;
            this.cells = cells;
            this.signs = List.copyOf(signs);
            this.signSpots = new long[signs.size()];
            for (int i = 0; i < signs.size(); i++) {
                Sign s = signs.get(i);
                signSpots[i] = spotOf(s.x() & 15, s.y() - floor, s.z() & 15);
            }
            Arrays.sort(signSpots);
        }

        /** How many planned blocks. */
        int size() {
            return cells.length;
        }

        /** Planned block {@code i} (bottom-up, then x, then z, then plan order): its x. */
        int x(int i) {
            return (cx << 4) | (int) ((cells[i] >>> X_SHIFT) & 15);
        }

        /** Its z. */
        int z(int i) {
            return (cz << 4) | (int) ((cells[i] >>> Z_SHIFT) & 15);
        }

        /** Its height above the half's floor. */
        int dy(int i) {
            return (int) (cells[i] >>> Y_SHIFT);
        }

        /** Its place in the plan among this chunk's blocks (0 first). */
        int ord(int i) {
            return (int) ((cells[i] >>> ORD_SHIFT) & ORD_MASK);
        }

        /** Its palette index. */
        int state(int i) {
            return (int) (cells[i] & STATE_MASK);
        }

        /** The signs, in plan order. */
        List<Sign> signs() {
            return signs;
        }

        /** Whether the plan names spot ({@code dy} above the floor, chunk x {@code lx}, z {@code lz}): binary search. */
        boolean names(int dy, int lx, int lz) {
            long spot = spotOf(lx, dy, lz);
            int lo = 0;
            int hi = cells.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long s = cells[mid] >>> SPOT_SHIFT;
                if (s < spot) {
                    lo = mid + 1;
                } else if (s > spot) {
                    hi = mid - 1;
                } else {
                    return true;
                }
            }
            return Arrays.binarySearch(signSpots, spot) >= 0;
        }

        /** A cursor over this chunk's named spots, asked in rising order (bottom-up, then x, then z). */
        Cursor cursor() {
            return new Cursor(this);
        }
    }

    /**
     * "Does the plan name this spot?" for spots asked in rising order (a chunk's scan): each answer
     * moves past the cells below it, so a whole chunk costs one walk of its cells.
     */
    static final class Cursor {
        private final Chunk chunk;
        private int cell;
        private int sign;

        private Cursor(Chunk chunk) {
            this.chunk = chunk;
        }

        /** Whether the plan names spot ({@code dy}, {@code lx}, {@code lz}); never asked a lower spot than the last. */
        boolean names(int dy, int lx, int lz) {
            long spot = spotOf(lx, dy, lz);
            long[] cells = chunk.cells;
            while (cell < cells.length && (cells[cell] >>> SPOT_SHIFT) < spot) {
                cell++;
            }
            if (cell < cells.length && (cells[cell] >>> SPOT_SHIFT) == spot) {
                return true;
            }
            long[] signs = chunk.signSpots;
            while (sign < signs.length && signs[sign] < spot) {
                sign++;
            }
            return sign < signs.length && signs[sign] == spot;
        }
    }

    private final Box half;
    private final int cx0;
    private final int cz0;
    private final int width;
    private final int depth;
    private final Chunk[] chunks;
    private final String[] states;
    private final boolean water;
    private final long blocks;

    private BuildIndex(Box half, Chunk[] chunks, String[] states, boolean water, long blocks) {
        this.half = half;
        this.cx0 = half.minX() >> 4;
        this.cz0 = half.minZ() >> 4;
        this.width = (half.maxX() >> 4) - cx0 + 1;
        this.depth = (half.maxZ() >> 4) - cz0 + 1;
        this.chunks = chunks;
        this.states = states;
        this.water = water;
        this.blocks = blocks;
    }

    /**
     * The index of {@code p} in {@code half}, every block text in the world's spelling
     * ({@code canonical}): {@link #prepare} then {@link Prepared#resolve}, on one thread.
     *
     * @throws IllegalArgumentException when a block or sign is outside the half or isn't a block (the
     *                                  first in plan order, as {@link BuildJob} always said), or the
     *                                  half or a chunk is too big to pack
     */
    static BuildIndex of(Box half, Plan p, UnaryOperator<String> canonical) {
        return prepare(half, p).resolve(canonical);
    }

    /**
     * The plan-sized part of the index, on any thread (the planner's: MOUNTAIN-V2-SPEC F11): every
     * block checked, counted, packed and sorted into its chunk. Pure and never throws: a bad block is
     * kept and thrown by {@link Prepared#resolve}, after the palette's own problems, as the old index
     * threw them.
     */
    static Prepared prepare(Box half, Plan p) {
        int states = p.palette().size();
        int cx0 = half.minX() >> 4;
        int cz0 = half.minZ() >> 4;
        int width = (half.maxX() >> 4) - cx0 + 1;
        int depth = (half.maxZ() >> 4) - cz0 + 1;
        List<BlockOp> ops = p.ops();
        try {
            if ((long) half.maxY() - half.minY() + 1 > MAX_HEIGHT) {
                throw new IllegalArgumentException("a half " + ((long) half.maxY() - half.minY() + 1)
                        + " blocks tall is taller than any world");
            }
            int[] count = new int[width * depth];
            boolean[] used = new boolean[states];
            for (BlockOp op : ops) { // checked and counted in plan order: the first bad block is the one named
                inside(half, op.x(), op.y(), op.z());
                int state = op.state();
                if (state >= states) {
                    throw new ArrayIndexOutOfBoundsException("Index " + state + " out of bounds for length " + states);
                }
                int c = ((op.x() >> 4) - cx0) * depth + ((op.z() >> 4) - cz0);
                if (++count[c] > MAX_PER_CHUNK) {
                    throw new IllegalArgumentException("the plan has more than " + MAX_PER_CHUNK
                            + " blocks in chunk " + (op.x() >> 4) + "," + (op.z() >> 4));
                }
                used[state] = true;
            }
            long[][] cells = new long[count.length][];
            int[] filled = new int[count.length];
            for (BlockOp op : ops) {
                int c = ((op.x() >> 4) - cx0) * depth + ((op.z() >> 4) - cz0);
                if (cells[c] == null) {
                    cells[c] = new long[count[c]];
                }
                int ord = filled[c]++;
                cells[c][ord] = (spotOf(op.x() & 15, op.y() - half.minY(), op.z() & 15) << SPOT_SHIFT)
                        | ((long) ord << ORD_SHIFT) | op.state();
            }
            for (long[] c : cells) {
                if (c != null) {
                    Arrays.sort(c);
                }
            }
            return new Prepared(half, p, cells, used, null);
        } catch (RuntimeException e) {
            return new Prepared(half, p, null, null, e);
        }
    }

    /**
     * An index made but for its block texts ({@link #prepare}); {@link #resolve} finishes it on the
     * thread that owns the world's spelling (the main thread: a world port's cache isn't shared).
     */
    static final class Prepared {
        private final Box half;
        private final Plan plan;
        private final long[][] cells;
        private final boolean[] used;
        private final RuntimeException bad;

        private Prepared(Box half, Plan plan, long[][] cells, boolean[] used, RuntimeException bad) {
            this.half = half;
            this.plan = plan;
            this.cells = cells;
            this.used = used;
            this.bad = bad;
        }

        /** The half it was made for. */
        Box half() {
            return half;
        }

        /** The plan it was made from. */
        Plan plan() {
            return plan;
        }

        /**
         * The index, every block text in the world's spelling: the palette's first (a text that isn't a
         * block throws here, as it always did first), then a bad block {@link #prepare} found, then each
         * sign in plan order (outside the half, or not a block). Costs the palette and the signs, not the
         * blocks.
         */
        BuildIndex resolve(UnaryOperator<String> canonical) {
            String[] states = new String[plan.palette().size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = canonical.apply(plan.palette().get(i));
            }
            if (bad != null) {
                throw bad;
            }
            boolean water = false;
            for (int i = 0; i < states.length; i++) {
                water |= used[i] && BuildJob.fluid(states[i]);
            }
            int cx0 = half.minX() >> 4;
            int cz0 = half.minZ() >> 4;
            int depth = (half.maxZ() >> 4) - cz0 + 1;
            List<List<Sign>> signs = new ArrayList<>(cells.length);
            for (int i = 0; i < cells.length; i++) {
                signs.add(null);
            }
            for (SignText s : plan.signs()) {
                inside(half, s.x(), s.y(), s.z());
                int c = ((s.x() >> 4) - cx0) * depth + ((s.z() >> 4) - cz0);
                if (signs.get(c) == null) {
                    signs.set(c, new ArrayList<>(2));
                }
                signs.get(c).add(new Sign(s.x(), s.y(), s.z(), canonical.apply(s.blockData()),
                        BuildJob.pad(s.lines())));
            }
            Chunk[] chunks = new Chunk[cells.length];
            for (int c = 0; c < cells.length; c++) {
                if (cells[c] == null && signs.get(c) == null) {
                    continue;
                }
                chunks[c] = new Chunk(cx0 + c / depth, cz0 + c % depth, cells[c] == null ? new long[0] : cells[c],
                        signs.get(c) == null ? List.of() : signs.get(c), half.minY());
            }
            return new BuildIndex(half, chunks, states, water, plan.ops().size());
        }
    }

    private static void inside(Box half, int x, int y, int z) {
        if (!half.contains(x, y, z)) {
            throw new IllegalArgumentException("the plan has a block at " + x + "," + y + "," + z
                    + ", outside its half (" + half.describe() + ")");
        }
    }

    /** A spot as the cells sort it: height, then x, then z. */
    private static long spotOf(int lx, int dy, int lz) {
        return ((long) dy << 8) | ((long) lx << 4) | lz;
    }

    /** Chunk ({@code cx}, {@code cz})'s planned blocks and signs, or {@code null} when it has none. */
    Chunk chunk(int cx, int cz) {
        long i = (long) cx - cx0;
        long k = (long) cz - cz0;
        return i < 0 || i >= width || k < 0 || k >= depth ? null : chunks[(int) (i * depth + k)];
    }

    /** The world's spelling of palette entry {@code state}. */
    String state(int state) {
        return states[state];
    }

    /** The height of a cell's floor: a block {@code dy} above it is at {@code floor() + dy}. */
    int floor() {
        return half.minY();
    }

    /** Whether the plan has water (its writes are staged over the whole half). */
    boolean water() {
        return water;
    }

    /** Planned blocks (not signs). */
    long blocks() {
        return blocks;
    }

    /**
     * Roughly the bytes it holds, counted from its arrays: 8 a planned block, plus each chunk's object,
     * array headers and signs. A JVM with compressed references (any heap under 32 GB) uses this or less.
     */
    long bytes() {
        long n = 16 + 4L * chunks.length; // the chunk array
        for (Chunk c : chunks) {
            if (c == null) {
                continue;
            }
            n += 32 + 16 + 8L * c.cells.length + 16 + 8L * c.signSpots.length; // object, cells, sign spots
            n += 128L * c.signs.size(); // a sign's record and its list of four lines (the texts are the plan's)
        }
        for (String s : states) {
            n += 8 + (s == null ? 0 : 40 + s.length());
        }
        return n;
    }
}
