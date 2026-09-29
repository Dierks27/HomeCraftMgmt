package com.dierks.homecraft.games.arena.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * What the floors do during one round (EVENTS-DROPPER-SPEC §B.3.3 step 4): every block a player
 * stands on turns red at once and is air {@code fade_ticks} later; after {@code round_seconds} the
 * floors fall in from the edges, one ring every 2 s; below the bottom floor a player is out.
 *
 * <p>Why "red at once, gone a moment later" and not "gone at once": the red is the warning. A
 * child sees where they have been and where not to go, and the half second is exactly long enough
 * to step on, never long enough to stand on. Standing still doesn't help and neither does jumping
 * in place: every landing marks the cells again, and a jump lasts longer than the fade.
 *
 * <p>Pure and deterministic. One instance is one round, made on whole floors at Go and fed one
 * {@link #step} per tick. It never looks at a world: it decides, and returns the
 * {@link FloorWrite}s for {@code FloorWriter} to make. Only planned floor cells are ever written,
 * and only SOLID to RED to AIR.
 *
 * <p><b>Order within a tick</b>, the same everywhere: cells whose fade is over turn to air, then a
 * sudden-death ring (if one is due) turns red, then the cells under each player's feet (in the
 * order given) turn red. So a cell that goes to air this tick is never marked again, and a player
 * standing on it this tick is already falling.
 */
public final class FloorRules {

    /** How far above a floor top the feet may be and still mark it (a landing is caught early). */
    public static final double STAND_ABOVE = 0.1;
    /** Slack below a top for rounding: feet at 200.9999999 are on the floor at 201. */
    public static final double STAND_BELOW = 1e-6;
    /** Half a player's width: the footprint is 0.6 x 0.6. */
    public static final double HALF_WIDTH = 0.3;
    /** A footprint that only touches a cell's edge isn't on it (and float sums land on edges). */
    static final double EDGE = 1e-7;

    private final FloorLayout layout;
    private final int fadeTicks;
    private final long suddenDeathAt;
    private final int ringTicks;
    private final CellState[][] state;
    private final int[] left;
    /** Cells waiting to turn to air, in the order they turned red: {deadline, layer, ordinal}. */
    private final ArrayDeque<long[]> fading = new ArrayDeque<>();
    private long lastTick = -1;
    private int ringsDone;
    private long marks;
    private long fades;

    /**
     * @param layout        the week's floors (whole, as the reset left them)
     * @param fadeTicks     ticks from red to air
     * @param suddenDeathAt the play tick at which the first ring falls
     * @param ringTicks     ticks between rings
     */
    public FloorRules(FloorLayout layout, int fadeTicks, long suddenDeathAt, int ringTicks) {
        if (layout == null) {
            throw new IllegalArgumentException("the floors need a layout");
        }
        if (fadeTicks < 1 || ringTicks < 1 || suddenDeathAt < 0) {
            throw new IllegalArgumentException("fade " + fadeTicks + ", rings every " + ringTicks
                    + " and sudden death at " + suddenDeathAt + " must be positive");
        }
        this.layout = layout;
        this.fadeTicks = fadeTicks;
        this.suddenDeathAt = suddenDeathAt;
        this.ringTicks = ringTicks;
        this.state = new CellState[layout.layerCount()][];
        this.left = new int[layout.layerCount()];
        for (int i = 0; i < layout.layerCount(); i++) {
            state[i] = new CellState[layout.cellCount(i)];
            Arrays.fill(state[i], CellState.SOLID);
            left[i] = layout.cellCount(i);
        }
    }

    /** The floors for a round with these settings. */
    public static FloorRules of(FloorLayout layout, RoundSettings settings) {
        return new FloorRules(layout, settings.fadeTicks(), settings.roundTicks(), RoundSettings.RING_TICKS);
    }

    /**
     * One tick of play. {@code playTick} counts from 0 at the first tick after Go and must go up
     * each call; a skipped tick is caught up (every fade and ring that fell due is applied).
     *
     * @param feet the players still in, in a fixed order (the round's starter order)
     * @return the blocks to write, in order
     */
    public List<FloorWrite> step(long playTick, Collection<Feet> feet) {
        if (playTick <= lastTick) {
            throw new IllegalStateException("play tick " + playTick + " after " + lastTick
                    + ": the floors only move forward");
        }
        lastTick = playTick;
        List<FloorWrite> out = new ArrayList<>();
        while (!fading.isEmpty() && fading.peekFirst()[0] <= playTick) {
            long[] f = fading.pollFirst();
            int layer = (int) f[1];
            int n = (int) f[2];
            if (state[layer][n] == CellState.RED) {
                state[layer][n] = CellState.AIR;
                left[layer]--;
                fades++;
                out.add(write(layer, n, CellState.AIR));
            }
        }
        while (ringsDone < layout.maxRings() && playTick >= suddenDeathAt + (long) ringsDone * ringTicks) {
            for (int layer = 0; layer < layout.layerCount(); layer++) {
                for (int n = 0; n < state[layer].length; n++) {
                    if (layout.ring(layer, n) <= ringsDone) {
                        mark(layer, n, playTick, out);
                    }
                }
            }
            ringsDone++;
        }
        if (feet != null) {
            for (Feet f : feet) {
                int layer = standingOn(f);
                if (layer < 0) {
                    continue;
                }
                for (Cell c : footprint(f.x(), f.z())) {
                    int n = layout.ordinal(layer, c.x(), c.z());
                    if (n >= 0) {
                        mark(layer, n, playTick, out);
                    }
                }
            }
        }
        return out;
    }

    private void mark(int layer, int n, long playTick, List<FloorWrite> out) {
        if (state[layer][n] != CellState.SOLID) {
            return;
        }
        state[layer][n] = CellState.RED;
        marks++;
        fading.addLast(new long[]{playTick + fadeTicks, layer, n});
        out.add(write(layer, n, CellState.RED));
    }

    private FloorWrite write(int layer, int n, CellState s) {
        Cell c = layout.layer(layer).cells().get(n);
        return new FloorWrite(layer, c.x(), layout.layer(layer).y(), c.z(), s);
    }

    // ---- the geometry (static, so the wiring and the simulation read players the same way) ----

    /**
     * The cells under a 0.6 x 0.6 footprint centred on (x, z): one, two or four. A footprint that
     * only touches a cell's edge isn't on it. Sorted by x, then z.
     */
    public static List<Cell> footprint(double x, double z) {
        int x0 = (int) Math.floor(x - HALF_WIDTH + EDGE);
        int x1 = (int) Math.floor(x + HALF_WIDTH - EDGE);
        int z0 = (int) Math.floor(z - HALF_WIDTH + EDGE);
        int z1 = (int) Math.floor(z + HALF_WIDTH - EDGE);
        List<Cell> cells = new ArrayList<>(4);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                cells.add(new Cell(cx, cz));
            }
        }
        return cells;
    }

    /**
     * The floor a player is standing on (or landing on this tick): feet within
     * {@value #STAND_ABOVE} above its top and not going up; -1 when none. Floors are at least
     * {@link FloorLayout#MIN_GAP} apart, so at most one matches.
     */
    public int standingOn(Feet f) {
        if (f == null || f.vy() > 0) {
            return -1;
        }
        for (int i = 0; i < layout.layerCount(); i++) {
            int top = layout.topY(i);
            if (f.y() >= top - STAND_BELOW && f.y() <= top + STAND_ABOVE) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a player whose feet are here is out: below {@code out_y}. */
    public boolean isOut(Feet f) {
        return f != null && f.y() < layout.outY();
    }

    /** Whether floor {@code layer} holds up a footprint at (x, z): any cell under it red or whole. */
    public boolean holds(int layer, double x, double z) {
        for (Cell c : footprint(x, z)) {
            if (state(layer, c.x(), c.z()).holds()) {
                return true;
            }
        }
        return false;
    }

    // ---- reading it -----------------------------------------------------------------------------

    /** Cell (x, z) of floor {@code layer}; AIR for anything that isn't a cell. */
    public CellState state(int layer, int x, int z) {
        int n = layout.ordinal(layer, x, z);
        return n < 0 ? CellState.AIR : state[layer][n];
    }

    /** The {@code n}th cell of floor {@code layer} (its order in the layout). */
    public CellState stateAt(int layer, int n) {
        return state[layer][n];
    }

    /** Cells of floor {@code layer} that are not yet air. */
    public int left(int layer) {
        return left[layer];
    }

    /** Whether every cell of every floor is air (after sudden death, always). */
    public boolean allGone() {
        for (int l : left) {
            if (l > 0) {
                return false;
            }
        }
        return true;
    }

    /** The floors this round runs on. */
    public FloorLayout layout() {
        return layout;
    }

    /** Sudden-death rings fallen so far. */
    public int ringsDone() {
        return ringsDone;
    }

    /** The play tick at which the last ring falls: after it, and one fade, nothing is left. */
    public long lastRingAt() {
        return suddenDeathAt + (long) Math.max(0, layout.maxRings() - 1) * ringTicks;
    }

    /** Cells turned red so far. */
    public long marks() {
        return marks;
    }

    /** Cells turned to air so far. */
    public long fades() {
        return fades;
    }
}
