package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Sight;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.ArrayList;
import java.util.List;

/**
 * Where kept courses stand (GEN-SPEC-KEEP §4): a grid of plots in the Games world, well apart from
 * every generator region, as pure geometry.
 *
 * <p>Every plot is the same size, 144 x 176 x 336 (Sky Rings' 128 x 176 x 320 half plus 16 blocks along
 * x and z), pinned, so a kept course never moves when a generator grows; a course whose half fits a plot
 * fits any plot ({@link #fits}), and two kept courses side by side are at least 16 blocks apart. A course is built at its plot's corner plus {@value #MARGIN} along x and
 * z. Plots run {@value #COLUMNS} to a row along +x, rows along +z, from {@code keep.area}, with
 * {@code gap} empty blocks between neighbours ({@code keep.plot_gap}): 0 in 0.35 (the plots touch,
 * so every kept course is in plain sight of its neighbours), {@link Sight#GAP} to keep each out of
 * the others' sight (LAYOUT-SPEC §1.2).
 *
 * <p>Why a separate area and not the generator's halves: a kept course is hand-built territory
 * from then on (admins may edit it, the generator never writes into its plot again), so it must
 * never be inside a half the generator converges or guards. {@link #problem} refuses an area that
 * overlaps a half or comes within {@value Regions#CLEARANCE} blocks of one.
 *
 * @param x        the first plot's min corner
 * @param y        ...
 * @param z        ...
 * @param maxPlots how many plots there are
 * @param gap      empty blocks between two neighbouring plots, along x and along z (0 or more)
 */
public record KeepArea(int x, int y, int z, int maxPlots, int gap) {

    /** Plots in a row. */
    public static final int COLUMNS = 6;
    /** The gap between plots in 0.35.0: none, the plots touch. */
    public static final int LEGACY_GAP = 0;
    /**
     * The gap that keeps every kept course out of its neighbours' sight at every view distance
     * ({@link Sight#GAP}).
     */
    public static final int SIGHT_GAP = Sight.GAP;
    /**
     * The gap when config names none ({@code keep.plot_gap}): {@link #SIGHT_GAP}. An install that
     * kept courses in 0.35 has {@code plot_gap: 0} written, so its plots stay where they are.
     */
    public static final int DEFAULT_GAP = SIGHT_GAP;
    /** The largest {@code keep.plot_gap} config takes; it is a multiple of {@link Slots#GAP_GRID}. */
    public static final int MAX_GAP = 4096;
    /** A course stands this far in from its plot's edges along x and z. */
    public static final int MARGIN = 8;
    /**
     * One plot's size: 144 x 176 x 336, pinned (GOLF-V4-SPEC §4.1, MOUNTAIN-V2-SPEC §10.3 item 3). It was
     * the largest half of any generator plus 16 along x and z (Sky Rings' 128 x 176 x 320), and it stays
     * that as literals: every kept plot's corner is worked out from it, so a generator that grows (the
     * Mountain Run v2's 480 x 176 x 640 half) must never move a kept course. A course whose half doesn't
     * fit a plot can't be kept ({@link #fits}); it stays in the archive and on its boards.
     */
    public static final int PLOT_X = 144;
    public static final int PLOT_Y = 176;
    public static final int PLOT_Z = 336;

    public KeepArea {
        if (gap < 0) {
            throw new IllegalArgumentException("a plot gap is 0 or more blocks: " + gap);
        }
    }

    /** An area whose plots stand {@link #DEFAULT_GAP} apart. */
    public KeepArea(int x, int y, int z, int maxPlots) {
        this(x, y, z, maxPlots, DEFAULT_GAP);
    }

    /** Plot {@code n} (1 to {@link #maxPlots}). */
    public Box plot(int n) {
        if (n < 1 || n > maxPlots) {
            throw new IllegalArgumentException("plots are 1-" + maxPlots + ", not " + n);
        }
        int col = (n - 1) % COLUMNS;
        int row = (n - 1) / COLUMNS;
        return Box.sized(x + col * (PLOT_X + gap), y, z + row * (PLOT_Z + gap), PLOT_X, PLOT_Y, PLOT_Z);
    }

    /** Every plot, 1 to {@link #maxPlots}. */
    public List<Box> plots() {
        List<Box> out = new ArrayList<>();
        for (int n = 1; n <= maxPlots; n++) {
            out.add(plot(n));
        }
        return out;
    }

    /** Where a course of {@code def}'s size stands in plot {@code n}: its half's size, {@value #MARGIN} in. */
    public Box build(int n, Slots.Def def) {
        Box p = plot(n);
        return Box.sized(p.minX() + MARGIN, p.minY(), p.minZ() + MARGIN, def.sizeX(), def.sizeY(), def.sizeZ());
    }

    /**
     * Whether a course whose half is {@code sx} x {@code sy} x {@code sz} fits a plot, {@value #MARGIN} in
     * from its edges along x and z: at most 128 x 176 x 320. Golf of the Week's 128 x 16 x 224 does; the
     * Mountain Run v2's 480 x 176 x 640 doesn't (those courses stay in the archive).
     */
    public static boolean fits(int sx, int sy, int sz) {
        return sx > 0 && sy > 0 && sz > 0 && sx <= PLOT_X - 2 * MARGIN && sy <= PLOT_Y && sz <= PLOT_Z - 2 * MARGIN;
    }

    /** {@link #fits(int, int, int)} for a half. */
    public static boolean fits(Box half) {
        return half != null && fits(half.sizeX(), half.sizeY(), half.sizeZ());
    }

    /** Every plot together, the gaps between them included. */
    public Box area() {
        int rows = (maxPlots + COLUMNS - 1) / COLUMNS;
        int cols = Math.min(maxPlots, COLUMNS);
        return Box.sized(x, y, z, cols * PLOT_X + (cols - 1) * gap, PLOT_Y, rows * PLOT_Z + (rows - 1) * gap);
    }

    /**
     * Why the area can't be used, or {@code null}: it must stay inside ±{@value Regions#MAX_XZ}
     * and y {@value Regions#MIN_Y}..{@value Regions#MAX_Y}, and be at least
     * {@value Regions#CLEARANCE} blocks from every generator half in {@code halves}.
     */
    public String problem(List<Box> halves) {
        if (maxPlots < 1) {
            return "it has no plots";
        }
        Box a = area();
        if (Math.abs((long) a.minX()) > Regions.MAX_XZ || Math.abs((long) a.maxX()) > Regions.MAX_XZ
                || Math.abs((long) a.minZ()) > Regions.MAX_XZ || Math.abs((long) a.maxZ()) > Regions.MAX_XZ) {
            return "it reaches past +-" + Regions.MAX_XZ + " (" + a.describe() + ")";
        }
        if (a.minY() < Regions.MIN_Y || a.maxY() > Regions.MAX_Y) {
            return "it needs y " + a.minY() + ".." + a.maxY() + ", outside " + Regions.MIN_Y + ".." + Regions.MAX_Y;
        }
        for (Box h : halves == null ? List.<Box>of() : halves) {
            int gap = a.gap(h);
            if (gap < Regions.CLEARANCE) {
                return "it is " + (gap < 0 ? "on top of" : "only " + gap + " blocks from") + " a Fresh Courses area ("
                        + h.describe() + "; it must be " + Regions.CLEARANCE + " away)";
            }
        }
        return null;
    }

    /** "x 1760..5503, y 128..303, z 7296..10367, plots 576 apart" for admins (the gap when there is one). */
    public String describe() {
        return area().describe() + (gap == 0 ? "" : ", plots " + gap + " apart");
    }
}
