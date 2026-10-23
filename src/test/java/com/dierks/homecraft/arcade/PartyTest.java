package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Party Time (SHOP-V2-SPEC §5.11): one party at a time on the server, a buy refused at no charge while one runs,
 * and fireworks only around players in an economy world who aren't in a game or a Games world (and don't hide
 * other people's parties, unless it's their own).
 */
class PartyTest {

    @Test
    void oneAtATime() {
        PartyService.Schedule s = new PartyService.Schedule();
        UUID host = UUID.randomUUID();
        long first = s.tryStart(host);
        assertTrue(first != 0, "the first starts");
        assertTrue(s.running());
        assertEquals(0, s.tryStart(UUID.randomUUID()), "a second can't while it runs");
        assertEquals(host, s.host(), "and the first is still the one on");
        assertTrue(s.end(first), "its own timer's last round ends it");
        assertFalse(s.running(), "over");
        long next = s.tryStart(UUID.randomUUID());
        assertTrue(next != 0 && next != first, "then the next one can start, with its own number");
        s.endAll();
        assertFalse(s.running(), "a stop ends whatever is on");
    }

    /**
     * Red-team finding ECON-2: the old party ended on the wall clock while its tick timer still had a round to run,
     * so a buy in that gap started a second party whose timer the first one's last round then cancelled, and went
     * on cancelling every later party until a restart (300 tokens each). Now a party is on until its own timer ends
     * it, and an old timer's end changes nothing.
     */
    @Test
    void aPartyBoughtAsTheLastOneEndsIsNeverCutShortByTheOldTimer() {
        PartyService.Schedule s = new PartyService.Schedule();
        long first = s.tryStart(UUID.randomUUID());
        // the first party's ten seconds are up on the wall clock, its last round hasn't run yet: still on
        assertEquals(0, s.tryStart(UUID.randomUUID()), "a buy in the gap is refused (nothing charged)");
        assertTrue(s.end(first), "the last round ends it");
        long second = s.tryStart(UUID.randomUUID());
        assertTrue(second != 0, "the next one starts");
        assertFalse(s.end(first), "a late call from the first party's timer changes nothing");
        assertFalse(s.end(first), "nor every second after it");
        assertTrue(s.running() && s.on(second), "the second party is still on");
        assertFalse(s.on(first));
        assertTrue(s.end(second), "until its own timer ends it");
        assertFalse(s.running());
    }

    @Test
    void itsLengthIsTheRowsSecondsClamped() {
        assertEquals(10, PartyService.Schedule.rounds(10));
        assertEquals(5, PartyService.Schedule.rounds(1));
        assertEquals(30, PartyService.Schedule.rounds(90));
    }

    @Test
    void aBuyWhileOneRunsIsRefusedWithNothingCharged() throws Exception {
        Prize party = ShopTx.row("party_time", PrizeType.PARTY, 300, ShopTx.limit(LimitPer.DAY, 1), List.of(),
                OnlyIf.ANY);
        try (ShopTx tx = new ShopTx(1000)) {
            assertEquals(PrizeService.PARTY_ON, tx.confirm(party, "d1", false, null, true, PrizeService.PARTY_ON),
                    "read again inside the lock");
            assertEquals(1000, tx.balance(), "nothing charged");
            assertEquals(0, tx.bought(party, "d1"), "and today's one is still there");
            assertNull(tx.confirm(party, "d1"), "with none on, it is bought");
            assertEquals(700, tx.balance());
        }
    }

    @Test
    void onlyEconomyWorldPlayersNotInAGameGetFireworks() {
        assertTrue(PartyService.guest(true, false, false, false, false), "someone playing in an economy world");
        assertFalse(PartyService.guest(false, false, false, false, false), "another world");
        assertFalse(PartyService.guest(true, true, false, false, false), "in a game");
        assertFalse(PartyService.guest(true, false, true, false, false), "a Games world");
        assertFalse(PartyService.guest(true, false, false, true, false), "hiding other people's parties");
        assertTrue(PartyService.guest(true, false, false, true, true), "the host always gets theirs");
        assertFalse(PartyService.guest(true, true, false, false, true), "but not in a game");
    }
}
