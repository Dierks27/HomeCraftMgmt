package com.dierks.homecraft.games.arena.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dierks.homecraft.games.arena.rules.ArenaFixtures.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Falling Floors says: "You lasted 0:42 - 3rd of 6!" (EVENTS-DROPPER-SPEC §B.3.3), places that
 * share, the winner's line, the results for the gallery, and the house rules for copy (no emoji,
 * nothing above U+FFFF).
 */
class ArenaTextTest {

    @Test
    void timesReadLikeAClock() {
        assertEquals("0:00", ArenaText.clock(0), "nothing");
        assertEquals("0:00", ArenaText.clock(19), "under a second rounds down");
        assertEquals("0:42", ArenaText.clock(840), "42 s");
        assertEquals("1:12", ArenaText.clock(1440), "72 s");
        assertEquals("61:00", ArenaText.clock(61 * 1200), "minutes keep counting past an hour");
        assertEquals("0:00", ArenaText.clock(-5), "never negative");
    }

    @Test
    void ordinalsAreEnglish() {
        int[] n = {1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101, 111, 112};
        String[] want = {"1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st", "111th",
                "112th"};
        for (int i = 0; i < n.length; i++) {
            assertEquals(want[i], ArenaText.ordinal(n[i]), "the ordinal of " + n[i]);
        }
    }

    @Test
    void theOutLineIsTheSpecsLine() {
        assertEquals("&eYou lasted 0:42 - 3rd of 6!", ArenaText.outLine(new RoundEvent.Out(p(1), 840, 3, 6, false,
                OutReason.FELL, false)), "the spec's own words");
        assertEquals("&eYou lasted 0:42 - joint 3rd of 6!", ArenaText.outLine(new RoundEvent.Out(p(1), 840, 3, 6,
                true, OutReason.FELL, false)), "a shared place says so");
        assertEquals("&eYou lasted 0:42!", ArenaText.outLine(new RoundEvent.Out(p(1), 840, 1, 1, false,
                OutReason.FELL, true)), "solo: just the time");
        assertEquals("&aYou won together - joint 1st of 3! You lasted 1:12.", ArenaText.outLine(new RoundEvent.Out(
                p(1), 1440, 1, 3, true, OutReason.FELL, false)), "the last ones out together won together");
        assertEquals("&7You left the round after 0:05.", ArenaText.outLine(new RoundEvent.Out(p(1), 100, 4, 4, false,
                OutReason.LEFT, false)), "leaving is said plainly, with no place");
    }

    @Test
    void theLastOneStandingHearsTheyWon() {
        RoundResult won = new RoundResult(1, false, false, true, 2, 1440, List.of(
                new Standing(p(1), 1, 1440, null, false, true), new Standing(p(2), 2, 1440, OutReason.FELL, false, false)));
        assertEquals("&aLast one standing - you won! You lasted 1:12.", ArenaText.standingLine(won, won.standing(p(1))),
                "the winner's line");
        assertNull(ArenaText.standingLine(won, won.standing(p(2))), "2nd had their out line already");
        RoundResult walkover = new RoundResult(1, false, false, false, 2, 600, List.of(
                new Standing(p(1), 1, 600, null, false, false), new Standing(p(2), 2, 600, OutReason.LEFT, false, false)));
        assertEquals("&eEveryone else left, so the round is over. You lasted 0:30.",
                ArenaText.standingLine(walkover, walkover.standing(p(1))), "no win claimed when everyone else left");
    }

    @Test
    void theGalleryHearsTheResults() {
        Map<UUID, String> names = Map.of(p(1), "Alex", p(2), "Sam", p(3), "Jo");
        RoundResult won = new RoundResult(1, false, false, true, 3, 1440, List.of(
                new Standing(p(1), 1, 1440, null, false, true),
                new Standing(p(2), 2, 1440, OutReason.FELL, false, false),
                new Standing(p(3), 3, 600, OutReason.FELL, false, false)));
        assertEquals(List.of("&6Falling Floors: &fAlex &ewon&6!", "&71st &fAlex &71:12", "&72nd &fSam &71:12",
                "&73rd &fJo &70:30"), ArenaText.results(won, names::get), "a headline, then the places");
        RoundResult together = new RoundResult(2, false, false, true, 2, 500, List.of(
                new Standing(p(1), 1, 500, OutReason.FELL, true, true), new Standing(p(2), 1, 500, OutReason.FELL, true,
                        true)));
        assertEquals("&6Falling Floors: &fAlex&e, &fSam &ewon together&6!",
                ArenaText.results(together, names::get).get(0), "joint winners are named together");
        RoundResult solo = new RoundResult(3, true, false, false, 1, 840, List.of(new Standing(p(3), 1, 840,
                OutReason.FELL, false, false)));
        assertEquals(List.of("&6Falling Floors: &fJo &elasted 0:42&6!"), ArenaText.results(solo, names::get),
                "a solo round is one line");
        assertEquals(List.of(ArenaText.calledOff()), ArenaText.results(RoundResult.calledOff(4, false, 2, 0),
                names::get), "a called-off round says so");
        assertEquals("&6Falling Floors: &fSomeone &elasted 0:42&6!", ArenaText.results(solo, id -> null).get(0),
                "a name that can't be found is 'Someone', never 'null'");
    }

    @Test
    void aBigRoundShowsEightPlacesAndSaysHowManyMore() {
        List<Standing> st = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            st.add(new Standing(p(i), i, 1000 - i, i == 1 ? null : OutReason.FELL, false, i == 1));
        }
        List<String> lines = ArenaText.results(new RoundResult(1, false, false, true, 12, 999, st), id -> "P");
        assertEquals(10, lines.size(), "a headline, eight places and one more line");
        assertEquals("&7...and 4 more", lines.get(9), "the rest are counted, not listed");
    }

    @Test
    void theCountdownAndLobbyLinesCountInWholeSeconds() {
        assertEquals("&eFalling Floors starts in 10", ArenaText.countdown(200), "10 s at the start of the bar");
        assertEquals("&eFalling Floors starts in 10", ArenaText.countdown(181), "still 10 until 9 s are left");
        assertEquals("&eFalling Floors starts in 9", ArenaText.countdown(180), "9");
        assertEquals("&eFalling Floors starts in 1", ArenaText.countdown(1), "1, never 0");
        assertEquals("&e2/12 here &7- &a1 ready &7- starts in 0:20", ArenaText.lobby(2, 1, 12, 400),
                "the lobby's bar with its 20 s wait");
        assertEquals("&e1/12 here &7- &a0 ready &7- waiting for a friend (or play solo)", ArenaText.lobby(1, 0, 12, -1),
                "alone");
    }

    @Test
    void everyRefusalHasALineAndSuccessHasNone() {
        for (ArenaRound.Solo s : ArenaRound.Solo.values()) {
            if (s == ArenaRound.Solo.STARTED) {
                assertNull(ArenaText.solo(s), "starting needs no refusal line");
            } else {
                assertNotNull(ArenaText.solo(s), "solo refusal " + s + " is explained");
            }
        }
        for (ArenaRound.Join j : ArenaRound.Join.values()) {
            if (j == ArenaRound.Join.JOINED) {
                assertNull(ArenaText.join(j, 12), "joining needs no refusal line");
            } else {
                assertNotNull(ArenaText.join(j, 12), "join refusal " + j + " is explained");
            }
        }
        assertEquals("&eThe server restarts at 4:00 PM - rounds start again after it.", ArenaText.hold("4:00 PM"),
                "the restart hold, in the RestartHold refusal's own words");
    }

    @Test
    void noLineUsesAnythingAboveUffff() {
        List<String> all = new ArrayList<>(List.of(ArenaText.go(), ArenaText.suddenDeath(), ArenaText.calledOff(),
                ArenaText.closed(), ArenaText.hold("4:00 PM"), ArenaText.countdown(100), ArenaText.lobby(3, 2, 12, 100)));
        for (ArenaRound.Solo s : ArenaRound.Solo.values()) {
            if (ArenaText.solo(s) != null) {
                all.add(ArenaText.solo(s));
            }
        }
        for (ArenaRound.Join j : ArenaRound.Join.values()) {
            if (ArenaText.join(j, 12) != null) {
                all.add(ArenaText.join(j, 12));
            }
        }
        for (String line : all) {
            assertTrue(line.codePoints().allMatch(c -> c <= 0xFFFF), "no emoji or other astral characters: " + line);
            assertTrue(line.length() <= 80, "short enough for a phone's chat: " + line);
        }
    }
}
