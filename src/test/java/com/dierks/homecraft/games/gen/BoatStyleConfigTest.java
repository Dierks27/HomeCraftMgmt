package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.boat.BoatStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ice Boat's {@code games.fresh.slots.fresh_boat.style} (MOUNTAIN-V2-SPEC §5.1, §13 item 2; red-team F05).
 *
 * <p>Pinned here: config.yml ships {@code style: random} and it reads as {@code null} (random) with no WARN;
 * {@code road} and {@code slalom} read in any case; anything else is random with one WARN naming the key,
 * and never switches the course off; only Ice Boat has the key (in {@link DailySettings#KEYS}, nowhere else);
 * the style survives every wither, the slot's equality sees it, and every older {@code SlotConfig}
 * constructor still builds a slot with no style.
 */
class BoatStyleConfigTest {

    private static Map<String, Object> shipped() throws Exception {
        try (InputStream in = BoatStyleConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                return GamesConfig.tree(c.getConfigurationSection("games.fresh"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static DailySettings with(String slot, String key, Object value, List<String> warns) throws Exception {
        Map<String, Object> fresh = shipped();
        Map<String, Object> slots = (Map<String, Object>) fresh.get("slots");
        Map<String, Object> s = new LinkedHashMap<>((Map<String, Object>) slots.get(slot));
        s.put(key, value);
        slots.put(slot, s);
        GamesConfig.Node n = new GamesConfig.Node("games.fresh", fresh, warns::add);
        return DailySettings.parse(n, DailySettings.defaults());
    }

    @Test
    void configShipsRandomWhichReadsAsNoStyleWithNoWarn() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> boat = (Map<String, Object>) ((Map<String, Object>) shipped().get("slots"))
                .get(Slots.ICE_BOAT.id());
        assertEquals("random", boat.get("style"), "config.yml ships style: random for Ice Boat");
        List<String> warns = new ArrayList<>();
        DailySettings s = DailySettings.parse(new GamesConfig.Node("games.fresh", shipped(), warns::add),
                DailySettings.defaults());
        assertEquals(List.of(), warns, "with no WARN");
        assertNull(s.slot(Slots.ICE_BOAT.id()).style(), "random is no style of its own");
        assertEquals("random", s.slot(Slots.ICE_BOAT.id()).styleWord(), "and is said as random");
        assertNull(DailySettings.defaults().slot(Slots.ICE_BOAT.id()).style(), "the defaults agree");
        assertTrue(DailySettings.KEYS.contains("slots.fresh_boat.style"), "a key the parser reads and config ships");
        assertEquals(1, DailySettings.KEYS.stream().filter(k -> k.endsWith(".style")).count(), "Ice Boat's only");
        int at = DailySettings.KEYS.indexOf("slots.fresh_boat.style");
        assertEquals("slots.fresh_boat.tier", DailySettings.KEYS.get(at - 1), "after its tier");
        assertEquals("slots.fresh_boat.origin", DailySettings.KEYS.get(at + 1), "and before its origin, as config writes it");
    }

    @Test
    void roadAndSlalomReadInAnyCase() throws Exception {
        for (Object[] c : new Object[][]{{"road", BoatStyle.ROAD}, {"Road", BoatStyle.ROAD}, {" SLALOM ", BoatStyle.SLALOM},
                {"slalom", BoatStyle.SLALOM}, {"RANDOM", null}, {"random", null}}) {
            List<String> warns = new ArrayList<>();
            DailySettings s = with(Slots.ICE_BOAT.id(), "style", c[0], warns);
            assertEquals(List.of(), warns, "\"" + c[0] + "\" reads without a WARN");
            assertEquals(c[1], s.slot(Slots.ICE_BOAT.id()).style(), "\"" + c[0] + "\"");
        }
    }

    @Test
    void anythingElseIsRandomWithOneWarnAndTheCourseStays() throws Exception {
        for (Object junk : new Object[]{"winding", "fast", 3, true, List.of("road")}) {
            List<String> warns = new ArrayList<>();
            DailySettings s = with(Slots.ICE_BOAT.id(), "style", junk, warns);
            assertEquals(1, warns.size(), junk + ": one WARN: " + warns);
            assertTrue(warns.get(0).startsWith("games.fresh.slots.fresh_boat.style ")
                    && warns.get(0).endsWith("using random"), "naming the key and what it does: " + warns);
            assertNull(s.slot(Slots.ICE_BOAT.id()).style(), junk + ": random");
            assertEquals(DailySettings.defaults().slot(Slots.ICE_BOAT.id()).origin()[2],
                    s.slot(Slots.ICE_BOAT.id()).origin()[2], "the course stays where it is");
            assertFalse(s.slot(Slots.ICE_BOAT.id()).enabled(), "and as switched as config says (off, as shipped)");
        }
        List<String> warns = new ArrayList<>();
        DailySettings on = with(Slots.ICE_BOAT.id(), "enabled", true, warns);
        assertTrue(on.slot(Slots.ICE_BOAT.id()).enabled(), "fixture: Ice Boat switched on");
    }

    @Test
    void onlyIceBoatHasAStyle() throws Exception {
        List<String> warns = new ArrayList<>();
        DailySettings s = with("fresh_parkour", "style", "road", warns);
        assertNull(s.slot("fresh_parkour").style(), "another course never has one (the key isn't read)");
    }

    @Test
    void theStyleSurvivesEveryWitherAndOlderConstructorsHaveNone() {
        DailySettings.SlotConfig c = DailySettings.SlotConfig.shipped(Slots.ICE_BOAT).withStyle(BoatStyle.ROAD);
        assertEquals(BoatStyle.ROAD, c.withEnabled(true).style(), "withEnabled keeps it");
        assertEquals(BoatStyle.ROAD, c.withOrigin(new int[]{0, 96, 0}).style(), "withOrigin");
        assertEquals(BoatStyle.ROAD, c.withTierOrMix("hard").style(), "withTierOrMix");
        assertEquals(BoatStyle.ROAD, c.withDailyClear(3).style(), "withDailyClear");
        assertEquals(BoatStyle.ROAD, c.withHalfGap(640).style(), "withHalfGap");
        assertEquals(BoatStyle.ROAD, c.unplaced().style(), "unplaced");
        assertEquals(BoatStyle.ROAD, c.held("config.yml is below revision 21").style(), "held");
        assertNotEquals(c, c.withStyle(BoatStyle.SLALOM), "equality sees the style");
        assertNotEquals(c, c.withStyle(null), "random is another setting than road");
        assertEquals(c, c.withStyle(BoatStyle.ROAD), "and the same style is the same slot");
        assertEquals(c.hashCode(), c.withStyle(BoatStyle.ROAD).hashCode(), "with the same hash");
        int[] o = {6080, 96, 2880};
        assertNull(new DailySettings.SlotConfig("fresh_boat", false, "medium", o, 15).style(), "5 arguments: none");
        assertNull(new DailySettings.SlotConfig("fresh_boat", false, "medium", o, 15, 576).style(), "6");
        assertNull(new DailySettings.SlotConfig("fresh_boat", false, "medium", o, 15, 576, true).style(), "7");
        assertNull(new DailySettings.SlotConfig("fresh_boat", false, "medium", o, 15, 576, false, "why").style(), "8");
        assertEquals("road", c.styleWord(), "said as config writes it");
    }
}
