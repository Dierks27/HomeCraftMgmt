package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which courses run a Cup (§D2 "opt-in per course, and on by default for Fresh parkour, rings and
 * boat"; EVENTS-RECONCILED adds the Dropper): a Fresh slot's own course of those kinds is on while the
 * schedule is weekly; a hand-built course and a recalled Classic are off until an admin says; an
 * admin's choice wins; and a Fresh course can never run one on a schedule that changes it mid-week.
 */
class CupOptInTest {

    private static CupOptIn.Course fresh(String kind) {
        return new CupOptIn.Course(true, false, kind);
    }

    @Test
    void freshParkourRingsBoatAndDropperRunACupByDefault() {
        for (String kind : new String[]{"parkour", "elytra", "boat", "dropper", "PARKOUR"}) {
            assertTrue(CupOptIn.byDefault(fresh(kind), true), kind + ": a Fresh course of this kind is on");
            assertTrue(CupOptIn.on(null, fresh(kind), true), kind + ": with no admin choice");
        }
        assertFalse(CupOptIn.byDefault(fresh("golf"), true), "golf is no time trial");
    }

    @Test
    void aHandBuiltCourseIsOffUntilAnAdminSwitchesItOn() {
        CupOptIn.Course hand = new CupOptIn.Course(false, false, "parkour");
        assertFalse(CupOptIn.on(null, hand, true), "hand-built: off by default");
        assertTrue(CupOptIn.on(true, hand, true), "on once an admin says so");
        assertTrue(CupOptIn.on(true, hand, false), "a hand-built layout doesn't follow the Fresh schedule");
        assertNull(CupOptIn.cantSwitchOn(hand, false), "so it can always be switched on");
    }

    @Test
    void aRecalledClassicIsOffByDefaultAndCanBeSwitchedOn() {
        CupOptIn.Course classic = new CupOptIn.Course(true, true, "parkour");
        assertFalse(CupOptIn.on(null, classic, true), "its window may end mid-week: off unless chosen");
        assertTrue(CupOptIn.on(true, classic, false), "an admin may run one: its blocks stay across weeks");
    }

    @Test
    void anAdminsOffWinsOverTheDefault() {
        assertFalse(CupOptIn.on(false, fresh("elytra"), true), "an admin switched Sky Rings' Cup off");
    }

    @Test
    void aFreshCourseRunsNoCupOnASchedulethatChangesItMidWeek() {
        assertFalse(CupOptIn.on(null, fresh("parkour"), false), "a daily cadence: off by default");
        assertFalse(CupOptIn.on(true, fresh("parkour"), false), "and an admin's on can't override it");
        assertNotNull(CupOptIn.cantSwitchOn(fresh("parkour"), false), "the admin is told why");
        assertNull(CupOptIn.cantSwitchOn(fresh("parkour"), true), "weekly: fine");
    }
}
