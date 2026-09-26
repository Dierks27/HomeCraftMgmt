package com.dierks.homecraft.mini;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * The arithmetic behind a rarity-odds pack, kept free of Bukkit so it can be tested.
 *
 * <p>A pack says how likely each rarity is ("Common 62, Uncommon 28, …"). When no Mini of a
 * rarity can be issued any more — every one of them sold out — that rarity drops out of the draw
 * and the rest are scaled back up to 100%, so the pack never rolls a rarity it cannot give.
 */
public final class PackOdds {

    private PackOdds() {
    }

    /**
     * The odds a pack actually rolls with: only rarities that are {@code available} and have a
     * positive weight, scaled to add up to 1. Empty when nothing can be given — the pack is sold
     * out.
     */
    public static Map<Rarity, Double> effective(Map<Rarity, Double> odds, Collection<Rarity> available) {
        Map<Rarity, Double> out = new EnumMap<>(Rarity.class);
        if (odds == null) {
            return out;
        }
        double total = 0;
        for (Rarity r : Rarity.values()) {
            Double w = odds.get(r);
            if (w != null && w > 0 && available.contains(r)) {
                total += w;
            }
        }
        if (total <= 0) {
            return out;
        }
        for (Rarity r : Rarity.values()) {
            Double w = odds.get(r);
            if (w != null && w > 0 && available.contains(r)) {
                out.put(r, w / total);
            }
        }
        return out;
    }

    /**
     * Pick a rarity from {@link #effective} odds with a uniform {@code roll} in [0, 1).
     *
     * @return null only when {@code effective} is empty
     */
    public static Rarity pick(Map<Rarity, Double> effective, double roll) {
        Rarity last = null;
        double r = roll;
        for (Rarity rarity : Rarity.values()) {
            Double p = effective.get(rarity);
            if (p == null) {
                continue;
            }
            last = rarity;
            r -= p;
            if (r < 0) {
                return rarity;
            }
        }
        return last; // rounding at the top end
    }

    /**
     * What to hand back for a pack that could fill only {@code awarded} of its {@code count}
     * slots: the same share of what was paid. Money keeps its cents.
     */
    public static double refundMoney(double paid, int count, int awarded) {
        if (paid <= 0 || count <= 0 || awarded >= count) {
            return 0;
        }
        double share = paid * (count - Math.max(0, awarded)) / count;
        return Math.round(share * 100.0) / 100.0;
    }

    /** {@link #refundMoney} for tokens, rounded to the nearest whole token. */
    public static int refundTokens(int paid, int count, int awarded) {
        if (paid <= 0 || count <= 0 || awarded >= count) {
            return 0;
        }
        return (int) Math.round((double) paid * (count - Math.max(0, awarded)) / count);
    }
}
