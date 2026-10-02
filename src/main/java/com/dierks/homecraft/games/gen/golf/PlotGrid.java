package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.golf.GolfCourse;

/**
 * Where a golf course's holes stand in its half (GOLF-V4-SPEC §4.1): THE one geometry function the
 * planner, the validators, the scenery and the live proof share.
 *
 * <p>A half is a grid of plots, {@link #plotX} x {@link #plotZ}, {@link #gapX} blocks apart in X and
 * {@link #gapZ} in Z, {@link #columns} to a row. Hole i takes row i / columns, and runs along the
 * row in snake order (the engine teleports players from tee to tee anyway).
 * <ul>
 *   <li>{@link #V3}: every layout of golf planner version 3 or older, and Tiny Golf at any version:
 *       20 x 40 plots, gaps 2 and 4 (Golf of the Week's old 64 x 128 half held 3 x 3, Tiny Golf's
 *       64 x 48 one row of 3). Byte for byte what {@code GolfPlanner.plot} always gave.</li>
 *   <li>{@link #V4}: Golf v4 (version 4 or later) everywhere but Tiny Golf: 40 x 64 plots, gaps 4
 *       and 16, so a 128 x 224 half holds 3 x 3 (3 x 40 + 2 x 4 = 128; 3 x 64 + 2 x 16 = 224).</li>
 * </ul>
 *
 * <p><b>Chosen from the plan's (or the tag's) slot and version, never from the half's size.</b> An
 * old plan recalled into a bigger Classic, or kept in a plot, keeps its old plots: its holes, its
 * scenery and its ponds stand where the old geometry put them, and the checks must look there.
 * Pure.
 *
 * @param plotX   a plot's width (x)
 * @param plotZ   a plot's depth (z)
 * @param gapX    blocks between plots in a row
 * @param gapZ    blocks between rows
 * @param columns plots in a row
 */
public record PlotGrid(int plotX, int plotZ, int gapX, int gapZ, int columns) {

    /** The last golf planner version whose plots are 20 x 40 (and Tiny Golf's at every version). */
    public static final int LAST_V3_ALGO = 3;
    /** Adventure Golf's plots (and older): 20 x 40, gaps 2 and 4, three to a row. */
    public static final PlotGrid V3 = new PlotGrid(20, 40, 2, 4, 3);
    /** Golf v4's plots: 40 x 64, gaps 4 and 16, three to a row. */
    public static final PlotGrid V4 = new PlotGrid(40, 64, 4, 16, 3);

    public PlotGrid {
        if (plotX < 1 || plotZ < 1 || gapX < 0 || gapZ < 0 || columns < 1) {
            throw new IllegalArgumentException("a plot grid of " + plotX + " x " + plotZ + ", gaps " + gapX + "/"
                    + gapZ + ", " + columns + " columns");
        }
    }

    /**
     * The grid a layout of golf planner version {@code algo} made for slot {@code slotId} stands on:
     * {@link #V3} for version {@value #LAST_V3_ALGO} or older and for Tiny Golf, {@link #V4} otherwise.
     */
    public static PlotGrid of(String slotId, int algo) {
        if (algo <= LAST_V3_ALGO || Slots.TINY_GOLF.id().equals(slotId)) {
            return V3;
        }
        return V4;
    }

    /** {@link #of(String, int)} for a slot's def. */
    public static PlotGrid of(Slots.Def slot, int algo) {
        return of(slot == null ? null : slot.id(), algo);
    }

    /**
     * The grid a generated course's holes stand on, from its own tag (the slot and version it was
     * made for; a recalled course's tag names its ORIGINAL slot); {@link #V3} for a course with no
     * tag (hand-built, or kept), which then has no plots to speak of.
     */
    public static PlotGrid of(GolfCourse course) {
        GenTag tag = course == null ? null : course.gen();
        return tag == null ? V3 : of(tag.slot(), tag.algo());
    }

    /** Where hole {@code i}'s plot starts in {@code half}: {x, z} (its min corner). */
    public int[] plot(Box half, int i) {
        int row = i / columns;
        int col = row % 2 == 0 ? i % columns : columns - 1 - i % columns;
        return new int[]{half.minX() + col * (plotX + gapX), half.minZ() + row * (plotZ + gapZ)};
    }

    /** Hole {@code i}'s plot as a column box (x and z) at the y range {@code minY}..{@code maxY}. */
    public Box plotBox(Box half, int i, int minY, int maxY) {
        int[] p = plot(half, i);
        return new Box(p[0], minY, p[1], p[0] + plotX - 1, maxY, p[1] + plotZ - 1);
    }

    /** Whether hole {@code i}'s plot lies inside {@code half}. */
    public boolean fits(Box half, int i) {
        int[] p = plot(half, i);
        return p[0] + plotX - 1 <= half.maxX() && p[1] + plotZ - 1 <= half.maxZ();
    }

    /** How big a half {@code holes} plots need, {x, z}: the grid's rows and columns, gaps between them. */
    public int[] needs(int holes) {
        int cols = Math.min(columns, Math.max(1, holes));
        int rows = (Math.max(1, holes) + columns - 1) / columns;
        return new int[]{cols * plotX + (cols - 1) * gapX, rows * plotZ + (rows - 1) * gapZ};
    }
}
