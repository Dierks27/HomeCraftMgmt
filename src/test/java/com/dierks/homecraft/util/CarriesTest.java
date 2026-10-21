package com.dierks.homecraft.util;

import com.dierks.homecraft.marketplace.PalletService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TokenPrizes.carries} fails closed (0.37.3, spec D36, red team D-M3): past its depth limit an item that holds
 * anything counts as carrying a prize, and so does one whose contents can't be read. A prize six bundles deep used
 * to pass and went onto a Pallet. The walk runs here on stand-ins for items, with the real depth rule.
 */
class CarriesTest {

    /** A stand-in item: a prize or not, what it holds, and whether its contents can be read. */
    private record Box(String what, boolean prize, List<Box> inside, boolean unreadable) {

        static Box item(String what) {
            return new Box(what, false, List.of(), false);
        }

        static Box arcadePrize() {
            return new Box("an Arcade prize", true, List.of(), false);
        }

        static Box bundle(Box... inside) {
            return new Box("a bundle", false, List.of(inside), false);
        }
    }

    private static boolean carries(Box b) {
        return TokenPrizes.carries(b, 0, Box::prize,
                (box, depth) -> box.unreadable() ? Optional.empty() : TokenPrizes.limited(box.inside(), depth));
    }

    /** {@code inner} inside {@code n} bundles. */
    private static Box nested(int n, Box inner) {
        Box b = inner;
        for (int i = 0; i < n; i++) {
            b = Box.bundle(b);
        }
        return b;
    }

    @Test
    void aPrizeSixBundlesDeepCountsAsCarrying() {
        assertTrue(carries(nested(6, Box.arcadePrize())), "six deep: the sixth bundle isn't opened, so it counts");
        assertTrue(carries(nested(7, Box.arcadePrize())));
        assertTrue(carries(nested(5, Box.arcadePrize())), "five deep is still read and found");
        assertTrue(carries(nested(1, Box.arcadePrize())));
        assertTrue(carries(Box.arcadePrize()));
    }

    @Test
    void anUnreadableContainerCountsAsCarrying() {
        assertTrue(carries(new Box("a shulker box that can't be read", false, List.of(), true)));
        assertTrue(carries(Box.bundle(Box.item("dirt"), new Box("a bundle that can't be read", false, List.of(), true))),
                "one unreadable bundle anywhere inside is enough");
    }

    @Test
    void aPlainNestedBundleOfDirtDoesNot() {
        assertFalse(carries(Box.item("dirt")));
        assertFalse(carries(Box.bundle(Box.item("dirt"), Box.item("cobblestone"))));
        assertFalse(carries(nested(3, Box.bundle(Box.item("dirt")))), "bundles of dirt four deep");
        assertFalse(carries(nested(4, Box.bundle())), "empty bundles five deep hold nothing to hide");
        assertFalse(carries(null));
    }

    @Test
    void theDepthRule() {
        List<String> some = List.of("dirt");
        assertEquals(Optional.of(some), TokenPrizes.limited(some, TokenPrizes.MAX_DEPTH), "the deepest that is read");
        assertEquals(Optional.empty(), TokenPrizes.limited(some, TokenPrizes.MAX_DEPTH + 1),
                "deeper and holding something: unread");
        assertEquals(Optional.of(List.of()), TokenPrizes.limited(List.<String>of(), TokenPrizes.MAX_DEPTH + 9),
                "deeper and holding nothing: nothing to hide");
        assertTrue(carries(nested(6, Box.item("dirt"))),
                "dirt six deep counts too: past the limit nothing is opened, so it can't be told from a prize");
    }

    @Test
    void thePalletRefusesTheSixDeepBundle() {
        assertEquals(TokenPrizes.REFUSAL, PalletService.stockRefusal(false, carries(nested(6, Box.arcadePrize())), false,
                false), "Arcade prizes can't be sold");
        assertNull(PalletService.stockRefusal(false, carries(nested(3, Box.bundle(Box.item("dirt")))), false, false),
                "the bundle of dirt lists as ever");
        assertEquals("Minis are sold at the Vending Machine / Auction House, not Pallets.",
                PalletService.stockRefusal(true, true, false, false), "the checks keep their order");
        assertEquals("A delivery crate is not yours to sell.", PalletService.stockRefusal(false, false, true, true));
        assertEquals("That item can't be listed on the Marketplace.",
                PalletService.stockRefusal(false, false, false, true));
    }
}
