package com.dierks.homecraft.games.cup;

import java.util.Locale;
import java.util.Set;

/**
 * Which courses run a Weekly Cup (§D2: "opt-in per course, and on by default for Fresh parkour,
 * rings and boat"; EVENTS-RECONCILED decision 3 adds the Dropper). Pure.
 *
 * <ul>
 *   <li><b>A Fresh slot's own course</b> (parkour, Sky Rings, Ice Boat, a Dropper): on by default,
 *       but only while the schedule keeps one layout for each whole Cup week
 *       ({@link CupRules#freshEligible}). With a daily cadence the layout would change mid-week and
 *       void the Cup every day, so an admin can't switch it on either.</li>
 *   <li><b>A hand-built course</b>: off until an admin switches it on
 *       ({@code /hcm games cup on <course>}). Its layout only changes when an admin edits it.</li>
 *   <li><b>A course recalled into a Classics slot</b>: off by default (its window may end mid-week),
 *       and an admin may switch it on; its blocks stay across weeks.</li>
 * </ul>
 * An admin's choice ({@code on}/{@code off}) is kept per course id and wins over the default, and
 * {@code default} forgets it.
 */
public final class CupOptIn {

    /** The course kinds a Fresh slot runs a Cup on by default: parkour, Sky Rings, Ice Boat and the Dropper. */
    public static final Set<String> FRESH_KINDS = Set.of("parkour", "elytra", "boat", "dropper");

    private CupOptIn() {
    }

    /**
     * The course as the opt-in sees it.
     *
     * @param generated Fresh Courses made it
     * @param recalled  it was recalled into a Classics slot
     * @param kind      its kind's id ({@code parkour}, {@code elytra}, {@code boat}, {@code dropper})
     */
    public record Course(boolean generated, boolean recalled, String kind) {

        /** A Fresh slot's own layout (not a recalled Classic). */
        public boolean freshOwn() {
            return generated && !recalled;
        }
    }

    /** Whether {@code c} runs a Cup when no admin has said: a Fresh slot's own course of a Cup kind, on a weekly schedule. */
    public static boolean byDefault(Course c, boolean freshEligible) {
        return c != null && c.freshOwn() && freshEligible && FRESH_KINDS.contains(kind(c));
    }

    /**
     * Whether {@code c} runs a Cup now.
     *
     * @param chosen the admin's choice for the course ({@code true} on, {@code false} off), or
     *               {@code null} for the default
     */
    public static boolean on(Boolean chosen, Course c, boolean freshEligible) {
        if (c == null) {
            return false;
        }
        if (c.freshOwn() && !freshEligible) {
            return false;
        }
        return chosen != null ? chosen : byDefault(c, freshEligible);
    }

    /**
     * Why an admin can't switch the Cup on for {@code c}, or {@code null} when they can: a Fresh slot's
     * own course needs the weekly schedule.
     */
    public static String cantSwitchOn(Course c, boolean freshEligible) {
        if (c != null && c.freshOwn() && !freshEligible) {
            return "Fresh Courses change more often than once a week (games.fresh.cadence, rebuild_day), so a"
                    + " Fresh course's layout would change mid-week and call the Cup off every time.";
        }
        return null;
    }

    private static String kind(Course c) {
        return c.kind() == null ? "" : c.kind().toLowerCase(Locale.ROOT);
    }
}
