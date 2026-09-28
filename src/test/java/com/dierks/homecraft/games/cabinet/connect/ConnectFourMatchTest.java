package com.dierks.homecraft.games.cabinet.connect;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One Connect Four game's seats, turns and outcomes, and the friend-game bookkeeping, without a
 * server.
 *
 * <p>Pinned here: red (the player, or whoever invited) moves first and nobody moves out of turn;
 * the Arcade moves only on its turn; leaving ends the game for both sides with the right outcome
 * each; only a win against the Arcade on normal or hard earns the day's reward, only a hard win
 * counts on the {@code hard} board, and a friend game earns nothing; and {@link FriendGames}
 * never offers someone busy, already invited, not taking invites or not allowed, lets an inviter
 * have one invite out, re-checks at the start, and ends a game for both players at once.
 */
class ConnectFourMatchTest {

    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAT = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void redMovesFirstAndNobodyMovesOutOfTurn() {
        ConnectFourMatch m = ConnectFourMatch.friends(ANNA, BEN);
        assertTrue(m.yourTurn(ANNA), "whoever invited plays red and starts");
        assertEquals(-1, m.play(BEN, 3), "yellow can't move first");
        assertEquals(-1, m.play(CAT, 3), "a stranger can't move at all");
        assertEquals(0, m.play(ANNA, 3), "red drops into the bottom of column 3");
        assertEquals(-1, m.play(ANNA, 3), "red can't move twice");
        assertEquals(1, m.play(BEN, 3), "yellow lands on top");
        assertEquals(2, m.version(), "each move changes the version the other screen watches");
        assertEquals(ANNA, m.other(BEN), "each player's opponent");
        assertNull(m.other(CAT), "a stranger has none");
    }

    @Test
    void theArcadeMovesOnlyOnItsTurn() {
        ConnectFourMatch m = ConnectFourMatch.vsArcade(ANNA, ConnectFourAI.Level.NORMAL, 1);
        assertTrue(m.vsArcade(), "no second player");
        assertEquals(-1, m.arcadeMove(), "the player moves first");
        m.play(ANNA, 3);
        assertTrue(m.arcadeToMove(), "then it's the Arcade's move");
        assertEquals(-1, m.play(ANNA, 2), "and the player waits");
        int col = m.arcadeMove();
        assertTrue(col >= 0 && col < 7, "the Arcade played a column");
        assertEquals(2, m.board().moves(), "one move each");
        assertTrue(m.yourTurn(ANNA), "back to the player");
    }

    @Test
    void leavingEndsTheGameForBoth() {
        ConnectFourMatch m = ConnectFourMatch.friends(ANNA, BEN);
        m.play(ANNA, 0);
        assertTrue(m.leave(BEN), "yellow leaves mid-game");
        assertTrue(m.over(), "the game is over");
        assertEquals(ConnectFourMatch.Outcome.LEFT, m.outcome(BEN), "the one who left");
        assertEquals(ConnectFourMatch.Outcome.OTHER_LEFT, m.outcome(ANNA), "the one left behind");
        assertEquals(-1, m.play(ANNA, 1), "nobody moves after that");
        assertFalse(m.leave(ANNA), "leaving a finished game changes nothing");
    }

    @Test
    void onlyAWinOnNormalOrHardEarnsTheDay() {
        assertTrue(won(ConnectFourAI.Level.NORMAL).earnsDaily(ANNA), "a normal win earns the day's reward");
        assertTrue(won(ConnectFourAI.Level.HARD).earnsDaily(ANNA), "so does a hard win");
        assertFalse(won(ConnectFourAI.Level.EASY).earnsDaily(ANNA), "an easy win doesn't");
        assertTrue(won(ConnectFourAI.Level.HARD).hardWin(ANNA), "a hard win counts on the hard board");
        assertFalse(won(ConnectFourAI.Level.NORMAL).hardWin(ANNA), "a normal win doesn't");
        ConnectFourMatch friends = ConnectFourMatch.friends(ANNA, BEN);
        for (int i = 0; i < 3; i++) {
            friends.play(ANNA, 0);
            friends.play(BEN, 1);
        }
        friends.play(ANNA, 0);
        assertEquals(ConnectFourMatch.Outcome.WON, friends.outcome(ANNA), "Anna won the friend game");
        assertEquals(ConnectFourMatch.Outcome.LOST, friends.outcome(BEN), "and Ben lost it");
        assertFalse(friends.earnsDaily(ANNA), "friend games pay nothing, so they can't be farmed");
        assertFalse(friends.hardWin(ANNA), "and don't count on the hard board");
    }

    @Test
    void theDailyWinIsTheOnlyReward() {
        ConnectFourSettings s = ConnectFourSettings.defaults();
        assertEquals(0, s.milestoneReward(), "Connect Four has no milestones (spec §10b)");
        assertTrue(s.milestonesFor(ConnectFour.BOARD).isEmpty(), "not even on the hard board");
        assertEquals(1, s.dailyReward(), "the day's first normal-or-hard win pays one token");
        assertEquals(1, s.dailyCap(), "and that's all it pays in a day");
    }

    @Test
    void aFriendGameNeedsTwoPlayers() {
        assertThrows(IllegalArgumentException.class, () -> ConnectFourMatch.friends(ANNA, ANNA),
                "nobody plays themselves");
    }

    @Test
    void friendGamesOfferOnlyPlayersWhoCanBeAsked() {
        FriendGames<String> f = new FriendGames<>();
        assertTrue(f.canInvite(ANNA, BEN, true, false, true), "a free player who takes invites");
        assertFalse(f.canInvite(ANNA, ANNA, true, false, true), "not yourself");
        assertFalse(f.canInvite(ANNA, BEN, false, false, true), "not someone who turned these invites off");
        assertFalse(f.canInvite(ANNA, BEN, true, true, true), "not someone with an invite already waiting");
        assertFalse(f.canInvite(ANNA, BEN, true, false, false), "not someone who can't play here");
        f.invited(ANNA, BEN);
        assertEquals(BEN, f.waitingOn(ANNA), "Anna waits on Ben");
        assertFalse(f.canInvite(ANNA, CAT, true, false, true), "one invite of your own out at a time");
        f.answered(ANNA);
        assertTrue(f.canInvite(ANNA, CAT, true, false, true), "once it's answered, Anna can ask again");
    }

    @Test
    void aFriendGameStartsOnlyIfBothAreStillFree() {
        FriendGames<String> f = new FriendGames<>();
        f.invited(ANNA, BEN);
        assertFalse(f.start(ANNA, BEN, "g1", false), "one of them left before the accept");
        assertNull(f.waitingOn(ANNA), "the answer still clears the wait");
        assertTrue(f.start(ANNA, BEN, "g1", true), "both here and free");
        assertTrue(f.busy(ANNA) && f.busy(BEN), "both are in the game");
        assertEquals("g1", f.of(BEN), "the same game for both");
        assertEquals(ANNA, f.opponent(BEN), "each knows the other");
        assertFalse(f.canInvite(CAT, ANNA, true, false, true), "someone in a game isn't offered");
        assertFalse(f.start(CAT, ANNA, "g2", true), "and can't be pulled into a second one");
        assertEquals(1, f.all().size(), "one game, listed once");
        assertEquals("g1", f.end(BEN), "either player ending it...");
        assertFalse(f.busy(ANNA) || f.busy(BEN), "...frees both");
        assertNull(f.end(ANNA), "ending it twice finds nothing");
    }

    @Test
    void quittingEndsTheGameAndForgetsTheInvite() {
        FriendGames<String> f = new FriendGames<>();
        f.invited(CAT, ANNA);
        f.start(ANNA, BEN, "g1", true);
        assertEquals("g1", f.quit(ANNA), "Anna quits: her game ends");
        assertFalse(f.busy(BEN), "Ben is free again");
        f.quit(CAT);
        assertNull(f.waitingOn(CAT), "a quitting inviter's wait is forgotten");
        f.start(ANNA, BEN, "g2", true);
        f.clear();
        assertTrue(f.all().isEmpty(), "a stopped game forgets everything");
    }

    /**
     * A game the player won against the Arcade at {@code level}: red's four across the bottom,
     * built straight on the board (the Arcade would block a scripted player).
     */
    private static ConnectFourMatch won(ConnectFourAI.Level level) {
        ConnectFourMatch m = ConnectFourMatch.vsArcade(ANNA, level, 0);
        for (int col : new int[] {0, 6, 1, 6, 2, 5, 3}) {
            m.board().play(col);
        }
        return m;
    }
}
