package com.dierks.homecraft.games.gen.boat;

import com.dierks.homecraft.games.gen.api.BlockOp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Mountain Run v2's blocks while they are laid (MOUNTAIN-V2-SPEC §3.6): column-sparse, because a
 * 480 × 176 × 640 half is 54 million cells and a plan places about 350 thousand. Each column keeps its
 * own short list of (height, block) entries; a column with nothing in it costs a null.
 *
 * <p>Blocks are named by their palette index, so a column entry is one int: the height above the
 * half's floor in the high bits, the index + 1 in the low 16. The ops come out sorted by (x, z, y),
 * which is what the compact archive codec and the plan hash read best (§3.6: the order is free, F30).
 * Not thread-safe; a plan is made on one thread.
 */
final class Voxels {

    final int sx;
    final int sy;
    final int sz;
    final int y0;
    /** The block texts, by index. */
    final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();
    private final int[][] cols;
    private final int[] count;
    private int total;

    /** An empty store for a half of {@code sx} x {@code sy} x {@code sz} whose floor is world height {@code y0}. */
    Voxels(int sx, int sy, int sz, int y0) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.y0 = y0;
        this.cols = new int[sx * sz][];
        this.count = new int[sx * sz];
    }

    /** How many blocks are laid. */
    int size() {
        return total;
    }

    boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < sx && z < sz;
    }

    boolean in(int x, int y, int z) {
        return inside(x, z) && y >= y0 && y < y0 + sy;
    }

    /** The palette index of {@code block}, added when new. */
    int state(String block) {
        Integer i = index.get(block);
        if (i == null) {
            i = palette.size();
            palette.add(block);
            index.put(block, i);
        }
        return i;
    }

    /** Lay {@code block} at local column (x, z), world height y (replacing what was there; outside: nothing). */
    void put(int x, int y, int z, String block) {
        if (!in(x, y, z)) {
            return;
        }
        int s = state(block);
        int c = x * sz + z;
        int ly = y - y0;
        int[] col = cols[c];
        int n = count[c];
        if (col != null) {
            for (int i = 0; i < n; i++) {
                if ((col[i] >>> 16) == ly) {
                    col[i] = (ly << 16) | (s + 1);
                    return;
                }
            }
        }
        if (col == null) {
            col = new int[4];
            cols[c] = col;
        } else if (n == col.length) {
            col = Arrays.copyOf(col, n * 2);
            cols[c] = col;
        }
        col[n] = (ly << 16) | (s + 1);
        count[c] = n + 1;
        total++;
    }

    /** Take away whatever is at (x, y, z). */
    void clear(int x, int y, int z) {
        if (!in(x, y, z)) {
            return;
        }
        int c = x * sz + z;
        int ly = y - y0;
        int[] col = cols[c];
        int n = count[c];
        for (int i = 0; col != null && i < n; i++) {
            if ((col[i] >>> 16) == ly) {
                col[i] = col[n - 1];
                count[c] = n - 1;
                total--;
                return;
            }
        }
    }

    /** The block at (x, y, z), or {@code null} for air (and outside). */
    String at(int x, int y, int z) {
        if (!in(x, y, z)) {
            return null;
        }
        int c = x * sz + z;
        int ly = y - y0;
        int[] col = cols[c];
        for (int i = 0; col != null && i < count[c]; i++) {
            if ((col[i] >>> 16) == ly) {
                return palette.get((col[i] & 0xFFFF) - 1);
            }
        }
        return null;
    }

    boolean empty(int x, int y, int z) {
        return in(x, y, z) && at(x, y, z) == null;
    }

    /** The highest block's height in column (x, z), or {@code y0 - 1} when it is empty. */
    int top(int x, int z) {
        if (!inside(x, z)) {
            return y0 - 1;
        }
        int c = x * sz + z;
        int best = -1;
        int[] col = cols[c];
        for (int i = 0; col != null && i < count[c]; i++) {
            best = Math.max(best, col[i] >>> 16);
        }
        return best < 0 ? y0 - 1 : best + y0;
    }

    /** Whether column (x, z) holds any block. */
    boolean any(int x, int z) {
        return inside(x, z) && count[x * sz + z] > 0;
    }

    /** Every block of column (x, z): {world y, palette index} pairs, in no particular order. */
    int[][] column(int x, int z) {
        int c = x * sz + z;
        int n = count[c];
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new int[]{(cols[c][i] >>> 16) + y0, (cols[c][i] & 0xFFFF) - 1};
        }
        return out;
    }

    /**
     * The ops, by (x, z, y), in world coordinates ({@code wx}, {@code wz} the half's min corner), each
     * naming an entry of {@link #compactPalette} (unused texts dropped, first use first).
     */
    List<BlockOp> ops(int wx, int wz, List<String> compactPalette) {
        int[] remap = new int[palette.size()];
        Arrays.fill(remap, -1);
        compactPalette.clear();
        List<BlockOp> out = new ArrayList<>(total);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int c = x * sz + z;
                int n = count[c];
                if (n == 0) {
                    continue;
                }
                int[] col = Arrays.copyOf(cols[c], n);
                Arrays.sort(col);
                for (int v : col) {
                    int s = (v & 0xFFFF) - 1;
                    if (remap[s] < 0) {
                        compactPalette.add(palette.get(s));
                        remap[s] = compactPalette.size() - 1;
                    }
                    out.add(new BlockOp(wx + x, (v >>> 16) + y0, wz + z, (short) remap[s]));
                }
            }
        }
        return out;
    }
}
