package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.LayoutGuard;
import com.dierks.homecraft.games.gen.LayoutScenarios;
import com.dierks.homecraft.games.gen.LayoutScenarios.Disk;
import com.dierks.homecraft.games.gen.LayoutScenarios.Install;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner's own update to v4, start by start, as {@code onEnable} runs it: a config.yml that 0.36 left
 * (revision 19, its stamp "new" in the database) is migrated (revision 21 moves the three grown areas'
 * untouched spots), backfilled and saved; the legacy guard has nothing to do; and every Games place reads
 * back as a fresh v4 install's. The owner types nothing.
 */
class AreaRevisionUpgradeTest {

    private static List<Integer> list(Object raw) {
        return ((List<?>) raw).stream().map(o -> ((Number) o).intValue()).toList();
    }

    /** The bundled file as 0.36 shipped it: the three grown areas at their 0.36 spots, revision 19. */
    private static YamlConfiguration v036() {
        YamlConfiguration c = LayoutFixtures.bundled();
        c.set("games.fresh.slots.fresh_golf.origin", List.of(7488, 160, 4096));
        c.set("games.fresh.slots.fresh_boat.origin", List.of(6080, 160, 5888));
        c.set("games.fresh.classics.slots.fresh_classic_golf.origin", List.of(7488, 160, 4800));
        c.set("config_revision", 19);
        return c;
    }

    @Test
    void theOwnersFileFrom036TakesTheV4AreasAndTheGuardHasNothingToDo() {
        try (Install install = new Install()) {
            install.set(LayoutGuard.STAMP_KEY, "new|1790000000000|"); // 0.36 decided: the new layout
            Disk disk = new Disk(v036());
            FileConfiguration c = disk.load();
            List<String> log = HomeCraftManagement.migrateConfig(c, "world");
            HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
            disk.text = c.saveToString();
            FileConfiguration after = disk.load();
            assertEquals(21, after.getInt("config_revision"), "stamped revision 21");
            assertEquals(List.of(8768, 160, 4096), list(after.get("games.fresh.slots.fresh_golf.origin")),
                    "Golf of the Week at Col G");
            assertEquals(List.of(6080, 96, 2880), list(after.get("games.fresh.slots.fresh_boat.origin")),
                    "the Ice Boat north");
            assertEquals(List.of(8768, 160, 4896), list(after.get("games.fresh.classics.slots.fresh_classic_golf"
                    + ".origin")), "Classic Golf under Golf of the Week");
            assertTrue(log.stream().anyMatch(l -> l.contains("Golf v4 and Mountain Run v2 areas get their new spots")),
                    "the console says so: " + log);
            assertTrue(log.stream().noneMatch(l -> l.startsWith(HomeCraftManagement.WARN) && l.contains("fresh_")),
                    "no WARN: nothing of the owner's was in the way: " + log);
            assertFalse(LayoutGuard.pending(after), "nothing for the legacy guard to decide");
            assertNull(LayoutGuard.run(LayoutGuard.Store.of(install.db), disk, 1L).decision(), "and it does nothing");
            assertNull(LayoutScenarios.newProblem(disk.load()), "every place exactly as a fresh v4 install's");
            List<String> again = HomeCraftManagement.migrateConfig(disk.load(), "world");
            assertTrue(again.stream().noneMatch(l -> l.contains("new spots")), "the next start: nothing more");
        }
    }

    @Test
    void anOwnersSpotEditedAfterTheUpdateIsNeverMovedAgain() {
        YamlConfiguration c = v036();
        HomeCraftManagement.migrateConfig(c, "world");
        c.set("games.fresh.slots.fresh_golf.origin", List.of(7488, 160, 4096)); // the owner puts golf back by hand
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(7488, 160, 4096), list(c.get("games.fresh.slots.fresh_golf.origin")),
                "revision 21 ran once: a file past it keeps whatever the owner writes, even an old shipped value");
        assertTrue(log.stream().noneMatch(l -> l.contains("Golf v4")), "and says nothing: " + log);
    }
}
