package com.dierks.homecraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 29 (0.44.0): the Cooked Turkey 2026 joins the Turkey Mini 2026's series, the Turkey Mini gets the
 * turkey's head where it is still revision 26's, and the welcome turkey goes off.
 */
class TurkeyMinisMigrationTest {

    private static YamlConfiguration rev28(Map<String, Object> turkey) {
        YamlConfiguration c = new YamlConfiguration();
        Map<String, Object> holiday = new LinkedHashMap<>();
        holiday.put("name", "Holiday Collection");
        holiday.put("rarity", "EPIC");
        holiday.put("entries", new ArrayList<>(List.of(new LinkedHashMap<>(turkey))));
        c.set(TurkeyMinisMigration.SERIES, new ArrayList<>(List.of(holiday)));
        c.set(TurkeyMinisMigration.WELCOME, true);
        return c;
    }

    private static List<Map<String, Object>> entries(YamlConfiguration c) {
        List<Map<String, Object>> series = ArcadeConfigMigration.mapRows(c.getList(TurkeyMinisMigration.SERIES));
        return ArcadeConfigMigration.mapRows((List<?>) series.get(0).get("entries"));
    }

    @Test
    void aRevision28FileGetsBothMinisAndNoWelcome() {
        YamlConfiguration c = rev28(ThanksgivingMigration.TURKEY_MINI);
        List<String> log = new ArrayList<>();
        TurkeyMinisMigration.apply(c, log);
        List<Map<String, Object>> e = entries(c);
        assertEquals(2, e.size());
        assertTrue(ShopMigration.same(e.get(0), TurkeyMinisMigration.TURKEY_MINI), "the turkey's head");
        assertTrue(ShopMigration.same(e.get(1), TurkeyMinisMigration.COOKED_TURKEY), "the Cooked Turkey after it");
        assertFalse(c.getBoolean(TurkeyMinisMigration.WELCOME));
        assertEquals(3, log.size(), log.toString());
        List<String> again = new ArrayList<>();
        TurkeyMinisMigration.apply(c, again);
        assertEquals(List.of(), again, "a second run changes nothing");
        assertEquals(2, entries(c).size());
    }

    @Test
    void anOwnersOwnTurkeyTextureStays() {
        Map<String, Object> mine = new LinkedHashMap<>(ThanksgivingMigration.TURKEY_MINI);
        mine.put("texture", "my-own");
        YamlConfiguration c = rev28(mine);
        TurkeyMinisMigration.apply(c, new ArrayList<>());
        assertEquals("my-own", entries(c).get(0).get("texture"));
        assertEquals(2, entries(c).size(), "the Cooked Turkey still comes");
    }

    @Test
    void theBundledFileIsTheMigratedOne() {
        YamlConfiguration b = com.dierks.homecraft.games.gen.LayoutFixtures.bundled();
        List<String> log = new ArrayList<>();
        TurkeyMinisMigration.apply(b, log);
        assertEquals(List.of(), log, "the bundled file has both Minis and no welcome turkey already");
    }
}
