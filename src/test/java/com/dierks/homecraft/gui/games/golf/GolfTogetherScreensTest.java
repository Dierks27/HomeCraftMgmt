package com.dierks.homecraft.gui.games.golf;

import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.golf.GolfGroup;
import com.dierks.homecraft.games.golf.GolfRun;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golf together's two screens (EVENTS-OWNER-DECISIONS D4), with no server: the party and the shared
 * scorecard. Pinned here: every build paints every slot once; the party shows who's in, who hosts
 * and who's ready in the NAMES, only the host can start (with 2 or more), a full party can't invite;
 * the shared card has a row per player with their holes, where they are in the NAME, the group
 * ranking at the end, page arrows only at 45 and 53 for 18 holes and the way out at 49; and every
 * line uses glyphs Bedrock can draw and none of the words the games never say.
 */
class GolfTogetherScreensTest {

    private static final UUID SAM = new UUID(0, 1);
    private static final UUID AVA = new UUID(0, 2);

    // ---- the party ------------------------------------------------------------------------------

    private static GolfPartyMenu.View party(boolean youHost, String startBlock, int members, boolean open) {
        List<GolfPartyMenu.Member> m = new ArrayList<>();
        m.add(new GolfPartyMenu.Member("Sam", true, true, youHost));
        if (members > 1) {
            m.add(new GolfPartyMenu.Member("Ava", false, false, !youHost));
        }
        for (int i = 2; i < members; i++) {
            m.add(new GolfPartyMenu.Member("P" + i, false, false, false));
        }
        return new GolfPartyMenu.View("Meadow", 9, 27, m, 4, youHost, youHost, open, startBlock);
    }

    @Test
    void thePartyShowsWhosInWhoHostsAndWhosReady() {
        List<GolfPartyMenu.Tile> t = GolfPartyMenu.tiles(party(true, null, 2, true), false);
        assertEquals(GolfPartyMenu.SIZE, t.size(), "27 slots");
        for (int i = 0; i < t.size(); i++) {
            assertEquals(i, t.get(i).slot(), "every slot, in order");
        }
        assertEquals("&dGolf together &7- Meadow, 2 of 4 in", t.get(GolfPartyMenu.HEADER).name(), "the course, 2 of 4");
        assertEquals("&aSam &7(you) &7- host, ready", t.get(GolfPartyMenu.FIRST_MEMBER).name(), "the host, ready");
        assertEquals("&fAva", t.get(GolfPartyMenu.FIRST_MEMBER + 1).name(), "a friend, not ready yet");
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, t.get(GolfPartyMenu.FIRST_MEMBER + 2).material(),
                "a free spot");
        assertEquals("&aStart &7- everyone to hole 1", t.get(GolfPartyMenu.START).name(), "the host can start");
        assertEquals("&bInvite a friend", t.get(GolfPartyMenu.INVITE).name(), "room to invite");
        assertEquals("&cLeave the party", t.get(GolfPartyMenu.LEAVE).name(), "leave");
        assertEquals(Material.BARRIER, t.get(GolfPartyMenu.EXIT).material(), "the way out at 22");
    }

    @Test
    void onlyTheHostStartsAndAFullPartyCantInvite() {
        List<GolfPartyMenu.Tile> guest = GolfPartyMenu.tiles(party(false, "Only the party's host can start.", 2, true),
                true);
        assertEquals("&7Waiting for Sam to start", guest.get(GolfPartyMenu.START).name(), "a guest waits for the host");
        List<GolfPartyMenu.Tile> alone = GolfPartyMenu.tiles(party(true, "Invite a friend first - a party needs 2 players.",
                1, true), true);
        assertEquals("&7Invite a friend first - a party needs 2 players.", alone.get(GolfPartyMenu.START).name(),
                "alone: invite first");
        List<GolfPartyMenu.Tile> full = GolfPartyMenu.tiles(party(true, null, 4, true), true);
        assertEquals("&7The party is full &8(4)", full.get(GolfPartyMenu.INVITE).name(), "4 of 4: full");
        List<GolfPartyMenu.Tile> playing = GolfPartyMenu.tiles(party(true, "That party has already started.", 2, false),
                true);
        assertEquals("&7Playing now", playing.get(GolfPartyMenu.INVITE).name(), "no invites mid-round");
    }

    // ---- the shared card --------------------------------------------------------------------------

    private static GolfGroup group(int holes) {
        Map<UUID, String> names = new LinkedHashMap<>();
        names.put(SAM, "Sam");
        names.put(AVA, "Ava");
        List<Integer> pars = new ArrayList<>();
        for (int i = 0; i < holes; i++) {
            pars.add(3);
        }
        return new GolfGroup(1, "meadow", "Meadow", pars, names);
    }

    @Test
    void theSharedCardHasARowPerPlayerWithWhereTheyAre() {
        GolfGroup g = group(9);
        g.holeDone(SAM, new GolfRun.HoleScore(3, 1, false));
        g.strokes(AVA, 2);
        List<GolfGroupCardMenu.Tile> t = GolfGroupCardMenu.tiles(g.card(), 0, false);
        assertEquals(GolfGroupCardMenu.SIZE, t.size(), "54 slots");
        assertEquals("&dGolf together &8· &fMeadow &7- hole 1 of 9", t.get(GolfGroupCardMenu.HEADER).name(), "the hole");
        assertEquals("&fSam &7- done with hole 1", t.get(9).name(), "Sam's row: done with the hole");
        assertEquals(Material.NETHER_STAR, t.get(10).material(), "Sam's hole-in-one shines");
        assertTrue(t.get(10).glint(), "with a glint");
        assertEquals(1, t.get(10).amount(), "the stack count is the hole number");
        assertEquals("&fAva &7- hole 1: 2 strokes so far", t.get(18).name(), "Ava's row: playing");
        assertEquals(Material.LIGHT_BLUE_CONCRETE, t.get(19).material(), "Ava's hole 1 is being played");
        assertEquals(Material.GRAY_CONCRETE, t.get(20).material(), "hole 2 not played yet");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, t.get(27).material(), "no third player");
        assertEquals("&7Still playing: Ava &8- &epicked up in 2:00", t.get(GolfGroupCardMenu.STATUS).name(),
                "who is still out, and when the hole clock picks their ball up, in the NAME for Bedrock");
        assertEquals(Material.BARRIER, t.get(GolfGroupCardMenu.EXIT).material(), "the way out at 49");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, t.get(GolfGroupCardMenu.PREV).material(), "9 holes: one page");
        assertEquals(Material.ARROW, t.get(GolfGroupCardMenu.NEXT).material(), "hole 9 is on the next page");
    }

    @Test
    void eighteenHolesPageWithArrowsOnlyAt45And53() {
        GolfGroup g = group(18);
        assertEquals(3, GolfGroupCardMenu.pages(g.card()), "8 holes a page");
        List<GolfGroupCardMenu.Tile> middle = GolfGroupCardMenu.tiles(g.card(), 1, false);
        assertEquals(Material.ARROW, middle.get(GolfGroupCardMenu.PREV).material(), "back");
        assertEquals(Material.ARROW, middle.get(GolfGroupCardMenu.NEXT).material(), "on");
        assertEquals(9, middle.get(10).amount(), "page 2 starts at hole 9");
        for (GolfGroupCardMenu.Tile tile : middle) {
            if (tile.material() == Material.ARROW) {
                assertTrue(tile.slot() == 45 || tile.slot() == 53, "arrows only at 45 and 53: " + tile.slot());
            }
            if (tile.material() == Material.BARRIER) {
                assertEquals(49, tile.slot(), "the way out only at 49");
            }
        }
        List<GolfGroupCardMenu.Tile> last = GolfGroupCardMenu.tiles(g.card(), 2, false);
        assertEquals(17, last.get(10).amount(), "the last page: holes 17 and 18");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, last.get(12).material(), "nothing past hole 18");
    }

    @Test
    void theEndRanksTheGroupAndOffersPlayingAgain() {
        GolfGroup g = group(2);
        g.holeDone(SAM, new GolfRun.HoleScore(3, 2, false));
        g.holeDone(AVA, new GolfRun.HoleScore(3, 3, false));
        g.advance();
        g.holeDone(SAM, new GolfRun.HoleScore(3, 3, false));
        g.holeDone(AVA, new GolfRun.HoleScore(3, 3, false));
        g.advance();
        List<GolfGroupCardMenu.Tile> t = GolfGroupCardMenu.tiles(g.card(), 0, true);
        assertTrue(t.get(GolfGroupCardMenu.HEADER).name().endsWith("final"), "final");
        assertEquals("&61st Sam &7- 5 strokes (1 under par)", t.get(9).name(), "Sam 1st, his total in the name");
        assertEquals("&f2nd Ava &7- 6 strokes (par)", t.get(18).name(), "Ava 2nd");
        assertEquals("&6Fewest strokes: Sam", t.get(GolfGroupCardMenu.STATUS).name(), "the winner");
        assertEquals("&aPlay again together", t.get(GolfGroupCardMenu.AGAIN).name(), "play again");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE,
                GolfGroupCardMenu.tiles(g.card(), 0, false).get(GolfGroupCardMenu.AGAIN).material(),
                "not while away or out of the party");
    }

    @Test
    void everyLineIsKidSafeAndDrawable() {
        List<String> lines = new ArrayList<>();
        for (GolfPartyMenu.Tile t : GolfPartyMenu.tiles(party(true, null, 2, true), true)) {
            lines.add(t.name());
            lines.addAll(t.lore());
        }
        GolfGroup g = group(3);
        g.holeDone(SAM, new GolfRun.HoleScore(3, 6, true));
        g.left(AVA);
        for (GolfGroupCardMenu.Tile t : GolfGroupCardMenu.tiles(g.card(), 0, true)) {
            lines.add(t.name());
            lines.addAll(t.lore());
        }
        for (String l : lines) {
            assertEquals(List.of(), GenCopy.copyProblems(l), l);
            assertFalse(l.toLowerCase().matches(".*\\b(bet|wager|gamble|casino|lucky|almost|sink)\\b.*"), "never: " + l);
        }
    }
}
