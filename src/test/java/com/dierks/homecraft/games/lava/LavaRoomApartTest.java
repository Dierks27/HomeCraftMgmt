package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.arena.ArenaRegions;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.ClubhouseRegions;
import com.dierks.homecraft.games.clubhouse.ClubhouseSettings;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.KeepArea;
import com.dierks.homecraft.games.gen.engine.Regions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The playroom's claim never overlaps anything else that claims land in the Games world, as shipped and whichever
 * moves: every Fresh Courses half there could ever be (on or off, the Classics), the kept courses' area, the Falling
 * Floors arena and the Clubhouse. It keeps {@value Regions#APART} blocks from each, and they keep the same from it
 * (Fresh Courses sees it through {@link DailyCourses#extraBoxes(FallingFloorsSettings, ClubhouseSettings,
 * LavaSettings)}). The shipped places are read from the bundled config.yml through the real parser.
 */
class LavaRoomApartTest {

    private static GamesConfig.Parsed shipped() throws Exception {
        try (InputStream in = LavaRoomApartTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(r);
                List<String> warns = new ArrayList<>();
                GamesConfig.Parsed parsed = GamesConfig.parse(c.getConfigurationSection("games"), warns::add, null);
                assertEquals(List.of(), warns, "the shipped games block reads without a WARN");
                return parsed;
            }
        }
    }

    private static List<DailySettings.SlotConfig> everySlot(DailySettings d) {
        List<DailySettings.SlotConfig> all = new ArrayList<>();
        for (DailySettings.SlotConfig c : d.slots()) {
            all.add(c.withEnabled(true));
        }
        for (DailySettings.SlotConfig c : d.archive().classics()) {
            all.add(c.withEnabled(true));
        }
        return all;
    }

    @Test
    void theShippedPlayroomIsApartFromEverythingElse() throws Exception {
        GamesConfig.Parsed cfg = shipped();
        LavaSettings lava = cfg.settings(FloorIsLava.SPEC);
        assertEquals(LavaSettings.defaults(), lava, "config.yml ships exactly the defaults");
        Box room = lava.box();
        assertEquals(32, room.sizeX());
        assertEquals(16, room.sizeY());
        assertEquals(32, room.sizeZ());
        DailySettings fresh = cfg.settings(DailyCourses.SPEC);
        FallingFloorsSettings ff = cfg.settings(FallingFloors.SPEC);
        ClubhouseSettings club = cfg.settings(Clubhouse.SPEC);

        List<Regions.Extra> others = DailyCourses.extraBoxes(ff, club);
        assertEquals(List.of(), LavaRegions.problems(room, fresh, others, List.of(), null),
                "its own check passes where it ships");
        for (Regions.Extra e : others) {
            assertTrue(room.gap(e.box()) >= Regions.APART, "the playroom is " + room.gap(e.box()) + " blocks from "
                    + e.name());
        }
        List<DailySettings.SlotConfig> slots = everySlot(fresh);
        for (DailySettings.SlotConfig c : slots) {
            for (Box half : Regions.halves(c)) {
                assertTrue(half.gap(room) >= Regions.APART, c.id() + "'s half " + half.describe() + " is "
                        + half.gap(room) + " blocks from the playroom");
            }
        }
        KeepArea keep = fresh.archive().keep();
        assertTrue(keep.area().gap(room) >= Regions.APART, "the keep area is " + keep.area().gap(room) + " away");

        // the other way round: Fresh Courses, the keep area and the other boxes see it too
        List<Regions.Extra> all = DailyCourses.extraBoxes(ff, club, lava);
        assertEquals(List.of(ArenaRegions.NAME, ClubhouseRegions.NAME, LavaRegions.NAME),
                all.stream().map(Regions.Extra::name).toList(), "the playroom comes after the arena and the Clubhouse");
        assertEquals(java.util.Map.of(), Regions.extraProblems(all, slots, keep), "all three boxes fit, together");
        assertEquals(List.of(), ArenaRegions.problems(ff.box(), fresh, List.of(all.get(1), all.get(2)), List.of(),
                null), "the arena's own check passes with the playroom beside it");
        assertEquals(List.of(), ClubhouseRegions.problems(club.box(), fresh, List.of(all.get(0), all.get(2)),
                List.of(), null), "and the Clubhouse's");
    }

    @Test
    void aPlayroomMovedOntoTheClubhouseOrTheArenaIsRefused() {
        DailySettings fresh = DailySettings.defaults();
        Box club = ClubhouseSettings.defaults().box();
        Box arena = FallingFloorsSettings.defaults().box();
        List<Regions.Extra> others = List.of(new Regions.Extra(ArenaRegions.NAME, arena),
                new Regions.Extra(ClubhouseRegions.NAME, club));
        Box onClub = Box.sized(club.minX(), club.minY(), club.minZ(), 32, 16, 32);
        List<String> p = LavaRegions.problems(onClub, fresh, others, List.of(), null);
        assertFalse(p.isEmpty(), "on top of the Clubhouse: refused");
        assertTrue(p.get(0).contains(ClubhouseRegions.NAME), p.get(0));
        Box nearArena = Box.sized(arena.maxX() + 11, arena.minY(), arena.minZ(), 32, 16, 32);
        List<String> q = LavaRegions.problems(nearArena, fresh, others, List.of(), null);
        assertFalse(q.isEmpty(), "10 blocks from the arena: refused");
        assertTrue(q.get(0).contains(ArenaRegions.NAME), q.get(0));
        // a slot crowding the playroom is refused by Fresh Courses' own vet, by the playroom's name
        DailySettings.SlotConfig slot = fresh.slots().get(0);
        Box half = Regions.half(slot.def(), slot.origin(), slot.halfGap(), 'A');
        LavaSettings beside = new LavaSettings(true, List.of(Math.floorDiv(half.maxX() + 16, 16) * 16, half.minY(),
                Math.floorDiv(half.minZ(), 16) * 16), 6, 10, 25, 12, 5);
        String near = Regions.extrasProblem(slot.def(), slot.origin(), slot.halfGap(),
                LavaRegions.extras(beside));
        assertNotNull(near, "a slot within " + Regions.APART + " blocks of the playroom is refused");
        assertTrue(near.contains(LavaRegions.NAME), near);
        assertNull(Regions.extraApartProblem(new Regions.Extra(LavaRegions.NAME, onClub),
                LavaRegions.extras(new LavaSettings(true, List.of(onClub.minX(), onClub.minY(), onClub.minZ()), 6,
                        10, 25, 12, 5))), "a box is never too near itself");
    }

    @Test
    void theShippedNumbersAreUnhurriedForAFourYearOld() {
        LavaSettings d = LavaSettings.defaults();
        assertTrue(d.enabled());
        assertEquals(6, d.maxPlayers());
        assertEquals(10, d.stars());
        assertEquals(25, d.safeSeconds());
        assertEquals(12, d.lavaSeconds());
        assertEquals(5, d.rounds());
        assertEquals(0, d.origin().get(0) % 16, "x on the 16-block grid");
        assertEquals(0, d.origin().get(2) % 16, "z on the 16-block grid");
    }
}
