package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.gen.api.GenRandom;

/**
 * The obstacle layers' shapes (EVENTS-DROPPER-SPEC §B.1.3, §B.1.5 step 3): a template says which
 * blocks of an 11 x 11 plate start solid; the planner then cuts the path opening and the witness's
 * tube out of it, so a template can never block the proven way down.
 *
 * <ul>
 *   <li>{@link Template#PLATE}: a whole plate; the only way through is the path opening.</li>
 *   <li>{@link Template#RING}: a centre plate with an open border (1 or 2 wide) along the walls.</li>
 *   <li>{@link Template#BARS}: solid bars two wide with one-wide slits between, along x or z.</li>
 *   <li>{@link Template#CROSS}: two solid 3-wide bars crossing the shaft, open quarters between.</li>
 *   <li>{@link Template#TWIN}: a whole plate with two good openings, each proven by its own witness:
 *       a real choice (layer 1 only).</li>
 *   <li>{@link Template#CHECKER}: 2 x 2 squares, solid and open by turns.</li>
 *   <li>{@link Template#DECOY}: a whole plate with a second hole whose column the next layer blocks
 *       (Hard only, never the last layer).</li>
 * </ul>
 * Pure; the draws come from the layer's own {@link GenRandom} stream.
 */
public final class LayerKit {

    /** A layer's template. */
    public enum Template { PLATE, RING, BARS, CROSS, TWIN, CHECKER, DECOY }

    /**
     * A template with its drawn parameters: RING's border width, BARS' axis and phase, CROSS's
     * centre, CHECKER's phase.
     */
    public record Shape(Template template, int a, int b) {

        /** Whether plate cell (i, j) (0-10 along x and z) starts solid. */
        public boolean solid(int i, int j) {
            int n = DropperGeometry.INSIDE;
            return switch (template) {
                case PLATE, TWIN, DECOY -> true;
                case RING -> i >= a && j >= a && i < n - a && j < n - a;
                case BARS -> Math.floorMod((a == 0 ? i : j) + b, 3) != 2;
                case CROSS -> Math.abs(i - a) <= 1 || Math.abs(j - b) <= 1;
                case CHECKER -> Math.floorMod((i >> 1) + (j >> 1) + a, 2) == 0;
            };
        }

        /** For admin lines: {@code RING/2}. */
        public String describe() {
            return switch (template) {
                case RING -> "RING/" + a;
                case BARS -> "BARS/" + (a == 0 ? "x" : "z");
                default -> template.name();
            };
        }
    }

    private LayerKit() {
    }

    /** Draw {@code t}'s parameters. */
    public static Shape shape(Template t, GenRandom rng) {
        return switch (t) {
            case RING -> new Shape(t, rng.nextInt(1, 2), 0);
            case BARS -> new Shape(t, rng.nextInt(2), rng.nextInt(3));
            case CROSS -> new Shape(t, rng.nextInt(3, 7), rng.nextInt(3, 7));
            case CHECKER -> new Shape(t, rng.nextInt(2), 0);
            default -> new Shape(t, 0, 0);
        };
    }

    /** How many plate cells start solid. */
    public static int solidCount(Shape s) {
        int n = 0;
        for (int i = 0; i < DropperGeometry.INSIDE; i++) {
            for (int j = 0; j < DropperGeometry.INSIDE; j++) {
                if (s.solid(i, j)) {
                    n++;
                }
            }
        }
        return n;
    }
}
