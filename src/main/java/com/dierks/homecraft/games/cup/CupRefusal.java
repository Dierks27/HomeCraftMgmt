package com.dierks.homecraft.games.cup;

/**
 * Why a player can't enter a Weekly Cup, in the order {@link CupRules#refusal} checks, and what to
 * tell them. {@code null} means "go ahead" wherever one is returned, as with the framework's
 * {@code Refusal}.
 *
 * <p>Messages are plain words with no colour codes, like {@code Refusal}'s: the caller colours them.
 * They never say "bet" or "wager", because the Cup is a skill contest (§D2 copy).
 */
public enum CupRefusal {
    /** {@code games.cup.enabled} is off, or the entry setting is broken. */
    OFF,
    /** This course doesn't run a Cup. */
    NOT_ON_THIS_COURSE,
    /** This week's Cup on this course was voided (course deleted, changed or closed). */
    CALLED_OFF,
    /**
     * This Cup's week is over: it was settled, or its key isn't the current week's (a screen opened
     * before the 04:00 rollover and clicked after it, before the settlement has run).
     */
    WEEK_OVER,
    /** The player is already in this course's Cup this week (§D2: once per course per week). */
    ALREADY_IN,
    /** The player can't pay the entry. */
    NOT_ENOUGH_TOKENS;

    /** The line the player reads; {@code fee} is the entry in tokens. */
    public String message(int fee) {
        return switch (this) {
            case OFF -> "The Weekly Cup is closed right now.";
            case NOT_ON_THIS_COURSE -> "This course doesn't have a Weekly Cup.";
            case CALLED_OFF -> "This week's Cup on this course was called off. It's back next week.";
            case WEEK_OVER -> "That Cup week has ended. Open the course again for this week's Cup.";
            case ALREADY_IN -> "You're already in this week's Cup on this course.";
            case NOT_ENOUGH_TOKENS -> "You need " + CupText.tokens(fee) + " to enter the Cup.";
        };
    }
}
