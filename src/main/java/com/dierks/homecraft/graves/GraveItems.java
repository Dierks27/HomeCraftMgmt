package com.dierks.homecraft.graves;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;

/**
 * What is in a grave and where it goes back (HCM-GRAVES §4). Pure and generic over the item type, so the slot
 * matching and the restore plan are tested with plain values ({@code GraveItemsTest}); {@code GraveService} uses it
 * with {@code ItemStack}.
 *
 * <p>A grave holds a list of {@link Entry}s: each stack with the inventory slot it came from (-1 when it didn't
 * come from one: the cursor, the crafting grid, another plugin's addition) and its own number {@code n}, given at
 * the death and never reused, so taking one stack from the grave screen names exactly that stack even after others
 * have gone. Slots are the player inventory's own numbering: 0-8 the hotbar, 9-35 the rest, 36 boots, 37
 * leggings, 38 chestplate, 39 helmet, 40 the off hand.
 */
public final class GraveItems {

    /** The player inventory's slots: 36 storage, 4 armor, the off hand. */
    public static final int SLOTS = 41;

    private GraveItems() {
    }

    /** One stack in a grave, as stored: its number, the slot it came from (-1: none) and the serialized item. */
    public record Entry(int n, int slot, String data) {
    }

    /** One stack with the slot it came from (-1: none). */
    public record Slotted<I>(int slot, I item) {
    }

    /**
     * The slot each drop came from: each inventory slot, in order, claims the first drop that is the
     * {@code same} stack and hasn't been claimed. A drop no slot claims gets -1. The result is in the drops' order,
     * and holds each drop exactly once.
     *
     * @param inventory the player's inventory as {@code getContents()} gives it (null or empty slots allowed)
     */
    public static <I> List<Slotted<I>> assignSlots(List<I> inventory, List<I> drops, BiPredicate<I, I> same) {
        Map<I, Integer> claimed = new IdentityHashMap<>();
        for (int slot = 0; slot < inventory.size() && slot < SLOTS; slot++) {
            I inSlot = inventory.get(slot);
            if (inSlot == null) {
                continue;
            }
            for (I drop : drops) {
                if (drop != null && !claimed.containsKey(drop) && same.test(inSlot, drop)) {
                    claimed.put(drop, slot);
                    break;
                }
            }
        }
        List<Slotted<I>> out = new ArrayList<>(drops.size());
        for (I drop : drops) {
            if (drop != null) {
                Integer slot = claimed.get(drop);
                out.add(new Slotted<>(slot == null ? -1 : slot, drop));
            }
        }
        return out;
    }

    /** Where each stack goes when the owner takes the grave back. */
    public record Plan<I>(Map<Integer, I> intoSlots, List<I> anywhere) {
    }

    /**
     * Back into the slots they came from where those are empty now; the rest (a slot the player has filled since,
     * a stack with no slot, a second stack for one slot) to go in anywhere, in order. The caller drops at the
     * player's feet whatever the inventory won't take. Every stack is in the plan exactly once.
     */
    public static <I> Plan<I> plan(List<Slotted<I>> items, IntPredicate slotEmpty) {
        Map<Integer, I> into = new LinkedHashMap<>();
        List<I> anywhere = new ArrayList<>();
        for (Slotted<I> s : items) {
            int slot = s.slot();
            if (slot >= 0 && slot < SLOTS && !into.containsKey(slot) && slotEmpty.test(slot)) {
                into.put(slot, s.item());
            } else {
                anywhere.add(s.item());
            }
        }
        return new Plan<>(Collections.unmodifiableMap(into), Collections.unmodifiableList(anywhere));
    }

    /**
     * What of the drops goes in the grave: every stack that isn't {@code empty} and isn't {@code ownHandling} (a prize,
     * a game kit item, a courier package: their own modules decide, and they stay in the drops), in the drops' order.
     */
    public static <I> List<I> take(List<I> drops, java.util.function.Predicate<I> empty,
                                   java.util.function.Predicate<I> ownHandling) {
        List<I> out = new ArrayList<>();
        for (I i : drops) {
            if (i != null && !empty.test(i) && !ownHandling.test(i)) {
                out.add(i);
            }
        }
        return out;
    }

    /** A grave made from the inventory itself: what goes in it (with each slot), and what the death keeps. */
    public record FromInventory<I>(List<Slotted<I>> toGrave, List<I> toKeep) {
    }

    /**
     * A keep-inventory death that still makes a grave ({@code graves.keep_inventory_makes_grave}): the inventory, slot
     * by slot. {@code ownHandling} stacks (prizes, kit items, courier packages) are kept, as the keep-inventory death
     * would have kept them; {@code gone} stacks (a grave compass, a Curse of Vanishing item) are neither kept nor put in
     * the grave, as in any death; everything else goes in the grave with its slot. Each stack is in exactly one place.
     */
    public static <I> FromInventory<I> fromInventory(List<I> inventory, java.util.function.Predicate<I> empty,
                                                     java.util.function.Predicate<I> ownHandling,
                                                     java.util.function.Predicate<I> gone) {
        List<Slotted<I>> grave = new ArrayList<>();
        List<I> keep = new ArrayList<>();
        for (int slot = 0; slot < inventory.size() && slot < SLOTS; slot++) {
            I item = inventory.get(slot);
            if (item == null || empty.test(item) || gone.test(item)) {
                continue;
            }
            if (ownHandling.test(item)) {
                keep.add(item);
            } else {
                grave.add(new Slotted<>(slot, item));
            }
        }
        return new FromInventory<>(grave, keep);
    }

    /** For each of {@code taken}, the first equal stack still in {@code drops} is removed; returns how many went. */
    public static <I> int removeEqualOnce(List<I> drops, List<I> taken, BiPredicate<I, I> same) {
        int removed = 0;
        for (I t : taken) {
            for (var it = drops.iterator(); it.hasNext(); ) {
                I d = it.next();
                if (d != null && same.test(d, t)) {
                    it.remove();
                    removed++;
                    break;
                }
            }
        }
        return removed;
    }

    /** The stacks of {@code drops} that are in {@code taken} (by identity), removed from {@code drops}. */
    public static <I> int removeTaken(List<I> drops, Set<I> taken) {
        int removed = 0;
        for (var it = drops.iterator(); it.hasNext(); ) {
            if (taken.contains(it.next())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** An identity set of {@code items}. */
    public static <I> Set<I> identitySet(List<I> items) {
        Set<I> out = Collections.newSetFromMap(new IdentityHashMap<>());
        out.addAll(items);
        return out;
    }

    // ---- stored form ------------------------------------------------------------------------------------

    /**
     * The stored form: one line per entry, {@code n:slot:data}. The serialized item is Base64, which never holds
     * a colon or a newline.
     */
    public static String encode(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(e.n()).append(':').append(e.slot()).append(':').append(e.data());
        }
        return sb.toString();
    }

    /** {@link #encode}'s lines back; a line that can't be read is skipped (and so is a blank one). */
    public static List<Entry> decode(String stored) {
        List<Entry> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String line : stored.split("\n")) {
            int a = line.indexOf(':');
            int b = a < 0 ? -1 : line.indexOf(':', a + 1);
            if (a <= 0 || b <= a + 1 || b == line.length() - 1) {
                continue;
            }
            try {
                out.add(new Entry(Integer.parseInt(line.substring(0, a).trim()),
                        Integer.parseInt(line.substring(a + 1, b).trim()), line.substring(b + 1).trim()));
            } catch (NumberFormatException ignored) {
                // skipped: a hand-edited row
            }
        }
        return out;
    }

    /** {@code entries} without the one numbered {@code n}. */
    public static List<Entry> without(List<Entry> entries, int n) {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.n() != n) {
                out.add(e);
            }
        }
        return out;
    }
}
