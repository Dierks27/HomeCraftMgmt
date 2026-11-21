package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.LegacyBoxes;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NPC regulars' places in the generated Clubhouse (the planner's own plan, as the planner tests use it): every one
 * is a seat (a chair or a stool with air over it) or a cell someone can stand in (air or a carpet at the feet, air at
 * the head, something solid under them), inside the room; every kind of place is there; a Parlor place is marked so,
 * and only a Parlor place; and a regular in the Parlor is never shown to a player who can't go in.
 */
class RegularSpotsTest {

    private static final Box MAIN = LegacyBoxes.clubhouse();
    private static final Box BOX = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 32, 16, 48);
    private static final ClubhouseSite SITE = ClubhousePlanner.plan(BOX);
    private static final Map<Long, String> BLOCKS = RegularSpots.blocks(SITE.plan());

    private static String at(int x, int y, int z) {
        return BLOCKS.get(RegularSpots.key(x, y, z));
    }

    @Test
    void everyPlaceIsASeatOrACellToStandInInsideTheRoom() {
        List<RegularSpots.Spot> spots = RegularSpots.of(SITE, ClubhousePlanner.Rooms.BOTH);
        assertFalse(spots.isEmpty());
        Set<RegularSpots.Spot> unique = new HashSet<>(spots);
        assertEquals(spots.size(), unique.size(), "no place twice");
        for (RegularSpots.Spot s : spots) {
            int x = (int) Math.floor(s.x());
            int y = (int) Math.floor(s.y());
            int z = (int) Math.floor(s.z());
            assertTrue(BOX.contains(x, y, z), "inside the box: " + s);
            if (s.sit()) {
                String seat = at(x, y, z);
                assertNotNull(seat, "a seat block under a sitter: " + s);
                assertTrue(seat.contains("_stairs") || seat.contains("_slab[type=bottom"), "a chair or a stool: " + seat);
                assertEquals(null, at(x, y + 1, z), "air over the seat: " + s);
                assertEquals(0.5, s.y() - y, 1e-9, "on the seat's top");
            } else {
                String feet = at(x, y, z);
                assertTrue(feet == null || feet.contains("_carpet"), "air or a carpet at the feet: " + s + " " + feet);
                assertEquals(null, at(x, y + 1, z), "air at the head: " + s);
                String under = feet == null ? at(x, y - 1, z) : at(x, y - 1, z);
                assertNotNull(under, "something under the feet, never the void: " + s);
                assertFalse(ClubhousePlanner.noFooting(under), "something to stand on: " + under);
                assertEquals(feet == null ? 0.0 : RegularSpots.CARPET, s.y() - y, 1e-9, "on the floor or the carpet");
            }
            assertTrue(SITE.inside().contains(s.x(), s.y(), s.z()) || inMore(s), "in the room's air: " + s);
        }
    }

    private static boolean inMore(RegularSpots.Spot s) {
        for (Box b : SITE.more()) {
            if (b.contains(s.x(), s.y(), s.z())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyKindOfPlaceIsThereWithTheTablesFourChairs() {
        List<RegularSpots.Spot> spots = RegularSpots.of(SITE, ClubhousePlanner.Rooms.BOTH);
        Set<RegularSpots.Kind> kinds = EnumSet.noneOf(RegularSpots.Kind.class);
        int chairs = 0;
        for (RegularSpots.Spot s : spots) {
            kinds.add(s.kind());
            chairs += s.kind() == RegularSpots.Kind.TABLE ? 1 : 0;
        }
        assertEquals(EnumSet.allOf(RegularSpots.Kind.class), kinds, "the bar, the hall, the Arcade Room, the Trophy"
                + " Room, the slot machines, the track and the table");
        assertEquals(4, chairs, "the Twenty-One table's four chairs");
        assertEquals(4, spots.stream().filter(s -> s.kind() == RegularSpots.Kind.BAR).count(), "the bar's stools");
    }

    @Test
    void aParlorPlaceIsMarkedSoAndOnlyAParlorPlace() {
        Box parlor = ClubhousePlanner.parlorInside(BOX);
        for (RegularSpots.Spot s : RegularSpots.of(SITE, ClubhousePlanner.Rooms.BOTH)) {
            assertEquals(parlor.contains(s.x(), s.y(), s.z()), s.parlor(), "marked as where it is: " + s);
            boolean parlorKind = s.kind() == RegularSpots.Kind.SLOTS || s.kind() == RegularSpots.Kind.TRACK
                    || s.kind() == RegularSpots.Kind.TABLE;
            assertEquals(parlorKind, s.parlor(), "the slots, the track and the table are the Parlor's: " + s);
        }
        // a player who can't go into the Parlor never sees a regular there; nobody with NPCs off sees any
        assertFalse(ClubRegulars.mayShow(true, true, false), "a Parlor regular, a player who can't go in: hidden");
        assertTrue(ClubRegulars.mayShow(true, true, true));
        assertTrue(ClubRegulars.mayShow(true, false, false), "downstairs: shown to everyone with NPCs on");
        assertFalse(ClubRegulars.mayShow(false, false, true), "NPCs off: none");
        assertFalse(ClubRegulars.mayShow(false, true, true));
    }

    @Test
    void withoutTheSideRoomsOrTheParlorTheirPlacesAreGone() {
        Box main = Box.sized(MAIN.minX(), MAIN.minY(), MAIN.minZ(), 32, 16, 32);
        List<RegularSpots.Spot> alone = RegularSpots.of(ClubhousePlanner.plan(main), ClubhousePlanner.Rooms.NONE);
        assertFalse(alone.isEmpty(), "the bar and the hall are always there");
        for (RegularSpots.Spot s : alone) {
            assertTrue(s.kind() == RegularSpots.Kind.BAR || s.kind() == RegularSpots.Kind.HALL, "only downstairs: " + s);
            assertFalse(s.parlor());
        }
        ClubhouseSite noParlor = ClubhousePlanner.plan(BOX, new ClubhousePlanner.Rooms(true, true, true, false));
        for (RegularSpots.Spot s : RegularSpots.of(noParlor, new ClubhousePlanner.Rooms(true, true, true, false))) {
            assertFalse(s.parlor(), "no Parlor built: no Parlor places");
        }
    }
}
