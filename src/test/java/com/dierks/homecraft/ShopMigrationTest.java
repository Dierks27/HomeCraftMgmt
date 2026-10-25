package com.dierks.homecraft;

import com.dierks.homecraft.games.gen.LayoutFixtures;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 22, the token shop ({@link ShopMigration}, SHOP-V2-SPEC §7.2-7.5), against verbatim copies of
 * 0.35.0's, 0.36.0's and 0.37.2's bundled config.yml.
 *
 * <p>Pinned here: the owner's 0.37.2 file ends with exactly the bundled counter and crate, one INFO each and no
 * WARN, and so do 0.36.0's and 0.35.0's; a hat with only the owner's picture moves with it; an edited row, an
 * owner's row on a new id and an edited crate are kept with a WARN and the line to paste; a deleted or reordered
 * shipped row holds nothing back (red team D-m14) and is not put back; new rows already there are not added twice;
 * a second run and a revision-22 file change nothing; the frozen tables are what each version shipped and what
 * this one ships, in order, at the end of the list; and the pre-17 and pre-14 paths need no step 2.
 */
class ShopMigrationTest {

    private static final String PRIZES = ShopMigration.PRIZES;
    private static final String CRATE = ShopMigration.CRATE;

    private static List<String> warns(List<String> log) {
        return log.stream().filter(l -> l.startsWith(HomeCraftManagement.WARN)).toList();
    }

    private static List<String> infos(List<String> log) {
        return log.stream().filter(l -> !l.startsWith(HomeCraftManagement.WARN)).toList();
    }

    private static List<Map<String, Object>> rows(FileConfiguration c, String path) {
        return ArcadeConfigMigration.mapRows(c.getList(path));
    }

    private static List<String> ids(FileConfiguration c) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows(c, PRIZES)) {
            out.add(ShopMigration.idOf(r));
        }
        return out;
    }

    private static Map<String, Object> rowOf(FileConfiguration c, String id) {
        for (Map<String, Object> r : rows(c, PRIZES)) {
            if (ShopMigration.idOf(r).equals(id)) {
                return r;
            }
        }
        return null;
    }

    private static void setRow(FileConfiguration c, String id, java.util.function.Consumer<Map<String, Object>> edit) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : rows(c, PRIZES)) {
            if (ShopMigration.idOf(r).equals(id)) {
                edit.accept(r);
            }
            out.add(r);
        }
        c.set(PRIZES, out);
    }

    private static List<String> addedIds() {
        return ShopMigration.ADDED_22.stream().map(ShopMigration::idOf).toList();
    }

    private static void assertCounterAndCrateAreBundled(FileConfiguration c, String why) {
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertTrue(ArcadeConfigMigration.sameRows(rows(c, PRIZES), rows(bundled, PRIZES)),
                why + ": the counter is the bundled one, row by row: " + ids(c));
        assertTrue(ArcadeConfigMigration.sameRows(rows(c, CRATE), rows(bundled, CRATE)), why + ": and the crate");
    }

    /** The one revision-22 step on its own (a revision-21 file). */
    private static List<String> apply(FileConfiguration c) {
        List<String> log = new ArrayList<>();
        ShopMigration.apply(c, log);
        return log;
    }

    // ---- 1, 2: the files that shipped -----------------------------------------------------------

    @Test
    void theOwners0372FileEndsWithTheBundledCounterAndCrate() {
        YamlConfiguration c = LayoutFixtures.v0372();
        assertEquals(21, c.getInt("config_revision"), "fixture: 0.37.2 is revision 21");
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: no WARN");
        assertCounterAndCrateAreBundled(c, "0.37.2");
        assertEquals(24, c.getInt("config_revision"),
                "and on through revisions 23 and 24 (GrandShowMigrationTest, FireworkRemoteMigrationTest)");
        List<String> counter = log.stream().filter(l -> l.startsWith("Config migration: Prize Counter - ")).toList();
        assertEquals(3, counter.size(), "one INFO for the counter, revision 23's Grand Firework Show and revision"
                + " 24's bigger shows: " + log);
        assertTrue(counter.get(0).contains("32 new rows") && counter.get(0).contains("10 rows updated"), counter.get(0));
        assertTrue(counter.get(1).contains("added the Grand Firework Show"), counter.get(1));
        assertTrue(counter.get(2).contains("added the Super Firework Show"), counter.get(2));
        assertEquals(1, log.stream().filter(l -> l.contains("Arcade Crate no longer holds hats")).count(),
                "and one for the crate: " + log);
        assertEquals(1, log.stream().filter(l -> l.startsWith("Config migration: arcade.events.candy - ")).count(),
                "and revision 23's one for the candy: " + log);
        assertEquals(6, log.size(), "and nothing else but the revision: " + log);
        assertEquals("Config migration: config_revision 21 → 24.", log.get(5));
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(c, "world"), "a second start runs nothing");
    }

    @Test
    void the036And035FilesEndWithTheBundledCounterAndCrateToo() {
        for (YamlConfiguration c : List.of(LayoutFixtures.v036(), LayoutFixtures.v035())) {
            List<String> log = HomeCraftManagement.migrateConfig(c, "world");
            assertTrue(warns(log).stream().noneMatch(l -> l.contains(PRIZES) || l.contains(CRATE)),
                    "no WARN about the counter or the crate: " + warns(log));
            assertCounterAndCrateAreBundled(c, "revision " + c.getInt("config_revision"));
        }
    }

    // ---- 3: a hat the owner gave a picture ----------------------------------------------------------

    @Test
    void aHatWithOnlyTheOwnersPictureMovesWithIt() {
        YamlConfiguration c = LayoutFixtures.v0372();
        setRow(c, "hat_fox", r -> {
            r.put("texture", "eyJmb3gi");
            Map<String, Object> icon = new LinkedHashMap<>(asMap(r.get("icon")));
            icon.put("texture", "eyJmb3gi");
            r.put("icon", icon);
        });
        List<String> log = apply(c);
        Map<String, Object> fox = rowOf(c, "hat_fox");
        assertEquals("LOOKS", fox.get("tab"), "moved to the Looks tab");
        assertEquals(90, fox.get("days"));
        assertEquals("eyJmb3gi", fox.get("texture"), "with their picture");
        assertEquals("eyJmb3gi", asMap(fox.get("icon")).get("texture"), "on the tile too");
        assertEquals(List.of("Wear it on your head!", "Yours for 90 days."), fox.get("description"));
        assertEquals(1, log.stream().filter(l -> l.contains("moved your Fox Hat to the Looks tab")).count(),
                "one INFO for it: " + log);
        assertEquals(List.of(), warns(log), "no WARN");
        assertEquals("LOOKS", rowOf(c, "hat_bunny").get("tab"), "the rest move");
        assertEquals(30, rowOf(c, "trail_hearts").get("days"));
        assertTrue(ids(c).containsAll(addedIds()), "and the 32 are appended");
        assertEquals(List.of(), apply(c), "a second run leaves the textured hat alone");
    }

    // ---- 4: a row the owner changed -------------------------------------------------------------------

    @Test
    void anEditedTrailIsKeptWithOneWarnAndItsPasteLine() {
        YamlConfiguration c = LayoutFixtures.v0372();
        setRow(c, "trail_notes", r -> r.put("cost_tokens", 80));
        List<String> log = apply(c);
        Map<String, Object> notes = rowOf(c, "trail_notes");
        assertEquals(80, notes.get("cost_tokens"), "their price");
        assertEquals(7, notes.get("days"), "and the rest of their row");
        List<String> w = warns(log);
        assertEquals(2, w.size(), "one WARN and its paste line: " + w);
        assertTrue(w.get(0).contains("arcade.prizes → trail_notes"), w.get(0));
        assertEquals(HomeCraftManagement.WARN + "  - { id: trail_notes, tab: COSMETICS, display: \"&aMusic Trail\","
                + " description: [\"Music notes follow you\", \"for a month.\"], cost_tokens: 100, icon: { texture: \"\","
                + " material: NOTE_BLOCK }, type: trail, particle: NOTE, days: 30 }", w.get(1));
        assertEquals(30, rowOf(c, "trail_hearts").get("days"), "the others move");
    }

    // ---- 5, 6: deleted, reordered and already-present rows -----------------------------------------------

    @Test
    void aDeletedOrSwappedShippedRowHoldsNothingBackAndIsNotPutBack() {
        YamlConfiguration c = LayoutFixtures.v0372();
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        list.removeIf(o -> o instanceof Map<?, ?> m && "name_color_aqua".equals(m.get("id")));
        int a = 0;
        int b = 1;
        Object first = list.get(a);
        list.set(a, list.get(b));
        list.set(b, first);
        c.set(PRIZES, list);
        List<String> log = apply(c);
        assertFalse(ids(c).contains("name_color_aqua"), "the deleted row is not re-added");
        assertEquals(addedIds(), ids(c).subList(ids(c).size() - 32, ids(c).size()), "the 32, in order, at the end");
        assertEquals(2, infos(log).size(), "one INFO for the counter and one for the crate: " + log);
        assertEquals(List.of(), warns(log));
    }

    @Test
    void aNewIdTheOwnerAlreadyUsesKeepsTheirRowWithOneWarnAndThePasteLine() {
        YamlConfiguration c = LayoutFixtures.v0372();
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("id", "trail_snow");
        mine.put("display", "&fMy Snow");
        mine.put("cost_tokens", 50);
        mine.put("type", "trail");
        mine.put("particle", "SNOWFLAKE");
        list.add(mine);
        c.set(PRIZES, list);
        List<String> log = apply(c);
        assertEquals(50, rowOf(c, "trail_snow").get("cost_tokens"), "their own row stays");
        assertEquals(1, ids(c).stream().filter("trail_snow"::equals).count(), "and is not doubled");
        List<String> w = warns(log);
        assertEquals(2, w.size(), "one WARN and the shipped line: " + w);
        assertTrue(w.get(0).contains("trail_snow"), w.get(0));
        assertTrue(w.get(1).startsWith(HomeCraftManagement.WARN + "  - { id: trail_snow, tab: COSMETICS,"), w.get(1));
        assertTrue(ids(c).containsAll(addedIds()), "the other 31 are appended");
    }

    @Test
    void someNewRowsAlreadyThereOnlyTheAbsentAreAppended() {
        YamlConfiguration c = LayoutFixtures.v0372();
        List<Object> list = new ArrayList<>(c.getList(PRIZES));
        list.add(ShopMigration.copy(ShopMigration.ADDED_22.get(5)));
        list.add(ShopMigration.copy(ShopMigration.ADDED_22.get(31)));
        c.set(PRIZES, list);
        int before = list.size();
        List<String> log = apply(c);
        assertEquals(before + 30, ids(c).size(), "30 appended");
        assertEquals(1, ids(c).stream().filter("trail_glow"::equals).count());
        assertEquals(1, ids(c).stream().filter("trophy_month"::equals).count());
        assertEquals(List.of(), warns(log), "theirs is the shipped row: no WARN");
        assertTrue(infos(log).get(0).contains("30 new rows"), infos(log).get(0));
    }

    // ---- 7: an edited crate -----------------------------------------------------------------------------

    @Test
    void anEditedCrateIsKeptWithOneWarnNamingTheLine() {
        YamlConfiguration c = LayoutFixtures.v0372();
        List<Map<String, Object>> crate = rows(c, CRATE);
        crate.get(4).put("amount", 7);
        c.set(CRATE, new ArrayList<Object>(crate));
        List<String> log = apply(c);
        assertTrue(ArcadeConfigMigration.sameRows(crate, rows(c, CRATE)), "their crate stays");
        List<String> w = warns(log);
        assertEquals(2, w.size(), "one WARN and the line to delete: " + w);
        assertTrue(w.get(0).contains(CRATE), w.get(0));
        assertEquals(HomeCraftManagement.WARN + "  - { type: prize, prize: [hat_bunny, hat_panda, hat_fox, hat_penguin,"
                + " hat_frog, hat_cake], weight: 3 }", w.get(1));
    }

    // ---- 8: idempotent, and nothing of 22's past 22 ----------------------------------------------------

    @Test
    void aRevision22FileRunsNothingOf22sAndASecondApplyChangesNothing() {
        YamlConfiguration at22 = LayoutFixtures.v0372();
        at22.set("config_revision", 22);
        List<Map<String, Object>> counter = rows(at22, PRIZES);
        List<Map<String, Object>> crate = rows(at22, CRATE);
        List<String> log22 = HomeCraftManagement.migrateConfig(at22, "world");
        assertTrue(log22.stream().noneMatch(l -> l.contains("32 new rows") || l.contains("Arcade Crate no longer")),
                "no revision-22 step runs from 22, only 23's: " + log22);
        assertTrue(ArcadeConfigMigration.sameRows(crate, rows(at22, CRATE)), "the crate is as it was");
        assertEquals(counter.size() + 7, rows(at22, PRIZES).size(), "the counter gains only the Grand Firework Show,"
                + " the Firework Launcher and the Mega Firework Show (23), and the Super, Ultra and Ultimate shows and the"
                + " Firework Remote (24)");

        YamlConfiguration at24 = LayoutFixtures.v0372();
        at24.set("config_revision", 24);
        String before = at24.saveToString();
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(at24, "world"), "no step runs from 24");
        assertEquals(before, at24.saveToString());

        YamlConfiguration c = LayoutFixtures.v0372();
        apply(c);
        String once = c.saveToString();
        assertEquals(List.of(), apply(c), "the second run logs nothing");
        assertEquals(once, c.saveToString(), "and changes nothing");
    }

    // ---- 9: the frozen tables ------------------------------------------------------------------------

    @Test
    void theOldFormsAreWhatEachVersionShipped() {
        for (YamlConfiguration old : List.of(LayoutFixtures.v035(), LayoutFixtures.v036(), LayoutFixtures.v0372())) {
            for (ShopMigration.Change ch : ShopMigration.CHANGED_22) {
                Map<String, Object> shipped = rowOf(old, ch.id());
                assertTrue(shipped != null && ShopMigration.same(shipped, ch.old()), ch.id() + " in revision "
                        + old.getInt("config_revision") + ": " + shipped);
            }
            assertTrue(ArcadeConfigMigration.sameRows(rows(old, CRATE), ShopMigration.CRATE_OLD),
                    "the crate in revision " + old.getInt("config_revision"));
            for (String id : addedIds()) {
                assertNull(rowOf(old, id), id + " is new");
            }
        }
    }

    @Test
    void theNewFormsAreTheBundledRowsInOrderAtTheEnd() throws Exception {
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (ShopMigration.Change ch : ShopMigration.CHANGED_22) {
            assertTrue(ShopMigration.same(rowOf(bundled, ch.id()), ch.now()), ch.id() + " is the bundled row");
        }
        List<Map<String, Object>> all = rows(bundled, PRIZES);
        List<Map<String, Object>> tail = all.subList(all.size() - ShopMigration.ADDED_22.size(), all.size());
        assertTrue(ArcadeConfigMigration.sameRows(tail, ShopMigration.ADDED_22),
                "the 32 rows, in order, are the end of the bundled list");
        assertEquals(32, ShopMigration.ADDED_22.size());
        assertEquals(10, ShopMigration.CHANGED_22.size());
        assertTrue(ArcadeConfigMigration.sameRows(rows(bundled, CRATE), ShopMigration.CRATE_NEW), "the bundled crate");

        // Each new row's paste line is the bundled file's own line, word for word.
        String text = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        for (Map<String, Object> r : ShopMigration.ADDED_22) {
            assertTrue(text.contains("\n    " + ArcadeConfigMigration.flowRow(r) + "\n"),
                    ShopMigration.idOf(r) + "'s line: " + ArcadeConfigMigration.flowRow(r));
        }
        for (ShopMigration.Change ch : ShopMigration.CHANGED_22) {
            assertTrue(text.contains("\n    " + ArcadeConfigMigration.flowRow(ch.now()) + "\n"), ch.id());
        }
        assertTrue(text.contains("\n    # Hats: paste a minecraft-heads.com \"Value\" into texture to show one (blank ="
                + " hidden). On Bedrock a\n    # textured head shows as a plain head unless its skin is listed in"
                + " Geyser's custom-skulls.yml.\n"), "the hat comment");
        for (Map<String, Object> r : ShopMigration.ADDED_22) {
            assertFalse(r.containsKey("renew_tokens"), "no shipped row sets renew_tokens (D6)");
        }
    }

    @Test
    void theTablesAreLiteralsNeverTheBundledFile() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/dierks/homecraft/ShopMigration.java"),
                StandardCharsets.UTF_8);
        assertFalse(src.contains("bundled("), "ShopMigration never reads the bundled config.yml");
        assertFalse(src.contains("getResource"), "nor any other file");
    }

    // ---- 10: the older paths -------------------------------------------------------------------------

    @Test
    void thePre17AndPre14PathsEndAtTheBundledCounterWithNoStepTwo() throws Exception {
        YamlConfiguration v034 = LayoutFixtures.bundled();
        v034.set("games", null);
        v034.set("config_revision", 16);
        List<String> log = HomeCraftManagement.migrateConfig(v034, "world");
        assertCounterAndCrateAreBundled(v034, "0.34 (revision 16)");
        assertTrue(log.stream().noneMatch(l -> l.contains("Prize Counter - ") || l.contains("Arcade Crate no longer")),
                "revision 22 finds nothing to do: " + log);

        YamlConfiguration pre14 = LayoutFixtures.bundled();
        pre14.set("config_revision", 13);
        List<Object> three = new ArrayList<>();
        three.add(new LinkedHashMap<>(Map.of("id", "filament_bundle", "display", "&fFilament Bundle", "cost_tokens", 6,
                "type", "filament", "amount", 8)));
        three.add(new LinkedHashMap<>(Map.of("id", "display_case", "display", "&bDisplay Case", "cost_tokens", 25,
                "type", "block", "block", "display_case")));
        three.add(new LinkedHashMap<>(Map.of("id", "starter_pack", "display", "&dStarter Pack", "cost_tokens", 30,
                "type", "pack", "pack", "starter")));
        pre14.set(PRIZES, three);
        pre14.set("arcade.crates.arcade_crate", null); // revision 14 adds it beside the owner's crates
        pre14.set("arcade.crates.vip.display", "VIP Crate");
        pre14.set("arcade.crates.vip.cost_tokens", 20);
        pre14.set("arcade.crates.vip.rewards", List.of(Map.of("type", "filament", "amount", 5, "weight", 1)));
        List<String> log14 = HomeCraftManagement.migrateConfig(pre14, "world");
        assertCounterAndCrateAreBundled(pre14, "a pre-14 file");
        assertTrue(log14.stream().noneMatch(l -> l.contains("Prize Counter - ") || l.contains("Arcade Crate no")),
                "revision 14 brought every row, so revision 22 logs nothing: " + log14);
    }

    // ---- the absent cases --------------------------------------------------------------------------

    @Test
    void anAbsentListOrCrateIsLeftToTheBackfill() {
        YamlConfiguration c = LayoutFixtures.v0372();
        c.set(PRIZES, null);
        c.set(CRATE, null);
        assertEquals(List.of(), apply(c));
        assertFalse(c.contains(PRIZES));
        assertFalse(c.contains(CRATE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
