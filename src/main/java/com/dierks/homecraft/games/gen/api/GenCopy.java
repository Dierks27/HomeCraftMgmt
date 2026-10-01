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
        return golfTee(hole, par, TeeFeature.NONE);
    }

    /**
     * What a golf hole's tee sign tells about it (Course Variety §8): its main feature, lines 3-4 of
     * the sign. Lines 1-2 are always "HOLE n" / "Par p".
     */
    public enum TeeFeature {
        /** Nothing special: "Hit the ball" / "to the flag!" (every hole before Course Variety). */
        NONE("Hit the ball", "to the flag!"),
        /** A pond, a creek or an island green: water in play. */
        WATER("Mind the pond!", "Splash = +1"),
        /** A sand bunker. */
        SAND("Sand is slow!", "Hit it harder"),
        /** A hill or a hump to putt over. */
        HILL("Up and over", "the hill!"),
        /** Terraces: steps down (the glass waterfall). */
        TERRACES("Down the steps!", "Watch it roll"),
        /** The volcano green. */
        VOLCANO("Up the volcano!", "Not too hard!"),
        /** Trees in play. */
        TREES("Bank off the", "trees!"),
        /** Two ways round an island. */
        TWO_WAY("Pick a path!", "Short or safe?"),
        /** A dogleg that drops at the corner. */
        DOGLEG_DOWN("Round the bend", "and down!");

        private final String line3;
        private final String line4;

        TeeFeature(String line3, String line4) {
            this.line3 = line3;
            this.line4 = line4;
        }

        /** Lines 3 and 4 of the tee sign. */
        public List<String> lines() {
            return List.of(line3, line4);
        }
    }

    /**
     * A golf tee sign with its hole's main feature: "HOLE 5" / "Par 3" / "Down the steps!" /
     * "Watch it roll". {@code null} is {@link TeeFeature#NONE}.
     */
    public static List<String> golfTee(int hole, int par, TeeFeature feature) {
        TeeFeature f = feature == null ? TeeFeature.NONE : feature;
        return List.of("HOLE " + hole, "Par " + par, f.line3, f.line4);
    }

    /**
     * The feature a tee sign's lines name when they are hole {@code hole}'s tee sign of par
     * {@code par} (any feature, {@link #golfTee(int, int, TeeFeature)}), or {@code null} when they
     * aren't: what a validator reads off a plan's tee sign.
     */
    public static TeeFeature golfTeeFeature(List<String> lines, int hole, int par) {
        if (lines == null) {
            return null;
        }
        for (TeeFeature f : TeeFeature.values()) {
            if (golfTee(hole, par, f).equals(lines)) {
                return f;
            }
        }
        return null;
    }

    /** The ice boat's start sign (the algo 1-2 loop; kept for its doc and tests). */
    public static List<String> boatStart(int laps) {
        return List.of("ICE BOAT", "Go " + laps + " laps,", "follow the", "arrows!");
    }

    // ---- the Mountain Run (Course Variety §8): a standing sign by the start, the rest on wall tops ----

    /** The Mountain Run's start sign, on the pit wall beside the start. */
    public static List<String> boatRun() {
        return List.of("DOWNHILL RACE!", "Follow arrows", "down to the", "gold finish!");
    }

    /** Before a Hop (a 1-block drop). */
    public static List<String> boatHop() {
        return List.of("HOP!", "Little drop");
    }

    /** Before a Big Drop (a 2-block drop). */
    public static List<String> boatBigDrop() {
        return List.of("BIG DROP!", "Hold on!");
    }

    /** The sign before a drop of {@code blocks}: a Hop for 1, a Big Drop for more. */
    public static List<String> boatDrop(int blocks) {
        return blocks <= 1 ? boatHop() : boatBigDrop();
    }

    /** Before the Final Drop, the last lip before the finish under the stand. */
    public static List<String> boatFinalDrop() {
        return List.of("FINAL DROP!", "Then the gold", "finish line!");
    }

    /** Before a bend with a sand run-off on its outside. */
    public static List<String> boatSandyBend() {
        return List.of("SANDY BEND", "Sand is slow,", "ice is fast!");
    }

    /** Before a sand pit with its two ice ways round. */
    public static List<String> boatSandPit() {
        return List.of("SAND PIT!", "Stay on the ice", "to go fast!");
    }

    /** Before a split round a tree island. */
    public static List<String> boatSplit() {
        return List.of("PICK A PATH!", "Left or right?");
    }

    /** Before the Ice Cave. */
    public static List<String> boatIceCave() {
        return List.of("ICE CAVE", "Lights on!");
    }

    /** Before the forest slalom. */
    public static List<String> boatForest() {
        return List.of("FOREST", "Weave through", "the trees!");
    }

    /**
     * A Dropper level's sign, on the wall over its ledge (EVENTS-DROPPER-SPEC §B.1.1): "LEVEL 2 of 5"
     * / "Step off and" / "fall into the" / "WATER!".
     */
    public static List<String> dropperLevel(int level, int levels) {
        return List.of("LEVEL " + level + " of " + levels, "Step off and", "fall into the", "WATER!");
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

    /**
     * The words of a course recalled into a Classics slot, given where a cadence picks a board's or
     * a reward's words ({@link #when}, {@link #bestOf}, {@link #yourBest}, {@link #firstFinish},
     * {@link #times}, {@link #clearLimit}): its board holds its original set's times, so it never
     * says "this week" or "today" of them ("Best on this course", "Your best on this course"). Never
     * a cadence for anything else (a schedule, a slot's name, a set's dates).
     */
    public static final int CLASSIC = 0;

    /**
     * The cadence a course's board and reward words follow: {@link #CLASSIC} for a recalled course,
     * else its set's own ({@code null}: weekly).
     */
    public static int words(GenTag t) {
        return t == null ? Edition.WEEKLY : t.recalled() ? CLASSIC : t.cadence();
    }

    /**
     * When a course's boards and rewards run, as a line ends: "today" (daily), "this week"
     * (weekly), "on this course" (any other cadence: each set is a new course; and a Classic).
     */
    public static String when(int cadence) {
        return switch (cadence) {
            case 1 -> "today";
            case 7 -> "this week";
            default -> "on this course";
        };
    }

    /** A set's board as a line starts: "Today's best", "This week's best", "Best on this course". */
    public static String bestOf(int cadence) {
        return switch (cadence) {
            case 1 -> "Today's best";
            case 7 -> "This week's best";
            default -> "Best on this course";
        };
    }

    /** "Your best today", "Your best this week", "Your best on this course". */
    public static String yourBest(int cadence) {
        return "Your best " + when(cadence);
    }

    /** "First finish today", "First finish this week", "First finish on this course". */
    public static String firstFinish(int cadence) {
        return "First finish " + when(cadence);
    }

    /** The ledger's reason for a set's first finish: "first finish this week". */
    public static String firstFinishReason(int cadence) {
        return "first finish " + when(cadence);
    }

    /** A set's board as a tile names it: "Today's times", "This week's times", "This course's times". */
    public static String times(int cadence) {
        return switch (cadence) {
            case 1 -> "Today's times";
            case 7 -> "This week's times";
            default -> "This course's times";
        };
    }

    /** The mark on a course's tile while it is the current set's: "(new today)", "(new this week)", "(new)". */
    public static String newMark(int cadence) {
        return switch (cadence) {
            case 1 -> "(new today)";
            case 7 -> "(new this week)";
            default -> "(new)";
        };
    }

    /** The mark while it is still the last set's: "(yesterday's)", "(last week's)", "(the last one)". */
    public static String oldMark(int cadence) {
        return switch (cadence) {
            case 1 -> "(yesterday's)";
            case 7 -> "(last week's)";
            default -> "(the last one)";
        };
    }

    /** A leaderboard display's title: "Hard Parkour - this week", "Parkour - today", or the name alone. */
    public static String boardTitle(String name, int cadence) {
        return switch (cadence) {
            case 1 -> name + " - today";
            case 7 -> name + " - this week";
            default -> name;
        };
    }

    /**
     * What a player reads when today's caps can't pay a set's first-finish tokens in full, so none
     * are paid (CADENCE-UI-TODO §4c: all or nothing): the weekly words as the owner set them; a set
     * of 2 to 6 days says "before the courses change"; a daily set can't be finished another day.
     */
    public static String clearLimit(int cadence) {
        if (cadence >= Edition.WEEKLY) {
            return GOAL_LIMIT;
        }
        if (cadence <= Edition.DAILY) {
            return "&7You've reached today's token limit - your time and stars still count!";
        }
        return "&7You've reached today's token limit - finish it again another day before the courses change"
                + " for its tokens.";
    }

    /** The same for a Star Chart goal (the chart is weekly whatever the cadence). */
    public static final String GOAL_LIMIT =
            "&7You've reached today's token limit - finish it again another day this week for its tokens.";

    // ---- the archive: course codes, Classics and kept courses (GEN-SPEC-KEEP) -----------------------

    /** The line that tells players old courses can come back (screens and the players' guide). */
    public static final String CLASSICS_TIP = "&7Loved an old course? Tell an admin its course code, and they can"
            + " bring it back for a week, or keep it forever.";
    /** A course name is at most this long (the course tools cut a longer one). */
    public static final int NAME_CHARS = 32;

    /** A course's code as a player reads it, for an item NAME and the finish line: "Course code HARD-40". */
    public static String courseCode(String code) {
        return "Course code " + code;
    }

    /**
     * What an archived course recalled into a Classics slot is called: "Classic: Hard Parkour (week
     * of 5 Oct)", "Classic: Parkour (5 Oct)" for a daily one, "Classic: Sky Rings (5 Oct-7 Oct)" for
     * a 3-day one, with " (re-made)" when it was made again from its seed by today's generator.
     *
     * @param name     the course's own name ("Hard Parkour")
     * @param cadence  its edition's length in days
     * @param startDay its edition's first day (local epoch day)
     */
    public static String classicName(String name, int cadence, long startDay, boolean remade) {
        return "Classic: " + name + " (" + editionDates(cadence, startDay) + ")" + (remade ? " (re-made)" : "");
    }

    /**
     * {@link #classicName} cut to fit a course row's name ({@value #NAME_CHARS} characters): the
     * dates shortened first, then dropped. The screens show the full name from the tag.
     */
    public static String classicRowName(String name, int cadence, long startDay, boolean remade) {
        String full = classicName(name, cadence, startDay, remade);
        if (full.length() <= NAME_CHARS) {
            return full;
        }
        String shorter = "Classic: " + name + (remade ? " (re-made)" : " (" + DAY_MONTH.format(Edition.date(startDay))
                + ")");
        if (shorter.length() <= NAME_CHARS) {
            return shorter;
        }
        String bare = "Classic: " + name;
        return bare.length() <= NAME_CHARS ? bare : bare.substring(0, NAME_CHARS).trim();
    }

    /** An edition's dates for players: "5 Oct" (a day), "week of 5 Oct" (a week), "5 Oct-7 Oct" (N days). */
    public static String editionDates(int cadence, long startDay) {
        String first = DAY_MONTH.format(Edition.date(startDay));
        if (cadence <= Edition.DAILY) {
            return first;
        }
        if (cadence == Edition.WEEKLY) {
            return "week of " + first;
        }
        return first + "-" + DAY_MONTH.format(Edition.date(startDay + cadence - 1));
    }

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.US);

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
                newIn(6 * 86_400_000L + 14 * 3_600_000L), CLASSICS_TIP, courseCode("HARD-40"), GOAL_LIMIT));
        for (int cadence : new int[]{1, 2, 3, 7, 14, 28}) {
            out.add(cadenceName(cadence));
            out.add(current(cadence));
            out.add(schedule(cadence, DayOfWeek.MONDAY, "Thu 4:00 AM"));
            out.add(tile(cadence, DayOfWeek.THURSDAY));
            out.add(previous(cadence));
            out.add(when(cadence));
            out.add(bestOf(cadence));
            out.add(yourBest(cadence));
            out.add(firstFinish(cadence));
            out.add(firstFinishReason(cadence));
            out.add(times(cadence));
            out.add(newMark(cadence));
            out.add(oldMark(cadence));
            out.add(boardTitle("Hard Parkour", cadence));
            out.add(clearLimit(cadence));
            for (Slots.Def d : Slots.ALL) {
                out.add(slotName(d, cadence));
                out.add(classicName(slotName(d, cadence), cadence, 20731, false));
                out.add(classicRowName(slotName(d, cadence), cadence, 20731, true));
            }
        }
        out.addAll(List.of(when(CLASSIC), bestOf(CLASSIC), yourBest(CLASSIC), firstFinish(CLASSIC),
                firstFinishReason(CLASSIC), times(CLASSIC), boardTitle("Hard Parkour", CLASSIC), clearLimit(CLASSIC)));
        for (List<String> sign : everySign()) {
            out.addAll(sign);
        }
        return out;
    }

    /** Every sign, with sample values, for the copy test. */
    public static List<List<String>> everySign() {
        List<List<String>> out = new ArrayList<>(List.of(parkourStart("easy"), parkourStart("medium"),
                parkourStart("hard"), finish(), ringsStart(), ringsHow(), boatStart(2), boatRun(), boatHop(),
                boatBigDrop(), boatDrop(1), boatDrop(2), boatFinalDrop(), boatSandyBend(), boatSandPit(), boatSplit(),
                boatIceCave(), boatForest()));
        for (int hole = 1; hole <= 18; hole++) {
            out.add(golfTee(hole, 6));
            for (TeeFeature f : TeeFeature.values()) {
                out.add(golfTee(hole, 6, f));
            }
        }
        for (int levels = 1; levels <= 5; levels++) {
            for (int level = 1; level <= levels; level++) {
                out.add(dropperLevel(level, levels));
            }
        }
        return out;
    }

    private GenCopy() {
    }
}
