package com.dierks.homecraft.config;

import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.GamesAreaMigration;
import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F10 (the red team): config revision 21 couldn't be saved (a read-only config.yml, a full disk), so the Games
 * read the file 0.36 left, at revision 19. Rather than build Golf v4 at 0.36's spot at its new size (half B
 * straddling x 8192) and move it again once the file is saved, the three areas revision 21 moves are held
 * where they were built: off, with the reason, and nothing done there; everything else reads as usual.
 */
class AreaRevisionHoldTest {

    /** config.yml as 0.36 left it: the three moved areas at their 0.36 spots, revision 19. */
    private static YamlConfiguration v036() {
        YamlConfiguration c = LayoutFixtures.bundled();
        c.set("games.fresh.slots.fresh_golf.origin", List.of(7488, 160, 4096));
        c.set("games.fresh.slots.fresh_boat.origin", List.of(6080, 160, 5888));
        c.set("games.fresh.classics.slots.fresh_classic_golf.origin", List.of(7488, 160, 4800));
        c.set("config_revision", 19);
        return c;
    }

    private static DailySettings fresh(YamlConfiguration c) {
        return PluginConfig.games(c, w -> { }, null).settings(DailyCourses.SPEC);
    }

    @Test
    void aFileStillAtRevision19HoldsTheThreeMovedAreasWhereTheyWereBuilt() {
        YamlConfiguration c = v036();
        assertTrue(GamesAreaMigration.unsaved(c), "the fixture: below revision 21");
        DailySettings d = fresh(c);
        for (DailySettings.SlotConfig s : List.of(d.slot(Slots.DAILY_GOLF.id()), d.slot(Slots.ICE_BOAT.id()),
                d.archive().classic(Slots.CLASSIC_GOLF.id()))) {
            assertFalse(s.placed(), s.id() + ": not placed: the engine keeps it where it was claimed");
            assertFalse(s.enabled(), s.id() + ": and off");
            assertNotNull(s.held(), s.id() + ": with the reason");
            assertTrue(s.held().contains("still at config revision 19") && s.held().contains("revision 21 moves this"
                    + " area"), s.id() + ": naming the revision: " + s.held());
        }
        assertArrayEquals(new int[]{7488, 160, 4096}, d.slot(Slots.DAILY_GOLF.id()).origin(),
                "the spot it read is kept for the record (it isn't used)");
        for (Slots.Def def : Slots.ALL) {
            if (!List.of(Slots.DAILY_GOLF, Slots.ICE_BOAT).contains(def)) {
                assertTrue(d.slot(def.id()).placed(), def.id() + ": every other course reads as usual");
                assertNull(d.slot(def.id()).held(), def.id() + ": not held");
            }
        }
        assertTrue(d.archive().classic(Slots.CLASSIC_DROPPER.id()).placed(), "and every other Classic");
    }

    @Test
    void theSavedFileIsReadAsUsual() {
        YamlConfiguration c = v036();
        List<String> log = new ArrayList<>();
        GamesAreaMigration.apply(c, log);
        c.set("config_revision", GamesAreaMigration.REVISION);
        assertFalse(GamesAreaMigration.unsaved(c), "revision 21 on the file");
        DailySettings d = fresh(c);
        assertTrue(d.slot(Slots.DAILY_GOLF.id()).placed(), "golf placed");
        assertNull(d.slot(Slots.DAILY_GOLF.id()).held(), "not held");
        assertArrayEquals(new int[]{8768, 160, 4096}, d.slot(Slots.DAILY_GOLF.id()).origin(), "at Col G");
        assertEquals(fresh(LayoutFixtures.bundled()), d, "exactly a fresh install's reading");
    }

    @Test
    void aFileAtTheTokenBalancesRevisionIsHeldToo() {
        YamlConfiguration c = v036();
        c.set("config_revision", 20); // revision 20 saved, then 21 didn't
        assertNotNull(fresh(c).slot(Slots.DAILY_GOLF.id()).held(), "anything below 21 is held");
    }

    @Test
    void heldIsNeverSetOnAPlacedSlot() {
        DailySettings.SlotConfig s = new DailySettings.SlotConfig("fresh_golf", true, "EEMMH", new int[]{0, 0, 0}, 1,
                576, true, "a reason");
        assertNull(s.held(), "a placed slot has no hold");
        DailySettings.SlotConfig h = s.held("why");
        assertEquals("why", h.held(), "held: the reason");
        assertFalse(h.placed() || h.enabled(), "off and not placed");
        assertEquals("why", h.withTierOrMix("EEEEE").held(), "kept through a copy");
        assertFalse(h.equals(s.held("another")), "the reason is part of what it says");
    }
}
