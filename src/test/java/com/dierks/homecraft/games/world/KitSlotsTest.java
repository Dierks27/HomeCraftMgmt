package com.dierks.homecraft.games.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A game's kit never goes over the player's own things (final gate, #16). An auction win or a Mini is
 * delivered with {@code addItem} whatever the player is doing, so mid-session it lands in the first
 * empty slot: the Dropper's slot 1 between kits, the elytra's rocket slot once the rockets are used,
 * a race's warm-up slot. Every kit write therefore goes through {@link KitItems#put} (their thing
 * moves aside, to be banked at the end) and every kit slot is emptied through {@link KitItems#clear}
 * (only a kit item is taken), and no game writes a player's inventory any other way.
 */
class KitSlotsTest {

    /** Items are strings; a kit item starts with {@code "kit:"}. */
    static final class Inv implements KitItems.Slots<String> {
        final String[] slots = new String[41];

        @Override
        public String get(int slot) {
            return slots[slot];
        }

        @Override
        public void set(int slot, String item) {
            slots[slot] = item;
        }

        @Override
        public int firstEmpty() {
            for (int i = 0; i < 36; i++) {
                if (slots[i] == null) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean empty(String item) {
            return item == null;
        }

        @Override
        public boolean kit(String item) {
            return item.startsWith("kit:");
        }

        long count(String item) {
            return Arrays.stream(slots).filter(item::equals).count();
        }
    }

    @Test
    void aKitItemGoesInItsSlotAndThePlayersOwnThingThereMovesAside() {
        Inv inv = new Inv();
        inv.slots[0] = "kit:trials:checkpoint";
        inv.slots[1] = "Mini #42"; // delivered into the empty rocket slot
        assertTrue(KitItems.put(inv, 1, "kit:trials:firework x3"), "the rockets go in");
        assertEquals("kit:trials:firework x3", inv.slots[1], "in their slot");
        assertEquals("Mini #42", inv.slots[2], "the Mini moved to the first empty slot, never overwritten");
        assertTrue(KitItems.put(inv, 0, "kit:trials:checkpoint"), "an old kit item is simply replaced");
        assertEquals(1, inv.count("kit:trials:checkpoint"), "once");
    }

    @Test
    void withNoRoomTheKitItemWaitsAndTheirThingStays() {
        Inv inv = new Inv();
        for (int i = 0; i < 36; i++) {
            inv.slots[i] = "cobblestone x64";
        }
        inv.slots[1] = "Mini #42";
        assertFalse(KitItems.put(inv, 1, "kit:trials:firework x3"), "nowhere to move the Mini: no rockets");
        assertEquals("Mini #42", inv.slots[1], "and the Mini stays");
    }

    @Test
    void clearingAKitSlotTakesOnlyAKitItem() {
        Inv inv = new Inv();
        inv.slots[4] = "kit:trials:ready";
        assertTrue(KitItems.clear(inv, 4), "the warm-up's Ready is taken");
        assertNull(inv.slots[4], "gone");
        inv.slots[4] = "Mini #42";
        assertFalse(KitItems.clear(inv, 4), "a Mini that landed there is not a kit item");
        assertEquals("Mini #42", inv.slots[4], "it stays, to be banked at the session's end");
        assertFalse(KitItems.clear(inv, 5), "an empty slot: nothing to take");
    }

    // ---- no game writes a player's inventory any other way ----------------------------------------------

    /** A write straight into an inventory slot, or a slot of armour or a hand. */
    private static final Pattern RAW = Pattern.compile(
            "\\.(setItem|setItemInMainHand|setItemInOffHand|setHelmet|setChestplate|setLeggings|setBoots"
                    + "|setArmorContents|setStorageContents|setContents)\\(|getInventory\\(\\)\\.clear\\(");

    /** Where a raw write is the point: the kit rules themselves, and the saved state's own restore and clear. */
    private static final List<String> ALLOWED = List.of("world/KitItems.java", "world/BukkitStateAdapter.java");

    @Test
    void noGameWritesAPlayersInventoryExceptThroughTheKitRules() throws IOException {
        Path root = Path.of("src/main/java/com/dierks/homecraft/games");
        List<String> raw = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (ALLOWED.contains(rel)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // an armour stand's or a display's equipment is nobody's inventory
                    if (RAW.matcher(line).find() && !line.contains("getEquipment()")) {
                        raw.add(rel + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(raw.isEmpty(), "every kit item goes in with KitItems.put and every kit slot is emptied with "
                + "KitItems.clear, so an auction win or a Mini delivered mid-session is never overwritten:\n"
                + String.join("\n", raw));
    }
}
