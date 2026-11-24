package com.dierks.homecraft.games;

import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.clubhouse.KidSet;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.games.kidbingo.KidBingo;
import com.dierks.homecraft.games.kidbingo.KidBingo.Phase;
import com.dierks.homecraft.games.kidbingo.KidBingoSettings;
import com.dierks.homecraft.games.kidbingo.Picture;
import com.dierks.homecraft.games.kidbingo.PictureCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcade Room's Picture Bingo (0.57.0; the owner: "Free, prize only if she wins", as the kid race), run for real
 * over the framework's gate, rewards and database:
 *
 * <ul>
 *   <li>free and nothing like a game of chance: no round, no permission for chance, no limit of the Parlor's;</li>
 *   <li>pictures only: a card is eight different animals round a star, each animal its own spawn egg;</li>
 *   <li>the round ends on the first animal that gives a card three in a row; only a player whose card has it is paid,
 *       once a round, under the game's daily cap;</li>
 *   <li>NPCs join only a round a real player is in, play easy (they miss animals) and are paid nothing.</li>
 * </ul>
 */
class KidBingoTest {

    private Host host;
    private GamesService games;
    private KidBingo bingo;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 11, 24, 17, 0));
        open(KidBingoSettings.defaults());
    }

    private void open(KidBingoSettings settings) {
        host.config = GamesKit.config(GamesKit.common(true, 1000, 600, 60), "kid_bingo", settings);
        games = GamesKit.service(host, List.of(KidBingo.SPEC));
        bingo = (KidBingo) games.game("kid_bingo");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    private Fake player(String name) {
        Fake f = new Fake(name);
        host.online.put(f.id, f.player);
        host.give(f.id, 50);
        return f;
    }

    private void pass(long ms) {
        host.move(ms);
        bingo.tick();
    }

    /** Call the round until it is won (the win standing). */
    private void callToTheWin() {
        assertEquals(Phase.CALLING, bingo.phase());
        for (int i = 0; i < 40 && bingo.phase() == Phase.CALLING; i++) {
            pass(bingo.settings().callMs());
        }
        assertEquals(Phase.RESULT, bingo.phase(), "someone got three in a row");
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = host.connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    // ---- the card ---------------------------------------------------------------------------------------------

    @Test
    void aCardIsEightDifferentAnimalsRoundAStar() {
        for (long seed = 0; seed < 2_000; seed++) {
            PictureCard c = PictureCard.deal(seed);
            Set<Picture> seen = new HashSet<>();
            for (int sq = 0; sq < PictureCard.SQUARES; sq++) {
                if (sq == PictureCard.FREE) {
                    assertNull(c.at(sq), "the star in the middle");
                    assertTrue(c.lit(sq, Set.of()), "lit from the start");
                    continue;
                }
                assertTrue(seen.add(c.at(sq)), "no animal twice");
            }
            assertEquals(8, seen.size());
            assertFalse(c.won(Set.of()), "nothing won before an animal is called");
            assertTrue(c.won(Set.of(c.at(3), c.at(5))), "the middle row: two animals and the star");
            assertEquals(3, c.litCount(Set.of(c.at(0), c.at(1))));
        }
        assertEquals(16, Picture.COUNT, "sixteen animals");
        Set<String> eggs = new HashSet<>();
        for (Picture p : Picture.values()) {
            assertTrue(p.egg().endsWith("_SPAWN_EGG"), p + ": a picture, never a word");
            assertTrue(eggs.add(p.egg()), "each its own");
            assertTrue(p.sound().startsWith("entity."), p + "'s sound");
        }
    }

    // ---- free, and never a game of chance ------------------------------------------------------------------

    @Test
    void aRoundIsFreeAndOnlyAWinningCardIsPaid() throws Exception {
        Fake lilah = player("Lilah");
        Fake dad = player("Dad");
        lilah.denied.add(PlayGate.PERMISSION_CHANCE); // a child without the chance permission
        assertNull(games.canOpen(lilah.player, bingo), "never behind hcm.games.chance");
        assertTrue(games.open(lilah.player, "kid_bingo", null), "a tap opens it and deals her a card");
        assertFalse(bingo.kind().chance());
        assertFalse(bingo.featurable());
        assertEquals(Phase.GATHERING, bingo.phase(), "her card starts the wait");
        assertNotNull(bingo.entry(lilah.id));
        assertTrue(games.open(dad.player, "kid_bingo", null), "Dad taps too: the same round");
        assertEquals(2, bingo.entries().size());
        assertEquals(10, bingo.secondsLeft());
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"), "no round of chance, ever");

        bingo.startNow(lilah.player);
        assertEquals(Phase.GATHERING, bingo.phase(), "Dad hasn't tapped Go yet");
        bingo.startNow(dad.player);
        assertEquals(Phase.CALLING, bingo.phase(), "everyone tapped Go: it starts");
        assertEquals(0, bingo.calledSoFar().size());
        pass(1);
        assertEquals(1, bingo.calledSoFar().size(), "the first animal at once");
        assertNotNull(bingo.current());
        callToTheWin();

        int winners = 0;
        int paid = 0;
        for (KidBingo.Entry e : bingo.entries()) {
            assertEquals(e.card().won(e.caught()), e.won());
            if (e.won()) {
                winners++;
                paid += host.balanceOf(e.id()) - 50;
                assertTrue(heard(e.id(), lilah, dad).contains("BINGO!"));
            } else {
                assertEquals(50, host.balanceOf(e.id()), "nothing taken from a card that didn't win");
                assertTrue(heard(e.id(), lilah, dad).contains("got three in a row! Hooray!"));
            }
        }
        assertTrue(winners >= 1, "the round ends on a win");
        assertEquals(2 * winners, paid, "2 tokens for each winning card, once");
        String all = (lilah.heard() + dad.heard()).toLowerCase(Locale.ROOT);
        for (String sad : List.of("lost", "lose", "sorry", "no win")) {
            assertFalse(all.contains(sad), "nothing sad is said: " + all);
        }
        pass(KidBingo.RESULT_MS);
        assertEquals(Phase.IDLE, bingo.phase(), "Play again starts the next one");
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
        assertEquals(0, host.severe());
    }

    private static String heard(java.util.UUID id, Fake... fakes) {
        for (Fake f : fakes) {
            if (f.id.equals(id)) {
                return f.heard();
            }
        }
        return "";
    }

    @Test
    void thePrizesStopAtTheDailyCapAndEachRoundPaysOnce() throws Exception {
        open(new KidBingoSettings(true, 3, 7, 10, 3));
        Fake lilah = player("Lilah");
        int rounds = 0;
        for (int i = 0; i < 30 && host.balanceOf(lilah.id) < 57; i++) {
            assertTrue(games.open(lilah.player, "kid_bingo", null));
            bingo.startNow(lilah.player);
            callToTheWin();
            assertTrue(bingo.entry(lilah.id).won(), "alone, her card always gets there");
            pass(KidBingo.RESULT_MS);
            rounds++;
        }
        assertEquals(57, host.balanceOf(lilah.id), "3 + 3 + 1: the cap of 7");
        assertEquals(3, rounds);
        assertTrue(games.open(lilah.player, "kid_bingo", null));
        bingo.startNow(lilah.player);
        callToTheWin();
        pass(KidBingo.RESULT_MS);
        assertEquals(57, host.balanceOf(lilah.id), "past the cap the rounds are just for fun");
    }

    @Test
    void aTapWhileARoundIsCalledWatchesItAndAWalkOutLeavesIt() throws Exception {
        Fake lilah = player("Lilah");
        Fake late = player("Late");
        assertTrue(bingo.join(lilah.player));
        bingo.startNow(lilah.player);
        assertEquals(Phase.CALLING, bingo.phase());
        assertFalse(bingo.join(late.player), "too late for this one");
        assertTrue(bingo.watching(late.id), "watching it");
        assertNull(bingo.entry(late.id));
        callToTheWin();
        pass(KidBingo.RESULT_MS);
        assertFalse(bingo.watching(late.id));
        assertNotNull(bingo.lastCard(lilah.id), "the card the end screen shows");

        // the only real player leaves before the start: the round is off
        assertTrue(bingo.join(late.player));
        CpuPlayer pip = CpuRoster.ALL.get(0);
        assertTrue(bingo.enterNpc(pip.id(), CpuRoster.label(pip.name(), KidBingo.NPC_LEVEL)));
        host.online.remove(late.id);
        pass(1_000);
        assertEquals(Phase.IDLE, bingo.phase(), "a round of NPCs alone never runs");
        assertTrue(bingo.entries().isEmpty());
    }

    // ---- NPCs ----------------------------------------------------------------------------------------------

    @Test
    void npcsJoinOnlyARealPlayersRoundPlayEasyAndAreNeverPaid() throws Exception {
        CpuPlayer pip = CpuRoster.ALL.get(0);
        CpuPlayer bo = CpuRoster.ALL.get(1);
        CpuPlayer cy = CpuRoster.ALL.get(2);
        assertFalse(bingo.enterNpc(pip.id(), "Pip"), "no round to join");
        int npcWins = 0;
        int lilahWins = 0;
        int rounds = 60;
        for (int i = 0; i < rounds; i++) {
            Fake lilah = player("Lilah" + i);
            assertTrue(bingo.join(lilah.player));
            assertTrue(bingo.enterNpc(pip.id(), "Pip"));
            assertTrue(bingo.enterNpc(bo.id(), "Bo"));
            assertFalse(bingo.enterNpc(cy.id(), "Cy"), "two NPCs at most");
            bingo.seedNextRound(1_000L + i);
            bingo.startNow(lilah.player);
            callToTheWin();
            for (KidBingo.Entry e : bingo.entries()) {
                if (e.npc()) {
                    npcWins += e.won() ? 1 : 0;
                    assertEquals(0, host.balanceOf(e.id()), "an NPC is never paid");
                    // easy: an NPC's card lights only some of the animals called on it
                } else {
                    lilahWins += e.won() ? 1 : 0;
                }
            }
            pass(KidBingo.RESULT_MS);
        }
        assertTrue(lilahWins > npcWins, "easy NPCs: her card gets there first more often (" + lilahWins + " to "
                + npcWins + ")");
        assertTrue(lilahWins >= rounds / 2, "most rounds: " + lilahWins);
        assertEquals(50, KidBingo.catchPercent(com.dierks.homecraft.games.cpu.CpuLevel.EASY));
        assertEquals(0, count("SELECT COUNT(*) FROM game_rounds"));
    }

    // ---- the Arcade Room -----------------------------------------------------------------------------------

    @Test
    void itMayStandInTheArcadeRoomAsACabinetAndOpensFromOne() {
        Fake lilah = player("Lilah");
        assertNull(KidSet.refusal("kid_bingo", id -> id.equals("kid_bingo") ? GameKind.TRIAL : null),
                "a cabinet of its own, though it isn't a cabinet game by kind");
        assertTrue(KidSet.cabinet("kid_bingo", GameKind.TRIAL));
        assertFalse(KidSet.cabinet("kid_races", GameKind.TRIAL), "only the kid screens");
        assertFalse(KidSet.cabinet("bingo", GameKind.CHANCE), "a game of chance never");
        assertFalse(KidSet.CABINET_GAMES.contains("kid_bingo"), "it takes no spare place by itself");
        assertTrue(games.openKid(lilah.player, "kid_bingo"), "its cabinet's tap: straight in");
        assertNotNull(bingo.entry(lilah.id));
        assertTrue(PlayGate.fromClubhouse(bingo), "played from the Clubhouse");
    }
}
