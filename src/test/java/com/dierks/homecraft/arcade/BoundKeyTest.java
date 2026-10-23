package com.dierks.homecraft.arcade;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one bound-key test (SHOP-V2-SPEC §3.6, D29) that the guard, the trade window and presents call: an item is
 * bound when it carries {@code hcm:bound_to} (a rented hat or cap) or its prize id is a Keeper's; a collector cap, a
 * plain prize and a hat sold before 0.38 never are. Run on the two values the item check reads.
 */
class BoundKeyTest {

    private static final String OWNER = "6f1d2c3b-4a5e-4f60-9a7b-8c9d0e1f2a3b";

    @Test
    void aRentedKeyIsBoundByItsTag() {
        assertTrue(PrizeService.bound(OWNER, "cap_pink"), "hcm:bound_to on a rented cap");
        assertTrue(PrizeService.bound(OWNER, "hat_creeper"));
        assertTrue(PrizeService.bound(OWNER, null), "the tag alone is enough");
        assertTrue(PrizeService.bound("not-a-uuid", "cap_pink"), "any tag binds: a broken one is never loose");
    }

    @Test
    void aKeeperIsBoundByItsId() {
        assertTrue(PrizeService.bound(null, "keeper_legend_crown"), "a Keeper, with no tag at all");
        assertTrue(PrizeService.bound(null, "KEEPER_Wizard_Hat"), "case-insensitive, as the catalogue is");
        assertTrue(PrizeService.bound(OWNER, "keeper_knight_helm"));
        assertTrue(Keepers.reserved("keeper_anything"), "every keeper_ id, shipped or not");
    }

    @Test
    void aCollectorCapAPlainPrizeAndAnOldHatAreNot() {
        assertFalse(PrizeService.bound(null, "cap_santa_2026"), "a collector cap is physical and tradeable");
        assertFalse(PrizeService.bound(null, "cap_pumpkin_2026"));
        assertFalse(PrizeService.bound(null, "speed_boost"), "a plain prize");
        assertFalse(PrizeService.bound(null, "arcade_trophy"), "a trophy");
        assertFalse(PrizeService.bound(null, "hat_bunny"), "a hat sold before 0.38 has no tag: grandfathered (D34)");
        assertFalse(PrizeService.bound("", "hat_bunny"), "an empty tag is none");
        assertFalse(PrizeService.bound("  ", null));
        assertFalse(PrizeService.bound(null, null), "not a prize at all");
        assertFalse(PrizeService.bound(null, "my_keeper_hat"), "only the prefix counts");
    }

    @Test
    void boundToReadsTheOwner() {
        assertEquals(UUID.fromString(OWNER), PrizeService.boundTo(OWNER));
        assertEquals(UUID.fromString(OWNER), PrizeService.boundTo(" " + OWNER + " "));
        assertNull(PrizeService.boundTo("not-a-uuid"), "a tag that isn't a UUID binds to nobody");
        assertNull(PrizeService.boundTo(""));
        assertNull(PrizeService.boundTo((String) null));
        assertEquals(new UUID(0, 0), PrizeService.boundTo("00000000-0000-0000-0000-000000000000"),
                "the preview stand's stray key: bound to the nil UUID, so every sweep removes it");
    }

    @Test
    void nothingIsNeverBound() {
        assertFalse(PrizeService.bound((org.bukkit.inventory.ItemStack) null));
        assertNull(PrizeService.boundTo((org.bukkit.inventory.ItemStack) null));
    }
}
