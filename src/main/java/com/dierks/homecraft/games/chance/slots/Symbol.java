package com.dierks.homecraft.games.chance.slots;

/**
 * What can land on an Ore Slots reel (spec §5.3).
 *
 * <p>The order is the draw order: a reel's {@code nextInt(total)} walks the weights in this order,
 * so it is part of what a seed means and never changes. Stone is last because its weight is the
 * one the engine solves; it never pays on its own, but it still counts as the odd one out next to
 * a pair.
 */
public enum Symbol {

    COAL("coal", "Coal"),
    COPPER("copper", "Copper"),
    IRON("iron", "Iron"),
    GOLD("gold", "Gold"),
    DIAMOND("diamond", "Diamond"),
    /** Stands in for any ore. */
    WILD("wild", "Wild"),
    /** Never pays; its weight is solved so the game gives back what {@code rtp} says. */
    STONE("stone", "Stone");

    private final String key;
    private final String label;

    Symbol(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Its key under {@code games.ore_slots.reels} (Stone has none: it is solved). */
    public String key() {
        return key;
    }

    /** Its player-facing name ("Diamond"). */
    public String label() {
        return label;
    }

    /** Whether it is one of the five ores (not Wild, not Stone). */
    public boolean ore() {
        return this != WILD && this != STONE;
    }
}
