package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.gen.engine.KeepArea;

import java.util.Map;

/**
 * The 0.35.0 spots, frozen for the tests (LAYOUT-SPEC §8, package L0).
 *
 * <p>A plan's hash, its codec bytes and every block of it carry absolute coordinates, so a golden
 * pinned "at the shipped origin" moves whenever the shipped layout does. Every planner and
 * generator golden, and every test that names fixed block coordinates, stands at these boxes
 * instead, which never change: moving the shipped spots (LAYOUT-SPEC §1.5) changes no golden, and a
 * golden that changes means a planner changed. The numbers are written out here, not read from
 * {@link Slots}, on purpose.
 *
 * <p>Each half is the def's own size (fixed per generator) at its 0.35 origin, half B
 * {@link #HALF_GAP} blocks along +X from half A, as in 0.35.
 */
public final class LegacyBoxes {

    /** The 0.35 gap between a slot's halves. */
    public static final int HALF_GAP = 32;

    /** Each slot's and Classics slot's 0.35.0 origin (half A's min corner). */
    private static final Map<String, int[]> ORIGINS = Map.ofEntries(
            Map.entry("fresh_parkour_easy", new int[]{4096, 160, 4096}),
            Map.entry("fresh_parkour", new int[]{4352, 160, 4096}),
            Map.entry("fresh_parkour_hard", new int[]{4608, 160, 4096}),
            Map.entry("fresh_rings", new int[]{4096, 128, 4352}),
            Map.entry("fresh_golf", new int[]{4864, 160, 4096}),
            Map.entry("fresh_tiny_golf", new int[]{5120, 160, 4096}),
            Map.entry("fresh_boat", new int[]{4480, 160, 4352}),
            Map.entry("fresh_dropper_easy", new int[]{5376, 160, 4096}),
            Map.entry("fresh_dropper", new int[]{5376, 160, 4160}),
            Map.entry("fresh_classic_parkour", new int[]{4096, 160, 4736}),
            Map.entry("fresh_classic_rings", new int[]{4608, 128, 4736}),
            Map.entry("fresh_classic_golf", new int[]{4352, 160, 4736}),
            Map.entry("fresh_classic_dropper", new int[]{5376, 160, 4224}));

    private LegacyBoxes() {
    }

    /** {@code def}'s 0.35.0 origin {x, y, z} (a copy). */
    public static int[] origin(Slots.Def def) {
        int[] o = ORIGINS.get(def.id());
        if (o == null) {
            throw new IllegalArgumentException("no 0.35 spot for " + def.id());
        }
        return o.clone();
    }

    /** {@code def}'s half {@code which} where 0.35.0 stood it. */
    public static Box half(Slots.Def def, char which) {
        int[] o = origin(def);
        return def.half(o[0], o[1], o[2], which, HALF_GAP);
    }

    /** {@code def}'s two halves and the gap between them where 0.35.0 stood them. */
    public static Box region(Slots.Def def) {
        int[] o = origin(def);
        return def.region(o[0], o[1], o[2], HALF_GAP);
    }

    /** 0.35.0's keep area: 24 plots from x 4096, z 5376, touching (no gap between plots). */
    public static KeepArea keep() {
        return new KeepArea(4096, 128, 5376, 24, 0);
    }

    /** 0.35.0's Clubhouse box: x 5376-5407, y 160-175, z 4448-4479. */
    public static Box clubhouse() {
        return Box.sized(5376, 160, 4448, 32, 16, 32);
    }

    /** 0.35.0's Falling Floors box: x 5376-5423, y 176-215, z 4352-4399. */
    public static Box fallingFloors() {
        return Box.sized(5376, 176, 4352, 48, 40, 48);
    }
}
