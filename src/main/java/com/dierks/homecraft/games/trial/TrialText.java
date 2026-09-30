package com.dierks.homecraft.games.trial;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The words the time trials use, in one place and tested: how a time reads ("1:02.3"), how a
 * course is labelled ("Boat · Medium"), a Dropper's levels ("level 2 of 5"), a Mountain Run's drops
 * ("5 drops"), what a course id may look like, and how an admin's typed name is cleaned before a
 * player reads it.
 */
public final class TrialText {

    /** A course id: lower-case, starts with a letter, 2-32 of letters, digits and underscores. */
    static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{1,31}");
    /** A course name is cut to this many characters. */
    static final int MAX_NAME = 32;

    private TrialText() {
    }

    /**
     * A time in milliseconds as minutes, seconds and tenths ("1:02.3"), the tenths floored — so a
     * shown time is never better than the real one, and it reads like the high-score screens.
     */
    public static String time(long ms) {
        long t = Math.max(0, ms);
        long minutes = t / 60_000;
        long seconds = (t / 1000) % 60;
        long tenths = (t / 100) % 10;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds + "." + tenths;
    }

    /** "Boat · Medium". */
    public static String label(Course course) {
        return course.kind().label() + " · " + course.tier().label();
    }

    /** "1 checkpoint", "5 checkpoints". */
    public static String checkpoints(int n) {
        return n + " checkpoint" + (n == 1 ? "" : "s");
    }

    /** A Dropper's "1 level", "5 levels" (EVENTS-DROPPER-SPEC §B.1.8). */
    public static String levels(int n) {
        return n + " level" + (n == 1 ? "" : "s");
    }

    /** A Mountain Run's "1 drop", "5 drops" (COURSE-VARIETY-SPEC §5.2): a Hop and a Big Drop are one drop each. */
    public static String drops(int n) {
        return n + " drop" + (n == 1 ? "" : "s");
    }

    /** "level 2" (1-based). */
    public static String level(int level) {
        return "level " + level;
    }

    /** "level 2 of 5" (1-based), a Dropper's progress. */
    public static String level(int level, int of) {
        return "level " + level + " of " + of;
    }

    /**
     * What a course asks of a run, as its screen says it: "5 checkpoints, then the finish", or a
     * Dropper's "3 levels, down to the water".
     */
    public static String route(Course c) {
        if (c.kind() == TrialKind.DROPPER) {
            return levels(DropperLayout.levels(c)) + ", down to the water";
        }
        return checkpoints(c.checkpoints().size()) + ", then the finish";
    }

    /** "1 token", "10 tokens". */
    public static String tokens(int n) {
        return n + " token" + (n == 1 ? "" : "s");
    }

    /** Whether {@code id} may name a course (the namespace clash checks are the game's). */
    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * Whether {@code id} is a word another command keeps for itself, so no course may be called it:
     * {@code auto} is how {@code /hcm games feature} lets the day pick again, so a course called
     * that could never be pinned.
     */
    public static boolean keptWord(String id) {
        return id != null && id.trim().equalsIgnoreCase("auto");
    }

    /**
     * The finish line for a new personal best: "★ New best! (was 0:13.1)"; with no time before it
     * on the board, "★ Your first finish on Cliffs!" only when that first finish is being paid
     * ({@code firstFinish}) — after a layout change or a course made again the board is empty but
     * the first finish was long ago, so it's simply "★ New best!".
     */
    public static String bestLine(String courseName, Long previous, boolean firstFinish) {
        if (previous != null) {
            return "&e★ New best! &7(was " + time(previous) + ")";
        }
        return firstFinish ? "&e★ Your first finish on " + courseName + "!" : "&e★ New best!";
    }

    /** A course's first name from its id: {@code river_run} → "River Run". */
    public static String defaultName(String id) {
        if (id == null || id.isBlank()) {
            return "Course";
        }
        StringBuilder out = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return out.isEmpty() ? "Course" : out.toString();
    }

    /**
     * An admin's typed name made safe for a player's screen: colour codes out (the screens colour
     * it), nothing Bedrock can't draw (no code point above U+FFFF), spaces squeezed, at most
     * {@value #MAX_NAME} characters. {@code null} when nothing is left.
     */
    public static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("(?i)[&§][0-9a-fk-orx]", "");
        StringBuilder out = new StringBuilder();
        s.codePoints().filter(cp -> cp <= 0xFFFF && !Character.isISOControl(cp) && !Character.isSurrogate((char) cp))
                .forEach(out::appendCodePoint);
        String clean = out.toString().replaceAll("\\s+", " ").trim();
        if (clean.length() > MAX_NAME) {
            clean = clean.substring(0, MAX_NAME).trim();
        }
        return clean.isEmpty() ? null : clean;
    }
}
