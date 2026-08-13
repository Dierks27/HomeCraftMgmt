package com.dierks.homecraft.mini;

import java.util.ArrayList;
import java.util.List;

/**
 * Card Pack model (Phase 10, §3.5). A {@link PackDef} is a buyable booster: a price,
 * a card count, and a weighted pool of Card references (by Mini id). Opening a pack
 * charges money and issues N weighted-random <b>Cards</b> (never Minis — a Card is
 * printed into a Mini later at the Printer). Pack types are admin-authored via the
 * GUI pack-builder and persisted to config.
 */
public final class Pack {

    private Pack() {
    }

    /** One weighted Card reference within a pack pool (by the Mini id its Card prints). */
    public record PackEntry(String miniId, double weight) {
    }

    /** A buyable pack type: id, display name, price, cards-per-open, and its weighted pool. */
    public record PackDef(String id, String displayName, double price, int cardCount, List<PackEntry> pool) {

        /** A pack is openable only when it has at least one pooled card and a positive count. */
        public boolean isValid() {
            return pool != null && !pool.isEmpty() && cardCount > 0;
        }

        /** Total pool weight (for showing per-card odds in the builder / tooltip). */
        public double totalWeight() {
            double t = 0;
            if (pool != null) {
                for (PackEntry e : pool) {
                    t += Math.max(0, e.weight());
                }
            }
            return t;
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
