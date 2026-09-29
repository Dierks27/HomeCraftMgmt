package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.field;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static com.dierks.homecraft.games.cup.CupFixtures.timed;
import static com.dierks.homecraft.games.cup.CupFixtures.untimed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger proof that the server keeps nothing (§D2): a Cup's entries and payments sum to exactly
 * the top-up when it pays prizes and exactly 0 when it refunds, so {@code sum(payouts) ==
 * sum(entries) + topup}; and {@link CupRules#problems} catches every way a plan could break the rules.
 */
class CupLedgerTest {

    @Test
    void aPaidCupsLedgerNetsExactlyTheTopUp() {
        List<CupEntry> e = field(3);
        CupPlan plan = CupRules.settle(CUP, e, 10);
        List<CupLedgerRow> rows = CupRules.ledger(e, plan);
        assertEquals(List.of(
                new CupLedgerRow(p(1), CupSource.GAMES_CUP_ENTRY, -5),
                new CupLedgerRow(p(2), CupSource.GAMES_CUP_ENTRY, -5),
                new CupLedgerRow(p(3), CupSource.GAMES_CUP_ENTRY, -5),
                new CupLedgerRow(p(1), CupSource.GAMES_CUP_PRIZE, 13),
                new CupLedgerRow(p(2), CupSource.GAMES_CUP_PRIZE, 7),
                new CupLedgerRow(p(3), CupSource.GAMES_CUP_PRIZE, 5)), rows,
                "three entries out, three prizes back");
        assertEquals(10, CupRules.net(rows), "the players end up exactly the top-up richer: the server keeps nothing");
        assertEquals(plan.entries() + plan.topup(), plan.paidOut(), "sum(prizes) == sum(entries) + topup");
    }

    @Test
    void aRefundedCupsLedgerNetsExactlyZero() {
        List<CupEntry> alone = List.of(timed(1, 5, 40_000));
        assertEquals(0, CupRules.net(CupRules.ledger(alone, CupRules.settle(CUP, alone, 10))),
                "a lone entrant is exactly where they started");
        List<CupEntry> noContest = List.of(timed(1, 5, 40_000), untimed(2, 7));
        assertEquals(0, CupRules.net(CupRules.ledger(noContest, CupRules.settle(CUP, noContest, 10))),
                "no contest: everyone exactly where they started");
        List<CupEntry> ten = field(10);
        List<CupLedgerRow> rows = CupRules.ledger(ten, CupRules.voided(CUP, ten, CupPlan.VoidReason.CLOSED));
        assertEquals(0, CupRules.net(rows), "voided: everyone exactly where they started");
        assertEquals(20, rows.size(), "ten entries and ten refunds");
        assertTrue(rows.subList(10, 20).stream().allMatch(r -> r.source() == CupSource.GAMES_CUP_REFUND),
                "the refunds are written as GAMES_CUP_REFUND");
    }

    @Test
    void theSourcesAreTheTokenServicesNames() {
        assertEquals(List.of("GAMES_CUP_ENTRY", "GAMES_CUP_PRIZE", "GAMES_CUP_REFUND"),
                List.of(CupSource.values()).stream().map(Enum::name).toList(),
                "the wiring maps them by name to TokenService.Source");
        assertEquals(CupSource.GAMES_CUP_PRIZE, CupPayout.Kind.PRIZE.source(), "a prize's source");
        assertEquals(CupSource.GAMES_CUP_REFUND, CupPayout.Kind.REFUND.source(), "a refund's source");
        assertEquals(null, CupPayout.Kind.NONE.source(), "nothing paid, nothing written");
    }

    @Test
    void theProofFindsNothingWrongWithARealPlan() {
        for (int n = 0; n <= 12; n++) {
            List<CupEntry> e = field(n);
            assertEquals(List.of(), CupRules.problems(e, 10, CupRules.settle(CUP, e, 10)), n + " entrants: a sound plan");
        }
    }

    @Test
    void theProofCatchesAServerThatKeepsOrMintsATokens() {
        List<CupEntry> e = field(3);
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertCaught(e, 10, withTokens(plan, 0, 12), "the server would keep tokens", "1st paid one short");
        assertCaught(e, 10, withTokens(plan, 2, 6), "tokens from nowhere", "3rd paid one extra");
        CupPlan noTopup = new CupPlan(CUP, plan.outcome(), null, 15, 0, 15, plan.lines());
        assertCaught(e, 10, noTopup, "the top-up is 0 but should be 10", "the promised top-up left out");
    }

    @Test
    void theProofCatchesATopUpWithoutAContest() {
        List<CupEntry> alone = List.of(timed(1, 5, 40_000));
        CupPlan plan = new CupPlan(CUP, CupPlan.Outcome.REFUND_ALONE, null, 5, 10, 15,
                List.of(new CupPayout(p(1), CupPayout.Kind.REFUND, 15, 0, 0, 40_000, 5)));
        assertCaught(alone, 10, plan, "only a contest of 2 or more gets one", "a lone entrant paid the top-up");
        assertCaught(alone, 10, plan, "a refund of 15", "and a refund bigger than the entry");
    }

    @Test
    void theProofCatchesLinesForTheWrongPeople() {
        List<CupEntry> e = field(3);
        CupPlan plan = CupRules.settle(CUP, e, 10);
        List<CupPayout> stranger = new ArrayList<>(plan.lines());
        stranger.set(2, new CupPayout(p(99), CupPayout.Kind.PRIZE, 5, 3, 1, 42_000, 5));
        CupPlan s = new CupPlan(CUP, plan.outcome(), null, plan.entries(), plan.topup(), plan.pool(), stranger);
        assertCaught(e, 10, s, "who didn't enter", "a stranger paid");
        assertCaught(e, 10, s, "no line for entrant", "and an entrant left out");

        List<CupPayout> twice = new ArrayList<>(plan.lines());
        twice.set(2, new CupPayout(p(1), CupPayout.Kind.PRIZE, 5, 3, 1, 40_100, 5));
        CupPlan t = new CupPlan(CUP, plan.outcome(), null, plan.entries(), plan.topup(), plan.pool(), twice);
        assertCaught(e, 10, t, "two lines for", "one player paid twice");
    }

    @Test
    void theProofCatchesAPrizeWithoutATimeAndASlowerTimePaidMore() {
        List<CupEntry> e = List.of(timed(1, 5, 40_000), timed(2, 5, 41_000), untimed(3, 5));
        CupPlan plan = CupRules.settle(CUP, e, 10);
        List<CupPayout> lines = new ArrayList<>(plan.lines());
        lines.set(0, new CupPayout(p(1), CupPayout.Kind.PRIZE, lines.get(0).tokens() - 1, 1, 1, 40_000, 5));
        lines.set(2, new CupPayout(p(3), CupPayout.Kind.PRIZE, 1, 0, 0, CupEntry.NO_TIME, 5));
        assertCaught(e, 10, withLines(plan, lines), "who set no Cup time", "an entrant without a time paid");

        List<CupPayout> swapped = new ArrayList<>(plan.lines());
        swapped.set(0, new CupPayout(p(1), CupPayout.Kind.PRIZE, 7, 1, 1, 40_000, 5));
        swapped.set(1, new CupPayout(p(2), CupPayout.Kind.PRIZE, 18, 2, 1, 41_000, 5));
        assertCaught(e, 10, withLines(plan, swapped), "a slower time gets more", "the places swapped");
    }

    @Test
    void theProofCatchesTheWrongOutcomeAndMislabelledLines() {
        List<CupEntry> e = field(2);
        CupPlan refunded = CupRules.voided(CUP, e, CupPlan.VoidReason.CLOSED);
        CupPlan notVoided = new CupPlan(CUP, CupPlan.Outcome.REFUND_ALONE, null, refunded.entries(), 0,
                refunded.pool(), refunded.lines());
        assertCaught(e, 10, notVoided, "make it PRIZES", "two Cup times refunded as if alone");
        CupPlan noReason = new CupPlan(CUP, CupPlan.Outcome.VOIDED, null, refunded.entries(), 0,
                refunded.pool(), refunded.lines());
        assertCaught(e, 10, noReason, "without a reason", "a void with no reason to tell");

        CupPlan paid = CupRules.settle(CUP, e, 10);
        List<CupPayout> labels = new ArrayList<>(paid.lines());
        CupPayout second = labels.get(1);
        labels.set(1, new CupPayout(second.player(), CupPayout.Kind.NONE, second.tokens(), 2, 1, second.bestMs(), 5));
        assertCaught(e, 10, withLines(paid, labels), "a NONE line of 6 tokens", "a payment marked as nothing");
        List<CupPayout> refundInPaid = new ArrayList<>(paid.lines());
        refundInPaid.set(1, new CupPayout(second.player(), CupPayout.Kind.REFUND, 6, 2, 1, second.bestMs(), 5));
        assertCaught(e, 10, withLines(paid, refundInPaid), "a refund in a paid Cup", "a refund inside a contest");
        assertCaught(e, 10, null, "no plan", "a missing plan");
    }

    @Test
    void theSettlementRowsJsonIsStable() {
        List<CupEntry> e = List.of(timed(1, 5, 40_000), timed(2, 5, 40_000), untimed(3, 5));
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertEquals("{\"outcome\":\"PRIZES\",\"reason\":null,\"entries\":15,\"topup\":10,\"pool\":25,\"lines\":["
                        + "{\"player\":\"00000000-0000-0000-0000-000000000001\",\"kind\":\"PRIZE\",\"tokens\":13,\"place\":1,\"tied\":2,\"ms\":40000,\"paid\":5},"
                        + "{\"player\":\"00000000-0000-0000-0000-000000000002\",\"kind\":\"PRIZE\",\"tokens\":12,\"place\":1,\"tied\":2,\"ms\":40000,\"paid\":5},"
                        + "{\"player\":\"00000000-0000-0000-0000-000000000003\",\"kind\":\"NONE\",\"tokens\":0,\"place\":0,\"tied\":0,\"ms\":null,\"paid\":5}]}",
                plan.json(), "the payouts column is written the same way every time");
        CupPlan v = CupRules.voided(CUP, List.of(untimed(1, 5)), CupPlan.VoidReason.CHANGED);
        assertTrue(v.json().startsWith("{\"outcome\":\"VOIDED\",\"reason\":\"CHANGED\","), "a void names its reason");
        assertFalse(CupRules.settle(CUP, List.of(), 10).json().contains("player"), "an empty Cup has no lines");
    }

    private static void assertCaught(List<CupEntry> entries, int topup, CupPlan plan, String fragment, String why) {
        List<String> problems = CupRules.problems(entries, topup, plan);
        assertTrue(problems.stream().anyMatch(s -> s.contains(fragment)),
                why + ": the proof must say \"" + fragment + "\", but said " + problems);
    }

    private static CupPlan withTokens(CupPlan plan, int index, int tokens) {
        return withLines(plan, mapLine(plan.lines(), index, l -> new CupPayout(l.player(), l.kind(), tokens,
                l.place(), l.tied(), l.bestMs(), l.paid())));
    }

    private static CupPlan withLines(CupPlan plan, List<CupPayout> lines) {
        return new CupPlan(plan.key(), plan.outcome(), plan.reason(), plan.entries(), plan.topup(), plan.pool(), lines);
    }

    private static List<CupPayout> mapLine(List<CupPayout> lines, int index, UnaryOperator<CupPayout> f) {
        List<CupPayout> out = new ArrayList<>(lines);
        out.set(index, f.apply(out.get(index)));
        return out;
    }
}
