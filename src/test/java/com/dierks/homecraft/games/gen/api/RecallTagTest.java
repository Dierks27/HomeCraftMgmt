package com.dierks.homecraft.games.gen.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recalled course's tag (GEN-SPEC-KEEP §3): it carries the ORIGINAL edition, so its time board and
 * its first-finish reward are the original's, while its stars go on the recall's own board; it is
 * never the same layout as the slot's own; and the {@code gen:} block round-trips it, with a
 * normal tag's block unchanged.
 */
class RecallTagTest {

    private static GenTag original() {
        return new GenTag("fresh_parkour_hard", "parkour", 1, 20731, 0, 0x3f2a91c07d1e55b0L, 'A', "3c9e51aa07b2",
                38_000, 47_500, 68_400, List.of(), List.of(), 1_790_000_000_000L, 7);
    }

    @Test
    void aRecalledCourseKeepsTheOriginalBoardAndReward() {
        GenTag o = original();
        GenTag r = o.withRecall(new GenTag.Recall("fresh_classic_parkour", 1_795_000_000_000L, 20790));
        assertEquals(GenBoards.day(o), GenBoards.day(r), "its time board is the original edition's");
        assertEquals(GenBoards.clearRef(o), GenBoards.clearRef(r), "so is its first-finish reward's ref");
        assertEquals("fresh:fresh_parkour_hard:" + o.edition(), GenBoards.clearRef(r), "fresh:<slot>:<edition>");
        assertEquals("gstars:fresh_classic_parkour:20790", GenBoards.stars(r), "its stars are the recall's own");
        assertEquals(GenBoards.Kind.STARS, GenBoards.parse(GenBoards.stars(r)).kind(), "a board pruning can read");
        assertEquals(20790, GenBoards.parse(GenBoards.stars(r)).day(), "kept by the day it was recalled");
        assertFalse(o.sameLayout(r), "never the slot's own layout, even with the same plan and half");
        assertTrue(r.sameLayout(r.withBuiltAt(5)), "but the same as itself");
        assertEquals("fresh_classic_parkour", r.holder(), "the Classics slot holds its blocks");
        assertEquals("fresh_parkour_hard", o.holder(), "a slot holds its own");
        assertTrue(r.recalled() && !o.recalled(), "and it says so");
    }

    @Test
    void theGenBlockRoundTripsARecallAndLeavesANormalTagAlone() {
        GenTag o = original();
        Map<String, Object> plain = GenTagCodec.write(o);
        assertFalse(plain.keySet().stream().anyMatch(k -> k.startsWith("recall")), "a normal tag writes no recall");
        assertNull(GenTagCodec.read(plain).recall(), "and reads back without one");
        GenTag r = o.withRecall(new GenTag.Recall("fresh_classic_parkour", 1_795_000_000_000L, 20790));
        assertEquals(r, GenTagCodec.read(GenTagCodec.write(r)), "a recalled tag round-trips");
        Map<String, Object> broken = new java.util.LinkedHashMap<>(GenTagCodec.write(r));
        broken.put("recall_from", "soon");
        try {
            GenTagCodec.read(broken);
            throw new AssertionError("a damaged recall must not read as a normal layout");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("recall_from"), "it names what can't be read");
        }
    }

    @Test
    void classicNamesFitACourseRowAndFollowTheCadence() {
        assertEquals("Classic: Hard Parkour (week of 5 Oct)", GenCopy.classicName("Hard Parkour", 7, 20731, false),
                "a weekly course: the week it was up");
        assertEquals("Classic: Parkour (5 Oct)", GenCopy.classicName("Parkour", 1, 20731, false), "a daily one");
        assertEquals("Classic: Sky Rings (5 Oct-7 Oct)", GenCopy.classicName("Sky Rings", 3, 20731, false),
                "a 3-day one");
        for (Slots.Def d : Slots.ALL) {
            for (int n : new int[]{1, 3, 7, 14}) {
                for (boolean remade : new boolean[]{false, true}) {
                    String row = GenCopy.classicRowName(GenCopy.slotName(d, n), n, 20731, remade);
                    assertTrue(row.length() <= GenCopy.NAME_CHARS, "'" + row + "' fits a course name");
                    assertTrue(row.startsWith("Classic: "), "and says it is a classic");
                    assertTrue(GenCopy.copyProblems(row).isEmpty(), row + " is fine copy");
                }
            }
        }
        assertTrue(GenCopy.classicRowName("Hard Parkour", 7, 20731, true).contains("re-made"),
                "a course made again says so");
    }
}
