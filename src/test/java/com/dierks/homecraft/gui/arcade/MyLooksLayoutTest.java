package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.looks.LookRules.Window;
import com.dierks.homecraft.arcade.looks.LookService.CapWhere;
import com.dierks.homecraft.gui.arcade.MyLooksMenu.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * My Looks' layout (SHOP-V2-SPEC §3.11): 16 hat and 16 trail tiles, the two section panes, the arrows only when a
 * section has more, and every tile name it can draw (and the counter's rental tiles, §3.12) reads to a child and on a
 * Bedrock screen: no admin words (PlayerCopyTest's list) and nothing above U+FFFF (BedrockGlyphTest).
 */
class MyLooksLayoutTest {

    private static final Pattern[] BANNED = {Pattern.compile("(?i)\\bsink"), Pattern.compile("(?i)\\bburned\\b"),
            Pattern.compile("(?i)\\(tag:"), Pattern.compile("\\barcade\\.[a-z_]")};

    @Test
    void sixteenHatsAndSixteenTrailsAPage() {
        assertEquals(16, MyLooksMenu.HAT_SLOTS.length);
        assertEquals(16, MyLooksMenu.TRAIL_SLOTS.length);
        Set<Integer> seen = new HashSet<>();
        for (int s : MyLooksMenu.HAT_SLOTS) {
            assertTrue(seen.add(s));
            assertTrue((s >= 10 && s <= 17) || (s >= 19 && s <= 26), "hats in rows 1-2, after the pane: " + s);
        }
        for (int s : MyLooksMenu.TRAIL_SLOTS) {
            assertTrue(seen.add(s));
            assertTrue((s >= 28 && s <= 35) || (s >= 37 && s <= 44), "trails in rows 3-4, after the pane: " + s);
        }
        for (int fixed : new int[] {MyLooksMenu.HEAD, 4, MyLooksMenu.TRAIL, 9, 27, 45, 46,
                MyLooksMenu.GET_LOOKS, 49, MyLooksMenu.GET_TRAILS, 53}) {
            assertFalse(seen.contains(fixed), "no tile on slot " + fixed);
        }
        assertEquals(2, MyLooksMenu.HEAD);
        assertEquals(6, MyLooksMenu.TRAIL);
        assertTrue(MyLooksMenu.GET_LOOKS > 45 && MyLooksMenu.GET_TRAILS < 53 && MyLooksMenu.GET_LOOKS != 49
                && MyLooksMenu.GET_TRAILS != 49, "the bottom row: the arrows on 45/53, the way out on 49");
    }

    @Test
    void theArrowsOnlyWhenNeeded() {
        assertEquals(1, MyLooksMenu.pages(0, 0), "nothing yet: one page, no arrows");
        assertEquals(1, MyLooksMenu.pages(16, 16), "a full page of each still fits");
        assertEquals(2, MyLooksMenu.pages(17, 0), "more hats than fit");
        assertEquals(2, MyLooksMenu.pages(3, 20), "or more trails: one page number pages both");
        assertEquals(3, MyLooksMenu.pages(33, 1));
    }

    @Test
    void theDaysLeftAreTheStackCount() {
        assertEquals(6, MyLooksMenu.amount(State.OPEN, 6), "a few days left: the number both editions draw");
        assertEquals(7, MyLooksMenu.amount(State.OPEN, 9));
        assertEquals(1, MyLooksMenu.amount(State.OPEN, 0));
        assertEquals(1, MyLooksMenu.amount(State.LIVE, 40), "only while buy again is open");
    }

    @Test
    void theTileNamesTellTheState() {
        LocalDate jan18 = LocalDate.of(2027, 1, 18);
        assertEquals("&aLime Cap &a✔ on your head", MyLooksMenu.tileName(true, State.WORN, "Lime Cap", jan18, 90));
        assertEquals("&aSnow Trail &a✔ on", MyLooksMenu.tileName(false, State.WORN, "Snow Trail", jan18, 9));
        assertEquals("&fLime Cap &7- until 18 Jan", MyLooksMenu.tileName(true, State.LIVE, "Lime Cap", jan18, 90));
        assertEquals("&eLime Cap &e- 6 days left", MyLooksMenu.tileName(true, State.OPEN, "Lime Cap", jan18, 6));
        assertEquals("&7Lime Cap &8- ended", MyLooksMenu.tileName(true, State.ENDED, "Lime Cap", jan18, 0));
        assertEquals("&6Royal Trail &7- yours for good", MyLooksMenu.tileName(false, State.KEPT, "Royal Trail", jan18, 0));
        assertEquals("&6Pumpkin Cap 2026 &7- yours for good, in your ender chest",
                MyLooksMenu.capName("Pumpkin Cap 2026", CapWhere.ENDER_CHEST));
        assertEquals("&6Pumpkin Cap 2026 &7- yours for good, not with you",
                MyLooksMenu.capName("Pumpkin Cap 2026", CapWhere.AWAY));
        assertEquals("&dCreeper Hat &7- &6120 tokens", PrizeCounterMenu.rentalName("&dCreeper Hat", Window.NEW, 120, null, 0));
        assertEquals("&dCreeper Hat &7- &aYours until 18 Jan",
                PrizeCounterMenu.rentalName("&dCreeper Hat", Window.LIVE, 120, jan18, 60));
        assertEquals("&dCreeper Hat &7- &eBuy again: 120 tokens (6 days left)",
                PrizeCounterMenu.rentalName("&dCreeper Hat", Window.OPEN, 120, jan18, 6));
        assertEquals("&dCreeper Hat &7- &eBuy again: 120 tokens (ended)",
                PrizeCounterMenu.rentalName("&dCreeper Hat", Window.OPEN, 120, jan18, 0));
        assertEquals("&dCreeper Hat &7- &aYours for good", PrizeCounterMenu.rentalName("&dCreeper Hat", Window.KEPT, 120,
                jan18, 0));
    }

    /**
     * A first buy's lines (§3.12) after the row's own: the 0.38 hat and cap rows already say "Yours for 90 days.", so
     * the tile says it once; a trail's row says "for a month.", so its tile adds its days.
     */
    @Test
    void aFirstBuyTileSaysHowLongItLastsOnce() {
        List<String> cap = new ArrayList<>(List.of("&7A comfy cap to wear!", "&7Yours for 90 days."));
        PrizeCounterMenu.firstBuyLines(cap, 90);
        assertEquals(List.of("&7A comfy cap to wear!", "&7Yours for 90 days.", "&7Then you can buy it again."), cap);
        List<String> trail = new ArrayList<>(List.of("&7Snowflakes follow you", "&7for a month."));
        PrizeCounterMenu.firstBuyLines(trail, 30);
        assertEquals(List.of("&7Snowflakes follow you", "&7for a month.", "&7Yours for 30 days.",
                "&7Then you can buy it again."), trail);
    }

    @Test
    void everyNameReadsToAChildAndOnBedrock() {
        List<String> names = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 12, 24);
        for (State s : State.values()) {
            for (boolean hat : new boolean[] {true, false}) {
                names.add(MyLooksMenu.tileName(hat, s, "Star Trail", day, 3));
            }
        }
        for (CapWhere w : CapWhere.values()) {
            names.add(MyLooksMenu.capName("Santa Cap 2026", w));
        }
        for (Window w : Window.values()) {
            names.add(PrizeCounterMenu.rentalName("&eStar Trail", w, 100, day, 3));
        }
        // S4: the hide-others switch (slot 46) and the Keepers shelf's tiles
        names.add(MyLooksMenu.hideName(true));
        names.add(MyLooksMenu.hideName(false));
        for (boolean owned : new boolean[] {true, false}) {
            for (boolean on : new boolean[] {true, false}) {
                names.add(PrizeCounterMenu.keeperName("&6&lLegend Crown", owned, on, 5000));
            }
        }
        names.addAll(com.dierks.homecraft.arcade.PrizeItems.keeperLines());
        for (String n : names) {
            for (Pattern p : BANNED) {
                assertFalse(p.matcher(n).find(), "admin words in a player's tile: " + n);
            }
            assertTrue(n.codePoints().allMatch(cp -> cp <= 0xFFFF), "Bedrock can't draw it: " + n);
        }
    }

    /**
     * Slot 46 (SHOP-V2-SPEC §5.6, red team O21): the hide-others switch, in the bottom row away from the top row a
     * child taps first; its NAME says what it is now and what a tap does.
     */
    @Test
    void theHideSwitchIsInTheBottomRowAndSaysWhatATapDoes() {
        assertEquals(46, MyLooksMenu.HIDE_OTHERS);
        assertTrue(MyLooksMenu.HIDE_OTHERS > 45 && MyLooksMenu.HIDE_OTHERS < MyLooksMenu.GET_LOOKS,
                "between the Previous arrow and Get more looks");
        assertEquals("&fOther people's trails and parties: Shown - tap to hide", MyLooksMenu.hideName(false));
        assertEquals("&fOther people's trails and parties: Hidden - tap to show", MyLooksMenu.hideName(true));
    }

    /** The Keepers shelf (§5.1): the Crown on top, the trails, the hats, My Looks; the facts in each tile's NAME. */
    @Test
    void theKeepersShelf() {
        assertEquals(java.util.Map.of("keeper_legend_crown", 13, "keeper_trail_aurora", 20, "keeper_trail_stardust", 22,
                "keeper_trail_frost", 24, "keeper_wizard_hat", 30, "keeper_knight_helm", 32), PrizeCounterMenu.KEEPER_SLOTS);
        assertEquals(40, PrizeCounterMenu.KEEPER_MY_LOOKS);
        assertFalse(PrizeCounterMenu.KEEPER_SLOTS.containsKey("keeper_trail_royal"), "the Royal Trail is no tile");
        Set<Integer> used = new HashSet<>(PrizeCounterMenu.KEEPER_SLOTS.values());
        used.add(PrizeCounterMenu.KEEPER_MY_LOOKS);
        for (int nav : new int[] {45, 47, 49, 53}) {
            assertFalse(used.contains(nav), "the bottom row stays the counter's: " + nav);
        }
        for (int tab = 0; tab < 9; tab++) {
            assertFalse(used.contains(tab), "row 0 is the balance and the tabs");
        }
        assertEquals("&6&lLegend Crown &7- &65000 tokens", PrizeCounterMenu.keeperName("&6&lLegend Crown", false, false,
                5000));
        assertEquals("&5Wizard Hat &7- &aYours! Tap to wear it", PrizeCounterMenu.keeperName("&5Wizard Hat", true,
                false, 2500));
        assertEquals("&bFrost Trail &7- &a✔ Wearing it", PrizeCounterMenu.keeperName("&bFrost Trail", true, true, 3000));
    }
}
