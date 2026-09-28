package com.dierks.homecraft.games.chance.coinflip;

import com.dierks.homecraft.games.Refusal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coin Flip's checks (spec §5.7, R1.8): who can be invited, and what is checked AGAIN — for both
 * players — at the moment the invited player confirms. An invite is only a question: a pause, a
 * limit or an empty wallet that arrived in between, a player who left or walked off, an invite
 * already used or run out, or a daily or pair limit reached all call the flip off before a single
 * token moves.
 */
class CoinFlipRulesTest {

    private static final CoinFlipSettings S = CoinFlipSettings.defaults();
    private static final UUID A = new UUID(1, 1);
    private static final UUID B = new UUID(2, 2);
    private static final Refusal PAUSED = Refusal.paused("Thu 12 AM");

    private static CoinFlipRules.Side at(UUID id, String world, double x, Refusal gate, int plays) {
        return new CoinFlipRules.Side(id, true, world, x, 64, 0, gate, plays);
    }

    private static CoinFlipRules.Side a() {
        return at(A, "world", 0, null, 0);
    }

    private static CoinFlipRules.Side b() {
        return at(B, "world", 10, null, 0);
    }

    // ---- at the confirm ---------------------------------------------------------------------

    @Test
    void aFlipGoesAheadOnlyWhenEverythingStillHolds() {
        assertNull(CoinFlipRules.confirmable(S, 10, true, false, a(), b(), 0), "both fine: flip");
        assertNull(CoinFlipRules.confirmable(S, 10, true, false, at(A, "world", 0, null, 4), at(B, "world", 32, null, 4), 1),
                "one flip left each, one for the pair, exactly at the distance: still fine");
    }

    @Test
    void aPauseTakenBetweenTheInviteAndTheConfirmCallsItOffForEitherPlayer() {
        String inviterPaused = CoinFlipRules.confirmable(S, 10, true, false, at(A, "world", 0, PAUSED, 0), b(), 0);
        assertNotNull(inviterPaused, "the inviter paused after asking: called off");
        assertTrue(inviterPaused.contains("taking a break"), inviterPaused);
        String invitedPaused = CoinFlipRules.confirmable(S, 10, true, false, a(), at(B, "world", 10, PAUSED, 0), 0);
        assertNotNull(invitedPaused, "the invited player paused in between: called off");
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, a(),
                at(B, "world", 10, Refusal.needMore(4), 0), 0), "spent their tokens meanwhile: called off");
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, at(A, "world", 0, Refusal.personalLimit(25), 0),
                b(), 0), "the inviter reached their day's limit elsewhere: called off");
    }

    @Test
    void theInviteMustBeUnusedUnexpiredAndTheStakeStillOffered() {
        assertEquals("the invite was already used", CoinFlipRules.confirmable(S, 10, false, false, a(), b(), 0));
        assertEquals("the invite ran out", CoinFlipRules.confirmable(S, 10, true, true, a(), b(), 0));
        assertNotNull(CoinFlipRules.confirmable(S, 7, true, false, a(), b(), 0), "a stake Coin Flip doesn't offer");
    }

    @Test
    void bothMustBeOnlineInTheSameWorldAndCloseEnough() {
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, CoinFlipRules.Side.offline(A), b(), 0),
                "the inviter left");
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, a(), CoinFlipRules.Side.offline(B), 0),
                "the invited player left");
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, a(), at(B, "world_nether", 0, null, 0), 0),
                "another world");
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, a(), at(B, "world", 32.5, null, 0), 0),
                "just past max_distance 32");
        CoinFlipSettings anywhere = new CoinFlipSettings(true, S.stakes(), S.dailyLimit(), S.pairDailyLimit(), S.rtp(),
                0, S.inviteSeconds(), S.odds());
        assertNull(CoinFlipRules.confirmable(anywhere, 10, true, false, a(), at(B, "world", 5000, null, 0), 0),
                "max_distance 0 is anywhere in the same world");
        assertFalse(CoinFlipRules.inRange(0, a(), at(B, "world_nether", 0, null, 0)), "but never another world");
    }

    @Test
    void theDailyAndPairLimitsCallItOff() {
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, at(A, "world", 0, null, 5), b(), 0),
                "the inviter has had their 5 flips today");
        assertNotNull(CoinFlipRules.confirmable(S, 10, true, false, a(), at(B, "world", 1, null, 5), 0),
                "the invited player has had theirs");
        assertEquals("the pair has no flips left today", CoinFlipRules.confirmable(S, 10, true, false, a(), b(), 2),
                "two flips between the same two players today, whoever asked");
        assertNull(CoinFlipRules.confirmable(S, 10, true, false, a(), b(), 1), "one left for the pair");
    }

    // ---- who can be invited -----------------------------------------------------------------

    @Test
    void onlyPlayersWhoCouldTakeTheFlipNowCanBeInvited() {
        assertNull(CoinFlipRules.invitable(S, 10, a(), b(), true, false, false, 0), "close by, invites on, free: yes");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), at(A, "world", 0, null, 0), true, false, false, 0),
                "never yourself");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), b(), false, false, false, 0),
                "Coin Flip invites are off until the player turns them on");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), b(), true, true, false, 0), "already has one waiting");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), b(), true, false, true, 0), "asked in the last 30 seconds");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), at(B, "world", 10, PAUSED, 0), true, false, false, 0),
                "a paused player never appears");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), at(B, "world", 10, Refusal.NO_CHANCE, 0), true, false,
                false, 0), "nor one without permission");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), at(B, "world", 10, null, 5), true, false, false, 0),
                "nor one with no flips left today");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), b(), true, false, false, 2), "nor a pair out of flips");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), at(B, "world", 100, null, 0), true, false, false, 0),
                "nor someone too far away");
        assertNotNull(CoinFlipRules.invitable(S, 10, a(), CoinFlipRules.Side.offline(B), true, false, false, 0),
                "nor someone offline");
    }

    @Test
    void aPairIsThePairWhoeverAsked() {
        assertEquals(CoinFlipRules.pairKey(A, B), CoinFlipRules.pairKey(B, A), "one cooldown for both directions");
        String data = CoinFlipRules.data("ab12cd34", 10, 18, false);
        assertEquals("v=1;pair=ab12cd34;stake=10;pays=18;winner=invited", data,
                "both rows share the pair id and say who won");
        assertEquals(30_000, CoinFlipRules.PAIR_COOLDOWN_MS);
    }
}
