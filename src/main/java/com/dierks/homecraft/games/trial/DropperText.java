package com.dierks.homecraft.games.trial;

import java.util.List;

/**
 * The words a Dropper run shows a player (EVENTS-DROPPER-SPEC §B.1.1, §B.1.7, §B.1.8; the practice
 * drop of EVENTS-OWNER-DECISIONS D3), in one place and tested: kid-safe, nothing Bedrock can't
 * draw (no code point above U+FFFF), the key facts in item NAMES.
 *
 * <p>A bonk is never a fail: it reads "Bonk!" and says where you go, the clock keeps running, and
 * the result counts bonks as a thing to get better at ("Bonks: 2"), with a clean run called a
 * perfect drop.
 */
public final class DropperText {

    // ---- the practice drop (owner decision D3) -------------------------------------------------

    /** The offer's title. */
    public static final String OFFER_TITLE = "&bPractice first?";
    /** ...and its subtitle. */
    public static final String OFFER_SUBTITLE = "&7Pick one in your hotbar";
    /** The offer in chat, once. */
    public static final String OFFER = "&bWant a practice drop first? &7It isn't timed. Pick &ePractice drop"
            + " &7or &eGo straight &7in your hotbar.";
    /** The action bar while the offer waits. */
    public static final String OFFER_BAR = "&ePractice drop, or go straight? &7Pick one in your hotbar";

    /** The kit item that takes the practice drop: its NAME says it isn't timed (Bedrock). */
    public static final String PRACTICE_ITEM = "&bPractice drop &7(not timed)";
    public static final List<String> PRACTICE_LORE = List.of("&7One drop to try it out.",
            "&7Nothing is timed or counted.");
    /** The kit item that skips it. */
    public static final String STRAIGHT_ITEM = "&aGo straight to the timed run";
    public static final List<String> STRAIGHT_LORE = List.of("&7The 3-2-1 starts at once.");
    /** During the practice: the kit item that ends it early. */
    public static final String TIMED_ITEM = "&aStart timed run";
    public static final List<String> TIMED_LORE = List.of("&7Ends the practice drop.", "&7Then the 3-2-1.");

    /** The practice drop's title. */
    public static final String PRACTICE_TITLE = "&bPractice drop";
    /** ...and its subtitle. */
    public static final String PRACTICE_SUBTITLE = "&7Not timed - have a go!";
    /** The action bar during the practice drop. */
    public static final String PRACTICE_BAR = "&bPractice drop - not counted";
    /** The practice drop splashed. */
    public static final String PRACTICE_SPLASH = "&aSplash! &7That was your practice drop. Now the timed run!";
    /** The practice drop bonked. */
    public static final String PRACTICE_BONK = "&eBonk! &7That was your practice drop. Now the timed run!";
    /** The practice drop ended with "Start timed run". */
    public static final String PRACTICE_SKIPPED = "&7Now the timed run!";

    // ---- the timed run ---------------------------------------------------------------------------

    /** The kit's way back up: a bonk (EVENTS-DROPPER-SPEC §B.1.7), slot 0. */
    public static final String BACK_ITEM = "&eBack to the top &7- of this level";
    public static final List<String> BACK_LORE = List.of("&7Takes you to this level's ledge.",
            "&7The clock keeps running.");
    /** The first bonk of a run adds this tip. */
    public static final String TIP = "&eSteer while you fall to go through the holes!";
    /** The course screen's extra rule. */
    public static final String CLOCK_RULE = "The clock keeps running after a bonk.";
    /** The course screen's Start lore when a practice drop is offered. */
    public static final String PRACTICE_ON_START = "&7You can have one practice drop first.";

    private DropperText() {
    }

    /** "&amp;73 levels: step off, steer through the holes, land in the water. Ready..." */
    public static String ready(int levels) {
        return "&7" + TrialText.levels(levels) + ": step off, steer through the holes, land in the water. Ready...";
    }

    /** A splash that clears a level: the title "&amp;aLevel 2!" ({@code next} is the level now on, 1-based). */
    public static String levelTitle(int next) {
        return "&aLevel " + next + "!";
    }

    /** ...and its subtitle: "&amp;7of 5 - keep going!". */
    public static String levelSubtitle(int levels) {
        return "&7of " + levels + " - keep going!";
    }

    /** A bonk's title. */
    public static final String BONK_TITLE = "&eBonk!";

    /** ...and its subtitle: "&amp;7Back to the top of level 2." ({@code level} 1-based). */
    public static String bonkSubtitle(int level) {
        return "&7Back to the top of " + TrialText.level(level) + ".";
    }

    /** The last splash's title. */
    public static final String SPLASH_TITLE = "&6Splash!";

    /** ...and its subtitle: "&amp;f0:21.4 &amp;7· ★★★" (no stars: just the time). */
    public static String splashSubtitle(long ms, int stars) {
        String time = "&f" + TrialText.time(ms);
        return stars > 0 ? time + " &7· " + com.dierks.homecraft.games.gen.api.Stars.text(stars) : time;
    }

    /** The result's bonk line: "&amp;aNo bonks - perfect drop!", or "&amp;eBonks: 2". */
    public static String bonks(int bonks) {
        return bonks <= 0 ? "&aNo bonks - perfect drop!" : "&eBonks: " + bonks;
    }

    /** "1 bonk", "2 bonks". */
    public static String bonkCount(int n) {
        return n + (n == 1 ? " bonk" : " bonks");
    }

    /**
     * The clock line: "&amp;e0:12.4 &amp;7· level 2 of 5 · 1 bonk" (no bonks: none said), a test run's in
     * pink, a voided one's red and marked.
     *
     * @param level the level on, 1-based
     */
    public static String clock(long ms, int level, int levels, int bonks, boolean test, boolean voided) {
        String head = test ? "&dTest &e" : voided ? "&c" : "&e";
        return head + TrialText.time(ms) + " &7· " + TrialText.level(level, levels)
                + (bonks > 0 ? " · " + bonkCount(bonks) : "") + (voided ? " &8(won't count)" : "");
    }

    /** Every line and name above, with sample values, for the copy test. */
    public static List<String> everyLine() {
        java.util.List<String> out = new java.util.ArrayList<>(List.of(OFFER_TITLE, OFFER_SUBTITLE, OFFER, OFFER_BAR,
                PRACTICE_ITEM, STRAIGHT_ITEM, TIMED_ITEM, PRACTICE_TITLE, PRACTICE_SUBTITLE, PRACTICE_BAR,
                PRACTICE_SPLASH, PRACTICE_BONK, PRACTICE_SKIPPED, BACK_ITEM, TIP, CLOCK_RULE, PRACTICE_ON_START,
                BONK_TITLE, SPLASH_TITLE, DropperLayout.GEOMETRY_REFUSED));
        out.addAll(PRACTICE_LORE);
        out.addAll(STRAIGHT_LORE);
        out.addAll(TIMED_LORE);
        out.addAll(BACK_LORE);
        for (int n = 1; n <= 5; n++) {
            out.add(ready(n));
            out.add(levelTitle(n));
            out.add(levelSubtitle(n));
            out.add(bonkSubtitle(n));
            out.add(bonks(n - 1));
            out.add(splashSubtitle(21_400, n - 1));
            out.add(clock(12_400, n, 5, n - 1, false, false));
            out.add(clock(12_400, n, 5, n - 1, true, false));
            out.add(clock(12_400, n, 5, n - 1, false, true));
        }
        return out;
    }
}
