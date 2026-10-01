package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.gen.LayoutScenarios.Disk;
import com.dierks.homecraft.games.gen.LayoutScenarios.Install;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.Regions;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value the plugin can't read on a server that kept 0.35's spots (LAYOUT-DECISIONS item 5): what a
 * course, a Classic, the Clubhouse or the arena falls back to must never be the new shipped spot, which
 * isn't where it was built. A bad switch or tier keeps the spot config gives; an origin or half_gap that
 * can't be read leaves a course unplaced (off, and kept where it was claimed: {@code UnplacedSlotEngineTest});
 * an unreadable Clubhouse or arena origin closes that block rather than build a second room.
 */
class LegacyFallbackTest {

    private static final String GOLF = "fresh_golf";
    private static final String CLASSIC = "fresh_classic_golf";

    /** A 0.35 install that built, after the guard: its config.yml as the guard wrote it. */
    private static FileConfiguration kept() {
        try (Install install = new Install()) {
            install.edition(Slots.DAILY_GOLF).recall(Slots.CLASSIC_GOLF).clubhouse().arena();
            Disk disk = new Disk(LayoutScenarios.marked035());
            assertEquals(LayoutGuard.Decision.LEGACY, LayoutGuard.run(LayoutGuard.Store.of(install.db), disk, 1L)
                    .decision(), "the fixture: a server that kept 0.35's spots");
            return disk.load();
        }
    }

    private static DailySettings fresh(FileConfiguration c, List<String> warns) {
        return LayoutFixtures.parse(c, warns).settings(DailyCourses.SPEC);
    }

    @Test
    void anOriginOrGapThatCantBeReadLeavesTheCourseUnplacedNeverAtTheShippedSpot() {
        for (Object[] bad : new Object[][]{{"half_gap", "32"}, {"origin", Arrays.asList(4864, 160, "4096")},
                {"origin", "somewhere"}}) {
            FileConfiguration c = kept();
            c.set("games.fresh.slots." + GOLF + "." + bad[0], bad[1]);
            List<String> warns = new ArrayList<>();
            DailySettings.SlotConfig golf = fresh(c, warns).slot(GOLF);
            String what = bad[0] + ": " + bad[1];
            assertFalse(golf.placed(), what + ": config can't say where Golf of the Week stands");
            assertFalse(golf.enabled(), what + ": so it is off");
            assertTrue(warns.stream().anyMatch(w -> w.startsWith("games.fresh.slots." + GOLF)), what + ": one WARN names"
                    + " it: " + warns);
        }
        FileConfiguration c = kept();
        c.set("games.fresh.slots." + GOLF, "junk");
        assertFalse(fresh(c, new ArrayList<>()).slot(GOLF).placed(), "a slot that isn't a section or a switch: unplaced");
    }

    @Test
    void aBadSwitchOrTierKeepsTheSpotConfigGivesAndTurnsTheCourseOff() {
        for (Object[] bad : new Object[][]{{"enabled", "maybe"}, {"mix", List.of("E", "M")}}) {
            FileConfiguration c = kept();
            c.set("games.fresh.slots." + GOLF + "." + bad[0], bad[1]);
            DailySettings.SlotConfig golf = fresh(c, new ArrayList<>()).slot(GOLF);
            String what = bad[0] + ": " + bad[1];
            assertFalse(golf.enabled(), what + ": the course is off");
            assertTrue(golf.placed(), what + ": its spot is still read");
            assertArrayEquals(LegacyBoxes.origin(Slots.DAILY_GOLF), golf.origin(), what + ": at its 0.35 spot, where it"
                    + " was built, not the new shipped one");
            assertEquals(Slots.LEGACY_HALF_GAP, golf.halfGap(), what + ": at its 0.35 gap");
            assertEquals(Regions.claim(Slots.DAILY_GOLF, LayoutScenarios.WORLD, golf.origin(), golf.halfGap()),
                    Install.claim035(Slots.DAILY_GOLF), what + ": so its claim still matches: nothing moves");
        }
    }

    @Test
    void aClassicWhoseOriginOrGapCantBeReadIsUnplacedToo() {
        for (Object[] bad : new Object[][]{{"origin", Arrays.asList(4352, 160, "4736")}, {"half_gap", "32"}}) {
            FileConfiguration c = kept();
            c.set("games.fresh.classics.slots." + CLASSIC + "." + bad[0], bad[1]);
            DailySettings.SlotConfig classic = fresh(c, new ArrayList<>()).archive().classic(CLASSIC);
            assertFalse(classic.placed(), bad[0] + ": Classic Golf is unplaced, not at the shipped spot");
            assertFalse(classic.enabled(), bad[0] + ": and off");
        }
        FileConfiguration c = kept();
        c.set("games.fresh.classics.slots." + CLASSIC, "somewhere");
        assertFalse(fresh(c, new ArrayList<>()).archive().classic(CLASSIC).placed(), "a Classic that isn't a list or a"
                + " section: unplaced");
    }

    @Test
    void anUnplacedCoursesPlaceholderIsNeverTakenForWhereItStands() {
        FileConfiguration c = kept();
        c.set("games.fresh.slots." + GOLF + ".origin", "somewhere");
        // the keep area moved onto the new shipped spot of Golf of the Week, which nothing stands at here
        c.set("games.fresh.keep.area", List.of(7488, 128, 4096));
        List<String> warns = new ArrayList<>();
        DailySettings st = fresh(c, warns);
        assertEquals(1, warns.size(), "only the origin's WARN: " + warns);
        assertEquals(null, st.archive().keepProblem(), "the keep area isn't checked against an unplaced course's"
                + " placeholder (the shipped spot)");

        c = kept();
        c.set("games.fresh.slots." + GOLF + ".origin", "somewhere");
        st = fresh(c, new ArrayList<>());
        assertEquals(List.of(), com.dierks.homecraft.games.clubhouse.ClubhouseRegions.problems(
                        com.dierks.homecraft.games.gen.api.Box.sized(7488, 160, 4096, 32, 16, 32), st, List.of(), null, null),
                "nor is a room's box there (the Clubhouse's and the arena's checks count every course, on or off)");
    }

    @Test
    void anUnreadableClubhouseOrArenaOriginClosesItNeverASecondRoomAtTheShippedSpot() {
        FileConfiguration c = kept();
        c.set("games.clubhouse.origin", Arrays.asList(5376, 160, "east"));
        List<String> warns = new ArrayList<>();
        GamesConfig.Parsed p = LayoutFixtures.parse(c, warns);
        assertFalse(p.readable(Clubhouse.SPEC.id()), "the Clubhouse is closed until it is fixed, so no room is built at"
                + " the shipped corner beside the one that stands: " + warns);
        assertTrue(warns.stream().anyMatch(w -> w.startsWith("games.clubhouse.origin")
                && w.endsWith("is off until it is fixed")), "one WARN says so: " + warns);

        c = kept();
        c.set("games.clubhouse.origin", Arrays.asList(5376, 160, "4448"));
        p = LayoutFixtures.parse(c, new ArrayList<>());
        assertTrue(p.readable(Clubhouse.SPEC.id()), "numbers written as text are read");
        assertEquals(LegacyBoxes.clubhouse(), p.settings(Clubhouse.SPEC).box(), "at the room's 0.35 box");

        c = kept();
        c.set("games.falling_floors.origin", "east");
        p = LayoutFixtures.parse(c, new ArrayList<>());
        assertFalse(p.readable(FallingFloors.SPEC.id()), "an unreadable arena origin closes Falling Floors, so no arena"
                + " is built at the shipped spot");
    }
}
