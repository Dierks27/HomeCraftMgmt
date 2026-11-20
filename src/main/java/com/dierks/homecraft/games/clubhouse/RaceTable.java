package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.chance.mobraces.RaceTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * Mob Races' table as a model racetrack (pure): where it stands ({@link Frame}) and everything drawn on it that is
 * not a block ({@link #pieces()}).
 *
 * <p>The table itself is plain blocks, {@value RaceTrack#LENGTH} long, {@value RaceTrack#DEPTH} deep and one high.
 * Everything that makes it a racetrack is a small block display laid on its top, so the same track is drawn on the
 * generated room's table and on a table the owner built: the clay running surface round the ring, the grass infield
 * with its flowers and bushes, white rails inside and out, a checkered line with a gantry over it, a little
 * grandstand with a crowd and a red roof, and flags.
 *
 * <p>Everything here is in the table's own measure ({@link RaceTrack}): {@code u} along it, {@code w} across it, and
 * a height over its top. A {@link Frame} turns that into the world.
 */
final class RaceTable {

    /** How thick the running surface and the grass are laid on the table: the racers stand on top of it. */
    static final double SURFACE = 0.04;
    /** The blocks the model is made of. */
    static final String CLAY = "minecraft:terracotta";
    static final String GRASS = "minecraft:moss_block";
    static final String WHITE = "minecraft:white_concrete";
    static final String BLACK = "minecraft:black_concrete";
    static final String STAND = "minecraft:light_gray_concrete";
    static final String STAND_BACK = "minecraft:gray_concrete";
    static final String ROOF = "minecraft:red_concrete";
    private static final String[] CROWD = {"minecraft:cyan_concrete", "minecraft:lime_concrete",
            "minecraft:orange_concrete", "minecraft:light_blue_concrete", "minecraft:magenta_concrete",
            "minecraft:yellow_concrete", "minecraft:red_concrete", "minecraft:white_concrete"};
    private static final String[] FLOWERS = {"minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower",
            "minecraft:oxeye_daisy", "minecraft:azure_bluet", "minecraft:allium", "minecraft:pink_tulip",
            "minecraft:blue_orchid"};
    /** Where the flowers stand in the infield ({@code u}, {@code w}), and how big they are drawn. */
    private static final double[][] FLOWER_AT = {{1.25, 1.2}, {1.7, 1.62}, {2.15, 1.25}, {2.6, 1.68}, {3.05, 1.3},
            {3.45, 1.66}, {3.9, 1.22}, {4.2, 1.6}};
    static final double FLOWER_SIZE = 0.22;
    /** The rails: how thick, how high their bar is, and how far the outer one stands in from the table's edge. */
    static final double RAIL = 0.03;
    static final double RAIL_UP = 0.13;
    static final double RAIL_IN = 0.02;
    /** The gantry over the line: how high its beam is. */
    static final double GANTRY_UP = 0.62;
    /** Nothing on the table is taller than this over its top (the generated room's barrier is one block up). */
    static final double TALLEST = 0.95;

    private RaceTable() {
    }

    /**
     * Where a table stands in the world: the corner its measure starts from, at the height of its top, and the two
     * directions it runs in (each one block east, west, south or north).
     *
     * @param ox  the corner's x
     * @param top the height of the table's top
     * @param oz  the corner's z
     * @param ux  one block along the table, in x
     * @param uz  one block along the table, in z
     * @param wx  one block across the table, in x
     * @param wz  one block across the table, in z
     */
    record Frame(double ox, double top, double oz, int ux, int uz, int wx, int wz) {

        /**
         * The table whose corner block someone stands on, facing along it: it runs {@value RaceTrack#LENGTH} blocks
         * the way they face and {@value RaceTrack#DEPTH} to their right, and its top is under their feet.
         */
        static Frame standingOn(double x, double feetY, double z, float yaw) {
            int[] a = ClubhouseRoom.ahead(yaw);
            int[] r = ClubhouseRoom.right(yaw);
            double cx = Math.floor(x) + 0.5;
            double cz = Math.floor(z) + 0.5;
            return new Frame(cx - 0.5 * a[0] - 0.5 * r[0], Math.floor(feetY + 1e-6), cz - 0.5 * a[1] - 0.5 * r[1],
                    a[0], a[1], r[0], r[1]);
        }

        double x(double u, double w) {
            return ox + u * ux + w * wx;
        }

        double z(double u, double w) {
            return oz + u * uz + w * wz;
        }

        /** The Minecraft yaw of a heading on the table (0 along {@code u}, 90 along {@code w}). */
        float yaw(double heading) {
            double rad = Math.toRadians(heading);
            double dx = Math.cos(rad) * ux + Math.sin(rad) * wx;
            double dz = Math.cos(rad) * uz + Math.sin(rad) * wz;
            return (float) Math.toDegrees(Math.atan2(-dx, dz));
        }

        /** The block (x, z) of the table's cell {@code i} along and {@code k} across. */
        int[] cell(int i, int k) {
            return new int[]{(int) Math.floor(x(i + 0.5, k + 0.5)), (int) Math.floor(z(i + 0.5, k + 0.5))};
        }

        /** The y of the table's blocks (their top is {@link #top}). */
        int blockY() {
            return (int) Math.round(top) - 1;
        }

        /** The table's footprint in the world: {minX, minZ, maxX, maxZ}. */
        double[] footprint() {
            double x0 = x(0, 0);
            double x1 = x(RaceTrack.LENGTH, RaceTrack.DEPTH);
            double z0 = z(0, 0);
            double z1 = z(RaceTrack.LENGTH, RaceTrack.DEPTH);
            return new double[]{Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1)};
        }
    }

    /**
     * One piece of the model: a block drawn as a box, {@code su} by {@code sy} by {@code sw}, its low corner at
     * ({@code u}, {@code y}, {@code w}), {@code y} over the table's top.
     */
    record Piece(String block, double u, double y, double w, double su, double sy, double sw) {

        /** Its box in the world for a table at {@code f}: {minX, minY, minZ, sizeX, sizeY, sizeZ}. */
        double[] box(Frame f) {
            double x0 = f.x(u, w);
            double x1 = f.x(u + su, w + sw);
            double z0 = f.z(u, w);
            double z1 = f.z(u + su, w + sw);
            return new double[]{Math.min(x0, x1), f.top() + y, Math.min(z0, z1), Math.abs(x1 - x0), sy,
                    Math.abs(z1 - z0)};
        }
    }

    /** Everything drawn on the table, in the order it is made. */
    static List<Piece> pieces() {
        List<Piece> out = new ArrayList<>();
        surface(out);
        rails(out);
        line(out);
        grandstand(out);
        flags(out);
        garden(out);
        return List.copyOf(out);
    }

    /** The running surface on every block of the ring, and grass on the infield: one thin slab a block. */
    private static void surface(List<Piece> out) {
        for (int i = 0; i < RaceTrack.LENGTH; i++) {
            for (int k = 0; k < RaceTrack.DEPTH; k++) {
                boolean infield = i >= RaceTrack.IN_U0 && i < RaceTrack.IN_U1 && k >= RaceTrack.IN_W0
                        && k < RaceTrack.IN_W1;
                out.add(new Piece(infield ? GRASS : CLAY, i, 0, k, 1, SURFACE, 1));
            }
        }
    }

    /** White rails: a bar on posts round the outside of the ring, and one round the infield. */
    private static void rails(List<Piece> out) {
        double l = RaceTrack.LENGTH;
        double d = RaceTrack.DEPTH;
        double far = l - RAIL_IN - RAIL;
        double deep = d - RAIL_IN - RAIL;
        // the outer rail's four bars, then its posts
        out.add(new Piece(WHITE, RAIL_IN, SURFACE + RAIL_UP, RAIL_IN, l - 2 * RAIL_IN, RAIL, RAIL));
        out.add(new Piece(WHITE, RAIL_IN, SURFACE + RAIL_UP, deep, l - 2 * RAIL_IN, RAIL, RAIL));
        out.add(new Piece(WHITE, RAIL_IN, SURFACE + RAIL_UP, RAIL_IN, RAIL, RAIL, d - 2 * RAIL_IN));
        out.add(new Piece(WHITE, far, SURFACE + RAIL_UP, RAIL_IN, RAIL, RAIL, d - 2 * RAIL_IN));
        for (int i = 0; i <= RaceTrack.LENGTH; i++) {
            double u = Math.max(RAIL_IN, Math.min(far, i - RAIL / 2));
            out.add(new Piece(WHITE, u, SURFACE, RAIL_IN, RAIL, RAIL_UP, RAIL));
            out.add(new Piece(WHITE, u, SURFACE, deep, RAIL, RAIL_UP, RAIL));
        }
        for (int k = 1; k < RaceTrack.DEPTH; k++) {
            out.add(new Piece(WHITE, RAIL_IN, SURFACE, k - RAIL / 2, RAIL, RAIL_UP, RAIL));
            out.add(new Piece(WHITE, far, SURFACE, k - RAIL / 2, RAIL, RAIL_UP, RAIL));
        }
        // the inner rail, on the infield's edge
        double u0 = RaceTrack.IN_U0;
        double u1 = RaceTrack.IN_U1;
        double w0 = RaceTrack.IN_W0;
        double w1 = RaceTrack.IN_W1;
        out.add(new Piece(WHITE, u0, SURFACE + RAIL_UP, w0, u1 - u0, RAIL, RAIL));
        out.add(new Piece(WHITE, u0, SURFACE + RAIL_UP, w1 - RAIL, u1 - u0, RAIL, RAIL));
        out.add(new Piece(WHITE, u0, SURFACE + RAIL_UP, w0, RAIL, RAIL, w1 - w0));
        out.add(new Piece(WHITE, u1 - RAIL, SURFACE + RAIL_UP, w0, RAIL, RAIL, w1 - w0));
        for (double u = u0; u <= u1; u += 2) {
            double at = Math.min(u1 - RAIL, u);
            out.add(new Piece(WHITE, at, SURFACE, w0, RAIL, RAIL_UP, RAIL));
            out.add(new Piece(WHITE, at, SURFACE, w1 - RAIL, RAIL, RAIL_UP, RAIL));
        }
    }

    /** The start and finish line: a checkered band across the first long side, and a checkered gantry over it. */
    private static void line(List<Piece> out) {
        double u = RaceTrack.LINE_U;
        double w0 = 0.1;
        out.add(new Piece(WHITE, u - 0.1, SURFACE, w0, 0.2, 0.005, 0.8));
        for (int row = 0; row < 8; row++) {
            out.add(new Piece(BLACK, u - (row % 2 == 0 ? 0.1 : 0.0), SURFACE + 0.005, w0 + row * 0.1, 0.1, 0.005,
                    0.1));
        }
        double post = 0.04;
        double outer = 0.01; // on the outer rail's line, clear of the outside lane
        double inner = RaceTrack.IN_W0 + RAIL + 0.01; // just inside the infield's rail
        out.add(new Piece(WHITE, u - post / 2, SURFACE, outer, post, GANTRY_UP, post));
        out.add(new Piece(WHITE, u - post / 2, SURFACE, inner, post, GANTRY_UP, post));
        double span = inner + post - outer;
        for (int n = 0; n < 8; n++) {
            out.add(new Piece(n % 2 == 0 ? BLACK : WHITE, u - post / 2, SURFACE + GANTRY_UP, outer + n * span / 8,
                    post, 0.08, span / 8));
        }
    }

    /** A little grandstand in the infield, facing the line: three steps, a back, a red roof on posts, a crowd. */
    private static void grandstand(List<Piece> out) {
        double u0 = 4.6;
        double len = 2.0;
        for (int step = 0; step < 3; step++) {
            out.add(new Piece(STAND, u0, SURFACE, 1.25 + 0.2 * step, len, 0.1 * (step + 1), 0.2));
        }
        out.add(new Piece(STAND_BACK, u0, SURFACE, 1.85, len, 0.5, 0.05));
        out.add(new Piece(ROOF, u0 - 0.05, SURFACE + 0.56, 1.4, len + 0.1, 0.04, 0.55));
        out.add(new Piece(WHITE, u0, SURFACE + 0.1, 1.42, RAIL, 0.46, RAIL));
        out.add(new Piece(WHITE, u0 + len - RAIL, SURFACE + 0.1, 1.42, RAIL, 0.46, RAIL));
        double[][] rows = {{4.8, 5.2, 5.6, 6.0, 6.4}, {4.7, 5.05, 5.45, 5.85, 6.25}, {4.9, 5.3, 5.7, 6.1, 6.45}};
        int colour = 0;
        for (int step = 0; step < rows.length; step++) {
            for (double u : rows[step]) {
                out.add(new Piece(CROWD[colour++ % CROWD.length], u, SURFACE + 0.1 * (step + 1), 1.31 + 0.2 * step,
                        0.07, 0.07, 0.07));
            }
        }
    }

    /** Flags: two on the grandstand's roof, two at the infield's other end. */
    private static void flags(List<Piece> out) {
        flag(out, 4.62, 1.9, SURFACE + 0.6, 0.3, "minecraft:red_concrete");
        flag(out, 6.55, 1.9, SURFACE + 0.6, 0.3, "minecraft:yellow_concrete");
        flag(out, 1.12, 1.15, SURFACE, 0.5, "minecraft:lime_concrete");
        flag(out, 1.12, 1.82, SURFACE, 0.5, "minecraft:light_blue_concrete");
    }

    private static void flag(List<Piece> out, double u, double w, double y, double tall, String colour) {
        out.add(new Piece(WHITE, u, y, w, 0.02, tall, 0.02));
        out.add(new Piece(colour, u + 0.02, y + tall - 0.11, w + 0.005, 0.18, 0.1, 0.01));
    }

    /** Small flowers and two bushes on the grass. */
    private static void garden(List<Piece> out) {
        for (int n = 0; n < FLOWER_AT.length; n++) {
            out.add(new Piece(FLOWERS[n % FLOWERS.length], FLOWER_AT[n][0], SURFACE, FLOWER_AT[n][1], FLOWER_SIZE,
                    FLOWER_SIZE, FLOWER_SIZE));
        }
        out.add(new Piece("minecraft:flowering_azalea_leaves", 1.42, SURFACE, 1.68, 0.2, 0.2, 0.2));
        out.add(new Piece("minecraft:azalea_leaves", 3.25, SURFACE, 1.62, 0.17, 0.17, 0.17));
    }
}
