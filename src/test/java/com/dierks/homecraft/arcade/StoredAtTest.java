package com.dierks.homecraft.arcade;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A placed prize of any head type gives its prize back (SHOP-V2-SPEC §3.7): {@link PlacedPrizes} reads every head
 * material, floor and wall, so a placed creeper-head prize no longer breaks into a plain, sellable creeper head (the
 * latent bug).
 */
class StoredAtTest {

    @Test
    void everyHeadMaterialIsAPlacedPrizesPlace() {
        for (Material m : new Material[] {Material.PLAYER_HEAD, Material.PLAYER_WALL_HEAD, Material.CREEPER_HEAD,
                Material.CREEPER_WALL_HEAD, Material.DRAGON_HEAD, Material.DRAGON_WALL_HEAD, Material.ZOMBIE_HEAD,
                Material.ZOMBIE_WALL_HEAD, Material.SKELETON_SKULL, Material.SKELETON_WALL_SKULL,
                Material.WITHER_SKELETON_SKULL, Material.WITHER_SKELETON_WALL_SKULL, Material.PIGLIN_HEAD,
                Material.PIGLIN_WALL_HEAD}) {
            assertTrue(PlacedPrizes.headMaterial(m), m.name());
        }
    }

    @Test
    void nothingElseIs() {
        assertFalse(PlacedPrizes.headMaterial(Material.PISTON_HEAD), "a piston head is no skull");
        assertFalse(PlacedPrizes.headMaterial(Material.STONE));
        assertFalse(PlacedPrizes.headMaterial(Material.LEATHER_HELMET));
        assertFalse(PlacedPrizes.headMaterial(null));
    }
}
