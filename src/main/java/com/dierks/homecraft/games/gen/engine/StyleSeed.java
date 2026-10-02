package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.cabinet.CabinetGame;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.Edition;
import com.dierks.homecraft.games.gen.api.GenSeed;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatPlanner;
import com.dierks.homecraft.games.gen.boat.BoatStyle;

/**
 * Which seed a Mountain Run v2 week uses (MOUNTAIN-V2-SPEC §5.1, with red-team F05 replacing its Race Night
 * row): the engine's side of {@code games.fresh.slots.fresh_boat.style}. Pure: no Bukkit, no clock.
 *
 * <p><b>Why the engine picks a seed and the planner never reads the style.</b> The style is a function of
 * the seed ({@link BoatStyle#of}), so a layout still comes only from (algo, seed, day, reroll, tier, half)
 * and every pin, pick, rederive, archive row and Weekly Cup hash keeps naming one layout. A week that must
 * be a Winding Road (or a Slalom) takes the first of {@value #TRIES} seeds of its edition that has that
 * style: seed<sub>0</sub> is the edition's own seed (exactly {@link GenSeed#seed}), seed<sub>k</sub> for k ≥ 1
 * the same HMAC over the label with {@code "~k"} appended. With none of the {@value #TRIES} (a 2<sup>-16</sup>
 * event) it is seed<sub>0</sub>.
 *
 * <p><b>The rule</b> ({@link #want}):
 * <ul>
 *   <li>{@code style: road} or {@code slalom}: that style every week;</li>
 *   <li>{@code style: random} with Race Night off: the week's own seed, unchanged (about half and half);</li>
 *   <li>{@code style: random} with Race Night on: the Winding Road every week (the owner: racing is the
 *       fast winding road, the Slalom is back and forth for solo runs), so the Slalom comes only in weeks
 *       with no night.</li>
 * </ul>
 * An admin's explicit seed ({@code pin}, {@code choose}, {@code preview <seed>}) is used as given. Only the
 * Ice Boat slot in a Mountain Run v2 half ({@link BoatPlanner#mountain}) is ever steered: every other slot,
 * and an Ice Boat still in its 0.36 box (the algo-3 spiral, which has no style), keeps its own seed exactly.
 */
public final class StyleSeed {

    /** How many seeds of an edition are looked at for the wanted style (k = 0 .. 15). */
    public static final int TRIES = 16;

    private StyleSeed() {
    }

    /**
     * The style a new layout of slot {@code def} in {@code half} must have, or {@code null} for the edition's
     * own seed: {@code config} ({@code null} = random) for the Ice Boat in a Mountain Run v2 half, the Winding
     * Road when that is random and Race Night is on, else {@code null}.
     */
    public static BoatStyle want(Slots.Def def, Box half, BoatStyle config, boolean raceNight) {
        if (def == null || half == null || !Slots.BOAT.equals(def.generator()) || !BoatPlanner.mountain(half)) {
            return null;
        }
        if (config != null) {
            return config;
        }
        return raceNight ? BoatStyle.ROAD : null;
    }

    /**
     * The label of seed<sub>k</sub>: {@link GenSeed#label(String, int, int)}, with {@code "~k"} after it for
     * k ≥ 1 (so k = 0 is today's label, unchanged).
     */
    public static String label(String slot, int cadence, int reroll, int k) {
        String base = GenSeed.label(slot, cadence, reroll);
        return k <= 0 ? base : base + "~" + k;
    }

    /** Seed<sub>k</sub> of {@code slot}'s edition: k = 0 is {@link GenSeed#seed(long, int, long, String, int)}. */
    public static long seed(long secret, int cadence, long startDay, String slot, int reroll, int k) {
        int n = Edition.clampCadence(cadence);
        return CabinetGame.seed(secret, Edition.index(n, startDay), label(slot, n, reroll, k));
    }

    /**
     * The seed {@code slot}'s edition uses: its own ({@link GenSeed#seed}) when {@code want} is {@code null},
     * else the first seed<sub>k</sub> (k = 0 .. {@value #TRIES} - 1) whose style is {@code want}, or its own
     * when none is.
     */
    public static long seed(long secret, int cadence, long startDay, String slot, int reroll, BoatStyle want) {
        long own = seed(secret, cadence, startDay, slot, reroll, 0);
        if (want == null) {
            return own;
        }
        for (int k = 0; k < TRIES; k++) {
            long s = k == 0 ? own : seed(secret, cadence, startDay, slot, reroll, k);
            if (BoatStyle.of(s) == want) {
                return s;
            }
        }
        return own;
    }

    /**
     * A random candidate seed ({@code preview <course> next} with no seed) of style {@code want}: the first of
     * {@code draws} (a source of random longs) that has it, at most 64 draws, else the first draw; any draw
     * for {@code null}.
     */
    public static long random(java.util.function.LongSupplier draws, BoatStyle want) {
        long first = draws.getAsLong();
        if (want == null || BoatStyle.of(first) == want) {
            return first;
        }
        for (int i = 1; i < 64; i++) {
            long s = draws.getAsLong();
            if (BoatStyle.of(s) == want) {
                return s;
            }
        }
        return first;
    }

    /** "the Winding Road" / "the Slalom" / "either style", for the admin's lines. */
    public static String words(BoatStyle s) {
        return s == null ? "either style" : "the " + s.title();
    }
}
