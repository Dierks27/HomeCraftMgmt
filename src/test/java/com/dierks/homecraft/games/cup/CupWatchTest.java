package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.gen.api.GenTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calling a Cup off from what the course is now (§D2: "if the course is deleted, changes layout, or is
 * closed mid-week, every entry is refunded in full and the player is told why"): a gone row, a course
 * or Fresh slot an admin closed, and a new layout call it off with the right reason; a rename, a heal
 * of the same layout, a read that failed, and Fresh Courses being off for a moment never do; and the
 * scheduled flip from last week's layout to the week's own is the Cup's layout going up, not a change.
 */
class CupWatchTest {

    /** The Cup week: Monday 2026-09-28. */
    private static final long WEEK = 20724L;

    private static GenTag rings(long day, int reroll, String hash) {
        return new GenTag("fresh_rings", "rings", 1, day, reroll, 42L, 'A', hash, 30_000, 34_000, 40_000,
                List.of(), List.of(), 0L, 7);
    }

    private static CupWatch.Seen fresh(GenTag tag, Boolean wanted) {
        return new CupWatch.Seen(true, true, true, wanted, CupLayout.fresh(tag));
    }

    private static CupWatch.Seen hand(int hash, boolean enabled) {
        return new CupWatch.Seen(true, enabled, false, null, CupLayout.handBuilt("parkour", hash));
    }

    @Test
    void aDeletedHandBuiltCourseIsCalledOffAsRemoved() {
        CupWatch.Verdict v = CupWatch.judge(CupLayout.handBuilt("parkour", 7), CupWatch.Seen.gone(false), WEEK);
        assertEquals(CupWatch.Action.VOID, v.action(), "a course whose row is gone can't hold a Cup");
        assertEquals(CupPlan.VoidReason.DELETED, v.reason(), "and the players read that it was removed");
    }

    @Test
    void aFreshSlotWhoseRowWentIsCalledOffAsClosed() {
        CupWatch.Verdict v = CupWatch.judge(CupLayout.fresh(rings(WEEK, 0, "aaa")), CupWatch.Seen.gone(true), WEEK);
        assertEquals(CupWatch.Action.VOID, v.action(), "a Classics slot's recall ended, or the slot was cleared");
        assertEquals(CupPlan.VoidReason.CLOSED, v.reason(), "a slot closes; it isn't deleted");
    }

    @Test
    void aCourseAnAdminClosedIsCalledOff() {
        CupWatch.Verdict v = CupWatch.judge(CupLayout.handBuilt("parkour", 7), hand(7, false), WEEK);
        assertEquals(CupWatch.Action.VOID, v.action(), "/hcm games course <id> disable closes the course");
        assertEquals(CupPlan.VoidReason.CLOSED, v.reason(), "and the players read that it closed");
    }

    @Test
    void aFreshSlotSwitchedOffIsCalledOffButFreshCoursesBeingOffIsNot() {
        GenTag own = rings(WEEK, 0, "aaa");
        CupWatch.Verdict off = CupWatch.judge(CupLayout.fresh(own), fresh(own, false), WEEK);
        assertEquals(CupWatch.Action.VOID, off.action(), "an admin switched the slot off (/hcm games gen off)");
        assertEquals(CupPlan.VoidReason.CLOSED, off.reason(), "the course closed");
        assertEquals(CupWatch.Verdict.NONE, CupWatch.judge(CupLayout.fresh(own), fresh(own, null), WEEK),
                "while Fresh Courses isn't running (a reload, a boot check) nobody can tell: nothing is called off");
        assertEquals(CupWatch.Verdict.NONE, CupWatch.judge(CupLayout.fresh(own), fresh(own, true), WEEK),
                "switched on and the same layout: the Cup stands");
    }

    @Test
    void aNewHandBuiltLayoutIsACalledOffCupButARenameIsNot() {
        CupWatch.Verdict v = CupWatch.judge(CupLayout.handBuilt("parkour", 7), hand(8, true), WEEK);
        assertEquals(CupWatch.Action.VOID, v.action(), "a checkpoint moved: the Cup times are on another course");
        assertEquals(CupPlan.VoidReason.CHANGED, v.reason(), "the players read that the course changed");
        assertEquals(CupWatch.Verdict.NONE, CupWatch.judge(CupLayout.handBuilt("parkour", 7), hand(7, true), WEEK),
                "a rename, a tier or a shortest time keeps the layout (its hash): nothing happens");
        assertEquals(CupWatch.Action.VOID, CupWatch.judge(CupLayout.handBuilt("parkour", 7),
                new CupWatch.Seen(true, true, false, null, CupLayout.handBuilt("boat", 7)), WEEK).action(),
                "the same points as another kind of course are another course");
    }

    @Test
    void anAdminsRerollOrPromoteOfTheWeeksOwnLayoutCallsTheCupOff() {
        CupLayout own = CupLayout.fresh(rings(WEEK, 0, "aaa"));
        CupWatch.Verdict reroll = CupWatch.judge(own, fresh(rings(WEEK, 1, "bbb"), true), WEEK);
        assertEquals(CupWatch.Action.VOID, reroll.action(), "a reroll puts a new layout up mid-week");
        assertEquals(CupPlan.VoidReason.CHANGED, reroll.reason(), "the course changed");
        assertEquals(CupWatch.Action.VOID, CupWatch.judge(own, fresh(rings(WEEK, 0, "ccc"), true), WEEK).action(),
                "a promoted preview is a new plan in the same edition: a change too");
    }

    @Test
    void aHealOfTheSameLayoutIsNoChange() {
        CupLayout own = CupLayout.fresh(rings(WEEK, 0, "aaa"));
        GenTag healed = new GenTag("fresh_rings", "rings", 1, WEEK, 0, 42L, 'A', "aaa", 30_000, 34_000, 40_000,
                List.of(), List.of(), 999L, 7);
        assertEquals(CupWatch.Verdict.NONE, CupWatch.judge(own, fresh(healed, true), WEEK),
                "/hcm games gen rebuild puts the same blocks back: same slot, edition and plan");
    }

    @Test
    void theScheduledFlipToTheWeeksOwnLayoutIsAdoptedNeverACalledOffCup() {
        CupLayout lastWeeks = CupLayout.fresh(rings(WEEK - 7, 0, "old"));
        assertFalse(lastWeeks.covers(WEEK), "last week's layout, still up after 04:00, isn't this Cup's");
        assertEquals(CupWatch.Verdict.ADOPT, CupWatch.judge(lastWeeks, fresh(rings(WEEK, 0, "new"), true), WEEK),
                "a player entered at 04:05 before the new layout was built: its flip is the Cup's layout going up");
        CupLayout own = CupLayout.fresh(rings(WEEK, 0, "new"));
        assertTrue(own.covers(WEEK), "the week's own layout is the Cup's");
        assertEquals(CupWatch.Action.VOID, CupWatch.judge(own, fresh(rings(WEEK, 1, "newer"), true), WEEK).action(),
                "once the week's own layout is up, a reroll of it is a change");
    }

    @Test
    void nothingRememberedAdoptsAndAnUnreadableLayoutIsLeftAlone() {
        assertEquals(CupWatch.Verdict.ADOPT, CupWatch.judge(null, hand(7, true), WEEK),
                "a Cup whose layout wasn't written down takes the course as it is now");
        assertEquals(CupWatch.Verdict.NONE, CupWatch.judge(CupLayout.handBuilt("parkour", 7),
                new CupWatch.Seen(true, true, false, null, null), WEEK), "never call a Cup off on a guess");
        assertEquals(CupWatch.Verdict.NONE, CupWatch.judge(CupLayout.handBuilt("parkour", 7), null, WEEK),
                "a course that couldn't be looked at is left alone");
    }

    @Test
    void aRecalledClassicCoversEveryWeekSoAnyChangeCallsItsCupOff() {
        GenTag classic = new GenTag("fresh_parkour_hard", "parkour", 1, WEEK - 70, 0, 7L, 'B', "abc", 30_000, 34_000,
                40_000, List.of(), List.of(), 0L, 7, new GenTag.Recall("fresh_classic_parkour", 1L, WEEK - 3));
        CupLayout l = CupLayout.fresh(classic);
        assertTrue(l.recalled() && l.covers(WEEK), "its blocks stay across weeks: its runs count by time alone");
        GenTag other = new GenTag("fresh_parkour", "parkour", 1, WEEK - 14, 0, 9L, 'A', "def", 30_000, 34_000,
                40_000, List.of(), List.of(), 0L, 7, new GenTag.Recall("fresh_classic_parkour", 2L, WEEK));
        CupWatch.Verdict v = CupWatch.judge(l, fresh(other, true), WEEK);
        assertEquals(CupWatch.Action.VOID, v.action(), "another course recalled into the slot");
        assertEquals(CupPlan.VoidReason.CHANGED, v.reason(), "is a new course");
    }

    @Test
    void aLayoutIsKeptAsOneLineAndReadBack() {
        CupLayout fresh = CupLayout.fresh(rings(WEEK, 2, "0123456789ab"));
        assertEquals(fresh, CupLayout.decode(fresh.encode()), "a Fresh layout reads back whole");
        CupLayout hand = CupLayout.handBuilt("elytra", -12345);
        assertEquals(hand, CupLayout.decode(hand.encode()), "a hand-built one too, a negative hash included");
        assertTrue(fresh.print().contains("7:") && fresh.print().contains("r2"), "the edition key with its reroll");
        for (String junk : new String[]{null, "", "x", "1;0;a;b;print", "1;0;1;2;"}) {
            assertNull(CupLayout.decode(junk), "'" + junk + "' is no layout: never throws");
        }
    }
}
