package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.HolidayShows.Theme;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holiday firework shows' windows (0.38.2; the owner: "We can make this part of the holiday stuff too so we can make
 * special holiday firework shows"): each shipped window on its first and last day and off the day after; New Year runs
 * over 1 January; the first window listed wins; switched off, none; Halloween is on now (0.38.2 ships before 31
 * October); every way a window can't be read is one WARN and that window skipped; the defaults; and the bundled
 * section reads as the code's {@link HolidayShows#SHIPPED} with no WARN.
 */
class HolidayShowsTest {

    private static final int YEAR = 2026;

    private static String on(HolidayShows h, int month, int day) {
        Theme t = h.themeOn(LocalDate.of(YEAR, month, day));
        return t == null ? null : t.id();
    }

    private static Map<String, Object> window(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (var r = new InputStreamReader(HolidayShowsTest.class.getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8)) {
            c.load(r);
        }
        return c;
    }

    @Test
    void eachShippedWindowIsOnFromItsFirstToItsLastDay() {
        HolidayShows h = HolidayShows.SHIPPED;
        int[][] windows = {{10, 24, 10, 31}, {12, 18, 12, 26}, {2, 10, 2, 14}, {3, 14, 3, 17}, {7, 1, 7, 5}};
        String[] ids = {"halloween", "christmas", "valentines", "st_patricks", "independence_day"};
        for (int i = 0; i < ids.length; i++) {
            int[] w = windows[i];
            assertEquals(ids[i], on(h, w[0], w[1]), ids[i] + ": its first day");
            assertEquals(ids[i], on(h, w[2], w[3]), ids[i] + ": its last day");
            LocalDate before = LocalDate.of(YEAR, w[0], w[1]).minusDays(1);
            LocalDate after = LocalDate.of(YEAR, w[2], w[3]).plusDays(1);
            assertNull(h.themeOn(before), ids[i] + ": not the day before");
            assertNull(h.themeOn(after), ids[i] + ": nor the day after");
        }
        assertNull(on(h, 6, 15), "a plain day in June: the shows as they are");
        assertNull(on(h, 11, 1), "nor 1 November");
        assertEquals(6, h.windows().size());
    }

    @Test
    void newYearRunsOverTheFirstOfJanuary() {
        HolidayShows h = HolidayShows.SHIPPED;
        assertEquals("new_year", on(h, 12, 31));
        assertEquals("new_year", on(h, 1, 1), "over New Year");
        assertNull(on(h, 12, 30));
        assertNull(on(h, 1, 2));
        assertNull(h.themeOn(LocalDate.of(2027, 1, 2)), "every year");
        assertEquals("new_year", h.themeOn(LocalDate.of(2028, 1, 1)).id());
    }

    @Test
    void halloweenIsOnFromInstallDay() {
        for (int day = 25; day <= 31; day++) {
            Theme t = HolidayShows.SHIPPED.themeOn(LocalDate.of(YEAR, 10, day));
            assertEquals("Halloween", t.name(), "25-31 October " + YEAR);
            assertEquals(FireworkEffect.Type.CREEPER, t.shape(), "with creeper faces");
            assertEquals(List.of(DyeColor.ORANGE.getFireworkColor(), DyeColor.PURPLE.getFireworkColor(),
                    DyeColor.LIME.getFireworkColor()), t.colours(), "orange, purple and lime");
        }
    }

    @Test
    void anOverlapGoesToTheFirstListedAndOffIsOff() {
        List<String> warnings = new ArrayList<>();
        List<Object> windows = List.of(
                window("id", "spooky", "from", "10-20", "to", "11-02", "colours", List.of("ORANGE")),
                window("id", "halloween", "from", "10-24", "to", "10-31", "colours", List.of("PURPLE")));
        HolidayShows h = HolidayShows.read(true, true, windows, warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals("spooky", on(h, 10, 31), "the first listed wins");
        assertNull(HolidayShows.read(true, false, windows, warnings::add).themeOn(LocalDate.of(YEAR, 10, 31)),
                "enabled: false = every show as it always is");
        assertNull(HolidayShows.OFF.themeOn(LocalDate.of(YEAR, 10, 31)));
        assertNull(HolidayShows.SHIPPED.themeOn(null));
        assertEquals(List.of(), warnings);
        HolidayShows odd = HolidayShows.read(true, "yes", windows, warnings::add);
        assertEquals(List.of("arcade.holiday_shows.enabled 'yes' is not true or false - using true."), warnings);
        assertTrue(odd.enabled());
    }

    @Test
    void coloursAreHexOrDyeNames() {
        assertEquals(Color.fromRGB(0xFF8000), HolidayShows.colour("#FF8000"));
        assertEquals(Color.fromRGB(0xFF8000), HolidayShows.colour("ff8000"), "without the # too, any case");
        assertEquals(DyeColor.ORANGE.getFireworkColor(), HolidayShows.colour("ORANGE"), "a vanilla star's orange");
        assertEquals(DyeColor.LIGHT_BLUE.getFireworkColor(), HolidayShows.colour(" light_blue "));
        assertNull(HolidayShows.colour("GOLDEN"));
        assertNull(HolidayShows.colour("#FF80"));
        assertNull(HolidayShows.colour(null), "an unquoted #RRGGBB is a YAML comment: nothing");
    }

    @Test
    void aWindowThatCantBeReadIsOneWarnAndSkipped() {
        List<Object> windows = new ArrayList<>(List.of(
                window("id", "good", "from", "05-01", "to", "05-02", "colours", List.of("RED")),
                window("id", "bad_date", "from", "02-30", "to", "03-01", "colours", List.of("RED")),
                window("id", "bad_colour", "from", "05-01", "to", "05-02", "colours", List.of("RED", "GOLDEN")),
                window("id", "bad_fade", "from", "05-01", "to", "05-02", "colours", List.of("RED"),
                        "fades", List.of("#12")),
                window("id", "bad_shape", "from", "05-01", "to", "05-02", "colours", List.of("RED"), "shape", "HEART"),
                window("id", "good", "from", "06-01", "to", "06-02", "colours", List.of("BLUE")),
                window("from", "05-01", "to", "05-02", "colours", List.of("RED")),
                window("id", "no_colours", "from", "5-1", "to", "05-02"),
                "not a row"));
        List<Object> withNull = new ArrayList<>(windows);
        withNull.add(window("id", "null_colour", "from", "05-01", "to", "05-02", "colours",
                java.util.Arrays.asList("RED", null)));
        List<String> warnings = new ArrayList<>();
        HolidayShows h = HolidayShows.read(true, null, withNull, warnings::add);
        assertEquals(List.of("good"), h.windows().stream().map(w -> w.theme().id()).toList(), "only the good one");
        assertEquals(9, warnings.size(), "one WARN a bad window: " + warnings);
        assertTrue(warnings.get(0).contains("'bad_date'") && warnings.get(0).contains("from '02-30'"), warnings.get(0));
        assertTrue(warnings.get(1).contains("colours 'GOLDEN' is not a dye name"), warnings.get(1));
        assertTrue(warnings.get(2).contains("fades '#12'"), warnings.get(2));
        assertTrue(warnings.get(3).contains("shape 'HEART'"), warnings.get(3));
        assertTrue(warnings.get(4).contains("a second window with the id 'good'"), warnings.get(4));
        assertTrue(warnings.get(5).contains("window 7") && warnings.get(5).contains("no id"), warnings.get(5));
        assertTrue(warnings.get(6).contains("from '5-1'") && warnings.get(6).contains("no colours"),
                "every problem in its one WARN: " + warnings.get(6));
        assertTrue(warnings.get(7).contains("window 9 is not a"), warnings.get(7));
        assertTrue(warnings.get(8).contains("keep its quotes") || warnings.get(8).contains("needs its quotes"),
                "an empty colour says why: " + warnings.get(8));
        List<String> notList = new ArrayList<>();
        assertEquals(List.of(), HolidayShows.read(true, true, "halloween", notList::add).windows());
        assertEquals(1, notList.size(), notList.toString());
        List<String> none = new ArrayList<>();
        assertEquals(List.of(), HolidayShows.read(true, true, null, none::add).windows(), "no windows: none");
        assertEquals(List.of(), none, "and nothing to say");
        assertEquals(MonthDay.of(2, 29), HolidayShows.read(true, true, List.of(window("id", "leap", "from", "02-29",
                "to", "02-29", "colours", List.of("RED"))), none::add).windows().get(0).from(), "29 Feb is a day");
    }

    @Test
    void theDefaultsAreAWhiteFadeABallAndTheIdAsTheName() {
        List<String> warnings = new ArrayList<>();
        HolidayShows h = HolidayShows.read(true, true, List.of(window("id", " Party ", "from", "08-01", "to", "08-01",
                "colours", List.of("#FF0000", "LIME"))), warnings::add);
        assertEquals(List.of(), warnings);
        Theme t = h.windows().get(0).theme();
        assertEquals("party", t.id(), "trimmed, lower-case");
        assertEquals("party", t.name(), "no name: the id");
        assertEquals(List.of(DyeColor.WHITE.getFireworkColor()), t.fades(), "fades: white");
        assertEquals(FireworkEffect.Type.BALL, t.shape(), "shape: a ball");
        assertEquals(List.of(Color.fromRGB(0xFF0000), DyeColor.LIME.getFireworkColor()), t.colours());
    }

    @Test
    void anAbsentSectionIsTheShippedWindowsAndTheBundledOneParsesToThem() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(HolidayShows.SHIPPED, HolidayShows.read(false, null, null, warnings::add),
                "no section (a start whose backfill didn't run): the shipped windows, so Halloween is on regardless");
        YamlConfiguration c = bundled();
        HolidayShows read = HolidayShows.read(c.contains("arcade.holiday_shows"), c.get("arcade.holiday_shows.enabled"),
                c.get("arcade.holiday_shows.windows"), warnings::add);
        assertEquals(List.of(), warnings, "the bundled section reads with no WARN");
        assertEquals(HolidayShows.SHIPPED, read, "and is the code's own copy, window for window");
        assertEquals(List.of("halloween", "christmas", "new_year", "valentines", "st_patricks", "independence_day"),
                read.windows().stream().map(w -> w.theme().id()).toList());
        assertEquals(List.of("Halloween", "Christmas", "New Year", "Valentine's", "St Patrick's", "Independence Day"),
                read.windows().stream().map(w -> w.theme().name()).toList());
        assertEquals(java.util.Set.of(FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE,
                FireworkEffect.Type.STAR, FireworkEffect.Type.BURST, FireworkEffect.Type.CREEPER), HolidayShows.SHAPES);
    }
}
