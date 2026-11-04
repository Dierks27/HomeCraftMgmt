package com.dierks.homecraft.arcade.candy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The Harvest Basket's items for the shower engine: {@link HarvestItems}' statics. A tap eats any treat, the
 * Golden Pie included (a pie is not always edible on Bedrock, unlike a golden apple). There is no toy.
 */
public final class HarvestShowerItems implements ShowerItems {

    /** Every material a treat can be. */
    static final Set<Material> TAP = Collections.unmodifiableSet(EnumSet.copyOf(HarvestItems.MATERIALS));

    /** The eat-on-tap rule, as plain facts: any treat, golden or not. */
    static boolean edible(boolean treat, boolean golden) {
        return treat;
    }

    @Override
    public ItemStack item(int year, boolean golden, int index) {
        return HarvestItems.treat(year, golden, index);
    }

    @Override
    public boolean isItem(ItemStack item) {
        return HarvestItems.isHarvest(item);
    }

    @Override
    public boolean isGolden(ItemStack item) {
        return HarvestItems.isGolden(item);
    }

    @Override
    public int year(ItemStack item) {
        return HarvestItems.year(item);
    }

    @Override
    public Set<Material> tapMaterials() {
        return TAP;
    }

    @Override
    public boolean edibleOnTap(ItemStack item) {
        return edible(HarvestItems.isHarvest(item), false);
    }

    @Override
    public boolean isToy(ItemStack item) {
        return false;
    }
}
