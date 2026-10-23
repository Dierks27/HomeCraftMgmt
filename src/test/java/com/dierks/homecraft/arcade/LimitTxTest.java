package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.storage.DeliveryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A counted row's buy is ONE transaction (SHOP-V2-SPEC D30, §5.9; red team D-m11, D-m12), on a real SQLite: a
 * count that can't be written charges nothing; a LIFETIME 1 ladder trophy can't be bought twice by a double
 * confirm; the Arcade Trophy's price still rises; a trophy that doesn't fit the bag is a READY Mailbox row in the
 * same transaction, never the ground, and a refused charge takes that row back with everything else.
 */
class LimitTxTest {

    private static final String NOT_SAVED = "That couldn't be saved — nothing charged. Tell an admin.";
    private static final Prize BRONZE = ShopTx.row("trophy_bronze", PrizeType.TROPHY, 300,
            ShopTx.limit(LimitPer.LIFETIME, 1), List.of(), OnlyIf.ANY);
    private static final String SERIAL = PrizeService.trophySerialKey("trophy_bronze");

    @Test
    void aCountThatCantBeWrittenChargesNothingAndGivesNothing() throws Exception {
        try (ShopTx tx = new ShopTx(1000)) {
            assertEquals(NOT_SAVED, tx.confirm(BRONZE, "life", true, SERIAL, true, null));
            assertEquals(1000, tx.balance(), "nothing charged");
            assertEquals(List.of(), tx.ledger(), "and no ledger line");
            assertEquals(0, tx.bought(BRONZE, "life"), "nothing counted");
            assertEquals(0, tx.prizes.state(SERIAL, 0), "and no number used");
            assertEquals(1, tx.notSaved.size(), "the error went to the log");
        }
    }

    @Test
    void aLadderTrophyCantBeBoughtTwiceByADoubleConfirm() throws Exception {
        try (ShopTx tx = new ShopTx(1000)) {
            assertNull(tx.confirm(BRONZE, "life", false, SERIAL, true, null), "the first confirm buys it");
            assertEquals("used up", tx.confirm(BRONZE, "life", false, SERIAL, true, null),
                    "the second finds the limit used, inside the lock");
            assertEquals(700, tx.balance(), "charged once");
            assertEquals(List.of(-300), tx.ledger());
            assertEquals(1, tx.prizes.state(SERIAL, 0), "one number taken");
        }
    }

    @Test
    void theArcadeTrophysPriceStillRisesWhenACountFailsOnTheWay() throws Exception {
        Prize arcade = ShopTx.row("arcade_trophy", PrizeType.TROPHY, 750, ShopTx.limit(LimitPer.LIFETIME, 99),
                List.of(750, 1000, 1250, 1500), OnlyIf.ANY);
        try (ShopTx tx = new ShopTx(5000)) {
            assertNull(tx.confirm(arcade, "life", false, "trophy_serial", true, null));
            assertEquals(1000, tx.price(arcade), "the next one costs 1,000");
            assertEquals(NOT_SAVED, tx.confirm(arcade, "life", true, "trophy_serial", true, null));
            assertEquals(1000, tx.price(arcade), "a failed buy moves nothing");
            assertNull(tx.confirm(arcade, "life", false, "trophy_serial", true, null));
            assertEquals(1250, tx.price(arcade));
            assertEquals(List.of(-750, -1000), tx.ledger());
        }
    }

    @Test
    void aTrophyWithAFullBagIsAMailboxRowInTheSameTransaction() throws Exception {
        try (ShopTx tx = new ShopTx(1000)) {
            assertNull(tx.confirm(BRONZE, "life", false, SERIAL, false, null));
            List<DeliveryDao.Delivery> mail = tx.deliveries.activeFor(tx.buyer);
            assertEquals(1, mail.size(), "it is in the Mailbox");
            assertEquals(DeliveryDao.READY, mail.get(0).status(), "ready to collect now");
            assertEquals("Bronze Trophy #1", mail.get(0).label());
            assertEquals("PRIZE", mail.get(0).source());
            assertEquals(700, tx.balance());
        }
    }

    @Test
    void aRefusedChargeTakesTheMailboxRowBackWithEverythingElse() throws Exception {
        try (ShopTx tx = new ShopTx(100)) {
            assertEquals("broke", tx.confirm(BRONZE, "life", false, SERIAL, false, null));
            assertEquals(List.of(), tx.deliveries.activeFor(tx.buyer), "no Mailbox row");
            assertEquals(0, tx.bought(BRONZE, "life"), "no count");
            assertEquals(0, tx.prizes.state(SERIAL, 0), "no number");
            assertEquals(100, tx.balance());
        }
    }

    @Test
    void whichBuysAreOneTransaction() {
        assertTrue(PrizeService.asOne(BRONZE, true), "a trophy");
        assertTrue(PrizeService.asOne(BRONZE, false), "a crate's trophy too: its number and the Mailbox");
        assertTrue(PrizeService.asOne(ShopTx.row("speed", PrizeType.BOOST, 8, ShopTx.limit(LimitPer.DAY, 5),
                List.of(), OnlyIf.ANY), true), "a boost with a daily limit");
        assertTrue(PrizeService.asOne(ShopTx.row("make_it_day", PrizeType.COMMAND, 20, null, List.of(), OnlyIf.NIGHT),
                true), "a command");
        assertTrue(PrizeService.asOne(ShopTx.row("party_time", PrizeType.PARTY, 300, null, List.of(), OnlyIf.ANY),
                true), "a party");
        assertTrue(PrizeService.asOne(ShopTx.row("home", PrizeType.HOME_SLOT, 400, null, List.of(400, 600),
                OnlyIf.ANY), true), "a price list");
        Prize trail = ShopTx.row("trail_hearts", PrizeType.TRAIL, 100, null, List.of(), OnlyIf.ANY);
        assertFalse(PrizeService.asOne(trail, true), "an unlimited trail is charged and granted as before");
        assertFalse(PrizeService.asOne(ShopTx.row("speed", PrizeType.BOOST, 8, ShopTx.limit(LimitPer.DAY, 5),
                List.of(), OnlyIf.ANY), false), "a crate's boost is never counted");
    }

    /**
     * Red-team finding R1: the free path ({@code /hcm give prize}, charge off) lost the command, party and extra-home
     * hand-overs when they moved into {@code deliverAsOne}, so a give of a command row handed a null item on and
     * threw. Every row whose hand-over lives only there goes there, free or bought.
     */
    @Test
    void aFreeCommandPartyOrHomeIsHandedOverWhereABoughtOneIs() {
        Prize hat = ShopTx.row("hat_command", PrizeType.COMMAND, 300, ShopTx.limit(LimitPer.LIFETIME, 1), List.of(),
                OnlyIf.ANY);
        Prize party = ShopTx.row("party_time", PrizeType.PARTY, 300, ShopTx.limit(LimitPer.DAY, 1), List.of(),
                OnlyIf.ANY);
        Prize home = ShopTx.row("home_slot", PrizeType.HOME_SLOT, 400, ShopTx.limit(LimitPer.LIFETIME, 2),
                List.of(400, 600), OnlyIf.ANY);
        for (Prize p : List.of(hat, party, home)) {
            assertTrue(PrizeService.asOne(p, false), p.id() + " given free");
            assertTrue(PrizeService.asOne(p, true), p.id() + " bought");
        }
        for (PrizeType t : PrizeType.values()) {
            assertEquals(t == PrizeType.COMMAND || t == PrizeType.PARTY || t == PrizeType.HOME_SLOT,
                    PrizeService.handedOverAsOne(t), t + ": the plain path hands over an item or a trail only");
        }
    }
}
