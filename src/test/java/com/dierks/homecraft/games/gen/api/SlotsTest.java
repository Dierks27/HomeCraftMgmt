package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slots, fixed in code (GEN-SPEC §1.1, §2.2, §2.3): the seven ids in order with the shipped
 * tiers, mixes and tokens; the half sizes and default halves exactly as the spec's table has
 * them, on chunk lines and every pair at least 32 blocks apart; the reserved play ids; and which
 * difficulty words a slot accepts.
 */
class SlotsTest {

    @Test
    void theSlotsAreTheSpecsSevenInOrder() {
        assertEquals(List.of("fresh_parkour_easy", "fresh_parkour", "fresh_parkour_hard", "fresh_rings",
                "fresh_golf", "fresh_tiny_golf", "fresh_boat"), Slots.ids(), "the slots in display and config order");
        assertEquals(List.of("easy", "medium", "hard", "easy", "EEEMMMMHH", "EEE", "medium"),
                Slots.ALL.stream().map(Slots.Def::tierOrMix).toList(), "their shipped tiers and mixes");
        assertEquals(List.of(1, 2, 3, 2, 2, 1, 2), Slots.ALL.stream().map(Slots.Def::dailyClear).toList(),
                "their shipped first-finish tokens at a daily cadence (the addendum's table)");
        assertEquals(List.of(2, 3, 5, 3, 3, 2, 3), Slots.ALL.stream().map(Slots.Def::weeklyClear).toList(),
                "and at a weekly one");
        assertEquals(List.of(true, true, true, true, true, true, false),
                Slots.ALL.stream().map(Slots.Def::enabled).toList(), "every slot ships on but the ice boat");
        assertEquals(List.of("Easy Parkour", "Parkour", "Hard Parkour", "Sky Rings", "Golf of the Week", "Tiny Golf",
                "Ice Boat"), Slots.ALL.stream().map(Slots.Def::name).toList(), "the names players see (weekly)");
        assertEquals("fresh_courses", Slots.DAILY, "the game's id and screen");
        assertEquals("fresh_parkour_tiers", Slots.DAILY_PARKOUR, "the level picker, not the middle course");
        assertEquals(List.of("trials", "trials", "trials", "trials", "golf", "golf", "trials"),
                Slots.ALL.stream().map(Slots.Def::game).toList(), "the game each row belongs to");
        assertEquals(List.of("parkour", "parkour", "parkour", "elytra", "golf", "golf", "boat"),
                Slots.ALL.stream().map(Slots.Def::kind).toList(), "each row's kind");
        assertEquals(List.of(0, 0, 0, 0, 9, 3, 0), Slots.ALL.stream().map(Slots.Def::plots).toList(),
                "golf holds nine or three holes");
    }

    @Test
    void theDefaultHalvesAreTheSpecsTable() {
        record Row(String id, int ax, int bx, int z1, int z2, int y1, int y2) {
        }
        List<Row> table = List.of(
                new Row("fresh_parkour_easy", 4096, 4192, 4096, 4159, 160, 207),
                new Row("fresh_parkour", 4352, 4448, 4096, 4159, 160, 207),
                new Row("fresh_parkour_hard", 4608, 4704, 4096, 4159, 160, 207),
                new Row("fresh_golf", 4864, 4960, 4096, 4223, 160, 175),
                new Row("fresh_tiny_golf", 5120, 5216, 4096, 4143, 160, 175),
                new Row("fresh_rings", 4096, 4256, 4352, 4671, 128, 303),
                new Row("fresh_boat", 4480, 4640, 4352, 4479, 160, 175));
        for (Row row : table) {
            Slots.Def s = Slots.of(row.id());
            Box a = s.half('A');
            Box b = s.half('b');
            assertEquals(row.ax(), a.minX(), row.id() + ": half A starts at its origin");
            assertEquals(row.bx(), b.minX(), row.id() + ": half B is 32 blocks further along +X");
            assertEquals(a.sizeX(), b.sizeX(), row.id() + ": the halves are the same size");
            assertEquals(row.z1(), a.minZ(), row.id() + ": z from");
            assertEquals(row.z2(), a.maxZ(), row.id() + ": z to");
            assertEquals(row.y1(), a.minY(), row.id() + ": y from");
            assertEquals(row.y2(), a.maxY(), row.id() + ": y to");
            assertEquals(32, a.gap(b), row.id() + ": 32 blocks between the halves");
            assertEquals(Box.of(a.minX(), a.minY(), a.minZ(), b.maxX(), b.maxY(), b.maxZ()),
                    s.region(s.originX(), s.originY(), s.originZ()), row.id() + ": the region is both halves");
            assertEquals(0, s.originX() % 16, row.id() + ": x on a chunk line");
            assertEquals(0, s.originZ() % 16, row.id() + ": z on a chunk line");
            assertTrue(a.maxY() <= 312 && a.minY() >= -56, row.id() + ": inside the world's y limits");
        }
        assertThrows(IllegalArgumentException.class, () -> Slots.TINY_GOLF.half('C'), "a half is A or B");
    }

    @Test
    void everyDefaultHalfIsAtLeast32BlocksFromEveryOther() {
        List<Box> halves = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        for (Slots.Def s : Slots.ALL) {
            for (char h : new char[]{'A', 'B'}) {
                halves.add(s.half(h));
                names.add(s.id() + " " + h);
            }
        }
        for (int i = 0; i < halves.size(); i++) {
            for (int j = i + 1; j < halves.size(); j++) {
                assertTrue(halves.get(i).gap(halves.get(j)) >= 32,
                        names.get(i) + " and " + names.get(j) + " are at least 32 blocks apart");
            }
        }
    }

    @Test
    void slotsAreFoundInAnyCaseAndTheirPlayIdsAreReserved() {
        assertSame(Slots.DAILY_GOLF, Slots.of(" Fresh_Golf "), "any case, trimmed");
        assertNull(Slots.of("river_run"), "a hand-built id is no slot");
        assertNull(Slots.of(null), "nor is nothing");
        assertTrue(Slots.isSlot("fresh_rings"), "fresh_rings is a slot");
        assertFalse(Slots.isSlot("fresh_courses"), "fresh_courses is a screen, not a slot");
        assertFalse(Slots.reserved("daily"), "the old daily id is free again: it never shipped");
        assertEquals(Set.of("fresh_parkour_easy", "fresh_parkour", "fresh_parkour_hard", "fresh_rings",
                "fresh_golf", "fresh_tiny_golf", "fresh_boat", "fresh_courses", "fresh_parkour_tiers"), Slots.RESERVED,
                "every slot, the Fresh Courses screen and the level picker are reserved");
        assertTrue(Slots.reserved("FRESH_PARKOUR_TIERS"), "in any case");
        assertFalse(Slots.reserved(null), "nothing is not reserved");
    }

    @Test
    void aSlotAcceptsOnlyItsKindOfDifficulty() {
        assertNull(Slots.DAILY_PARKOUR_EASY.tierProblem("Hard"), "a parkour slot takes a tier, any case");
        assertNotNull(Slots.DAILY_PARKOUR_EASY.tierProblem("extreme"), "but not extreme");
        assertNotNull(Slots.DAILY_PARKOUR_EASY.tierProblem("EEE"), "nor a mix");
        assertNull(Slots.DAILY_GOLF.tierProblem("eeemmmmhh"), "a golf slot takes a mix, any case");
        assertNull(Slots.DAILY_GOLF.tierProblem("H"), "one hole is a mix");
        assertNotNull(Slots.DAILY_GOLF.tierProblem("EEEEEEEEEE"), "ten holes don't fit nine plots");
        assertNotNull(Slots.TINY_GOLF.tierProblem("EEEE"), "four don't fit Tiny Golf's three");
        assertNotNull(Slots.TINY_GOLF.tierProblem("EXE"), "only E, M and H");
        assertNotNull(Slots.TINY_GOLF.tierProblem(""), "nothing is no mix");
        assertNotNull(Slots.TINY_GOLF.tierProblem("easy"), "a golf slot doesn't take a tier");
        assertEquals("EEM", Slots.TINY_GOLF.normalise(" eem "), "a mix is upper-cased");
        assertEquals("hard", Slots.SKY_RINGS.normalise(" HARD "), "a tier lower-cased");
        assertTrue(Slots.DAILY_GOLF.golf(), "golf is golf");
        assertFalse(Slots.SKY_RINGS.golf(), "rings are a trial");
    }
}
