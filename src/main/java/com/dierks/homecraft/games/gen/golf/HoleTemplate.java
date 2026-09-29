package com.dierks.homecraft.games.gen.golf;

import com.dierks.homecraft.games.gen.api.GenRandom;
import com.dierks.homecraft.games.gen.api.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * The shapes a generated golf hole can take, and the rasteriser that draws one as blocks
 * (GEN-SPEC §4.3).
 *
 * <p><b>Why templates, not free-form lanes.</b> Every hole must be solvable, readable by a small
 * child, and fit a 20 x 40 plot. A handful of shapes a child recognises (a straight, a bend, a
 * ramp up to a raised green, a run of ice) with seeded lengths, widths, feature offsets and a
 * mirror give plenty of variety, while the physics — not the template — decides par: every hole is
 * solved on its blocks by {@link ExpertSearch} and checked against the sloppy player of
 * {@link KidPolicy}. A template that comes out too hard or too easy for its tier is simply drawn
 * again from a new seed.
 *
 * <p><b>The drawing rules</b>, the same for every template (a lane runs along +Z from its tee, and
 * is mirrored in X or not):
 * <ul>
 *   <li>The turf's top is T; floor blocks sit at T - 1 on nothing. Turf is lime and green concrete
 *       in 2 x 2 checks, so distances are easy to read.</li>
 *   <li>A ramp is a bottom slab (T + 0.5) and a raised green is a full block (T + 1); a slab only
 *       ever sits in the middle of a lane, never next to a wall, so every wall stands exactly one
 *       block above the lane beside it.</li>
 *   <li>Walls go round every lane cell (diagonals too), from T - 1 up to one block above the
 *       highest lane cell next to them. One block is too high for a ball to climb and low enough
 *       for a player to step over.</li>
 *   <li>Bumpers are slime blocks in walls or as single-block rocks, never in the floor.</li>
 *   <li>The cup is sunken: the lane block at the cup is left out and black concrete goes one
 *       lower, with a 3 x 3 white ring round it, a white tee, and red wool floating three above.</li>
 *   <li>The tee sign stands on the wall right behind the tee.</li>
 * </ul>
 */
public enum HoleTemplate {

    /** Width 5, the cup 10-18 ahead, centred or one off. */
    STRAIGHT("E"),
    /** A straight (10-16) with one or two slime rocks and slime side walls. */
    BUMPERS("E"),
    /** A row of slabs, then a full step up to a raised green with the cup (longer for Medium). */
    RAMP("EM"),
    /** 10-14 up, a right angle, 6-10 across; slime at the elbow for a bank shot. */
    DOGLEG("M"),
    /** Turf, 4-8 of packed ice, a 2-3 soul-soil brake, then the cup one off the middle. */
    ICE_RUN("M"),
    /** A 5 x 5 raised green at the end, walled except for its ramp entrance (2 wide on Hard). */
    ISLAND("MH"),
    /** 7-9 up, 7-9 across, 5-7 up again: two opposite right angles with slime corners. */
    S_BEND("H"),
    /**
     * Width 3, 10-15 of ice, then a right angle into a 3-wide pocket with the cup and a rock near
     * it. (A straight 3-wide ice lane always had a one-putt bank line, too easy for Hard.)
     */
    NARROW_ICE("H"),
    /** The fallback: width 5, 10 long, flat. Always passes the validator (tested). */
    SAFE_STRAIGHT("");

    /** A plot's size in blocks (x, z). */
    public static final int PLOT_X = 20;
    public static final int PLOT_Z = 40;

    private final String tiers;

    HoleTemplate(String tiers) {
        this.tiers = tiers;
    }

    /** Whether this template can be drawn for a hole of this tier ('E', 'M' or 'H'). */
    public boolean fits(char tier) {
        return tiers.indexOf(Character.toUpperCase(tier)) >= 0;
    }

    /** The templates for a tier, in their fixed order. */
    public static List<HoleTemplate> forTier(char tier) {
        List<HoleTemplate> out = new ArrayList<>();
        for (HoleTemplate t : values()) {
            if (t.fits(tier)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Draw this template into the plot whose min corner is ({@code plotX}, {@code plotZ}), the turf
     * top at {@code turfY}, with its numbers drawn from {@code r}.
     *
     * @param tier the hole's tier ('E', 'M' or 'H'), for templates shared by two tiers
     */
    public HoleLayout draw(GenRandom r, char tier, int plotX, int plotZ, int turfY) {
        Sketch s = new Sketch();
        boolean hard = Character.toUpperCase(tier) == 'H';
        boolean mirror;
        // Every template draws its numbers in the same order for a seed; changing one changes
        // every later number, and so the golden plans (bump GolfPlanner.ALGO with it).
        String what;
        switch (this) {
            case STRAIGHT -> {
                int length = r.nextInt(10, 18);
                int off = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                straight(s, 7, 11, length, off);
                what = length + " long, cup " + signed(off);
            }
            case BUMPERS -> {
                int length = r.nextInt(10, 16);
                int off = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                straight(s, 7, 11, length, off);
                int rocks = r.nextInt(1, 2);
                int placed = 0;
                for (int i = 0; i < 12 && placed < rocks; i++) {
                    int x = r.nextInt(7, 11);
                    int z = r.nextInt(6, s.cupZ - 3);
                    if (s.canRock(x, z)) {
                        s.rock(x, z, true);
                        placed++;
                    }
                }
                s.slimeWalls(6, 6, 1, s.cupZ + 2);
                s.slimeWalls(12, 12, 1, s.cupZ + 2);
                what = length + " long, " + placed + " rock" + (placed == 1 ? "" : "s");
            }
            case RAMP -> {
                boolean medium = Character.toUpperCase(tier) != 'E';
                int approach = medium ? r.nextInt(6, 9) : r.nextInt(4, 6);
                int onGreen = medium ? r.nextInt(5, 9) : r.nextInt(3, 6);
                int off = medium ? r.nextInt(-1, 1) : 0;
                mirror = r.nextBoolean();
                int rampZ = 3 + approach;
                int cupZ = rampZ + onGreen;
                s.lane(7, 11, 2, rampZ - 1, 0);
                s.lane(7, 11, rampZ, cupZ + 1, 2);
                s.lane(8, 10, rampZ, rampZ, 1);
                s.tee(9, 3);
                s.cup(9 + off, cupZ);
                what = "ramp after " + approach + ", cup " + onGreen + " on";
            }
            case DOGLEG -> {
                int up = r.nextInt(10, 14);
                int across = r.nextInt(6, 10);
                int offZ = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                int elbowZ = 3 + up;
                s.lane(2, 6, 2, elbowZ + 2, 0);
                s.lane(2, 4 + across + 1, elbowZ - 2, elbowZ + 2, 0);
                s.tee(4, 3);
                s.cup(4 + across, elbowZ + offZ);
                s.slimeWalls(1, 7, elbowZ + 3, elbowZ + 3);
                s.slimeWalls(1, 1, elbowZ - 2, elbowZ + 3);
                what = up + " up, " + across + " across";
            }
            case ICE_RUN -> {
                int turf = r.nextInt(2, 4);
                int ice = r.nextInt(4, 8);
                int brake = r.nextInt(2, 3);
                int after = r.nextInt(3, 6);
                int off = r.nextBoolean() ? 1 : -1;
                mirror = r.nextBoolean();
                int ice0 = 3 + turf;
                int brake0 = ice0 + ice;
                int cupZ = brake0 + brake - 1 + after;
                straightTo(s, 7, 11, cupZ, off);
                s.floor(7, 11, ice0, brake0 - 1, Sketch.ICE);
                s.floor(7, 11, brake0, brake0 + brake - 1, Sketch.BRAKE);
                what = ice + " ice, " + brake + " brake";
            }
            case ISLAND -> {
                int approach = hard ? r.nextInt(6, 11) : r.nextInt(7, 11);
                int entrance = hard ? r.nextInt(8, 9) : 8;
                int width = hard ? 2 : 3;
                int ox = r.nextInt(-1, 1);
                int oz = r.nextInt(-1, 1);
                mirror = r.nextBoolean();
                int rampZ = 3 + approach;
                s.lane(7, 11, 2, rampZ - 1, 0);
                s.lane(7, 11, rampZ, rampZ + 5, 2);
                s.lane(entrance, entrance + width - 1, rampZ, rampZ, 1);
                s.tee(9, 3);
                s.cup(9 + ox, rampZ + 3 + oz);
                what = "green after " + approach + ", entrance " + width + " wide";
            }
            case S_BEND -> {
                int first = r.nextInt(7, 9);
                int across = r.nextInt(7, 9);
                int last = r.nextInt(5, 7);
                mirror = r.nextBoolean();
                int bendZ = 3 + first;
                int bendX = 4 + across;
                int cupZ = bendZ + last;
                s.lane(2, 6, 2, bendZ + 2, 0);
                s.lane(2, bendX + 2, bendZ - 2, bendZ + 2, 0);
                s.lane(bendX - 2, bendX + 2, bendZ - 2, cupZ + 1, 0);
                s.tee(4, 3);
                s.cup(bendX + r.nextInt(-1, 1), cupZ);
                s.slimeWalls(1, 7, bendZ + 3, bendZ + 3);
                s.slimeWalls(1, 1, bendZ - 2, bendZ + 3);
                s.slimeWalls(bendX - 3, bendX + 3, bendZ - 3, bendZ - 3);
                s.slimeWalls(bendX + 3, bendX + 3, bendZ - 3, bendZ + 2);
                what = first + " up, " + across + " across, " + last + " up";
            }
            case NARROW_ICE -> {
                int length = r.nextInt(10, 15);
                int pocket = r.nextInt(4, 6);
                mirror = r.nextBoolean();
                int top = 3 + length;
                s.lane(6, 8, 2, top + 1, 0);
                s.floor(6, 8, 5, top + 1, Sketch.ICE);
                s.lane(9, 8 + pocket + 1, top - 1, top + 1, 0);
                s.tee(7, 3);
                s.cup(8 + pocket, top);
                s.rock(9 + r.nextInt(0, pocket - 3), top + (r.nextBoolean() ? 1 : -1), false);
                what = length + " of ice, " + pocket + " across";
            }
            case SAFE_STRAIGHT -> {
                mirror = false;
                straight(s, 7, 11, 10, 0);
                what = "10 long";
            }
            default -> throw new IllegalStateException("unknown template " + this);
        }
        return s.render(this, mirror, plotX, plotZ, turfY, name() + " " + what + (mirror ? ", mirrored" : ""));
    }

    /** A flat straight of lane columns x0..x1: tee at (9, 3), the cup {@code length} ahead, one off. */
    private static void straight(Sketch s, int x0, int x1, int length, int off) {
        straightTo(s, x0, x1, 3 + length, off);
    }

    private static void straightTo(Sketch s, int x0, int x1, int cupZ, int off) {
        s.lane(x0, x1, 2, cupZ + 1, 0);
        s.tee((x0 + x1) / 2, 3);
        s.cup((x0 + x1) / 2 + off, cupZ);
    }

    private static String signed(int n) {
        return n == 0 ? "centred" : (n > 0 ? "+" : "") + n;
    }

    /**
     * A hole drawn on the plot's own cells before it becomes blocks: which cells are lane and at
     * what height, what their floor is, where the rocks, slime walls, tee and cup are.
     */
    static final class Sketch {

        /** Floors. */
        static final byte TURF = 0;
        static final byte ICE = 1;
        static final byte BRAKE = 2;

        /** Lane heights in half blocks above T; -1 is not lane. */
        private final int[][] level = new int[PLOT_X][PLOT_Z];
        private final byte[][] floor = new byte[PLOT_X][PLOT_Z];
        private final boolean[][] rock = new boolean[PLOT_X][PLOT_Z];
        private final boolean[][] slime = new boolean[PLOT_X][PLOT_Z];
        int teeX = -1;
        int teeZ = -1;
        int cupX = -1;
        int cupZ = -1;

        Sketch() {
            for (int[] col : level) {
                java.util.Arrays.fill(col, -1);
            }
        }

        /** Lane cells x0..x1, z0..z1 at height {@code halfBlocks} (0 turf, 1 slab, 2 raised). */
        void lane(int x0, int x1, int z0, int z1, int halfBlocks) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    check(x, z);
                    level[x][z] = halfBlocks;
                    rock[x][z] = false;
                }
            }
        }

        /** The floor of the lane cells in x0..x1, z0..z1. */
        void floor(int x0, int x1, int z0, int z1, byte kind) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (level[x][z] >= 0) {
                        floor[x][z] = kind;
                    }
                }
            }
        }

        /** Whether a rock may go here: a turf lane cell, clear of the tee, the cup ring and other rocks. */
        boolean canRock(int x, int z) {
            if (x < 0 || x >= PLOT_X || z < 0 || z >= PLOT_Z || level[x][z] != 0) {
                return false;
            }
            boolean nearCup = Math.abs(x - cupX) <= 2 && Math.abs(z - cupZ) <= 2;
            boolean nearTee = Math.abs(x - teeX) <= 1 && Math.abs(z - teeZ) <= 2;
            if (nearCup || nearTee) {
                return false;
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx >= 0 && nx < PLOT_X && nz >= 0 && nz < PLOT_Z && rock[nx][nz]) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** A single-block rock (slime or wood) standing in the lane. */
        void rock(int x, int z, boolean isSlime) {
            check(x, z);
            level[x][z] = -1;
            rock[x][z] = true;
            slime[x][z] = isSlime;
        }

        /** Make the walls in x0..x1, z0..z1 slime (cells that turn out not to be walls stay empty). */
        void slimeWalls(int x0, int x1, int z0, int z1) {
            for (int x = Math.max(0, x0); x <= Math.min(PLOT_X - 1, x1); x++) {
                for (int z = Math.max(0, z0); z <= Math.min(PLOT_Z - 1, z1); z++) {
                    if (level[x][z] < 0) {
                        slime[x][z] = true;
                    }
                }
            }
        }

        void tee(int x, int z) {
            check(x, z);
            teeX = x;
            teeZ = z;
        }

        void cup(int x, int z) {
            check(x, z);
            cupX = x;
            cupZ = z;
        }

        private static void check(int x, int z) {
            if (x < 1 || x >= PLOT_X - 1 || z < 1 || z >= PLOT_Z - 1) {
                throw new IllegalStateException("a hole drawn outside its plot: " + x + "," + z);
            }
        }

        private boolean lane(int x, int z) {
            return x >= 0 && x < PLOT_X && z >= 0 && z < PLOT_Z && level[x][z] >= 0;
        }

        /** Whether a wall or rock goes here: a cell off the lane with a lane cell round it (diagonals too). */
        private boolean walled(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && lane(x + dx, z + dz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Blocks of wall above T - 1, the same all round the hole: one block above its highest lane
         * cell (1 on a flat hole, 2 round a raised green).
         */
        private int wallHeight() {
            int highest = 0;
            for (int[] col : level) {
                for (int l : col) {
                    highest = Math.max(highest, l);
                }
            }
            return 1 + (highest + 1) / 2;
        }

        HoleLayout render(HoleTemplate template, boolean mirror, int plotX, int plotZ, int turfY, String describe) {
            int h = wallHeight();
            if (teeX < 0 || cupX < 0 || level[teeX][teeZ] != 0 || level[cupX][cupZ] < 0) {
                throw new IllegalStateException(template + " has no tee or cup on its lane");
            }
            int cupLevel = level[cupX][cupZ];
            if (cupLevel == 1) {
                throw new IllegalStateException(template + " put its cup on a slab");
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!lane(cupX + dx, cupZ + dz) || level[cupX + dx][cupZ + dz] != cupLevel) {
                        throw new IllegalStateException(template + "'s cup ring isn't level lane");
                    }
                }
            }
            List<HoleLayout.Placed> out = new ArrayList<>();
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int lx = 0; lx < PLOT_X; lx++) {
                for (int lz = 0; lz < PLOT_Z; lz++) {
                    int wx = plotX + (mirror ? PLOT_X - 1 - lx : lx);
                    int wz = plotZ + lz;
                    int lvl = level[lx][lz];
                    if (lvl >= 0) {
                        minX = Math.min(minX, wx);
                        maxX = Math.max(maxX, wx);
                        minZ = Math.min(minZ, wz);
                        maxZ = Math.max(maxZ, wz);
                        boolean cup = lx == cupX && lz == cupZ;
                        boolean tee = lx == teeX && lz == teeZ;
                        boolean ring = !cup && Math.abs(lx - cupX) <= 1 && Math.abs(lz - cupZ) <= 1;
                        String top = tee ? Palette.TEE : ring ? Palette.CUP_RING
                                : floor[lx][lz] == ICE ? Palette.GOLF_ICE
                                : floor[lx][lz] == BRAKE ? Palette.BRAKE : turf(wx, wz);
                        switch (lvl) {
                            case 0 -> out.add(cup ? new HoleLayout.Placed(wx, turfY - 2, wz, Palette.CUP)
                                    : new HoleLayout.Placed(wx, turfY - 1, wz, top));
                            case 1 -> {
                                out.add(new HoleLayout.Placed(wx, turfY - 1, wz, turf(wx, wz)));
                                out.add(new HoleLayout.Placed(wx, turfY, wz, Palette.RAMP));
                            }
                            default -> {
                                if (cup) {
                                    out.add(new HoleLayout.Placed(wx, turfY - 1, wz, Palette.CUP));
                                } else {
                                    out.add(new HoleLayout.Placed(wx, turfY - 1, wz, Palette.TURF_DARK));
                                    out.add(new HoleLayout.Placed(wx, turfY, wz, top));
                                }
                            }
                        }
                        continue;
                    }
                    if (!walled(lx, lz)) {
                        continue;
                    }
                    String wall = slime[lx][lz] ? Palette.BUMPER : Palette.GOLF_WALL;
                    if (rock[lx][lz]) {
                        out.add(new HoleLayout.Placed(wx, turfY - 1, wz, turf(wx, wz)));
                    } else {
                        out.add(new HoleLayout.Placed(wx, turfY - 1, wz, wall));
                    }
                    for (int y = 0; y < h; y++) {
                        out.add(new HoleLayout.Placed(wx, turfY + y, wz, wall));
                    }
                }
            }
            int cupWX = plotX + (mirror ? PLOT_X - 1 - cupX : cupX);
            int cupWZ = plotZ + cupZ;
            int cupTop = turfY + cupLevel / 2;
            out.add(new HoleLayout.Placed(cupWX, cupTop + 3, cupWZ, Palette.FLAG));
            int signLX = teeX;
            int signLZ = teeZ - 2;
            if (lane(signLX, signLZ) || !walled(signLX, signLZ)) {
                throw new IllegalStateException(template + " has no wall behind its tee for the sign");
            }
            int teeWX = plotX + (mirror ? PLOT_X - 1 - teeX : teeX);
            return new HoleLayout(template, mirror, turfY, teeWX, plotZ + teeZ, cupWX, cupWZ, cupTop, minX, minZ,
                    maxX, maxZ, teeWX, turfY + h, plotZ + signLZ, out, describe);
        }

        /** Checked turf, 2 x 2, so distances read at a glance. */
        private static String turf(int wx, int wz) {
            return (((wx >> 1) + (wz >> 1)) & 1) == 0 ? Palette.TURF_LIGHT : Palette.TURF_DARK;
        }
    }
}
