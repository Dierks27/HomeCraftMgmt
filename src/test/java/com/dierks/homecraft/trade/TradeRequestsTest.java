package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeRequests.Request;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trade requests (SHOP-V2-SPEC §4.2, §4.8, red team D-m10, O12), as the Games' invites are tested: one waiting for
 * each player; a double accept runs once; expiry; the 30 s pair cooldown after a no, a timeout and the asker taking
 * it back, but not after asking; a quit or a world change drops a request with no cooldown; three a minute; and the
 * Bedrock "Trade?" screen opens only with no container open, an empty cursor and no game.
 */
class TradeRequestsTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID DAUGHTER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final long T = 1_000_000L;

    @Test
    void oneRequestWaitsAndADoubleAcceptRunsOnce() {
        TradeRequests r = new TradeRequests();
        Request sent = r.send(OWNER, "Owner", HELPER, "Helper", T, 30);
        assertEquals(sent, r.incoming(HELPER, T + 1));
        assertEquals(sent, r.outgoing(OWNER, T + 1));
        assertEquals(30, sent.secondsLeft(T));
        assertEquals(sent, r.accept(HELPER, null, T + 5), "the first accept takes it");
        assertNull(r.accept(HELPER, null, T + 5), "[X] a second accept (a double tap) finds nothing");
        assertNull(r.accept(HELPER, OWNER, T + 6), "nor a command or a crouch-click after it");
        assertNull(r.incoming(HELPER, T + 6));
        assertFalse(r.coolingDown(OWNER, HELPER, T + 6), "a yes is no reason to wait");
    }

    @Test
    void acceptingByNameTakesOnlyThatOne() {
        TradeRequests r = new TradeRequests();
        r.send(OWNER, "Owner", HELPER, "Helper", T, 30);
        assertNull(r.accept(HELPER, DAUGHTER, T + 1), "not from her");
        assertNotNull(r.accept(HELPER, OWNER, T + 1));
    }

    @Test
    void aRequestRunsOutAndThePairCoolsDown() {
        TradeRequests r = new TradeRequests();
        r.send(OWNER, "Owner", HELPER, "Helper", T, 30);
        assertNull(r.incoming(HELPER, T + 30_000), "gone once its time is up");
        assertNull(r.accept(HELPER, null, T + 30_000), "and can't be accepted late");
        List<Request> lapsed = r.expire(T + 30_000);
        assertEquals(1, lapsed.size());
        assertTrue(r.coolingDown(OWNER, HELPER, T + 30_001));
        assertTrue(r.coolingDown(HELPER, OWNER, T + 59_999), "either way round");
        assertFalse(r.coolingDown(OWNER, HELPER, T + 60_000));
    }

    @Test
    void aNoAndATakeBackCoolDownButAnAskDoesNot() {
        TradeRequests r = new TradeRequests();
        r.send(OWNER, "Owner", HELPER, "Helper", T, 30);
        assertFalse(r.coolingDown(OWNER, HELPER, T + 1), "asking alone starts no cooldown");
        assertNotNull(r.deny(HELPER, null, T + 2));
        assertTrue(r.coolingDown(OWNER, HELPER, T + 3), "after a no");
        TradeRequests s = new TradeRequests();
        s.send(OWNER, "Owner", HELPER, "Helper", T, 30);
        assertNotNull(s.withdraw(OWNER, T + 2));
        assertNull(s.incoming(HELPER, T + 3));
        assertTrue(s.coolingDown(OWNER, HELPER, T + 3), "[X] asking, taking it back and asking again waits");
        assertNull(s.withdraw(OWNER, T + 4), "nothing left to take back");
    }

    @Test
    void aQuitOrAWorldChangeDropsItWithNoCooldown() {
        TradeRequests r = new TradeRequests();
        r.send(OWNER, "Owner", HELPER, "Helper", T, 30);
        r.send(DAUGHTER, "Daughter", OWNER, "Owner", T, 30);
        List<Request> dropped = r.dropAll(OWNER);
        assertEquals(2, dropped.size(), "every request from or to them");
        assertTrue(r.all().isEmpty());
        assertFalse(r.coolingDown(OWNER, HELPER, T + 1));
    }

    @Test
    void pendingWithSomeoneElse() {
        TradeRequests r = new TradeRequests();
        r.send(OWNER, "Owner", HELPER, "Helper", T, 30);
        assertTrue(r.pendingWithOther(OWNER, DAUGHTER, T + 1), "the owner has one out to the helper");
        assertFalse(r.pendingWithOther(OWNER, HELPER, T + 1), "but not with someone else than the helper");
        assertTrue(r.pendingWithOther(HELPER, DAUGHTER, T + 1), "one waits for the helper");
        assertFalse(r.pendingWithOther(DAUGHTER, OWNER, T + 1));
    }

    @Test
    void threeAMinute() {
        TradeRequests r = new TradeRequests();
        for (int i = 0; i < 3; i++) {
            r.send(OWNER, "Owner", UUID.randomUUID(), "Someone", T + i * 1_000, 30);
        }
        assertEquals(3, r.sentLastMinute(OWNER, T + 5_000));
        assertEquals(2, r.sentLastMinute(OWNER, T + 60_000), "the first falls out after a minute");
        assertEquals(0, r.sentLastMinute(HELPER, T));
    }

    @Test
    void theBedrockScreenOpensOnlyWhenItCanDropNothing() {
        assertTrue(TradeRules.askScreenOpens(true, true, false));
        assertFalse(TradeRules.askScreenOpens(false, true, false), "a chest or a crafting table is open");
        assertFalse(TradeRules.askScreenOpens(true, false, false), "she is carrying a stack on her cursor");
        assertFalse(TradeRules.askScreenOpens(true, true, true), "she is in a game");
    }

    /**
     * Red-team finding TW-1: a yes opened the asker's window over whatever they had open, up to 30 s after they
     * asked, so a crafting table's grid or a carried stack could drop when the bag was full. The windows open over
     * nothing but a player's own grid or the "Trade?" screen, with an empty cursor.
     */
    @Test
    void aTradeWindowOpensOnlyWhereItCanDropNothing() {
        assertTrue(TradeRules.windowOpens(true, false, true), "nothing open");
        assertTrue(TradeRules.windowOpens(false, true, true), "the Trade? screen she just said yes on");
        assertFalse(TradeRules.windowOpens(false, false, true), "[X] the asker opened a crafting table meanwhile");
        assertFalse(TradeRules.windowOpens(true, false, false), "[X] a stack carried on the cursor");
        assertFalse(TradeRules.windowOpens(false, true, false), "even on the Trade? screen");
    }

    /**
     * The yes that waits for the asker to close what they have open: the request stays (the windows open the
     * second both are free), a second yes changes nothing, the accept still hands it over once, and a yes that
     * never gets to start runs out at the request's time like any other.
     */
    @Test
    void aYesWaitsUntilBothAreFreeAndStillRunsOnce() {
        TradeRequests r = new TradeRequests();
        Request sent = r.send(DAUGHTER, "Kid", OWNER, "Owner", T, 30);
        assertFalse(sent.yes());
        Request yes = r.sayYes(OWNER, DAUGHTER, T + 5_000);
        assertNotNull(yes);
        assertTrue(yes.yes(), "the yes is on the request");
        assertEquals(sent.id(), yes.id(), "the same request");
        assertTrue(r.incoming(OWNER, T + 6_000).yes(), "still waiting, with the yes");
        assertTrue(r.sayYes(OWNER, null, T + 7_000).yes(), "a second yes changes nothing");
        assertNull(r.sayYes(OWNER, HELPER, T + 7_000), "a yes to someone who didn't ask finds nothing");
        assertNull(r.sayYes(HELPER, null, T + 7_000));
        assertEquals(sent.id(), r.accept(OWNER, DAUGHTER, T + 8_000).id(), "free now: handed over");
        assertNull(r.accept(OWNER, null, T + 8_000), "[X] once");

        Request again = r.send(DAUGHTER, "Kid", OWNER, "Owner", T + 60_000, 30);
        r.sayYes(OWNER, null, T + 61_000);
        List<Request> out = r.expire(T + 90_000);
        assertEquals(1, out.size());
        assertEquals(again.id(), out.get(0).id());
        assertTrue(out.get(0).yes(), "it ran out with its yes on it, so both are told it didn't start");
        assertNull(r.incoming(OWNER, T + 90_000));
    }

    /**
     * Her "Trade?" screen closes on its own once its request has gone (ran out, taken back, called off): only the
     * screen asking about that request, never another screen or another request's.
     */
    @Test
    void theBedrockScreenClosesWithItsRequest() {
        TradeRequests r = new TradeRequests();
        Request sent = r.send(OWNER, "Owner", DAUGHTER, "Kid", T, 30);
        TradeService.AskScreen screen = sent::id;
        assertTrue(TradeService.asksAbout(screen, sent.id()), "the screen for this request closes with it");
        assertFalse(TradeService.asksAbout(screen, sent.id() + 1), "a screen for another request stays");
        assertFalse(TradeService.asksAbout(new Object(), sent.id()), "any other screen (a chest, a menu) stays");
        assertFalse(TradeService.asksAbout(null, sent.id()), "her own inventory: nothing to close");
        assertEquals(List.of(sent), r.expire(T + 30_000), "it runs out at 30 s, and that is when it closes");
    }
}
