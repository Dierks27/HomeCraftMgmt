package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.config.GamesConfig;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code games.cup} (EVENTS-OWNER-DECISIONS §D2): the shipped block is exactly the defaults (on, 5
 * tokens to enter, a top-up of 10), its keys are the record's, an out-of-range number is clamped
 * with one WARN naming its full key, and {@code enabled: false} switches new entries off.
 */
class CupSettingsTest {

    private static Map<String, Object> shippedGames() throws Exception {
        try (InputStream in = CupSettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    private static CupSettings parse(Map<String, Object> block, List<String> warns) {
        return CupSettings.parse(new GamesConfig.Node("games.cup", block, warns::add), CupSettings.defaults());
    }

    @Test
    void theShippedBlockIsOnWithAFiveTokenEntryAndATopUpOfTen() throws Exception {
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(shippedGames(), warns::add, null);
        assertEquals(CupSettings.defaults(), parsed.settings(WeeklyCup.SPEC), "config.yml ships the defaults");
        assertEquals(new CupSettings(true, 5, 10), CupSettings.defaults(), "§D2: 5 tokens, a top-up of 10, on");
        assertTrue(warns.stream().noneMatch(w -> w.startsWith("games.cup")), "and reads without a WARN: " + warns);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) shippedGames().get("cup");
        assertEquals(CupSettings.KEYS, List.copyOf(block.keySet()), "the block's keys, in order");
        assertEquals("cup", WeeklyCup.SPEC.id(), "the Cup's block is games.cup");
    }

    @Test
    void anOutOfRangeNumberIsClampedWithOneWarnNamingItsKey() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("entry", 500);
        List<String> warns = new ArrayList<>();
        assertEquals(100, parse(block, warns).entry(), "an entry is at most 100");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("games.cup.entry "), "the WARN names the key: " + warns);

        block.clear();
        block.put("entry", 0);
        warns.clear();
        assertEquals(1, parse(block, warns).entry(), "a Cup is never free: free play already is");
        assertEquals(1, warns.size(), warns.toString());

        block.clear();
        block.put("server_topup", -3);
        warns.clear();
        assertEquals(0, parse(block, warns).serverTopup(), "no negative top-up");
        assertTrue(warns.get(0).startsWith("games.cup.server_topup "), warns.toString());

        block.clear();
        block.put("server_topup", 1_000);
        warns.clear();
        assertEquals(100, parse(block, warns).serverTopup(), "a top-up of at most 100");
    }

    @Test
    void enabledFalseSwitchesTheCupOffAndTheRestReadsAsShipped() {
        List<String> warns = new ArrayList<>();
        CupSettings off = parse(Map.of("enabled", false), warns);
        assertFalse(off.enabled(), "games.cup.enabled: false");
        assertEquals(5, off.entry(), "the rest as shipped");
        assertEquals(10, off.serverTopup(), "the rest as shipped");
        assertEquals(List.of(), warns, "no WARN");
    }

    @Test
    void theRecordItselfKeepsItsNumbersInRange() {
        assertEquals(new CupSettings(true, 1, 100), new CupSettings(true, -5, 5_000), "clamped however it is made");
    }
}
