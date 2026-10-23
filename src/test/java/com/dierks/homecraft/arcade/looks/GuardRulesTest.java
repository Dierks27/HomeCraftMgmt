package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.arcade.looks.GuardRules.Top;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's decision table (SHOP-V2-SPEC §3.7), every row: a bound key never goes into a top that isn't the
 * player's own crafting grid or ender chest, never into a bundle, and is never dropped or copied from a click; moves
 * inside the player's own things are free.
 */
class GuardRulesTest {

    /** A click with only the facts each case names set. */
    private static boolean click(Top top, boolean inTop, InventoryAction action, ClickType type, boolean cursor,
                                 boolean current) {
        return GuardRules.clickRefused(top, inTop, action, type, cursor, current, false, false, false, false);
    }

    @Test
    void everyWayIntoAChestIsRefused() {
        assertTrue(click(Top.OTHER, true, InventoryAction.PLACE_ALL, ClickType.LEFT, true, false), "cursor to a chest slot");
        assertTrue(click(Top.OTHER, true, InventoryAction.PLACE_ONE, ClickType.RIGHT, true, false));
        assertTrue(click(Top.OTHER, true, InventoryAction.PLACE_SOME, ClickType.LEFT, true, false));
        assertTrue(click(Top.OTHER, true, InventoryAction.SWAP_WITH_CURSOR, ClickType.LEFT, true, false),
                "swapped with what is in the chest slot");
        assertTrue(click(Top.OTHER, false, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, false, true),
                "shift-click up from the bag");
        assertTrue(GuardRules.clickRefused(Top.OTHER, true, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY, false,
                false, true, false, false, false), "a number key with the key on that hotbar slot");
        assertTrue(GuardRules.clickRefused(Top.OTHER, true, InventoryAction.HOTBAR_SWAP, ClickType.SWAP_OFFHAND, false,
                false, false, true, false, false), "SWAP_OFFHAND with the key in the off-hand");
        assertTrue(GuardRules.dragRefused(Top.OTHER, true, true), "a drag over a chest slot");
    }

    @Test
    void anotherPlayersEnderChestIsAChestLikeAnyOther() {
        // "Own ender chest" is view.getTopInventory().equals(player.getEnderChest()); anyone else's is OTHER.
        assertTrue(click(Top.OTHER, true, InventoryAction.PLACE_ALL, ClickType.LEFT, true, false));
        assertFalse(click(Top.OWN_ENDER_CHEST, true, InventoryAction.PLACE_ALL, ClickType.LEFT, true, false),
                "her own ender chest is fine: she can put her hats away");
        assertFalse(click(Top.OWN_ENDER_CHEST, false, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT,
                false, true));
        assertFalse(GuardRules.dragRefused(Top.OWN_ENDER_CHEST, true, true));
    }

    @Test
    void movesInsideYourOwnThingsAreAllowed() {
        assertFalse(click(Top.OWN_CRAFTING, false, InventoryAction.PLACE_ALL, ClickType.LEFT, true, false),
                "a bag slot to another bag slot");
        assertFalse(click(Top.OWN_CRAFTING, false, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT,
                false, true), "shift-click onto the head, or hotbar to bag");
        assertFalse(click(Top.OWN_CRAFTING, true, InventoryAction.PLACE_ALL, ClickType.LEFT, true, false),
                "the 2x2 grid");
        assertFalse(GuardRules.clickRefused(Top.OWN_CRAFTING, false, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY,
                false, false, true, false, false, false), "a number key inside the bag");
        assertFalse(GuardRules.dragRefused(Top.OWN_CRAFTING, true, true));
        assertFalse(click(Top.OTHER, true, InventoryAction.PICKUP_ALL, ClickType.LEFT, false, true),
                "taking one out of a chest it got into some other way");
        assertFalse(click(Top.OTHER, true, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, false, true),
                "shift-click down from the chest");
        assertFalse(click(Top.OTHER, false, InventoryAction.PLACE_ALL, ClickType.LEFT, true, false),
                "a chest open, the key moved inside the bag");
        assertFalse(click(Top.OTHER, true, InventoryAction.COLLECT_TO_CURSOR, ClickType.DOUBLE_CLICK, true, false));
        assertFalse(click(Top.OTHER, true, InventoryAction.PLACE_ALL, ClickType.LEFT, false, false),
                "nothing bound: nothing to judge");
    }

    @Test
    void neverIntoABundleAnywhere() {
        assertTrue(GuardRules.clickRefused(Top.OWN_CRAFTING, false, InventoryAction.PLACE_ALL_INTO_BUNDLE,
                ClickType.LEFT, true, false, false, false, false, true), "the cursor key onto a bundle");
        assertTrue(GuardRules.clickRefused(Top.OWN_CRAFTING, false, InventoryAction.PICKUP_ALL_INTO_BUNDLE,
                ClickType.RIGHT, false, true, false, false, true, false), "a bundle on the cursor over a key");
        assertTrue(GuardRules.clickRefused(Top.OWN_ENDER_CHEST, true, InventoryAction.SWAP_WITH_CURSOR,
                ClickType.LEFT, true, false, false, false, false, true), "even in her own ender chest");
        assertFalse(GuardRules.clickRefused(Top.OWN_CRAFTING, false, InventoryAction.PLACE_ALL_INTO_BUNDLE,
                ClickType.LEFT, false, false, false, false, false, true), "anything else into a bundle is fine");
    }

    @Test
    void noDropAndNoCopyFromAClick() {
        assertTrue(click(Top.OWN_CRAFTING, false, InventoryAction.DROP_ALL_SLOT, ClickType.CONTROL_DROP, false, true));
        assertTrue(click(Top.OWN_CRAFTING, false, InventoryAction.DROP_ONE_SLOT, ClickType.DROP, false, true));
        assertTrue(click(Top.OWN_CRAFTING, false, InventoryAction.DROP_ALL_CURSOR, ClickType.LEFT, true, false),
                "a click outside the window with the key on the cursor");
        assertTrue(click(Top.OTHER, false, InventoryAction.CLONE_STACK, ClickType.MIDDLE, false, true),
                "a Creative middle-click copy with a chest open");
        assertFalse(click(Top.OWN_CRAFTING, false, InventoryAction.DROP_ALL_SLOT, ClickType.CONTROL_DROP, false, false));
    }

    @Test
    void ourMenusAndCreativeAreNeverJudged() {
        assertFalse(click(Top.MENU, true, InventoryAction.PLACE_ALL, ClickType.LEFT, true, false),
                "MenuListener already cancels every click in our menus");
        assertFalse(GuardRules.dragRefused(Top.MENU, true, true));
        assertFalse(GuardRules.creativeRefused(), "a Creative inventory is the client's: a refusal desyncs it");
    }

    /** A stand-in item for {@link GuardRules#keysInside}: a key, plain stuff, or a container. */
    private record Thing(LookRules.Key key, List<Thing> inside, boolean unreadable) {
        static Thing key(String id) {
            return new Thing(new LookRules.Key(id, UUID.randomUUID(), 0, null), null, false);
        }

        static Thing plain() {
            return new Thing(null, null, false);
        }

        static Thing bundle(Thing... in) {
            return new Thing(null, Arrays.asList(in), false);
        }

        boolean container() {
            return inside != null || unreadable;
        }
    }

    private static List<LookRules.Found> inside(Thing t) {
        return GuardRules.keysInside(t, Thing::container, Thing::key, (x, depth) -> x.unreadable() ? Optional.empty()
                : Optional.of(x.inside() == null ? List.of() : x.inside()));
    }

    /**
     * Red-team finding GUARD-1: a bundle or a shulker box holding a key (only Creative puts one there, and the
     * player's sweep keeps it) was invisible to the click guard, the close sweep and the spawn guard, so it could go
     * into a chest or onto the ground with the key inside. It now counts as the key: every key inside is found, at
     * any depth the sweep reads, and an item that isn't a container costs one test.
     */
    @Test
    void aContainerHoldingAKeyIsGuardedAsTheKey() {
        List<LookRules.Found> found = inside(Thing.bundle(Thing.plain(), Thing.key("cap_lime"),
                Thing.bundle(Thing.key("keeper_wizard_hat"))));
        assertEquals(2, found.size(), "[X] both keys inside, the nested one too");
        assertEquals("cap_lime", found.get(0).key().id());
        assertEquals(List.of(1), found.get(0).path(), "its place, so it can be taken out and the rest kept");
        assertEquals(List.of(2, 0), found.get(1).path());
        assertTrue(inside(Thing.bundle(Thing.plain(), null)).isEmpty(), "a bundle of plain things is plain");
        assertTrue(inside(Thing.key("cap_lime")).isEmpty(), "a key itself is the bound test's, not this one's");
        assertTrue(inside(Thing.plain()).isEmpty(), "not a container: nothing is read");
        assertTrue(inside(new Thing(null, null, true)).isEmpty(), "an unreadable one finds nothing: never deleted");
        assertTrue(inside(null).isEmpty());
        // and the click rules then refuse it as they refuse a key
        assertTrue(click(Top.OTHER, true, InventoryAction.PLACE_ALL, ClickType.LEFT, !found.isEmpty(), false),
                "[X] the bundle into a chest");
    }

    @Test
    void framesStandsAlliesPotsAndShelves() {
        assertTrue(GuardRules.entityRefused(GuardRules.EntityKind.ITEM_FRAME, true));
        assertTrue(GuardRules.entityRefused(GuardRules.EntityKind.ARMOR_STAND, true));
        assertTrue(GuardRules.entityRefused(GuardRules.EntityKind.ALLAY, true));
        assertFalse(GuardRules.entityRefused(GuardRules.EntityKind.OTHER, true), "a villager, a horse: their own rules");
        assertFalse(GuardRules.entityRefused(GuardRules.EntityKind.ITEM_FRAME, false));
        assertTrue(GuardRules.blockUseRefused("DECORATED_POT", true, false));
        assertTrue(GuardRules.blockUseRefused("CHISELED_BOOKSHELF", true, false));
        assertFalse(GuardRules.blockUseRefused("DECORATED_POT", false, true), "a pot takes only the hand's item");
        assertTrue(GuardRules.blockUseRefused("OAK_SHELF", false, true),
                "a shelf while any hotbar slot holds a key: a powered shelf swaps the whole hotbar (D-m8)");
        assertTrue(GuardRules.blockUseRefused("PALE_OAK_SHELF", true, false));
        assertFalse(GuardRules.blockUseRefused("OAK_SHELF", false, false));
        assertFalse(GuardRules.blockUseRefused("BOOKSHELF", true, true), "a plain bookshelf does nothing with items");
        assertFalse(GuardRules.blockUseRefused("CHEST", true, true), "a chest is the click rules' to judge");
    }
}
