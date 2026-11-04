package com.dierks.homecraft.arcade.candy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * What one shower drops and how its items are known (0.38.6): the shower engine asks here instead of naming
 * candy, so one engine runs the Candy Apple shower and the Harvest Basket. Two implementations, because
 * {@link CandyItems} and {@link HarvestItems} are static utilities: {@link CandyShowerItems} and
 * {@link HarvestShowerItems}.
 */
public interface ShowerItems {

    /** One item of the year's, amount 1: golden or not, the {@code index}-th of its shower. */
    ItemStack item(int year, boolean golden, int index);

    /** Whether the item is one of this shower's, by its own key (never by an id prefix). */
    boolean isItem(ItemStack item);

    /** Whether the item is this shower's golden one. */
    boolean isGolden(ItemStack item);

    /** The year the item is from, or 0 when it isn't one of this shower's. */
    int year(ItemStack item);

    /**
     * The materials a tap is looked at for: every right-click on the server reaches the tap handler, so the
     * held item's type is checked against these before anything is read off it.
     */
    Set<Material> tapMaterials();

    /** Whether a full Bedrock player eats this item with a tap ({@link CandyRules#eatsOnTap}'s {@code edible}). */
    boolean edibleOnTap(ItemStack item);

    /** Whether the item is this shower's toy that plays on a tap (the Trick-or-Treat Bag); false for none. */
    boolean isToy(ItemStack item);
}
