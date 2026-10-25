package com.dierks.homecraft.arcade;

import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Holiday firework shows (0.38.2; the owner: "We can make this part of the holiday stuff too so we can make special
 * holiday firework shows"): during a holiday's window of local dates ({@code clock.time_zone}, every year), every long
 * firework show (the Grand, Mega, Super, Ultra and Ultimate shows; never the 3-second Firework Show or Party Time)
 * takes that holiday's look: its colours and fades in place of the lanes' own (still starting on a different colour a
 * lane), its shape on about one rocket in four ({@link #signature}), a finale in its colours, and its name on the
 * server-wide line ("✦ Someone started a Halloween Mega Firework Show!"). Outside every window the shows are exactly
 * as they were.
 *
 * <p>The windows are {@code arcade.holiday_shows} ({@link #read}): Halloween, Christmas, New Year (which runs over
 * 1 January), Valentine's, St Patrick's and Independence Day ship ({@link #SHIPPED}, the same as the bundled file, so
 * Halloween is on from the first start even if the backfill never ran). The first window that holds a day wins. A
 * window that can't be read is skipped with one WARN naming every problem; colours are a dye name (the colour a
 * vanilla firework star of that dye has) or {@code "#RRGGBB"}.
 *
 * <p>Pure: {@link #themeOn} is the one question a show asks, once, when it starts (a show keeps the theme it started
 * with, so a 23:59 start on 31 October stays Halloween to its end).
 */
public record HolidayShows(boolean enabled, List<Window> windows) {

    /** A holiday's look: what its shows are called and drawn in. */
    public record Theme(String id, String name, List<Color> colours, List<Color> fades, FireworkEffect.Type shape) {
        public Theme {
            colours = List.copyOf(colours);
            fades = List.copyOf(fades);
        }
    }

    /** A window of local dates, every year, both ends in it; {@code from} after {@code to} runs over New Year. */
    public record Window(MonthDay from, MonthDay to, Theme theme) {
        /** Whether {@code d} is in it. */
        boolean on(MonthDay d) {
            return from.compareTo(to) <= 0 ? !d.isBefore(from) && !d.isAfter(to) : !d.isBefore(from) || !d.isAfter(to);
        }
    }

    /** The shapes a holiday may mix in: every vanilla firework shape. */
    public static final Set<FireworkEffect.Type> SHAPES = Set.copyOf(EnumSet.of(FireworkEffect.Type.BALL,
            FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST,
            FireworkEffect.Type.CREEPER));
    /** At most this many colours (or fades) a window. */
    static final int MAX_COLOURS = 8;
    /** No holiday shows at all. */
    public static final HolidayShows OFF = new HolidayShows(false, List.of());
    /** The six windows the bundled file ships, as literals (a test pins them to the bundled section). */
    public static final HolidayShows SHIPPED = new HolidayShows(true, List.of(
            window("halloween", "Halloween", "10-24", "10-31", List.of(dye(DyeColor.ORANGE), dye(DyeColor.PURPLE),
                    dye(DyeColor.LIME)), List.of(dye(DyeColor.YELLOW), dye(DyeColor.WHITE)), FireworkEffect.Type.CREEPER),
            window("christmas", "Christmas", "12-18", "12-26", List.of(dye(DyeColor.RED), Color.fromRGB(0x1FAF3F),
                    dye(DyeColor.WHITE), Color.fromRGB(0xFFD700)), List.of(dye(DyeColor.WHITE)), FireworkEffect.Type.STAR),
            window("new_year", "New Year", "12-31", "01-01", List.of(Color.fromRGB(0xFFD700), Color.fromRGB(0xC0C0C0),
                    dye(DyeColor.WHITE), Color.fromRGB(0x00FFFF)), List.of(dye(DyeColor.WHITE)),
                    FireworkEffect.Type.BALL_LARGE),
            window("valentines", "Valentine's", "02-10", "02-14", List.of(dye(DyeColor.PINK), dye(DyeColor.RED),
                    dye(DyeColor.WHITE)), List.of(Color.fromRGB(0xFFC0CB)), FireworkEffect.Type.BALL),
            window("st_patricks", "St Patrick's", "03-14", "03-17", List.of(dye(DyeColor.LIME), Color.fromRGB(0x1FAF3F),
                    Color.fromRGB(0xFFD700)), List.of(Color.fromRGB(0xCCFFCC)), FireworkEffect.Type.STAR),
            window("independence_day", "Independence Day", "07-01", "07-05", List.of(dye(DyeColor.RED),
                    dye(DyeColor.WHITE), dye(DyeColor.BLUE)), List.of(dye(DyeColor.WHITE)), FireworkEffect.Type.BURST)));

    private static final Pattern DAY = Pattern.compile("\\d\\d-\\d\\d");
    private static final Pattern HEX = Pattern.compile("#?[0-9A-Fa-f]{6}");
    /** Where the windows live, for the WARNs. */
    private static final String PATH = "arcade.holiday_shows";

    public HolidayShows {
        windows = List.copyOf(windows);
    }

    /**
     * The theme of {@code day} (a local date in {@code clock.time_zone}), or null: switched off, or no window holds
     * it. The first window listed that holds it wins.
     */
    public Theme themeOn(LocalDate day) {
        if (!enabled || day == null) {
            return null;
        }
        MonthDay d = MonthDay.from(day);
        for (Window w : windows) {
            if (w.on(d)) {
                return w.theme();
            }
        }
        return null;
    }

    // ---- reading arcade.holiday_shows ----------------------------------------------------------------

    /**
     * {@code arcade.holiday_shows}: {@link #SHIPPED} when the section isn't there ({@code present} false: a start whose
     * backfill didn't run). {@code enabled} is true unless it says false (anything but true or false is one WARN, and
     * true). Each of {@code windows} is read on its own: one that can't be read is skipped with one WARN naming every
     * problem, and a second window with an id already read is skipped too. No list: no windows (one WARN when it is
     * something else).
     */
    public static HolidayShows read(boolean present, Object enabled, Object windows, Consumer<String> warn) {
        if (!present) {
            return SHIPPED;
        }
        boolean on = true;
        if (enabled instanceof Boolean b) {
            on = b;
        } else if (enabled != null) {
            warn.accept(PATH + ".enabled '" + enabled + "' is not true or false - using true.");
        }
        List<Window> out = new ArrayList<>();
        if (windows instanceof List<?> list) {
            Set<String> ids = new HashSet<>();
            int n = 0;
            for (Object raw : list) {
                n++;
                Window w = window(n, raw, warn);
                if (w == null) {
                    continue;
                }
                if (!ids.add(w.theme().id())) {
                    warn.accept(PATH + ": a second window with the id '" + w.theme().id() + "' - skipped (the first"
                            + " one stays).");
                    continue;
                }
                out.add(w);
            }
        } else if (windows != null) {
            warn.accept(PATH + ".windows is not a list of windows - no holiday shows.");
        }
        return new HolidayShows(on, out);
    }

    /** Window {@code n} of the list, or null (one WARN) when it can't be read. */
    private static Window window(int n, Object raw, Consumer<String> warn) {
        Map<?, ?> row = raw instanceof Map<?, ?> m ? m
                : raw instanceof ConfigurationSection s ? s.getValues(false) : null;
        if (row == null) {
            warn.accept(PATH + ": window " + n + " is not a { id, name, from, to, colours, fades, shape } row -"
                    + " skipped.");
            return null;
        }
        List<String> problems = new ArrayList<>();
        Object rawId = row.get("id");
        String id = rawId == null ? "" : String.valueOf(rawId).trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            problems.add("it has no id");
        }
        MonthDay from = day(row.get("from"), "from", problems);
        MonthDay to = day(row.get("to"), "to", problems);
        List<Color> colours = colours(row.get("colours"), "colours", true, problems);
        List<Color> fades = row.get("fades") == null ? List.of(dye(DyeColor.WHITE))
                : colours(row.get("fades"), "fades", false, problems);
        FireworkEffect.Type shape = FireworkEffect.Type.BALL;
        if (row.get("shape") != null) {
            shape = shape(row.get("shape"));
            if (shape == null) {
                problems.add("shape '" + row.get("shape") + "' is not BALL, BALL_LARGE, STAR, BURST or CREEPER");
            }
        }
        if (!problems.isEmpty()) {
            warn.accept(PATH + ": window " + (id.isEmpty() ? String.valueOf(n) : "'" + id + "'") + " skipped: "
                    + String.join("; ", problems) + ".");
            return null;
        }
        Object rawName = row.get("name");
        String name = rawName == null || String.valueOf(rawName).isBlank() ? id : String.valueOf(rawName).trim();
        return new Window(from, to, new Theme(id, name, colours, fades, shape));
    }

    /** "MM-DD" as a day of the year ("02-29" is one, "02-30" is not); null with a problem when it isn't. */
    private static MonthDay day(Object raw, String key, List<String> problems) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        if (DAY.matcher(s).matches()) {
            try {
                return MonthDay.parse("--" + s);
            } catch (DateTimeException e) {
                // falls through to the problem
            }
        }
        problems.add(key + " '" + (raw == null ? "" : raw) + "' is not a day as \"MM-DD\" (keep the quotes)");
        return null;
    }

    /** A list of 1-{@value #MAX_COLOURS} colours; null with a problem for every one that isn't. */
    private static List<Color> colours(Object raw, String key, boolean required, List<String> problems) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            problems.add(raw == null && required ? "it has no " + key
                    : key + " is not a list of 1 to " + MAX_COLOURS + " colours");
            return null;
        }
        if (list.size() > MAX_COLOURS) {
            problems.add(key + " has " + list.size() + " colours (at most " + MAX_COLOURS + ")");
            return null;
        }
        List<Color> out = new ArrayList<>();
        int before = problems.size();
        for (Object o : list) {
            Color c = colour(o);
            if (c == null) {
                problems.add(o == null ? key + " has an empty colour (a #RRGGBB needs its quotes: \"#FF8000\")"
                        : key + " '" + o + "' is not a dye name or \"#RRGGBB\"");
            } else {
                out.add(c);
            }
        }
        return problems.size() == before ? List.copyOf(out) : null;
    }

    /**
     * A colour: {@code "#RRGGBB"} or {@code "RRGGBB"} (hex), else a dye name (any case) as the colour a vanilla firework
     * star of that dye has ({@link DyeColor#getFireworkColor()}); null if neither.
     */
    static Color colour(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (HEX.matcher(s).matches()) {
            return Color.fromRGB(Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16));
        }
        try {
            return dye(DyeColor.valueOf(s.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A shape by name (any case), one of {@link #SHAPES}; null when it isn't. */
    private static FireworkEffect.Type shape(Object raw) {
        try {
            FireworkEffect.Type t = FireworkEffect.Type.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
            return SHAPES.contains(t) ? t : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Color dye(DyeColor d) {
        return d.getFireworkColor();
    }

    private static Window window(String id, String name, String from, String to, List<Color> colours,
                                 List<Color> fades, FireworkEffect.Type shape) {
        return new Window(MonthDay.parse("--" + from), MonthDay.parse("--" + to),
                new Theme(id, name, colours, fades, shape));
    }

    // ---- the looks ------------------------------------------------------------------------------------

    /**
     * Two of a theme's colours for rocket {@code nth} of lane {@code lane}: {@code c[i]} and {@code c[i + 1]} with
     * {@code i = (lane + nth) mod |c|}, so the lanes start on different colours and each rocket of a lane differs from
     * the last; one colour when the theme has only one.
     */
    static List<Color> two(Theme t, int lane, int nth) {
        List<Color> c = t.colours();
        int i = Math.floorMod(lane + nth, c.size());
        return c.size() == 1 ? List.of(c.get(0)) : List.of(c.get(i), c.get((i + 1) % c.size()));
    }

    /** The theme's first two colours (its finale's gold), or its one. */
    static List<Color> firstTwo(Theme t) {
        List<Color> c = t.colours();
        return c.size() == 1 ? List.of(c.get(0)) : List.of(c.get(0), c.get(1));
    }

    /** The fade of rocket {@code nth} of lane {@code lane}: {@code f[(lane + nth) mod |f|]}. */
    static Color fade(Theme t, int lane, int nth) {
        List<Color> f = t.fades();
        return f.get(Math.floorMod(lane + nth, f.size()));
    }

    /** Whether rocket {@code nth} of lane {@code lane} takes the holiday's shape: about one rocket in four. */
    static boolean signature(int lane, int nth) {
        return Math.floorMod(lane + nth, 4) == 0;
    }
}
