package com.dierks.homecraft.games.cup;

/**
 * Which Weekly Cup: one time-trial course in one week (EVENTS-OWNER-DECISIONS §D2). A player enters
 * a Cup once, a Cup is settled once, and both the entry rows and the settlement row are keyed by it.
 *
 * <p>The week is the local epoch day the week starts on, turning at the Fresh Courses' 04:00 on the
 * quests' week start ({@link CupRules#week}), so the Cup is paid at the same moment the week's Fresh
 * Courses change.
 *
 * @param course the course id (a hand-built course's id, or a Fresh or Classic course's id)
 * @param week   the week's first local epoch day ({@code Edition.weekKey})
 */
public record CupKey(String course, long week) {

    public CupKey {
        if (course == null || course.isBlank()) {
            throw new IllegalArgumentException("a Cup needs a course");
        }
    }

    /**
     * The Cup's ref, {@code cup:<course>:<week>}: what a ledger detail or a once-only guard names,
     * in the same shape as {@code SkillRewards.weeklyRef}.
     */
    public String ref() {
        return "cup:" + course + ":" + week;
    }
}
