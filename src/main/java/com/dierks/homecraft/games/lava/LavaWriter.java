package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.engine.HalfWriter;
import com.dierks.homecraft.games.gen.engine.WorldPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only thing that changes a block during a round of The Floor Is Lava: a restricted {@link HalfWriter} for the
 * room's box (Falling Floors' {@code FloorWriter}, for lava).
 *
 * <p><b>What it may write, and nothing else:</b>
 * <ul>
 *   <li>lava, or air again, in a cell the room of the moment leaves empty in one of its lava layers
 *       ({@link LavaSite#layer});</li>
 *   <li>the glowing block, or the plan's own floor block again, in a bare floor cell
 *       ({@link LavaSite#glowCells});</li>
 *   <li>0.57.0: the furniture, moved from one layout to the next ({@link #moveTo}): only cells over the floor where
 *       the two layouts differ, each to the next layout's block (or to air, or to lava where the lava is up).</li>
 * </ul>
 * The {@link HalfWriter} throws for any position outside the box before anything is written.
 *
 * <p><b>The lava never flows.</b> Every lava block is a source block placed by {@link #tick}, a whole layer at a
 * time from the bottom up, through the world port without physics (so no fluid tick is ever scheduled by the write),
 * and removed the same way from the top down. It fills its layer wall to wall (0.57.0: or one side of the room first,
 * {@link #targetSide}, the rest following once {@link #release}d; a piece that moves away while the lava is up leaves
 * lava where it stood), so there is nowhere for it to go; the area guard refuses any flow, fire or burning in the box
 * all the same.
 *
 * <p><b>Gone for certain.</b> {@link #restore} puts every cell this writer could have changed back to the room as
 * built, at once: every lava layer to air, every cell the furniture was moved through to the built room's block, and
 * the floor to its own blocks, whatever it believes is there. It is called when a round ends, when the room empties,
 * and when the game stops (a reload, the plugin disabling). A crash is healed at the next start: the room is
 * converged to its plan, which has no lava in it and the furniture where it was built, before anyone is let in.
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

    /** 0.57.0: a side of the room, for lava that comes up there first. */
    public enum Side {
        NORTH, SOUTH, EAST, WEST;

        /** Whether a column is on this side of the room's middle. */
        boolean has(LavaSite site, int x, int z) {
            double mx = (site.box().minX() + site.box().maxX() + 1) / 2.0;
            double mz = (site.box().minZ() + site.box().maxZ() + 1) / 2.0;
            return switch (this) {
                case NORTH -> z + 0.5 < mz;
                case SOUTH -> z + 0.5 >= mz;
                case WEST -> x + 0.5 < mx;
                case EAST -> x + 0.5 >= mx;
            };
        }
    }

    private final HalfWriter writer;
    private final WorldPort port;
    private final LavaSite home;
    private LavaSite site;
    private final String lava;
    private final String glow;
    private final Map<String, String> canonical = new HashMap<>();
    /** The glowing floor cells of the room of the moment, and every floor cell that ever glowed since a restore. */
    private List<int[]> glowCells;
    private final Map<Long, int[]> glowed = new LinkedHashMap<>();
    private final Set<Long> lit = new java.util.HashSet<>();
    private Side glowSide;
    /** Every cell the furniture was moved through since a restore. */
    private final Set<Long> moved = new LinkedHashSet<>();
    private final Map<Long, int[]> movedAt = new HashMap<>();
    // the lava: layers filled on the side (a) and elsewhere (b), and where each is going
    private int a;
    private int b;
    private int ta;
    private int tb;
    private Side side;
    private int wait;
    private int glowStage;
    private boolean dirty;
    private long written;

    /** A writer for the room as built. */
    public LavaWriter(WorldPort port, LavaSite site) {
        this(port, site, site);
    }

    /**
     * @param home the room as built (what {@link #restore} puts back)
     * @param now  the room as it stands now (the same, unless a round already moved the furniture)
     */
    public LavaWriter(WorldPort port, LavaSite home, LavaSite now) {
        if (port == null || home == null || now == null) {
            throw new IllegalArgumentException("a lava writer needs its world and its room");
        }
        this.writer = new HalfWriter(port, home.box());
        this.port = port;
        this.home = home;
        this.site = now;
        this.lava = port.canonical(LAVA);
        this.glow = port.canonical(GLOW);
        this.glowCells = now.glowCells();
    }

    private String canon(String state) {
        return state == null ? WorldPort.AIR : canonical.computeIfAbsent(state, port::canonical);
    }

    /** The room of the moment. */
    public LavaSite site() {
        return site;
    }

    /** How deep the lava is now: the layers filled (where it is deepest). */
    public int level() {
        return Math.max(a, b);
    }

    /** How deep it is going to be. */
    public int target() {
        return Math.max(ta, tb);
    }

    /** Whether anything this writer changed may still be in the room. */
    public boolean dirty() {
        return dirty;
    }

    /** Blocks written since it was made. */
    public long written() {
        return written;
    }

    /** Rise or drain to {@code level} (0: none; never over the room's deepest), a layer at a time, everywhere. */
    public void target(int level) {
        int to = clamp(level);
        if (to != ta || to != tb) {
            ta = to;
            tb = to;
            wait = 0;
        }
    }

    /**
     * 0.57.0: rise to {@code level} on {@code side} of the room only (a surprise); the rest follows when
     * {@link #release}d.
     */
    public void targetSide(int level, Side side) {
        if (side == null) {
            target(level);
            return;
        }
        if (this.side != side) {
            if (a != b) {
                settle(); // never two sides at once
            }
            this.side = side;
        }
        ta = clamp(level);
        wait = 0;
    }

    /** 0.57.0: the rest of the room's lava comes up to the side's. */
    public void release() {
        tb = ta;
    }

    private int clamp(int level) {
        return Math.max(0, Math.min(level, site.flood()));
    }

    /** Once a tick: the next layer up or down, when it is due. */
    public void tick() {
        if (a == ta && b == tb) {
            if (a == b) {
                side = null;
            }
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        wait = LAYER_TICKS - 1;
        step();
    }

    /** One layer, on the side first. */
    private void step() {
        if (side == null) {
            if (a < ta) {
                fill(a + 1, lava, 0);
                a++;
            } else if (a > ta) {
                fill(a, WorldPort.AIR, 0);
                a--;
            }
            b = a;
            tb = ta;
            return;
        }
        if (a != ta) {
            if (a < ta) {
                fill(a + 1, lava, 1);
                a++;
            } else {
                fill(a, WorldPort.AIR, 1);
                a--;
            }
        } else if (b != tb) {
            if (b < tb) {
                fill(b + 1, lava, -1);
                b++;
            } else {
                fill(b, WorldPort.AIR, -1);
                b--;
            }
        }
    }

    /** Finish the rise or the drain now. */
    public void settle() {
        int guard = 64;
        while ((a != ta || b != tb) && guard-- > 0) {
            step();
        }
        if (a == b) {
            side = null;
        }
        wait = 0;
    }

    /** Layer {@code layer}'s cells: {@code where} 0 all, 1 the side, -1 the rest. */
    private void fill(int layer, String state, int where) {
        for (int[] c : site.layer(layer)) {
            boolean on = side == null || side.has(site, c[0], c[2]);
            if (where == 0 || (where == 1) == on) {
                writer.set(c[0], c[1], c[2], state);
                written++;
            }
        }
        dirty = true;
    }

    /** 0.57.0: whether the cell is lava now (the room's air, under the lava's top on its side). */
    public boolean lavaAt(int x, int y, int z) {
        int layer = y - site.box().minY();
        if (layer < 1 || !site.air(x, y, z) || !site.inside().contains(x, y, z)) {
            return false;
        }
        int top = side == null || side.has(site, x, z) ? a : b;
        return layer <= top && layer <= site.flood();
    }

    /**
     * 0.57.0: the furniture moves to {@code next}'s layout, now: every cell over the floor where the two differ is
     * written, to {@code next}'s block, or where it is empty to lava if the lava is up to it there, else air. The
     * floor under a piece that went keeps its glow if the floor glows.
     *
     * @return the blocks written
     */
    public int moveTo(LavaSite next) {
        if (next == null || next == site) {
            return 0;
        }
        LavaSite was = site;
        int n = 0;
        int top = Math.min(LavaPlanner.ROOF - 1, Math.max(LavaPlanner.FLOOD, was.flood()) + 1);
        List<int[]> writes = new ArrayList<>();
        for (int x = was.inside().minX(); x <= was.inside().maxX(); x++) {
            for (int z = was.inside().minZ(); z <= was.inside().maxZ(); z++) {
                for (int y = was.box().minY() + 1; y <= was.box().minY() + top; y++) {
                    String from = was.block(x, y, z);
                    String to = next.block(x, y, z);
                    if (from == null ? to != null : !from.equals(to)) {
                        writes.add(new int[]{x, y, z});
                    }
                }
            }
        }
        site = next; // what is lava now is read from the next room's air
        for (int[] c : writes) {
            String to = next.block(c[0], c[1], c[2]);
            String state = to != null ? canon(to) : lavaAt(c[0], c[1], c[2]) ? lava : WorldPort.AIR;
            writer.set(c[0], c[1], c[2], state);
            long k = LavaSite.key(c[0], c[1], c[2]);
            if (moved.add(k)) {
                movedAt.put(k, c);
            }
            n++;
        }
        glowCells = next.glowCells();
        written += n;
        dirty |= n > 0;
        glow(glowStage); // a floor a piece left glows too, while the floor glows
        return n;
    }

    /**
     * The warning's glow: 0 none, 1 every other floor block, 2 the whole floor (0.57.0: only on one side while
     * {@link #glowOnly} says so). Only what changes is written.
     */
    public void glow(int stage) {
        int to = Math.max(0, Math.min(2, stage));
        for (int[] c : glowCells) {
            long k = LavaSite.key(c[0], c[1], c[2]);
            boolean was = lit.contains(k);
            boolean now = glows(c, to);
            if (was != now) {
                writer.set(c[0], c[1], c[2], now ? glow : floorAt(c));
                written++;
                if (now) {
                    lit.add(k);
                    glowed.put(k, c);
                } else {
                    lit.remove(k);
                }
            }
        }
        glowStage = to;
        dirty |= to > 0;
    }

    /** 0.57.0: from now on the floor glows only on {@code only}'s side ({@code null}: everywhere). */
    public void glowOnly(Side only) {
        glowSide = only;
    }

    private boolean glows(int[] cell, int stage) {
        if (stage == 0 || (glowSide != null && !glowSide.has(site, cell[0], cell[2]))) {
            return false;
        }
        return stage == 2 || (stage == 1 && ((cell[0] + cell[2]) & 1) == 0);
    }

    private String floorAt(int[] c) {
        return canon(home.block(c[0], c[1], c[2]));
    }

    /**
     * Everything back to the room as built, now: every lava layer emptied, every cell the furniture moved through
     * set to the built room's block, and every floor block that glowed its own again, written whatever this writer
     * believes is there. Nothing is written when it has changed nothing since the last restore.
     *
     * @return the blocks written
     */
    public int restore() {
        ta = 0;
        tb = 0;
        wait = 0;
        if (!dirty) {
            a = 0;
            b = 0;
            side = null;
            glowSide = null;
            glowStage = 0;
            lit.clear();
            site = home;
            glowCells = home.glowCells();
            return 0;
        }
        int n = 0;
        for (int layer = site.flood(); layer >= 1; layer--) { // every layer, whatever it believes
            for (int[] c : site.layer(layer)) {
                writer.set(c[0], c[1], c[2], WorldPort.AIR);
                n++;
            }
        }
        for (long k : moved) {
            int[] c = movedAt.get(k);
            writer.set(c[0], c[1], c[2], canon(home.block(c[0], c[1], c[2])));
            n++;
        }
        Map<Long, int[]> floors = new LinkedHashMap<>(glowed);
        for (int[] c : glowCells) {
            floors.put(LavaSite.key(c[0], c[1], c[2]), c);
        }
        for (int[] c : floors.values()) {
            writer.set(c[0], c[1], c[2], floorAt(c));
            n++;
        }
        written += n;
        a = 0;
        b = 0;
        side = null;
        glowSide = null;
        glowStage = 0;
        dirty = false;
        moved.clear();
        movedAt.clear();
        glowed.clear();
        lit.clear();
        site = home;
        glowCells = home.glowCells();
        return n;
    }
}
