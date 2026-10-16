package com.dierks.homecraft.games.cup;

import com.dierks.homecraft.games.trial.TrialText;

/**
 * The Weekly Cup's words, in one place and tested (§D2 copy): the entry prompt, the live pool line,
 * the ledger details and the line each entrant reads when their Cup ends.
 *
 * <p>Plain words with no colour codes (the caller colours them) and no emoji. The Cup is a skill
 * contest, so the words are "enter", "pool", "place" and "time", never "bet" or "wager", and a
 * result is stated as it is: no "so close", no "almost", and no nothing dressed up as a win.
 */
public final class CupText {

    private CupText() {
    }

    /** "Enter this week's Cup: 10 tokens. Best time wins the pool." */
    public static String enterPrompt(int fee) {
        return "Enter this week's Cup: " + tokens(fee) + ". Best time wins the pool.";
    }

    /** "Cup pool: 35 tokens · 5 in". */
    public static String poolLine(int pool, int in) {
        return "Cup pool: " + tokens(pool) + " · " + in + " in";
    }

    /** "1 token", "5 tokens". */
    public static String tokens(int n) {
        return TrialText.tokens(n);
    }

    /** "1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "101st". */
    public static String ordinal(int n) {
        int mod100 = Math.floorMod(n, 100);
        int mod10 = Math.floorMod(n, 10);
        String suffix;
        if (mod100 >= 11 && mod100 <= 13) {
            suffix = "th";
        } else if (mod10 == 1) {
            suffix = "st";
        } else if (mod10 == 2) {
            suffix = "nd";
        } else if (mod10 == 3) {
            suffix = "rd";
        } else {
            suffix = "th";
        }
        return n + suffix;
    }

    /** Why a Cup was voided, as the end of a sentence: "the course was removed". */
    public static String because(CupPlan.VoidReason reason) {
        return switch (reason) {
            case DELETED -> "the course was removed";
            case CHANGED -> "the course changed";
            case CLOSED -> "the course closed";
            case STOPPED -> "an admin stopped it";
        };
    }

    /** The ledger detail of an entry: "Weekly Cup entry: Sky Rings". */
    public static String entryDetail(String course) {
        return "Weekly Cup entry: " + course;
    }

    /**
     * The ledger detail of a payment: "Weekly Cup: 1st on Sky Rings", "Weekly Cup: tied 2nd on Sky
     * Rings", "Weekly Cup refund: Sky Rings". {@code null} for a line that pays nothing.
     */
    public static String payoutDetail(CupPayout line, String course) {
        return switch (line.kind()) {
            case PRIZE -> "Weekly Cup: " + place(line) + " on " + course;
            case REFUND -> "Weekly Cup refund: " + course;
            case NONE -> null;
        };
    }

    /**
     * The line an entrant reads when the Cup on {@code course} ends (a queued notice if they are
     * offline), with the reason for any refund (§D2: "the player is told why").
     */
    public static String result(CupPlan plan, CupPayout line, String course) {
        String cup = "Weekly Cup on " + course;
        return switch (plan.outcome()) {
            case EMPTY -> cup + ": nobody entered this week.";
            case REFUND_ALONE -> "Nobody else entered the " + cup + ", so your " + tokens(line.tokens())
                    + " came back.";
            case REFUND_NO_CONTEST -> "Fewer than 2 Cup times were set in the " + cup + ", so your "
                    + tokens(line.tokens()) + " came back.";
            case VOIDED -> "The " + cup + " was called off because " + because(plan.reason()) + ". Your "
                    + tokens(line.tokens()) + " came back.";
            case PRIZES -> {
                if (line.place() <= 0) {
                    yield cup + ": you didn't set a Cup time this week.";
                }
                String how = cup + ": you came " + place(line) + " with " + TrialText.time(line.bestMs());
                yield line.tokens() > 0 ? how + " - " + tokens(line.tokens()) + "." : how + ".";
            }
        };
    }

    /** "1st", or "tied 2nd" when the place is shared. */
    static String place(CupPayout line) {
        return (line.tied() > 1 ? "tied " : "") + ordinal(line.place());
    }
}
