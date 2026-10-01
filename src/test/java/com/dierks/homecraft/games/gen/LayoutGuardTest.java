package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.gen.LayoutGuard.Area;
import com.dierks.homecraft.games.gen.LayoutGuard.Decision;
import com.dierks.homecraft.games.gen.LayoutGuard.Facts;
import com.dierks.homecraft.games.gen.LayoutGuard.Held;
import com.dierks.homecraft.games.gen.LayoutGuard.Stamp;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenAdminKeys;
import com.dierks.homecraft.games.gen.engine.KeepArea;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy guard's pure parts (LAYOUT-DECISIONS item 5): the frozen 0.35 table and this version's,
 * what counts as built, the stamp, and what each decision writes into a config file. The whole
 * upgrade (the migration, the backfill, the database, crashes) is {@code LayoutGuardUpgradeTest}.
 */
class LayoutGuardTest {

    private static Area area(String id) {
        return LayoutGuard.AREAS.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Integer> list(Object raw) {
        List<Integer> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            out.add(((Number) o).intValue());
        }
        return out;
    }

    // ---- the two tables ------------------------------------------------------------------------

    @Test
    void theFrozenTableIsExactlyWhat0350ShippedAndWhatItBuiltAt() {
        YamlConfiguration v035 = LayoutFixtures.v035();
        assertEquals(16, LayoutGuard.AREAS.size(), "9 courses, 4 Classics, the keep area, the arena and the Clubhouse");
        for (Area a : LayoutGuard.AREAS) {
            assertEquals(a.legacy(), list(LayoutGuard.origin(v035, a)), a.id() + ": 0.35.0's config.yml says so");
            assertEquals(Held.LEGACY, LayoutGuard.held(v035, a), a.id() + ": a 0.35 file holds exactly it");
            if (a.def() != null) {
                assertEquals(List.of(LegacyBoxes.origin(a.def())[0], LegacyBoxes.origin(a.def())[1],
                        LegacyBoxes.origin(a.def())[2]), a.legacy(), a.id() + ": the goldens' 0.35 spot too");
            }
        }
        assertEquals(List.of(LegacyBoxes.keep().x(), LegacyBoxes.keep().y(), LegacyBoxes.keep().z()),
                area("keep").legacy(), "the keep area's 0.35 corner");
        assertEquals(List.of(LegacyBoxes.clubhouse().minX(), LegacyBoxes.clubhouse().minY(),
                LegacyBoxes.clubhouse().minZ()), area("clubhouse").legacy(), "the Clubhouse's");
        assertEquals(List.of(LegacyBoxes.fallingFloors().minX(), LegacyBoxes.fallingFloors().minY(),
                LegacyBoxes.fallingFloors().minZ()), area("falling_floors").legacy(), "the arena's");
    }

    @Test
    void theShippedColumnIsWhatThisVersionShipsInCodeAndInConfigYml() {
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (Area a : LayoutGuard.AREAS) {
            assertEquals(a.shipped(), list(LayoutGuard.origin(bundled, a)), a.id() + ": the bundled config.yml");
            assertEquals(Held.SHIPPED, LayoutGuard.held(bundled, a), a.id() + ": a fresh install holds it");
            assertFalse(a.shipped().equals(a.legacy()), a.id() + ": every place moves");
            if (a.def() != null) {
                assertEquals(List.of(a.def().originX(), a.def().originY(), a.def().originZ()), a.shipped(),
                        a.id() + ": the slot's own default");
            }
        }
        KeepArea keep = DailySettings.Archive.shipped().keep();
        assertEquals(List.of(keep.x(), keep.y(), keep.z()), area("keep").shipped(), "the keep area's default");
        assertEquals(ClubhouseSettings.ORIGIN, area("clubhouse").shipped(), "the Clubhouse's");
        assertEquals(FallingFloorsSettings.ORIGIN, area("falling_floors").shipped(), "the arena's");
        assertNull(bundled.get(LayoutGuard.PENDING_KEY), "a fresh install's file carries no mark");
        for (Area a : LayoutGuard.AREAS) {
            if (a.gapPath() != null) {
                assertNull(bundled.get(a.gapPath()), a.gapPath() + " isn't shipped, so the backfill never adds it");
            }
        }
    }

    @Test
    void theirKeysAreTheOnesTheSettingsRead() {
        assertEquals("games.fresh.slots.fresh_parkour.origin", area("fresh_parkour").originPath(), "a course");
        assertEquals("games.fresh.slots.fresh_parkour.half_gap", area("fresh_parkour").gapPath(), "its gap");
        assertEquals("games.fresh.classics.slots.fresh_classic_golf.half_gap", area("fresh_classic_golf").gapPath(),
                "a Classic's gap");
        assertEquals("games.fresh.keep.area", area("keep").originPath(), "the keep area");
        assertEquals("games.fresh.keep.plot_gap", area("keep").gapPath(), "its plot gap");
        assertEquals("games." + com.dierks.homecraft.config.GamesConfig.block(
                com.dierks.homecraft.games.clubhouse.Clubhouse.SPEC.id()) + ".origin", area("clubhouse").originPath(),
                "the Clubhouse's block");
        assertEquals("games." + com.dierks.homecraft.config.GamesConfig.block(
                com.dierks.homecraft.games.arena.FallingFloors.SPEC.id()) + ".origin",
                area("falling_floors").originPath(), "the arena's block");
        assertNull(area("clubhouse").gapPath(), "one box: no gap");
        assertTrue(DailySettings.OPTIONAL_KEYS.contains("slots.fresh_parkour.half_gap")
                && DailySettings.OPTIONAL_KEYS.contains("classics.slots.fresh_classic_golf.half_gap")
                && DailySettings.OPTIONAL_KEYS.contains("keep.plot_gap"), "every gap it writes is a known key");
        assertEquals(LayoutGuard.CLUBHOUSE_CLAIM, com.dierks.homecraft.games.clubhouse.ClubhouseRoom.CLAIM_KEY,
                "the Clubhouse's claim key, spelled out");
        assertEquals(LayoutGuard.ARENA_CLAIM, com.dierks.homecraft.games.arena.ArenaService.CLAIM_KEY,
                "the arena's claim key, spelled out");
    }

    @Test
    void onlyAnExactValueCountsAsUntouched() {
        List<Integer> v = List.of(4096, 160, 4096);
        assertTrue(LayoutGuard.same(List.of(4096, 160, 4096), v), "the list itself");
        assertTrue(LayoutGuard.same(List.of(4096.0, 160, 4096L), v), "4096.0 and a long 4096 are 4096");
        assertTrue(LayoutGuard.same(List.of((short) 4096, (short) 160, (short) 4096), v), "shorts");
        assertFalse(LayoutGuard.same(List.of(4096.7, 160, 4096), v), "4096.7 is not (no rounding)");
        assertFalse(LayoutGuard.same(List.of("4096", 160, 4096), v), "nor is text");
        assertFalse(LayoutGuard.same(List.of(4096, 161, 4096), v), "one block off");
        assertFalse(LayoutGuard.same(List.of(4096, 160), v), "two numbers");
        assertFalse(LayoutGuard.same(List.of(4096, 160, 4096, 0), v), "four");
        assertFalse(LayoutGuard.same("4096, 160, 4096", v), "a string");
        assertFalse(LayoutGuard.same(null, v), "nothing");
        assertTrue(LayoutGuard.same(List.of(Integer.valueOf(4096), Integer.valueOf(160), Integer.valueOf(4096)), v),
                "boxed integers above 127 are compared by value");
    }

    // ---- what counts as built -----------------------------------------------------------------

    @Test
    void nothingIsBuiltOnAnEmptyDatabaseOrOneWithOnlySettings() {
        assertEquals(List.of(), LayoutGuard.built(new Facts(Map.of(), 0, List.of())), "a new database");
        Map<String, String> settings = new HashMap<>();
        settings.put(GenAdminKeys.schedule(), "7|monday|1759000000000");
        settings.put(GenAdminKeys.enabled("fresh_golf"), "false");
        settings.put(GenAdminKeys.tier("fresh_parkour"), "hard");
        settings.put(GenAdminKeys.pin("fresh_rings"), "1a2b:1:20000");
        settings.put("gen.clubhouse.off", "true");
        settings.put("gen.clubhouse.mode", "hand");
        settings.put("gen.clubhouse.hand.spawn", "games,1,2,3,0,0");
        settings.put(GenAdminKeys.goals(20_000), "3:1");
        settings.put(GenAdminKeys.claim("fresh_golf"), "");
        assertEquals(List.of(), LayoutGuard.built(new Facts(settings, 0, List.of())),
                "switches, tiers, pins, goals, an owner-built Clubhouse room and a blank value build nothing at a spot");
    }

    @Test
    void eachKindOfBuiltThingIsFoundOnItsOwn() {
        Map<String, List<String>> cases = new java.util.LinkedHashMap<>();
        cases.put("claims", LayoutGuard.built(meta(GenAdminKeys.claim("fresh_parkour"), "games,4352,160,4096,64,48,64")));
        cases.put("wet", LayoutGuard.built(meta(GenAdminKeys.wet("fresh_dropper"), "games,5376,160,4160,64,64,16")));
        cases.put("recalls", LayoutGuard.built(meta(GenAdminKeys.recall("fresh_classic_golf"), "fresh_golf|7:38|1|2|0")));
        cases.put("kept", LayoutGuard.built(meta(GenAdminKeys.plot(3), "cliff_hop|games|4400,128,5376,4543,303,5711|x|y")));
        cases.put("keeping", LayoutGuard.built(meta(GenAdminKeys.KEEP_PENDING, "keep|3|cliff_hop")));
        cases.put("clubhouse", LayoutGuard.built(meta("gen.clubhouse.claim", "games,5376,160,4448,32,16,32")));
        cases.put("arena", LayoutGuard.built(meta("gen.floors.claim", "games,5376,176,4352,48,40,48")));
        cases.put("archive", LayoutGuard.built(new Facts(Map.of(), 3, List.of())));
        cases.put("rows", LayoutGuard.built(new Facts(Map.of(), 0, List.of("fresh_golf"))));
        assertEquals(LayoutGuard.RULES.stream().map(LayoutGuard.Evidence::name).toList(), List.copyOf(cases.keySet()),
                "one case per rule");
        cases.forEach((rule, why) -> assertEquals(1, why.size(), rule + " alone is built: " + why));
        assertEquals(List.of("Parkour's area is claimed"), cases.get("claims"), "said plainly");
        assertEquals(List.of("Classic Golf holds a recalled course"), cases.get("recalls"), "a Classic by its name");
        assertEquals(List.of("3 Fresh Courses sets in the archive"), cases.get("archive"), "counted");
    }

    private static Facts meta(String key, String value) {
        return new Facts(Map.of(key, value), 0, List.of());
    }

    // ---- the stamp ----------------------------------------------------------------------------

    @Test
    void theStampReadsBackAndAnythingElseIsNoStamp() {
        Stamp s = new Stamp(Decision.LEGACY, 1_790_000_000_000L, "Parkour's area is claimed; 2 kept courses");
        assertEquals(s, Stamp.parse(s.text()), "legacy round trip");
        assertEquals("legacy|1790000000000|Parkour's area is claimed; 2 kept courses", s.text(), "its text");
        Stamp n = new Stamp(Decision.NEW, 5L, "");
        assertEquals(n, Stamp.parse(n.text()), "new round trip");
        assertEquals("a/b", Stamp.parse(new Stamp(Decision.NEW, 1L, "a|b").text()).why(),
                "a bar in the why can't split the stamp");
        assertEquals(Decision.NEW, Stamp.parse("new|7").decision(), "a stamp without a why");
        assertNull(Stamp.parse("maybe|7|x"), "not a decision");
        assertNull(Stamp.parse("legacy|soon|x"), "not a time");
        assertNull(Stamp.parse("legacy"), "too short");
        assertNull(Stamp.parse(null), "none");
        assertEquals("15 Sep 2026", new Stamp(Decision.LEGACY, 1_789_430_400_000L, "").day(), "the day, for admins");
    }

    // ---- what a decision writes ---------------------------------------------------------------

    @Test
    void legacyWritesEveryOldSpotAndShapeAndAgainChangesNothing() {
        YamlConfiguration c = LayoutFixtures.v035();
        LayoutGuard.markPending(c);
        List<String> log = new ArrayList<>();
        assertTrue(LayoutGuard.apply(c, Decision.LEGACY, true, List.of("Parkour's area is claimed"), null, log),
                "the file changes");
        assertFalse(LayoutGuard.pending(c), "the mark is gone");
        for (Area a : LayoutGuard.AREAS) {
            assertEquals(Held.LEGACY, LayoutGuard.held(c, a), a.id() + " keeps its 0.35 spot");
            if (a.gapPath() != null) {
                assertEquals(a.legacyGap(), c.getInt(a.gapPath(), -1), a.gapPath() + " is its 0.35 gap");
            }
        }
        assertEquals(1, log.size(), "one line: " + log);
        assertTrue(log.get(0).startsWith(LayoutGuard.WARN) && log.get(0).contains("Parkour's area is claimed")
                && log.get(0).contains("Moving an area by hand"), "a warning that says why and how to move: " + log);
        assertEquals(LayoutFixtures.legacyPlaces(), LayoutFixtures.places(LayoutFixtures.reread(c)),
                "every place reads back exactly where and as 0.35 built it");
        String once = c.saveToString();
        assertFalse(LayoutGuard.apply(c, Decision.LEGACY, false, List.of(), null, new ArrayList<>()),
                "a second run changes nothing");
        assertEquals(once, c.saveToString(), "not a byte");
    }

    @Test
    void legacyPutsBackAnOriginTheBackfillWroteForAMissingKeyButNotOneTheOwnerTyped() {
        YamlConfiguration c = LayoutFixtures.v035();
        c.set(area("fresh_golf").originPath(), null);
        c.set(area("clubhouse").originPath(), null);
        c.set(area("fresh_parkour").originPath(), new ArrayList<>(area("fresh_parkour").shipped()));
        LayoutGuard.markPending(c);
        assertEquals(List.of("fresh_golf", "clubhouse"), LayoutGuard.missing(c), "the mark notes what was missing");
        c.set(area("fresh_golf").originPath(), new ArrayList<>(area("fresh_golf").shipped())); // the backfill
        c.set(area("clubhouse").originPath(), new ArrayList<>(area("clubhouse").shipped()));
        List<String> log = new ArrayList<>();
        LayoutGuard.apply(c, Decision.LEGACY, true, List.of("x"), null, log);
        assertEquals(Held.LEGACY, LayoutGuard.held(c, area("fresh_golf")),
                "the owner had deleted it, so 0.35's default was what it built at");
        assertEquals(Held.LEGACY, LayoutGuard.held(c, area("clubhouse")), "the Clubhouse too");
        assertEquals(Held.SHIPPED, LayoutGuard.held(c, area("fresh_parkour")),
                "a value the owner typed is theirs, even when it is this version's spot");
        assertEquals(32, c.getInt(area("fresh_parkour").gapPath()), "and keeps the shape it was built with");
        assertTrue(log.get(0).contains("games.fresh.slots.fresh_parkour.origin"), "named as theirs: " + log);
    }

    @Test
    void legacyKeepsAnOwnersOwnSpotAndGapAndGivesTheSpotItsOldShape() {
        YamlConfiguration c = LayoutFixtures.v035();
        c.set(area("fresh_parkour").originPath(), List.of(4352, 160, 4800));
        c.set(area("fresh_rings").gapPath(), 96);
        List<String> log = new ArrayList<>();
        LayoutGuard.apply(c, Decision.LEGACY, true, List.of("x"), null, log);
        assertEquals(List.of(4352, 160, 4800), list(c.get(area("fresh_parkour").originPath())), "the owner's spot stays");
        assertEquals(32, c.getInt(area("fresh_parkour").gapPath()), "with 0.35's gap");
        assertEquals(96, c.getInt(area("fresh_rings").gapPath()), "a gap the owner set stays");
        assertTrue(log.get(0).contains("games.fresh.slots.fresh_parkour.origin"), "and the line names it: " + log);
    }

    @Test
    void newMovesEveryUntouchedSpotAndWritesNoGap() {
        YamlConfiguration c = LayoutFixtures.v035();
        LayoutGuard.markPending(c);
        List<String> log = new ArrayList<>();
        assertTrue(LayoutGuard.apply(c, Decision.NEW, true, List.of(), null, log), "the file changes");
        for (Area a : LayoutGuard.AREAS) {
            assertEquals(Held.SHIPPED, LayoutGuard.held(c, a), a.id() + " takes its new spot");
            if (a.gapPath() != null) {
                assertNull(c.get(a.gapPath()), a.gapPath() + ": none written, so the default (576) holds");
            }
        }
        assertEquals(LayoutFixtures.shippedPlaces(), LayoutFixtures.places(LayoutFixtures.reread(c)),
                "every place reads back exactly as a fresh install's");
        assertEquals(1, log.size(), "one line: " + log);
        assertFalse(log.get(0).startsWith(LayoutGuard.WARN), "an INFO: nothing for the owner to do");
        assertTrue(log.get(0).contains("Easy Parkour") && log.get(0).contains("the Clubhouse"), "naming them: " + log);
        String once = c.saveToString();
        assertFalse(LayoutGuard.apply(c, Decision.NEW, false, List.of(), Stamp.parse("new|1|"), new ArrayList<>()),
                "a second run changes nothing");
        assertEquals(once, c.saveToString(), "not a byte");
    }

    @Test
    void newKeepsAnOwnersOwnSpotWithItsOldShape() {
        YamlConfiguration c = LayoutFixtures.v035();
        c.set(area("fresh_parkour").originPath(), List.of(4352, 160, 4800));
        c.set(area("keep").originPath(), List.of(4096, 128, 6400));
        List<String> log = new ArrayList<>();
        LayoutGuard.apply(c, Decision.NEW, true, List.of(), null, log);
        assertEquals(List.of(4352, 160, 4800), list(c.get(area("fresh_parkour").originPath())), "the owner's own spot");
        assertEquals(32, c.getInt(area("fresh_parkour").gapPath()), "keeps 0.35's gap, so its shape stays");
        assertEquals(List.of(4096, 128, 6400), list(c.get(area("keep").originPath())), "their own keep area");
        assertEquals(0, c.getInt(area("keep").gapPath(), -1), "keeps its plots touching");
        assertEquals(Held.SHIPPED, LayoutGuard.held(c, area("fresh_golf")), "everything else moves");
        assertTrue(log.stream().anyMatch(l -> l.startsWith(LayoutGuard.WARN)
                        && l.contains("kept games.fresh.slots.fresh_parkour.origin = [4352, 160, 4800] because you set it")),
                "a WARN names it: " + log);
    }

    @Test
    void anUntouchedSpotWhoseNewSpotAnOwnerHasTakenKeepsItsOldOne() {
        YamlConfiguration c = LayoutFixtures.v035();
        Area tiny = area("fresh_tiny_golf");
        c.set(area("fresh_parkour").originPath(), new ArrayList<>(tiny.shipped())); // on Tiny Golf's new spot
        List<String> log = new ArrayList<>();
        LayoutGuard.apply(c, Decision.NEW, true, List.of(), null, log);
        assertEquals(Held.LEGACY, LayoutGuard.held(c, tiny), "Tiny Golf keeps 0.35's spot");
        assertEquals(32, c.getInt(tiny.gapPath()), "and 0.35's shape");
        assertEquals(Held.SHIPPED, LayoutGuard.held(c, area("fresh_golf")), "Golf of the Week still moves");
        assertTrue(log.stream().anyMatch(l -> l.startsWith(LayoutGuard.WARN) && l.contains("Tiny Golf keeps its 0.35"
                + " spot") && l.contains("your own spot for Parkour")), "the WARN says why: " + log);
        List<String> warns = new ArrayList<>();
        LayoutFixtures.parse(LayoutFixtures.reread(c), warns);
        assertTrue(warns.stream().noneMatch(w -> w.contains("fresh_tiny_golf") || w.contains("fresh_parkour")),
                "neither course is switched off for crowding the other: " + warns);
    }

    @Test
    void aBareClassicListStaysAListWhenItMovesAndGainsItsGapWhenItStays() {
        String path = area("fresh_classic_rings").entry();
        YamlConfiguration moved = LayoutFixtures.v035();
        moved.set(path, List.of(4608, 128, 4736));
        LayoutGuard.apply(moved, Decision.NEW, true, List.of(), null, new ArrayList<>());
        assertEquals(List.of(6080, 128, 4992), list(moved.get(path)), "moved, still a bare list");

        YamlConfiguration kept = LayoutFixtures.v035();
        kept.set(path, List.of(4608, 128, 4736));
        LayoutGuard.apply(kept, Decision.LEGACY, true, List.of("x"), null, new ArrayList<>());
        ConfigurationSection s = kept.getConfigurationSection(path);
        assertNotNull(s, "it becomes a section, the one shape that holds a gap");
        assertEquals(List.of(4608, 128, 4736), list(s.get("origin")), "same spot");
        assertEquals(32, s.getInt("half_gap"), "same shape");
        assertEquals(LayoutFixtures.legacyPlaces().get("fresh_classic_rings"),
                LayoutFixtures.places(LayoutFixtures.reread(kept)).get("fresh_classic_rings"), "read back as 0.35 built it");
    }

    @Test
    void aValueThatIsntASectionIsLeftAlone() {
        YamlConfiguration c = LayoutFixtures.v035();
        c.set(area("fresh_classic_parkour").entry(), "nope");
        LayoutGuard.apply(c, Decision.LEGACY, true, List.of("x"), null, new ArrayList<>());
        assertEquals("nope", c.get(area("fresh_classic_parkour").entry()), "nothing is written under it");
        YamlConfiguration off = new YamlConfiguration();
        off.set("games", false);
        assertFalse(LayoutGuard.apply(off, Decision.LEGACY, false, List.of("x"), null, new ArrayList<>()),
                "a bare games switch the migration couldn't rewrite: nothing written");
        assertEquals(false, off.get("games"), "it stays as the owner wrote it");
    }

    @Test
    void aNewInstallsFileIsLeftExactlyAsItIs() {
        YamlConfiguration c = LayoutFixtures.bundled();
        String before = c.saveToString();
        List<String> log = new ArrayList<>();
        assertFalse(LayoutGuard.apply(c, Decision.NEW, false, List.of(), null, log), "nothing changes");
        assertEquals(before, c.saveToString(), "not a byte");
        assertEquals(1, log.size(), "one INFO line: " + log);
    }

    @Test
    void theShippedAndLegacyLayoutsNeverCrowdEachOther() {
        for (Area a : LayoutGuard.AREAS) {
            for (Area b : LayoutGuard.AREAS) {
                List<Box> mine = a.boxes(new int[]{a.shipped().get(0), a.shipped().get(1), a.shipped().get(2)},
                        a.gapPath() == null ? -1 : (a.def() != null ? Slots.HALF_GAP : KeepArea.DEFAULT_GAP), 24);
                List<Box> theirs = b.boxes(new int[]{b.legacy().get(0), b.legacy().get(1), b.legacy().get(2)},
                        b.legacyGap(), 24);
                assertFalse(LayoutGuard.crowds(mine, theirs), a.id() + "'s new spot and " + b.id()
                        + "'s 0.35 spot: an untouched default only stays for an owner's spot");
            }
        }
    }
}
