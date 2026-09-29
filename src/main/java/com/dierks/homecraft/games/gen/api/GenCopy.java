package com.dierks.homecraft.games.gen.api;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Every fixed word Fresh Courses shows a player, in one place so one test can read it all
 * (GEN-SPEC §7).
 *
 * <p>The readers are children, the youngest one on a Bedrock tablet. So signs are short words a
 * six-year-old can sound out, at most {@value #SIGN_LINES} lines of {@value #SIGN_CHARS} plain
 * ASCII characters (a sign shows nothing else reliably on both editions); nothing anywhere uses a
 * character above U+FFFF (Bedrock draws a box); and no line uses the words the Games never say
 * ({@link #BANNED}). The colour language does the rest: green start, light-blue checkpoint, gold
 * finish.
 *
 * <p>Chat and tile lines carry {@code &}-colour codes like the rest of the plugin; sign lines
 * carry none.
 *
 * <p><b>The words follow the cadence</b> (weekly addendum §2): how often the courses change is a
 * setting, so no screen may say "today" or "daily" on its own. Everything that names the set, the
 * schedule or the golf course takes the cadence in days ({@link #current}, {@link #schedule},
 * {@link #previous}, {@link #slotName}, {@link #tile}); the constants say nothing about when.
 */
public final class GenCopy {

    /** A sign shows at most this many lines... */
    public static final int SIGN_LINES = 4;
    /** ...of at most this many characters. */
    public static final int SIGN_CHARS = 15;

    /** The game's name. */
    public static final String NAME = "Fresh Courses";

    /** Words no Fresh Courses line may use (the Games' list, plus "sink" for golf). */
    public static final List<String> BANNED = List.of("bet", "wager", "gamble", "gambling", "casino", "lucky",
            "almost", "so close", "sink", "house", "hot", "due", "jackpot", "big win");

    private static final Pattern CODES = Pattern.compile("(?i)[&§][0-9a-fk-or]");

    // ---- signs --------------------------------------------------------------------------------

    /** The parkour start sign for a tier ({@code easy}, {@code medium} or {@code hard}). */
    public static List<String> parkourStart(String tier) {
        String title = switch (tier == null ? "" : tier.toLowerCase(Locale.ROOT)) {
            case "easy" -> "EASY PARKOUR";
            case "hard" -> "HARD PARKOUR";
            default -> "PARKOUR";
        };
        return List.of(title, "Hop to the", "GOLD pad!", "Blue = saved");
    }

    /** The sign on a finish pad. */
    public static List<String> finish() {
        return List.of("FINISH!");
    }

    /** Sky Rings' first tower sign: how to start flying. */
    public static List<String> ringsStart() {
        return List.of("SKY RINGS", "Walk off, then", "press JUMP", "to fly!");
    }

    /** Sky Rings' second tower sign: where to fly. */
    public static List<String> ringsHow() {
        return List.of("Fly through", "every ring,", "follow the", "rainbow!");
    }

    /** A golf tee sign: "HOLE 3" / "Par 3" / "Hit the ball" / "to the flag!". */
    public static List<String> golfTee(int hole, int par) {
        return List.of("HOLE " + hole, "Par " + par, "Hit the ball", "to the flag!");
    }

    /** The ice boat's start sign. */
    public static List<String> boatStart(int laps) {
        return List.of("ICE BOAT", "Go " + laps + " laps,", "follow the", "arrows!");
    }

    // ---- chat, titles and tiles ----------------------------------------------------------------

    /** The Fresh Courses tile on the Courses and Golf tabs at the shipped (weekly) cadence: {@link #tile}. */
    public static final String TILE = "&eFresh Courses &7- new every Monday";
    /** A course whose new layout hasn't flipped yet is still the last edition's: {@link #previous} says which. */
    public static final String YESTERDAY = "&7The last course &8- the new one is on its way";
    /** Anyone in a building area who isn't mid-run there is moved out at once. */
    public static final String MOVED = "&7A new course is being built here, so we moved you somewhere safe.";
    /** The last warning before a run on an old layout ends. */
    public static final String ONE_MINUTE = "&e1 minute left!";
    /** What an admin editing a course inside a Fresh Courses area reads. */
    public static final String EDITOR_REFUSED =
            "&cThat's inside the Fresh Courses area (it rebuilds itself). Build somewhere else.";
    /** What anyone placing or breaking a block in a Fresh Courses area reads. */
    public static final String GUARDED = "&cThis area is built by Fresh Courses - use &e/hcm games gen&c.";
    /** What an admin reads for a course edit the course tools can't do on a generated course. */
    public static final String MADE_BY_DAILY = "&7This course is made by Fresh Courses - use &e/hcm games gen&7.";
    /** Sky Rings, the first fall-reset of a run. */
    public static final String WINGS_TIP = "&ePress jump again while falling to open your wings!";

    /** A slot that is switched off, or whose layout can't be vouched for right now. */
    public static String closed(String name) {
        return "&7" + name + " is closed for now.";
    }

    /** A slot's tile while it is being built. */
    public static String building(String name) {
        return "&7" + name + " &8- being built, back soon";
    }

    /** Someone on a layout the next build needs: how long they have, and that it still counts. */
    public static String comingHere(int minutes) {
        return "&eA new course is coming here! &7Finish in the next " + minutes + " minute"
                + (minutes == 1 ? "" : "s") + " - your time still counts.";
    }

    /** Their time is up; the run ended and their things came back. */
    public static String timesUp(String playId) {
        return "&eTime's up - a new course is ready! &7Your things are back. Try it: &e/hcm play " + playId;
    }

    /** An admin command held back by a scheduled restart ("4:00 PM"). */
    public static String restartSoon(String at) {
        return "&cA restart is coming at " + at + " - try after it.";
    }

    /** "New course in 11h 2m". */
    public static String newIn(long millis) {
        return "&7New course in &f" + span(millis);
    }

    /** A wait as players read it: "6d 14h", "11h 2m", "5m", "less than a minute". */
    public static String span(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        if (minutes < 1) {
            return "less than a minute";
        }
        long d = minutes / (24 * 60);
        long h = minutes / 60 % 24;
        long m = minutes % 60;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        return h == 0 ? m + "m" : h + "h " + m + "m";
    }

    // ---- words that follow the cadence ---------------------------------------------------------

    /** The cadence as admins and the status read it: "weekly", "daily", "every 3 days". */
    public static String cadenceName(int cadence) {
        return switch (cadence) {
            case 1 -> "daily";
            case 7 -> "weekly";
            default -> "every " + cadence + " days";
        };
    }

    /** What the live set is called: "This week's courses", "Today's courses", "The current courses". */
    public static String current(int cadence) {
        return switch (cadence) {
            case 1 -> "Today's courses";
            case 7 -> "This week's courses";
            default -> "The current courses";
        };
    }

    /**
     * When the courses change: "New courses every Monday" (weekly, on the rebuild day), "New courses
     * every day", or "New courses every 3 days - next Thu 4:00 AM" ({@code next} as {@link #when}
     * writes it; left off when {@code null}).
     */
    public static String schedule(int cadence, DayOfWeek rebuildDay, String next) {
        return switch (cadence) {
            case 1 -> "New courses every day";
            case 7 -> "New courses every " + dayName(rebuildDay);
            default -> "New courses every " + cadence + " days" + (next == null ? "" : " - next " + next);
        };
    }

    /** The Fresh Courses tile's name: "&amp;eFresh Courses &amp;7- new every Monday" (or every day, every 3 days). */
    public static String tile(int cadence, DayOfWeek rebuildDay) {
        String every = switch (cadence) {
            case 1 -> "every day";
            case 7 -> "every " + dayName(rebuildDay);
            default -> "every " + cadence + " days";
        };
        return "&e" + NAME + " &7- new " + every;
    }

    /** A course still showing the last edition while the new one is built. */
    public static String previous(int cadence) {
        return switch (cadence) {
            case 1 -> "&7Yesterday's course &8- today's is on its way";
            case 7 -> "&7Last week's course &8- this week's is on its way";
            default -> YESTERDAY;
        };
    }

    /**
     * A course's name at a cadence: golf's big course is "Golf of the Day" or "Golf of the Week"
     * (and "Fresh Golf" for any other cadence); every other course keeps its own name.
     */
    public static String slotName(Slots.Def def, int cadence) {
        if (def == null) {
            return "";
        }
        if (def != Slots.DAILY_GOLF) {
            return def.name();
        }
        return switch (cadence) {
            case 1 -> "Golf of the Day";
            case 7 -> "Golf of the Week";
            default -> "Fresh Golf";
        };
    }

    /** A moment as players and admins read it: "Thu 4:00 AM". */
    public static String when(long millis, ZoneId zone) {
        return WHEN.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** A moment with its date, for the admin's status: "Mon 5 Oct 4:00 AM". */
    public static String whenDated(long millis, ZoneId zone) {
        return WHEN_DATED.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US);
    private static final DateTimeFormatter WHEN_DATED = DateTimeFormatter.ofPattern("EEE d MMM h:mm a", Locale.US);

    private static String dayName(DayOfWeek day) {
        return (day == null ? DayOfWeek.MONDAY : day).getDisplayName(TextStyle.FULL, Locale.US);
    }

    // ---- checks (tested) -------------------------------------------------------------------------

    /**
     * What is wrong with a sign's lines: more than {@value #SIGN_LINES}, none at all, a line longer
     * than {@value #SIGN_CHARS}, anything but printable ASCII, or a banned word. Empty when fine.
     */
    public static List<String> signProblems(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            out.add("a sign needs at least one line");
            return out;
        }
        if (lines.size() > SIGN_LINES) {
            out.add("a sign has at most " + SIGN_LINES + " lines: " + lines);
        }
        for (String line : lines) {
            if (line == null) {
                out.add("a sign line is missing");
                continue;
            }
            if (line.length() > SIGN_CHARS) {
                out.add("'" + line + "' is longer than " + SIGN_CHARS + " characters");
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c < 0x20 || c > 0x7E) {
                    out.add("'" + line + "' has a character that isn't plain ASCII");
                    break;
                }
            }
            out.addAll(copyProblems(line));
        }
        return out;
    }

    /** What is wrong with a line of copy: a banned word or a character above U+FFFF. Empty when fine. */
    public static List<String> copyProblems(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        if (text.codePoints().anyMatch(cp -> cp > 0xFFFF)) {
            out.add("'" + text + "' has a character Bedrock can't draw");
        }
        String words = " " + CODES.matcher(text).replaceAll(" ").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ") + " ";
        for (String banned : BANNED) {
            if (words.contains(" " + banned + " ")) {
                out.add("'" + text + "' says '" + banned + "'");
            }
        }
        return out;
    }

    /** Every fixed line and every sign above, with sample values, for the copy test. */
    public static List<String> everyLine() {
        List<String> out = new ArrayList<>(List.of(NAME, TILE, YESTERDAY, MOVED, ONE_MINUTE, EDITOR_REFUSED, GUARDED,
                MADE_BY_DAILY, WINGS_TIP, closed("Easy Parkour"), building("Sky Rings"), comingHere(20),
                comingHere(1), timesUp("fresh_golf"), restartSoon("4:00 PM"), newIn(11 * 3_600_000L + 120_000L),
                newIn(6 * 86_400_000L + 14 * 3_600_000L)));
        for (int cadence : new int[]{1, 2, 3, 7, 14, 28}) {
            out.add(cadenceName(cadence));
            out.add(current(cadence));
            out.add(schedule(cadence, DayOfWeek.MONDAY, "Thu 4:00 AM"));
            out.add(tile(cadence, DayOfWeek.THURSDAY));
            out.add(previous(cadence));
            for (Slots.Def d : Slots.ALL) {
                out.add(slotName(d, cadence));
            }
        }
        for (List<String> sign : everySign()) {
            out.addAll(sign);
        }
        return out;
    }

    /** Every sign, with sample values, for the copy test. */
    public static List<List<String>> everySign() {
        List<List<String>> out = new ArrayList<>(List.of(parkourStart("easy"), parkourStart("medium"),
                parkourStart("hard"), finish(), ringsStart(), ringsHow(), boatStart(2)));
        for (int hole = 1; hole <= 18; hole++) {
            out.add(golfTee(hole, 6));
        }
        return out;
    }

    private GenCopy() {
    }
}
