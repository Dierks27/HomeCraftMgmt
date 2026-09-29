package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.gen.api.GenTag;

import java.util.Locale;

/**
 * Which layout a Cup is being raced on, as the Cup remembers it (§D2: "if the course changes layout
 * mid-week, every entry is refunded"). It is written down when the Cup's first player enters and
 * compared with the course as it is now by {@link CupWatch}, so a Cup is voided by what really
 * happened to the course, whichever command or engine did it.
 *
 * <p><b>What "the layout" is.</b> For a hand-built course: its kind and its geometry (the world,
 * start, checkpoints, finish and fall height: {@code Course.layoutHash()}). A rename, a new tier or a
 * new shortest time is not a new layout. For a Fresh course: its slot, edition key (with any reroll),
 * the plan's hash and any recall. A heal of the same layout ({@code /hcm games gen rebuild}) keeps
 * all of them, so it voids nothing.
 *
 * <p><b>Why a Fresh layout keeps its days.</b> A slot's own layout counts only for the Cup weeks its
 * edition was made for ({@link CupRules#runWeeks}). While last week's layout still stands after the
 * rollover (the new one isn't built yet), a Cup entered then remembers a layout that doesn't
 * {@link #covers} its week, and the scheduled flip to the week's own layout is the Cup's layout going
 * up, not a change ({@link CupWatch}).
 *
 * @param generated whether Fresh Courses made it
 * @param recalled  a course recalled into a Classics slot (its blocks stay across weeks)
 * @param day       a Fresh layout's edition's first day (local epoch day); 0 for a hand-built course
 * @param endDay    the day its edition ends (the next one starts); 0 for a hand-built course
 * @param print     everything that makes it this layout, as one line of text
 */
public record CupLayout(boolean generated, boolean recalled, long day, long endDay, String print) {

    private static final String SEP = ";";

    public CupLayout {
        print = print == null ? "" : print;
    }

    /** A hand-built course: its kind ({@code parkour}) and {@code Course.layoutHash()}. */
    public static CupLayout handBuilt(String kind, int layoutHash) {
        return new CupLayout(false, false, 0, 0,
                "h:" + (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) + ":" + Integer.toHexString(layoutHash));
    }

    /** A Fresh course (a slot's own layout, or a recalled one) from its tag. */
    public static CupLayout fresh(GenTag tag) {
        GenTag.Recall r = tag.recall();
        String print = "g:" + tag.slot() + ":" + tag.editionKey() + ":" + tag.planHash()
                + (r == null ? "" : ":recall:" + r.slot() + "@" + r.from());
        return new CupLayout(true, r != null, tag.day(), tag.endDay(), print);
    }

    /**
     * Whether runs on this layout can set times in the Cup of week {@code week}: a hand-built or
     * recalled course always can; a slot's own layout only in the weeks its edition was made for
     * ({@code day} to {@code endDay - 1}, as {@link CupRules#runWeeks} counts them).
     */
    public boolean covers(long week) {
        return !generated || recalled || (day <= week && week <= endDay - 1);
    }

    /** Whether {@code other} is the same layout. */
    public boolean same(CupLayout other) {
        return other != null && print.equals(other.print);
    }

    /** One line to keep: {@code generated;recalled;day;endDay;print}. */
    public String encode() {
        return (generated ? "1" : "0") + SEP + (recalled ? "1" : "0") + SEP + day + SEP + endDay + SEP + print;
    }

    /** A layout read back, or {@code null} when {@code text} isn't one {@link #encode} wrote. Never throws. */
    public static CupLayout decode(String text) {
        if (text == null) {
            return null;
        }
        String[] p = text.split(SEP, 5);
        if (p.length != 5 || p[4].isEmpty()) {
            return null;
        }
        try {
            return new CupLayout("1".equals(p[0]), "1".equals(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]), p[4]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
