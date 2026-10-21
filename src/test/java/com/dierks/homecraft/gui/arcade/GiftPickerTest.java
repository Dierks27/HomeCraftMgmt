package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.arcade.PrizeService;
import com.dierks.homecraft.arcade.PrizeService.GiftFacts;
import com.dierks.homecraft.config.PluginConfig.Collector;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Give a present" (HALLOWEEN-SPEC D8 steps 1-3): when the counter offers it, what the present screen
 * lists, who the person screen lists and how their tiles read, and that a green tile is exactly a
 * receiver {@link PrizeService#giftRefusal} would let through.
 */
class GiftPickerTest {

    private static final String NAME = "Friend";

    private static Prize row(String id, PrizeType type) {
        return new Prize(id, PrizeTab.COSMETICS, "&f" + id, List.of(), 100, new Icon("", Material.SUGAR), type, true,
                null, 1, null, null, null, null, 0, 0, null, 7, "", List.of(), List.of(), null, null,
                List.of(), null);
    }

    /** A collector the catalogue might one day hold that can't be given. */
    private static Prize notGiftable() {
        Prize cap = Collectors.ROWS.get(0);
        return new Prize("cap_keeper", cap.tab(), cap.display(), cap.description(), cap.costTokens(), cap.icon(),
                cap.type(), true, cap.limit(), 1, null, null, null, null, 0, 0, null, 0, "", List.of(), List.of(),
                null, null, List.of(), new Collector(2026, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                        false, cap.collector().look()));
    }

    @Test
    void theEntryShowsOnlyWhileGiftingIsOnAndAGiftableCollectorIsOnSale() {
        List<Prize> both = Collectors.ROWS.subList(0, 2);
        assertEquals(List.of("cap_pumpkin_2026", "trail_leaves_2026"),
                GiftPickerMenu.presents(true, both).stream().map(Prize::id).toList(), "both, in counter order");
        assertEquals(List.of("cap_santa_2026", "trail_twinkle_2026"),
                GiftPickerMenu.presents(true, Collectors.ROWS.subList(2, 4)).stream().map(Prize::id).toList(),
                "December: the Santa Cap and Twinkle, both giftable");
        assertEquals(List.of("trail_twinkle_2026"),
                GiftPickerMenu.presents(true, List.of(Collectors.ROWS.get(3))).stream().map(Prize::id).toList(),
                "1-6 January: Twinkle can still be given");
        assertTrue(GiftPickerMenu.presents(false, both).isEmpty(), "arcade.collectors.gifting: false hides the entry");
        assertTrue(GiftPickerMenu.presents(true, List.of()).isEmpty(),
                "nothing on sale (out of season, or on_sale: false): no entry");
        assertEquals(List.of("trail_leaves_2026"),
                GiftPickerMenu.presents(true, List.of(Collectors.ROWS.get(1))).stream().map(Prize::id).toList(),
                "November: the cap is off sale, the trail can still be given");
    }

    @Test
    void onlyGiftableCollectorsArePresents() {
        List<Prize> mixed = new ArrayList<>();
        mixed.add(row("trail_heart", PrizeType.TRAIL));
        mixed.add(row("hat_creeper", PrizeType.HAT));
        mixed.add(notGiftable());
        mixed.addAll(Collectors.ROWS);
        assertEquals(List.of("cap_pumpkin_2026", "trail_leaves_2026", "cap_santa_2026", "trail_twinkle_2026"),
                GiftPickerMenu.presents(true, mixed).stream().map(Prize::id).toList(),
                "a config row is never giftable in this slice, nor a collector marked not giftable");
    }

    /** The counter's entry names no season, so it is true in October and in December alike. */
    @Test
    void theEntrySaysACollectorForSomeoneOnline() {
        assertArrayEquals(new String[] {"&7Buy a collector", "&7for someone who's online."},
                PrizeCounterMenu.giftEntryLore());
        for (String line : PrizeCounterMenu.giftEntryLore()) {
            assertFalse(line.contains("Halloween") || line.contains("Christmas"), line);
        }
    }

    @Test
    void presentTilesSitCentredOnTheMiddleRow() {
        assertArrayEquals(new int[0], GiftPickerMenu.rowSlots(0));
        assertArrayEquals(new int[] {13}, GiftPickerMenu.rowSlots(1));
        assertArrayEquals(new int[] {12, 14}, GiftPickerMenu.rowSlots(2), "today's two presents");
        assertArrayEquals(new int[] {11, 13, 15}, GiftPickerMenu.rowSlots(3));
        assertArrayEquals(new int[] {9, 11, 13, 15, 17}, GiftPickerMenu.rowSlots(5));
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15}, GiftPickerMenu.rowSlots(6));
        assertArrayEquals(new int[] {9, 10, 11, 12, 13, 14, 15, 16, 17}, GiftPickerMenu.rowSlots(12),
                "never past the row");
        for (int n = 0; n <= 12; n++) {
            for (int slot : GiftPickerMenu.rowSlots(n)) {
                assertTrue(slot >= 9 && slot <= 17, n + " presents: slot " + slot);
                assertTrue(slot != 4 && slot != 22, "never on the header or Back");
            }
        }
    }

    @Test
    void thePickerListsEveryoneTheGiverCanSeeButThemselves() {
        assertTrue(GiftPickerMenu.listable(false, true));
        assertFalse(GiftPickerMenu.listable(true, true), "no \"For me\" tile: buying for yourself is the collector's own tile");
        assertFalse(GiftPickerMenu.listable(false, false), "a vanished admin is never listed (E24)");
    }

    /** A receiver who can get the cap, with the giver's side fine too. */
    private static final class Facts {
        boolean economyWorld = true;
        boolean gamesWorld = false;
        boolean inGame = false;
        boolean hasIt = false;
        boolean cap = true;
        boolean headEmpty = true;
        boolean bagRoom = true;

        GiftFacts facts() {
            return new GiftFacts(true, true, true, true, true, true, false, economyWorld, gamesWorld, inGame,
                    hasIt, cap, headEmpty, bagRoom, 300, 250, NAME, "Pumpkin Cap 2026");
        }

        String note() {
            return GiftPickerMenu.note(facts());
        }
    }

    @Test
    void whySomeoneCantGetItIsInTheirTilesName() {
        Facts f = new Facts();
        assertNull(f.note());
        assertEquals("&aFriend", GiftPickerMenu.tileName(NAME, f.note()), "green: the only tiles that can be tapped");

        f = new Facts();
        f.hasIt = true;
        assertEquals("has it", f.note());
        assertEquals("&7Friend &8- has it", GiftPickerMenu.tileName(NAME, f.note()));

        f = new Facts();
        f.headEmpty = false;
        f.bagRoom = false;
        assertEquals("&7Friend &8- bag full", GiftPickerMenu.tileName(NAME, f.note()));

        for (int i = 0; i < 3; i++) {
            f = new Facts();
            f.economyWorld = i != 0;
            f.gamesWorld = i == 1;
            f.inGame = i == 2;
            assertEquals("&7Friend &8- not here", GiftPickerMenu.tileName(NAME, f.note()),
                    "another world, a Games world, or in a game (E10, E22)");
        }
    }

    @Test
    void theNotesFollowTheGiftChecksOrder() {
        Facts f = new Facts();
        f.inGame = true;
        f.hasIt = true;
        f.headEmpty = false;
        f.bagRoom = false;
        assertEquals("not here", f.note(), "where they are is checked before what they have");
        f.inGame = false;
        assertEquals("has it", f.note(), "having it is checked before room");
    }

    @Test
    void roomMattersOnlyForACapAndOnlyWhenTheHeadIsTaken() {
        Facts f = new Facts();
        f.bagRoom = false;
        assertNull(f.note(), "an empty head takes the cap, whatever the bag");
        f = new Facts();
        f.headEmpty = false;
        assertNull(f.note(), "a worn helmet swaps into one free slot");
        f = new Facts();
        f.cap = false;
        f.headEmpty = false;
        f.bagRoom = false;
        assertNull(f.note(), "a trail needs no room");
    }

    @Test
    void aGreenTileIsExactlyAReceiverTheGiftWouldTake() {
        // Every combination of the receiver's own facts, with the giver's side passing.
        for (int bits = 0; bits < 128; bits++) {
            Facts f = new Facts();
            f.economyWorld = (bits & 1) != 0;
            f.gamesWorld = (bits & 2) != 0;
            f.inGame = (bits & 4) != 0;
            f.hasIt = (bits & 8) != 0;
            f.cap = (bits & 16) != 0;
            f.headEmpty = (bits & 32) != 0;
            f.bagRoom = (bits & 64) != 0;
            String note = f.note();
            String refusal = PrizeService.giftRefusal(f.facts());
            assertEquals(note == null, refusal == null,
                    "bits " + Integer.toBinaryString(bits) + ": the tile says " + note + ", the gift says " + refusal);
            if (note != null) {
                assertNotNull(refusal);
                assertTrue(refusal.startsWith(NAME), "a receiver's refusal names them: " + refusal);
            }
        }
    }

    @Test
    void peopleComeThirtySixAPage() {
        assertEquals(1, GiftPickerMenu.pages(0));
        assertEquals(1, GiftPickerMenu.pages(36));
        assertEquals(2, GiftPickerMenu.pages(37), "more than 36: the arrows");
        assertEquals(0, GiftPickerMenu.clampPage(-1, 10));
        assertEquals(0, GiftPickerMenu.clampPage(1, 36), "someone left: back to the page there is");
        assertEquals(1, GiftPickerMenu.clampPage(1, 37));
        assertEquals(1, GiftPickerMenu.clampPage(5, 40));
    }
}
