package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
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
 * The Fresh Courses screen and the parkour level picker (GEN-SPEC §5.4, §7, the weekly addendum
 * §2, GEN-SPEC-KEEP §3 and §8), with no server.
 *
 * <p>Pinned here: a tile's NAME carries the stars the player has in this set (Bedrock shows lore
 * only on tap-and-hold), in the slot's tier colour, a golf tile's name its holes and par, and the
 * course code at the end; golf's big course is named for the cadence; a course that can't be
 * played reads "being built, back soon" in grey; the title and header follow the cadence ("This
 * week's courses - Mon 28 Sep-Sun 4 Oct"); the Classics tiles say whether a slot holds a course,
 * is being built or is empty (with the tip); every name uses glyphs Bedrock can draw (nothing above
 * U+FFFF) and none of the words the games never say; the tiles sit centred in the second row in
 * slot order, the tier picker easiest first; an enabled slot shows even before it is built while
 * the shipped-off ice boat stays hidden until it is made; and each tile is its colour's concrete.
 */
class FreshMenuTest {

    @Test
    void theNameCarriesTheStarsTheColourAndForGolfTheHolesAndPar() {
        for (Slots.Def slot : Slots.ALL) {
            for (int stars = 1; stars <= Stars.MAX; stars++) {
                String name = DailyTiles.name(slot, 7, true, stars, slot.plots(), 29, null);
                assertTrue(name.contains(Stars.text(stars)), slot.id() + " with " + stars + " stars says so in its NAME: "
                        + name);
                assertTrue(name.startsWith(slot.colour() + slot.name()), "in the tier colour, the slot's name first: "
                        + name);
            }
            String none = DailyTiles.name(slot, 7, true, 0, slot.plots(), 29, null);
            assertFalse(none.contains("★") || none.contains("☆"), "no stars yet: no star glyphs: " + none);
        }
        assertEquals("&aEasy Parkour &7- ★★☆", DailyTiles.name(Slots.DAILY_PARKOUR_EASY, 7, true, 2, 0, 0, null),
                "the spec's own example");
        assertEquals("&cHard Parkour", DailyTiles.name(Slots.DAILY_PARKOUR_HARD, 7, true, 0, 0, 0, null),
                "and before a finish");
        assertEquals("&dGolf of the Week &7- 9 holes, par 29", DailyTiles.name(Slots.DAILY_GOLF, 7, true, 0, 9, 29,
                null), "golf says its holes and par");
        assertEquals("&dTiny Golf &7- 3 holes, par 8 ★★★", DailyTiles.name(Slots.TINY_GOLF, 7, true, 3, 3, 8, null),
                "then its stars");
        assertEquals("&aEasy Dropper &7- 3 levels · ★★☆", DailyTiles.name(Slots.EASY_DROPPER, 7, true, 2, 3, 0, null),
                "a dropper says its levels, then its stars (EVENTS-DROPPER-SPEC §B.1.8)");
        assertEquals("&9Dropper &7- 5 levels &8· &7Course code DROP-12", DailyTiles.name(Slots.FRESH_DROPPER, 7, true,
                0, 5, 0, "DROP-12"), "and its course code in the NAME, for Bedrock");
        assertEquals(Material.BLUE_CONCRETE, DailyTiles.icon(Slots.FRESH_DROPPER), "the Dropper's tile is blue");
        assertEquals(Material.LIME_CONCRETE, DailyTiles.icon(Slots.EASY_DROPPER), "Easy Dropper's is easy's green");
    }

    @Test
    void theCourseCodeEndsTheNameForBedrock() {
        assertEquals("&cHard Parkour &7- ★★☆ &8· &7Course code HARD-40",
                DailyTiles.name(Slots.DAILY_PARKOUR_HARD, 7, true, 2, 0, 0, "HARD-40"),
                "the key facts, then the code, all in the NAME");
        assertEquals("&dGolf of the Week &7- 9 holes, par 29 &8· &7Course code GOLF-3",
                DailyTiles.name(Slots.DAILY_GOLF, 7, true, 0, 9, 29, "GOLF-3"), "golf too");
        assertEquals("&cHard Parkour", DailyTiles.name(Slots.DAILY_PARKOUR_HARD, 7, true, 0, 0, 0, null),
                "a set from before the archive has no code: none is shown");
        assertEquals("", DailyLookup.codeSuffix(" "), "a blank code is no code");
        assertEquals("&7Sky Rings &8- being built, back soon",
                DailyTiles.name(Slots.SKY_RINGS, 7, false, 0, 0, 0, "RINGS-2"), "a closed tile names no code");
    }

    @Test
    void golfsBigCourseIsNamedForTheCadence() {
        assertEquals("&dGolf of the Day &7- 9 holes, par 29", DailyTiles.name(Slots.DAILY_GOLF, 1, true, 0, 9, 29,
                null), "daily: Golf of the Day");
        assertEquals("&dFresh Golf &7- 9 holes, par 29", DailyTiles.name(Slots.DAILY_GOLF, 3, true, 0, 9, 29, null),
                "every 3 days: Fresh Golf");
        assertEquals("&7Golf of the Day &8- being built, back soon", DailyTiles.name(Slots.DAILY_GOLF, 1, false, 0, 0,
                0, null), "and while it is being built");
        assertEquals("&aEasy Parkour", DailyTiles.name(Slots.DAILY_PARKOUR_EASY, 1, true, 0, 0, 0, null),
                "the others keep their names");
    }

    @Test
    void aCourseThatCantBePlayedSaysItIsBeingBuilt() {
        assertEquals("&7Sky Rings &8- being built, back soon", DailyTiles.name(Slots.SKY_RINGS, 7, false, 3, 0, 0,
                null), "grey, and no stars on a closed tile");
        assertEquals(GenCopy.building("Sky Rings"), DailyText.closedName(Slots.SKY_RINGS, 7),
                "the same words as the rest of Fresh Courses");
    }

    @Test
    void theTitleAndHeaderFollowTheCadence() {
        assertEquals("&eThis week's courses", FreshMenu.title(7), "weekly, shipped");
        assertEquals("&eToday's courses", FreshMenu.title(1), "daily");
        assertEquals("&eThe current courses", FreshMenu.title(3), "every 3 days");
        assertEquals("&eThis week's courses &7- Mon 28 Sep-Sun 4 Oct", FreshMenu.headerName(7, 20_724),
                "a week shows its first and last day");
        assertEquals("&eToday's courses &7- Tue 29 Sep", FreshMenu.headerName(1, 20_725), "a day, its date");
        assertEquals("&eThe current courses &7- Mon 28 Sep-Wed 30 Sep", FreshMenu.headerName(3, 20_724),
                "three days, the first and the last");
        assertEquals("&7No courses yet", FreshMenu.NONE_YET, "never 'daily'");
        assertEquals("&eParkour Levels", "&e" + TierMenu.NAME, "the picker is the playable's name");
    }

    @Test
    void aClassicsSlotHoldsACourseIsBeingBuiltOrIsEmpty() {
        assertEquals(DailyTiles.Classic.HOLDING, DailyTiles.classic(true, false), "holding one");
        assertEquals(DailyTiles.Classic.HOLDING, DailyTiles.classic(true, true), "holding wins");
        assertEquals(DailyTiles.Classic.BUILDING, DailyTiles.classic(false, true), "a recall on its way");
        assertEquals(DailyTiles.Classic.EMPTY, DailyTiles.classic(false, false), "never recalled, or its recall over");
        assertEquals("&7Classic Parkour &8- empty", DailyTiles.emptyClassicName(Slots.CLASSIC_PARKOUR),
                "an empty slot says so in its NAME");
        GenTag hard = new GenTag("fresh_parkour_hard", "parkour", 1, 20_731, 0, 1L, 'A', "abc", 1, 2, 3, List.of(),
                List.of(), 1L, 7, new GenTag.Recall("fresh_classic_parkour", 0L, 20_740));
        assertEquals("&6Classic: Hard Parkour (week of 5 Oct) &8· &7Course code HARD-40",
                DailyTiles.classicName(hard, false, "HARD-40"), "the spec's own example");
        assertEquals("&6Classic: Hard Parkour (week of 5 Oct) (re-made) &8· &7Course code HARD-40",
                DailyTiles.classicName(hard, true, "HARD-40"), "a re-made one says so");
        java.time.ZoneId utc = java.time.ZoneOffset.UTC;
        long mon12 = java.time.LocalDateTime.of(2026, 10, 12, 4, 2).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        assertEquals("&7Back until Mon 12 Oct 4:02 AM", DailyTiles.backUntil(mon12, utc), "its window's end");
        assertEquals("&7Back until an admin closes it", DailyTiles.backUntil(null, utc), "a recall forever");
        for (String line : List.of(DailyTiles.emptyClassicName(Slots.CLASSIC_GOLF), DailyTiles.classicName(hard, true,
                "HARD-40"), GenCopy.CLASSICS_TIP)) {
            assertEquals(List.of(), GenCopy.copyProblems(line), "kid-safe: " + line);
        }
        assertEquals(Slots.CLASSICS.size(), FreshMenu.CLASSICS.length, "one tile per Classics slot (four, Classic Dropper too)");
        assertEquals(4, FreshMenu.CLASSICS.length, "Classic Parkour, Sky Rings, Golf and Dropper");
    }

    @Test
    void everyNameIsKidSafeAndDrawableOnBedrock() {
        List<String> names = new ArrayList<>();
        for (int cadence : new int[]{1, 3, 7, 14}) {
            for (Slots.Def slot : Slots.ALL) {
                for (int stars = 0; stars <= Stars.MAX; stars++) {
                    names.add(DailyTiles.name(slot, cadence, true, stars, slot.plots(), 29, "EASY-1"));
                }
                names.add(DailyTiles.name(slot, cadence, false, 0, 0, 0, null));
            }
            names.add(FreshMenu.headerName(cadence, 20_725));
            names.add(FreshMenu.title(cadence));
        }
        for (Slots.Def classic : Slots.CLASSICS) {
            names.add(DailyTiles.emptyClassicName(classic));
        }
        for (String name : names) {
            assertTrue(name.codePoints().allMatch(cp -> cp <= 0xFFFF), "Bedrock draws every glyph of: " + name);
            assertEquals(List.of(), GenCopy.copyProblems(name), "kid-safe words: " + name);
            assertFalse(name.toLowerCase(java.util.Locale.ROOT).contains("daily"), "never 'daily': " + name);
        }
    }

    @Test
    void theTilesSitCentredInTheSecondRowAndTheTiersEasiestFirst() {
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15}, FreshMenu.row(6), "six courses, shipped");
        assertArrayEquals(new int[]{10, 11, 12, 13, 14, 15, 16}, FreshMenu.row(7), "seven with the ice boat");
        assertArrayEquals(new int[]{13}, FreshMenu.row(1), "one in the middle");
        assertArrayEquals(new int[0], FreshMenu.row(0), "none: nothing");
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
