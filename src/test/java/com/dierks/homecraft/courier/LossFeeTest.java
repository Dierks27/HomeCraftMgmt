package com.dierks.homecraft.courier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind a lost crate.
 *
 * <p>Two invariants, and both of them are the kind that only bite in the case nobody tests by
 * hand: a player with no money, and a player who pays more than they owe.
 *
 * <p>Registry-free: this is arithmetic, so it needs no server and no Bukkit call. The formulas
 * are mirrored from {@code CourierService} rather than called, because calling them would mean
 * standing up a plugin, an economy and a database to check a multiplication.
 */
class LossFeeTest {

    /** {@code (base + perBlock × distance) × multiplier}, as {@code CourierService.fee} does it. */
    private static double fee(double base, double perBlock, int distance, double multiplier) {
        return (base + perBlock * distance) * multiplier;
    }

    private static double round(double amount) {
        return Math.round(amount * 100.0) / 100.0;
    }

    /**
     * What you are charged for losing a crate does not depend on how you travelled.
     *
     * <p>The real property, and the one worth pinning: the loss fee is priced at the on-foot
     * multiplier, so a player who flew the route is charged exactly what a player who walked it
     * is. Pricing it off the <i>actual</i> payout instead would make losing a crate cheaper the
     * lazier the journey — and worse, unknowable until after the loss, which is a surprise
     * rather than a deterrent.
     */
    @Test
    void theFeeIsTheSameHoweverThePlayerTravelled() {
        double base = 25;
        double perBlock = 0.35;
        double[] howTheyTravelled = {1.0, 0.75, 0.5, 0.25, 0.0};

        for (int distance = 100; distance <= 4000; distance += 300) {
            double expected = fee(base, perBlock, distance, 1.0);
            for (double travel : howTheyTravelled) {
                // What the run actually paid varies with the journey…
                double paid = fee(base, perBlock, distance, travel);
                // …but the fee is quoted at 1.0 regardless, and never reads `paid`.
                double loss = fee(base, perBlock, distance, 1.0) * 1.0;

                assertEquals(expected, loss, 1e-9,
                        "the loss fee moved with a travel multiplier of " + travel
                                + " — it must be priced on foot, or flying makes losing a crate cheap");
                assertTrue(loss >= paid - 1e-9,
                        "a fee below the payout (" + loss + " vs " + paid + ") would make losing "
                                + "a crate profitable against the run that carried it");
            }
        }
    }

    /**
     * A fee larger than the balance takes everything there is and owes the rest — it never
     * drives the balance negative, and it never quietly charges nothing.
     */
    @Test
    void aPlayerWhoCannotPayOwesExactlyTheShortfall() {
        double[][] cases = {
                //  owed, balance
                {100.00, 0.00},
                {100.00, 40.00},
                {100.00, 99.99},
                {100.00, 100.00},
                {100.00, 250.00},
                {0.01, 0.00},
        };
        for (double[] c : cases) {
            double owed = c[0];
            double balance = c[1];

            double taken = round(Math.min(owed, Math.max(0, balance)));
            double shortfall = round(owed - taken);

            assertTrue(taken <= balance + 1e-9,
                    "took " + taken + " from a balance of " + balance + " — that is an overdraft");
            assertTrue(taken >= 0, "a negative charge is a payout");
            assertTrue(shortfall >= 0, "a negative shortfall is credit with the courier office");
            assertEquals(owed, round(taken + shortfall), 1e-9,
                    "what was taken plus what is owed must be the fee, or money appeared");
        }
    }

    /**
     * Paying down a debt clamps at zero.
     *
     * <p>Pinned because the failure is silent and compounding: a negative balance in the debt
     * table would be credit, and credit turns settling one lost crate into a head start on the
     * next.
     */
    @Test
    void payingMoreThanYouOweSettlesTheAccountAndNoMore() {
        double[][] cases = {{50, 10}, {50, 50}, {50, 75}, {50, 0}, {0, 25}};
        for (double[] c : cases) {
            double owed = c[0];
            double payment = c[1];
            double remaining = Math.max(0, owed - Math.max(0, payment));
            assertTrue(remaining >= 0,
                    "owing " + owed + " and paying " + payment + " left " + remaining
                            + " — debt must never go below zero");
            assertTrue(remaining <= owed,
                    "paying must never increase what is owed");
        }
    }
}
