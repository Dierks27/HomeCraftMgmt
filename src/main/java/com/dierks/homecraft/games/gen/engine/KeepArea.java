package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Slots;

import java.util.ArrayList;
import java.util.List;

/**
 * Where kept courses stand (GEN-SPEC-KEEP §4): a grid of plots in the Games world, well apart from
 * every generator region, as pure geometry.
 *
 * <p>Every plot is the same size: the largest half of any generator (Sky Rings', 128 x 176 x 320)
 * plus 16 blocks along x and z, so any course fits any plot and two kept courses side by side are
 * at least 16 blocks apart. A course is built at its plot's corner plus {@value #MARGIN} along x and
 * z. Plots run {@value #COLUMNS} to a row along +x, rows along +z, from {@code keep.area}.
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
 */
public record KeepArea(int x, int y, int z, int maxPlots) {

    /** Plots in a row. */
    public static final int COLUMNS = 6;
    /** A course stands this far in from its plot's edges along x and z. */
    public static final int MARGIN = 8;
    /** One plot's size. */
    public static final int PLOT_X;
    public static final int PLOT_Y;
    public static final int PLOT_Z;

    static {
        int sx = 0;
        int sy = 0;
        int sz = 0;
        List<Slots.Def> all = new ArrayList<>(Slots.ALL);
        all.addAll(Slots.CLASSICS);
        for (Slots.Def d : all) {
            sx = Math.max(sx, d.sizeX());
            sy = Math.max(sy, d.sizeY());
            sz = Math.max(sz, d.sizeZ());
        }
        PLOT_X = sx + 2 * MARGIN;
        PLOT_Y = sy;
        PLOT_Z = sz + 2 * MARGIN;
    }

    /** Plot {@code n} (1 to {@link #maxPlots}). */
    public Box plot(int n) {
        if (n < 1 || n > maxPlots) {
            throw new IllegalArgumentException("plots are 1-" + maxPlots + ", not " + n);
        }
        int col = (n - 1) % COLUMNS;
        int row = (n - 1) / COLUMNS;
        return Box.sized(x + col * PLOT_X, y, z + row * PLOT_Z, PLOT_X, PLOT_Y, PLOT_Z);
    }

    /** Where a course of {@code def}'s size stands in plot {@code n}: its half's size, {@value #MARGIN} in. */
    public Box build(int n, Slots.Def def) {
        Box p = plot(n);
        return Box.sized(p.minX() + MARGIN, p.minY(), p.minZ() + MARGIN, def.sizeX(), def.sizeY(), def.sizeZ());
    }

    /** Every plot together. */
    public Box area() {
        int rows = (maxPlots + COLUMNS - 1) / COLUMNS;
        int cols = Math.min(maxPlots, COLUMNS);
        return Box.sized(x, y, z, cols * PLOT_X, PLOT_Y, rows * PLOT_Z);
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

    /** "x 4096..4959, y 128..303, z 5376..6719" for admins. */
    public String describe() {
        return area().describe();
    }
}
