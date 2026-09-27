package com.dierks.homecraft.market.sim;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calendar seasons: small, known-ahead nudges to a few items' prices (spec §9.1).
 *
 * <p><b>Windows.</b> A season runs from local midnight on {@code from} to local midnight after
 * {@code to}, both {@code MM-DD} and inclusive, in {@code clock.time_zone}. {@code from > to}
 * wraps the year ({@code 12-01 → 02-28} is one window from December into the next year).
 * {@code 02-29} means {@code 02-28} in a year without one.
 *
 * <p><b>Weight.</b> Inside a window {@code w(t) = min(smooth((t−start)/ramp), smooth((end−t)/ramp))}
 * with {@code ramp = ramp_days × 24 h} and {@code smooth(u) = u²(3−2u)}, so a season fades in over
 * its first days and out over its last; {@code w = 0} outside. An item's season effect is
 * {@code Σ percent/100 · w} over every season that names it (or {@code "*"}, every item), so
 * overlaps add up. Within one season an item's own entry is used in place of {@code "*"}.
 *
 * <p><b>Bounds.</b> {@link #parse} clamps each percent to {@code ±min(maxPercent, 4.5)}; the
 * simulator clamps season + real together to {@code c_pred} (§2.4). Nothing here widens a limit.
 *
 * <p>Pure Java — no Bukkit, no clock of its own — so the calendar is unit-tested with fixed
 * instants.
 */
public final class SeasonCalendar {

    /** The largest percent any one season may name: the §2.4 predictable cap, {@link SimLimits#PREDICTABLE_MAX}. */
    public static final double MAX_PERCENT = SimLimits.PREDICTABLE_MAX * 100.0;
    /** The id every item matches. */
    public static final String ALL = "*";

    private static final long DAY_MS = 86_400_000L;
    private static final Pattern MM_DD = Pattern.compile("(\\d{1,2})-(\\d{1,2})");
    private static final Pattern SEASON_ID = Pattern.compile("[a-z0-9_]{1,32}");

    /** The shipped {@code market.sim.seasons.list}, in config order. */
    public static final List<Season> SHIPPED = List.of(
            season("new_year", "New Year", "01-01", "01-07", pct(ALL, -3.0),
                    "Happy New Year! Everything at Crate costs a little less this week."),
            season("cozy_winter", "Cozy Winter", "12-01", "02-28", pct("oak_log", 4.0),
                    "Brrr, it's cold! Everyone wants Oak Logs to keep warm."),
            season("sparkle_week", "Sparkle Week", "02-07", "02-14", pct("gold_ingot", 3.0, "diamond", 3.0),
                    "Sparkle week! Everyone wants something shiny."),
            season("spring_building", "Spring Building", "03-20", "05-20", pct("cobblestone", 3.0, "oak_log", 3.0),
                    "Spring is here! Time to build, build, build!"),
            season("back_to_school", "Back to School", "08-15", "09-10", pct("iron_ingot", 3.0),
                    "Back to school! Everyone is making new tools."),
            season("harvest_time", "Harvest Time", "09-15", "10-31", pct("wheat", -4.0),
                    "Harvest time! The farms are full of wheat."),
            season("gift_season", "Gift Season", "12-10", "12-26", pct("gold_ingot", 4.0, "diamond", 4.0),
                    "Gift season! Shiny gold and diamonds make great presents."));

    /**
     * One season's current window.
     *
     * @param season the season
     * @param weight {@code w(t)} in {@code [0, 1]}
     * @param start  the window's first local day ({@code from} in the year it began)
     * @param end    the window's LAST local day, inclusive ({@code to}; "until Oct 31")
     */
    public record Active(Season season, double weight, LocalDate start, LocalDate end) {

        /** The instant the window opens: local midnight at the start of {@link #start}. */
        public long startsAt(ZoneId zone) {
            return start.atStartOfDay(zoneOr(zone)).toInstant().toEpochMilli();
        }

        /** The instant the window closes: local midnight after {@link #end} (exclusive). */
        public long endsAt(ZoneId zone) {
            return end.plusDays(1).atStartOfDay(zoneOr(zone)).toInstant().toEpochMilli();
        }

        /** The year {@code from} fell in — the second half of the SEASON row's tag. */
        public int year() {
            return start.getYear();
        }

        /** {@code id:year-of-from}, the SEASON row's idempotency tag. */
        public String tag() {
            return season.id() + ":" + start.getYear();
        }

        /** This season's share of {@code itemId}'s multiplier right now ({@code percent/100 · w}). */
        public double effect(String itemId) {
            return percentFor(season, itemId) / 100.0 * weight;
        }
    }

    private SeasonCalendar() {
    }

    // ---- the calendar -------------------------------------------------------------------

    /**
     * The summed season effect on {@code itemId} at {@code epochMs}, as a fraction
     * ({@code -0.04} = −4%). Not clamped here — the simulator clamps season + real to
     * {@code c_pred}.
     */
    public static double effect(List<Season> seasons, String itemId, long epochMs, ZoneId zone, int rampDays) {
        if (seasons == null || seasons.isEmpty() || itemId == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (Season s : seasons) {
            double pct = percentFor(s, itemId);
            if (pct == 0.0) {
                continue;
            }
            Optional<Active> w = window(s, epochMs, zone, rampDays);
            if (w.isPresent() && w.get().weight() > 0.0) {
                sum += pct / 100.0 * w.get().weight();
            }
        }
        return sum;
    }

    /** Every season with {@code w > 0} at {@code epochMs}, in list order. */
    public static List<Active> active(List<Season> seasons, long epochMs, ZoneId zone, int rampDays) {
        if (seasons == null || seasons.isEmpty()) {
            return List.of();
        }
        List<Active> out = new ArrayList<>();
        for (Season s : seasons) {
            window(s, epochMs, zone, rampDays).filter(a -> a.weight() > 0.0).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    /**
     * The window of {@code season} that contains {@code epochMs} — its weight is {@code 0} at the
     * very first instant — or empty when {@code epochMs} is outside every window.
     */
    public static Optional<Active> window(Season season, long epochMs, ZoneId zone, int rampDays) {
        if (season == null || season.from() == null || season.to() == null) {
            return Optional.empty();
        }
        ZoneId z = zoneOr(zone);
        LocalDate today = Instant.ofEpochMilli(epochMs).atZone(z).toLocalDate();
        Active best = null;
        for (int y = today.getYear() - 1; y <= today.getYear(); y++) {
            LocalDate start = on(season.from(), y);
            LocalDate end = wraps(season) ? on(season.to(), y + 1) : on(season.to(), y);
            if (end.isBefore(start)) {
                continue;
            }
            long s = start.atStartOfDay(z).toInstant().toEpochMilli();
            long e = end.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
            if (epochMs < s || epochMs >= e) {
                continue;
            }
            Active a = new Active(season, weight(epochMs, s, e, rampDays), start, end);
            if (best == null || a.weight() > best.weight()) {
                best = a;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * {@code id:year-of-from} for the window containing {@code epochMs} — or, outside every
     * window, for the latest window that began on or before it. A wrapping season keeps the
     * year it began in: {@code cozy_winter} on 2027-01-15 is {@code cozy_winter:2026}.
     */
    public static String tag(Season season, long epochMs, ZoneId zone) {
        ZoneId z = zoneOr(zone);
        LocalDate today = Instant.ofEpochMilli(epochMs).atZone(z).toLocalDate();
        int year = today.getYear();
        if (on(season.from(), year).isAfter(today)) {
            year--;
        }
        return season.id() + ":" + year;
    }

    /** {@code smooth(u) = u²(3−2u)}, {@code u} clamped to {@code [0, 1]}. */
    static double smooth(double u) {
        double x = Math.max(0.0, Math.min(1.0, u));
        return x * x * (3.0 - 2.0 * x);
    }

    private static double weight(long t, long start, long end, int rampDays) {
        if (rampDays <= 0) {
            return 1.0;
        }
        double ramp = rampDays * (double) DAY_MS;
        return Math.min(smooth((t - start) / ramp), smooth((end - t) / ramp));
    }

    private static boolean wraps(Season s) {
        return s.from().isAfter(s.to());
    }

    /** {@code md} in {@code year}; {@code 02-29} becomes {@code 02-28} in a year without one. */
    private static LocalDate on(MonthDay md, int year) {
        return md.isValidYear(year) ? md.atYear(year) : LocalDate.of(year, md.getMonth(), 28);
    }

    /** The item's own percent in this season, else {@code "*"}'s, else 0. */
    private static double percentFor(Season s, String itemId) {
        Map<String, Double> pct = s == null ? null : s.percent();
        if (pct == null || itemId == null) {
            return 0.0;
        }
        Double own = pct.get(itemId);
        if (own == null) {
            own = pct.get(itemId.toLowerCase(Locale.ROOT));
        }
        if (own == null) {
            own = pct.get(ALL);
        }
        return own == null || !Double.isFinite(own) ? 0.0 : own;
    }

    private static ZoneId zoneOr(ZoneId zone) {
        return zone == null ? ZoneOffset.UTC : zone;
    }

    // ---- config -------------------------------------------------------------------------

    /** {@link #parse(List, double, Consumer)} with the shipped {@code predictable_max_percent} (4.5). */
    public static List<Season> parse(List<Map<?, ?>> rows, Consumer<String> warn) {
        return parse(rows, MAX_PERCENT, warn);
    }

    /**
     * {@code market.sim.seasons.list} rows ({@code {id, name, from, to, percent, headline}}) as
     * seasons, in order. A row is dropped, with a WARN, when its id is missing, not
     * {@code [a-z0-9_]{1,32}} or repeated; when {@code from} or {@code to} is not a real
     * {@code MM-DD}; or when it has no usable percent. A percent that is not a number is
     * dropped; one past {@code ±min(maxPercent, 4.5)} is clamped — each with a WARN naming the
     * key. A missing or unsafe name falls back to the id written as words ({@code harvest_time}
     * → {@code Harvest Time}), a missing or unsafe headline to "{name} is here!". Item ids are lower-cased; whether they exist is checked separately
     * ({@link #unknownIds}), because the catalog can change on reload.
     */
    public static List<Season> parse(List<Map<?, ?>> rows, double maxPercent, Consumer<String> warn) {
        Consumer<String> w = warn == null ? s -> { } : warn;
        if (rows == null) {
            return List.of();
        }
        double cap = Double.isFinite(maxPercent) ? Math.max(0.0, Math.min(maxPercent, MAX_PERCENT)) : MAX_PERCENT;
        List<Season> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<?, ?> row = rows.get(i);
            String where = "market.sim.seasons.list #" + (i + 1);
            if (row == null) {
                w.accept(where + " is empty - skipped");
                continue;
            }
            String id = str(row.get("id")).toLowerCase(Locale.ROOT);
            if (!SEASON_ID.matcher(id).matches()) {
                w.accept(where + " has no valid id (a-z, 0-9, _; at most 32) - skipped");
                continue;
            }
            where = "market.sim.seasons.list[" + id + "]";
            if (!seen.add(id)) {
                w.accept(where + " appears twice - the second is skipped");
                continue;
            }
            MonthDay from = monthDay(row.get("from"));
            MonthDay to = monthDay(row.get("to"));
            if (from == null || to == null) {
                w.accept(where + " needs from/to as MM-DD (e.g. \"09-15\") - skipped");
                continue;
            }
            Map<String, Double> percent = percents(row.get("percent"), cap, where, w);
            if (percent.isEmpty()) {
                w.accept(where + " has no percent entries - skipped");
                continue;
            }
            String name = str(row.get("name"));
            String badName = name.isEmpty() ? null : Headlines.textProblem(name, true);
            if (badName != null) {
                w.accept(where + ".name " + badName + " - using the id");
            }
            if (name.isEmpty() || badName != null) {
                name = nameOf(id);
            }
            String headline = str(row.get("headline"));
            String bad = headline.isEmpty() ? null : Headlines.textProblem(headline, true);
            if (headline.isEmpty() || bad != null) {
                if (bad != null) {
                    w.accept(where + ".headline " + bad + " - using a plain one");
                }
                headline = name + " is here!";
            }
            out.add(new Season(id, name, from, to, percent, headline));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * A season id as players read it when no name is configured: {@code harvest_time} →
     * {@code Harvest Time} (the same words the website uses).
     */
    public static String nameOf(String id) {
        String s = id == null ? "" : id.strip();
        return s.isEmpty() ? "" : Headlines.name(s.toUpperCase(Locale.ROOT));
    }

    /**
     * {@code season}'s effects as they can really apply: each whole percent held to
     * {@code ±capFrac × 100} (the predictable cap {@code c_pred}, spec §2.4), entries that end up
     * 0 dropped, config order kept. What a SEASON announcement may quote: with a narrow
     * {@code market.spread} a configured {@code -4} reads {@code -2.25}, and with a spread of 0
     * nothing is left. A cap that is not a number (or infinite) keeps the configured percents.
     */
    public static Map<String, Double> effective(Season season, double capFrac) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (season == null || season.percent() == null) {
            return out;
        }
        boolean capped = Double.isFinite(capFrac);
        double cap = capped ? Math.max(0.0, capFrac) * 100.0 : Double.POSITIVE_INFINITY;
        for (Map.Entry<String, Double> e : season.percent().entrySet()) {
            Double v = e.getValue();
            if (e.getKey() == null || v == null || !Double.isFinite(v)) {
                continue;
            }
            double held = Math.max(-cap, Math.min(cap, v));
            if (held != 0.0) {
                out.put(e.getKey(), held);
            }
        }
        return out;
    }

    /** The season in {@code seasons} a SEASON row's tag ({@code id:year}) names, or {@code null}. */
    public static Season byTag(List<Season> seasons, String tag) {
        if (seasons == null || tag == null) {
            return null;
        }
        int colon = tag.indexOf(':');
        String id = colon < 0 ? tag : tag.substring(0, colon);
        for (Season s : seasons) {
            if (s != null && s.id().equalsIgnoreCase(id)) {
                return s;
            }
        }
        return null;
    }

    /**
     * The item ids the seasons name that are not in {@code knownIds} (never {@code "*"}),
     * sorted. They are simply ignored; the caller logs one WARN listing them.
     */
    public static List<String> unknownIds(List<Season> seasons, Collection<String> knownIds) {
        Set<String> out = new TreeSet<>();
        if (seasons == null) {
            return List.of();
        }
        for (Season s : seasons) {
            if (s == null || s.percent() == null) {
                continue;
            }
            for (String id : s.percent().keySet()) {
                if (!ALL.equals(id) && (knownIds == null || !knownIds.contains(id))) {
                    out.add(id);
                }
            }
        }
        return List.copyOf(out);
    }

    /** A {@code MM-DD} (1 or 2 digits each), or {@code null} when it is not a real date. */
    static MonthDay monthDay(Object raw) {
        String s = str(raw);
        Matcher m = MM_DD.matcher(s);
        if (!m.matches()) {
            return null;
        }
        try {
            return MonthDay.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Double> percents(Object raw, double cap, String where, Consumer<String> w) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String item = str(e.getKey()).toLowerCase(Locale.ROOT);
            String key = where + ".percent." + item;
            if (item.isEmpty()) {
                continue;
            }
            Double v = number(e.getValue());
            if (v == null) {
                w.accept(key + " is not a number - skipped");
                continue;
            }
            double clamped = Math.max(-cap, Math.min(cap, v));
            if (clamped != v) {
                w.accept(key + " " + v + " is past the " + cap + "% limit - using " + clamped);
            }
            out.put(item, clamped);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (raw instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim().replace("%", ""));
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    // ---- shipped rows -------------------------------------------------------------------

    private static Season season(String id, String name, String from, String to,
                                 Map<String, Double> percent, String headline) {
        return new Season(id, name, monthDay(from), monthDay(to), percent, headline);
    }

    private static Map<String, Double> pct(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], (Double) kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
