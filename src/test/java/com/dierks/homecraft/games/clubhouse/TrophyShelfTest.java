package com.dierks.homecraft.games.clubhouse;

import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Trophy Room (0.41.0): the same feed as the trophy case, each cup beside the right head (its holder's), the
 * podium's gold, silver and bronze, the shimmer only on the walker's own cups, the fanfare only for a cup that
 * changed hands; and who sees the models (with the pack loaded) or the vanilla stand-ins (anyone else).
 */
class TrophyShelfTest {

    private static final UUID SAM = UUID.randomUUID();
    private static final UUID DAD = UUID.randomUUID();
    private static final UUID LILAH = UUID.randomUUID();

    private static FameBoard.Fame fame(List<FameBoard.FamilyRow> lastWeek) {
        return new FameBoard.Fame(List.of(), null, null, 0, List.of(),
                List.of(new FameBoard.CourseTime("Sky Rings", "Sam", 62_400, SAM),
                        new FameBoard.CourseTime("Cliff Hop", "Dad", 81_000, DAD)),
                List.of(new FameBoard.GolfRound("Windmill", "LapisFox", 20, -2, LILAH)),
                List.of(), lastWeek, 7);
    }

    @Test
    void eachRecordIsAGoldCupWithItsHoldersHead() {
        List<TrophyShelf.Cup> cups = TrophyShelf.cups(fame(List.of()), 11);
        assertEquals(3, cups.size(), "two course records and a golf record");
        assertEquals(TrophyShelf.PODIUM, cups.get(0).slot(), "after the podium's three");
        assertEquals(SAM, cups.get(0).id(), "Sam's head beside Sam's record");
        assertEquals(DAD, cups.get(1).id(), "Dad's beside Dad's");
        assertEquals(LILAH, cups.get(2).id(), "and Lilah's beside her golf record");
        for (TrophyShelf.Cup c : cups) {
            assertEquals("hcm:trophy_gold", c.model().id(), "a record is a 1st");
        }
        assertTrue(cups.get(0).label().contains("Sky Rings") && cups.get(0).label().contains("1:02.4")
                && cups.get(0).label().contains("Sam"), cups.get(0).label());
        assertTrue(cups.get(2).label().contains("20 (-2)"), cups.get(2).label());
        assertEquals(List.of(), TrophyShelf.cups(null, 11), "nothing before the first read");
        assertEquals(1, TrophyShelf.cups(fame(List.of()), 4).size(), "only as many as there are pedestals");
    }

    @Test
    void lastWeeksTopThreeStandOnThePodiumGoldSilverBronze() {
        List<TrophyShelf.Cup> cups = TrophyShelf.cups(fame(List.of(new FameBoard.FamilyRow(LILAH, "LapisFox", 40, 0),
                new FameBoard.FamilyRow(DAD, "Dad", 30, 0), new FameBoard.FamilyRow(SAM, "Sam", 10, 0))), 11);
        assertEquals(6, cups.size());
        assertEquals(List.of(0, 1, 2), cups.subList(0, 3).stream().map(TrophyShelf.Cup::slot).toList());
        assertEquals(List.of("hcm:trophy_gold", "hcm:trophy_silver", "hcm:trophy_bronze"),
                cups.subList(0, 3).stream().map(c -> c.model().id()).toList());
        assertEquals(List.of(LILAH, DAD, SAM), cups.subList(0, 3).stream().map(TrophyShelf.Cup::id).toList());
        FameBoard.Fame off = new FameBoard.Fame(List.of(), null, null, 0, List.of(), List.of(), List.of(), List.of(),
                List.of(new FameBoard.FamilyRow(LILAH, "LapisFox", 40, 0)), -1);
        assertEquals(List.of(), TrophyShelf.cups(off, 11), "no family week: no podium");
    }

    @Test
    void theShimmerIsOnlyOnTheWalkersOwnCups() {
        List<TrophyShelf.Cup> cups = TrophyShelf.cups(fame(List.of(new FameBoard.FamilyRow(LILAH, "LapisFox", 40, 0))),
                11);
        assertEquals(List.of(0, 5), TrophyShelf.mine(cups, LILAH), "her podium cup and her golf record");
        assertEquals(List.of(4), TrophyShelf.mine(cups, DAD), "Dad's record only");
        assertEquals(List.of(), TrophyShelf.mine(cups, UUID.randomUUID()), "someone with none: nothing shimmers");
        assertEquals(List.of(), TrophyShelf.mine(cups, null));
    }

    @Test
    void aCupThatChangesHandsIsCheeredButNotTheFirstLook() {
        List<TrophyShelf.Cup> before = TrophyShelf.cups(fame(List.of()), 11);
        assertEquals(List.of(), TrophyShelf.won(List.of(), before), "the room just opened: nothing is new");
        assertEquals(List.of(), TrophyShelf.won(before, before), "nothing changed");
        FameBoard.Fame lilahWins = new FameBoard.Fame(List.of(), null, null, 0, List.of(),
                List.of(new FameBoard.CourseTime("Sky Rings", "LapisFox", 60_000, LILAH),
                        new FameBoard.CourseTime("Cliff Hop", "Dad", 81_000, DAD)),
                List.of(new FameBoard.GolfRound("Windmill", "LapisFox", 20, -2, LILAH)), List.of(), List.of(), 7);
        assertEquals(List.of(TrophyShelf.PODIUM), TrophyShelf.won(before, TrophyShelf.cups(lilahWins, 11)),
                "Lilah's new Sky Rings record");
    }

    /** With the pack loaded the models; declined, failed, not answered yet or no CraftBridge, the stand-ins. */
    @Test
    void theModelsOnlyForAPlayerWithThePackLoaded() {
        assertTrue(SideRooms.seesModels(true, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
        for (PlayerResourcePackStatusEvent.Status s : PlayerResourcePackStatusEvent.Status.values()) {
            if (s != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
                assertFalse(SideRooms.seesModels(true, s), "the stand-ins after " + s);
            }
            assertFalse(SideRooms.seesModels(false, s), "no CraftBridge: always the stand-ins (" + s + ")");
        }
        assertFalse(SideRooms.seesModels(true, null), "no answer yet");
    }

    @Test
    void aRecordOfUnknownHolderHasNoHead() {
        FameBoard.Fame f = new FameBoard.Fame(List.of(), null, null, 0, List.of(),
                List.of(new FameBoard.CourseTime("Sky Rings", "Sam", 62_400)), List.of());
        assertNull(TrophyShelf.cups(f, 11).get(0).id(), "0.40.0's rows had no id: a cup with no head");
    }
}
