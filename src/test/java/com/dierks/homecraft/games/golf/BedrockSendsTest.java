package com.dierks.homecraft.games.golf;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Bedrock player near a golf ball is shown its stand, then sent it afresh for a few passes (0.38.7: a
 * spawn their client lost mid-teleport meant no ball all round); out of range, a new hole or a new stand
 * starts them again.
 */
class BedrockSendsTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0009-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0009-000000000002");

    @Test
    void firstPassShowsThenAFewFreshSendsThenNothing() {
        BedrockSends s = new BedrockSends();
        assertEquals(BedrockSends.Send.SHOW, s.pass(A));
        for (int i = 1; i < BedrockSends.PASSES; i++) {
            assertEquals(BedrockSends.Send.RESEND, s.pass(A), "pass " + (i + 1));
        }
        assertEquals(BedrockSends.Send.NONE, s.pass(A), "sent enough times: left alone");
        assertEquals(BedrockSends.Send.NONE, s.pass(A));
        assertEquals(BedrockSends.Send.SHOW, s.pass(B), "each player counts on their own");
    }

    @Test
    void outOfRangeStartsThemAgainWhenTheyComeBack() {
        BedrockSends s = new BedrockSends();
        for (int i = 0; i <= BedrockSends.PASSES; i++) {
            s.pass(A);
            s.pass(B);
        }
        s.retain(Set.of(B));
        assertEquals(BedrockSends.Send.SHOW, s.pass(A), "came back into range: shown afresh");
        assertEquals(BedrockSends.Send.NONE, s.pass(B), "stayed in range: still done");
    }

    @Test
    void aNewHoleOrStandStartsEveryoneAgain() {
        BedrockSends s = new BedrockSends();
        for (int i = 0; i <= BedrockSends.PASSES; i++) {
            s.pass(A);
        }
        s.clear();
        assertEquals(BedrockSends.Send.SHOW, s.pass(A));
        assertEquals(BedrockSends.Send.RESEND, s.pass(A));
    }
}
