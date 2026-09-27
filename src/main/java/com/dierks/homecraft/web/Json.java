package com.dierks.homecraft.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The few JSON writers the dashboard feeds need, shared by every feed builder so
 * {@code /api/market} and {@code /api/minis} escape and format values the same way.
 *
 * <p>Deliberately hand-rolled rather than a Gson tree: the feeds are rebuilt on the main
 * thread every refresh, the shapes are fixed, and the market feed's existing fields must
 * stay byte-for-byte what the website already parses. {@link #string}, {@link #num2} and
 * {@link #plain} are the dashboard's original {@code jsonString}, {@code num} and
 * {@code stripLegacy}, moved here unchanged.
 *
 * <p>Plain Java — no Bukkit, no plugin classes — so it is unit-tested without a server.
 */
public final class Json {

    /** Legacy '&amp;'/'§' colour + format codes (the dashboard's original stripLegacy pattern). */
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§][0-9a-fk-or]");

    private Json() {
    }

    /**
     * A JSON string literal, quotes included. Escapes {@code "}, {@code \}, newline,
     * carriage return and tab by name and every other control character below
     * {@code 0x20} as {@code \}{@code u00XX}; everything else (accents, stars, emoji)
     * passes through as UTF-8. {@code null} writes the empty string {@code ""}.
     */
    public static String string(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * A number to exactly two decimals ({@code 2.4 -> "2.40"}), always with a '.' whatever
     * the server's locale. NaN and infinities write {@code 0} — JSON has no spelling for
     * them, and one bad price must not make the whole feed unparseable.
     */
    public static String num2(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.2f", d);
    }

    /**
     * A number to at most four decimals, for the long history arrays where every byte is
     * repeated a few hundred times per item: rounded half-up on the decimal value
     * ({@code 2.00005 -> "2.0001"}), trailing zeros dropped ({@code 2.10 -> "2.1"},
     * {@code 2.0 -> "2"}), never in exponent form ({@code 1e7 -> "10000000"}). Anything that
     * rounds to zero — {@code -0.0} and {@code -0.00001} included — writes a plain
     * {@code 0}, and NaN or an infinity writes {@code 0} as {@link #num2} does.
     */
    public static String num4(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }
        // valueOf goes through Double.toString, so 2.00005 rounds as the decimal it reads as
        // (up), not as the binary fraction just below it — the same rule %.2f applies.
        BigDecimal rounded = BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP);
        if (rounded.signum() == 0) {
            return "0";
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    /**
     * Web-safe plain text: legacy '&amp;'/'§' colour and format codes stripped
     * ({@code "&6Gold &lIngot" -> "Gold Ingot"}) and the ends trimmed. {@code null} gives
     * {@code ""}. The website strips them too, but the feed never relies on that.
     */
    public static String plain(String s) {
        return s == null ? "" : LEGACY_CODE.matcher(s).replaceAll("").trim();
    }
}
