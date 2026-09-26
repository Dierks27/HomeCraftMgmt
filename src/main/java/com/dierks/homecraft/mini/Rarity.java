package com.dierks.homecraft.mini;

import java.util.Locale;

/** Mini rarity tiers (drive the visual style + smart defaults — see {@link RarityStyle}). */
public enum Rarity {
    COMMON,
    UNCOMMON,
    RARE,
    EPIC,
    LEGENDARY;

    /**
     * The name a player reads: "Rare", never "RARE". An enum constant on a menu is the plugin
     * talking to itself — and on a server where the youngest player is only starting to read,
     * shouting in capitals is also just harder to read.
     */
    public String display() {
        String n = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** "a" or "an", for a sentence that starts with this rarity ("an Epic Card"). */
    public String article() {
        return this == UNCOMMON || this == EPIC ? "an" : "a";
    }
}
