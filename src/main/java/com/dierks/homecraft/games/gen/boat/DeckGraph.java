package com.dierks.homecraft.games.gen.boat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The Mountain Run's track as a graph (Course Variety §2.10 V2, V3, V6): which columns a boat drives
 * on, at what height, where a boat fits, and where it can get to. Pure, built from the drive
 * heights alone, so the validator (and later the planner's fun gates) can ask it without a server.
 *
 * <p><b>Cells and moves.</b> Each column of the half holds at most one drive cell (ice or sand a boat
 * sits on), at a block height. A boat never climbs, not even a 0.125 step (fact F1), so a move goes
 * to a side neighbour at the same height or lower, never higher: {@link #reach} is where a boat
 * starting at a cell can get to, going only that way.
 *
 * <p><b>Decks.</b> The hull is 1.375 wide, so a cell is <i>boat-fit</i> ({@link #fit}) when some
 * 2 × 2 square of drive cells at one height holds it. A deck is boat-fit cells joined side to side
 * at one height; where two decks touch, the lower one is one drop down from the higher
 * ({@link #below}). Every edge goes down, so the graph has no cycles, and "every deck a boat can
 * reach from the start reaches the finish" is a plain reachability question
 * ({@link #decksFrom}, {@link #decksTo}): turning round inside a deck is always possible (fact F5),
 * so a boat stuck anywhere is stuck in a deck that doesn't reach the finish.
 */
public final class DeckGraph {

    /** The height of a column with no drive cell. */
    public static final int NONE = Integer.MIN_VALUE;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final int sx;
    private final int sz;
    private final int[] height;
    private final boolean[] fit;
    private final int[] deck;
    private final List<Integer> deckHeight = new ArrayList<>();
    private final List<Integer> deckFirst = new ArrayList<>();
    private final List<TreeSet<Integer>> below = new ArrayList<>();

    private DeckGraph(int sx, int sz, int[] height) {
        this.sx = sx;
        this.sz = sz;
        this.height = height;
        this.fit = new boolean[sx * sz];
        this.deck = new int[sx * sz];
        java.util.Arrays.fill(deck, -1);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                fit[x * sz + z] = drive(x, z) && fits(x, z);
            }
        }
        // decks: boat-fit cells joined side to side at one height
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < sx * sz; i++) {
            if (!fit[i] || deck[i] >= 0) {
                continue;
            }
            int id = deckHeight.size();
            deckHeight.add(height[i]);
            deckFirst.add(i);
            below.add(new TreeSet<>());
            deck[i] = id;
            queue.add(i);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                int x = c / sz;
                int z = c % sz;
                for (int[] s : SIDES) {
                    int n = index(x + s[0], z + s[1]);
                    if (n >= 0 && fit[n] && deck[n] < 0 && height[n] == height[c]) {
                        deck[n] = id;
                        queue.add(n);
                    }
                }
            }
        }
        // edges: a boat-fit cell beside a lower boat-fit cell is a drop from its deck to that one
        for (int i = 0; i < sx * sz; i++) {
            if (!fit[i]) {
                continue;
            }
            int x = i / sz;
            int z = i % sz;
            for (int[] s : SIDES) {
                int n = index(x + s[0], z + s[1]);
                if (n >= 0 && fit[n] && height[n] < height[i]) {
                    below.get(deck[i]).add(deck[n]);
                }
            }
        }
    }

    /**
     * The graph of a half whose column (x, z) has its drive cell at block height
     * {@code heights[x][z]}, or {@link #NONE}.
     */
    public static DeckGraph of(int[][] heights) {
        int sx = heights.length;
        int sz = sx == 0 ? 0 : heights[0].length;
        int[] h = new int[sx * sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                h[x * sz + z] = heights[x][z];
            }
        }
        return new DeckGraph(sx, sz, h);
    }

    public int sizeX() {
        return sx;
    }

    public int sizeZ() {
        return sz;
    }

    /** Whether (x, z) is inside and has a drive cell. */
    public boolean drive(int x, int z) {
        int i = index(x, z);
        return i >= 0 && height[i] != NONE;
    }

    /** The drive cell's block height at (x, z), or {@link #NONE}. */
    public int height(int x, int z) {
        int i = index(x, z);
        return i < 0 ? NONE : height[i];
    }

    /** Whether a boat fits at (x, z): some 2 × 2 square of drive cells at one height holds it. */
    public boolean fit(int x, int z) {
        int i = index(x, z);
        return i >= 0 && fit[i];
    }

    /** The deck (x, z) is on, or -1 when a boat doesn't fit there. */
    public int deck(int x, int z) {
        int i = index(x, z);
        return i < 0 ? -1 : deck[i];
    }

    /** How many decks there are. */
    public int decks() {
        return deckHeight.size();
    }

    /** Deck {@code d}'s block height. */
    public int deckHeight(int d) {
        return deckHeight.get(d);
    }

    /** One cell of deck {@code d}, {x, z} (the first in x-then-z order), for saying where it is. */
    public int[] deckCell(int d) {
        int i = deckFirst.get(d);
        return new int[]{i / sz, i % sz};
    }

    /** The decks one drop down from deck {@code d}, in order. */
    public List<Integer> below(int d) {
        return List.copyOf(below.get(d));
    }

    /** The decks a boat on deck {@code from} can reach (itself included), going only down. */
    public boolean[] decksFrom(int from) {
        boolean[] seen = new boolean[decks()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[from] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            for (int n : below.get(queue.poll())) {
                if (!seen[n]) {
                    seen[n] = true;
                    queue.add(n);
                }
            }
        }
        return seen;
    }

    /** The decks from which deck {@code to} can be reached (itself included). */
    public boolean[] decksTo(int to) {
        boolean[] seen = new boolean[decks()];
        seen[to] = true;
        // the graph only goes down, so one pass from the highest deck to the lowest settles each
        Integer[] order = new Integer[decks()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(deckHeight.get(a), deckHeight.get(b)));
        for (int d : order) {
            for (int n : below.get(d)) {
                if (seen[n]) {
                    seen[d] = true;
                    break;
                }
            }
        }
        return seen;
    }

    /**
     * Where a boat starting at drive cell (x, z) can get to, cell by cell (itself included): side
     * steps onto a drive cell at the same height or lower, never onto one in {@code blocked} (a
     * checkpoint's disk, when asking whether it is a cut). {@code blocked} may be {@code null}.
     * Nothing is reached when (x, z) isn't a drive cell or is blocked.
     */
    public boolean[][] reach(int x, int z, boolean[][] blocked) {
        boolean[][] seen = new boolean[sx][sz];
        if (!drive(x, z) || (blocked != null && blocked[x][z])) {
            return seen;
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen[x][z] = true;
        queue.add(new int[]{x, z});
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int h = height(c[0], c[1]);
            for (int[] s : SIDES) {
                int nx = c[0] + s[0];
                int nz = c[1] + s[1];
                if (drive(nx, nz) && !seen[nx][nz] && height(nx, nz) <= h
                        && (blocked == null || !blocked[nx][nz])) {
                    seen[nx][nz] = true;
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        return seen;
    }

    /**
     * How many side steps each drive cell is from the nearest of {@code from}, going either way
     * (up or down, as a sphere's reach doesn't care which way the track runs), up to
     * {@code limit}; -1 for farther, or no drive cell.
     */
    public int[][] steps(List<int[]> from, int limit) {
        int[][] out = new int[sx][sz];
        for (int[] row : out) {
            java.util.Arrays.fill(row, -1);
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int[] c : from) {
            if (drive(c[0], c[1]) && out[c[0]][c[1]] < 0) {
                out[c[0]][c[1]] = 0;
                queue.add(c);
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int d = out[c[0]][c[1]];
            if (d >= limit) {
                continue;
            }
            for (int[] s : SIDES) {
                int nx = c[0] + s[0];
                int nz = c[1] + s[1];
                if (drive(nx, nz) && out[nx][nz] < 0) {
                    out[nx][nz] = d + 1;
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        return out;
    }

    private boolean fits(int x, int z) {
        int h = height(x, z);
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                if (height(x + ox, z + oz) == h && height(x + ox + 1, z + oz) == h && height(x + ox, z + oz + 1) == h
                        && height(x + ox + 1, z + oz + 1) == h) {
                    return true;
                }
            }
        }
        return false;
    }

    private int index(int x, int z) {
        return x < 0 || z < 0 || x >= sx || z >= sz ? -1 : x * sz + z;
    }
}
