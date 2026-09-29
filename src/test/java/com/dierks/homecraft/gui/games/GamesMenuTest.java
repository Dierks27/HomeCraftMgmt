package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.world.Session;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Games screen's plain logic: how tiles sort into tabs and pages, what a player on a break
 * (or without the permission) sees in place of the games of chance — here and on the Arcade hub
 * (spec §8.1, §8.2, R1.16) — that the Arcade's own links wait while a world game is on, and that
 * an invite names its game. Daily Courses' tiles come first on their tab (GEN-SPEC §5.4): Today's
 * Courses, then the daily courses, then everything else as before.
 */
class GamesMenuTest {

    private static GamesMenu.Tile tile(Game.Tab tab, int rank, int order) {
        return new GamesMenu.Tile(tab, rank, order, null, null);
    }

    @Test
    void theAllTabSortsByTabThenCatalogOrderThenEachGamesOwnOrder() {
        GamesMenu.Tile sweeper = tile(Game.Tab.CABINETS, 5, 0);
        GamesMenu.Tile scratch = tile(Game.Tab.LUCK, GamesMenu.LINK_RANK, 0);
        GamesMenu.Tile slots = tile(Game.Tab.LUCK, 0, 0);
        GamesMenu.Tile courseB = tile(Game.Tab.COURSES, 13, 2);
        GamesMenu.Tile courseA = tile(Game.Tab.COURSES, 13, 1);
        GamesMenu.Tile merge = tile(Game.Tab.CABINETS, 6, 0);
        GamesMenu.Tile golf = tile(Game.Tab.GOLF, 14, 0);
        List<GamesMenu.Tile> in = List.of(golf, sweeper, scratch, slots, courseB, courseA, merge);

        assertEquals(List.of(slots, scratch, sweeper, merge, courseA, courseB, golf), GamesMenu.arrange(in, null),
                "All shows Luck, Cabinets, Courses, Golf in that order; inside a tab the catalog order, "
                        + "the Arcade's own links (Scratch Ticket, crates) last, then each game's tile order");
    }

    @Test
    void aTabShowsOnlyItsOwnTilesAndTheInputIsLeftAlone() {
        List<GamesMenu.Tile> in = new ArrayList<>(List.of(tile(Game.Tab.CABINETS, 6, 0), tile(Game.Tab.LUCK, 0, 0),
                tile(Game.Tab.CABINETS, 5, 0)));
        in.add(null);
        List<GamesMenu.Tile> before = new ArrayList<>(in);

        List<GamesMenu.Tile> cabinets = GamesMenu.arrange(in, Game.Tab.CABINETS);

        assertEquals(2, cabinets.size(), "only the two cabinet tiles belong on the Cabinets tab");
        assertEquals(5, cabinets.get(0).rank(), "catalog order inside the tab");
        assertEquals(before, in, "arranging must not reorder the caller's list");
        assertEquals(0, GamesMenu.arrange(in, Game.Tab.GOLF).size(), "an empty tab is empty, not an error");
    }

    @Test
    void pagesHoldThirtySixTilesAndThereIsAlwaysAtLeastOne() {
        assertEquals(1, GamesMenu.pages(0), "an empty screen is still one page");
        assertEquals(1, GamesMenu.pages(36), "rows 1-4 hold exactly 36");
        assertEquals(2, GamesMenu.pages(37), "the 37th tile starts page two");
        assertEquals(3, GamesMenu.pages(73), "73 tiles need three pages");
    }

    @Test
    void aPageOutOfRangeIsClampedAndSlicedToWhatExists() {
        List<Integer> forty = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            forty.add(i);
        }
        assertEquals(1, GamesMenu.clampPage(5, 40), "past the end goes to the last page");
        assertEquals(0, GamesMenu.clampPage(-3, 40), "before the start goes to the first page");
        assertEquals(36, GamesMenu.slice(forty, 0).size(), "the first page is full");
        assertEquals(List.of(36, 37, 38, 39), GamesMenu.slice(forty, 1), "the second page has the last four");
        assertEquals(List.of(36, 37, 38, 39), GamesMenu.slice(forty, 9), "a stale page number still shows tiles");
        assertEquals(List.of(), GamesMenu.slice(List.of(), 0), "nothing to show is an empty page");
    }

    @Test
    void theLuckTabFollowsThePermissionThenTheBreak() {
        long now = 1_000_000L;
        assertSame(GamesMenu.Luck.HIDDEN, GamesMenu.luck(false, true, now + 5, now),
                "without hcm.games.chance there is nothing at all, paused or not");
        assertSame(GamesMenu.Luck.HIDDEN, GamesMenu.luck(false, false, 0, now),
                "the permission is checked before the break is even read");
        assertSame(GamesMenu.Luck.CLOSED, GamesMenu.luck(true, false, 0, now),
                "a break that can't be read closes games of chance (fails closed)");
        assertSame(GamesMenu.Luck.PAUSED, GamesMenu.luck(true, true, now + 1, now),
                "a pause that hasn't ended replaces the games of chance with one tile");
        assertSame(GamesMenu.Luck.OPEN, GamesMenu.luck(true, true, now, now),
                "a pause is over at the exact moment it ends");
        assertSame(GamesMenu.Luck.OPEN, GamesMenu.luck(true, true, 0, now), "no pause, no break: open");
    }

    @Test
    void theTogetherTabIsAtSevenAndShowsOnlyOnceItHasATile() {
        // EVENTS-DROPPER-SPEC §A.6: row 0 is 0 balance, 2-7 the tabs, 8 an invite
        assertEquals(2, GamesMenu.tabSlot(null), "All");
        assertEquals(3, GamesMenu.tabSlot(Game.Tab.LUCK), "Luck");
        assertEquals(4, GamesMenu.tabSlot(Game.Tab.CABINETS), "Cabinets");
        assertEquals(5, GamesMenu.tabSlot(Game.Tab.COURSES), "Courses");
        assertEquals(6, GamesMenu.tabSlot(Game.Tab.GOLF), "Golf");
        assertEquals(7, GamesMenu.tabSlot(Game.Tab.TOGETHER), "Together takes the free slot 7");
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int slot : GamesMenu.TAB_SLOTS) {
            assertTrue(slot > 0 && slot < 8, slot + " stays clear of the balance (0) and the invite (8)");
            assertTrue(seen.add(slot), slot + " holds one tab");
        }
        assertFalse(GamesMenu.togetherShown(0, null), "with Race Night and Falling Floors off, slot 7 is as it was");
        assertFalse(GamesMenu.togetherShown(0, Game.Tab.COURSES), "on another tab too");
        assertTrue(GamesMenu.togetherShown(1, null), "one tile shows the tab");
        assertTrue(GamesMenu.togetherShown(0, Game.Tab.TOGETHER), "and a viewer on it keeps it");
        assertEquals(0, GamesMenu.counts(List.of(tile(Game.Tab.GOLF, 14, 0))).get(Game.Tab.TOGETHER),
                "an empty Together tab counts 0, never null");
        assertEquals(Game.Tab.COURSES, Game.Tab.of(com.dierks.homecraft.games.GameKind.TRIAL),
                "no kind maps to Together: a game picks it for its tiles");
    }

    @Test
    void eachTabIsCountedAndEmptyTabsCountZero() {
        Map<Game.Tab, Integer> counts = GamesMenu.counts(List.of(tile(Game.Tab.LUCK, 0, 0),
                tile(Game.Tab.LUCK, 1, 0), tile(Game.Tab.GOLF, 14, 0)));
        assertEquals(2, counts.get(Game.Tab.LUCK), "two Luck tiles");
        assertEquals(1, counts.get(Game.Tab.GOLF), "one Golf tile");
        assertEquals(0, counts.get(Game.Tab.CABINETS), "a tab with nothing reads 0, never null");
        assertEquals(0, counts.get(Game.Tab.COURSES), "a tab with nothing reads 0, never null");
    }

    @Test
    void theHubShowsCratesAndTheTicketOnlyWhileGamesOfChanceAreOpenToThePlayer() {
        assertTrue(GamesMenu.hubShowsChance(true, GamesMenu.Luck.OPEN), "open: as normal");
        assertFalse(GamesMenu.hubShowsChance(true, GamesMenu.Luck.PAUSED), "on a break: the break tile instead");
        assertFalse(GamesMenu.hubShowsChance(true, GamesMenu.Luck.CLOSED), "unreadable break: closed instead");
        assertFalse(GamesMenu.hubShowsChance(true, GamesMenu.Luck.HIDDEN),
                "without hcm.games.chance they aren't seen at all, as plugin.yml promises");
        for (GamesMenu.Luck any : GamesMenu.Luck.values()) {
            assertTrue(GamesMenu.hubShowsChance(false, any), "games off: the hub is exactly the old one (" + any + ")");
        }
        assertTrue(GamesMenu.hubShowsChance(false, null), "games off: the luck isn't even read");
    }

    @Test
    void theArcadesLinksWaitWhileTheViewerIsInAWorldGame() {
        Session session = new Session(UUID.randomUUID(), "trials", "river_run", "s1", "games",
                Session.Phase.ACTIVE, 0L);
        assertSame(Refusal.IN_SESSION, GamesMenu.linkRefusal(session),
                "a Scratch Ticket or crate from inside a world game is refused before anything is taken");
        assertNull(GamesMenu.linkRefusal(null), "outside one, go ahead");
    }

    @Test
    void anInviteTileNamesItsGameAndWhoItIsFrom() {
        assertEquals("&eConnect Four invite &7from Sam", GamesMenu.inviteName("Connect Four", "Sam"),
                "the game in the NAME: Bedrock shows lore only on a long press");
        assertEquals("&eGame invite &7from a player", GamesMenu.inviteName(null, null),
                "still reads with nothing known");
        com.dierks.homecraft.games.Invite ride = new com.dierks.homecraft.games.Invite(1, UUID.randomUUID(),
                UUID.randomUUID(), "rider", "a ride in the back of Dad's boat", 0L, 60_000L, "Ride along");
        assertEquals("&eRide along invite &7from Dad", GamesMenu.inviteName(ride.name(), "Dad"),
                "a ride's tile says it is a ride in its NAME (the final gate's #0), from the invite's own name");
    }

    // ---- Fresh Courses first ------------------------------------------------------------------

    private static GamesMenu.Tile tile(Game.Tab tab, String playId, int rank, int order) {
        return new GamesMenu.Tile(tab, GamesMenu.priority(playId), rank, order, null, null);
    }

    @Test
    void generatedTilesComeFirstOnTheirTab() {
        GamesMenu.Tile river = tile(Game.Tab.COURSES, "river_run", 13, 0);
        GamesMenu.Tile cliffs = tile(Game.Tab.COURSES, "cliffs", 13, 1);
        GamesMenu.Tile easy = tile(Game.Tab.COURSES, "fresh_parkour_easy", 13, 2);
        GamesMenu.Tile rings = tile(Game.Tab.COURSES, "fresh_rings", 13, 3);
        GamesMenu.Tile today = tile(Game.Tab.COURSES, Slots.DAILY, 15, 0);
        GamesMenu.Tile meadow = tile(Game.Tab.GOLF, "meadow", 14, 0);
        GamesMenu.Tile tiny = tile(Game.Tab.GOLF, "fresh_tiny_golf", 14, 1);
        GamesMenu.Tile todayGolf = tile(Game.Tab.GOLF, "fresh_courses", 15, 1);
        GamesMenu.Tile snake = tile(Game.Tab.CABINETS, "snake", 7, 0);
        List<GamesMenu.Tile> in = List.of(river, cliffs, easy, rings, today, meadow, tiny, todayGolf, snake);

        assertEquals(List.of(today, easy, rings, river, cliffs), GamesMenu.arrange(in, Game.Tab.COURSES),
                "the Fresh Courses screen, then the fresh courses in their own order, then the hand-built ones as "
                        + "before, even though Fresh Courses sits later in the catalog");
        assertEquals(List.of(todayGolf, tiny, meadow), GamesMenu.arrange(in, Game.Tab.GOLF), "the same on the Golf tab");
        assertEquals(List.of(snake, today, easy, rings, river, cliffs, todayGolf, tiny, meadow),
                GamesMenu.arrange(in, null), "the tabs keep their order on All");
    }

    @Test
    void onlyDailyCoursesIdsJumpTheQueue() {
        assertEquals(GamesMenu.TODAY, GamesMenu.priority("fresh_courses"), "the Fresh Courses screen");
        assertEquals(GamesMenu.TODAY, GamesMenu.priority(" Fresh_Parkour_Tiers "), "the tier picker, any case");
        assertEquals(GamesMenu.OTHERS, GamesMenu.priority("daily"), "the old id is nothing now");
        for (String id : Slots.ids()) {
            assertEquals(GamesMenu.DAILY, GamesMenu.priority(id), id + " is a fresh course");
        }
        assertEquals(GamesMenu.OTHERS, GamesMenu.priority("river_run"), "a hand-built course");
        assertEquals(GamesMenu.OTHERS, GamesMenu.priority(null), "no id");
        assertEquals(GamesMenu.OTHERS, new GamesMenu.Tile(Game.Tab.LUCK, 0, 0, null, null).priority(),
                "an old-style tile sorts as before");
    }
}
