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
 * its own slot and neither covers anything the screen already shows; the Clubhouse's "Take a rider"
 * (WP-CH, a boat course) has 26. On a Dropper there is no party race and no lap warm-up: its only
 * extra is its practice drop.
 *
 * <p>The final maps after the events batch and the Clubhouse were merged:
 * <ul>
 *   <li>the course screen (27): 4, 10-16 and 22 fixed; 20 Race with friends; 24 the Cup; 26 Take a rider;</li>
 *   <li>the party screen (54): 4 the party, 11 the last race, 13 Start, 15 Leave, 19-25 and 28-34 the
 *       members, 36 Go to the Clubhouse, 37 Watch, 38 Invite, 39 Take a rider, 40 Ready, 42 Warm up
 *       first, 44 the Cup, 49 the way out; 45 and 53 (the page arrows' slots) stay filler;</li>
 *   <li>the Race Night screen (27): 24 the Clubhouse (wait or watch), 25 Take a rider, both on filler
 *       ({@code RaceNightMenuTest}).</li>
 * </ul>
 */
class CourseMenuLayoutTest {

    @Test
    void raceWithFriendsTheCupAndTakeARiderEachHaveTheirOwnSlotOnTheBottomRow() {
        assertEquals(20, CourseMenu.PARTY_SLOT, "Race with friends: left of the way out");
        assertEquals(24, CupLink.SLOT, "the Cup: right of the way out");
        assertEquals(26, CourseMenu.RIDER_SLOT, "Take a rider (a boat course): the bottom row's right end");
        assertEquals(List.of(4, 10, 11, 12, 13, 14, 15, 16, 22), CourseMenu.FIXED_SLOTS, "the fixed tiles");
        assertTrue(CourseMenu.FIXED_SLOTS.contains(22), "the way out is the 27-slot screen's bottom middle (22)");
        Set<Integer> taken = new HashSet<>(CourseMenu.FIXED_SLOTS);
        for (int slot : List.of(CourseMenu.PARTY_SLOT, CupLink.SLOT, CourseMenu.RIDER_SLOT)) {
            assertTrue(slot >= 18 && slot < 27, slot + " is on the bottom row of the 27-slot screen");
            assertTrue(taken.add(slot), slot + " is nobody else's");
        }
        assertEquals(CourseMenu.FIXED_SLOTS.size() + 3, taken.size(), "twelve items, twelve slots");
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

    @Test
    void thePartyScreenHasTheCupItemOnASlotOfItsOwn() {
        // /hcm play race <course> opens the party screen: a party race's finish counts for the Cup like any
        // run, so it shows the course screen's own Cup item (CupLink.button, the prompt in its NAME)
        Set<Integer> taken = new HashSet<>(List.of(4, 11, 13, 15, 38, 40, 42, 49));
        for (int m : PartyMenu.MEMBER_SLOTS) {
            taken.add(m);
        }
        assertFalse(taken.contains(PartyMenu.CUP_SLOT), PartyMenu.CUP_SLOT + " is nobody else's on the party screen");
        assertTrue(PartyMenu.CUP_SLOT >= 0 && PartyMenu.CUP_SLOT < 54, "on the 54-slot screen");
        assertFalse(List.of(45, 49, 53).contains(PartyMenu.CUP_SLOT), "never a page arrow's or the way out's slot");
    }

    @Test
    void thePartyScreensFinalMapGivesTheCupAndTheClubhouseEachASlotOfItsOwn() {
        assertEquals(44, PartyMenu.CUP_SLOT, "the Cup at 44");
        assertEquals(36, PartyMenu.CLUB_SLOT, "Go to the Clubhouse at 36");
        assertEquals(37, PartyMenu.WATCH_SLOT, "Watch at 37");
        assertEquals(39, PartyMenu.RIDER_SLOT, "Take a rider at 39");
        assertEquals(List.of(19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34),
                java.util.Arrays.stream(PartyMenu.MEMBER_SLOTS).boxed().toList(), "the members' two rows of seven");
        // the party, last race, Start, Leave, Invite, Ready, Warm up first, and the way out (54 - 5)
        List<Integer> map = new java.util.ArrayList<>(List.of(4, 11, 13, 15, 38, 40, 42, 49));
        for (int m : PartyMenu.MEMBER_SLOTS) {
            map.add(m);
        }
        map.addAll(List.of(PartyMenu.CUP_SLOT, PartyMenu.CLUB_SLOT, PartyMenu.WATCH_SLOT, PartyMenu.RIDER_SLOT));
        Set<Integer> taken = new HashSet<>();
        for (int slot : map) {
            assertTrue(slot >= 0 && slot < 54, slot + " is on the 54-slot screen");
            assertTrue(taken.add(slot), slot + " is used once: nothing on the party screen covers anything else");
        }
        assertFalse(taken.contains(45), "45 is only ever a page arrow's slot");
        assertFalse(taken.contains(53), "53 is only ever a page arrow's slot");
        assertTrue(taken.contains(49), "49 is the way out");
        assertEquals(26, taken.size(), "twenty-six items, twenty-six slots; every other slot is filler");
    }
    @Test
    void whileThePartyIsRacingTheClubhouseItemsHideLikeTheOtherPreRaceItems() {
        assertEquals(Set.of(PartyMenu.CLUB_SLOT, PartyMenu.WATCH_SLOT, PartyMenu.RIDER_SLOT),
                PartyMenu.clubSlots(true, true, false, false), "before the race: Go, Watch and Take a rider");
        assertEquals(Set.of(PartyMenu.WATCH_SLOT), PartyMenu.clubSlots(true, true, true, false),
                "racing: Go and Take a rider hide (as Invite, Ready, Warm up and the Cup do); Watch stays for a"
                        + " member who isn't in the race, since watching live is for a race going on");
        assertEquals(Set.of(), PartyMenu.clubSlots(true, true, true, true), "a racer mid-race sees none of them");
        assertEquals(Set.of(PartyMenu.RIDER_SLOT), PartyMenu.clubSlots(false, true, false, false),
                "with the Clubhouse closed there is no Go or Watch");
        assertEquals(Set.of(PartyMenu.CLUB_SLOT, PartyMenu.WATCH_SLOT), PartyMenu.clubSlots(true, false, false, false),
                "not a boat course: no Take a rider");
    }

    @Test
    void raceAgainIsSaidOnlyWhileTheClubhouseIsOpen() {
        assertEquals("&aStart the race!", PartyMenu.startName(false, true), "the first race");
        assertEquals("&aRace again!", PartyMenu.startName(true, true), "after a race, everyone is seated again from"
                + " the Clubhouse");
        assertEquals("&aStart the race!", PartyMenu.startName(true, false), "the Clubhouse off: the racers went home,"
                + " so it's a new start as before (#10)");
    }
}
