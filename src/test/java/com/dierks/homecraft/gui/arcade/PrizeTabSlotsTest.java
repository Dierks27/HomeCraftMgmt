package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.Keepers;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.mini.Rarity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slot plan of 0.38's eight Prize Counter tabs (SHOP-V2-SPEC §5.9): the counter's tabs in 1-8 after the
 * balance, the present at 47, the hub's tabs in 19-26 and Card Packs at 35, none on another tile or on the nav
 * anchors {@code NavAnchorTest} pins (45 and 53 the arrows, 49 the way out); and the Keepers tab drawn only while
 * there are Keepers.
 */
class PrizeTabSlotsTest {

    @Test
    void theCountersEightTabs() {
        assertEquals(8, PrizeTab.values().length);
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7, 8}, PrizeCounterMenu.TAB_SLOTS,
                "every tab in row 0, after the balance at 0");
        assertEquals(List.of(PrizeTab.BOOSTS, PrizeTab.HUNT, PrizeTab.COSMETICS, PrizeTab.LOOKS, PrizeTab.PERKS,
                PrizeTab.TROPHIES, PrizeTab.MINIS, PrizeTab.KEEPERS), List.of(PrizeTab.values()), "their order");
        assertEquals("Looks", PrizeTab.LOOKS.label());
        assertEquals("Keepers", PrizeTab.KEEPERS.label());
    }

    @Test
    void thePresentIsAt47AwayFromTheAnchors() {
        assertEquals(47, PrizeCounterMenu.GIFT_SLOT);
        Set<Integer> taken = new HashSet<>(Set.of(0, 45, 49, 53));
        for (int s : PrizeCounterMenu.TAB_SLOTS) {
            assertTrue(taken.add(s), "slot " + s + " is used twice");
        }
        assertTrue(taken.add(PrizeCounterMenu.GIFT_SLOT), "the present is on a slot of its own");
        for (int s = 9; s < 45; s++) {
            assertFalse(taken.contains(s), "the grid (9-44) is the rows'");
        }
    }

    /**
     * The hub's tab slots, read from its source: {@code ArcadeMenu} builds its tiles as it loads, which needs a
     * server, so its array can't be touched here (its {@code int} constants are copied in by the compiler).
     */
    private static int[] hubTabs() throws IOException {
        String src = Files.readString(Path.of("src", "main", "java", "com", "dierks", "homecraft", "gui", "arcade",
                "ArcadeMenu.java"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("static final int\\[] TABS = \\{([^}]*)}").matcher(src);
        assertTrue(m.find(), "ArcadeMenu declares TABS");
        return Arrays.stream(m.group(1).split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }

    @Test
    void theHubsTabsAndPacks() throws IOException {
        int[] tabs = hubTabs();
        assertArrayEquals(new int[] {19, 20, 21, 22, 23, 24, 25, 26}, tabs, "row 2 after its label at 18");
        assertEquals(35, ArcadeMenu.PACKS, "row 3's free end");
        Set<Integer> taken = new HashSet<>(Set.of(ArcadeMenu.WALLET, ArcadeMenu.WILD, 9, 10, 11, 12, 13, 14, 18, 27,
                29, 31, 33, 49));
        for (int s : tabs) {
            assertTrue(taken.add(s), "slot " + s + " is used twice");
        }
        assertTrue(taken.add(ArcadeMenu.PACKS), "Card Packs on a slot of its own");
        assertTrue(ArcadeMenu.PACKS < 36, "and above the Play row (36-43), which is border while the games are off");
    }

    private static PluginConfig.Arcade arcade(List<Prize> prizes) {
        return new PluginConfig.Arcade(true, true, List.of(), true, 60, Map.of(), prizes, 150, Rarity.RARE, 1, null,
                Map.of(), null, Map.of(), true, true);
    }

    @Test
    void theKeepersTabIsDrawnOnlyWhileThereAreKeepers() {
        PluginConfig.Arcade none = arcade(PluginConfig.mergeCollectors(List.of(), null));
        assertEquals(Keepers.ROWS.isEmpty(), !PrizeCounterMenu.tabShown(PrizeTab.KEEPERS, none),
                "the catalogue ships empty in the contract commit: no tab");
        assertFalse(PrizeCounterMenu.tabShown(PrizeTab.KEEPERS, null));
        for (PrizeTab t : PrizeTab.values()) {
            if (t != PrizeTab.KEEPERS) {
                assertTrue(PrizeCounterMenu.tabShown(t, none), t + " is always drawn, empty or not");
            }
        }
        Prize keeper = new Prize("keeper_test", PrizeTab.KEEPERS, "&6Test", List.of(), 2500,
                new PluginConfig.Icon("", org.bukkit.Material.NETHER_STAR), PluginConfig.PrizeType.TRAIL, true, null,
                1, null, null, null, null, 0, 0, "HEART", 36500, "", List.of(), List.of(), null, null, List.of(), null);
        assertTrue(PrizeCounterMenu.tabShown(PrizeTab.KEEPERS, arcade(List.of(keeper))), "with a Keeper, drawn");
    }
}
