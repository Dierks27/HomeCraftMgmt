package com.dierks.homecraft.arcade;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A placed prize head, a hat or a trophy (0.37.3, spec §4.15 (4), D17, Q7): only whoever placed it, or an admin, may
 * break it, and the prize goes into the breaker's bag, or their Mailbox when the bag is full, never the ground. A
 * head placed before 0.37.3 remembers no placer and breaks for anyone, as it always did. A HomeCraft block placed as a
 * head is never a placed prize, even when it is a token prize (0.38.1: a skinned Firework Launcher).
 */
class PlacedPrizeTest {

    @Test
    void aNonPlacersBreakIsRefused() {
        assertTrue(PlacedPrizeRules.breakRefused(false, false, true), "someone else, not an admin: refused");
        assertFalse(PlacedPrizeRules.breakRefused(true, false, true), "the placer may");
        assertFalse(PlacedPrizeRules.breakRefused(false, true, true), "an admin may");
        assertFalse(PlacedPrizeRules.breakRefused(true, true, true));
    }

    @Test
    void aTrophyPlacedBefore0373BreaksForAnyone() {
        assertFalse(PlacedPrizeRules.breakRefused(false, false, false), "no placer remembered: anyone, as before");
        assertFalse(PlacedPrizeRules.breakRefused(false, true, false));
    }

    /** One breaker's bag and Mailbox: what each was handed, in order. */
    private static final class Breaker implements PlacedPrizeRules.Hand<String> {
        final List<String> bag = new ArrayList<>();
        final List<String> mailbox = new ArrayList<>();
        final List<String> ground = new ArrayList<>(); // nothing may ever land here
        final boolean bagFull;
        final boolean mailboxFails;
        final String prize;

        Breaker(String prize, boolean bagFull, boolean mailboxFails) {
            this.prize = prize;
            this.bagFull = bagFull;
            this.mailboxFails = mailboxFails;
        }

        @Override
        public String toBag() {
            if (bagFull) {
                return prize;
            }
            bag.add(prize);
            return null;
        }

        @Override
        public void toMailbox(String rest) throws SQLException {
            if (mailboxFails) {
                throw new SQLException("disk full");
            }
            mailbox.add(rest);
        }
    }

    @Test
    void thePlacersAndAnAdminsBreakGiveThePrizeToTheBag() {
        for (String who : List.of("the placer", "an admin")) {
            Breaker b = new Breaker("Arcade Trophy #3", false, false);
            assertEquals(PlacedPrizeRules.Goes.BAG, PlacedPrizeRules.handOver(b), who);
            assertEquals(List.of("Arcade Trophy #3"), b.bag, who + ": the same prize, in the bag");
            assertEquals(List.of(), b.mailbox, who + ": the Mailbox isn't touched");
            assertEquals(List.of(), b.ground);
        }
    }

    @Test
    void aFullBagSendsItToTheMailboxNeverTheGround() {
        Breaker b = new Breaker("Arcade Trophy #3", true, false);
        assertEquals(PlacedPrizeRules.Goes.MAILBOX, PlacedPrizeRules.handOver(b));
        assertEquals(List.of(), b.bag);
        assertEquals(List.of("Arcade Trophy #3"), b.mailbox, "it waits in the Mailbox");
        assertEquals(List.of(), b.ground, "never the ground");

        Breaker failing = new Breaker("Arcade Trophy #3", true, true);
        assertEquals(PlacedPrizeRules.Goes.STAYS, PlacedPrizeRules.handOver(failing),
                "the Mailbox couldn't be written: the break is called off and the trophy stays standing");
        assertEquals(List.of(), failing.bag);
        assertEquals(List.of(), failing.mailbox);
        assertEquals(List.of(), failing.ground);
    }

    /**
     * Plan risk R3 (0.38.1): a Firework Launcher is a token prize, and with {@code skins.firework_launcher} set it is
     * placed as a head. Stored as a placed prize too, its break would put one launcher in the bag (the placed-prize
     * hand-over) and drop another (the block's own drop). A HomeCraft block is the block system's, as
     * {@code MiniHeadListener} leaves it; every other prize head is remembered as before.
     */
    @Test
    void aHomeCraftBlockItemIsNeverStoredAsAPlacedPrize() throws Exception {
        assertTrue(PrizeItemListener.storesAsPlacedPrize(true, true, false), "a hat or a trophy placed as a head");
        assertFalse(PrizeItemListener.storesAsPlacedPrize(true, true, true), "a skinned Firework Launcher: never");
        assertFalse(PrizeItemListener.storesAsPlacedPrize(true, false, false), "a prize that isn't a head");
        assertFalse(PrizeItemListener.storesAsPlacedPrize(false, true, false), "a head that isn't a prize (a Mini)");
        assertFalse(PrizeItemListener.storesAsPlacedPrize(false, true, true), "a PC: the block system's");
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dierks/homecraft/arcade/PrizeItemListener.java")).replaceAll("\\s+", " ");
        int fast = src.indexOf("if (!TokenPrizes.is(item) || !(event.getBlockPlaced().getState() instanceof Skull"
                + " skull)) { return; }");
        int ask = src.indexOf("if (!storesAsPlacedPrize(true, true, blocks != null && blocks.itemType(item) != null))"
                + " { return;");
        assertTrue(fast > 0 && ask > fast, "onPlace asks it once the place is a prize head (every other place costs"
                + " one tag read, as before)");
        assertTrue(ask < src.indexOf("pdc.set(Keys.PRIZE_ITEM", ask), "and before anything is written to the skull");
    }

    @Test
    void theBreakIsDecidedAtHighAndTheHandOverWaitsUntilItIsFinal() throws Exception {
        EventHandler check = PrizeItemListener.class.getMethod("onBreakCheck", BlockBreakEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler give = PrizeItemListener.class.getMethod("onBreak", BlockBreakEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler place = PrizeItemListener.class.getMethod("onPlace", BlockPlaceEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(check);
        assertNotNull(give);
        assertNotNull(place);
        assertEquals(EventPriority.HIGH, check.priority());
        assertTrue(check.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, give.priority(), "a cancelled break would otherwise copy the prize");
        assertTrue(give.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, place.priority(), "only a placement that stands is remembered");
        assertTrue(place.ignoreCancelled());
        assertEquals("PRIZE", PrizeItemListener.MAILBOX_SOURCE);
    }
}
