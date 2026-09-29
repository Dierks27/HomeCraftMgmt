package com.dierks.homecraft.games.gen.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Every fixed word Daily Courses shows a player, in one place so one test can read it all
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
 */
public final class GenCopy {

    /** A sign shows at most this many lines... */
    public static final int SIGN_LINES = 4;
    /** ...of at most this many characters. */
    public static final int SIGN_CHARS = 15;

    /** Words no Daily Courses line may use (the Games' list, plus "sink" for golf). */
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

    /** The Daily Courses tile on the Courses and Golf tabs. */
    public static final String TILE = "&eToday's Courses &7- new every morning";
    /** A course whose layout today hasn't flipped yet is still yesterday's. */
    public static final String YESTERDAY = "&7Yesterday's course &8- today's is on its way";
    /** Anyone in a building area who isn't mid-run there is moved out at once. */
    public static final String MOVED = "&7A new course is being built here, so we moved you somewhere safe.";
    /** The last warning before a run on an old layout ends. */
    public static final String ONE_MINUTE = "&e1 minute left!";
    /** What an admin editing a course inside a Daily Courses area reads. */
    public static final String EDITOR_REFUSED =
            "&cThat's inside the Daily Courses area (rebuilt every day). Build somewhere else.";
    /** What anyone placing or breaking a block in a Daily Courses area reads. */
    public static final String GUARDED = "&cThis area is built by Daily Courses - use &e/hcm games gen&c.";
    /** What an admin reads for a course edit the course tools can't do on a generated course. */
    public static final String MADE_BY_DAILY = "&7This course is made by Daily Courses - use &e/hcm games gen&7.";
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

    /** A wait as players read it: "11h 2m", "5m", "less than a minute". */
    public static String span(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        if (minutes < 1) {
            return "less than a minute";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return h == 0 ? m + "m" : h + "h " + m + "m";
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
        List<String> out = new ArrayList<>(List.of(TILE, YESTERDAY, MOVED, ONE_MINUTE, EDITOR_REFUSED, GUARDED,
                MADE_BY_DAILY, WINGS_TIP, closed("Easy Parkour"), building("Sky Rings"), comingHere(20),
                comingHere(1), timesUp("daily_golf"), restartSoon("4:00 PM"), newIn(11 * 3_600_000L + 120_000L)));
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
