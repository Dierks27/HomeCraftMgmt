package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.trial.TrialText;

/**
 * Everything the Clubhouse's walls say (0.38.3; the owner: "display the top runs of the week and such ... Maybe
 * some stats or something? Have to make it a reason to be there after all"): this week's best, now playing and my
 * stats, and the My stats item and screen. Kid-safe like the rest of the Clubhouse's copy (the scan,
 * {@code ClubhouseCopyTest}, reads this file), no emoji, and short plain lines: Bedrock shows a board as a floating
 * name tag, one line each, so a name is cut to {@value #NAME_MAX} characters and a course to {@value #COURSE_MAX}.
 */
public final class ClubBoardText {

    /** Names and courses longer than these are cut, with "..." (plain ASCII). */
    static final int NAME_MAX = 16;
    static final int COURSE_MAX = 18;

    private ClubBoardText() {
    }

    // ---- the kit and the screen -----------------------------------------------------------------------

    /** Kit slot 7: the key fact in the NAME (Bedrock). */
    public static final String KIT_STATS = "&dMy stats &7- your times and tokens";
    /** What My stats says before its numbers have been read (they are read off the main thread). */
    public static final String COUNTING = "&7Counting your stats - one moment.";
    public static final String ONLY_YOU = "&8Only you can see this";

    // ---- this week's best ----------------------------------------------------------------------------

    public static final String FAME_TITLE = "&6&lThis week's best";
    public static final String WEEK_TITLE = "&6&lThis week";
    public static final String RECORDS_TITLE = "&6&lAll-time records";
    public static final String NO_RUNS = "&7No runs yet this week - be the first!";
    public static final String NO_RECORDS = "&7No records yet - be the first!";
    public static final String NO_GOLF = "&7No golf rounds yet this week";
    public static final String NO_PICK = "&7No pick today";
    /** Before the first read (a moment after the Clubhouse opens). */
    public static final String READING = "&7Reading the boards - one moment.";

    /** "&amp;fSky Rings &amp;7- &amp;eSam 1:02.4": a course's fastest run (this week's, or its record). */
    public static String courseTime(String course, String name, long ms) {
        return "&f" + course(course) + " &7- &e" + name(name) + " " + TrialText.time(ms);
    }

    /** "&amp;fTiny Golf &amp;7- &amp;eSam 24 (-2)": a golf course's record. */
    public static String golfRecord(String course, String name, int strokes, int vsPar) {
        return "&f" + course(course) + " &7- &e" + name(name) + " " + strokes + " (" + vs(vsPar) + ")";
    }

    /** "&amp;dGolf: &amp;fSam &amp;7- Tiny Golf 24 (-2)": the week's best round. */
    public static String golfWeek(String course, String name, int strokes, int vsPar) {
        return "&dGolf: &f" + name(name) + " &7- " + course(course) + " " + strokes + " ("
                + vs(vsPar) + ")";
    }

    /** "&amp;eToday's pick: &amp;fSky Rings &amp;7(+5 tokens)". */
    public static String pick(String pick, int bonus) {
        if (pick == null || pick.isBlank()) {
            return NO_PICK;
        }
        return "&eToday's pick: &f" + course(pick) + (bonus > 0 ? " &7(+" + bonus + (bonus == 1 ? " token)"
                : " tokens)") : "");
    }

    /** "&amp;bWeekly Cup: &amp;fSky Rings &amp;7- Sam 1:02.4": a course's Cup leader this week. */
    public static String cup(String course, String name, long ms) {
        return "&bWeekly Cup: &f" + course(course) + " &7- " + name(name) + " " + TrialText.time(ms);
    }

    /** A page's title, with "(2/3)" when the list runs over more than one page. */
    public static String paged(String title, int page, int pages) {
        return pages > 1 ? title + " &7(" + page + "/" + pages + ")" : title;
    }

    // ---- now playing ---------------------------------------------------------------------------------

    public static final String NOW_TITLE = "&b&lNow playing";
    public static final String NOBODY = "&7Nobody's playing right now.";
    public static final String CHEER = "&7Cheer them on: /hcm play cheer";
    public static final String IN_CLUBHOUSE = "in the Clubhouse";
    public static final String WATCHING = "watching live";
    public static final String STARTING = "starting";
    public static final String WARMING_UP = "warming up";
    public static final String RACING = "racing";
    public static final String FINISHED = "finished";

    /** "checkpoint 3". */
    public static String checkpoint(int n) {
        return "checkpoint " + n;
    }

    /** "hole 4". */
    public static String hole(int n) {
        return "hole " + n;
    }

    /** "&amp;7and 3 more". */
    public static String more(int n) {
        return "&7and " + n + " more";
    }

    /** "&amp;fSam &amp;7- &amp;eSky Rings &amp;7- checkpoint 3 &amp;7- &amp;f0:41": who, the game, how far, how long. */
    public static String playing(String name, String what, String where, long ms) {
        return "&f" + name(name) + " &7- &e" + course(what) + (where == null || where.isBlank() ? "" : " &7- " + where)
                + (ms > 0 ? " &7- &f" + clock(ms) : "");
    }

    /** "0:41", "12:05": minutes and seconds (a board's clock; the times on the boards keep their tenths). */
    public static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ---- my stats ------------------------------------------------------------------------------------

    public static final String STATS_TITLE = "&d&lMy stats";

    /** "&amp;d&amp;lMy stats &amp;7- Sam". */
    public static String statsTitle(String name) {
        return STATS_TITLE + " &7- " + name(name);
    }

    /** "&amp;7Runs this week: &amp;f12". */
    public static String runs(int n) {
        return "&7Runs this week: &f" + n;
    }

    /** "&amp;7Tokens from games this week: &amp;f45". */
    public static String tokens(int n) {
        return "&7Tokens from games this week: &f" + n;
    }

    /** "&amp;7Best golf: &amp;fTiny Golf 24 (-2)", or none yet. */
    public static String bestGolf(String course, int strokes, int vsPar) {
        return course == null ? "&7Best golf: &fnone yet" : "&7Best golf: &f" + course(course) + " " + strokes + " ("
                + vs(vsPar) + ")";
    }

    /** "&amp;fSky Rings &amp;71:02.4 &amp;e#2": a personal best and its place on the course's board. */
    public static String best(String course, long ms, int rank) {
        return "&f" + course(course) + " &7" + TrialText.time(ms) + (rank > 0 ? " &e#" + rank : "");
    }

    /** "&amp;7No course times yet - go set one!". */
    public static final String NO_BESTS = "&7No course times yet - go set one!";

    /** Strokes against par as the results board says it: "par", "+3", "-2". */
    public static String vs(int vsPar) {
        return vsPar == 0 ? "par" : (vsPar > 0 ? "+" : "") + vsPar;
    }

    // ---- cutting -------------------------------------------------------------------------------------

    /** A player's name as a board shows it: never blank, at most {@value #NAME_MAX} characters. */
    public static String name(String name) {
        return cut(name == null || name.isBlank() ? "someone" : name, NAME_MAX);
    }

    /** A course's (or a game's) name as a board shows it: at most {@value #COURSE_MAX} characters. */
    public static String course(String course) {
        return cut(course == null || course.isBlank() ? "a course" : course, COURSE_MAX);
    }

    /** {@code s} cut to {@code max} characters, the last three "..." when cut. */
    static String cut(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 3)).stripTrailing() + "...";
    }
}
