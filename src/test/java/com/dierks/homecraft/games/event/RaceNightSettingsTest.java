package com.dierks.homecraft.games.event;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.TokenBalance;
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
 * {@code games.race_night.hype} (COURSE-VARIETY-SPEC §5.2, §6): the one new key, runtime copy only.
 *
 * <p>Pinned here: it ships on, last in the block, and the shipped block still reads as exactly the
 * defaults with no WARN; {@code hype: false} reads as off; junk is one WARN naming its full key, and
 * the setting keeps its default; and every settings a caller built before the key reads it as on.
 */
class RaceNightSettingsTest {

    private static Map<String, Object> shippedGames() throws Exception {
        try (InputStream in = RaceNightSettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return GamesConfig.tree(c.getConfigurationSection("games"));
            }
        }
    }

    private static RaceNightSettings parse(Map<String, Object> block, List<String> warns) {
        return RaceNightSettings.parse(new GamesConfig.Node("games.race_night", block, warns::add),
                RaceNightSettings.defaults());
    }

    @Test
    void hypeShipsOnLastInTheBlockAndTheBlockIsStillExactlyTheDefaults() throws Exception {
        assertTrue(RaceNightSettings.defaults().hype(), "§6: games.race_night.hype defaults to true");
        assertEquals("hype", RaceNightSettings.KEYS.get(RaceNightSettings.KEYS.size() - 1), "the new key is last");
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed parsed = GamesConfig.parse(shippedGames(), warns::add, null);
        assertEquals(RaceNightSettings.defaults(), parsed.settings(RaceNight.SPEC), "config.yml ships the defaults");
        assertTrue(warns.stream().noneMatch(w -> w.startsWith("games.race_night")), "and reads without a WARN: " + warns);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) shippedGames().get("race_night");
        assertEquals(RaceNightSettings.KEYS, List.copyOf(block.keySet()), "the block's keys, in order");
        assertEquals(Boolean.TRUE, block.get("hype"), "config.yml: hype: true");
    }

    @Test
    void hypeFalseTurnsItOffAndJunkKeepsTheDefaultWithOneWarn() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("hype", false);
        List<String> warns = new ArrayList<>();
        assertFalse(parse(block, warns).hype(), "hype: false is off");
        assertTrue(warns.isEmpty(), "with no WARN: " + warns);

        block.put("hype", "maybe");
        warns.clear();
        assertTrue(parse(block, warns).hype(), "junk keeps the default");
        assertEquals(1, warns.size(), "one WARN: " + warns);
        assertTrue(warns.get(0).startsWith("games.race_night.hype "), "naming its full key: " + warns);
    }

    @Test
    void thePrizesClampAtThirtyAndTheFinisherPrizeAtTen() {
        // the 2 Oct token balance: prizes 20/12/8 + 5, so the limits moved from 0-10 and 0-2
        int max = RaceNightSettings.MAX_PRIZE;
        int fin = RaceNightSettings.MAX_FINISHER_PRIZE;
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("prizes", List.of(max, 12, 8));
        block.put("finisher_prize", fin);
        List<String> warns = new ArrayList<>();
        RaceNightSettings at = parse(block, warns);
        assertEquals(List.of(max, 12, 8), at.prizes(), "the bound is allowed");
        assertEquals(fin, at.finisherPrize(), "and so is the finisher prize's");
        assertEquals(List.of(), warns, "with no WARN");

        block.put("prizes", List.of(max + 1, 12, 8));
        block.put("finisher_prize", fin + 1);
        warns.clear();
        RaceNightSettings over = parse(block, warns);
        assertEquals(List.of(max, 12, 8), over.prizes(), "one over is held to the bound");
        assertEquals(fin, over.finisherPrize(), "and so is the finisher prize");
        assertEquals(2, warns.size(), "one WARN each: " + warns);
        assertTrue(warns.get(0).startsWith("games.race_night.prizes "), "naming its key: " + warns);
        assertTrue(warns.get(1).startsWith("games.race_night.finisher_prize "), "naming its key: " + warns);
        assertEquals(NightRules.MAX_PRIZE_PER_NIGHT, RaceNightSettings.MAX_PRIZE, "the same bound as a night's");
        assertEquals(TokenBalance.RACE_MAX_FINISHER_PRIZE, fin, "the token balance's bounds");
    }

    @Test
    void settingsBuiltBeforeTheKeyReadItAsOn() {
        RaceNightSettings d = RaceNightSettings.defaults();
        RaceNightSettings old = new RaceNightSettings(d.enabled(), d.schedule(), d.course(), d.races(), d.laps(),
                d.announceMinutes(), d.joinMinutes(), d.adminJoinMinutes(), d.minRacers(), d.maxRacers(),
                d.finishWindowSeconds(), d.maxRaceMinutes(), d.breakSeconds(), d.warmupSeconds(), d.points(),
                d.finishPoints(), d.stillRacingPoints(), d.prizes(), d.finisherPrize(), d.prizeEventsPerWeek(),
                d.season(), d.standRadius());
        assertTrue(old.hype(), "the old shape reads the hype as shipped: on");
        assertEquals(d, old, "and is the defaults");
    }
}
