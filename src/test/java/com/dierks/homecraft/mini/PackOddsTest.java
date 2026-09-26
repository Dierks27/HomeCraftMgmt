package com.dierks.homecraft.mini;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pack rolls only rarities it can give, and a short pack pays back its share. */
class PackOddsTest {

    private static Map<Rarity, Double> starter() {
        Map<Rarity, Double> m = new EnumMap<>(Rarity.class);
        m.put(Rarity.COMMON, 62.0);
        m.put(Rarity.UNCOMMON, 28.0);
        m.put(Rarity.RARE, 9.0);
        m.put(Rarity.EPIC, 1.0);
        m.put(Rarity.LEGENDARY, 0.0);
        return m;
    }

    @Test
    void everythingAvailableKeepsTheWrittenOdds() {
        Map<Rarity, Double> e = PackOdds.effective(starter(), EnumSet.allOf(Rarity.class));
        assertEquals(0.62, e.get(Rarity.COMMON), 1e-9);
        assertEquals(0.01, e.get(Rarity.EPIC), 1e-9);
        assertFalse(e.containsKey(Rarity.LEGENDARY), "a zero weight is never rolled");
    }

    @Test
    void aSoldOutRarityDropsOutAndTheRestScaleUp() {
        Set<Rarity> left = EnumSet.of(Rarity.COMMON, Rarity.UNCOMMON, Rarity.EPIC); // every Rare is gone
        Map<Rarity, Double> e = PackOdds.effective(starter(), left);
        assertFalse(e.containsKey(Rarity.RARE));
        assertEquals(62.0 / 91.0, e.get(Rarity.COMMON), 1e-9);
        assertEquals(1.0, e.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    @Test
    void nothingAvailableIsSoldOut() {
        assertTrue(PackOdds.effective(starter(), EnumSet.of(Rarity.LEGENDARY)).isEmpty(),
                "Legendary is weight 0 in this pack, so nothing can come out");
        assertNull(PackOdds.pick(Map.of(), 0.5));
    }

    @Test
    void pickWalksTheOddsInOrder() {
        Map<Rarity, Double> e = PackOdds.effective(starter(), EnumSet.allOf(Rarity.class));
        assertEquals(Rarity.COMMON, PackOdds.pick(e, 0.0));
        assertEquals(Rarity.COMMON, PackOdds.pick(e, 0.6199));
        assertEquals(Rarity.UNCOMMON, PackOdds.pick(e, 0.62));
        assertEquals(Rarity.RARE, PackOdds.pick(e, 0.95));
        assertEquals(Rarity.EPIC, PackOdds.pick(e, 0.999999));
    }

    @Test
    void aShortPackRefundsItsShare() {
        assertEquals(100.0, PackOdds.refundMoney(300, 3, 2), 1e-9);
        assertEquals(0.0, PackOdds.refundMoney(300, 3, 3), 1e-9);
        assertEquals(66.67, PackOdds.refundMoney(100, 3, 1), 1e-9);
        assertEquals(17, PackOdds.refundTokens(50, 3, 2));
        assertEquals(0, PackOdds.refundTokens(0, 3, 0), "a free pack refunds nothing");
    }
}
