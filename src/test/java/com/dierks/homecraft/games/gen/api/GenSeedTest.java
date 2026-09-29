package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.cabinet.CabinetGame;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A slot's seed (GEN-SPEC §4.0): exactly the cabinets' daily HMAC over {@code gen:<slot>} (and
 * {@code #<reroll>}), different for every day, slot, reroll and secret; and seeds read and written
 * as hex the same way everywhere.
 */
class GenSeedTest {

    private static final long SECRET = 0x5EC12E7L;

    @Test
    void theSeedIsTheCabinetsHmacOverTheSlotsLabel() {
        assertEquals(CabinetGame.seed(SECRET, 20725, "gen:daily_golf"), GenSeed.seed(SECRET, 20725, "daily_golf", 0),
                "no reroll: gen:<slot>");
        assertEquals(CabinetGame.seed(SECRET, 20725, "gen:daily_golf#2"), GenSeed.seed(SECRET, 20725, "daily_golf", 2),
                "a reroll: gen:<slot>#<n>");
        assertEquals("gen:sky_rings", GenSeed.label("sky_rings", 0), "the label without a reroll");
        assertEquals("gen:sky_rings#1", GenSeed.label("sky_rings", 1), "and with one");
    }

    @Test
    void theSeedChangesWithDaySlotRerollAndSecret() {
        long base = GenSeed.seed(SECRET, 20725, "daily_parkour_easy", 0);
        assertNotEquals(base, GenSeed.seed(SECRET, 20726, "daily_parkour_easy", 0), "tomorrow is another course");
        assertNotEquals(base, GenSeed.seed(SECRET, 20725, "daily_parkour_hard", 0), "another slot is another course");
        assertNotEquals(base, GenSeed.seed(SECRET, 20725, "daily_parkour_easy", 1), "a reroll is another course");
        assertNotEquals(base, GenSeed.seed(SECRET + 1, 20725, "daily_parkour_easy", 0),
                "another server's secret is another course");
        Set<Long> year = new HashSet<>();
        for (long day = 20725; day < 20725 + 365; day++) {
            year.add(GenSeed.seed(SECRET, day, "tiny_golf", 0));
        }
        assertEquals(365, year.size(), "a year of days gives a year of different seeds");
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
