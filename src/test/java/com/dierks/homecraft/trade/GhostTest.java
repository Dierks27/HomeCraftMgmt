package com.dierks.homecraft.trade;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trade window's pictures of offered stacks (SHOP-V2-SPEC §4.3, red team D-m2): a ghost loses every
 * {@code homecraftmanagement:} and {@code hcm:} key and gains {@code hcm:trade_ghost}; a shulker's or a bundle's
 * ghost is built EMPTY (the key strip is top-level only, so a ghost that kept its contents would carry a real Mini
 * or prize), with what it holds as lore lines; and another plugin's keys are left alone.
 */
class GhostTest {

    /** A stand-in item: its data keys, what it holds, and its lore. */
    private static final class Fake implements ItemFacts.Ghostable {
        final Set<String> keys = new LinkedHashSet<>();
        final List<String> contents = new ArrayList<>();
        final List<String> lore = new ArrayList<>();

        Fake(String... keys) {
            this.keys.addAll(List.of(keys));
        }

        @Override
        public Set<String> keys() {
            return keys;
        }

        @Override
        public void removeKey(String key) {
            keys.remove(key);
        }

        @Override
        public void markGhost() {
            keys.add("hcm:trade_ghost");
        }

        @Override
        public void empty() {
            contents.clear();
        }

        @Override
        public void addLore(List<String> lines) {
            lore.addAll(lines);
        }
    }

    private static ItemFacts thing(String name, int amount) {
        return ItemFacts.plain(name, "STONE", amount);
    }

    @Test
    void aShulkersGhostHoldsNothingAndSaysWhatTheRealOneHolds() {
        Fake box = new Fake("homecraftmanagement:custom_block_type", "other:tag");
        box.contents.addAll(List.of("12 Diamond", "Creeper Mini (uid u-1)"));
        ItemFacts.ghost(box, "Owner", List.of(thing("Diamond", 8), thing("Creeper Mini", 1), thing("Diamond", 4)),
                false);
        assertTrue(box.contents.isEmpty(), "[X] the ghost holds nothing: no real Mini rides along");
        assertEquals(Set.of("other:tag", "hcm:trade_ghost"), box.keys, "ours are gone, another plugin's stay");
        assertEquals(List.of("&7Inside: 12 Diamond", "&7  1 Creeper Mini",
                "&8Still in Owner's bag until you both press Ready"), box.lore, "the same names added up");
    }

    @Test
    void aBundlesGhostHoldsNothingEither() {
        Fake bundle = new Fake("hcm:token_prize");
        bundle.contents.add("Speed Boost");
        ItemFacts.ghost(bundle, "Helper", List.of(thing("Speed Boost", 1)), false);
        assertTrue(bundle.contents.isEmpty(), "[X] a bundle's ghost holds nothing");
        assertFalse(bundle.keys.contains("hcm:token_prize"), "and is no prize");
        assertTrue(bundle.keys.contains("hcm:trade_ghost"));
    }

    @Test
    void aMinisGhostIsNoMini() {
        Fake head = new Fake("homecraftmanagement:mini_uid", "homecraftmanagement:mini_id",
                "HomeCraftManagement:mini_mint", "hcm:bound_to");
        ItemFacts.ghost(head, "Owner", List.of(), false);
        assertEquals(Set.of("hcm:trade_ghost"), head.keys, "no uid, no id, no bound key: a plain head");
        assertEquals(List.of("&8Still in Owner's bag until you both press Ready"), head.lore, "no Inside lines");
    }

    @Test
    void atMostEightLinesThenAndNMore() {
        List<ItemFacts> inside = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            inside.add(thing("Thing " + i, i));
        }
        List<String> lines = ItemFacts.insideLines(inside, false);
        assertEquals(9, lines.size());
        assertEquals("&7Inside: 1 Thing 1", lines.get(0));
        assertEquals("&7  8 Thing 8", lines.get(7));
        assertEquals("&7and 3 more", lines.get(8));
        assertEquals(List.of("&7Too many bags inside bags"), ItemFacts.insideLines(List.of(), true),
                "contents too deep to read say so");
    }

    @Test
    void onlyOurNamespacesAreStripped() {
        assertTrue(ItemFacts.stripped("hcm:token_prize"));
        assertTrue(ItemFacts.stripped("homecraftmanagement:mini_uid"));
        assertFalse(ItemFacts.stripped("minecraft:custom"));
        assertFalse(ItemFacts.stripped("hcmx:thing"));
        assertFalse(ItemFacts.stripped(null));
    }
}
