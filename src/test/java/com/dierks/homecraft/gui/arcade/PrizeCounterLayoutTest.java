package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dierks.homecraft.config.PluginConfig.PrizeType.BALL;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.BLOCK;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.BOOST;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.COMMAND;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.FIREWORK;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.HAT;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.PARTY;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.QUEST_REROLL;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.RADAR;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.REMOTE;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.TRAIL;
import static com.dierks.homecraft.config.PluginConfig.PrizeType.TROPHY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Prize Counter's shelves by kind (0.38.3, {@link PrizeShelves}): on screen only, each kind from its own row while
 * the tab fits one page, else packed as 0.38.2 did. The shipped shelves on 26 Oct 2026 (the day the owner asked for
 * "a quick reorganization of the Arcade") and in December, the packed fallback, and the bundled config's every row,
 * all switched on, still on one page per tab.
 */
class PrizeCounterLayoutTest {

    /** The counter's grid: four rows of nine (slots 9-44). */
    private static final int ROWS = 4;

    private static int[] range(int from, int to) {
        int[] out = new int[to - from + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i;
        }
        return out;
    }

    private static int[] concat(int[]... parts) {
        List<Integer> all = new ArrayList<>();
        for (int[] p : parts) {
            for (int i : p) {
                all.add(i);
            }
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- the shipped shelves --------------------------------------------------------------------------------

    /** Boosts on 26 Oct 2026, config order: five boosts, the Quest Reroll, two more boosts, Make it Day!, Sunny Day. */
    static final List<PrizeType> BOOSTS_OCT = List.of(BOOST, BOOST, BOOST, BOOST, BOOST, QUEST_REROLL, BOOST, BOOST,
            COMMAND, COMMAND);

    /**
     * Fireworks &amp; Trails on 26 Oct 2026, in counter order: the Autumn Leaves collector (the collectors come first),
     * the Show, Grand, Launcher, Mega, Super, Ultra, Ultimate, Remote, the eight trails, Party Time!.
     */
    static final List<PrizeType> COSMETICS_OCT = List.of(TRAIL, FIREWORK, FIREWORK, BLOCK, FIREWORK, FIREWORK,
            FIREWORK, FIREWORK, REMOTE, TRAIL, TRAIL, TRAIL, TRAIL, TRAIL, TRAIL, TRAIL, TRAIL, PARTY);

    /** Looks on 26 Oct 2026: the Pumpkin Cap, the Creeper and Dragon hats, four caps, then the ten golf balls. */
    static final List<PrizeType> LOOKS_OCT = List.of(HAT, HAT, HAT, HAT, HAT, HAT, HAT,
            BALL, BALL, BALL, BALL, BALL, BALL, BALL, BALL, BALL, BALL);

    @Test
    void boostsThenTheTwoThatChangeTheWorld() {
        assertArrayEquals(concat(range(0, 7), range(9, 10)), PrizeShelves.slots(PrizeTab.BOOSTS, BOOSTS_OCT, ROWS),
                "the boosts and the Quest Reroll on row 1; Make it Day! and Sunny Day on row 2");
    }

    @Test
    void theFireworksOnOneRowAndTheTrailsOnTheNext() {
        int[] slots = PrizeShelves.slots(PrizeTab.COSMETICS, COSMETICS_OCT, ROWS);
        assertArrayEquals(concat(new int[] {9}, range(0, 7), range(10, 17), new int[] {8}), slots,
                "Autumn Leaves first among the trails; Party Time! closes the fireworks' row");
        List<Integer> row1 = new ArrayList<>();
        List<Integer> row2 = new ArrayList<>();
        for (int s : slots) {
            (s < 9 ? row1 : row2).add(s);
        }
        assertEquals(9, row1.size(), "exactly nine firework things");
        assertEquals(9, row2.size(), "exactly nine trails");
    }

    @Test
    void inDecemberTwinkleTakesTheTrailsFirstSlot() {
        // Twinkle 2026 (1 Dec - 6 Jan) is where Autumn Leaves (to 30 Nov) was: the same types, the same slots
        assertArrayEquals(PrizeShelves.slots(PrizeTab.COSMETICS, COSMETICS_OCT, ROWS),
                PrizeShelves.slots(PrizeTab.COSMETICS, onShelf(PrizeTab.COSMETICS, LocalDate.of(2026, 12, 10)), ROWS));
        assertEquals(COSMETICS_OCT, onShelf(PrizeTab.COSMETICS, LocalDate.of(2026, 12, 10)));
        List<PrizeType> noCollector = new ArrayList<>(COSMETICS_OCT);
        noCollector.remove(0);
        assertArrayEquals(concat(range(0, 7), range(9, 16), new int[] {8}),
                PrizeShelves.slots(PrizeTab.COSMETICS, noCollector, ROWS),
                "with no collector in season the trails' row has eight");
    }

    @Test
    void hatsAndCapsThenTheGolfBalls() {
        assertArrayEquals(concat(range(0, 6), range(9, 18)), PrizeShelves.slots(PrizeTab.LOOKS, LOOKS_OCT, ROWS),
                "the hats and caps on row 1; the ten balls from row 2");
    }

    /** The explicit lists above are the bundled config's shelves on 26 Oct 2026, read the way the counter reads them. */
    @Test
    void theListsAreTheBundledShelvesOn26October() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 26);
        assertEquals(BOOSTS_OCT, onShelf(PrizeTab.BOOSTS, day));
        assertEquals(COSMETICS_OCT, onShelf(PrizeTab.COSMETICS, day));
        assertEquals(LOOKS_OCT, onShelf(PrizeTab.LOOKS, day));
    }

    // ---- the packed fallback and the other tabs ---------------------------------------------------------------

    @Test
    void aTabThatNeedsMoreRowsThanAPagePacksAsBefore() {
        assertNull(PrizeShelves.slots(PrizeTab.COSMETICS, Collections.nCopies(40, TRAIL), ROWS),
                "40 trails are more than a page: packed and paged as before");
        List<PrizeType> fiveRows = new ArrayList<>(Collections.nCopies(19, BOOST));
        fiveRows.add(COMMAND);
        fiveRows.add(RADAR);
        assertNull(PrizeShelves.slots(PrizeTab.BOOSTS, fiveRows, ROWS),
                "21 tiles fit a page packed, but by kind they need five rows (3 + 1 + 1): packed");
        List<PrizeType> looks = new ArrayList<>(Collections.nCopies(19, HAT));
        looks.addAll(Collections.nCopies(10, BALL));
        assertNull(PrizeShelves.slots(PrizeTab.LOOKS, looks, ROWS), "19 hats and 10 balls: 3 + 2 rows");
        List<PrizeType> four = new ArrayList<>(Collections.nCopies(18, HAT));
        four.addAll(Collections.nCopies(10, BALL));
        assertNotNull(PrizeShelves.slots(PrizeTab.LOOKS, four, ROWS), "18 hats and 10 balls: 2 + 2 rows fit");
    }

    @Test
    void aOneKindTabLandsWhereItAlwaysDid() {
        assertArrayEquals(range(0, 6), PrizeShelves.slots(PrizeTab.TROPHIES, Collections.nCopies(7, TROPHY), ROWS));
        assertArrayEquals(range(0, 35), PrizeShelves.slots(PrizeTab.MINIS,
                Collections.nCopies(36, PrizeType.FILAMENT), ROWS), "a full page");
        assertNull(PrizeShelves.slots(PrizeTab.MINIS, Collections.nCopies(37, PrizeType.FILAMENT), ROWS),
                "one more than a page: paged, as before");
        for (PrizeTab tab : List.of(PrizeTab.HUNT, PrizeTab.PERKS, PrizeTab.TROPHIES, PrizeTab.MINIS)) {
            for (PrizeType type : PrizeType.values()) {
                assertEquals(0, PrizeShelves.kind(tab, type), tab + " is one kind: " + type);
            }
        }
        assertArrayEquals(new int[0], PrizeShelves.slots(PrizeTab.BOOSTS, List.of(), ROWS), "an empty tab");
    }

    @Test
    void aRowOfAnotherKindGetsTheLastRow() {
        assertArrayEquals(new int[] {0, 18, 9, 1}, PrizeShelves.slots(PrizeTab.BOOSTS,
                List.of(BOOST, RADAR, COMMAND, QUEST_REROLL), ROWS), "a Mini Radar moved to Boosts: after the rest");
        assertArrayEquals(new int[] {0, 9, 18}, PrizeShelves.slots(PrizeTab.COSMETICS,
                List.of(FIREWORK, TRAIL, COMMAND), ROWS), "a name colour (a command) on its own row");
        assertArrayEquals(new int[] {9, 0}, PrizeShelves.slots(PrizeTab.LOOKS, List.of(TRAIL, HAT), ROWS),
                "a trail moved to Looks: after the hats");
    }

    /** Every row the jar and config.yml ship, every one switched on and every collector at once: one page a tab. */
    @Test
    void theBundledShelvesAllOnStillGroupOnOnePage() throws Exception {
        Map<PrizeTab, List<PrizeType>> all = new EnumMap<>(PrizeTab.class);
        for (Prize p : Collectors.ROWS) {
            all.computeIfAbsent(p.tab(), t -> new ArrayList<>()).add(p.type());
        }
        for (Row r : bundled()) {
            all.computeIfAbsent(r.tab(), t -> new ArrayList<>()).add(r.type());
        }
        for (Map.Entry<PrizeTab, List<PrizeType>> e : all.entrySet()) {
            assertNotNull(PrizeShelves.slots(e.getKey(), e.getValue(), ROWS),
                    e.getKey() + " still groups on one page with everything on: " + e.getValue());
        }
        assertEquals(22, all.get(PrizeTab.COSMETICS).size(),
                "9 fireworks, 8 trails, 2 collector trails and 3 name colours: 1 + 2 + 1 rows");
        assertEquals(24, all.get(PrizeTab.LOOKS).size(), "14 hats and caps, 10 balls: 2 + 2 rows");
    }

    // ---- the bundled config, read as the counter shows it ------------------------------------------------------

    /** One config row: its tab, its type, and what decides whether it is drawn. */
    private record Row(String id, PrizeTab tab, PrizeType type, boolean enabled, boolean hatShown) {
    }

    private static List<Row> bundled() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (InputStream in = PrizeCounterLayoutTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                c.load(r);
            }
        }
        List<Row> out = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("arcade.prizes")) {
            PrizeTab tab = PrizeTab.valueOf(String.valueOf(m.get("tab")).toUpperCase(Locale.ROOT));
            PrizeType type = PrizeType.valueOf(String.valueOf(m.get("type")).toUpperCase(Locale.ROOT));
            Object texture = m.get("texture");
            boolean hatShown = m.get("material") != null || (texture != null && !String.valueOf(texture).isBlank());
            out.add(new Row(String.valueOf(m.get("id")), tab, type, !Boolean.FALSE.equals(m.get("enabled")),
                    hatShown));
        }
        assertTrue(out.size() >= 60, "the whole shelf was read: " + out.size());
        return out;
    }

    /**
     * A tab's types as the counter draws them on {@code day}: the collectors first (in season), then the config rows
     * that are on and a hat only with a look to show (the counter's own rule, PrizeService.shelved).
     */
    private static List<PrizeType> onShelf(PrizeTab tab, LocalDate day) {
        List<PrizeType> out = new ArrayList<>();
        for (Prize p : Collectors.ROWS) {
            if (p.tab() == tab && p.collector().inSeason(day)) {
                out.add(p.type());
            }
        }
        try {
            for (Row r : bundled()) {
                if (r.tab() == tab && r.enabled() && (r.type() != HAT || r.hatShown())) {
                    out.add(r.type());
                }
            }
        } catch (Exception e) {
            throw new AssertionError("config.yml could not be read", e);
        }
        return out;
    }
}
