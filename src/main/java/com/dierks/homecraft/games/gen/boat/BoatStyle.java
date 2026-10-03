package com.dierks.homecraft.games.gen.boat;

import java.util.Locale;

/**
 * The two kinds of Mountain Run v2 (MOUNTAIN-V2-SPEC §5.1): the <b>Winding Road</b>, a 7-9 wide racing
 * road for Race Night and party races, and the <b>Slalom</b>, an 11-15 wide run through red and blue
 * gate fences for solo time trials.
 *
 * <p><b>A pure function of the seed.</b> The style is never stored and never read from config by the
 * planner: {@link #of} reads it off the seed alone, so a layout still comes only from (algo, seed, day,
 * reroll, tier, half) (rule R6), and a rederive, a pin, an admin's pick, the Weekly Cup's plan hash and
 * the archive need nothing new. The engine (package C) picks <i>which</i> seed a week uses when the
 * admin forces a style or Race Night favours the Road; the runtime reads the style back the same way
 * from {@code course.gen().seed()}.
 *
 * <p>The bit comes from SplitMix64's output function over {@code seed ^ }{@value #SALT_HEX}, so it is
 * about 50/50 and independent of every stream the planner forks from the same seed. Pinned by
 * {@code BoatStyleTest}: changing it changes which style every past seed names (an ALGO bump).
 */
public enum BoatStyle {

    /** The Winding Road: a fast, flowing race line down the mountain. */
    ROAD("road", "Winding Road"),
    /** The Slalom: back and forth through the gates. */
    SLALOM("slalom", "Slalom");

    /** XORed into the seed before the bit is taken, so the style is independent of the planner's own streams. */
    public static final long SALT = 0x5EED57E1E00DL;
    /** {@link #SALT} as text, for the javadoc. */
    static final String SALT_HEX = "0x5EED57E1E00D";

    private final String id;
    private final String title;

    BoatStyle(String id, String title) {
        this.id = id;
        this.title = title;
    }

    /** The config word: {@code road} or {@code slalom}. */
    public String id() {
        return id;
    }

    /** What players read: "Winding Road" or "Slalom". */
    public String title() {
        return title;
    }

    /** The style a seed makes: the low bit of SplitMix64's finaliser over {@code seed ^ SALT} (0 = Road). */
    public static BoatStyle of(long seed) {
        return (mix(seed ^ SALT) & 1L) == 0 ? ROAD : SLALOM;
    }

    /**
     * The style called {@code word} (any case, trimmed), or {@code null} for anything else, {@code random}
     * included: the engine reads {@code games.fresh.slots.fresh_boat.style} with it and treats {@code null}
     * as random.
     */
    public static BoatStyle byWord(String word) {
        if (word == null) {
            return null;
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        for (BoatStyle s : values()) {
            if (s.id.equals(w)) {
                return s;
            }
        }
        return null;
    }

    /** SplitMix64's output function (the same as {@code GenRandom}'s, written out so this enum stands alone). */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
