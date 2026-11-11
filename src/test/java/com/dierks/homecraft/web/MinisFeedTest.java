package com.dierks.homecraft.web;

import java.time.ZoneId;
import java.time.LocalDate;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniType;
import com.dierks.homecraft.mini.Rarity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /api/minis} feed the website's Minis page reads.
 *
 * <p>Pinned: when a Mini counts as sold out; the exact JSON for a capped Mini with a skin and an
 * uncapped one without; the normalising the site relies on (upper-case category, {@code MISC}
 * for none, colour codes gone, {@code -1} for uncapped, a missing count is 0); catalog order.
 *
 * <p>And privacy: each entry carries exactly the documented keys and nothing else — no owner,
 * holder, UUID, balance or provenance, and not the Mini's price or tags either.
 */
class MinisFeedTest {

    private static final long T = 1_790_000_000_000L;

    /** "Golden Idol", exactly as src/main/resources/config.yml ships it (cap 5). */
    private static final String IDOL_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWU1YmY2MDM2Mzg2YjJlYjAwZmMyZTYzNWU1NzViNzRlMmI1YTBjNjljYmI1MzY5MWFlZGZkYjZhMGMzNjAwOSJ9fX0=";

    private static final Set<String> KEYS = Set.of("id", "name", "rarity", "category", "series", "cap",
            "printed", "soldOut", "skin");

    private static MiniDef idol() {
        return new MiniDef("golden_idol", "&6Golden Idol", "&eLegendary Relics", "Symbol", Rarity.LEGENDARY,
                MiniType.HEAD, IDOL_TEXTURE, 5, 250.0, false, List.of("mining"));
    }

    private static MiniDef amethyst() {
        return new MiniDef("blue_amethyst", "Blue Amethyst", "Gems", "misc", Rarity.COMMON,
                MiniType.HEAD, "", -1, 12.5, true, List.of("starter"));
    }

    private static MiniDef mini(String id, String category, long cap) {
        return new MiniDef(id, id, "S", category, Rarity.RARE, MiniType.HEAD, "", cap, 1, false);
    }

    private static List<JsonObject> minis(String json) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : MarketFeedTest.strict(json).getAsJsonObject().getAsJsonArray("minis")) {
            out.add(e.getAsJsonObject());
        }
        return out;
    }

    // ---- soldOut ------------------------------------------------------------------------

    @Test
    void soldOutOnlyWhenCappedAndEveryCopyPrinted() {
        assertFalse(MinisFeed.soldOut(-1, Long.MAX_VALUE), "uncapped never sells out");
        assertFalse(MinisFeed.soldOut(-1, 0));
        assertFalse(MinisFeed.soldOut(-7, 10), "any negative cap is uncapped");
        assertTrue(MinisFeed.soldOut(0, 0), "a cap of 0 is sold out from the start");
        assertFalse(MinisFeed.soldOut(5, 4));
        assertTrue(MinisFeed.soldOut(5, 5));
        assertTrue(MinisFeed.soldOut(5, 6), "over the cap (an admin mint) is still sold out");
    }

    // ---- the exact shape ----------------------------------------------------------------

    @Test
    void aCappedMiniWithASkinAndAnUncappedOneWithout() {
        String json = MinisFeed.json(T, List.of(idol(), amethyst()), Map.of("golden_idol", 5L, "blue_amethyst", 12L));
        assertEquals("{\"generatedAt\":1790000000000,\"minis\":["
                + "{\"id\":\"golden_idol\",\"name\":\"Golden Idol\",\"rarity\":\"LEGENDARY\",\"category\":\"SYMBOL\","
                + "\"series\":\"Legendary Relics\",\"cap\":5,\"printed\":5,\"soldOut\":true,"
                + "\"skin\":\"https://textures.minecraft.net/texture/"
                + "1e5bf6036386b2eb00fc2e635e575b74e2b5a0c69cbb53691aedfdb6a0c36009\"},"
                + "{\"id\":\"blue_amethyst\",\"name\":\"Blue Amethyst\",\"rarity\":\"COMMON\",\"category\":\"MISC\","
                + "\"series\":\"Gems\",\"cap\":-1,\"printed\":12,\"soldOut\":false}]}", json);
    }

    @Test
    void anEmptyOrMissingCatalogIsAnEmptyList() {
        String expected = "{\"generatedAt\":1790000000000,\"minis\":[]}";
        assertEquals(expected, MinisFeed.json(T, List.of(), Map.of()));
        assertEquals(expected, MinisFeed.json(T, null, null));
        List<MiniDef> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals(expected, MinisFeed.json(T, withNull, Map.of()));
    }

    @Test
    void catalogOrderIsKept() {
        List<MiniDef> defs = List.of(mini("zebra", "ANIMAL", -1), mini("apple", "FOOD", -1), mini("moose", "ANIMAL", -1));
        List<JsonObject> out = minis(MinisFeed.json(T, defs, Map.of()));
        assertEquals(List.of("zebra", "apple", "moose"),
                out.stream().map(o -> o.get("id").getAsString()).toList());
    }

    // ---- normalising --------------------------------------------------------------------

    @Test
    void aMissingPrintedCountIsZero() {
        Map<String, Long> withNullValue = new HashMap<>();
        withNullValue.put("a", null);
        withNullValue.put("b", -3L);

        List<MiniDef> defs = List.of(mini("a", "FOOD", 3), mini("b", "FOOD", 3), mini("c", "FOOD", 3));
        for (Map<String, Long> printed : List.of(Map.<String, Long>of(), withNullValue)) {
            for (JsonObject m : minis(MinisFeed.json(T, defs, printed))) {
                assertEquals(0, m.get("printed").getAsLong(), m.toString());
                assertFalse(m.get("soldOut").getAsBoolean(), m.toString());
            }
        }
        for (JsonObject m : minis(MinisFeed.json(T, defs, null))) {
            assertEquals(0, m.get("printed").getAsLong(), "no counts at all: " + m);
        }
    }

    @Test
    void aMiniWithoutAnIdStillWritesAndCountsZero() {
        MiniDef noId = new MiniDef(null, "Nameless", "S", "FOOD", Rarity.COMMON, MiniType.HEAD, "", 1, 1, false);
        JsonObject m = minis(MinisFeed.json(T, List.of(noId), Map.of("x", 1L))).get(0);
        assertEquals("", m.get("id").getAsString());
        assertEquals(0, m.get("printed").getAsLong());
    }

    @Test
    void theCategoryIsUpperCaseAndMiscWhenBlank() {
        List<MiniDef> defs = List.of(mini("a", "animal", -1), mini("b", "Vehicle", -1), mini("c", "", -1),
                mini("d", "   ", -1), mini("e", null, -1), mini("f", "&aFood", -1), mini("g", "&r", -1));
        List<String> categories = minis(MinisFeed.json(T, defs, Map.of())).stream()
                .map(o -> o.get("category").getAsString()).toList();
        assertEquals(List.of("ANIMAL", "VEHICLE", "MISC", "MISC", "MISC", "FOOD", "MISC"), categories);
    }

    @Test
    void nameAndSeriesLoseTheirColourCodes() {
        MiniDef def = new MiniDef("x", "&6&lShiny §bFox &r", "§5Night &oCritters", "ANIMAL", Rarity.EPIC,
                MiniType.HEAD, "", -1, 1, false);
        JsonObject m = minis(MinisFeed.json(T, List.of(def), Map.of())).get(0);
        assertEquals("Shiny Fox", m.get("name").getAsString());
        assertEquals("Night Critters", m.get("series").getAsString());
        assertEquals("EPIC", m.get("rarity").getAsString());
    }

    @Test
    void anyNegativeCapIsWrittenAsMinusOne() {
        JsonObject m = minis(MinisFeed.json(T, List.of(mini("x", "FOOD", -42)), Map.of("x", 7L))).get(0);
        assertEquals(-1, m.get("cap").getAsLong());
        assertFalse(m.get("soldOut").getAsBoolean());
    }

    @Test
    void aMissingRarityIsCommon() {
        MiniDef def = new MiniDef("x", "X", "S", "FOOD", null, MiniType.HEAD, "", -1, 1, false);
        assertEquals("COMMON", minis(MinisFeed.json(T, List.of(def), Map.of())).get(0).get("rarity").getAsString());
    }

    @Test
    void anUnusableTextureLeavesTheSkinKeyOut() {
        MiniDef broken = new MiniDef("x", "X", "S", "FOOD", Rarity.COMMON, MiniType.HEAD, "!!garbage!!", -1, 1, false);
        String json = MinisFeed.json(T, List.of(broken, amethyst()), Map.of());
        assertFalse(json.contains("\"skin\""), json);
    }

    // ---- privacy ------------------------------------------------------------------------

    @Test
    void entriesCarryExactlyTheDocumentedKeysAndNoPlayerData() {
        Map<String, Long> printed = new LinkedHashMap<>();
        printed.put("golden_idol", 3L);
        printed.put("blue_amethyst", 1L);
        String json = MinisFeed.json(T, List.of(idol(), amethyst()), printed);

        JsonObject root = MarketFeedTest.strict(json).getAsJsonObject();
        assertEquals(Set.of("generatedAt", "minis"), root.keySet());

        List<JsonObject> out = minis(json);
        assertEquals(KEYS, out.get(0).keySet(), "the capped Mini with a skin");
        Set<String> withoutSkin = new HashSet<>(KEYS);
        withoutSkin.remove("skin");
        assertEquals(withoutSkin, out.get(1).keySet(), "the uncapped Mini without one");

        for (String forbidden : List.of("owner", "owners", "uuid", "holder", "holders", "balance", "provenance",
                "price", "tags", "texture")) {
            assertFalse(json.contains("\"" + forbidden + "\""), "the feed must never carry \"" + forbidden + "\"");
        }
        assertFalse(json.contains("250"), "not even the mint price's value");
        assertFalse(json.contains("mining"), "nor a tag's");
    }

    // ---- holiday Minis (0.38.8) ------------------------------------------------------------

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** The Turkey Mini 2026 as config.yml ships it (no texture here). */
    private static MiniDef turkey(LocalDate from, LocalDate to) {
        return new MiniDef("turkey_mini_2026", "Turkey Mini 2026", "Holiday Collection", "HOLIDAY", Rarity.EPIC,
                MiniType.HEAD, "", 20, 0, false, List.of(), true, from, to);
    }

    @Test
    void aHolidayMiniSaysSoWithItsFirstAndLastMillisecond() {
        MiniDef turkey = turkey(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 29));
        assertEquals("{\"generatedAt\":1790000000000,\"minis\":["
                + "{\"id\":\"turkey_mini_2026\",\"name\":\"Turkey Mini 2026\",\"rarity\":\"EPIC\",\"category\":\"HOLIDAY\","
                + "\"series\":\"Holiday Collection\",\"cap\":20,\"printed\":3,\"soldOut\":false,"
                + "\"eventOnly\":true,\"earnFrom\":1795327200000,\"earnTo\":1796018399999},"
                + "{\"id\":\"blue_amethyst\",\"name\":\"Blue Amethyst\",\"rarity\":\"COMMON\",\"category\":\"MISC\","
                + "\"series\":\"Gems\",\"cap\":-1,\"printed\":0,\"soldOut\":false}]}",
                MinisFeed.json(T, List.of(turkey, amethyst()), Map.of("turkey_mini_2026", 3L), CHICAGO),
                "22 Nov 00:00 to 29 Nov 23:59:59.999 in Chicago; the ordinary Mini beside it is as it always was");

        JsonObject withSkin = minis(MinisFeed.json(T, List.of(new MiniDef("t", "T", "S", "HOLIDAY", Rarity.EPIC,
                MiniType.HEAD, IDOL_TEXTURE, 20, 0, false, List.of(), true, LocalDate.of(2026, 11, 22),
                LocalDate.of(2026, 11, 22))), Map.of(), CHICAGO)).get(0);
        assertEquals(List.of("id", "name", "rarity", "category", "series", "cap", "printed", "soldOut", "skin",
                "eventOnly", "earnFrom", "earnTo"), new ArrayList<>(withSkin.keySet()), "after skin");
        assertEquals(86_400_000L - 1, withSkin.get("earnTo").getAsLong() - withSkin.get("earnFrom").getAsLong(),
                "a one-day window is that whole day");
    }

    @Test
    void anOrdinaryMiniHasNoneOfTheHolidayKeys() {
        String old = MinisFeed.json(T, List.of(idol(), amethyst()), Map.of("golden_idol", 5L));
        assertEquals(old, MinisFeed.json(T, List.of(idol(), amethyst()), Map.of("golden_idol", 5L), CHICAGO),
                "byte for byte the feed as it was");
        assertFalse(old.contains("eventOnly") || old.contains("earnFrom") || old.contains("earnTo"));
        // dates on a Mini that is not event_only are ignored, as the plugin ignores them
        MiniDef dated = new MiniDef("d", "D", "S", "MISC", Rarity.RARE, MiniType.HEAD, "", 5, 1, false, List.of(),
                false, LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 29));
        assertFalse(MinisFeed.json(T, List.of(dated), Map.of(), CHICAGO).contains("earn"));
    }

    @Test
    void aHolidayMiniWithoutAUsableWindowCarriesEventOnlyAlone() {
        String tail = "\"soldOut\":false,\"eventOnly\":true}]}";
        LocalDate a = LocalDate.of(2026, 11, 22);
        LocalDate b = LocalDate.of(2026, 11, 29);
        assertTrue(MinisFeed.json(T, List.of(turkey(null, b)), Map.of(), CHICAGO).endsWith(tail), "no earn_from");
        assertTrue(MinisFeed.json(T, List.of(turkey(a, null)), Map.of(), CHICAGO).endsWith(tail), "no earn_to");
        assertTrue(MinisFeed.json(T, List.of(turkey(b, a)), Map.of(), CHICAGO).endsWith(tail), "back to front");
        assertTrue(MinisFeed.json(T, List.of(turkey(a, b)), Map.of(), null).endsWith(tail), "no zone to date it in");
        assertTrue(MinisFeed.json(T, List.of(turkey(a, b)), Map.of()).endsWith(tail), "the older call has no zone");
    }
}
