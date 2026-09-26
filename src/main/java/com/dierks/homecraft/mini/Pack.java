package com.dierks.homecraft.mini;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Card Pack model (§3.5). A {@link PackDef} is a buyable booster: a price in dollars and/or
 * tokens, how many Cards it holds, and what can come out of it. Opening a pack issues Cards
 * (never Minis — a Card is printed into a Mini later at the Printer).
 *
 * <p>What comes out is decided one of two ways:
 * <ul>
 *   <li><b>Rarity odds</b> (the default): roll a rarity by {@code rarityOdds}, then any Mini of
 *       that rarity that still has Cards left, optionally only Minis carrying {@code tag}. A new
 *       Mini joins every such pack the moment it is added to the catalog.</li>
 *   <li><b>An explicit pool</b>: a weighted list of Mini ids an admin picked by hand. Kept for
 *       curated packs; when a pool is set it wins over the odds.</li>
 * </ul>
 */
public final class Pack {

    private Pack() {
    }

    /** One weighted Card reference within a pack pool (by the Mini id its Card prints). */
    public record PackEntry(String miniId, double weight) {
    }

    /**
     * A buyable pack type.
     *
     * @param price       dollars (0 = not sold for dollars)
     * @param priceTokens tokens (0 = not sold for tokens)
     * @param tag         only Minis with this tag, or blank for every Mini (odds mode only)
     * @param rarityOdds  relative weight per rarity (odds mode)
     * @param pool        an explicit weighted list; non-empty means pool mode
     */
    public record PackDef(String id, String displayName, double price, int priceTokens, int cardCount,
                          String tag, Map<Rarity, Double> rarityOdds, List<PackEntry> pool) {

        public PackDef {
            tag = tag == null ? "" : tag.trim();
            Map<Rarity, Double> odds = new EnumMap<>(Rarity.class);
            if (rarityOdds != null) {
                odds.putAll(rarityOdds);
            }
            rarityOdds = Collections.unmodifiableMap(odds);
            pool = pool == null ? List.of() : List.copyOf(pool);
        }

        /** A hand-picked list decides what comes out. */
        public boolean usesPool() {
            return !pool.isEmpty();
        }

        /** Rarity odds decide what comes out. */
        public boolean usesOdds() {
            if (usesPool()) {
                return false;
            }
            for (Double w : rarityOdds.values()) {
                if (w != null && w > 0) {
                    return true;
                }
            }
            return false;
        }

        /** A pack is openable only when something can be rolled and it holds at least one Card. */
        public boolean isValid() {
            return cardCount > 0 && (usesPool() || usesOdds());
        }

        public boolean hasTag() {
            return !tag.isEmpty();
        }

        /** Total pool weight (for showing per-card odds in the builder). */
        public double totalWeight() {
            double t = 0;
            for (PackEntry e : pool) {
                t += Math.max(0, e.weight());
            }
            return t;
        }

        public double odds(Rarity r) {
            Double w = rarityOdds.get(r);
            return w == null ? 0 : Math.max(0, w);
        }

        public PackDef withDisplayName(String v) {
            return new PackDef(id, v, price, priceTokens, cardCount, tag, rarityOdds, pool);
        }

        public PackDef withPrice(double v) {
            return new PackDef(id, displayName, v, priceTokens, cardCount, tag, rarityOdds, pool);
        }

        public PackDef withPriceTokens(int v) {
            return new PackDef(id, displayName, price, v, cardCount, tag, rarityOdds, pool);
        }

        public PackDef withCardCount(int v) {
            return new PackDef(id, displayName, price, priceTokens, v, tag, rarityOdds, pool);
        }

        public PackDef withTag(String v) {
            return new PackDef(id, displayName, price, priceTokens, cardCount, v, rarityOdds, pool);
        }

        public PackDef withOdds(Rarity r, double weight) {
            Map<Rarity, Double> odds = new EnumMap<>(Rarity.class);
            odds.putAll(rarityOdds);
            odds.put(r, Math.max(0, weight));
            return new PackDef(id, displayName, price, priceTokens, cardCount, tag, odds, pool);
        }

        public PackDef withPool(List<PackEntry> v) {
            return new PackDef(id, displayName, price, priceTokens, cardCount, tag, rarityOdds, v);
        }
    }

    /** The full pack config: the ordered list of pack types. */
    public record Packs(List<PackDef> packs) {

        public PackDef byId(String id) {
            if (id == null) {
                return null;
            }
            for (PackDef p : packs) {
                if (p.id().equalsIgnoreCase(id)) {
                    return p;
                }
            }
            return null;
        }

        public List<PackDef> all() {
            return new ArrayList<>(packs);
        }
    }
}
