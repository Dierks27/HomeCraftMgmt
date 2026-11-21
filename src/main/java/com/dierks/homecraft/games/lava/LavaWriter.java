package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.engine.HalfWriter;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.util.List;

/**
 * The only thing that changes a block during a round of The Floor Is Lava: a restricted {@link HalfWriter} for the
 * room's box (Falling Floors' {@code FloorWriter}, for lava).
 *
 * <p><b>What it may write, and nothing else:</b>
 * <ul>
 *   <li>lava, or air again, in a cell the plan leaves empty in one of the room's lava layers
 *       ({@link LavaSite#layer});</li>
 *   <li>the glowing block, or the plan's own floor block again, in a bare floor cell
 *       ({@link LavaSite#glowCells}).</li>
 * </ul>
 * The {@link HalfWriter} throws for any position outside the box before anything is written.
 *
 * <p><b>The lava never flows.</b> Every lava block is a source block placed by {@link #tick}, a whole layer at a
 * time from the bottom up, through the world port without physics (so no fluid tick is ever scheduled by the write),
 * and removed the same way from the top down. It fills its layer wall to wall, so there is nowhere for it to go; the
 * area guard refuses any flow, fire or burning in the box all the same.
 *
 * <p><b>Gone for certain.</b> {@link #restore} puts every cell this writer could ever have changed back to the plan
 * at once: every lava layer to air and the floor to its own blocks, whatever it believes is there. It is called when
 * a round ends, when the room empties, and when the game stops (a reload, the plugin disabling). A crash is healed
 * at the next start: the room is converged to its plan, which has no lava in it, before anyone is let in.
 *
 * <p>Main thread only.
 */
public final class LavaWriter {

    /** Real lava, a source block. */
    public static final String LAVA = "minecraft:lava[level=0]";
    /** What the floor turns to in the warning. */
    public static final String GLOW = "minecraft:magma_block";
    /** A layer rises or drains every this many ticks, so the lava is seen to come up and go down. */
    public static final int LAYER_TICKS = 4;

    private final HalfWriter writer;
    private final LavaSite site;
    private final String lava;
    private final String glow;
    /** The plan's floor blocks under the glow cells, in the world's spelling, in {@link LavaSite#glowCells} order. */
    private final String[] floor;
    private final List<int[]> glowCells;
    private int level;
    private int target;
    private int wait;
    private int glowStage;
    private boolean dirty;
    private long written;

    public LavaWriter(WorldPort port, LavaSite site) {
        if (port == null || site == null) {
            throw new IllegalArgumentException("a lava writer needs its world and its room");
        }
        this.writer = new HalfWriter(port, site.box());
        this.site = site;
        this.lava = port.canonical(LAVA);
        this.glow = port.canonical(GLOW);
        this.glowCells = site.glowCells();
        this.floor = new String[glowCells.size()];
        for (int n = 0; n < floor.length; n++) {
            int[] c = glowCells.get(n);
            floor[n] = port.canonical(site.block(c[0], c[1], c[2]));
        }
    }

    /** How deep the lava is now: the layers filled. */
    public int level() {
        return level;
    }

    /** How deep it is going to be. */
    public int target() {
        return target;
    }

    /** Whether anything this writer changed may still be in the room. */
    public boolean dirty() {
        return dirty;
    }

    /** Blocks written since it was made. */
    public long written() {
        return written;
    }

    /** Rise or drain to {@code level} (0: none; never over the room's deepest), a layer at a time. */
    public void target(int level) {
        int to = Math.max(0, Math.min(level, site.flood()));
        if (to != target) {
            target = to;
            wait = 0;
        }
    }

    /** Once a tick: the next layer up or down, when it is due. */
    public void tick() {
        if (level == target) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        wait = LAYER_TICKS - 1;
        if (level < target) {
            fill(level + 1, lava);
            level++;
        } else {
            fill(level, WorldPort.AIR);
            level--;
        }
    }

    /** Finish the rise or the drain now. */
    public void settle() {
        while (level < target) {
            fill(level + 1, lava);
            level++;
        }
        while (level > target) {
            fill(level, WorldPort.AIR);
            level--;
        }
        wait = 0;
    }

    private void fill(int layer, String state) {
        for (int[] c : site.layer(layer)) {
            writer.set(c[0], c[1], c[2], state);
            written++;
        }
        dirty = true;
    }

    /**
     * The warning's glow: 0 none, 1 every other floor block, 2 the whole floor. Only what changes is written.
     */
    public void glow(int stage) {
        int to = Math.max(0, Math.min(2, stage));
        if (to == glowStage) {
            return;
        }
        for (int n = 0; n < floor.length; n++) {
            int[] c = glowCells.get(n);
            boolean was = glows(c, glowStage);
            boolean now = glows(c, to);
            if (was != now) {
                writer.set(c[0], c[1], c[2], now ? glow : floor[n]);
                written++;
            }
        }
        glowStage = to;
        dirty |= to > 0;
    }

    private static boolean glows(int[] cell, int stage) {
        return stage == 2 || (stage == 1 && ((cell[0] + cell[2]) & 1) == 0);
    }

    /**
     * Everything back to the plan, now: every lava layer of the room emptied and every floor block its own again,
     * written whatever this writer believes is there. Nothing is written when it has changed nothing since the last
     * restore.
     *
     * @return the blocks written
     */
    public int restore() {
        target = 0;
        wait = 0;
        if (!dirty) {
            level = 0;
            glowStage = 0;
            return 0;
        }
        int n = 0;
        for (int layer = site.flood(); layer >= 1; layer--) {
            for (int[] c : site.layer(layer)) {
                writer.set(c[0], c[1], c[2], WorldPort.AIR);
                n++;
            }
        }
        for (int i = 0; i < floor.length; i++) {
            int[] c = glowCells.get(i);
            writer.set(c[0], c[1], c[2], floor[i]);
            n++;
        }
        written += n;
        level = 0;
        glowStage = 0;
        dirty = false;
        return n;
    }
}
