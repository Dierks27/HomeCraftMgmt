package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Box;

/**
 * A plot of the keep area holding a kept course (GEN-SPEC-KEEP §4), as kept in {@code hcm_meta}
 * ({@code gen.keep.plot.<n>}).
 *
 * <p>The plot's box is stored with it, so moving {@code keep.area} in config never loses track of
 * a course already kept: {@code clear-plot} empties the box the course really stands in, and a
 * plot number stays taken until it is cleared.
 *
 * @param n        the plot number (1 up)
 * @param courseId the kept course
 * @param world    its world
 * @param box      the whole plot
 * @param slot     the slot it was kept from
 * @param edition  that slot's edition
 */
public record KeptPlot(int n, String courseId, String world, Box box, String slot, String edition) {

    /** As stored: {@code courseId|world|minX,minY,minZ,maxX,maxY,maxZ|slot|edition}. */
    public String text() {
        return courseId + "|" + world + "|" + boxText(box) + "|" + slot + "|" + edition;
    }

    /** A stored plot, or {@code null} when unreadable. */
    public static KeptPlot parse(int n, String text) {
        if (text == null) {
            return null;
        }
        String[] p = text.split("\\|", -1);
        if (p.length != 5) {
            return null;
        }
        Box b = box(p[2]);
        return b == null || p[0].isBlank() ? null : new KeptPlot(n, p[0], p[1], b, p[3], p[4]);
    }

    /** A box as stored: "minX,minY,minZ,maxX,maxY,maxZ". */
    static String boxText(Box b) {
        return b.minX() + "," + b.minY() + "," + b.minZ() + "," + b.maxX() + "," + b.maxY() + "," + b.maxZ();
    }

    /** A stored box, or {@code null}. */
    static Box box(String text) {
        if (text == null) {
            return null;
        }
        String[] p = text.split(",");
        if (p.length != 6) {
            return null;
        }
        try {
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) {
                v[i] = Integer.parseInt(p[i].trim());
            }
            return Box.of(v[0], v[1], v[2], v[3], v[4], v[5]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
