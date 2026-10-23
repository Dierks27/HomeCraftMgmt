package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.util.GameClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collectors' seasons (HALLOWEEN-SPEC D5): absolute local dates, both ends included, read on the
 * players' clock (America/Chicago as shipped), so the cap leaves the counter at midnight going into
 * 1 November there, and the trail at midnight going into 1 December, after the clocks fall back. The
 * Christmas collectors (SHOP-V2-SPEC §5.7) open at midnight going into 1 December; the Santa Cap leaves
 * at midnight going into 1 January, Twinkle at midnight going into 7 January.
 */
class CollectorSeasonTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static Prize cap() {
        return Collectors.ROWS.get(0);
    }

    private static Prize trail() {
        return Collectors.ROWS.get(1);
    }

    private static LocalDate today(String instant) {
        return new GameClock(CHICAGO, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)).today();
    }

    @Test
    void theCapIsOnSaleForTheWholeOfOctober() {
        assertFalse(cap().collector().inSeason(LocalDate.of(2026, 9, 30)));
        assertTrue(cap().collector().inSeason(LocalDate.of(2026, 10, 1)));
        assertTrue(cap().collector().inSeason(LocalDate.of(2026, 10, 31)));
        assertFalse(cap().collector().inSeason(LocalDate.of(2026, 11, 1)));
        assertFalse(cap().collector().inSeason(LocalDate.of(2027, 10, 15)), "the 2026 cap never comes back");
        assertFalse(cap().collector().inSeason(null));
    }

    @Test
    void theTrailIsOnSaleThroughNovember() {
        assertTrue(trail().collector().inSeason(LocalDate.of(2026, 10, 1)));
        assertTrue(trail().collector().inSeason(LocalDate.of(2026, 11, 30)));
        assertFalse(trail().collector().inSeason(LocalDate.of(2026, 12, 1)));
        assertFalse(trail().collector().inSeason(LocalDate.of(2026, 9, 30)));
    }

    @Test
    void theCapEndsAtMidnightInChicago() {
        assertTrue(PrizeService.onSale(cap(), true, today("2026-11-01T04:59:59Z")), "23:59:59 on 31 Oct, CDT");
        assertFalse(PrizeService.onSale(cap(), true, today("2026-11-01T05:00:00Z")), "00:00 on 1 Nov, CDT");
        assertFalse(PrizeService.onSale(cap(), true, today("2026-10-01T04:59:59Z")), "23:59:59 on 30 Sep");
        assertTrue(PrizeService.onSale(cap(), true, today("2026-10-01T05:00:00Z")), "00:00 on 1 Oct");
    }

    @Test
    void theTrailEndsAtMidnightInChicagoAfterTheFallBack() {
        // 1 Dec is Central Standard Time (UTC-6), so its midnight is 06:00Z, not 05:00Z.
        assertTrue(PrizeService.onSale(trail(), true, today("2026-12-01T05:59:59Z")));
        assertFalse(PrizeService.onSale(trail(), true, today("2026-12-01T06:00:00Z")));
    }

    private static Prize row(String id) {
        for (Prize p : Collectors.ROWS) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        throw new AssertionError("no collector " + id);
    }

    /** Christmas on the players' clock: both open at midnight going into 1 Dec, Central Standard Time. */
    @Test
    void christmasStartsAtMidnightInChicago() {
        for (String id : new String[] {"cap_santa_2026", "trail_twinkle_2026"}) {
            assertFalse(PrizeService.onSale(row(id), true, today("2026-12-01T05:59:59Z")), id + ": 23:59:59 on 30 Nov");
            assertTrue(PrizeService.onSale(row(id), true, today("2026-12-01T06:00:00Z")), id + ": 00:00 on 1 Dec");
        }
    }

    @Test
    void theSantaCapEndsAtMidnightGoingIntoTheNewYear() {
        Prize santa = row("cap_santa_2026");
        assertTrue(PrizeService.onSale(santa, true, today("2027-01-01T05:59:59Z")), "23:59:59 on 31 Dec");
        assertFalse(PrizeService.onSale(santa, true, today("2027-01-01T06:00:00Z")), "00:00 on 1 Jan");
        assertTrue(PrizeService.onSale(row("trail_twinkle_2026"), true, today("2027-01-01T06:00:00Z")),
                "Twinkle goes on into January");
    }

    @Test
    void twinkleEndsAtMidnightGoingIntoTheSeventhOfJanuary() {
        Prize twinkle = row("trail_twinkle_2026");
        assertTrue(PrizeService.onSale(twinkle, true, today("2027-01-07T05:59:59Z")), "23:59:59 on 6 Jan");
        assertFalse(PrizeService.onSale(twinkle, true, today("2027-01-07T06:00:00Z")), "00:00 on 7 Jan");
    }

    @Test
    void onSaleFalseHidesThemInSeason() {
        LocalDate halloween = LocalDate.of(2026, 10, 31);
        assertTrue(PrizeService.onSale(cap(), true, halloween));
        assertFalse(PrizeService.onSale(cap(), false, halloween));
        assertFalse(PrizeService.onSale(trail(), false, halloween));
        LocalDate christmas = LocalDate.of(2026, 12, 25);
        assertTrue(PrizeService.onSale(row("cap_santa_2026"), true, christmas));
        assertFalse(PrizeService.onSale(row("cap_santa_2026"), false, christmas));
        assertFalse(PrizeService.onSale(row("trail_twinkle_2026"), false, christmas));
    }
}
