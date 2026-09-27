package com.dierks.homecraft.market.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Real commodity closes, read and turned into a small nudge (spec §9.2). Only numbers come in
 * from outside — never text — and they feed Crate's own kid-safe templates.
 *
 * <p><b>Parsing.</b> {@link #parseStooq} reads Stooq's daily CSV ({@code Date,Open,High,Low,Close,…};
 * the {@code Date} and {@code Close} columns found by name, in any order and case).
 * {@link #parseYahoo} reads Yahoo's v8 chart JSON ({@code chart.result[0].timestamp[]} in
 * seconds and {@code indicators.quote[0].close[]}), each timestamp turned into a trade date in
 * {@code meta.exchangeTimezoneName} (UTC when missing). Both return closes oldest first, one per
 * day, positive only — and an empty list, never an exception, for "No data", a hits-limit
 * notice, an HTML page, a {@code chart.error}, or anything else unexpected.
 *
 * <p><b>Mapping.</b> {@link #latestMove} takes the two most recent trade days, the newer no
 * older than {@code maxAgeDays}; {@link #impulse} turns the move into
 * {@code R = clamp(gain·q, ±maxFrac)}, or {@code NaN} when {@code |q|} is past
 * {@code ignoreAboveFrac} (bad data). {@code maxFrac} itself never exceeds 4.5% — the
 * predictable-layer cap — whatever the config says.
 *
 * <p>Pure Java plus Gson (which Paper ships) — no Bukkit, no network — so it is tested against
 * fixture files; the fetcher does the HTTP.
 */
public final class RealQuotes {

    /** Stooq's daily CSV; {@code {from}}/{@code {to}} are {@code yyyyMMdd}. */
    public static final String STOOQ_URL = "https://stooq.com/q/d/l/?s={symbol}&d1={from}&d2={to}&i=d";
    /** Yahoo's chart API, one month of daily bars. */
    public static final String YAHOO_URL = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1mo&interval=1d";
    /** {@code {from}} is this many days before today. */
    public static final int LOOKBACK_DAYS = 14;
    /** The largest nudge a real move may give, whatever the config: {@link SimLimits#PREDICTABLE_MAX}. */
    public static final double MAX_FRAC = SimLimits.PREDICTABLE_MAX;

    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.=^_-]{1,16}");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** One trading day's close. */
    public record DailyClose(LocalDate day, double close) {
    }

    /**
     * The latest day-over-day move: {@code close} on {@code day} against {@code prevClose} on
     * the trade day before it.
     */
    public record Move(LocalDate prevDay, double prevClose, LocalDate day, double close) {

        /** {@code q = close/prevClose − 1} ({@code 0.015} = +1.5%). */
        public double change() {
            return close / prevClose - 1.0;
        }
    }

    private RealQuotes() {
    }

    // ---- providers ----------------------------------------------------------------------

    /** The provider's default URL template ({@code stooq} or {@code yahoo}), or {@code null}. */
    public static String defaultUrl(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "stooq" -> STOOQ_URL;
            case "yahoo" -> YAHOO_URL;
            default -> null;
        };
    }

    /** A usable URL template: it starts with {@code https://} (the feed is disabled otherwise). */
    public static boolean validUrl(String tpl) {
        return tpl != null && tpl.trim().regionMatches(true, 0, "https://", 0, "https://".length())
                && tpl.trim().length() > "https://".length();
    }

    /** A symbol a row may name: {@code [A-Za-z0-9.=^_-]{1,16}} ({@code gc.f}, {@code GC=F}). */
    public static boolean validSymbol(String symbol) {
        return symbol != null && SYMBOL.matcher(symbol).matches();
    }

    /**
     * {@code tpl} with {@code {symbol}} URL-encoded ({@code GC=F} → {@code GC%3DF}),
     * {@code {from}} = today − {@value #LOOKBACK_DAYS} days and {@code {to}} = today, both
     * {@code yyyyMMdd}.
     */
    public static String url(String tpl, String symbol, LocalDate today) {
        String t = tpl == null ? "" : tpl.trim();
        String enc = URLEncoder.encode(symbol == null ? "" : symbol, StandardCharsets.UTF_8);
        return t.replace("{symbol}", enc)
                .replace("{from}", today.minusDays(LOOKBACK_DAYS).format(YMD))
                .replace("{to}", today.format(YMD));
    }

    // ---- parsing ------------------------------------------------------------------------

    /**
     * Stooq's CSV as closes, oldest first. The first line must be a header with a {@code Date}
     * column; {@code Close} is found by name (any case). Rows whose date does not parse or whose
     * close is not a positive number are skipped. A later row for the same day wins.
     */
    public static List<DailyClose> parseStooq(String body) {
        if (body == null) {
            return List.of();
        }
        String text = body.startsWith("﻿") ? body.substring(1) : body;
        String[] lines = text.split("\\r?\\n|\\r");
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        if (first >= lines.length) {
            return List.of();
        }
        String header = lines[first];
        char sep = header.indexOf(',') >= 0 ? ',' : header.indexOf(';') >= 0 ? ';' : ',';
        String[] cols = split(header, sep);
        int dateCol = column(cols, "date");
        int closeCol = column(cols, "close");
        if (dateCol < 0 || closeCol < 0) {
            return List.of();
        }
        TreeMap<LocalDate, Double> byDay = new TreeMap<>();
        for (int i = first + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = split(lines[i], sep);
            if (cells.length <= Math.max(dateCol, closeCol)) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(cells[dateCol]);
            } catch (DateTimeParseException e) {
                continue;
            }
            double close = positive(cells[closeCol]);
            if (close > 0) {
                byDay.put(day, close);
            }
        }
        return toList(byDay);
    }

    /**
     * Yahoo's chart JSON as closes, oldest first: {@code timestamp[i]} (seconds) paired with
     * {@code close[i]}, nulls and non-positive values skipped, dates in the exchange's zone. A
     * later bar for the same day wins (the in-progress bar Yahoo appends).
     */
    public static List<DailyClose> parseYahoo(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonObject chart = child(root, "chart");
            if (chart == null) {
                return List.of();
            }
            JsonElement error = chart.get("error");
            if (error != null && !error.isJsonNull()) {
                return List.of();
            }
            JsonElement results = chart.get("result");
            if (results == null || !results.isJsonArray() || results.getAsJsonArray().isEmpty()) {
                return List.of();
            }
            JsonElement r0 = results.getAsJsonArray().get(0);
            if (!r0.isJsonObject()) {
                return List.of();
            }
            JsonObject result = r0.getAsJsonObject();
            ZoneId zone = zone(child(result, "meta"));
            JsonArray stamps = array(result, "timestamp");
            JsonObject indicators = child(result, "indicators");
            JsonArray quotes = array(indicators, "quote");
            if (stamps == null || quotes == null || quotes.isEmpty() || !quotes.get(0).isJsonObject()) {
                return List.of();
            }
            JsonArray closes = array(quotes.get(0).getAsJsonObject(), "close");
            if (closes == null) {
                return List.of();
            }
            TreeMap<LocalDate, Double> byDay = new TreeMap<>();
            int n = Math.min(stamps.size(), closes.size());
            for (int i = 0; i < n; i++) {
                JsonElement ts = stamps.get(i);
                JsonElement c = closes.get(i);
                if (!isNumber(ts) || !isNumber(c)) {
                    continue;
                }
                double close = c.getAsDouble();
                if (!(close > 0) || !Double.isFinite(close)) {
                    continue;
                }
                LocalDate day = Instant.ofEpochSecond(ts.getAsLong()).atZone(zone).toLocalDate();
                byDay.put(day, close);
            }
            return toList(byDay);
        } catch (RuntimeException e) {
            // JsonParseException for text that is not JSON; the rest for a tree of the wrong shape.
            return List.of();
        }
    }

    // ---- mapping ------------------------------------------------------------------------

    /**
     * The move between the two most recent distinct trade days {@code prevDay < day}, where
     * {@code day} is no later than {@code today} and no earlier than {@code today − maxAgeDays}.
     * Days after {@code today} are ignored. Empty when there are fewer than two usable days or
     * the newest is too old.
     */
    public static Optional<Move> latestMove(List<DailyClose> closes, LocalDate today, int maxAgeDays) {
        if (closes == null || closes.size() < 2 || today == null) {
            return Optional.empty();
        }
        TreeMap<LocalDate, Double> byDay = new TreeMap<>();
        for (DailyClose c : closes) {
            if (c == null || c.day() == null || c.day().isAfter(today) || !(c.close() > 0)
                    || !Double.isFinite(c.close())) {
                continue;
            }
            byDay.put(c.day(), c.close());
        }
        if (byDay.size() < 2) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, Double> last = byDay.lastEntry();
        if (last.getKey().isBefore(today.minusDays(Math.max(0, maxAgeDays)))) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, Double> prev = byDay.lowerEntry(last.getKey());
        return Optional.of(new Move(prev.getKey(), prev.getValue(), last.getKey(), last.getValue()));
    }

    /**
     * The nudge for a real move {@code change}: {@code clamp(gain·change, ±maxFrac)} with
     * {@code maxFrac} itself held to {@code [0, }{@value #MAX_FRAC}{@code ]}, or {@code NaN} —
     * skip it — when {@code |change| > ignoreAboveFrac} or {@code change} is not a number.
     */
    public static double impulse(double change, double gain, double maxFrac, double ignoreAboveFrac) {
        if (!Double.isFinite(change) || !(Math.abs(change) <= ignoreAboveFrac)) {
            return Double.NaN;
        }
        double cap = Double.isFinite(maxFrac) ? Math.max(0.0, Math.min(maxFrac, MAX_FRAC)) : 0.0;
        double g = Double.isFinite(gain) ? Math.max(0.0, gain) : 0.0;
        return Math.max(-cap, Math.min(cap, g * change));
    }

    // ---- helpers ------------------------------------------------------------------------

    private static List<DailyClose> toList(TreeMap<LocalDate, Double> byDay) {
        List<DailyClose> out = new ArrayList<>(byDay.size());
        byDay.forEach((d, c) -> out.add(new DailyClose(d, c)));
        return List.copyOf(out);
    }

    private static String[] split(String line, char sep) {
        String[] raw = line.split(Pattern.quote(String.valueOf(sep)), -1);
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i].trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1).trim();
            }
            raw[i] = s;
        }
        return raw;
    }

    private static int column(String[] cols, String name) {
        for (int i = 0; i < cols.length; i++) {
            if (cols[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** A positive, finite number, else {@code -1}. */
    private static double positive(String cell) {
        try {
            double v = Double.parseDouble(cell);
            return Double.isFinite(v) && v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ZoneId zone(JsonObject meta) {
        if (meta != null) {
            JsonElement tz = meta.get("exchangeTimezoneName");
            if (tz != null && tz.isJsonPrimitive() && tz.getAsJsonPrimitive().isString()) {
                try {
                    return ZoneId.of(tz.getAsString().trim());
                } catch (DateTimeException e) {
                    // an unknown zone name: fall through to UTC
                }
            }
        }
        return ZoneOffset.UTC;
    }

    private static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static JsonObject child(JsonElement parent, String key) {
        if (parent == null || !parent.isJsonObject()) {
            return null;
        }
        JsonElement value = parent.getAsJsonObject().get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }
}
