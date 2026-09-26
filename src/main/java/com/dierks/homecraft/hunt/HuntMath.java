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

    /** A distance in blocks as a radar band: Burning! under 12, Hot under 32, Warm under 64. */
    public static Band band(double distance) {
        if (distance < 0) {
            return Band.NONE;
        }
        if (distance < 12) {
            return Band.BURNING;
        }
        if (distance < 32) {
            return Band.HOT;
        }
        if (distance < 64) {
            return Band.WARM;
        }
        return Band.COLD;
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
