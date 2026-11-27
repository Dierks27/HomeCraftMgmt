package com.dierks.homecraft.games.clubhouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 0.60.0, the owner's own score TVs (the owner: "can I make a TV in there and have it saved so I can watch streaming
 * sports?"): {@code /hcm games clubhouse tv add [size]} puts the live scores on the block face the admin looks at (a TV
 * they built, say), {@code tv remove} takes the nearest one down, {@code tv list} lists them. They are kept in
 * {@code hcm_meta} ({@link #KEY}), so they stay through a restart and a rebuild of the Clubhouse (pure: the list as
 * text and back, the nearest one, the words; {@code SportsBar} draws them).
 */
final class ClubTvs {

    /** Where the list is kept. */
    static final String KEY = ClubhouseRoom.META_PREFIX + "tvs";
    /** The most TVs kept. */
    static final int MAX = 12;
    /** A TV's size (blocks across its screen): the smallest, the biggest, and the size when none is given. */
    static final int SIZE_MIN = 1;
    static final int SIZE_MAX = 6;
    static final int SIZE = 2;
    /** How far the admin may look for the block face. */
    static final int REACH = 8;
    /** How near a TV must be for {@code tv remove} to find it. */
    static final double NEAR = 12;

    private ClubTvs() {
    }

    /**
     * One TV: the middle of its screen (just off the block face), the way it faces (a yaw: the face's own way), and
     * how many blocks across its screen is.
     */
    record Tv(String world, double x, double y, double z, float yaw, int size) {

        /** "world;x;y;z;yaw;size", as kept. */
        String text() {
            return world + ";" + f(x) + ";" + f(y) + ";" + f(z) + ";" + f(yaw) + ";" + size;
        }

        /** Read one, or {@code null} for anything else. */
        static Tv of(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String[] p = text.split(";");
            if (p.length != 6 || p[0].isBlank()) {
                return null;
            }
            try {
                return new Tv(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Float.parseFloat(p[4]), clamp(Integer.parseInt(p[5].trim())));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        double distanceSq(String w, double px, double py, double pz) {
            if (w == null || !w.equalsIgnoreCase(world)) {
                return Double.MAX_VALUE;
            }
            return (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz);
        }

        private static String f(double v) {
            return String.format(Locale.ROOT, "%.2f", v);
        }
    }

    /** A size kept inside {@link #SIZE_MIN}-{@link #SIZE_MAX}. */
    static int clamp(int size) {
        return Math.max(SIZE_MIN, Math.min(SIZE_MAX, size));
    }

    /** The kept list as text (one TV a line). */
    static String text(List<Tv> tvs) {
        List<String> lines = new ArrayList<>();
        for (Tv t : tvs) {
            lines.add(t.text());
        }
        return String.join("\n", lines);
    }

    /** The kept list read back (anything unreadable left out). */
    static List<Tv> parse(String text) {
        List<Tv> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String line : text.split("\n")) {
            Tv t = Tv.of(line.trim());
            if (t != null && out.size() < MAX) {
                out.add(t);
            }
        }
        return out;
    }

    /** The index of the TV nearest a place (within {@link #NEAR}), or -1. */
    static int nearest(List<Tv> tvs, String world, double x, double y, double z) {
        int best = -1;
        double d = NEAR * NEAR;
        for (int i = 0; i < tvs.size(); i++) {
            double di = tvs.get(i).distanceSq(world, x, y, z);
            if (di <= d) {
                d = di;
                best = i;
            }
        }
        return best;
    }

    /**
     * A screen's middle off a block face (pure): the block at (bx, by, bz), its face pointing (dx, dz) (one of them
     * ±1, the other 0); {x, y, z, yaw}. The screen stands a hair off the face, facing out of it.
     */
    static double[] onFace(int bx, int by, int bz, int dx, int dz) {
        double x = bx + 0.5 + dx * 0.52;
        double z = bz + 0.5 + dz * 0.52;
        float yaw = dz == 1 ? 0f : dz == -1 ? 180f : dx == -1 ? 90f : -90f;
        return new double[]{x, by + 0.5, z, yaw};
    }

    /** The {@code tv list} lines. */
    static List<String> lines(List<Tv> tvs) {
        List<String> out = new ArrayList<>();
        out.add("&6Score TVs &7(" + tvs.size() + " of " + MAX + ")");
        if (tvs.isEmpty()) {
            out.add("&7None yet. Look at a wall in the Clubhouse and use &e/hcm games clubhouse tv add [size]&7.");
        }
        for (int i = 0; i < tvs.size(); i++) {
            Tv t = tvs.get(i);
            out.add("&e" + (i + 1) + ". &f" + t.world() + " " + Math.round(t.x()) + ", " + Math.round(t.y()) + ", "
                    + Math.round(t.z()) + " &7- " + t.size() + " across");
        }
        return out;
    }
}
