package com.dierks.homecraft.games.cabinet.merge;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * One Ore Merge game (spec §10b): 2048 on a 4 by 4 grid, with no Bukkit in it.
 *
 * <p>A tile is kept as its <b>level</b>: 1 is coal (2), 2 copper (4) … 11 the dragon egg (2048),
 * 0 an empty square; its value is {@code 2^level}. A move slides every tile as far as it goes
 * toward one edge, and two equal tiles that meet merge into the next level up. A tile merges at
 * most once per move, so a row of four coal becomes two copper, never one iron. The dragon egg is
 * the top ore: two of them don't merge.
 *
 * <p>After every move that changed anything, one new tile appears on an empty square: coal nine
 * times in ten, copper otherwise. Where and which come from the game's own seeded random, so the
 * same seed and the same moves always give the same game (the daily board's seed is the day's).
 * The score is the sum of the values of every tile made by a merge; the biggest tile made is
 * reported separately, because that is what the milestones and the daily goal ask for.
 */
public final class MergeEngine {

    public static final int SIZE = 4;
    public static final int CELLS = SIZE * SIZE;
    /** The dragon egg: the top ore, which doesn't merge further. */
    public static final int TOP = 11;
    /** The diamond (256): the daily goal. */
    public static final int DIAMOND = 8;

    private static final String[] NAMES = {"Empty", "Coal", "Copper", "Iron", "Redstone", "Lapis", "Gold", "Emerald",
            "Diamond", "Netherite", "Nether star", "Dragon egg"};

    /** Which way the tiles slide. */
    public enum Dir { LEFT, RIGHT, UP, DOWN }

    private final int[] level = new int[CELLS];
    private final boolean[] merged = new boolean[CELLS];
    private final SplittableRandom rng;
    private int spawned = -1;
    private long score;
    private int biggest;
    private int moves;
    private boolean over;

    /** A new game: two tiles on an empty grid. */
    public MergeEngine(long seed) {
        this.rng = new SplittableRandom(seed);
        spawn();
        spawn();
        biggest = max();
    }

    /** Test hook: a game starting from {@code levels} (row by row), spawning from {@code seed}. */
    MergeEngine(int[] levels, long seed) {
        this.rng = new SplittableRandom(seed);
        System.arraycopy(levels, 0, level, 0, CELLS);
        biggest = max();
        over = !canMove();
    }

    // ---- play ---------------------------------------------------------------------------------

    /**
     * Slide every tile toward {@code dir}. When anything moved, the merges score, a new tile
     * appears and the game ends if no move is left. A move that changes nothing does nothing
     * (false), and spawns nothing.
     */
    public boolean move(Dir dir) {
        if (over) {
            return false;
        }
        Arrays.fill(merged, false);
        boolean changed = false;
        for (int line = 0; line < SIZE; line++) {
            int[] cells = cells(dir, line);
            int[] in = new int[SIZE];
            for (int i = 0; i < SIZE; i++) {
                in[i] = level[cells[i]];
            }
            Slide s = slide(in);
            for (int i = 0; i < SIZE; i++) {
                level[cells[i]] = s.out()[i];
                merged[cells[i]] = s.merged()[i];
            }
            score += s.gained();
            changed |= s.changed();
        }
        if (!changed) {
            return false;
        }
        moves++;
        spawn();
        biggest = Math.max(biggest, max());
        over = !canMove();
        return true;
    }

    /** Whether any move would change the grid: an empty square, or two equal neighbours that can merge. */
    public boolean canMove() {
        for (int c = 0; c < CELLS; c++) {
            if (level[c] == 0) {
                return true;
            }
            int x = c % SIZE;
            int y = c / SIZE;
            if (x + 1 < SIZE && mergeable(level[c], level[c + 1])) {
                return true;
            }
            if (y + 1 < SIZE && mergeable(level[c], level[c + SIZE])) {
                return true;
            }
        }
        return false;
    }

    // ---- the public view ----------------------------------------------------------------------

    /** The level on square {@code cell} (row by row), 0 = empty. */
    public int level(int cell) {
        return level[cell];
    }

    /** Whether square {@code cell} was made by a merge on the last move. */
    public boolean merged(int cell) {
        return merged[cell];
    }

    /** The square the last new tile appeared on, or -1. */
    public int spawned() {
        return spawned;
    }

    /** The sum of every merge so far. */
    public long score() {
        return score;
    }

    /** The biggest level made so far. */
    public int biggest() {
        return biggest;
    }

    public int moves() {
        return moves;
    }

    /** No move is left. */
    public boolean over() {
        return over;
    }

    /** A level's value: coal 2, copper 4 … dragon egg 2048 (0 for an empty square). */
    public static long value(int level) {
        return level <= 0 ? 0 : 1L << level;
    }

    /** A level's ore, in words ("Diamond"); "Empty" for 0. */
    public static String name(int level) {
        return NAMES[Math.max(0, Math.min(TOP, level))];
    }

    /** The level whose value is exactly {@code value}, or -1 when no tile is worth that. */
    public static int levelOf(long value) {
        for (int level = 1; level <= TOP; level++) {
            if (value(level) == value) {
                return level;
            }
        }
        return -1;
    }


    // ---- the rules, pure ----------------------------------------------------------------------

    /**
     * One line after a slide toward its index 0.
     *
     * @param out     the levels after the slide
     * @param merged  which squares were made by a merge
     * @param gained  the value of every tile a merge made
     * @param changed whether anything moved or merged
     */
    record Slide(int[] out, boolean[] merged, long gained, boolean changed) {
    }

    /**
     * Slide one line toward its index 0: tiles close up, and each pair of equal neighbours merges
     * once, the one nearer the edge first ({@code [2,2,2,2]} → {@code [4,4,0,0]},
     * {@code [2,2,4,0]} → {@code [4,4,0,0]}, never {@code [8,0,0,0]}).
     */
    static Slide slide(int[] in) {
        int[] out = new int[in.length];
        boolean[] made = new boolean[in.length];
        long gained = 0;
        int n = 0;
        for (int v : in) {
            if (v == 0) {
                continue;
            }
            if (n > 0 && !made[n - 1] && mergeable(out[n - 1], v)) {
                out[n - 1] = v + 1;
                made[n - 1] = true;
                gained += value(v + 1);
            } else {
                out[n++] = v;
            }
        }
        return new Slide(out, made, gained, !Arrays.equals(in, out));
    }

    private static boolean mergeable(int a, int b) {
        return a != 0 && a == b && a < TOP;
    }

    /** The squares of line {@code line}, ordered from the edge the tiles slide toward. */
    static int[] cells(Dir dir, int line) {
        int[] out = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            out[i] = switch (dir) {
                case LEFT -> line * SIZE + i;
                case RIGHT -> line * SIZE + (SIZE - 1 - i);
                case UP -> i * SIZE + line;
                case DOWN -> (SIZE - 1 - i) * SIZE + line;
            };
        }
        return out;
    }

    /** A new coal (9 in 10) or copper on a random empty square, from the game's seed. */
    private void spawn() {
        int empty = 0;
        for (int v : level) {
            if (v == 0) {
                empty++;
            }
        }
        spawned = -1;
        if (empty == 0) {
            return;
        }
        int pick = rng.nextInt(empty);
        int lvl = rng.nextInt(10) == 0 ? 2 : 1;
        for (int c = 0; c < CELLS; c++) {
            if (level[c] == 0 && pick-- == 0) {
                level[c] = lvl;
                spawned = c;
                return;
            }
        }
    }

    private int max() {
        int m = 0;
        for (int v : level) {
            m = Math.max(m, v);
        }
        return m;
    }
}
