package com.dierks.homecraft.gui.games.event;

import com.dierks.homecraft.games.gen.api.GenCopy;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Race Night screen's layout (EVENTS-DROPPER-SPEC §A.6), with no server. Pinned here: every
 * build paints all 27 slots, each once; the way out is at 22; the key facts are in the item NAMES
 * (the start, the track and its races, the prizes or "Just for fun tonight", how many are in, when
 * joining opens, the last winner, your season points, the news toggle) because Bedrock shows lore
 * only on tap-and-hold; the Join tile follows the night (join, leave, opens at, full, started, none);
 * and every line uses glyphs Bedrock can draw and none of the words the games never say.
 */
class RaceNightMenuTest {

    private static RaceNightMenu.View view(RaceNightMenu.Join join, boolean prizeNight) {
        return new RaceNightMenu.View("Fri 7:00 PM", "Joining is open: 3 racers in so far.", "Ice Boat", 3, 2,
                List.of(5, 3, 2), 1, prizeNight, join, 3, 8, "6:50 PM", false, "Sam", "rnnight:rn-20260925-1900",
                "October", "rnseason:2026-10", 12, true);
    }

    private static String name(List<RaceNightMenu.Tile> tiles, int slot) {
        return tiles.get(slot).name();
    }

    @Test
    void everySlotIsPaintedOnceAndTheWayOutIsAt22() {
        for (RaceNightMenu.Join j : RaceNightMenu.Join.values()) {
            List<RaceNightMenu.Tile> tiles = RaceNightMenu.tiles(view(j, true), true);
            assertEquals(RaceNightMenu.SIZE, tiles.size(), j + ": 27 slots");
            Set<Integer> slots = new HashSet<>();
            for (int i = 0; i < tiles.size(); i++) {
                assertEquals(i, tiles.get(i).slot(), "in slot order");
                assertTrue(slots.add(tiles.get(i).slot()), "each slot once");
                assertTrue(tiles.get(i).material() != null && tiles.get(i).name() != null, "slot " + i + " painted");
            }
            assertEquals(Material.BARRIER, tiles.get(RaceNightMenu.EXIT).material(), "the way out at 22");
            assertEquals("&cBack", name(tiles, RaceNightMenu.EXIT), "Back when there is somewhere to go back to");
        }
        assertEquals("&cClose", name(RaceNightMenu.tiles(view(RaceNightMenu.Join.OPEN, true), false),
                RaceNightMenu.EXIT), "Close otherwise");
    }

    @Test
    void theClubhouseItemsHaveSlotsOfTheirOwnOnFiller() {
        // WP-CH: the screen paints "Wait in / Watch from the Clubhouse" at 24 and "Take a rider" at 25 over
        // its layout, so in every state those two are filler in the layout and cover none of its tiles
        assertEquals(24, RaceNightMenu.CLUB, "the Clubhouse at 24");
        assertEquals(25, RaceNightMenu.RIDER, "Take a rider at 25");
        for (RaceNightMenu.Join j : RaceNightMenu.Join.values()) {
            for (boolean prizes : new boolean[]{true, false}) {
                List<RaceNightMenu.Tile> tiles = RaceNightMenu.tiles(view(j, prizes), true);
                for (int slot : new int[]{RaceNightMenu.CLUB, RaceNightMenu.RIDER}) {
                    assertEquals(Material.GRAY_STAINED_GLASS_PANE, tiles.get(slot).material(),
                            j + ": slot " + slot + " is filler in the layout, free for the Clubhouse");
                }
            }
        }
    }

    @Test
    void theFactsAreInTheNames() {
        List<RaceNightMenu.Tile> t = RaceNightMenu.tiles(view(RaceNightMenu.Join.OPEN, true), true);
        assertEquals("&6Race Night &7- Fri 7:00 PM", name(t, RaceNightMenu.HEADER), "when");
        assertEquals(Material.CLOCK, t.get(RaceNightMenu.HEADER).material(), "a clock");
        assertEquals("&bIce Boat &7- 3 races, 2 laps", name(t, RaceNightMenu.TRACK), "the track and its races");
        assertEquals(Material.OAK_BOAT, t.get(RaceNightMenu.TRACK).material(), "a boat");
        assertEquals("&6Prizes &7- 5, 3, 2 tokens", name(t, RaceNightMenu.PRIZES), "the prizes");
        assertTrue(t.get(RaceNightMenu.PRIZES).lore().contains("&72nd needs 3 racers, 3rd needs 4."),
                "the podium rule in the lore");
        assertTrue(t.get(RaceNightMenu.PRIZES).lore().contains("&7Free to enter: nobody loses tokens."), "free entry");
        assertEquals("&aJoin Race Night &7- 3 of 8 in", name(t, RaceNightMenu.JOIN), "join, and how many are in");
        assertEquals(Material.LIME_CONCRETE, t.get(RaceNightMenu.JOIN).material(), "a green join");
        assertEquals("&bLast Race Night &7- won by Sam", name(t, RaceNightMenu.LAST), "the last winner");
        assertEquals("&6Season points &7- you: 12", name(t, RaceNightMenu.SEASON), "your season points");
        assertEquals("&7Race news: &aon", name(t, RaceNightMenu.NEWS), "the news toggle");
        assertEquals(Material.BELL, t.get(RaceNightMenu.NEWS).material(), "a bell");
        assertEquals(Material.SPYGLASS, t.get(RaceNightMenu.WATCH).material(), "watch");
    }

    @Test
    void aNightWithNoSlotLeftIsJustForFun() {
        List<RaceNightMenu.Tile> t = RaceNightMenu.tiles(view(RaceNightMenu.Join.OPEN, false), true);
        assertEquals("&7Just for fun tonight", name(t, RaceNightMenu.PRIZES), "no tokens tonight, said in the name");
        assertFalse(String.join(" ", t.get(RaceNightMenu.PRIZES).lore()).contains("5, 3, 2"), "no prize amounts");
    }

    @Test
    void theJoinTileFollowsTheNight() {
        assertEquals("&cLeave the race list &7- 3 of 8 in",
                name(RaceNightMenu.tiles(view(RaceNightMenu.Join.IN, true), true), RaceNightMenu.JOIN), "in: leave");
        assertEquals("&7Joining opens at 6:50 PM",
                name(RaceNightMenu.tiles(view(RaceNightMenu.Join.SOON, true), true), RaceNightMenu.JOIN), "soon");
        assertEquals("&7Race Night is full &8(8)",
                name(RaceNightMenu.tiles(view(RaceNightMenu.Join.FULL, true), true), RaceNightMenu.JOIN), "full");
        assertEquals("&7The racing has started",
                name(RaceNightMenu.tiles(view(RaceNightMenu.Join.STARTED, true), true), RaceNightMenu.JOIN), "started");
        assertEquals("&7No Race Night set yet",
                name(RaceNightMenu.tiles(view(RaceNightMenu.Join.NONE, true), true), RaceNightMenu.JOIN), "none");
        RaceNightMenu.View nothing = new RaceNightMenu.View(null, null, null, 3, 0, List.of(5, 3, 2), 1, true,
                RaceNightMenu.Join.NONE, 0, 8, null, false, null, null, null, null, 0, false);
        List<RaceNightMenu.Tile> t = RaceNightMenu.tiles(nothing, false);
        assertEquals("&6Race Night &7- no race set", name(t, RaceNightMenu.HEADER), "no night set");
        assertEquals("&bThe track &7- picked on the night", name(t, RaceNightMenu.TRACK), "no track yet");
        assertEquals("&7Last Race Night &8- none yet", name(t, RaceNightMenu.LAST), "no last night");
        assertEquals("&7Season points &8- off", name(t, RaceNightMenu.SEASON), "the season off");
        assertEquals("&7Race news: &coff", name(t, RaceNightMenu.NEWS), "news off");
    }

    @Test
    void everyLineIsKidSafeAndDrawable() {
        for (RaceNightMenu.Join j : RaceNightMenu.Join.values()) {
            for (boolean prizes : new boolean[]{true, false}) {
                for (RaceNightMenu.Tile tile : RaceNightMenu.tiles(view(j, prizes), true)) {
                    assertEquals(List.of(), GenCopy.copyProblems(tile.name()), "slot " + tile.slot() + ": " + tile.name());
                    for (String l : tile.lore()) {
                        assertEquals(List.of(), GenCopy.copyProblems(l), "slot " + tile.slot() + " lore: " + l);
                        assertFalse(l.toLowerCase().matches(".*\\b(bet|wager|gamble|casino|lucky|almost|sink)\\b.*"),
                                "never: " + l);
                    }
                }
            }
        }
    }
}
