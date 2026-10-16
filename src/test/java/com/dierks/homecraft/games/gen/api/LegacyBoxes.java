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
 * <p>Each half is its 0.35 size at its 0.35 origin, half B {@link #HALF_GAP} blocks along +X from half A,
 * as in 0.35. The sizes are written out too ({@link #size}): v4 made Golf of the Week and Classic Golf
 * 128 x 16 x 224 and the Ice Boat 480 x 176 x 640, and a golden pinned at these boxes must not move when a
 * slot grows (GOLF-V4-SPEC §5.2 step 1: "LegacyBoxes pins golf's old size explicitly, so no v3 golden
 * moves").
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

    /** Each slot's and Classics slot's half size in 0.35.0 and 0.36.0, {x, y, z}. */
    private static final Map<String, int[]> SIZES = Map.ofEntries(
            Map.entry("fresh_parkour_easy", new int[]{64, 48, 64}),
            Map.entry("fresh_parkour", new int[]{64, 48, 64}),
            Map.entry("fresh_parkour_hard", new int[]{64, 48, 64}),
            Map.entry("fresh_rings", new int[]{128, 176, 320}),
            Map.entry("fresh_golf", new int[]{64, 16, 128}),
            Map.entry("fresh_tiny_golf", new int[]{64, 16, 48}),
            Map.entry("fresh_boat", new int[]{128, 16, 128}),
            Map.entry("fresh_dropper_easy", new int[]{64, 64, 16}),
            Map.entry("fresh_dropper", new int[]{64, 64, 16}),
            Map.entry("fresh_classic_parkour", new int[]{64, 48, 64}),
            Map.entry("fresh_classic_rings", new int[]{128, 176, 320}),
            Map.entry("fresh_classic_golf", new int[]{64, 16, 128}),
            Map.entry("fresh_classic_dropper", new int[]{64, 64, 16}));

    private LegacyBoxes() {
    }

    /** {@code def}'s half size in 0.35.0 and 0.36.0, {x, y, z} (a copy). */
    public static int[] size(Slots.Def def) {
        int[] s = SIZES.get(def.id());
        if (s == null) {
            throw new IllegalArgumentException("no 0.35 size for " + def.id());
        }
        return s.clone();
    }

    /** {@code def}'s 0.35.0 origin {x, y, z} (a copy). */
    public static int[] origin(Slots.Def def) {
        int[] o = ORIGINS.get(def.id());
        if (o == null) {
            throw new IllegalArgumentException("no 0.35 spot for " + def.id());
        }
        return o.clone();
    }

    /** {@code def}'s half {@code which} where 0.35.0 stood it, at its 0.35 size. */
    public static Box half(Slots.Def def, char which) {
        int[] o = origin(def);
        int[] s = size(def);
        int dx = switch (Character.toUpperCase(which)) {
            case 'A' -> 0;
            case 'B' -> s[0] + HALF_GAP;
            default -> throw new IllegalArgumentException("a half is A or B: " + which);
        };
        return Box.sized(o[0] + dx, o[1], o[2], s[0], s[1], s[2]);
    }

    /** {@code def}'s two halves and the gap between them where 0.35.0 stood them, at its 0.35 size. */
    public static Box region(Slots.Def def) {
        int[] o = origin(def);
        int[] s = size(def);
        return Box.sized(o[0], o[1], o[2], 2 * s[0] + HALF_GAP, s[1], s[2]);
    }

    /** The 0.36.0 origins of the areas v4 moved (the out-of-sight layout's, before Golf v4 and the Mountain Run v2). */
    private static final Map<String, int[]> ORIGINS_036 = Map.of(
            "fresh_golf", new int[]{7488, 160, 4096},
            "fresh_classic_golf", new int[]{7488, 160, 4800},
            "fresh_boat", new int[]{6080, 160, 5888});

    /** The gap between a slot's halves in 0.36.0. */
    public static final int HALF_GAP_036 = 576;

    /**
     * {@code def}'s half {@code which} where 0.36.0 shipped it, at its 0.36 size: for the tests of an older
     * algo's layouts (a v3 Mountain Run, an Adventure Golf course) that stood at 0.36's shipped spot, so their
     * coordinates, hashes and goldens stay exactly what they were when v4 moved and grew those areas. Only
     * golf's two areas and the boat moved; any other slot's 0.36 spot is today's, at its own size.
     */
    public static Box v036(Slots.Def def, char which) {
        int[] o = ORIGINS_036.get(def.id());
        if (o == null) {
            return def.half(which);
        }
        int[] s = size(def);
        int dx = switch (Character.toUpperCase(which)) {
            case 'A' -> 0;
            case 'B' -> s[0] + HALF_GAP_036;
            default -> throw new IllegalArgumentException("a half is A or B: " + which);
        };
        return Box.sized(o[0] + dx, o[1], o[2], s[0], s[1], s[2]);
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
