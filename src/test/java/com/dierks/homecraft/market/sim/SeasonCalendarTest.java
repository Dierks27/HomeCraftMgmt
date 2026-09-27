package com.dierks.homecraft.market.sim;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calendar seasons (spec §9.1).
 *
 * <p>Pinned here: on ship day (2026-09-27) Harvest Time is fully on, so wheat is exactly −4%
 * and its window closes at local midnight after Oct 31; a season fades in over its ramp days
 * (weight 0, ½, 1 on days 0, 1, 2) and out the same way; a window whose {@code from} is after
 * its {@code to} wraps the year; overlapping seasons add up (Jan 3: oak +4 − 3 = +1%);
 * {@code "*"} reaches every item; {@code 02-29} is Feb 28 in a year without one; the SEASON
 * tag carries the year {@code from} fell in. And the config side: the shipped rows parse to
 * {@link SeasonCalendar#SHIPPED}, a bad {@code MM-DD} drops the row, an unknown item id is
 * only reported, and no percent gets past the 4.5% predictable cap.
 */
class SeasonCalendarTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final List<Season> SHIPPED = SeasonCalendar.SHIPPED;
    private static final long DAY = 86_400_000L;
    private static final double EPS = 1e-12;

    private static long at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(CHICAGO).toInstant().toEpochMilli();
    }

    private static double effect(String item, String localDateTime) {
        return SeasonCalendar.effect(SHIPPED, item, at(localDateTime), CHICAGO, 2);
    }

    private static Season shipped(String id) {
        return SHIPPED.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static Map<String, Object> row(String id, String from, String to, Map<String, Object> percent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", id);
        m.put("from", from);
        m.put("to", to);
        m.put("percent", percent);
        m.put("headline", "Something nice is happening!");
        return m;
    }

    // ---- ship day -----------------------------------------------------------------------

    @Test
    void onShipDayWheatIsExactlyFourPercentDown() {
        for (String t : List.of("2026-09-27T00:00", "2026-09-27T12:00", "2026-09-27T23:59")) {
            assertEquals(-0.04, effect("wheat", t), EPS, t);
        }
        assertEquals(0.0, effect("iron_ingot", "2026-09-27T12:00"), "Back to School ended on Sep 10");
        assertEquals(0.0, effect("oak_log", "2026-09-27T12:00"));

        List<SeasonCalendar.Active> active = SeasonCalendar.active(SHIPPED, at("2026-09-27T12:00"), CHICAGO, 2);
        assertEquals(1, active.size());
        SeasonCalendar.Active harvest = active.get(0);
        assertEquals("harvest_time", harvest.season().id());
        assertEquals(1.0, harvest.weight());
        assertEquals(LocalDate.of(2026, 9, 15), harvest.start());
        assertEquals(LocalDate.of(2026, 10, 31), harvest.end(), "end is the last day, inclusive");
        assertEquals(at("2026-09-15T00:00"), harvest.startsAt(CHICAGO));
        assertEquals(1_793_509_200_000L, harvest.endsAt(CHICAGO), "local midnight after Oct 31 (CDT)");
        assertEquals("harvest_time:2026", harvest.tag());
        assertEquals(-0.04, harvest.effect("wheat"), EPS);
        assertEquals(0.0, harvest.effect("oak_log"));
    }

    // ---- ramps --------------------------------------------------------------------------

    @Test
    void aSeasonFadesInOverItsRampDaysAndOutTheSameWay() {
        Season harvest = shipped("harvest_time");
        long start = at("2026-09-15T00:00");
        long end = at("2026-11-01T00:00");

        assertEquals(0.0, weight(harvest, start), "day 0: just opened");
        assertEquals(0.15625, weight(harvest, start + DAY / 2), EPS, "smooth(0.25)");
        assertEquals(0.5, weight(harvest, start + DAY), EPS, "day 1: halfway");
        assertEquals(1.0, weight(harvest, start + 2 * DAY), EPS, "day 2: full");
        assertEquals(1.0, weight(harvest, start + 20 * DAY), EPS);
        assertEquals(0.5, weight(harvest, end - DAY), EPS, "one day before the end: halfway out");
        assertEquals(0.0, SeasonCalendar.effect(SHIPPED, "wheat", end, CHICAGO, 2), "over at local midnight");
        assertEquals(0.0, SeasonCalendar.effect(SHIPPED, "wheat", start - 1, CHICAGO, 2), "not yet open");

        assertEquals(-0.02, SeasonCalendar.effect(SHIPPED, "wheat", start + DAY, CHICAGO, 2), EPS);
        assertTrue(SeasonCalendar.active(SHIPPED, start, CHICAGO, 2).isEmpty(), "weight 0 is not active");
        assertEquals(1, SeasonCalendar.active(SHIPPED, start + 60_000, CHICAGO, 2).size());
    }

    @Test
    void zeroRampDaysSwitchesASeasonFullyOnAndOff() {
        Season harvest = shipped("harvest_time");
        assertEquals(1.0, SeasonCalendar.window(harvest, at("2026-09-15T00:00"), CHICAGO, 0).orElseThrow().weight());
        assertEquals(1.0, SeasonCalendar.window(harvest, at("2026-10-31T23:59"), CHICAGO, 0).orElseThrow().weight());
        assertTrue(SeasonCalendar.window(harvest, at("2026-11-01T00:00"), CHICAGO, 0).isEmpty());
    }

    private static double weight(Season s, long t) {
        return SeasonCalendar.window(s, t, CHICAGO, 2).map(SeasonCalendar.Active::weight).orElse(0.0);
    }

    // ---- wrap, overlap, "*" -------------------------------------------------------------

    @Test
    void aWindowAfterItsEndWrapsTheYear() {
        assertEquals(0.04, effect("oak_log", "2027-01-15T12:00"), EPS, "Cozy Winter, Dec 1 → Feb 28");
        assertEquals(0.04, effect("oak_log", "2026-12-15T12:00"), EPS);
        assertEquals(0.03375, effect("oak_log", "2027-02-27T12:00"), EPS, "fading out: smooth(0.75)");
        assertEquals(0.0, effect("oak_log", "2027-03-01T00:00"), "over after Feb 28");

        SeasonCalendar.Active cozy = SeasonCalendar.window(shipped("cozy_winter"), at("2027-01-15T12:00"), CHICAGO, 2)
                .orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 1), cozy.start());
        assertEquals(LocalDate.of(2027, 2, 28), cozy.end());
    }

    @Test
    void overlappingSeasonsAddUp() {
        // Jan 3: New Year (* −3) and Cozy Winter (oak_log +4), both at full strength.
        assertEquals(0.01, effect("oak_log", "2027-01-03T12:00"), EPS);
        assertEquals(2, SeasonCalendar.active(SHIPPED, at("2027-01-03T12:00"), CHICAGO, 2).size());
        // Dec 20: Cozy Winter for oak, Gift Season for gold and diamond.
        assertEquals(0.04, effect("oak_log", "2026-12-20T12:00"), EPS);
        assertEquals(0.04, effect("diamond", "2026-12-20T12:00"), EPS);
    }

    @Test
    void aStarReachesEveryItemAndAnItemsOwnEntryWinsInsideOneSeason() {
        assertEquals(-0.03, effect("diamond", "2027-01-03T12:00"), EPS);
        assertEquals(-0.03, effect("wheat", "2027-01-03T12:00"), EPS);
        assertEquals(-0.03, effect("anything_at_all", "2027-01-03T12:00"), EPS);

        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("*", -3.0);
        pct.put("oak_log", 2.0);
        Season mixed = new Season("mixed", "Mixed", MonthDay.of(1, 1), MonthDay.of(1, 31), pct, "Hello!");
        assertEquals(0.02, SeasonCalendar.effect(List.of(mixed), "oak_log", at("2027-01-15T12:00"), CHICAGO, 2), EPS);
        assertEquals(-0.03, SeasonCalendar.effect(List.of(mixed), "wheat", at("2027-01-15T12:00"), CHICAGO, 2), EPS);
    }

    // ---- Feb 29 and tags ----------------------------------------------------------------

    @Test
    void february29IsFebruary28InAYearWithoutOne() {
        Season leapStart = new Season("leap", "Leap", MonthDay.of(2, 29), MonthDay.of(3, 5),
                Map.of("wheat", 2.0), "Leap!");
        SeasonCalendar.Active in2027 = SeasonCalendar.window(leapStart, at("2027-02-28T12:00"), CHICAGO, 0).orElseThrow();
        assertEquals(LocalDate.of(2027, 2, 28), in2027.start());
        assertTrue(SeasonCalendar.window(leapStart, at("2028-02-28T12:00"), CHICAGO, 0).isEmpty(),
                "2028 has a Feb 29, so it starts then");
        assertEquals(LocalDate.of(2028, 2, 29),
                SeasonCalendar.window(leapStart, at("2028-02-29T12:00"), CHICAGO, 0).orElseThrow().start());

        Season leapEnd = new Season("leap_end", "Leap End", MonthDay.of(2, 20), MonthDay.of(2, 29),
                Map.of("wheat", 2.0), "Leap!");
        assertEquals(LocalDate.of(2027, 2, 28),
                SeasonCalendar.window(leapEnd, at("2027-02-25T12:00"), CHICAGO, 0).orElseThrow().end());
        assertTrue(SeasonCalendar.window(leapEnd, at("2027-03-01T00:00"), CHICAGO, 0).isEmpty());
        assertEquals(0.02, SeasonCalendar.effect(List.of(leapEnd), "wheat", at("2028-02-29T12:00"), CHICAGO, 0), EPS);

        assertEquals(MonthDay.of(2, 29), SeasonCalendar.monthDay("02-29"));
    }

    @Test
    void theTagCarriesTheYearFromFellIn() {
        Season harvest = shipped("harvest_time");
        Season cozy = shipped("cozy_winter");
        assertEquals("harvest_time:2026", SeasonCalendar.tag(harvest, at("2026-09-27T12:00"), CHICAGO));
        assertEquals("harvest_time:2027", SeasonCalendar.tag(harvest, at("2027-10-01T12:00"), CHICAGO));
        assertEquals("cozy_winter:2026", SeasonCalendar.tag(cozy, at("2026-12-15T12:00"), CHICAGO));
        assertEquals("cozy_winter:2026", SeasonCalendar.tag(cozy, at("2027-01-15T12:00"), CHICAGO),
                "January belongs to the winter that began in December");
        assertEquals("cozy_winter:2027", SeasonCalendar.tag(cozy, at("2027-12-05T12:00"), CHICAGO));

        for (String t : List.of("2027-01-03T12:00", "2026-12-20T12:00", "2026-09-27T12:00")) {
            for (SeasonCalendar.Active a : SeasonCalendar.active(SHIPPED, at(t), CHICAGO, 2)) {
                assertEquals(SeasonCalendar.tag(a.season(), at(t), CHICAGO), a.tag(), t + " " + a.season().id());
            }
        }
    }

    // ---- config -------------------------------------------------------------------------

    @Test
    void theShippedRowsParseToTheShippedSeasons() {
        List<Map<?, ?>> rows = new ArrayList<>();
        for (Season s : SHIPPED) {
            Map<String, Object> pct = new LinkedHashMap<>();
            // YAML hands whole numbers over as Integer.
            s.percent().forEach((k, v) -> pct.put(k, (int) Math.round(v)));
            Map<String, Object> r = row(s.id(), String.format("%02d-%02d", s.from().getMonthValue(), s.from().getDayOfMonth()),
                    String.format("%02d-%02d", s.to().getMonthValue(), s.to().getDayOfMonth()), pct);
            r.put("name", s.name());
            r.put("headline", s.headline());
            rows.add(r);
        }
        List<String> warnings = new ArrayList<>();
        List<Season> parsed = SeasonCalendar.parse(rows, 4.5, warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(SHIPPED, parsed);
        assertEquals(7, parsed.size());
    }

    @Test
    void aBadMonthDayDropsThatSeasonOnly() {
        List<Map<?, ?>> rows = new ArrayList<>();
        rows.add(row("bad_month", "13-01", "01-05", Map.of("wheat", 2)));
        rows.add(row("bad_day", "02-30", "03-05", Map.of("wheat", 2)));
        rows.add(row("slashes", "9/15", "10/31", Map.of("wheat", 2)));
        rows.add(row("blank", "", "10-31", Map.of("wheat", 2)));
        Map<String, Object> missing = row("missing", "01-01", "01-02", Map.of("wheat", 2));
        missing.remove("to");
        rows.add(missing);
        rows.add(row("good", "9-15", "10-31", Map.of("wheat", -2)));

        List<String> warnings = new ArrayList<>();
        List<Season> parsed = SeasonCalendar.parse(rows, warnings::add);
        assertEquals(1, parsed.size());
        assertEquals("good", parsed.get(0).id());
        assertEquals(MonthDay.of(9, 15), parsed.get(0).from(), "one-digit months are fine");
        assertEquals(5, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.contains("MM-DD")), warnings.toString());
    }

    @Test
    void unknownItemIdsAreReportedAndOtherwiseIgnored() {
        List<Map<?, ?>> rows = new ArrayList<>();
        Map<String, Object> pct = new LinkedHashMap<>();
        pct.put("wheat", -4);
        pct.put("NOT_AN_ITEM", 3);
        pct.put("*", 1);
        rows.add(row("harvest", "09-15", "10-31", pct));
        List<Season> parsed = SeasonCalendar.parse(rows, s -> { });

        assertEquals(List.of("not_an_item"), SeasonCalendar.unknownIds(parsed, Set.of("wheat", "oak_log")),
                "ids are lower-cased; \"*\" is never unknown");
        assertEquals(-0.04, SeasonCalendar.effect(parsed, "wheat", at("2026-09-27T12:00"), CHICAGO, 2), EPS);
        assertEquals(0.01, SeasonCalendar.effect(parsed, "oak_log", at("2026-09-27T12:00"), CHICAGO, 2), EPS);
        assertEquals(List.of(), SeasonCalendar.unknownIds(SHIPPED,
                Set.of("cobblestone", "oak_log", "wheat", "iron_ingot", "gold_ingot", "diamond")));
    }

    @Test
    void noPercentGetsPastThePredictableCap() {
        List<Map<?, ?>> rows = new ArrayList<>();
        Map<String, Object> pct = new LinkedHashMap<>();
        pct.put("wheat", 9);
        pct.put("oak_log", "-20");
        pct.put("iron_ingot", "lots");
        pct.put("diamond", 1.5);
        rows.add(row("wild", "01-01", "12-31", pct));

        List<String> warnings = new ArrayList<>();
        Season wild = SeasonCalendar.parse(rows, 10.0, warnings::add).get(0);
        assertEquals(4.5, wild.percent().get("wheat"), "config may narrow, never widen");
        assertEquals(-4.5, wild.percent().get("oak_log"));
        assertEquals(1.5, wild.percent().get("diamond"));
        assertFalse(wild.percent().containsKey("iron_ingot"));
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("market.sim.seasons.list[wild].percent.wheat"), warnings.get(0));

        Season narrow = SeasonCalendar.parse(rows, 2.0, s -> { }).get(0);
        assertEquals(2.0, narrow.percent().get("wheat"));
        assertEquals(-2.0, narrow.percent().get("oak_log"));
    }

    @Test
    void rowsWithoutAUsableIdOrPercentAreDroppedAndUnsafeTextIsReplaced() {
        List<Map<?, ?>> rows = new ArrayList<>();
        rows.add(row("", "01-01", "01-02", Map.of("wheat", 1)));
        rows.add(row("Bad Id!", "01-01", "01-02", Map.of("wheat", 1)));
        rows.add(row("empty", "01-01", "01-02", Map.of()));
        rows.add(row("twice", "01-01", "01-02", Map.of("wheat", 1)));
        rows.add(row("twice", "02-01", "02-02", Map.of("wheat", 1)));
        Map<String, Object> scary = row("scary", "03-01", "03-02", Map.of("wheat", 1));
        scary.put("name", "Spooky");
        scary.put("headline", "A storm is coming! Buy wheat!");
        rows.add(scary);
        Map<String, Object> bare = row("bare", "04-01", "04-02", Map.of("wheat", 1));
        bare.remove("name");
        bare.remove("headline");
        rows.add(bare);

        List<String> warnings = new ArrayList<>();
        List<Season> parsed = SeasonCalendar.parse(rows, warnings::add);
        assertEquals(List.of("twice", "scary", "bare"), parsed.stream().map(Season::id).toList());
        assertEquals("Spooky is here!", parsed.get(1).headline(), "a banned word is never shown");
        assertEquals("bare", parsed.get(2).name());
        assertEquals("bare is here!", parsed.get(2).headline());
        assertEquals(5, warnings.size(), warnings.toString());
        assertEquals(List.of(), SeasonCalendar.parse(null, s -> { }));
    }
}
