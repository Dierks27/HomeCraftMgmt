package com.dierks.homecraft.games.cpu;

/**
 * How well an NPC player plays (the owner: "Maybe having a difficulty level different for each npc"): one general
 * value per NPC, not per game, so every game (and stage 2's races) reads the same one. Shown as stars next to the name,
 * one to three, so a child needs no words.
 */
public enum CpuLevel {
    EASY(1),
    MEDIUM(2),
    HARD(3);

    /** How many stars it shows. */
    public final int stars;

    CpuLevel(int stars) {
        this.stars = stars;
    }

    /** "★", "★★" or "★★★". */
    public String starText() {
        return "★".repeat(stars);
    }

    /** One level up (hard stays hard). */
    public CpuLevel up() {
        return this == EASY ? MEDIUM : HARD;
    }

    /** One level down (easy stays easy). */
    public CpuLevel down() {
        return this == HARD ? MEDIUM : EASY;
    }

    /** The level with this many stars, clamped to 1-3. */
    public static CpuLevel ofStars(int stars) {
        return stars <= 1 ? EASY : stars == 2 ? MEDIUM : HARD;
    }
}
