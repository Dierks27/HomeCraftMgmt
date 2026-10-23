package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.looks.GuardRules;
import com.dierks.homecraft.arcade.looks.GuardRules.Holder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard listener's decisions (SHOP-V2-SPEC §3.7) on the facts it reads: a bound key never lands or is placed, a
 * key left in a chest is deleted on close and logged, an admin's look at someone's things deletes nothing, and a
 * preview stand's copy goes on sight. Run on {@link PrizeService#bound}'s own two values, so "bound" here is the same
 * test the listener makes.
 */
class BoundItemGuardTest {

    private static final String HER = "0b0a0c0d-1e1f-4a2b-8c3d-4e5f6a7b8c9d";
    private static final String NOBODY = "00000000-0000-0000-0000-000000000000";

    /** A stand-in item: its binding and its prize id, as the data view gives them. */
    private record Item(String boundTo, String id) {
        boolean bound() {
            return PrizeService.bound(boundTo, id);
        }
    }

    @Test
    void aBoundKeyNeverLandsACollectorDoes() {
        assertTrue(GuardRules.spawnRefused(new Item(HER, "cap_pink").bound(), false), "a rented cap as an entity");
        assertTrue(GuardRules.spawnRefused(new Item(null, "keeper_wizard_hat").bound(), false), "a Keeper");
        assertFalse(GuardRules.spawnRefused(new Item(null, "cap_santa_2026").bound(), false),
                "a collector cap drops like a prize: prize safety keeps it, owner-bound");
        assertFalse(GuardRules.spawnRefused(new Item(null, "hat_bunny").bound(), false), "a hat from before 0.38");
    }

    @Test
    void aPreviewCopyIsDeletedOnSight() {
        Item copy = new Item(NOBODY, "cap_lime");
        assertTrue(GuardRules.spawnRefused(copy.bound(), true), "look_preview: it never lands");
        assertTrue(copy.bound(), "and it is bound, to nobody, so a sweep removes it as not yours");
    }

    @Test
    void aKeyIsNeverPlaced() {
        assertTrue(GuardRules.placeRefused(new Item(HER, "hat_creeper").bound()));
        assertFalse(GuardRules.placeRefused(new Item(null, "arcade_trophy").bound()), "a trophy is placed");
        assertFalse(GuardRules.placeRefused(new Item(null, "hat_bunny").bound()), "a grandfathered hat still is (D34)");
    }

    @Test
    void aKeyLeftInAClosedChestIsDeletedAndLogged() {
        assertTrue(GuardRules.closeSweeps(Holder.BLOCK, false, false, false), "a chest, a barrel, a hopper");
        assertTrue(GuardRules.closeSweeps(Holder.ENTITY, false, false, false), "a minecart, a chest boat, a llama");
        List<Item> chest = Arrays.asList(new Item(null, "speed_boost"), null, new Item(HER, "cap_pink"),
                new Item(null, "cap_santa_2026"));
        assertEquals(List.of(2), GuardRules.sweptSlots(chest, Item::bound), "only the key");
        String warn = GuardRules.sweptWarning("CHEST", "cap_pink", "Owner");
        assertTrue(warn.contains("cap_pink") && warn.contains("CHEST"), warn);
    }

    @Test
    void anAdminsLookAtHerThingsDeletesNothing() {
        assertFalse(GuardRules.closeSweeps(Holder.PLAYER, false, true, false), "/invsee: her PLAYER inventory");
        assertFalse(GuardRules.closeSweeps(Holder.OTHER, true, false, false), "/ec: her ender chest");
        assertFalse(GuardRules.closeSweeps(Holder.BLOCK, true, false, false), "an ender chest block too");
        assertFalse(GuardRules.closeSweeps(Holder.PLAYER, false, false, false), "a Player holder, whatever the type");
        assertFalse(GuardRules.closeSweeps(Holder.OTHER, false, false, true), "the crafting grid");
        assertFalse(GuardRules.closeSweeps(Holder.MENU, false, false, false), "our menus");
    }

    @Test
    void framesStandsAlliesPotsAndShelvesRefuseAKey() {
        boolean key = new Item(HER, "cap_pink").bound();
        assertTrue(GuardRules.entityRefused(GuardRules.EntityKind.ITEM_FRAME, key));
        assertTrue(GuardRules.entityRefused(GuardRules.EntityKind.ARMOR_STAND, key));
        assertTrue(GuardRules.entityRefused(GuardRules.EntityKind.ALLAY, key));
        assertTrue(GuardRules.blockUseRefused("DECORATED_POT", key, false));
        assertTrue(GuardRules.blockUseRefused("SPRUCE_SHELF", false, key));
        assertFalse(GuardRules.entityRefused(GuardRules.EntityKind.ITEM_FRAME, new Item(null, "speed_boost").bound()),
                "a boost in a frame is anyone's business");
        assertTrue(GuardRules.smeltRefused(true), "no furnace smelts a prize (the Knight's Helm is iron)");
    }
}
