package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.api.Stars;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Today's Courses and the parkour tier picker (GEN-SPEC §5.4, §7), with no server.
 *
 * <p>Pinned here: a tile's NAME carries the stars the player has today (Bedrock shows lore only on
 * tap-and-hold), in the slot's tier colour, and a golf tile's name its holes and par; a course
 * that can't be played reads "being built, back soon" in grey; every name uses glyphs Bedrock can
 * draw (nothing above U+FFFF) and none of the words the games never say; the tiles sit centred in
 * the middle row in slot order, the tier picker easiest first; an enabled slot shows even before
 * it is built while the shipped-off ice boat stays hidden until it is made; and each tile is its
 * colour's concrete.
 */
class TodayMenuTest {

    @Test
    void theNameCarriesTheStarsTheColourAndForGolfTheHolesAndPar() {
        for (Slots.Def slot : Slots.ALL) {
            for (int stars = 1; stars <= Stars.MAX; stars++) {
                String name = DailyTiles.name(slot, true, stars, slot.plots(), 29);
                assertTrue(name.contains(Stars.text(stars)), slot.id() + " with " + stars + " stars says so in its NAME: "
                        + name);
                assertTrue(name.startsWith(slot.colour() + slot.name()), "in the tier colour, the slot's name first: "
                        + name);
            }
            String none = DailyTiles.name(slot, true, 0, slot.plots(), 29);
            assertFalse(none.contains("★") || none.contains("☆"), "no stars yet: no star glyphs: " + none);
        }
        assertEquals("&aEasy Parkour &7- ★★☆", DailyTiles.name(Slots.DAILY_PARKOUR_EASY, true, 2, 0, 0),
                "the spec's own example");
        assertEquals("&cHard Parkour", DailyTiles.name(Slots.DAILY_PARKOUR_HARD, true, 0, 0, 0), "and before a finish");
        assertEquals("&dDaily Golf &7- 9 holes, par 29", DailyTiles.name(Slots.DAILY_GOLF, true, 0, 9, 29),
                "golf says its holes and par");
        assertEquals("&dTiny Golf &7- 3 holes, par 8 ★★★", DailyTiles.name(Slots.TINY_GOLF, true, 3, 3, 8),
                "then its stars");
    }

    @Test
    void aCourseThatCantBePlayedSaysItIsBeingBuilt() {
        assertEquals("&7Sky Rings &8- being built, back soon", DailyTiles.name(Slots.SKY_RINGS, false, 3, 0, 0),
                "grey, and no stars on a closed tile");
        assertEquals(GenCopy.building("Sky Rings"), DailyText.closedName(Slots.SKY_RINGS),
                "the same words as the rest of Daily Courses");
    }

    @Test
    void everyNameIsKidSafeAndDrawableOnBedrock() {
        List<String> names = new ArrayList<>();
        for (Slots.Def slot : Slots.ALL) {
            for (int stars = 0; stars <= Stars.MAX; stars++) {
                names.add(DailyTiles.name(slot, true, stars, slot.plots(), 29));
            }
            names.add(DailyTiles.name(slot, false, 0, 0, 0));
        }
        names.add(TodayMenu.headerName(20_725));
        for (String name : names) {
            assertTrue(name.codePoints().allMatch(cp -> cp <= 0xFFFF), "Bedrock draws every glyph of: " + name);
            assertEquals(List.of(), GenCopy.copyProblems(name), "kid-safe words: " + name);
        }
        assertEquals("&eToday's Courses &7- Tue 29 Sep", TodayMenu.headerName(20_725), "the header names the day");
    }

    @Test
    void theTilesSitCentredInTheMiddleRowAndTheTiersEasiestFirst() {
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15}, TodayMenu.row(6), "six courses, shipped");
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15, 16}, TodayMenu.row(7), "seven with the ice boat");
        assertArrayEquals(new int[]{13}, TodayMenu.row(1), "one in the middle");
        assertArrayEquals(new int[0], TodayMenu.row(0), "none: nothing");
        assertEquals(List.of(Slots.DAILY_PARKOUR_EASY, Slots.DAILY_PARKOUR_MEDIUM, Slots.DAILY_PARKOUR_HARD),
                TierMenu.TIERS, "easy, then medium, then hard");
        assertArrayEquals(new int[]{11, 13, 15}, TierMenu.AT, "side by side in the middle row");
    }

    @Test
    void anEnabledSlotShowsBeforeItIsBuiltAndTheIceBoatOnlyOnceMade() {
        assertTrue(DailyTiles.shown(Slots.SKY_RINGS, false, false), "shipped on: shown as being built");
        assertFalse(DailyTiles.shown(Slots.ICE_BOAT, false, false), "shipped off and never made: hidden");
        assertTrue(DailyTiles.shown(Slots.ICE_BOAT, true, false), "switched on and made once: shown");
        assertTrue(DailyTiles.shown(Slots.ICE_BOAT, true, true), "and open");
        assertFalse(DailyTiles.shown(null, true, true), "no slot: nothing");
    }

    @Test
    void eachTileIsItsColoursConcrete() {
        assertEquals(Material.LIME_CONCRETE, DailyTiles.icon(Slots.DAILY_PARKOUR_EASY), "green for easy");
        assertEquals(Material.YELLOW_CONCRETE, DailyTiles.icon(Slots.DAILY_PARKOUR_MEDIUM), "yellow for medium");
        assertEquals(Material.RED_CONCRETE, DailyTiles.icon(Slots.DAILY_PARKOUR_HARD), "red for hard");
        assertEquals(Material.LIGHT_BLUE_CONCRETE, DailyTiles.icon(Slots.SKY_RINGS), "light blue for the rings");
        assertEquals(Material.MAGENTA_CONCRETE, DailyTiles.icon(Slots.DAILY_GOLF), "magenta for golf");
    }
}
