package com.dierks.homecraft.games.cup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.dierks.homecraft.games.cup.CupFixtures.CUP;
import static com.dierks.homecraft.games.cup.CupFixtures.field;
import static com.dierks.homecraft.games.cup.CupFixtures.p;
import static com.dierks.homecraft.games.cup.CupFixtures.timed;
import static com.dierks.homecraft.games.cup.CupFixtures.untimed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Cup's words (§D2 copy): the entry prompt and the pool line word for word, ordinals, what each
 * entrant reads when the Cup ends, the ledger details, and never "bet", "wager", a near miss or a
 * character a Bedrock client can't show.
 */
class CupTextTest {

    private static final Pattern BANNED = Pattern.compile(
            "\\b(bet|bets|betting|wager|wagers|gamble|gambling|jackpot|almost|so close|try again|unlucky)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void theEntryPromptAndThePoolLineReadAsTheOwnerWroteThem() {
        assertEquals("Enter this week's Cup: 5 tokens. Best time wins the pool.", CupText.enterPrompt(5),
                "the entry prompt, word for word");
        assertEquals("Enter this week's Cup: 1 token. Best time wins the pool.", CupText.enterPrompt(1), "singular");
        assertEquals("Cup pool: 35 tokens · 5 in", CupText.poolLine(35, 5), "the live pool line, word for word");
        List<CupEntry> five = field(5);
        assertEquals("Cup pool: 35 tokens · 5 in", CupRules.livePool(five, 10).line(),
                "five entries of 5 and the top-up of 10 read as 35");
        assertEquals("Cup pool: 5 tokens · 1 in", CupRules.livePool(field(1), 10).line(),
                "alone, the top-up isn't promised");
        assertEquals("Cup pool: 0 tokens · 0 in", CupRules.livePool(List.of(), 10).line(), "nobody in yet");
    }

    @Test
    void ordinalsReadLikeEnglish() {
        List<String> got = new ArrayList<>();
        for (int n : new int[]{1, 2, 3, 4, 10, 11, 12, 13, 14, 21, 22, 23, 101, 111, 112}) {
            got.add(CupText.ordinal(n));
        }
        assertEquals(List.of("1st", "2nd", "3rd", "4th", "10th", "11th", "12th", "13th", "14th", "21st", "22nd",
                "23rd", "101st", "111th", "112th"), got, "st, nd, rd and th, with 11 to 13 as th");
    }

    @Test
    void eachEntrantReadsHowTheirCupWent() {
        List<CupEntry> e = new ArrayList<>(field(4));
        e.add(timed(5, 5, 40_100));
        e.add(untimed(6, 5));
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertEquals("Weekly Cup on Sky Rings: you came tied 1st with 0:40.1 - 16 tokens.",
                CupText.result(plan, plan.lineFor(p(1)), "Sky Rings"), "a tie says so: 20 + 12 of 40 shared by two");
        assertEquals("Weekly Cup on Sky Rings: you came tied 1st with 0:40.1 - 16 tokens.",
                CupText.result(plan, plan.lineFor(p(5)), "Sky Rings"), "both of the tie read the same");
        assertEquals("Weekly Cup on Sky Rings: you came 3rd with 0:40.2 - 8 tokens.",
                CupText.result(plan, plan.lineFor(p(2)), "Sky Rings"), "after a tie for 1st comes 3rd");
        assertEquals("Weekly Cup on Sky Rings: you came 5th with 0:40.4.",
                CupText.result(plan, plan.lineFor(p(4)), "Sky Rings"), "outside the paid places: the place, plainly");
        assertEquals("Weekly Cup on Sky Rings: you didn't set a Cup time this week.",
                CupText.result(plan, plan.lineFor(p(6)), "Sky Rings"), "no time, said plainly");
        CupPlan three = CupRules.settle(CUP, field(3), 10);
        assertEquals("Weekly Cup on Sky Rings: you came 1st with 0:40.1 - 13 tokens.",
                CupText.result(three, three.lineFor(p(1)), "Sky Rings"), "a winner reads their place, time and tokens");

        CupPlan alone = CupRules.settle(CUP, List.of(timed(1, 5, 40_000)), 10);
        assertEquals("Nobody else entered the Weekly Cup on Sky Rings, so your 5 tokens came back.",
                CupText.result(alone, alone.lines().get(0), "Sky Rings"), "a lone entrant is told why");
        CupPlan none = CupRules.settle(CUP, List.of(timed(1, 5, 40_000), untimed(2, 5)), 10);
        assertEquals("Fewer than 2 Cup times were set in the Weekly Cup on Sky Rings, so your 5 tokens came back.",
                CupText.result(none, none.lines().get(0), "Sky Rings"), "no contest is told why");
    }

    @Test
    void theLedgerDetailsNameTheCupAndThePlace() {
        List<CupEntry> e = List.of(timed(1, 5, 40_000), timed(2, 5, 41_000), timed(3, 5, 41_000));
        CupPlan plan = CupRules.settle(CUP, e, 10);
        assertEquals("Weekly Cup entry: Sky Rings", CupText.entryDetail("Sky Rings"), "an entry");
        assertEquals("Weekly Cup: 1st on Sky Rings", CupText.payoutDetail(plan.lineFor(p(1)), "Sky Rings"), "a prize");
        assertEquals("Weekly Cup: tied 2nd on Sky Rings", CupText.payoutDetail(plan.lineFor(p(3)), "Sky Rings"), "a shared prize");
        CupPlan v = CupRules.voided(CUP, e, CupPlan.VoidReason.DELETED);
        assertEquals("Weekly Cup refund: Sky Rings", CupText.payoutDetail(v.lines().get(0), "Sky Rings"), "a refund");
        CupPlan ten = CupRules.settle(CUP, field(10), 10);
        assertNull(CupText.payoutDetail(ten.lineFor(p(10)), "Sky Rings"), "nothing paid, nothing written");
    }

    @Test
    void noLineCallsTheCupABetOrDressesUpAResult() {
        List<String> all = new ArrayList<>();
        all.add(CupText.enterPrompt(5));
        all.add(CupText.poolLine(35, 5));
        for (CupRefusal r : CupRefusal.values()) {
            all.add(r.message(5));
        }
        List<CupEntry> e = new ArrayList<>(field(10));
        e.add(untimed(11, 5));
        for (CupPlan plan : List.of(CupRules.settle(CUP, e, 10), CupRules.settle(CUP, field(1), 10),
                CupRules.settle(CUP, List.of(timed(1, 5, 40_000), untimed(2, 5)), 10),
                CupRules.voided(CUP, field(2), CupPlan.VoidReason.CLOSED))) {
            for (CupPayout l : plan.lines()) {
                all.add(CupText.result(plan, l, "Sky Rings"));
                String d = CupText.payoutDetail(l, "Sky Rings");
                if (d != null) {
                    all.add(d);
                }
            }
        }
        for (String s : all) {
            assertFalse(BANNED.matcher(s).find(), "a skill contest's words, not a game of chance's: " + s);
            assertFalse(s.codePoints().anyMatch(c -> c > 0xFFFF), "nothing above U+FFFF (no emoji): " + s);
            assertFalse(s.contains("!"), "no shouting, not even for a win: " + s);
        }
    }
}
