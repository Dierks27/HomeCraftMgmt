package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.LayoutGuard;
import com.dierks.homecraft.games.gen.LayoutScenarios;
import com.dierks.homecraft.games.gen.LayoutScenarios.Disk;
import com.dierks.homecraft.games.gen.LayoutScenarios.Install;
import com.dierks.homecraft.games.gen.api.Slots;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm config reset games[.<part>]} never moves a built place: it leaves every origin,
 * {@code half_gap}, {@code keep.area} and {@code keep.plot_gap}, and the Games worlds, exactly as they are
 * on disk and resets everything else, and the dry run lists what it keeps. On a server that kept 0.35's
 * Games spots (LAYOUT-DECISIONS item 5) the bundled values are the new spots; on any server a place moved
 * by hand, or a void Games world ({@code sky}), would move to the bundled one.
 */
class ConfigResetLayoutTest {

    /** Every Games reset an owner can type that holds a spot. */
    private static final List<String> RESETS = List.of("games", "games.fresh", "games.clubhouse",
            "games.falling_floors", "games.fresh.slots", "games.fresh.slots.fresh_golf",
            "games.fresh.slots.fresh_golf.origin", "games.fresh.classics", "games.fresh.keep");

    /** A 0.35 install that built everything the layout has, after the guard kept its spots. */
    private static Install legacy(Disk disk) {
        Install install = new Install();
        for (Slots.Def d : Slots.ALL) {
            install.edition(d);
        }
        install.recall(Slots.CLASSIC_GOLF).kept(1).clubhouse().arena();
        LayoutGuard.run(LayoutGuard.Store.of(install.db), disk, 1L);
        return install;
    }

    private static String stamp(Install install) {
        try {
            return install.meta.get(LayoutGuard.STAMP_KEY);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void onAServerThatKept035sSpotsNoGamesResetMovesABuiltPlace() {
        Disk disk = new Disk(LayoutScenarios.marked035());
        try (Install install = legacy(disk)) {
            assertTrue(stamp(install).startsWith("legacy|"), "the fixture kept 0.35's spots");
            YamlConfiguration bundled = LayoutFixtures.bundled();
            for (String path : RESETS) {
                FileConfiguration c = disk.load();
                c.set("games.fresh.stars.gold.easy", 5.0); // what the owner wanted back
                List<String> keep = ConfigReset.kept(c, path);
                ConfigReset.Plan plan = ConfigReset.plan(c, bundled, path, keep);
                for (Map<String, ?> m : List.of(plan.changed(), plan.added(), plan.removed())) {
                    for (String k : m.keySet()) {
                        assertFalse(LayoutGuard.spotPaths(c).stream().anyMatch(s -> k.equals(s) || k.startsWith(s + ".")),
                                path + ": the dry run doesn't offer to change " + k);
                    }
                }
                ConfigReset.apply(c, bundled, path, keep);
                FileConfiguration after = LayoutFixtures.reread(c);
                assertNull(LayoutScenarios.legacyProblem(after, install), path + ": every claim still matches, so"
                        + " nothing is rerolled or built again elsewhere");
                if (path.equals("games") || path.equals("games.fresh")) {
                    assertEquals(2.0, after.getDouble("games.fresh.stars.gold.easy"), path + ": the rest is reset");
                }
            }
        }
    }

    @Test
    void theDryRunNamesTheSpotsItKeeps() {
        Disk disk = new Disk(LayoutScenarios.marked035());
        try (Install install = legacy(disk)) {
            FileConfiguration c = disk.load();
            ConfigReset.Plan plan = ConfigReset.plan(c, LayoutFixtures.bundled(), "games.fresh",
                    ConfigReset.kept(c, "games.fresh"));
            assertEquals(List.of(5120, 160, 4096), plan.kept().get("games.fresh.slots.fresh_tiny_golf.origin"),
                    "Tiny Golf's 0.35 origin, kept: " + plan.kept().keySet());
            assertEquals(32, plan.kept().get("games.fresh.slots.fresh_tiny_golf.half_gap"), "and its 0.35 gap");
            assertNull(plan.kept().get("games.fresh.slots.fresh_golf.origin"), "Golf of the Week is at its shipped v4"
                    + " spot (v4 grew it, so the guard gave it its new one): a reset has nothing of it to keep");
            assertEquals(0, plan.kept().get("games.fresh.keep.plot_gap"), "and the kept courses' spacing");
            assertFalse(plan.kept().containsKey("games.clubhouse.origin"), "only what the reset covers");
        }
    }

    @Test
    void aBareClassicListIsKeptWhole() {
        Disk disk = new Disk(LayoutScenarios.marked035());
        try (Install install = legacy(disk)) {
            FileConfiguration c = disk.load();
            c.set("games.fresh.classics.slots.fresh_classic_golf", List.of(4352, 160, 4736));
            ConfigReset.apply(c, LayoutFixtures.bundled(), "games.fresh.classics",
                    ConfigReset.kept(c, "games.fresh.classics"));
            assertEquals(List.of(4352, 160, 4736), LayoutFixtures.reread(c).get("games.fresh.classics.slots"
                    + ".fresh_classic_golf"), "the Classic's own bare list, as it was");
        }
    }

    @Test
    void aServerThatTookTheNewLayoutKeepsWhereItsPlacesStandTooAndItsVoidWorld() {
        // the owner's own setup: a fresh install, a void Games world, and a course moved by hand
        YamlConfiguration c = LayoutFixtures.bundled();
        c.set("games.worlds", List.of("games", "sky"));
        c.set("games.fresh.world", "sky");
        c.set("games.fresh.slots.fresh_golf.origin", List.of(9024, 160, 4096));
        c.set("games.fresh.stars.gold.easy", 5.0);
        for (String path : List.of("games", "games.fresh")) {
            YamlConfiguration disk = LayoutFixtures.reread(c);
            List<String> keep = ConfigReset.kept(disk, path);
            ConfigReset.Plan plan = ConfigReset.plan(disk, LayoutFixtures.bundled(), path, keep);
            assertEquals("sky", plan.kept().get("games.fresh.world"), path + ": the dry run lists the void world as kept");
            assertFalse(plan.changed().containsKey("games.fresh.world"), path + ": and doesn't offer to change it");
            ConfigReset.apply(disk, LayoutFixtures.bundled(), path, keep);
            FileConfiguration after = LayoutFixtures.reread(disk);
            assertEquals("sky", after.getString("games.fresh.world"), path + ": the courses stay in sky, not rerolled in"
                    + " games with their blocks left standing in sky");
            assertEquals(List.of(9024, 160, 4096), after.get("games.fresh.slots.fresh_golf.origin"), path + ": the course"
                    + " moved by hand stays where it was moved");
            assertEquals(2.0, after.getDouble("games.fresh.stars.gold.easy"), path + ": the rest is reset");
            if (path.equals("games")) {
                assertEquals(List.of("games", "sky"), after.get("games.worlds"), "the Games worlds stay as they are");
            }
        }
        assertEquals(List.of(), ConfigReset.kept(c, "games.snake"), "a reset that holds no spot keeps none");
        assertEquals(List.of(), ConfigReset.kept(c, "arcade"), "nor one outside the Games");
    }
}
