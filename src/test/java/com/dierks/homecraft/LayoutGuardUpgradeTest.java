package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.LayoutGuard;
import com.dierks.homecraft.games.gen.LayoutScenarios;
import com.dierks.homecraft.games.gen.LayoutScenarios.Disk;
import com.dierks.homecraft.games.gen.LayoutScenarios.Faulty;
import com.dierks.homecraft.games.gen.LayoutScenarios.Install;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenAdminKeys;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update to the new Games layout, start by start (LAYOUT-DECISIONS item 5): config revision 19
 * marks the file before the database opens, the backfill runs, and the legacy guard decides once the
 * database is open, as {@code onEnable} does. A fresh install and a 0.35 install that never built
 * anything take the new layout; a 0.35 install that built anything (a Fresh set, a kept course, the
 * Clubhouse, the arena, a Classic) keeps every spot and shape; an owner's own spot stays with its
 * old shape. Running it again does nothing, and a stop between any two steps ends the same way.
 */
class LayoutGuardUpgradeTest {

    private static final long NOW = 1_790_000_000_000L;

    /** One start: migrate (revision 19 marks), backfill, save, then the guard, as onEnable does. */
    private static LayoutGuard.Outcome start(Disk disk, LayoutGuard.Store store) {
        migrate(disk);
        return LayoutGuard.run(store, disk, NOW);
    }

    /** The part of a start before the database opens: the migration and the backfill, saved. */
    private static void migrate(Disk disk) {
        FileConfiguration c = disk.load();
        HomeCraftManagement.migrateConfig(c, "world");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        disk.text = c.saveToString();
    }

    private static String stamp(Install i) {
        try {
            return i.meta.get(LayoutGuard.STAMP_KEY);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every value under {@code s} that isn't a section, by path. */
    private static Map<String, Object> leaves(ConfigurationSection s) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        s.getValues(true).forEach((k, v) -> {
            if (!(v instanceof ConfigurationSection)) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static List<Integer> list(Object raw) {
        return ((List<?>) raw).stream().map(o -> ((Number) o).intValue()).toList();
    }

    // ---- a fresh install and one that never built ------------------------------------------------

    @Test
    void aFreshInstallTakesTheNewLayoutFromTheBundledFileAsItIs() {
        try (Install install = new Install()) {
            Disk disk = new Disk(LayoutFixtures.bundled());
            migrate(disk);
            String before = disk.text;
            LayoutGuard.Outcome o = LayoutGuard.run(LayoutGuard.Store.of(install.db), disk, NOW);
            assertEquals(LayoutGuard.Decision.NEW, o.decision(), "nothing was built");
            assertFalse(o.changed(), "the bundled file already is the new layout");
            assertEquals(before, disk.text, "not a byte of it changes");
            assertEquals(0, disk.saves, "nor is it written");
            assertEquals("new|" + NOW + "|", stamp(install), "the decision is stamped");
            assertNull(LayoutScenarios.newProblem(disk.load()), "the new layout");
            assertNull(start(disk, LayoutGuard.Store.of(install.db)).decision(), "the next start has nothing to do");
        }
    }

    @Test
    void a035ConfigThatNeverBuiltTakesTheNewLayout() {
        try (Install install = new Install()) {
            Disk disk = new Disk(LayoutFixtures.v035());
            migrate(disk);
            FileConfiguration marked = disk.load();
            assertEquals(19, marked.getInt("config_revision"), "revision 19 is stamped before the database opens");
            assertTrue(LayoutGuard.pending(marked), "and the file is marked for the guard");
            assertEquals(LayoutGuard.Decision.NEW,
                    LayoutGuard.run(LayoutGuard.Store.of(install.db), disk, NOW).decision(), "nothing was built");
            FileConfiguration after = disk.load();
            assertNull(LayoutScenarios.newProblem(after), "every place at its new spot, as a fresh install's");
            assertFalse(LayoutGuard.pending(after), "the mark is gone");
            for (LayoutGuard.Area a : LayoutGuard.AREAS) {
                if (a.gapPath() != null) {
                    assertNull(after.get(a.gapPath()), a.gapPath() + ": no gap written, so 576");
                }
            }
            assertTrue(stamp(install).startsWith("new|"), "stamped new");
        }
    }

    @Test
    void theOwnersOwnPathFrom034GetsTheGamesBlockExactlyAsBundled() {
        // 0.34.0 (revision 16) had no Games at all: the backfill adds the whole block, comments and all.
        YamlConfiguration v034 = LayoutFixtures.bundled();
        v034.set("games", null);
        v034.set("config_revision", 16);
        try (Install install = new Install()) {
            Disk disk = new Disk(v034);
            assertEquals(LayoutGuard.Decision.NEW, start(disk, LayoutGuard.Store.of(install.db)).decision(),
                    "an install from before the Games built nothing");
            FileConfiguration after = disk.load();
            YamlConfiguration bundled = LayoutFixtures.bundled();
            ConfigurationSection games = after.getConfigurationSection("games");
            assertNotNull(games, "the Games block is there");
            assertEquals(leaves(bundled.getConfigurationSection("games")), leaves(games),
                    "every Games value exactly as a fresh install ships it");
            assertEquals(bundled.getComments("games.fresh.slots"), after.getComments("games.fresh.slots"),
                    "with the comments that explain the new spots");
            assertFalse(LayoutGuard.pending(after), "and no mark left");
        }
    }

    // ---- a 0.35 install that built ----------------------------------------------------------------

    @Test
    void a035InstallThatBuiltAnyKindOfThingKeepsEverySpotAndShape() {
        Map<String, Consumer<Install>> built = new java.util.LinkedHashMap<>();
        built.put("a Fresh set", i -> i.edition(Slots.DAILY_PARKOUR_MEDIUM));
        built.put("a dropper's set", i -> i.edition(Slots.FRESH_DROPPER));
        built.put("a kept course", i -> i.kept(4));
        built.put("the Clubhouse", Install::clubhouse);
        built.put("the arena", Install::arena);
        built.put("a Classic", i -> i.recall(Slots.CLASSIC_GOLF));
        built.forEach((what, fill) -> {
            try (Install install = new Install()) {
                fill.accept(install);
                Disk disk = new Disk(LayoutFixtures.v035());
                LayoutGuard.Outcome o = start(disk, LayoutGuard.Store.of(install.db));
                assertEquals(LayoutGuard.Decision.LEGACY, o.decision(), what + ": built, so the old layout");
                FileConfiguration after = disk.load();
                assertNull(LayoutScenarios.legacyProblem(after, install), what + ": every place where and as 0.35"
                        + " built it, every claim still matching");
                for (LayoutGuard.Area a : LayoutGuard.AREAS) {
                    if (a.gapPath() != null) {
                        assertEquals(a.legacyGap(), after.getInt(a.gapPath(), -1), what + ": " + a.gapPath()
                                + " written as 0.35's");
                    }
                }
                assertTrue(stamp(install).startsWith("legacy|"), what + ": stamped legacy");
                assertTrue(o.log().stream().anyMatch(l -> l.startsWith(LayoutGuard.WARN)
                        && l.contains("/hcm games check")), what + ": a WARN says where to look: " + o.log());
                List<String> warns = new java.util.ArrayList<>();
                LayoutFixtures.parse(after, warns);
                assertEquals(List.of(), warns, what + ": the kept file reads without a WARN");
            }
        });
    }

    @Test
    void anOwnersOwnSpotStaysWithItsOldShapeWhetherOrNotAnythingWasBuilt() {
        for (boolean built : new boolean[]{false, true}) {
            try (Install install = new Install()) {
                if (built) {
                    install.edition(Slots.DAILY_GOLF);
                }
                YamlConfiguration v035 = LayoutFixtures.v035();
                v035.set("games.fresh.slots.fresh_parkour.origin", List.of(4352, 160, 4800));
                Disk disk = new Disk(v035);
                start(disk, LayoutGuard.Store.of(install.db));
                FileConfiguration after = disk.load();
                assertEquals(List.of(4352, 160, 4800), list(after.get("games.fresh.slots.fresh_parkour.origin")),
                        (built ? "built" : "never built") + ": the owner's spot stays");
                assertEquals(32, after.getInt("games.fresh.slots.fresh_parkour.half_gap"),
                        (built ? "built" : "never built") + ": with 0.35's gap, so its shape stays");
                assertEquals(built ? List.of(4864, 160, 4096) : List.of(7488, 160, 4096),
                        list(after.get("games.fresh.slots.fresh_golf.origin")),
                        built ? "built: Golf of the Week stays where it stands" : "never built: the others move");
            }
        }
    }

    @Test
    void anOriginMissingFromA035FileThatBuiltGoesBackToWhere035BuiltIt() {
        try (Install install = new Install()) {
            install.edition(Slots.TINY_GOLF);
            YamlConfiguration v035 = LayoutFixtures.v035();
            v035.set("games.fresh.slots.fresh_tiny_golf.origin", null); // deleted while the server was stopped
            v035.set("games.clubhouse.origin", null);
            Disk disk = new Disk(v035);
            migrate(disk);
            assertEquals(List.of(7488, 160, 5504), list(disk.load().get("games.fresh.slots.fresh_tiny_golf.origin")),
                    "the backfill wrote this version's spot");
            assertEquals(LayoutGuard.Decision.LEGACY,
                    LayoutGuard.run(LayoutGuard.Store.of(install.db), disk, NOW).decision(), "built");
            assertNull(LayoutScenarios.legacyProblem(disk.load(), install),
                    "the guard put back 0.35's, where Tiny Golf stands and is claimed");
        }
    }

    // ---- once, and crash-safe ---------------------------------------------------------------------

    @Test
    void aSecondStartChangesNothing() {
        for (boolean built : new boolean[]{false, true}) {
            try (Install install = new Install()) {
                if (built) {
                    install.edition(Slots.SKY_RINGS).kept(1).clubhouse();
                }
                Disk disk = new Disk(LayoutFixtures.v035());
                start(disk, LayoutGuard.Store.of(install.db));
                String file = disk.text;
                String stamp = stamp(install);
                int saves = disk.saves;
                Faulty store = new Faulty(LayoutGuard.Store.of(install.db));
                store.failFacts = true;
                LayoutGuard.Outcome again = start(disk, store);
                assertNull(again.decision(), "decided already: nothing to do");
                assertEquals(file, disk.text, "the file is the same");
                assertEquals(saves, disk.saves, "and not written again");
                assertEquals(stamp, stamp(install), "the stamp is the same");
                assertEquals(0, store.stamps, "and not written again");
            }
        }
    }

    /** Where a start can stop, and how the next start sees it. */
    private enum Crash {
        /** After revision 19 marked and saved the file, before the database opened. */
        BEFORE_THE_GUARD,
        /** The guard decided, and the server stopped while it wrote config.yml. */
        WRITING_THE_FILE,
        /** The file was written, and the server stopped before the stamp. */
        BEFORE_THE_STAMP
    }

    @Test
    void aStopBetweenAnyTwoStepsEndsExactlyAsAStartWithoutOne() {
        for (boolean built : new boolean[]{false, true}) {
            String clean;
            String cleanStamp;
            try (Install install = new Install()) {
                fill(install, built);
                Disk disk = new Disk(LayoutFixtures.v035());
                start(disk, LayoutGuard.Store.of(install.db));
                clean = disk.text;
                cleanStamp = stamp(install).split("\\|")[0];
            }
            for (Crash crash : Crash.values()) {
                String what = (built ? "built" : "never built") + ", stopped " + crash;
                try (Install install = new Install()) {
                    fill(install, built);
                    Disk disk = new Disk(LayoutFixtures.v035());
                    Faulty store = new Faulty(LayoutGuard.Store.of(install.db));
                    switch (crash) {
                        case BEFORE_THE_GUARD -> migrate(disk);
                        case WRITING_THE_FILE -> {
                            disk.failSave = true;
                            assertThrows(IllegalStateException.class, () -> start(disk, store), what);
                            assertTrue(LayoutGuard.pending(disk.load()), what + ": the file still carries the mark");
                        }
                        case BEFORE_THE_STAMP -> {
                            store.failStamp = true;
                            assertThrows(IllegalStateException.class, () -> start(disk, store), what
                                    + ": the run stops, and the plugin keeps the Games off until it finishes");
                            assertFalse(LayoutGuard.pending(disk.load()), what + ": the file was written");
                        }
                    }
                    assertNull(stamp(install), what + ": nothing is stamped yet");
                    String written = disk.text;
                    int saves = disk.saves;
                    LayoutGuard.Outcome o = start(disk, store);
                    assertEquals(built ? LayoutGuard.Decision.LEGACY : LayoutGuard.Decision.NEW, o.decision(),
                            what + ": the next start decides the same");
                    assertEquals(clean, disk.text, what + ": and the file ends exactly as without a stop");
                    assertEquals(cleanStamp, stamp(install).split("\\|")[0], what + ": the same stamp");
                    if (crash == Crash.BEFORE_THE_STAMP) {
                        assertEquals(written, disk.text, what + ": the file already had the decision");
                        assertEquals(saves, disk.saves, what + ": so it isn't written again");
                    }
                    if (built) {
                        assertNull(LayoutScenarios.legacyProblem(disk.load(), install), what + ": nothing moved");
                    }
                }
            }
        }
    }

    private static void fill(Install install, boolean built) {
        if (built) {
            install.edition(Slots.EASY_DROPPER).recall(Slots.CLASSIC_RINGS).kept(2).arena();
        }
    }

    // ---- a stamped server ---------------------------------------------------------------------------

    @Test
    void aConfigFromBeforeTheUpdatePutBackLaterGetsTheStampedDecisionNeverANewOne() {
        // Kept 0.35's layout: a 0.35 config.yml put back is given half_gap: 32 again, so nothing moves.
        try (Install install = new Install()) {
            install.edition(Slots.DAILY_GOLF);
            Disk disk = new Disk(LayoutFixtures.v035());
            start(disk, LayoutGuard.Store.of(install.db));
            Disk restored = new Disk(LayoutFixtures.v035());
            Faulty store = new Faulty(LayoutGuard.Store.of(install.db));
            store.failFacts = true;
            LayoutGuard.Outcome o = start(restored, store);
            assertEquals(LayoutGuard.Decision.LEGACY, o.decision(), "the stamped decision");
            assertNull(LayoutScenarios.legacyProblem(restored.load(), install), "every spot and shape kept again");
            assertEquals(0, store.factsRead, "the database's facts weren't asked again");
        }
        // Took the new layout and built there since: a 0.35 file put back is moved again, so the claims
        // at the new spots still match.
        try (Install install = new Install()) {
            Disk disk = new Disk(LayoutFixtures.v035());
            start(disk, LayoutGuard.Store.of(install.db));
            install.set(GenAdminKeys.claim("fresh_golf"), "games,7488,160,4096,64,16,128,576");
            Disk restored = new Disk(LayoutFixtures.v035());
            Faulty store = new Faulty(LayoutGuard.Store.of(install.db));
            store.failFacts = true;
            assertEquals(LayoutGuard.Decision.NEW, start(restored, store).decision(),
                    "the stamped decision, though something is built now");
            assertNull(LayoutScenarios.newProblem(restored.load()), "the new layout again");
        }
    }

    @Test
    void aStampThatIsntADecisionOrAFileThatCantBeReadStopsTheRun() {
        try (Install install = new Install()) {
            install.set(LayoutGuard.STAMP_KEY, "perhaps");
            Disk disk = new Disk(LayoutFixtures.v035());
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> start(disk, LayoutGuard.Store.of(install.db)), "no guessing");
            assertTrue(e.getMessage().contains(LayoutGuard.STAMP_KEY), "the message names the key: " + e.getMessage());
            assertTrue(LayoutGuard.pending(disk.load()), "the file keeps its mark");
        }
        try (Install install = new Install()) {
            LayoutGuard.ConfigFile broken = new LayoutGuard.ConfigFile() {
                @Override
                public FileConfiguration load() {
                    return null;
                }

                @Override
                public boolean save(FileConfiguration c) {
                    return false;
                }
            };
            assertThrows(IllegalStateException.class, () -> LayoutGuard.run(LayoutGuard.Store.of(install.db), broken,
                    NOW), "a config.yml that can't be read");
            assertNull(stamp(install), "nothing stamped");
        }
    }
}
