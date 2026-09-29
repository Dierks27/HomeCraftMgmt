package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.cabinet.CabinetGame;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A slot's seed (GEN-SPEC §4.0, weekly addendum §1): exactly the cabinets' HMAC over the edition's
 * index and {@code gen:<slot>@<N>} (and {@code #<reroll>}), different for every edition, cadence,
 * slot, reroll and secret — a daily and a weekly edition never share one; and seeds read and
 * written as hex the same way everywhere.
 */
class GenSeedTest {

    private static final long SECRET = 0x5EC12E7L;
    private static final long MON_28_SEP = 20724;

    @Test
    void theSeedIsTheCabinetsHmacOverTheEditionAndTheSlot() {
        assertEquals(CabinetGame.seed(SECRET, 38, "gen:fresh_golf@7"), GenSeed.seed(SECRET, 7, MON_28_SEP, "fresh_golf",
                0), "the week of 28 Sep: index 38, gen:<slot>@7");
        assertEquals(CabinetGame.seed(SECRET, 38, "gen:fresh_golf@7#2"), GenSeed.seed(SECRET, 7, MON_28_SEP,
                "fresh_golf", 2), "a reroll: gen:<slot>@<N>#<n>");
        assertEquals(CabinetGame.seed(SECRET, 267, "gen:fresh_golf@1"), GenSeed.seed(SECRET, 20725, "fresh_golf", 0),
                "the day form is the daily edition of that day");
        assertEquals("gen:fresh_rings@1", GenSeed.label("fresh_rings", 0), "the label without a reroll");
        assertEquals("gen:fresh_rings@3#1", GenSeed.label("fresh_rings", 3, 1), "and with a cadence and a reroll");
        assertEquals(GenSeed.seed(SECRET, 7, MON_28_SEP, "fresh_golf", 0), GenSeed.seed(SECRET, 7, MON_28_SEP + 3,
                "fresh_golf", 0), "any day of an edition names the same edition");
    }

    @Test
    void differentCadencesNeverShareASeed() {
        java.util.Set<Long> all = new HashSet<>();
        int made = 0;
        for (int n = 1; n <= 28; n++) {
            for (long start = 20458; start < 20458 + 3 * 28; start += n) {
                all.add(GenSeed.seed(SECRET, n, start, "fresh_parkour_easy", 0));
                made++;
            }
        }
        assertEquals(made, all.size(), "every edition of every cadence has its own seed");
        assertNotEquals(GenSeed.seed(SECRET, 1, 20458, "fresh_golf", 0), GenSeed.seed(SECRET, 7, 20458, "fresh_golf",
                0), "the first daily and the first weekly edition (both index 0) differ");
    }

    @Test
    void theSeedChangesWithEditionSlotRerollAndSecret() {
        long base = GenSeed.seed(SECRET, 7, MON_28_SEP, "fresh_parkour_easy", 0);
        assertNotEquals(base, GenSeed.seed(SECRET, 7, MON_28_SEP + 7, "fresh_parkour_easy", 0),
                "next week is another course");
        assertNotEquals(base, GenSeed.seed(SECRET, 7, MON_28_SEP, "fresh_parkour_hard", 0),
                "another slot is another course");
        assertNotEquals(base, GenSeed.seed(SECRET, 7, MON_28_SEP, "fresh_parkour_easy", 1),
                "a reroll is another course");
        assertNotEquals(base, GenSeed.seed(SECRET + 1, 7, MON_28_SEP, "fresh_parkour_easy", 0),
                "another server's secret is another course");
        Set<Long> year = new HashSet<>();
        for (long day = 20725; day < 20725 + 365; day++) {
            year.add(GenSeed.seed(SECRET, day, "fresh_tiny_golf", 0));
        }
        assertEquals(365, year.size(), "a year of daily editions gives a year of different seeds");
    }

    @Test
    void seedsAreSixteenHexDigitsBothWays() {
        long seed = 0x3F2A91C07D1E55B0L;
        assertEquals("3f2a91c07d1e55b0", GenSeed.hex(seed), "lower-case, 16 digits");
        assertEquals("000000000000002a", GenSeed.hex(42), "padded with zeros");
        assertEquals("3f2a", GenSeed.shortHex(seed), "the short form is the first four");
        assertEquals(seed, GenSeed.parse("3f2a91c07d1e55b0"), "read back");
        assertEquals(seed, GenSeed.parse(" 0x3F2A91C07D1E55B0 "), "any case, with 0x and spaces");
        assertEquals(-1L, GenSeed.parse("ffffffffffffffff"), "the top bit set is a negative long");
        assertEquals(42L, GenSeed.parse("2a"), "fewer digits are fine");
        for (String bad : new String[]{null, "", "0x", "xyz", "12345678901234567", "12 34", "-5"}) {
            assertNull(GenSeed.parse(bad), "'" + bad + "' is not a seed");
        }
    }
}
