package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.trial.PartyRaces;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.games.trial.Warmup;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The course screen's bottom row once two packages both wanted slot 20: "Race with friends" (WP-R1,
 * D4) keeps 20, left of the way out, and the Weekly Cup (WP-C, D2) has 24, right of it, so each has
 * its own slot and neither covers anything the screen already shows. On a Dropper there is no
 * party race and no lap warm-up: its only extra is its practice drop.
 */
class CourseMenuLayoutTest {

    @Test
    void raceWithFriendsAndTheCupEachHaveTheirOwnSlotOnTheBottomRow() {
        assertEquals(20, CourseMenu.PARTY_SLOT, "Race with friends: left of the way out");
        assertEquals(24, CupLink.SLOT, "the Cup: right of the way out");
        assertTrue(CourseMenu.FIXED_SLOTS.contains(22), "the way out is the 27-slot screen's bottom middle (22)");
        Set<Integer> taken = new HashSet<>(CourseMenu.FIXED_SLOTS);
        for (int slot : List.of(CourseMenu.PARTY_SLOT, CupLink.SLOT)) {
            assertTrue(slot >= 18 && slot < 27, slot + " is on the bottom row of the 27-slot screen");
            assertTrue(taken.add(slot), slot + " is nobody else's");
        }
        assertEquals(CourseMenu.FIXED_SLOTS.size() + 2, taken.size(), "eleven items, eleven slots");
    }

    @Test
    void aDropperOffersNoPartyRaceAndNoWarmUp() {
        TimeTrialsSettings on = TimeTrialsSettings.defaults();
        assertTrue(on.warmupsOn(), "warm-ups ship on (3:00)");
        assertFalse(PartyRaces.offered(TrialKind.DROPPER), "no Race with friends on a Dropper's screen");
        assertFalse(Warmup.offered(on, false, TrialKind.DROPPER), "and never Warm up (3:00): it has its practice drop");
        for (TrialKind k : TrialKind.values()) {
            if (k != TrialKind.DROPPER) {
                assertTrue(PartyRaces.offered(k), k + " courses can be raced with friends");
                assertTrue(Warmup.offered(on, false, k), k + " courses offer a warm-up");
            }
        }
        assertFalse(PartyRaces.offered(null), "no kind, no party");
    }
}
