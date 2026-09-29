package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPayout;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import com.dierks.homecraft.games.cup.CupText;
import com.dierks.homecraft.games.trial.FairPlay;
import com.dierks.homecraft.gui.games.trial.CourseMenu;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup's words on the screens, tiles and commands (§D2 copy, the house rules): the entry
 * prompt is in the item NAME ("Enter this week's Cup: 5 tokens. Best time wins the pool."), and so
 * are the pool and whether you're in; the tile's NAME carries the entry and the pool; every line is
 * for kids, with none of the words a skill contest must never use and nothing above U+FFFF; and
 * only a counted, timed run reaches the Cup through Time Trials' hook.
 */
class CupWordsTest {

    /** Words the house rules keep out of every line (EV-BUILD). */
    private static final Pattern BANNED = Pattern.compile(
            "\\b(bet|bets|betting|wager|wagers|gamble|gambling|casino|lucky|almost|sink)\\b|so close",
            Pattern.CASE_INSENSITIVE);

    private static final CupKey KEY = new CupKey("lava_leap", 20724L);
    private static final UUID ME = new UUID(0, 7);

    private static CupDesk.View view(boolean on, boolean open, int in, CupEntry mine, CupPlan.Outcome settled,
                                     CupRefusal refusal) {
        List<CupEntry> entries = new ArrayList<>();
        for (int i = 0; i < in; i++) {
            entries.add(CupEntry.entered(new UUID(1, i), 5, i));
        }
        return new CupDesk.View(KEY, on, open, 5, CupRules.livePool(entries, 10), mine, settled, 1L, refusal);
    }

    private static void assertKidSafe(String line) {
        assertFalse(BANNED.matcher(line).find(), "a word a skill contest never uses: " + line);
        assertTrue(line.codePoints().allMatch(c -> c <= 0xFFFF), "nothing above U+FFFF (no emoji): " + line);
    }

    /** Every line the Cup can put in front of a player. */
    private static List<String> everyLine() {
        List<String> out = new ArrayList<>(CupWords.RULES);
        out.addAll(CupWords.SHARES);
        CupEntry mine = new CupEntry(ME, 5, 0, 41_230, 50);
        List<CupDesk.View> views = new ArrayList<>();
        for (CupRefusal r : CupRefusal.values()) {
            views.add(view(true, true, 3, null, null, r));
        }
        views.add(view(true, true, 0, null, null, null));
        views.add(view(true, true, 5, mine, null, CupRefusal.ALREADY_IN));
        views.add(view(true, false, 2, null, null, CupRefusal.OFF));
        views.add(view(true, true, 1, CupEntry.entered(ME, 5, 0), null, CupRefusal.ALREADY_IN));
        views.add(view(true, true, 2, null, null, CupRefusal.NOT_UP_YET));
        for (CupDesk.View v : views) {
            out.add(CupWords.buttonName(v));
            out.addAll(CupWords.buttonLore(v, "Mon 4:00 AM"));
            out.add(CupWords.enterName(v));
            out.add(CupWords.tileSuffix(v));
            out.addAll(CupWords.tileLines(v));
        }
        out.add(CupWords.entered("Lava Leap", 5));
        out.add(CupWords.newCupTime("Lava Leap", 41_230));
        out.add(CupWords.lastWeeksLayout());
        out.add(CupWords.SHARES_NAME);
        out.add(CupWords.promptsState(true));
        out.add(CupWords.promptsState(false));
        out.add(CupWords.promptsSet(true));
        out.add(CupWords.promptsSet(false));
        for (CupPlan.Outcome o : CupPlan.Outcome.values()) {
            out.add(CupWords.outcome(o));
        }
        for (CupRefusal r : CupRefusal.values()) {
            out.add(r.message(5));
        }
        List<CupEntry> field = List.of(new CupEntry(new UUID(2, 1), 5, 1, 40_000, 10),
                new CupEntry(new UUID(2, 2), 5, 2, 40_000, 20), new CupEntry(new UUID(2, 3), 5, 3, 45_000, 30),
                CupEntry.entered(new UUID(2, 4), 5, 4));
        List<CupPlan> plans = new ArrayList<>(List.of(CupRules.settle(KEY, field, 10),
                CupRules.settle(KEY, field.subList(0, 1), 10), CupRules.settle(KEY, List.of(field.get(0), field.get(3)), 10)));
        for (CupPlan.VoidReason why : CupPlan.VoidReason.values()) {
            plans.add(CupRules.voided(KEY, field, why));
        }
        for (CupPlan p : plans) {
            for (CupPayout l : p.lines()) {
                out.add(CupDesk.words("Lava Leap").line(p, l));
                if (l.pays()) {
                    out.add(CupText.payoutDetail(l, "Lava Leap"));
                }
            }
        }
        return out;
    }

    @Test
    void everyLineIsForKids() {
        List<String> lines = everyLine();
        assertTrue(lines.size() > 60, "the whole set was checked: " + lines.size());
        for (String line : lines) {
            assertKidSafe(line);
        }
    }

    @Test
    void theEntryPromptIsInTheItemName() {
        CupDesk.View open = view(true, true, 0, null, null, null);
        assertEquals("&6Enter this week's Cup: 5 tokens. Best time wins the pool. &eCup pool: 0 tokens · 0 in",
                CupWords.buttonName(open), "§D2's copy, in the course screen's item NAME for Bedrock, with the pool");
        assertEquals("&6Enter this week's Cup: 5 tokens. Best time wins the pool. &eCup pool: 20 tokens · 2 in",
                CupWords.buttonName(view(true, true, 2, null, null, CupRefusal.NOT_ENOUGH_TOKENS)),
                "the prompt and the live pool stay when you can't pay yet; the Cup screen says why");
        assertEquals("&aPay 5 tokens and enter this week's Cup", CupWords.enterName(open),
                "the Cup screen's button says what a click does");
        assertEquals("&7Weekly Cup &8- &7called off this week",
                CupWords.buttonName(view(true, true, 3, null, null, CupRefusal.CALLED_OFF)), "or why it's shut");
    }

    @Test
    void theLivePoolIsInTheNameOnceYoureIn() {
        CupDesk.View in = view(true, true, 5, new CupEntry(ME, 5, 0, 41_230, 50), null, CupRefusal.ALREADY_IN);
        assertEquals("&6You're in this week's Cup &7- your time &f0:41.2 &7- Cup pool: 35 tokens · 5 in",
                CupWords.buttonName(in), "your Cup time and \"Cup pool: 35 tokens · 5 in\" (25 in, plus 10) in the NAME");
        assertEquals("&aYou're in this week's Cup &7- your time &f0:41.2", CupWords.enterName(in),
                "and the Cup screen's NAME carries your time too");
        CupDesk.View noTime = view(true, true, 2, CupEntry.entered(ME, 5, 0), null, CupRefusal.ALREADY_IN);
        assertEquals("&6You're in this week's Cup &7- no Cup time yet &7- Cup pool: 20 tokens · 2 in",
                CupWords.buttonName(noTime), "before a counted run");
        assertEquals("&aYou're in this week's Cup &7- no Cup time yet", CupWords.enterName(noTime));
        assertTrue(CupWords.buttonLore(in, "Mon 4:00 AM").contains("&7Your Cup time: &f0:41.2"), "your Cup time");
        assertTrue(CupWords.buttonLore(in, "Mon 4:00 AM").contains("&7Paid Mon 4:00 AM."), "and when it is paid");
    }

    @Test
    void theTileNameCarriesTheEntryAndThePool() {
        assertEquals(" &6· Cup: 5 tokens", CupWords.tileSuffix(view(true, true, 0, null, null, null)),
                "nobody in yet: what it costs");
        assertEquals(" &6· Cup: 5 tokens, pool 20", CupWords.tileSuffix(view(true, true, 2, null, null, null)),
                "others in: the cost and the pool");
        assertEquals(" &6· in the Cup, pool 35", CupWords.tileSuffix(view(true, true, 5,
                new CupEntry(ME, 5, 0, 41_230, 50), null, CupRefusal.ALREADY_IN)), "you're in: the pool");
        assertEquals(" &6· Cup pool 20", CupWords.tileSuffix(view(true, false, 2, null, null, CupRefusal.OFF)),
                "entries closed: only the pool of the Cup still running");
        assertEquals("", CupWords.tileSuffix(view(false, true, 0, null, null, CupRefusal.NOT_ON_THIS_COURSE)),
                "a course with no Cup says nothing");
        assertEquals("", CupWords.tileSuffix(view(true, true, 3, null, CupPlan.Outcome.VOIDED, CupRefusal.CALLED_OFF)),
                "nor one whose Cup was called off this week");
        assertEquals("", CupWords.tileSuffix(null), "nor a Cup that is hidden or can't be read");
        assertEquals(List.of("&6Enter this week's Cup: 5 tokens. Best time wins the pool.", "&eCup pool: 0 tokens · 0 in"),
                CupWords.tileLines(view(true, true, 0, null, null, null)), "the tile's lore: the prompt and the pool");
    }

    @Test
    void theRulesOnTheScreenAreTheOnesThePoolIsPaidBy() {
        assertEquals(List.of("2 Cup times: 70% and 30%.", "3 or more Cup times: 50%, 30% and 20%.",
                "Fewer than 2 Cup times at the end? Every entry comes back.",
                "No Cup time? No share: your entry stays in the pool.", "Warm-ups and test runs never count.",
                "The server keeps nothing: every token is paid out."), CupWords.SHARES,
                "shared by Cup times, not by who entered, and the rule for no Cup time stated");
        assertTrue(CupWords.SHARES_NAME.contains("70/30 for 2 Cup times, 50/30/20 for 3 or more"),
                "the shares in the Cup screen's item NAME, for Bedrock: " + CupWords.SHARES_NAME);
        // What the lines say is what the rules do: two in, one Cup time, and it all comes back...
        List<CupEntry> twoInOneTime = List.of(new CupEntry(new UUID(3, 1), 5, 1, 40_000, 10),
                CupEntry.entered(new UUID(3, 2), 5, 2));
        assertEquals(CupPlan.Outcome.REFUND_NO_CONTEST, CupRules.settle(KEY, twoInOneTime, 10).outcome(),
                "fewer than 2 Cup times: every entry comes back");
        // ...and three in with two Cup times is shared 70/30, the one with no time getting nothing.
        List<CupEntry> threeInTwoTimes = List.of(new CupEntry(new UUID(3, 1), 5, 1, 40_000, 10),
                new CupEntry(new UUID(3, 2), 5, 2, 41_000, 20), CupEntry.entered(new UUID(3, 3), 5, 3));
        CupPlan plan = CupRules.settle(KEY, threeInTwoTimes, 10);
        assertEquals(List.of(18, 7, 0), plan.lines().stream().map(CupPayout::tokens).toList(),
                "2 Cup times: 70% and 30% of 25; no Cup time, no share");
    }

    @Test
    void aCupPaidOutEarlySaysItIsBackNextWeek() {
        assertEquals("This week's Cup on this course is already paid out. It's back next week.",
                CupRefusal.WEEK_OVER.message(5), "not a loop back to the same screen");
        assertEquals("&7Weekly Cup &8- &7already paid out this week",
                CupWords.buttonName(view(true, true, 3, null, null, CupRefusal.WEEK_OVER)), "in the NAME too");
    }

    @Test
    void onlyACountedTimedRunReachesTheCup() {
        FairPlay.Verdict counted = new FairPlay.Verdict(FairPlay.Kind.COUNTED, null);
        assertTrue(CupLink.counts(counted, false), "a counted, timed run");
        assertFalse(CupLink.counts(counted, true), "never a warm-up lap or a practice drop (D3)");
        assertFalse(CupLink.counts(new FairPlay.Verdict(FairPlay.Kind.TEST, null), false), "never a test run");
        assertFalse(CupLink.counts(new FairPlay.Verdict(FairPlay.Kind.VOID, "flying"), false), "never a voided run");
        assertFalse(CupLink.counts(new FairPlay.Verdict(FairPlay.Kind.STALE, "changed"), false),
                "never a run on a layout that's gone");
        assertFalse(CupLink.counts(null, false), "nor no verdict at all");
    }

    @Test
    void theCupsItemHasItsOwnSlotOnTheCourseScreen() {
        assertEquals(24, CupLink.SLOT, "the bottom row, right of the way out (22)");
        assertFalse(CourseMenu.FIXED_SLOTS.contains(CupLink.SLOT), "never on a slot the course screen already uses");
        assertNotEquals(CourseMenu.PARTY_SLOT, CupLink.SLOT, "nor on Race with friends' slot (20)");
        assertEquals("cup", WeeklyCup.SPEC.id().toLowerCase(Locale.ROOT), "/hcm games cup");
    }
}
