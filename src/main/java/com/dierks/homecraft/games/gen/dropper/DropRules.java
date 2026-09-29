package com.dierks.homecraft.games.gen.dropper;

import com.dierks.homecraft.games.trial.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Dropper's tier table (EVENTS-DROPPER-SPEC §B.1.3) and the arithmetic every other part shares:
 * how deep a level drops, where its layers go, how big its openings and pools are, how much room the
 * witness keeps, how many input changes it may make, how late and how sloppy the pilots are, which
 * templates and lights it gets; the mix a slot is set to; the reference time and the shortest time.
 *
 * <p>Why a table and not tuned constants spread through the planner: the planner, the validator
 * and the tests must agree on every number, and a test pins the relations the proof leans on (an
 * opening always fits the hitbox and its clearance; the clearance is more than one tick of sideways
 * travel; Easy is walk-only).
 *
 * <p>Heights are in blocks below the ledge top ("depth"). A layer's depth is where its plate's top
 * is, so its block row is {@code depth + 1} below the ledge top.
 */
public final class DropRules {

    /** A level's difficulty: one letter of a mix. */
    public enum Level {
        //    letter drop layers firstMin firstMax gapMin gapMax minOpen tube  changes before delays        aim  pool lights
        EASY('E', 32, 2, 12, 14, 9, 11, 5, 0.6, 1, 1, new int[]{0, 3, 5}, 10, 11, 2,
                List.of(LayerKit.Template.PLATE, LayerKit.Template.RING)),
        MEDIUM('M', 40, 3, 11, 13, 8, 10, 3, 0.4, 2, 2, new int[]{0, 2, 4}, 10, 7, 1,
                List.of(LayerKit.Template.PLATE, LayerKit.Template.RING, LayerKit.Template.BARS,
                        LayerKit.Template.CROSS, LayerKit.Template.TWIN)),
        HARD('H', 48, 4, 10, 12, 7, 9, 2, 0.25, 4, 0, new int[]{0, 1, 2}, 5, 5, 0,
                List.of(LayerKit.Template.PLATE, LayerKit.Template.RING, LayerKit.Template.BARS,
                        LayerKit.Template.CROSS, LayerKit.Template.TWIN, LayerKit.Template.CHECKER,
                        LayerKit.Template.DECOY));

        private final char letter;
        private final int drop;
        private final int layers;
        private final int firstMin;
        private final int firstMax;
        private final int gapMin;
        private final int gapMax;
        private final int minOpening;
        private final double tube;
        private final int maxChanges;
        private final int changesBefore;
        private final int[] delays;
        private final double aimError;
        private final int pool;
        private final int litLayers;
        private final List<LayerKit.Template> templates;

        Level(char letter, int drop, int layers, int firstMin, int firstMax, int gapMin, int gapMax, int minOpening,
              double tube, int maxChanges, int changesBefore, int[] delays, double aimError, int pool,
              int litLayers, List<LayerKit.Template> templates) {
            this.letter = letter;
            this.drop = drop;
            this.layers = layers;
            this.firstMin = firstMin;
            this.firstMax = firstMax;
            this.gapMin = gapMin;
            this.gapMax = gapMax;
            this.minOpening = minOpening;
            this.tube = tube;
            this.maxChanges = maxChanges;
            this.changesBefore = changesBefore;
            this.delays = delays;
            this.aimError = aimError;
            this.pool = pool;
            this.litLayers = litLayers;
            this.templates = templates;
        }

        /** Its letter in a mix: E, M or H. */
        public char letter() {
            return letter;
        }

        /** Blocks from the ledge top down to the water: 32, 40, 48. */
        public int drop() {
            return drop;
        }

        /** How many obstacle layers: 2, 3, 4. */
        public int layers() {
            return layers;
        }

        /** The first layer's depth below the ledge top, lowest and highest. */
        public int firstMin() {
            return firstMin;
        }

        public int firstMax() {
            return firstMax;
        }

        /** The spacing between layers, lowest and highest. */
        public int gapMin() {
            return gapMin;
        }

        public int gapMax() {
            return gapMax;
        }

        /** The smallest path opening, in blocks a side: 5, 3, 2. */
        public int minOpening() {
            return minOpening;
        }

        /** The clearance r the witness keeps from every obstacle: 0.6, 0.4, 0.25. */
        public double tube() {
            return tube;
        }

        /** The most input changes the witness may make: 1, 2, 4. */
        public int maxChanges() {
            return maxChanges;
        }

        /**
         * Every change must come before the feet reach this layer (1-based): 1 for Easy, 2 for
         * Medium; 0 for Hard, whose changes may come anywhere.
         */
        public int changesBefore() {
            return changesBefore;
        }

        /** The pilots' reaction delays, in ticks. */
        public int[] delays() {
            return delays.clone();
        }

        /** The sloppy pilots' constant aim error, in degrees. */
        public double aimError() {
            return aimError;
        }

        /** The pool's size a side: 11 (the whole floor), 7, 5. */
        public int pool() {
            return pool;
        }

        /** How many layers, from the top, get guide lights round the path opening: 2 (all), 1, 0. */
        public int litLayers() {
            return litLayers;
        }

        /** The layer templates it may draw. */
        public List<LayerKit.Template> templates() {
            return templates;
        }

        /** Whether the pool is the whole floor (Easy): it has no rim. */
        public boolean wholeFloor() {
            return pool >= DropperGeometry.INSIDE;
        }

        /** The opening the path gets: the tier's minimum, and never less than the hitbox plus 2r. */
        public int opening() {
            return Math.max(minOpening, (int) Math.ceil(DropSim.WIDTH + 2 * tube - 1e-9));
        }

        /** The walk-off fall time to the water, in ticks: 32, 37, 40. */
        public int fallTicks() {
            return DropSim.fallTicks(drop);
        }

        /** The deepest a layer may be: the water, less the clear air above it, less the plate. */
        public int deepestLayer() {
            return drop - DropperGeometry.CLEAR_AIR - 1;
        }

        /** The level whose letter is {@code c} (any case), or {@code null}. */
        public static Level of(char c) {
            char u = Character.toUpperCase(c);
            for (Level l : values()) {
                if (l.letter == u) {
                    return l;
                }
            }
            return null;
        }
    }

    /** The most levels a Dropper has. */
    public static final int MAX_LEVELS = 5;
    /** The fewest blocks between a ledge and its pool (DropperLayout's rule). */
    public static final int MIN_DROP = 24;
    /** Per level on top of the fall: landing, the hop and a look round (ms). */
    public static final long LEVEL_EXTRA_MS = 1_500;
    /** Per hop between levels (ms). */
    public static final long HOP_MS = 250;

    private static final Pattern MIX = Pattern.compile("[EMH]{1," + MAX_LEVELS + "}");

    private DropRules() {
    }

    /** The mix in upper case, trimmed; "" for none. */
    public static String normalise(String mix) {
        return mix == null ? "" : mix.trim().toUpperCase(Locale.ROOT);
    }

    /** Why {@code mix} can't be a Dropper's mix, or {@code null} when it can: 1 to 5 of E, M and H. */
    public static String mixProblem(String mix) {
        return MIX.matcher(normalise(mix)).matches() ? null
                : "a dropper mix is 1-" + MAX_LEVELS + " of E, M and H (like EEMMH)";
    }

    /** The levels of a mix, in order; empty when it isn't one. */
    public static List<Level> levels(String mix) {
        List<Level> out = new ArrayList<>();
        if (mixProblem(mix) != null) {
            return out;
        }
        for (char c : normalise(mix).toCharArray()) {
            out.add(Level.of(c));
        }
        return out;
    }

    /**
     * The row's tier: the rounded mean of the letters (E = 1, M = 2, H = 3; a half rounds up), so
     * EEE is easy and EEMMH medium. It drives the star factors and the first-clear reward.
     */
    public static Tier tier(String mix) {
        List<Level> levels = levels(mix);
        if (levels.isEmpty()) {
            return Tier.EASY;
        }
        int sum = 0;
        for (Level l : levels) {
            sum += l.ordinal() + 1;
        }
        long mean = Math.round((double) sum / levels.size());
        return mean <= 1 ? Tier.EASY : mean == 2 ? Tier.MEDIUM : Tier.HARD;
    }

    /**
     * The reference time: per level its walk-off fall and {@link #LEVEL_EXTRA_MS}, and a hop between
     * levels. EEE is 9.8 s and EEMMH 17.4 s (§B.1.8).
     */
    public static long refMs(String mix) {
        List<Level> levels = levels(mix);
        long ms = 0;
        for (Level l : levels) {
            ms += l.fallTicks() * 50L + LEVEL_EXTRA_MS;
        }
        return ms + Math.max(0, levels.size() - 1) * HOP_MS;
    }

    /**
     * The shortest time a run can honestly take: 90% of the walk-off falls, since nobody falls faster
     * than gravity (§B.1.7's {@code minSeconds}), whole seconds, rounded down.
     */
    public static int minSeconds(String mix) {
        long ticks = 0;
        for (Level l : levels(mix)) {
            ticks += l.fallTicks();
        }
        return (int) Math.floor(0.9 * ticks * 0.05);
    }
}
