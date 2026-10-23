package com.dierks.homecraft.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write first, then give (0.37.3, spec §4.15 (5), red team D-m21), one case per path that takes something out of
 * storage: a Display Case's Mini taken back ({@code VendingService.reclaim}), a Vending listing taken back
 * ({@code unlistVending}), a Pallet emptied ({@code PalletService.takeBack}) and a Mailbox delivery collected
 * ({@code DeliveryService.collect}). A failing delete or status write gives nothing; a full bag's remainder goes
 * (or stays) in the Mailbox; and a bag that takes less than planned has the rest written back.
 */
class GiveAfterWriteTest {

    /** One path's storage and the player's bag, recording every step in order. */
    private static final class Path implements GiveAfterWrite.Steps {
        final String name;
        final List<String> steps = new ArrayList<>();
        final List<Integer> mailbox = new ArrayList<>();
        int bag;
        boolean rowThere = true;
        boolean writeFails;
        boolean writeBackFails;
        /** How many the bag really refuses of what it is handed (0: it takes what was planned). */
        int bagRefuses;

        Path(String name) {
            this.name = name;
        }

        @Override
        public boolean write(List<Integer> toMailbox) throws SQLException {
            steps.add("write" + toMailbox);
            if (writeFails) {
                throw new SQLException("database is locked");
            }
            if (!rowThere) {
                return false;
            }
            rowThere = false;
            mailbox.addAll(toMailbox);
            return true;
        }

        @Override
        public int give(int amount) {
            steps.add("give " + amount);
            int refused = Math.min(amount, bagRefuses);
            bag += amount - refused;
            return refused;
        }

        @Override
        public void writeBack(List<Integer> amounts) throws SQLException {
            steps.add("writeBack" + amounts);
            if (writeBackFails) {
                throw new SQLException("disk full");
            }
            mailbox.addAll(amounts);
        }

        @Override
        public void lost(int amount, SQLException e) {
            steps.add("lost " + amount);
        }

        @Override
        public void notSaved(SQLException e) {
            steps.add("notSaved");
        }
    }

    private static final String[] MINI_PATHS = {"Vending reclaim (Display Case)", "Vending unlistVending"};

    @Test
    void aFailingDeleteOrStatusWriteGivesNothing() {
        for (Object[] c : new Object[][]{{MINI_PATHS[0], 1, 64, 1}, {MINI_PATHS[1], 1, 64, 1},
                {"Pallet takeBack", 150, 64, 64}, {"DeliveryService.collect", 40, 10, 64}}) {
            Path p = new Path((String) c[0]);
            p.writeFails = true;
            GiveAfterWrite.Outcome out = GiveAfterWrite.run((int) c[1], (int) c[2], (int) c[3], p);
            assertEquals(GiveAfterWrite.Status.NOT_SAVED, out.status(), p.name);
            assertEquals(0, p.bag, p.name + ": nothing given");
            assertEquals(List.of(), p.mailbox, p.name + ": nothing mailed");
            assertEquals(List.of("write" + GiveAfterWrite.stacks((int) c[1] - Math.min((int) c[1], (int) c[2]),
                    (int) c[3]), "notSaved"), p.steps, p.name + ": the write was tried and said, nothing after it");
            assertTrue(p.rowThere, p.name + ": the row is still there, so nothing is lost or copied");
        }
    }

    @Test
    void theRowIsWrittenBeforeAnythingIsGiven() {
        Path p = new Path(MINI_PATHS[0]);
        GiveAfterWrite.Outcome out = GiveAfterWrite.run(1, 64, 64, p);
        assertEquals(List.of("write[]", "give 1"), p.steps, "delete first, then the bag");
        assertEquals(new GiveAfterWrite.Outcome(GiveAfterWrite.Status.DONE, 1, 0, 0), out);
        assertEquals(1, p.bag);

        Path gone = new Path(MINI_PATHS[1]);
        gone.rowThere = false;
        assertEquals(GiveAfterWrite.Status.GONE, GiveAfterWrite.run(1, 64, 64, gone).status(),
                "already taken (a double click): nothing given");
        assertEquals(0, gone.bag);
    }

    @Test
    void aFullBagsRemainderGoesToTheMailbox() {
        for (String name : MINI_PATHS) {
            Path p = new Path(name);
            GiveAfterWrite.Outcome out = GiveAfterWrite.run(1, 0, 64, p);
            assertEquals(List.of("write[1]"), p.steps, name + ": the Mini goes to the Mailbox with the delete");
            assertEquals(List.of(1), p.mailbox);
            assertEquals(new GiveAfterWrite.Outcome(GiveAfterWrite.Status.DONE, 0, 1, 0), out);
        }

        Path pallet = new Path("Pallet takeBack");
        GiveAfterWrite.Outcome out = GiveAfterWrite.run(150, 70, 64, pallet);
        assertEquals(List.of("write[64, 16]", "give 70"), pallet.steps,
                "what won't fit is in the Mailbox (one row per stack) before anything goes into the bag");
        assertEquals(70, pallet.bag);
        assertEquals(List.of(64, 16), pallet.mailbox);
        assertEquals(new GiveAfterWrite.Outcome(GiveAfterWrite.Status.DONE, 70, 80, 0), out);

        Path collect = new Path("DeliveryService.collect");
        out = GiveAfterWrite.run(40, 10, 64, collect);
        assertEquals(List.of("write[30]", "give 10"), collect.steps, "the row is rewritten to the 30 that stay");
        assertEquals(10, collect.bag);
        assertEquals(new GiveAfterWrite.Outcome(GiveAfterWrite.Status.DONE, 10, 30, 0), out);
    }

    @Test
    void aBagThatTakesLessThanPlannedHasTheRestWrittenBack() {
        for (Object[] c : new Object[][]{{MINI_PATHS[0], 1, 1}, {"Pallet takeBack", 128, 5},
                {"DeliveryService.collect", 40, 3}}) {
            Path p = new Path((String) c[0]);
            p.bagRefuses = (int) c[2];
            int amount = (int) c[1];
            GiveAfterWrite.Outcome out = GiveAfterWrite.run(amount, amount, 64, p);
            int refused = (int) c[2];
            assertEquals(List.of("write[]", "give " + amount, "writeBack" + GiveAfterWrite.stacks(refused, 64)),
                    p.steps, p.name);
            assertEquals(amount - refused, p.bag, p.name);
            assertEquals(GiveAfterWrite.stacks(refused, 64), p.mailbox, p.name + ": the rest is in the Mailbox");
            assertEquals(new GiveAfterWrite.Outcome(GiveAfterWrite.Status.DONE, amount - refused, refused, 0), out);
        }

        Path worst = new Path("Pallet takeBack");
        worst.bagRefuses = 4;
        worst.writeBackFails = true;
        GiveAfterWrite.Outcome out = GiveAfterWrite.run(20, 20, 64, worst);
        assertEquals(List.of("write[]", "give 20", "writeBack[4]", "lost 4"), worst.steps,
                "the one case left: said, with the item, in the console for an admin");
        assertEquals(4, out.lost());
    }

    @Test
    void stacksAndTheBag() {
        assertEquals(List.of(64, 64, 22), GiveAfterWrite.stacks(150, 64));
        assertEquals(List.of(), GiveAfterWrite.stacks(0, 64));
        assertEquals(List.of(1, 1), GiveAfterWrite.stacks(2, 0), "a stack is at least one");
        assertEquals(0, GiveAfterWrite.toBag(5, 0));
        assertEquals(5, GiveAfterWrite.toBag(5, 9));
        assertEquals(3, GiveAfterWrite.toBag(5, 3));
        assertEquals(0, GiveAfterWrite.toBag(5, -1));
    }
}
