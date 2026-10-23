package com.dierks.homecraft.arcade.looks;

import com.dierks.homecraft.storage.LookDao;
import com.dierks.homecraft.storage.LookDao.Look;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.dierks.homecraft.arcade.looks.LookFixtures.DAY;
import static com.dierks.homecraft.arcade.looks.LookFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Something else comes on at an end (SHOP-V2-SPEC §3.9, red team O7): when the rented trail that was on ends, the
 * newest kept trail (a collector or a Keeper) comes on; when a rented hat comes off the head at its end, the newest
 * collector cap from the bag goes on. Otherwise she would walk with nothing.
 */
class EndFallbackTest {

    private static final long NOW = 400 * DAY;
    private static final long KEPT = NOW + 36_000 * DAY;

    @Test
    void aRentedTrailsEndSwitchesOnTheNewestKeptTrail() {
        List<Look> rows = List.of(
                row("trail_snow", LookDao.TRAIL, NOW - 1, 300 * DAY, true), // the one on, just ended
                row("trail_leaves_2026", LookDao.TRAIL, KEPT, 200 * DAY, false),
                row("trail_twinkle_2026", LookDao.TRAIL, KEPT, 250 * DAY, false),
                row("cap_lime", LookDao.HAT, KEPT, 390 * DAY, false));
        Look on = LookRules.fallbackTrail(rows, NOW, id -> true);
        assertEquals("trail_twinkle_2026", on.id(), "the newest kept trail, by its start; never a hat's right");
        assertEquals(List.of("trail_snow"), LookRules.endedOn(rows, NOW).stream().map(Look::id).toList());
        assertEquals("trail_leaves_2026",
                LookRules.fallbackTrail(rows, NOW, id -> !id.equals("trail_twinkle_2026")).id(),
                "only a kept trail whose row is still on the counter");
    }

    @Test
    void nothingComesOnWhenNothingEndedWhileOn() {
        List<Look> liveOn = List.of(row("trail_snow", LookDao.TRAIL, NOW + DAY, 1, true),
                row("trail_leaves_2026", LookDao.TRAIL, KEPT, 1, false));
        assertNull(LookRules.fallbackTrail(liveOn, NOW, id -> true), "a live trail is on");
        List<Look> off = List.of(row("trail_snow", LookDao.TRAIL, NOW - 1, 1, false),
                row("trail_leaves_2026", LookDao.TRAIL, KEPT, 1, false));
        assertNull(LookRules.fallbackTrail(off, NOW, id -> true), "it was off when it ended: she chose off");
        List<Look> noneKept = List.of(row("trail_snow", LookDao.TRAIL, NOW - 1, 1, true));
        assertNull(LookRules.fallbackTrail(noneKept, NOW, id -> true));
        assertEquals(Set.of(), Set.copyOf(LookRules.endedOn(off, NOW)));
    }

    @Test
    void aRentedHatsEndPutsTheNewestBaggedCollectorCapOn() {
        LocalDate[] bag = new LocalDate[36];
        assertEquals(-1, LookRules.fallbackCap(bag), "no cap in the bag: the head stays empty");
        bag[5] = LocalDate.of(2026, 10, 1); // the Pumpkin Cap
        bag[20] = LocalDate.of(2026, 12, 1); // the Santa Cap
        assertEquals(20, LookRules.fallbackCap(bag), "the newest collector cap");
        bag[2] = LocalDate.of(2026, 12, 1);
        assertEquals(2, LookRules.fallbackCap(bag), "a tie: the first slot");
    }
}
