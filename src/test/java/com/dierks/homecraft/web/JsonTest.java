package com.dierks.homecraft.web;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The feed's number and string writers.
 *
 * <p>{@link Json#num4} is new for the long history arrays: half-up on the decimal value, at
 * most four decimals, no trailing zeros, never an exponent (a JSON parser would accept
 * {@code 1E+7}, but the website's own sample data and every other number in the feed is
 * plain), and nothing that reads as a negative zero. {@link Json#string}, {@link Json#num2}
 * and {@link Json#plain} are the dashboard's original helpers moved out of the server; these
 * pin that they still write exactly what the website has been parsing.
 */
class JsonTest {

    @Test
    void num4RoundsHalfUpOnTheDecimalValue() {
        // 2.00005 is stored as a binary fraction a hair below itself; the feed rounds the
        // number as it reads, like %.2f does, not the fraction.
        assertEquals("2.0001", Json.num4(2.00005));
        assertEquals("2.1235", Json.num4(2.123456));
        assertEquals("2.1234", Json.num4(2.12344));
        assertEquals("0.0001", Json.num4(0.00005));
        assertEquals("-0.0001", Json.num4(-0.00005), "half-up rounds away from zero on both sides");
        assertEquals("-2.1235", Json.num4(-2.123456));
    }

    @Test
    void num4DropsTrailingZeros() {
        assertEquals("2.31", Json.num4(2.31));
        assertEquals("2.1", Json.num4(2.10));
        assertEquals("2", Json.num4(2.0));
        assertEquals("2", Json.num4(1.99999));
        assertEquals("100", Json.num4(100.0), "zeros before the point are digits, not padding");
        assertEquals("-2.5", Json.num4(-2.5));
    }

    @Test
    void num4NeverWritesAnExponent() {
        assertEquals("10000000", Json.num4(1e7));
        assertEquals("100000000000000000000", Json.num4(1e20));
        assertEquals("0.0001", Json.num4(1e-4));
        assertEquals("0", Json.num4(1e-5));
        for (double d : new double[] {1e7, 1e20, 1e-4, 1e-5, 12345678.9, 0.00015}) {
            String s = Json.num4(d);
            assertFalse(s.contains("E") || s.contains("e"), d + " wrote " + s);
        }
    }

    @Test
    void num4WritesEverythingThatRoundsToZeroAsAPlainZero() {
        assertEquals("0", Json.num4(0.0));
        assertEquals("0", Json.num4(-0.0));
        assertEquals("0", Json.num4(-0.00001));
        assertEquals("0", Json.num4(0.00004999));
        assertEquals("0", Json.num4(Double.MIN_VALUE));
    }

    @Test
    void nonFiniteNumbersWriteZero() {
        for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals("0", Json.num4(d), "num4 " + d);
            assertEquals("0", Json.num2(d), "num2 " + d);
        }
    }

    @Test
    void num2KeepsItsTwoFixedDecimals() {
        assertEquals("2.40", Json.num2(2.4));
        assertEquals("2.52", Json.num2(2.52));
        assertEquals("0.13", Json.num2(0.125));
        assertEquals("10000000.00", Json.num2(1e7));
        assertEquals("0.00", Json.num2(0.0));
        assertEquals("-3.10", Json.num2(-3.1));
    }

    @Test
    void numbersIgnoreTheServerLocale() {
        Locale before = Locale.getDefault();
        try {
            // A German or French server would otherwise write "2,40" — not JSON.
            Locale.setDefault(Locale.GERMANY);
            assertEquals("2.40", Json.num2(2.4));
            assertEquals("1234.5679", Json.num4(1234.56789));
            Locale.setDefault(Locale.FRANCE);
            assertEquals("2.40", Json.num2(2.4));
            assertEquals("0.5", Json.num4(0.5));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void stringEscapesWhatJsonRequires() {
        assertEquals("\"Iron Ingot\"", Json.string("Iron Ingot"));
        assertEquals("\"say \\\"hi\\\"\"", Json.string("say \"hi\""));
        assertEquals("\"C:\\\\path\"", Json.string("C:\\path"));
        assertEquals("\"a\\nb\\rc\\td\"", Json.string("a\nb\rc\td"));
        assertEquals("\"\\u0001\\u001f\\u0000\"", Json.string("\u0001\u001f\u0000"));
    }

    @Test
    void stringPassesEverythingElseThroughUnchanged() {
        assertEquals("\"Crème ★ </script> a/b & c\"", Json.string("Crème ★ </script> a/b & c"));
        assertEquals("\"\u007f\"", Json.string("\u007f"), "DEL is not a JSON control character");
    }

    @Test
    void nullStringIsTheEmptyJsonString() {
        assertEquals("\"\"", Json.string(null));
        assertEquals("\"\"", Json.string(""));
    }

    @Test
    void plainStripsLegacyColourAndFormatCodes() {
        assertEquals("Iron Ingot", Json.plain("&aIron &lIngot"));
        assertEquals("Gold", Json.plain("§6Gold"));
        assertEquals("Shouting", Json.plain("&CShou&Ltin&Rg"), "codes are case-insensitive");
        assertEquals("Obfuscated", Json.plain("&k&m&n&oObfuscated"));
        assertEquals("padded", Json.plain("  &r padded &r "));
    }

    @Test
    void plainLeavesOrdinaryAmpersandsAlone() {
        assertEquals("Fish & Chips", Json.plain("Fish & Chips"));
        assertEquals("&x&zNot codes", Json.plain("&x&zNot codes"), "x and z are not legacy codes");
        assertEquals("Trailing &", Json.plain("Trailing &"));
    }

    @Test
    void plainOfNullIsEmpty() {
        assertEquals("", Json.plain(null));
        assertEquals("", Json.plain("   "));
    }
}
