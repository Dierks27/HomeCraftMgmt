package com.dierks.homecraft.mini;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Holiday Collection rules: a holiday Mini never comes from a random draw (one test per
 * picker), never has Cards, is minted only by event code inside its window and under its cap, and
 * the Museum shows its window or "Closed".
 */
class EventMinisTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 22);
    private static final LocalDate TO = LocalDate.of(2026, 11, 29);

    private static final MiniDef TURKEY = new MiniDef("turkey_mini_2026", "Turkey Mini 2026", "Holiday Collection",
            "HOLIDAY", Rarity.EPIC, MiniType.HEAD, "", 20, 0, false, List.of(), true, FROM, TO);
    /** A holiday Mini someone tagged anyway: the tag must not let it into a pool. */
    private static final MiniDef TAGGED_HOLIDAY = new MiniDef("santa_mini_2026", "Santa Mini 2026",
            "Holiday Collection", "HOLIDAY", Rarity.EPIC, MiniType.HEAD, "", 20, 0, false, List.of("spawn"), true,
            LocalDate.of(2026, 12, 18), LocalDate.of(2026, 12, 26));
    private static final MiniDef SNOWMAN = new MiniDef("snowman", "Snowman", "Holiday Cheer", "HOLIDAY",
            Rarity.EPIC, MiniType.HEAD, "", 20, 0, false, List.of("spawn"));
    private static final MiniDef FOX = new MiniDef("fox", "Fox", "Woodland Critters", "ANIMAL",
            Rarity.COMMON, MiniType.HEAD, "", -1, 0, false, List.of("starter", "woods"));
    private static final MiniDef STAND = new MiniDef("knight", "Knight", "Adventurers", "CHARACTER",
            Rarity.EPIC, MiniType.ARMOR_STAND, "", 20, 0, false, List.of("spawn"));

    private static final List<MiniDef> CATALOG = List.of(FOX, TURKEY, SNOWMAN, TAGGED_HOLIDAY, STAND);

    private static Map<String, MiniDef> byId() {
        Map<String, MiniDef> m = new LinkedHashMap<>();
        for (MiniDef d : CATALOG) {
            m.put(d.id(), d);
        }
        return m;
    }

    private static Pack.PackDef pack(String tag) {
        Map<Rarity, Double> odds = new EnumMap<>(Rarity.class);
        for (Rarity r : Rarity.values()) {
            odds.put(r, 1.0);
        }
        return new Pack.PackDef("p", "Pack", 10, 0, 3, tag, odds, List.of());
    }

    // ---- every random picker leaves holiday Minis out --------------------------------------------

    @Test
    void anOrdinaryMiniIsDrawnAndAHolidayOneIsNot() {
        assertTrue(EventMinis.inDraws(FOX));
        assertTrue(EventMinis.inDraws(SNOWMAN), "the undated Holiday Cheer classics still drop all year");
        assertFalse(EventMinis.inDraws(TURKEY));
        assertFalse(EventMinis.inDraws(null));
        assertEquals(List.of(FOX, SNOWMAN, STAND), EventMinis.drawable(CATALOG));
    }

    @Test
    void cardPackWithBlankTagMeansEveryMiniButTheHolidayOnes() {
        assertEquals(List.of(FOX, SNOWMAN, STAND), EventMinis.packCandidates(pack(""), CATALOG));
    }

    @Test
    void cardPackWithATagNeverGivesATaggedHolidayMini() {
        assertEquals(List.of(SNOWMAN, STAND), EventMinis.packCandidates(pack("spawn"), CATALOG));
    }

    @Test
    void aHolidayMiniHasNoCardsSoPackPoolsCrateIdsAndThePrinterRefuseIt() {
        // CardService.canIssue and PrinterService.print both ask this, so a hand-picked pack pool,
        // a crate reward naming the id, or an admin's Card all give nothing for it.
        assertFalse(EventMinis.hasCards(TURKEY));
        assertFalse(EventMinis.hasCards(null));
        assertTrue(EventMinis.hasCards(FOX));
    }

    @Test
    void theBinderAlbumCountsOnlyMinisThatCanHaveCards() {
        // a holiday Mini has no Cards, so counting it would leave every binder one short for good
        assertEquals(List.of(FOX, SNOWMAN, STAND), EventMinis.album(CATALOG));
        assertEquals(List.of(), EventMinis.album(null));
    }

    @Test
    void crateCardRewardWithStarIsEveryMiniButTheHolidayOnes() {
        assertEquals(List.of(FOX, SNOWMAN, STAND), EventMinis.crateTagPool("*", CATALOG));
    }

    @Test
    void crateCardRewardWithATagNeverGivesATaggedHolidayMini() {
        assertEquals(List.of(SNOWMAN, STAND), EventMinis.crateTagPool("spawn", CATALOG));
    }

    @Test
    void rareCardPityLeavesHolidayMinisOut() {
        assertEquals(List.of(SNOWMAN, STAND), EventMinis.pityPool(CATALOG, Rarity.RARE));
        assertEquals(List.of(FOX, SNOWMAN, STAND), EventMinis.pityPool(CATALOG, Rarity.COMMON));
    }

    @Test
    void wildDropTagPoolLeavesHolidayMinisOut() {
        assertEquals(List.of(SNOWMAN, STAND), EventMinis.tagPool("spawn", CATALOG));
        assertEquals(List.of(), EventMinis.tagPool("", CATALOG), "a blank tag is no pool");
        assertEquals(List.of(), EventMinis.tagPool(null, CATALOG));
    }

    @Test
    void wildDropLootListNamingAHolidayMiniNeverDropsIt() {
        Map<String, MiniDef> ids = byId();
        List<Loot.LootEntry> entries = List.of(new Loot.LootEntry("fox", 5), new Loot.LootEntry("turkey_mini_2026", 50),
                new Loot.LootEntry("gone", 1));
        assertEquals(List.of(new Loot.LootEntry("fox", 5)), EventMinis.lootEntries(entries, ids::get),
                "the holiday Mini and an unknown id are both left out");
    }

    @Test
    void wildHuntForcedSpawnLeavesHolidayMinisOut() {
        assertEquals(List.of(FOX, SNOWMAN), EventMinis.huntPool(CATALOG, null), "head Minis only, no holiday one");
        assertEquals(List.of(SNOWMAN), EventMinis.huntPool(CATALOG, Rarity.EPIC));
    }

    // ---- the mint ----------------------------------------------------------------------------

    @Test
    void onlyEventCodeMintsAHolidayMini() {
        assertTrue(EventMinis.mintAllowed(TURKEY, true));
        assertFalse(EventMinis.mintAllowed(TURKEY, false), "the Printer, a drop, the hunt or an admin give");
        assertTrue(EventMinis.mintAllowed(FOX, false));
        assertTrue(EventMinis.mintAllowed(FOX, true));
        assertFalse(EventMinis.mintAllowed(null, true));
    }

    @Test
    void theWindowIncludesBothDays() {
        assertEquals(EventMinis.State.NOT_YET, EventMinis.state(TURKEY, LocalDate.of(2026, 11, 21)));
        assertEquals(EventMinis.State.OPEN, EventMinis.state(TURKEY, FROM));
        assertEquals(EventMinis.State.OPEN, EventMinis.state(TURKEY, LocalDate.of(2026, 11, 25)));
        assertEquals(EventMinis.State.OPEN, EventMinis.state(TURKEY, TO));
        assertEquals(EventMinis.State.CLOSED, EventMinis.state(TURKEY, LocalDate.of(2026, 11, 30)));
        assertEquals(EventMinis.State.CLOSED, EventMinis.state(TURKEY, LocalDate.of(2027, 11, 25)),
                "closed for good, not again next year");
    }

    @Test
    void aMissingOrBackToFrontWindowIsClosed() {
        assertEquals(EventMinis.State.CLOSED, EventMinis.state(null, TO, FROM));
        assertEquals(EventMinis.State.CLOSED, EventMinis.state(FROM, null, FROM));
        assertEquals(EventMinis.State.CLOSED, EventMinis.state(TO, FROM, LocalDate.of(2026, 11, 25)));
    }

    @Test
    void eventMintRefusesOutsideTheWindowAndAtTheCap() {
        assertEquals("Turkey Mini 2026 can be earned from 22 Nov 2026.",
                EventMinis.eventRefusal(TURKEY, LocalDate.of(2026, 11, 3), false));
        assertNull(EventMinis.eventRefusal(TURKEY, FROM, false));
        assertNull(EventMinis.eventRefusal(TURKEY, TO, false));
        assertEquals("Turkey Mini 2026 is closed: its event is over.",
                EventMinis.eventRefusal(TURKEY, LocalDate.of(2026, 11, 30), false));
        assertEquals("Turkey Mini 2026 is minted out.", EventMinis.eventRefusal(TURKEY, FROM, true),
                "the cap still applies");
        assertEquals("No such Mini.", EventMinis.eventRefusal(null, FROM, false));
    }

    @Test
    void aFullBagRefusesTheEventMintSoNothingIsDropped() {
        assertNull(EventMinis.eventRefusal(TURKEY, FROM, false, true));
        assertEquals("Turkey Mini 2026 needs an empty slot: make room first.",
                EventMinis.eventRefusal(TURKEY, FROM, false, false), "never dropped at her feet");
        assertEquals("Turkey Mini 2026 is closed: its event is over.",
                EventMinis.eventRefusal(TURKEY, LocalDate.of(2026, 11, 30), false, false),
                "closed says closed, not make room");
    }

    @Test
    void anOrdinaryMiniHasNoWindowForEventsButStillHasItsCap() {
        assertNull(EventMinis.eventRefusal(SNOWMAN, LocalDate.of(2030, 1, 1), false));
        assertEquals("Snowman is minted out.", EventMinis.eventRefusal(SNOWMAN, FROM, true));
    }

    // ---- the Museum --------------------------------------------------------------------------

    @Test
    void museumShowsTheWindowUntilItsLastDayThenClosed() {
        assertEquals("Earn: 22 Nov - 29 Nov 2026", EventMinis.museumLine(TURKEY, LocalDate.of(2026, 11, 3)));
        assertEquals("Earn: 22 Nov - 29 Nov 2026", EventMinis.museumLine(TURKEY, TO));
        assertEquals("Closed", EventMinis.museumLine(TURKEY, LocalDate.of(2026, 11, 30)));
        assertNull(EventMinis.museumLine(FOX, FROM), "an ordinary Mini has no such line");
    }

    @Test
    void aWindowAcrossNewYearNamesBothYears() {
        assertEquals("31 Dec 2026 - 3 Jan 2027",
                EventMinis.span(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 3)));
    }

    @Test
    void museumLineIsPlainAscii() {
        String line = EventMinis.museumLine(TURKEY, FROM);
        assertTrue(line.chars().allMatch(c -> c >= 32 && c < 127), "Bedrock shows it: " + line);
    }

    // ---- config ------------------------------------------------------------------------------

    private static Map<String, Object> row(Object eventOnly, Object from, Object to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Turkey Mini 2026");
        if (eventOnly != null) {
            m.put("event_only", eventOnly);
        }
        if (from != null) {
            m.put("earn_from", from);
        }
        if (to != null) {
            m.put("earn_to", to);
        }
        return m;
    }

    @Test
    void theHandoffRowParsesWithNoWarn() {
        List<String> warns = new ArrayList<>();
        EventMinis.Window w = EventMinis.readWindow(row(true, "2026-11-22", "2026-11-29"), "turkey_mini_2026",
                warns::add);
        assertEquals(new EventMinis.Window(true, FROM, TO), w);
        assertEquals(List.of(), warns);
    }

    @Test
    void anOrdinaryRowIsNotAHolidayMini() {
        List<String> warns = new ArrayList<>();
        assertEquals(EventMinis.Window.NONE, EventMinis.readWindow(row(null, null, null), "fox", warns::add));
        assertEquals(List.of(), warns);
    }

    @Test
    void eventOnlyWrittenAsTextOrAnUnquotedDateStillReads() {
        java.util.Date yamlDate = java.util.Date.from(FROM.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        EventMinis.Window w = EventMinis.readWindow(row("true", yamlDate, "2026-11-29"), "t", null);
        assertEquals(new EventMinis.Window(true, FROM, TO), w);
    }

    @Test
    void aBadDateIsOneWarnAndLeavesItClosed() {
        List<String> warns = new ArrayList<>();
        EventMinis.Window w = EventMinis.readWindow(row(true, "22/11/2026", "nope"), "turkey_mini_2026", warns::add);
        assertEquals(1, warns.size(), "one WARN for the Mini, not one per key: " + warns);
        assertTrue(warns.get(0).contains("turkey_mini_2026") && warns.get(0).contains("earn_from")
                && warns.get(0).contains("22/11/2026"), warns.get(0));
        assertEquals(EventMinis.State.CLOSED, EventMinis.state(w.from(), w.to(), FROM));
    }

    @Test
    void backToFrontDatesAreOneWarn() {
        List<String> warns = new ArrayList<>();
        EventMinis.readWindow(row(true, "2026-11-29", "2026-11-22"), "t", warns::add);
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("before"), warns.get(0));
    }

    @Test
    void eventOnlyWithoutDatesIsOneWarn() {
        List<String> warns = new ArrayList<>();
        EventMinis.readWindow(row(true, "2026-11-22", null), "t", warns::add);
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("earn_to"), warns.get(0));
    }

    @Test
    void writeThenReadGivesTheSameWindow() {
        Map<String, Object> out = new LinkedHashMap<>();
        EventMinis.writeWindow(TURKEY, out);
        assertEquals(Map.of("event_only", true, "earn_from", "2026-11-22", "earn_to", "2026-11-29"), out);
        assertEquals(new EventMinis.Window(true, FROM, TO), EventMinis.readWindow(out, "t", null));
    }

    @Test
    void anOrdinaryMiniWritesNoHolidayKeys() {
        Map<String, Object> out = new LinkedHashMap<>();
        EventMinis.writeWindow(FOX, out);
        assertTrue(out.isEmpty());
    }

    @Test
    void theStudioDraftKeepsTheHolidayKeys() {
        MiniDef back = MiniDraft.from(TURKEY).toDef();
        assertEquals(TURKEY, back);
    }
}
