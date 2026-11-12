package com.dierks.homecraft.graves;

import com.dierks.homecraft.graves.GraveItems.Entry;
import com.dierks.homecraft.graves.GraveItems.Slotted;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What goes in a grave and where it goes back, with plain strings standing in for stacks (an "item" is equal to
 * another by value, as {@code ItemStack.equals} compares type, amount and meta).
 */
class GraveItemsTest {

    /** A player inventory of 41 slots, all empty. */
    private static List<String> inventory() {
        return new ArrayList<>(Arrays.asList(new String[GraveItems.SLOTS]));
    }

    // ---- what goes in ---------------------------------------------------------------------------------

    @Test
    void prizesKitItemsAndCourierPackagesNeverGoInAGrave() {
        List<String> drops = new ArrayList<>(List.of("dirt", "prize:cap", "sword", "kit:elytra", "package:7", "air"));
        List<String> taken = GraveItems.take(drops, "air"::equals,
                s -> s.startsWith("prize:") || s.startsWith("kit:") || s.startsWith("package:"));
        assertEquals(List.of("dirt", "sword"), taken);
        GraveItems.removeTaken(drops, GraveItems.identitySet(taken));
        assertEquals(List.of("prize:cap", "kit:elytra", "package:7", "air"), drops,
                "they stay in the drops for their own modules; nothing is both on the ground and in the grave");
    }

    @Test
    void whatIsTakenLeavesTheDropsExactlyOnce() {
        String a = new String("stone");
        String b = new String("stone"); // an equal stack in another slot: both go, each once
        List<String> drops = new ArrayList<>(List.of(a, b, "torch"));
        List<String> taken = GraveItems.take(drops, s -> false, s -> s.equals("torch"));
        assertEquals(2, GraveItems.removeTaken(drops, GraveItems.identitySet(taken)));
        assertEquals(List.of("torch"), drops);
    }

    @Test
    void aKeepInventoryGraveIsMadeFromTheInventorySlotBySlot() {
        List<String> inv = inventory();
        inv.set(0, "sword");
        inv.set(3, "prize:cap");
        inv.set(7, "compass:4");
        inv.set(20, "vanishing-boots");
        inv.set(38, "chestplate");
        inv.set(40, "package:9");
        GraveItems.FromInventory<String> fi = GraveItems.fromInventory(inv, s -> false,
                s -> s.startsWith("prize:") || s.startsWith("package:"),
                s -> s.startsWith("compass:") || s.startsWith("vanishing"));
        assertEquals(List.of(new Slotted<>(0, "sword"), new Slotted<>(38, "chestplate")), fi.toGrave(),
                "each with the slot it was in");
        assertEquals(List.of("prize:cap", "package:9"), fi.toKeep(), "kept, as the keep-inventory death would");
    }

    @Test
    void aPluginsCopyInTheDropsIsTakenOutOnce() {
        List<String> drops = new ArrayList<>(List.of("sword", "dirt", "sword"));
        assertEquals(1, GraveItems.removeEqualOnce(drops, List.of("sword"), String::equals));
        assertEquals(List.of("dirt", "sword"), drops, "one for one: the second sword is its own stack");
    }

    // ---- which slot each came from ----------------------------------------------------------------------

    @Test
    void eachDropFindsItsSlot() {
        List<String> inv = inventory();
        inv.set(0, "sword");
        inv.set(5, "bread");
        inv.set(39, "helmet");
        inv.set(40, "shield");
        List<Slotted<String>> s = GraveItems.assignSlots(inv, List.of("shield", "sword", "helmet", "bread"),
                String::equals);
        assertEquals(List.of(new Slotted<>(40, "shield"), new Slotted<>(0, "sword"), new Slotted<>(39, "helmet"),
                new Slotted<>(5, "bread")), s);
    }

    @Test
    void equalStacksInTwoSlotsGetOneSlotEach() {
        List<String> inv = inventory();
        inv.set(2, "cobble");
        inv.set(30, "cobble");
        List<Slotted<String>> s = GraveItems.assignSlots(inv, List.of(new String("cobble"), new String("cobble")),
                String::equals);
        assertEquals(2, s.get(0).slot());
        assertEquals(30, s.get(1).slot());
    }

    @Test
    void aDropFromNoSlotGetsMinusOne() {
        List<String> inv = inventory();
        inv.set(0, "sword");
        List<Slotted<String>> s = GraveItems.assignSlots(inv, List.of("sword", "from-the-cursor"), String::equals);
        assertEquals(-1, s.get(1).slot(), "the cursor, the crafting grid, another plugin: anywhere");
    }

    // ---- where it goes back ---------------------------------------------------------------------------

    @Test
    void restoreIntoTheOriginalSlots() {
        List<Slotted<String>> grave = List.of(new Slotted<>(0, "sword"), new Slotted<>(36, "boots"),
                new Slotted<>(39, "helmet"), new Slotted<>(40, "shield"), new Slotted<>(8, "food"));
        GraveItems.Plan<String> plan = GraveItems.plan(grave, slot -> true);
        assertEquals(Map.of(0, "sword", 36, "boots", 39, "helmet", 40, "shield", 8, "food"), plan.intoSlots(),
                "armor back on, the off hand and the hotbar where they were");
        assertTrue(plan.anywhere().isEmpty());
    }

    @Test
    void restoreWithAFullInventory() {
        // Since the death they've picked up dirt in every slot: nothing goes to its slot, everything goes in
        // anywhere (the caller drops at their feet what won't fit). Nothing is lost or doubled.
        List<Slotted<String>> grave = List.of(new Slotted<>(0, "sword"), new Slotted<>(38, "chestplate"),
                new Slotted<>(-1, "cursor"));
        GraveItems.Plan<String> plan = GraveItems.plan(grave, slot -> false);
        assertTrue(plan.intoSlots().isEmpty());
        assertEquals(List.of("sword", "chestplate", "cursor"), plan.anywhere());
    }

    @Test
    void restoreWithSomeSlotsFilled() {
        Set<Integer> filled = new HashSet<>(Set.of(0, 39));
        List<Slotted<String>> grave = List.of(new Slotted<>(0, "sword"), new Slotted<>(1, "pick"),
                new Slotted<>(39, "helmet"), new Slotted<>(38, "chestplate"));
        GraveItems.Plan<String> plan = GraveItems.plan(grave, slot -> !filled.contains(slot));
        assertEquals(Map.of(1, "pick", 38, "chestplate"), plan.intoSlots());
        assertEquals(List.of("sword", "helmet"), plan.anywhere());
    }

    @Test
    void twoStacksForOneSlotNeverOverwriteEachOther() {
        GraveItems.Plan<String> plan = GraveItems.plan(List.of(new Slotted<>(4, "a"), new Slotted<>(4, "b")),
                slot -> true);
        assertEquals(Map.of(4, "a"), plan.intoSlots());
        assertEquals(List.of("b"), plan.anywhere());
    }

    @Test
    void anOtherPlayersLootGoesAnywhere() {
        GraveItems.Plan<String> plan = GraveItems.plan(List.of(new Slotted<>(-1, "a"), new Slotted<>(-1, "b")),
                slot -> true);
        assertTrue(plan.intoSlots().isEmpty());
        assertEquals(List.of("a", "b"), plan.anywhere());
    }

    // ---- the stored form ------------------------------------------------------------------------------

    @Test
    void encodeAndDecode() {
        List<Entry> entries = List.of(new Entry(0, 0, "QUJD"), new Entry(1, -1, "REVG+/="), new Entry(2, 40, "R0g="));
        String stored = GraveItems.encode(entries);
        assertEquals("0:0:QUJD\n1:-1:REVG+/=\n2:40:R0g=", stored);
        assertEquals(entries, GraveItems.decode(stored));
        assertEquals(List.of(), GraveItems.decode(""));
        assertEquals(List.of(), GraveItems.decode(null));
    }

    @Test
    void aLineThatCantBeReadIsSkipped() {
        assertEquals(List.of(new Entry(1, 2, "X")), GraveItems.decode("junk\n1:2:X\n:3:Y\n4:5:\nz:1:Q"));
    }

    @Test
    void withoutTakesOneByItsNumber() {
        List<Entry> entries = List.of(new Entry(0, 0, "A"), new Entry(1, 1, "B"), new Entry(2, 2, "C"));
        assertEquals(List.of(new Entry(0, 0, "A"), new Entry(2, 2, "C")), GraveItems.without(entries, 1));
        assertEquals(entries, GraveItems.without(entries, 9), "a number that isn't there takes nothing");
    }
}
