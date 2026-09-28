package com.dierks.homecraft.games.trial;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The words the time trials use, in one place and tested: how a time reads ("1:02.3"), how a
 * course is labelled ("Boat · Medium"), what a course id may look like, and how an admin's typed
 * name is cleaned before a player reads it.
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

    /** "1 token", "10 tokens". */
    public static String tokens(int n) {
        return n + " token" + (n == 1 ? "" : "s");
    }

    /** Whether {@code id} may name a course (the namespace clash checks are the game's). */
    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
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
