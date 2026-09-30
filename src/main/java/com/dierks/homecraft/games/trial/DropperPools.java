package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.dropper.DropMarks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Where every Dropper course's water stands, as blocks, for Time Trials' own flow guard: a fluid whose
 * source is in one of these never moves (EVENTS-DROPPER-SPEC §B.1.9, "a Dropper's pools never leak").
 * Pure, and read once per course list, so the guard's check is a world name and a few boxes.
 *
 * <p><b>Why Time Trials guards it too.</b> The Fresh Courses guard covers the slots' halves and kept
 * plots, but only while Fresh Courses is open. A Dropper course stands (a Fresh one with its engine
 * off, a Classic one, a kept one) as long as its row does, so its pools are guarded as long as Time
 * Trials is open, whatever else is.
 *
 * <p>A pool's water is the blocks of its pool box ({@link DropMarks#poolBox}): its column from the
 * pool's floor to the surface. The validator allows water nowhere else, so these are every water
 * block a Dropper has.
 */
final class DropperPools {

    /** No Dropper at all. */
    static final DropperPools NONE = new DropperPools(List.of(), List.of(), new int[0][]);

    /** Every world that holds a pool (a flow in any other is a quick no). */
    private final List<String> worlds;
    /** Each box's world... */
    private final List<String> boxWorlds;
    /** ...and its blocks, {x1, y1, z1, x2, y2, z2}, inclusive. */
    private final int[][] boxes;

    private DropperPools(List<String> worlds, List<String> boxWorlds, int[][] boxes) {
        this.worlds = worlds;
        this.boxWorlds = boxWorlds;
        this.boxes = boxes;
    }

    /** The pools of every Dropper course in {@code courses} (any other kind, or a malformed one: none). */
    static DropperPools of(Collection<Course> courses) {
        List<String> worlds = new ArrayList<>();
        List<String> boxWorlds = new ArrayList<>();
        List<int[]> boxes = new ArrayList<>();
        for (Course c : courses == null ? List.<Course>of() : courses) {
            if (!DropperLayout.is(c) || c.world() == null || !DropperLayout.problems(c).isEmpty()) {
                continue;
            }
            for (int level = 0; level < DropperLayout.levels(c); level++) {
                Course.Mark pool = DropperLayout.poolOf(c, level);
                if (pool == null) {
                    continue;
                }
                boxes.add(blocks(pool));
                boxWorlds.add(c.world());
                if (worlds.stream().noneMatch(c.world()::equalsIgnoreCase)) {
                    worlds.add(c.world());
                }
            }
        }
        return boxes.isEmpty() ? NONE
                : new DropperPools(List.copyOf(worlds), List.copyOf(boxWorlds), boxes.toArray(new int[0][]));
    }

    /** A pool's water as blocks, {x1, y1, z1, x2, y2, z2} inclusive: its box from the floor to the surface. */
    static int[] blocks(Course.Mark pool) {
        double[] b = DropMarks.poolBox(pool);
        int surface = (int) Math.round(DropMarks.surface(pool));
        return new int[]{(int) Math.floor(b[0]), (int) Math.floor(b[1]), (int) Math.floor(b[2]),
                (int) Math.ceil(b[3]) - 1, surface - 1, (int) Math.ceil(b[5]) - 1};
    }

    /** How many pools there are. */
    int size() {
        return boxes.length;
    }

    /** Whether any pool is in {@code world} (a flow anywhere else is a quick no: not a block looked at). */
    boolean covers(String world) {
        return world != null && listed(world);
    }

    /** Whether block (x, y, z) of {@code world} is a Dropper's water. */
    boolean holds(String world, int x, int y, int z) {
        if (!covers(world)) {
            return false;
        }
        for (int i = 0; i < boxes.length; i++) {
            int[] b = boxes[i];
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]
                    && boxWorlds.get(i).equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    private boolean listed(String world) {
        for (String w : worlds) {
            if (w.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }
}
