package com.dierks.homecraft.mini;

import org.bukkit.DyeColor;

import java.util.EnumMap;
import java.util.Map;

/**
 * The card-economy spec for a Mini type (Phase 9, §3.5): how many Cards of this
 * type can exist ({@code cardCap}, -1 = uncapped), the weighted print-grade odds,
 * and the per-colour filament the Printer consumes to print one. Defaults derive
 * from rarity when a Mini doesn't set its own {@code card:} block in config.
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
                weights(grades, 5, 15, 30, 30, 20);
                filament.put(DyeColor.ORANGE, 3);
                filament.put(DyeColor.PURPLE, 2);
                filament.put(DyeColor.YELLOW, 1);
            }
            case EPIC -> {
                weights(grades, 15, 25, 30, 20, 10);
                filament.put(DyeColor.PURPLE, 3);
                filament.put(DyeColor.MAGENTA, 2);
            }
            case RARE -> {
                weights(grades, 30, 30, 22, 13, 5);
                filament.put(DyeColor.LIGHT_BLUE, 3);
                filament.put(DyeColor.WHITE, 1);
            }
            case UNCOMMON -> {
                weights(grades, 45, 30, 15, 8, 2);
                filament.put(DyeColor.LIME, 2);
                filament.put(DyeColor.WHITE, 1);
            }
            default -> {
                weights(grades, 60, 25, 10, 4, 1);
                filament.put(DyeColor.WHITE, 2);
            }
        }
        return new CardSpec(-1, grades, filament);
    }

    private static void weights(Map<Grade, Double> m, double gray, double green, double blue, double purple, double gold) {
        m.put(Grade.GRAY, gray);
        m.put(Grade.GREEN, green);
        m.put(Grade.BLUE, blue);
        m.put(Grade.PURPLE, purple);
        m.put(Grade.GOLD, gold);
    }
}
