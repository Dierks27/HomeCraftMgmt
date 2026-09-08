package com.dierks.homecraft.mini;

import org.bukkit.DyeColor;

import java.util.EnumMap;
import java.util.Map;

/**
 * The card-economy spec for a Mini type (Phase 9, §3.5): how many Cards of this
 * type can exist ({@code cardCap}, -1 = uncapped), the weighted print-grade odds
 * (STANDARD / GRADED / MINT), and the per-colour filament the Printer consumes to
 * print one. Defaults derive from rarity when a Mini doesn't set its own
 * {@code card:} block in config. The same odds drive wild-drop and crate grades.
 */
public record CardSpec(long cardCap, Map<Grade, Double> gradeWeights, Map<DyeColor, Integer> filament) {

    public boolean uncappedCards() {
        return cardCap < 0;
    }

    /** Rarity-derived defaults so every Mini is printable even without a card: block. */
    public static CardSpec defaultsFor(Rarity rarity) {
        Map<Grade, Double> grades = new EnumMap<>(Grade.class);
        Map<DyeColor, Integer> filament = new EnumMap<>(DyeColor.class);
        switch (rarity) {
            case LEGENDARY -> {
                weights(grades, 55, 33, 12);
                filament.put(DyeColor.ORANGE, 3);
                filament.put(DyeColor.PURPLE, 2);
                filament.put(DyeColor.YELLOW, 1);
            }
            case EPIC -> {
                weights(grades, 62, 30, 8);
                filament.put(DyeColor.PURPLE, 3);
                filament.put(DyeColor.MAGENTA, 2);
            }
            case RARE -> {
                weights(grades, 70, 25, 5);
                filament.put(DyeColor.LIGHT_BLUE, 3);
                filament.put(DyeColor.WHITE, 1);
            }
            case UNCOMMON -> {
                weights(grades, 78, 19, 3);
                filament.put(DyeColor.LIME, 2);
                filament.put(DyeColor.WHITE, 1);
            }
            default -> {
                weights(grades, 85, 13, 2);
                filament.put(DyeColor.WHITE, 2);
            }
        }
        return new CardSpec(-1, grades, filament);
    }

    private static void weights(Map<Grade, Double> m, double standard, double graded, double mint) {
        m.put(Grade.STANDARD, standard);
        m.put(Grade.GRADED, graded);
        m.put(Grade.MINT, mint);
    }
}
