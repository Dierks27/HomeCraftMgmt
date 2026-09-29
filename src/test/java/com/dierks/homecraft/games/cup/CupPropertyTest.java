package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.gen.api.GenRandom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cup's rules over random inputs, from fixed seeds so a failure always comes back the same way.
 *
 * <p>Random Cups (0 to 14 entrants, entries of any size, times bunched so ties are common, some
 * entrants without a time, any top-up): every plan passes the ledger proof, pays out exactly the
 * entries plus the top-up (or the entries alone when refunded), is the same whatever order the rows
 * come in, pays each tie group exactly its places' amounts with the remainder to its earliest time,
 * and never pays a slower time more.
 *
 * <p>Random weeks through the {@link CupBook} (players entering, entering again, racing, courses
 * voided, rollovers settled or missed in a crash and caught at the next boot): nobody is ever in a
 * Cup twice, no Cup is ever settled twice, every Cup with entries ends settled, balances never go
 * below zero, and across all of it the players hold exactly the top-ups more than they started with.
 */
class CupPropertyTest {

    private static final int CUPS = 20_000;

    @Test
    void everyRandomCupBalancesAndFollowsTheTable() {
        GenRandom rnd = new GenRandom(0xC0FFEEL);
        CupKey key = CupFixtures.CUP;
        int paidCups = 0;
        int ties = 0;
        for (int c = 0; c < CUPS; c++) {
            List<CupEntry> entries = randomEntries(rnd);
            int topup = rnd.nextInt(-3, 30);
            CupPlan plan = CupRules.settle(key, entries, topup);
            String at = "cup " + c + " " + entries + " top-up " + topup;

            assertEquals(List.of(), CupRules.problems(entries, topup, plan), at + ": the ledger proof");
            long in = entries.stream().mapToLong(CupEntry::paid).sum();
            int top = plan.outcome() == CupPlan.Outcome.PRIZES ? Math.max(0, topup) : 0;
            assertEquals(in + top, plan.paidOut(), at + ": sum(payouts) == sum(entries) + top-up");
            assertEquals(top, CupRules.net(CupRules.ledger(entries, plan)), at + ": the ledger nets the top-up");
            assertEquals(entries.size(), plan.lines().size(), at + ": one line per entrant");

            List<CupEntry> reversed = new ArrayList<>(entries);
            java.util.Collections.reverse(reversed);
            assertEquals(plan, CupRules.settle(key, reversed, topup), at + ": the row order changes nothing");
            List<CupEntry> shuffled = new ArrayList<>(entries);
            for (int i = shuffled.size() - 1; i > 0; i--) {
                java.util.Collections.swap(shuffled, i, rnd.nextInt(i + 1));
            }
            assertEquals(plan, CupRules.settle(key, shuffled, topup), at + ": nor does a shuffle");

            CupPlan v = CupRules.voided(key, entries, CupPlan.VoidReason.CHANGED);
            assertEquals(List.of(), CupRules.problems(entries, topup, v), at + ": the voided plan is sound too");
            assertEquals(0, CupRules.net(CupRules.ledger(entries, v)), at + ": and nets exactly 0");

            if (plan.outcome() == CupPlan.Outcome.PRIZES) {
                paidCups++;
                ties += checkGroups(plan, at);
            }
        }
        assertTrue(paidCups > CUPS / 2, "most random Cups are contests, so the table is well exercised: " + paidCups);
        assertTrue(ties > CUPS / 4, "and ties are common, so the tie rule is too: " + ties);
    }

    @Test
    void randomWeeksNeverDoubleEnterDoubleSettleOrKeepAToken() {
        for (long seed = 1; seed <= 60; seed++) {
            runWeeks(new GenRandom(seed), "seed " + seed);
        }
    }

    /** Checks each tie group of a paid plan; returns how many groups were ties. */
    private static int checkGroups(CupPlan plan, String at) {
        List<CupPayout> placed = new ArrayList<>();
        for (CupPayout l : plan.lines()) {
            if (l.place() > 0) {
                placed.add(l);
            }
        }
        int[] amounts = CupRules.split(plan.pool(), CupRules.shares(placed.size()));
        int ties = 0;
        int i = 0;
        while (i < placed.size()) {
            int j = i;
            while (j < placed.size() && placed.get(j).bestMs() == placed.get(i).bestMs()) {
                j++;
            }
            int group = j - i;
            long want = 0;
            for (int q = i; q < j && q < amounts.length; q++) {
                want += amounts[q];
            }
            long got = 0;
            for (int m = i; m < j; m++) {
                CupPayout l = placed.get(m);
                got += l.tokens();
                assertEquals(i + 1, l.place(), at + ": a group shares the place it starts at");
                assertEquals(group, l.tied(), at + ": and says how many share it");
                if (m > i) {
                    assertEquals(placed.get(i + 1).tokens(), l.tokens(),
                            at + ": everyone after the earliest in a tie gets the same");
                }
            }
            assertEquals(want, got, at + ": a group gets exactly its places' amounts");
            int extra = placed.get(i).tokens() - (int) (want / group);
            assertTrue(extra >= 0 && extra < group, at + ": the earliest gets the even share plus less than one each");
            if (group > 1) {
                ties++;
            }
            i = j;
        }
        return ties;
    }

    private static List<CupEntry> randomEntries(GenRandom rnd) {
        int n = rnd.nextInt(0, 14);
        boolean bunched = rnd.chance(0.7);
        List<CupEntry> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            int paid = rnd.chance(0.05) ? 0 : rnd.nextInt(1, 20);
            long entered = rnd.nextInt(0, 500);
            if (rnd.chance(0.15)) {
                out.add(CupEntry.entered(p(i), paid, entered));
            } else {
                long ms = bunched ? 40_000 + rnd.nextInt(0, 4) : rnd.nextInt(20_000, 90_000);
                out.add(new CupEntry(p(i), paid, entered, ms, entered + rnd.nextInt(0, 50)));
            }
        }
        return out;
    }

    private static void runWeeks(GenRandom rnd, String at) {
        String[] courses = {"sky_rings", "lava_leap", "ice_boat"};
        long firstWeek = CupFixtures.CUP.week();
        int weeks = 6;
        int players = 8;
        CupBook book = new CupBook();
        Map<UUID, Long> balance = new HashMap<>();
        for (int i = 1; i <= players; i++) {
            balance.put(p(i), 20L);
        }
        long start = 20L * players;
        long minted = 0;
        Set<CupKey> settledKeys = new HashSet<>();
        long now = 0;

        for (int w = 0; w < weeks; w++) {
            long week = firstWeek + 7L * w;
            int ops = rnd.nextInt(10, 60);
            for (int o = 0; o < ops; o++) {
                now += rnd.nextInt(1, 1000);
                CupKey key = new CupKey(courses[rnd.nextInt(courses.length)], week);
                UUID who = p(rnd.nextInt(1, players));
                int roll = rnd.nextInt(100);
                if (roll < 45) {
                    int fee = rnd.nextInt(1, 10);
                    boolean wasIn = book.in(key, who);
                    boolean closed = book.settlement(key) != null;
                    long before = balance.get(who);
                    CupRefusal r = book.enter(key, who, fee, (int) before, now, true, true);
                    if (r == null) {
                        assertTrue(!wasIn && !closed && before >= fee, at + ": an entry goes in only when it may");
                        balance.put(who, before - fee);
                    } else if (wasIn && !closed) {
                        assertEquals(CupRefusal.ALREADY_IN, r, at + ": entering twice in a week is refused");
                    }
                } else if (roll < 95) {
                    book.run(key, who, 30_000 + rnd.nextInt(0, 6) * 250L, now);
                } else if (roll < 98) {
                    List<CupEntry> entries = book.entries(key);
                    CupPlan v = book.voidCup(key, CupPlan.VoidReason.values()[rnd.nextInt(3)]);
                    if (v != null) {
                        assertTrue(settledKeys.add(key), at + ": a Cup is closed once");
                        assertEquals(List.of(), CupRules.problems(entries, 0, v), at + ": a sound void");
                        pay(balance, v);
                    }
                } else if (settledKeys.contains(key)) {
                    assertNull(book.settle(key, 10), at + ": settling a closed Cup again pays nothing");
                }
            }
            // the rollover into the next week: settled now, or missed in a crash and caught at a later boot
            if (rnd.chance(0.7)) {
                minted += settleDue(book, week + 7, rnd, balance, settledKeys, at);
            }
        }
        minted += settleDue(book, firstWeek + 7L * weeks, rnd, balance, settledKeys, at);
        assertEquals(List.of(), book.due(firstWeek + 7L * weeks), at + ": after the last boot nothing is left to settle");

        long total = 0;
        for (long b : balance.values()) {
            assertTrue(b >= 0, at + ": no balance goes below zero");
            total += b;
        }
        assertEquals(start + minted, total, at + ": the players hold exactly the top-ups more; the server kept nothing");
        for (CupPlan plan : book.settlements()) {
            assertNull(book.settle(plan.key(), 10), at + ": every Cup refuses a second settlement");
            assertNull(book.voidCup(plan.key(), CupPlan.VoidReason.DELETED), at + ": and a late void");
        }
    }

    private static long settleDue(CupBook book, long currentWeek, GenRandom rnd, Map<UUID, Long> balance,
                                  Set<CupKey> settledKeys, String at) {
        long minted = 0;
        for (CupKey key : book.due(currentWeek)) {
            int topup = rnd.nextInt(0, 15);
            List<CupEntry> entries = book.entries(key);
            CupPlan plan = book.settle(key, topup);
            assertTrue(plan != null && settledKeys.add(key), at + ": a due Cup is settled, once");
            assertEquals(List.of(), CupRules.problems(entries, topup, plan), at + ": a sound settlement of " + key);
            minted += plan.topup();
            pay(balance, plan);
            assertNull(book.settle(key, topup), at + ": a retried settlement pays nothing");
        }
        return minted;
    }

    private static void pay(Map<UUID, Long> balance, CupPlan plan) {
        for (CupPayout l : plan.payouts()) {
            balance.merge(l.player(), (long) l.tokens(), Long::sum);
        }
    }
}
