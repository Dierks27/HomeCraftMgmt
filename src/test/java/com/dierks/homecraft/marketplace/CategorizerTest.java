package com.dierks.homecraft.marketplace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The name-only half of the classifier, which is what decides the department for the bulk
 * of a large catalog. Pure by design: it takes a String, not a Material, so it needs no
 * running server — on Paper 26.2 every Material predicate resolves through the registry
 * and throws without one.
 */
class CategorizerTest {

    @Test
    void specificBeatsGeneral() {
        // A diamond sword is Combat before a diamond is a material; a redstone block is
        // Redstone before "_BLOCK" makes it a Block. Order in byName() is load-bearing.
        assertEquals("Combat", Categorizer.byName("DIAMOND_SWORD"));
        assertEquals("Materials", Categorizer.byName("DIAMOND"));
        assertEquals("Blocks", Categorizer.byName("DIAMOND_BLOCK"));
        assertEquals("Redstone", Categorizer.byName("REDSTONE_BLOCK"));
        assertEquals("Redstone", Categorizer.byName("REDSTONE"));
        assertEquals("Combat", Categorizer.byName("DIAMOND_HELMET"));
        assertEquals("Tools", Categorizer.byName("DIAMOND_PICKAXE"));
    }

    @Test
    void weaponsAndArmorShareOneCombatDepartment() {
        // Eight departments is the ceiling — the tab row is nine slots and "All" takes the
        // first — so gear is one tab. Both families have to reach it.
        for (String gear : java.util.List.of("NETHERITE_SWORD", "BOW", "CROSSBOW", "TRIDENT",
                "ARROW", "SPECTRAL_ARROW", "MACE", "TNT",
                "IRON_CHESTPLATE", "LEATHER_BOOTS", "SHIELD", "ELYTRA", "TURTLE_HELMET",
                "DIAMOND_HORSE_ARMOR", "WOLF_ARMOR")) {
            assertEquals("Combat", Categorizer.byName(gear), gear);
        }
        // A pickaxe ends in AXE but not _AXE, so it stays a Tool — the rule that lets a
        // battle-axe be Combat must not swallow the mining tools with it.
        assertEquals("Combat", Categorizer.byName("IRON_AXE"));
        assertEquals("Tools", Categorizer.byName("IRON_PICKAXE"));
    }

    @Test
    void oresAndIngotsAreMaterialsNotBlocks() {
        assertEquals("Materials", Categorizer.byName("IRON_INGOT"));
        assertEquals("Materials", Categorizer.byName("GOLD_NUGGET"));
        assertEquals("Materials", Categorizer.byName("RAW_COPPER"));
        assertEquals("Materials", Categorizer.byName("NETHERITE_SCRAP"));
        assertEquals("Materials", Categorizer.byName("COAL"));
        // …while the ORE and the storage block stay placeable.
        assertEquals("Blocks", Categorizer.byName("IRON_ORE"));
        assertEquals("Blocks", Categorizer.byName("IRON_BLOCK"));
    }

    @Test
    void mobDropsAreCraftingStock() {
        // Deliberately Materials rather than Misc: these are things a player shops FOR,
        // and Misc is where things go when nothing better fits.
        for (String drop : java.util.List.of("BONE", "STRING", "GUNPOWDER", "LEATHER",
                "ENDER_PEARL", "SLIME_BALL", "BLAZE_ROD", "PHANTOM_MEMBRANE")) {
            assertEquals("Materials", Categorizer.byName(drop), drop);
        }
    }

    @Test
    void plantsAndBuildingBlocksAreBlocks() {
        for (String block : java.util.List.of("OAK_SAPLING", "OAK_LOG", "OAK_PLANKS",
                "STONE_STAIRS", "WHITE_WOOL", "BLUE_CONCRETE", "GLASS", "BAMBOO",
                "SUGAR_CANE", "OAK_LEAVES", "COBBLESTONE_WALL")) {
            assertEquals("Blocks", Categorizer.byName(block), block);
        }
    }

    @Test
    void foodIsFoodWithoutAskingTheRegistry() {
        // isEdible() would answer these, but it needs a server; the name rules mean the
        // common cases never reach that fallback.
        for (String food : java.util.List.of("BREAD", "COOKED_BEEF", "GOLDEN_APPLE",
                "MUSHROOM_STEW", "CARROT", "PUMPKIN_PIE", "COOKIE")) {
            assertEquals("Food", Categorizer.byName(food), food);
        }
    }

    @Test
    void collectiblesAndToolsLandRight() {
        assertEquals("Collectibles", Categorizer.byName("MUSIC_DISC_CAT"));
        assertEquals("Collectibles", Categorizer.byName("PLAYER_HEAD"));
        assertEquals("Collectibles", Categorizer.byName("TOTEM_OF_UNDYING"));
        assertEquals("Tools", Categorizer.byName("WATER_BUCKET"));
        assertEquals("Tools", Categorizer.byName("OAK_BOAT"));
        assertEquals("Tools", Categorizer.byName("SHEARS"));
    }

    @Test
    void anUnknownNameDefersToThePropertyFlags() {
        // null means "the name did not decide" — department() then asks isEdible/isBlock,
        // which is the only part that needs a server.
        assertNull(Categorizer.byName("SOME_FUTURE_ITEM"));
        assertNull(Categorizer.byName(""));
    }
}
