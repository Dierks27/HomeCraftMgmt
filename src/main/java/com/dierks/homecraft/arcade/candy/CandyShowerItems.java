package com.dierks.homecraft.arcade.candy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The Candy Apple shower's items for the shower engine: {@link CandyItems}' statics, unchanged. A tap eats a red
 * Candy Apple only (a golden apple is always edible), and the Trick-or-Treat Bag is the toy.
 */
public final class CandyShowerItems implements ShowerItems {

    /** The apple, and every bundle (the bag is an orange one): what 0.38.5's tap handler looked at. */
    static final Set<Material> TAP = taps();

    private static Set<Material> taps() {
        EnumSet<Material> out = EnumSet.of(Material.APPLE);
        for (Material m : Material.values()) {
            if (m.name().endsWith("BUNDLE")) {
                out.add(m);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** The eat-on-tap rule, as plain facts: a candy that isn't golden. */
    static boolean edible(boolean candy, boolean golden) {
        return candy && !golden;
    }

    @Override
    public ItemStack item(int year, boolean golden, int index) {
        return CandyItems.candy(year, golden);
    }

    @Override
    public boolean isItem(ItemStack item) {
        return CandyItems.isCandy(item);
    }

    @Override
    public boolean isGolden(ItemStack item) {
        return CandyItems.isGolden(item);
    }

    @Override
    public int year(ItemStack item) {
        return CandyItems.candyYear(item);
    }

    @Override
    public Set<Material> tapMaterials() {
        return TAP;
    }

    @Override
    public boolean edibleOnTap(ItemStack item) {
        return edible(CandyItems.isCandy(item), CandyItems.isGolden(item));
    }

    @Override
    public boolean isToy(ItemStack item) {
        return CandyItems.isBag(item);
    }
}
