package com.dierks.homecraft.games.cup;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Players, entries and checks the Cup tests share. */
final class CupFixtures {

    /** A Cup: Sky Rings, the week of Monday 2026-09-28 (epoch day 20724). */
    static final CupKey CUP = new CupKey("sky_rings", 20724L);
    /** The same course the week after. */
    static final CupKey NEXT_WEEK = new CupKey("sky_rings", 20731L);
    /** Another course the same week. */
    static final CupKey OTHER_COURSE = new CupKey("lava_leap", 20724L);

    private CupFixtures() {
    }

    /** Player {@code n}: ids that sort by {@code n}. */
    static UUID p(int n) {
        return new UUID(0L, n);
    }

    /** Player {@code n} paid {@code paid}, entered at {@code n}, Cup time {@code ms} set at {@code 1000 + n}. */
    static CupEntry timed(int n, int paid, long ms) {
        return new CupEntry(p(n), paid, n, ms, 1000L + n);
    }

    /** Player {@code n} paid {@code paid}, entered at {@code n}, Cup time {@code ms} set at {@code at}. */
    static CupEntry timedAt(int n, int paid, long ms, long at) {
        return new CupEntry(p(n), paid, n, ms, at);
    }

    /** Player {@code n} paid {@code paid} and never set a Cup time. */
    static CupEntry untimed(int n, int paid) {
        return CupEntry.entered(p(n), paid, n);
    }

    /** {@code count} entrants paying 5, player i with time {@code 40000 + 100 * i}. */
    static List<CupEntry> field(int count) {
        List<CupEntry> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(timed(i, 5, 40_000L + 100L * i));
        }
        return out;
    }

    /**
     * A counted run of {@code ms} by {@code player} on {@code key}'s course, finished at {@code at},
     * that counts for {@code key}'s week alone ({@link CupRules#runWeeks} worked out by the caller).
     */
    static boolean race(CupBook book, CupKey key, UUID player, long ms, long at) {
        return book.run(key.course(), player, ms, at, CupRules.Weeks.of(key.week()));
    }

    /** The tokens each line pays, in line order. */
    static List<Integer> tokens(CupPlan plan) {
        List<Integer> out = new ArrayList<>();
        for (CupPayout l : plan.lines()) {
            out.add(l.tokens());
        }
        return out;
    }

    /** Player {@code n}'s tokens in {@code plan}. */
    static int tokensOf(CupPlan plan, int n) {
        CupPayout l = plan.lineFor(p(n));
        return l == null ? -1 : l.tokens();
    }

    /** Fails with every problem the ledger proof finds, naming {@code why}. */
    static void assertSound(List<CupEntry> entries, int topup, CupPlan plan, String why) {
        assertEquals(List.of(), CupRules.problems(entries, topup, plan),
                why + ": the plan must balance and follow every Cup rule");
    }
}
