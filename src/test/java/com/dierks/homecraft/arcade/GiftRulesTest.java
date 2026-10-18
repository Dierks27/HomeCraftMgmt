package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.PrizeService.GiftFacts;
import com.dierks.homecraft.integration.EconomySandbox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A present's checks (HALLOWEEN-SPEC D8): every one before any token moves, and the first failure in
 * D8's order is the message. {@code giftRefusal} is the gate that runs before {@code spend}.
 */
class GiftRulesTest {

    private static final String NAME = "Friend";
    private static final String ITEM = "Pumpkin Cap 2026";

    /** A cap present that passes every check. */
    private static Facts ok() {
        return new Facts();
    }

    /** The facts as a mutable bag, so each case changes only what it is about. */
    private static final class Facts {
        boolean giverMaySpend = true;
        boolean giftingOn = true;
        boolean giftableCollector = true;
        boolean onSale = true;
        boolean receiverOnline = true;
        boolean giverCanSee = true;
        boolean self = false;
        boolean receiverEconomyWorld = true;
        boolean receiverGamesWorld = false;
        boolean receiverInGame = false;
        boolean receiverHasIt = false;
        boolean cap = true;
        boolean headEmpty = true;
        boolean bagHasRoom = true;
        int balance = 300;
        int cost = 250;

        String refusal() {
            return PrizeService.giftRefusal(new GiftFacts(giverMaySpend, giftingOn, giftableCollector, onSale,
                    receiverOnline, giverCanSee, self, receiverEconomyWorld, receiverGamesWorld, receiverInGame,
                    receiverHasIt, cap, headEmpty, bagHasRoom, balance, cost, NAME, ITEM));
        }
    }

    @Test
    void aPresentThatPassesEveryCheck() {
        assertNull(ok().refusal());
    }

    @Test
    void eachCheckAlone() {
        Facts f = ok();
        f.giverMaySpend = false;
        assertEquals(EconomySandbox.reason(), f.refusal());

        f = ok();
        f.giftingOn = false;
        assertEquals("That isn't for sale right now.", f.refusal(), "gifting off");
        f = ok();
        f.giftableCollector = false;
        assertEquals("That isn't for sale right now.", f.refusal(), "not a giftable collector");
        f = ok();
        f.onSale = false;
        assertEquals("That isn't for sale right now.", f.refusal(), "out of season");

        f = ok();
        f.receiverOnline = false;
        assertEquals("Friend isn't online.", f.refusal());
        f = ok();
        f.giverCanSee = false;
        assertEquals("Friend isn't online.", f.refusal(), "a vanished receiver is not online to the giver");
        f = ok();
        f.self = true;
        assertEquals("That's you! Tap the Pumpkin Cap 2026 itself to buy one for yourself.", f.refusal());

        String elsewhere = "Friend can't get presents right now (they're in a game or another world).";
        f = ok();
        f.receiverEconomyWorld = false;
        assertEquals(elsewhere, f.refusal());
        f = ok();
        f.receiverGamesWorld = true; // an empty economy list allows every world, the Games world too
        assertEquals(elsewhere, f.refusal());
        f = ok();
        f.receiverInGame = true; // in an economy world, but a game restores the whole inventory
        assertEquals(elsewhere, f.refusal());

        f = ok();
        f.receiverHasIt = true;
        assertEquals("Friend already has the Pumpkin Cap 2026!", f.refusal());

        f = ok();
        f.headEmpty = false;
        f.bagHasRoom = false;
        assertEquals("Friend's bag is full.", f.refusal());

        f = ok();
        f.balance = 249;
        assertEquals("You need 1 more tokens.", f.refusal(), "balance = cost - 1 is refused before spend");
        f = ok();
        f.balance = 250;
        assertNull(f.refusal(), "exactly the price is enough");
    }

    @Test
    void theFirstFailureInOrderIsTheMessage() {
        Facts f = ok();
        f.giverMaySpend = false;
        f.receiverHasIt = true;
        assertEquals(EconomySandbox.reason(), f.refusal(), "no sandbox + owned: the sandbox's message");

        f = ok();
        f.giftingOn = false;
        f.receiverOnline = false;
        f.balance = 0;
        assertEquals("That isn't for sale right now.", f.refusal());

        f = ok();
        f.giverCanSee = false;
        f.self = true;
        assertEquals("Friend isn't online.", f.refusal());

        f = ok();
        f.receiverInGame = true;
        f.receiverHasIt = true;
        assertEquals("Friend can't get presents right now (they're in a game or another world).", f.refusal());

        f = ok();
        f.receiverHasIt = true;
        f.headEmpty = false;
        f.bagHasRoom = false;
        f.balance = 0;
        assertEquals("Friend already has the Pumpkin Cap 2026!", f.refusal());

        f = ok();
        f.headEmpty = false;
        f.bagHasRoom = false;
        f.balance = 0;
        assertEquals("Friend's bag is full.", f.refusal(), "room before money: nothing is charged either way");
    }

    @Test
    void roomForACap() {
        Facts f = ok();
        f.headEmpty = true;
        f.bagHasRoom = false;
        assertNull(f.refusal(), "an empty head with a full bag: the cap goes on");

        f = ok();
        f.headEmpty = false;
        f.bagHasRoom = true;
        assertNull(f.refusal(), "a worn helmet (Binding or not) and one free slot: the swap or the bag");

        f = ok();
        f.headEmpty = false;
        f.bagHasRoom = false;
        assertEquals("Friend's bag is full.", f.refusal());

        f = ok();
        f.cap = false;
        f.headEmpty = false;
        f.bagHasRoom = false;
        assertNull(f.refusal(), "a trail needs no room");
    }

    @Test
    void anUnknownReceiverStillGetsASentence() {
        assertEquals("That player isn't online.", PrizeService.giftRefusal(new GiftFacts(true, true, true, true,
                false, false, false, false, false, false, false, true, true, true, 300, 250, null, ITEM)));
    }
}
