package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupKey;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin tools review, group D (fix2-D): the Weekly Cup after an admin's early
 * {@code /hcm games cup settle}. Once the Cup is switched off ({@code games.cup.enabled: false}) the
 * paid-out item goes, as every Cup prompt does, and nothing says "It's back next week" (D9); and the
 * Cup screen reached from "Click to see the Cup" no longer says the pool will be "paid Mon 4:00 AM"
 * next to "already paid out" (D10).
 */
class CupPaidOutEarlyTest {

    private static final CupKey KEY = new CupKey("lava_leap", 20724);
    private static final CupRules.LivePool POOL = new CupRules.LivePool(25, 3);
    private static final CupEntry MINE = new CupEntry(new UUID(0, 1), 5, 1L, 40_000, 2L);
    private static final String WHEN = "Mon 4:00 AM";

    private static CupDesk.View view(boolean open, CupPlan.Outcome settledAs, CupEntry mine, CupRefusal refusal) {
        return new CupDesk.View(KEY, true, open, 5, POOL, mine, settledAs, 0L, refusal);
    }

    @Test
    void aCupPaidOutEarlyAndThenSwitchedOffIsHiddenAndNeverSaysItsBackNextWeek() {
        CupDesk.View paidOpen = view(true, CupPlan.Outcome.PRIZES, MINE, CupRefusal.WEEK_OVER);
        assertTrue(paidOpen.shown(), "paid out early while the Cup is on: the course screen says so, as before");

        for (CupEntry mine : new CupEntry[]{MINE, null}) {
            CupDesk.View off = view(false, CupPlan.Outcome.PRIZES, mine, CupRefusal.OFF);
            assertFalse(off.shown(), "switched off: no Cup item, as games.cup.enabled: false promises (in: "
                    + (mine != null) + ")");
            assertEquals("&7The Weekly Cup is closed right now.", CupWords.enterName(off),
                    "a Cup screen still open reads why it is closed, not that it's back next week");
            assertEquals("&7Weekly Cup &8- &7closed right now", CupWords.buttonName(off), "and the NAME says closed");
            for (String line : CupWords.buttonLore(off, WHEN)) {
                assertFalse(line.contains("back next week"), "no promise the Cup is back: " + line);
            }
        }
        CupDesk.View runningOff = view(false, null, MINE, CupRefusal.OFF);
        assertTrue(runningOff.shown(), "a Cup already paid into still finishes while entries are off, as before");
    }

    @Test
    void theCupScreensPoolTileSaysAPaidOutCupWasPaidNotThatItWillBePaidMonday() {
        CupDesk.View paid = view(true, CupPlan.Outcome.PRIZES, MINE, CupRefusal.WEEK_OVER);
        String name = CupWords.poolName(paid, WHEN);
        assertFalse(name.contains("paid " + WHEN), "no payout still to come in the NAME: " + name);
        assertTrue(name.contains("paid out early"), "it says the pool was paid out: " + name);
        List<String> lore = CupWords.poolLore(paid, WHEN, 10);
        assertFalse(String.join(" ", lore).contains("Paid " + WHEN), "nor in the lore: " + lore);
        assertFalse(String.join(" ", lore).contains("from the server when 2 or more"), "no top-up still to come: "
                + lore);

        CupDesk.View running = view(true, null, MINE, null);
        assertEquals("&6Cup pool: 25 tokens · 3 in &7- paid " + WHEN, CupWords.poolName(running, WHEN),
                "a running Cup's pool, as before");
        assertEquals(List.of("&7Every entry, plus 10 tokens from the server when 2 or more set a Cup time.",
                "&7Paid " + WHEN + "."), CupWords.poolLore(running, WHEN, 10), "as before");
    }
}
