package com.dierks.homecraft.hunt;

import com.dierks.homecraft.mini.Loot;

import java.util.List;
import java.util.Locale;

/**
 * The wild hunt's arithmetic, kept free of the server so it can be tested: which way is it, how
 * many hints are due, and how warm is a player.
 */
public final class HuntMath {

    /** How close a player is to the nearest live wild Mini, as a Mini Radar reads it. */
    public enum Band {
        /** No wild Mini is live in this world. */
        NONE("No wild Mini right now"),
        COLD("Cold"),
        WARM("Warm"),
        HOT("Hot"),
        BURNING("Burning!");

        private final String label;

        Band(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Eight compass points, clockwise from north. */
    private static final String[] COMPASS = {
            "north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

    private HuntMath() {
    }

    /**
     * The 8-way compass direction from one point to another. North is −Z and east is +X, as in
     * the game's own F3 screen.
     */
    public static String direction(int fromX, int fromZ, int toX, int toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx == 0 && dz == 0) {
            return "right here";
        }
        double degrees = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, 90 = east
        if (degrees < 0) {
            degrees += 360;
        }
        return COMPASS[(int) Math.round(degrees / 45.0) % 8];
    }

    /**
     * How many hint stages are due at {@code now}: every stage whose {@code at_percent} of the
     * hunt's lifetime has passed. The 0% stage is due the moment the Mini appears.
     */
    public static int stagesDue(List<Loot.Hint> hints, long spawnedAt, long expiresAt, long now) {
        if (hints == null || hints.isEmpty()) {
            return 0;
        }
        long life = Math.max(1, expiresAt - spawnedAt);
        double percent = Math.max(0, now - spawnedAt) * 100.0 / life;
        int due = 0;
        for (Loot.Hint h : hints) {
            if (h.atPercent() <= percent) {
                due++;
            } else {
                break; // sorted by at_percent at load
            }
        }
        return due;
    }

    /**
     * Where a Mini Radar's bands end, in blocks: closer than {@code burningUnder} reads Burning!, closer than
     * {@code hotUnder} Hot, closer than {@code warmUnder} Warm, and anything further Cold. A {@code burningUnder} of 0
     * means no Burning band at all, so Hot is the closest a radar ever reads.
     */
    public record Bands(int burningUnder, int hotUnder, int warmUnder) {
        /** What the bundled file ships: no Burning band, Hot under 32, Warm under 64. */
        public static final Bands SHIPPED = new Bands(0, 32, 64);
    }

    /** A distance in blocks as a radar band, by {@code bands}; a negative distance (no Mini live) is NONE. */
    public static Band band(double distance, Bands bands) {
        if (distance < 0) {
            return Band.NONE;
        }
        if (distance < bands.burningUnder()) {
            return Band.BURNING;
        }
        if (distance < bands.hotUnder()) {
            return Band.HOT;
        }
        if (distance < bands.warmUnder()) {
            return Band.WARM;
        }
        return Band.COLD;
    }

    /**
     * True while a radar that locked on at {@code lockedAt} is still reading at {@code now} (milliseconds): for
     * {@code lastsSeconds} after it locked on, or for good with 0 (then it reads until the Mini is caught or gets away).
     */
    public static boolean charged(long lockedAt, int lastsSeconds, long now) {
        return lastsSeconds <= 0 || now - lockedAt < lastsSeconds * 1000L;
    }

    /**
     * True when a radar whose last reading was at {@code lastReadingAt} is due its next one at {@code now}
     * (milliseconds), {@code intervalSeconds} apart. The radar is checked once a second, so a reading that comes due
     * up to half a second after a check is taken at that check rather than a whole second late.
     */
    public static boolean readingDue(long lastReadingAt, int intervalSeconds, long now) {
        return now - lastReadingAt >= intervalSeconds * 1000L - 500L;
    }

    /** A namespaced biome key ("minecraft:dark_forest") as words: "Dark Forest". */
    public static String prettyBiome(String key) {
        if (key == null || key.isBlank()) {
            return "wild";
        }
        String k = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        StringBuilder out = new StringBuilder();
        for (String word : k.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? "wild" : out.toString();
    }

    /**
     * Fill a hint's placeholders. "a {biome}" becomes "an {biome}" before a vowel, so the shipped
     * "in a {biome} biome" does not tell a child the Mini is "in a Ocean biome".
     */
    public static String fill(String text, String rarity, String player, int minutes, String biome,
                              String direction) {
        String t = text == null ? "" : text;
        if (biome != null && !biome.isEmpty() && "AEIOUaeiou".indexOf(biome.charAt(0)) >= 0) {
            t = t.replace(" a {biome}", " an {biome}").replace("A {biome}", "An {biome}");
        }
        return t.replace("{rarity}", rarity)
                .replace("{player}", player)
                .replace("{minutes}", String.valueOf(minutes))
                .replace("{biome}", biome == null ? "wild" : biome)
                .replace("{direction}", direction);
    }
}
