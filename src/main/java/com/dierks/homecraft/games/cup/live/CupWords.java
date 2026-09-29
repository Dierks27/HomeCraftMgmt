package com.dierks.homecraft.games.cup.live;

import com.dierks.homecraft.games.cup.CupEntry;
import com.dierks.homecraft.games.cup.CupPlan;
import com.dierks.homecraft.games.cup.CupRefusal;
import com.dierks.homecraft.games.cup.CupText;
import com.dierks.homecraft.games.trial.TrialText;

import java.util.ArrayList;
import java.util.List;

/**
 * The Weekly Cup's words on the screens, tiles and commands (§D2 copy), in one place and tested:
 * {@link CupText} holds the rules' own lines ("Enter this week's Cup: 5 tokens. Best time wins the
 * pool.", "Cup pool: 35 tokens · 5 in", each entrant's result), and this class colours and places
 * them. The key facts go in item NAMES, since Bedrock shows lore only on tap-and-hold.
 *
 * <p>For kids, and a skill contest: "enter", "pool", "time" and "place", never "bet" or "wager";
 * no emoji and nothing above U+FFFF.
 */
public final class CupWords {

    /** The three rules, for the Cup screen and {@code /hcm games status}. */
    public static final List<String> RULES = List.of(
            "Pay once this week to be in this course's Cup.",
            "Your best counted time this week is your Cup time.",
            "At the week's end the pool is shared by Cup time.");

    /**
     * How the pool is shared, line by line (the Cup screen's "How it's paid"): by Cup times, not by
     * who entered, exactly as {@code CupRules.settle} pays, with the rule for an entrant who sets no
     * Cup time.
     */
    public static final List<String> SHARES = List.of(
            "2 Cup times: 70% and 30%.",
            "3 or more Cup times: 50%, 30% and 20%.",
            "Fewer than 2 Cup times at the end? Every entry comes back.",
            "No Cup time? No share: your entry stays in the pool.",
            "Warm-ups and test runs never count.",
            "The server keeps nothing: every token is paid out.");

    /** The Cup screen's "How it's paid" item NAME: the shares, for Bedrock. */
    public static final String SHARES_NAME = "&eHow it's paid: 70/30 for 2 Cup times, 50/30/20 for 3 or more";

    private CupWords() {
    }

    /**
     * The course screen's Cup item NAME, by where the viewer stands, with the key facts for Bedrock
     * (which shows lore only on tap-and-hold): the live pool, and once you're in your Cup time.
     */
    public static String buttonName(CupDesk.View v) {
        String pool = CupText.poolLine(v.pool().tokens(), v.pool().in());
        if (v.settledEarly()) { // paid out already, even to those who were in it
            return "&7Weekly Cup &8- &7" + shortWhy(CupRefusal.WEEK_OVER);
        }
        if (v.in()) {
            return "&6You're in this week's Cup &7- " + timeWord(v.mine()) + " &7- " + pool;
        }
        if (v.refusal() == null || v.refusal() == CupRefusal.NOT_ENOUGH_TOKENS) {
            return "&6" + CupText.enterPrompt(v.fee()) + " &e" + pool;
        }
        return "&7Weekly Cup &8- &7" + shortWhy(v.refusal());
    }

    /** Your Cup time for a NAME: "your time &amp;f0:40.0", or "no Cup time yet". */
    static String timeWord(CupEntry mine) {
        return mine == null || !mine.hasTime() ? "no Cup time yet" : "your time &f" + TrialText.time(mine.bestMs());
    }

    /** The course screen's Cup item lore. */
    public static List<String> buttonLore(CupDesk.View v, String endsAt) {
        List<String> out = new ArrayList<>();
        if (v.settledEarly()) { // settled early: nothing is running, and it isn't paid at the week's end
            out.add("&7" + CupRefusal.WEEK_OVER.message(v.fee()));
            out.add("&eClick to see the Cup");
            return out;
        }
        out.add("&e" + CupText.poolLine(v.pool().tokens(), v.pool().in()));
        if (v.in()) {
            out.add(yourTime(v.mine()));
        }
        out.add("&7Paid " + endsAt + ".");
        out.add("&eClick to see the Cup");
        return out;
    }

    /** The Cup screen's enter item NAME: what a click does, or why it can't. */
    public static String enterName(CupDesk.View v) {
        if (v.settledEarly()) {
            return "&7" + CupRefusal.WEEK_OVER.message(v.fee());
        }
        if (v.in()) {
            return "&aYou're in this week's Cup &7- " + timeWord(v.mine());
        }
        if (v.refusal() == null) {
            return "&aPay " + CupText.tokens(v.fee()) + " and enter this week's Cup";
        }
        return "&7" + v.refusal().message(v.fee());
    }

    /** The viewer's Cup time so far. */
    public static String yourTime(CupEntry mine) {
        return mine == null || !mine.hasTime() ? "&7No Cup time yet: finish a timed run!"
                : "&7Your Cup time: &f" + TrialText.time(mine.bestMs());
    }

    /** A short word for why a Cup is shut, for a NAME. */
    static String shortWhy(CupRefusal r) {
        return switch (r) {
            case OFF -> "closed right now";
            case NOT_ON_THIS_COURSE -> "not on this course";
            case CALLED_OFF -> "called off this week";
            case WEEK_OVER -> "already paid out this week";
            case ALREADY_IN -> "you're in";
            case NOT_UP_YET -> "starts when this week's course is up";
            case NOT_ENOUGH_TOKENS -> "not enough tokens";
        };
    }

    /**
     * What a course's tile adds to its NAME, the key facts for Bedrock (which shows lore only on
     * tap-and-hold): " · in the Cup, pool 35" once the viewer is in; " · Cup not open yet" while a
     * Fresh course is still on last week's layout; only the pool while the viewer can't enter (entries
     * closed); else what it costs, and the pool once anyone is in (" · Cup: 5 tokens, pool 35");
     * empty when the Cup isn't shown.
     */
    public static String tileSuffix(CupDesk.View v) {
        if (v == null || !v.shown() || v.settledEarly()) { // paid out early: only the course screen says so
            return "";
        }
        String pool = "pool " + v.pool().tokens();
        if (v.in()) {
            return " &6· in the Cup, " + pool;
        }
        if (v.refusal() == CupRefusal.NOT_UP_YET) {
            return " &7· Cup not open yet";
        }
        if (!v.open() || (v.refusal() != null && v.refusal() != CupRefusal.NOT_ENOUGH_TOKENS)) {
            return " &6· Cup " + pool;
        }
        return " &6· Cup: " + CupText.tokens(v.fee()) + (v.pool().in() > 0 ? ", " + pool : "");
    }

    /** What a course's tile adds to its lore: the prompt (or that you're in) and the pool. */
    public static List<String> tileLines(CupDesk.View v) {
        List<String> out = new ArrayList<>();
        if (v == null || !v.shown() || v.settledEarly()) {
            return out;
        }
        if (v.in()) {
            out.add("&6You're in this week's Cup");
            out.add(yourTime(v.mine()));
        } else if (v.open() && (v.refusal() == null || v.refusal() == CupRefusal.NOT_ENOUGH_TOKENS)) {
            out.add("&6" + CupText.enterPrompt(v.fee()));
        } else if (v.refusal() == CupRefusal.NOT_UP_YET) {
            out.add("&7" + CupRefusal.NOT_UP_YET.message(v.fee()));
        }
        out.add("&e" + CupText.poolLine(v.pool().tokens(), v.pool().in()));
        return out;
    }

    /** The line an entrant reads the moment they are in. */
    public static String entered(String course, int fee) {
        return "&aYou're in this week's Cup on " + course + " (" + CupText.tokens(fee) + ")."
                + " &7Your best counted time this week is your Cup time.";
    }

    /** The line a finish adds when it set a new Cup time. */
    public static String newCupTime(String course, long ms) {
        return "&6New Cup time on " + course + ": &f" + TrialText.time(ms);
    }

    /**
     * The line a finish adds when the player is in this week's Cup but the run was on last week's
     * layout of a Fresh course (it still standing after the rollover): why it set no Cup time.
     */
    public static String lastWeeksLayout() {
        return "&7That run was on last week's course, so it doesn't set a Cup time."
                + " The Cup starts when this week's course is up.";
    }

    /** {@code /hcm play cup}: whether the prompts show. */
    public static String promptsState(boolean shown) {
        return "&eWeekly Cup prompts: " + (shown ? "&aon" : "&7off") + " &7- /hcm play cup on|off";
    }

    /** {@code /hcm play cup on|off}: the switch done. */
    public static String promptsSet(boolean shown) {
        return shown ? "&aYou'll see the Weekly Cup on the course screens again."
                : "&7The Weekly Cup is hidden on the course screens. &8(/hcm play cup on)"
                + " &7If you're in a Cup, you still hear how it went.";
    }

    /** The admin's word for how a Cup ended. */
    public static String outcome(CupPlan.Outcome o) {
        return switch (o) {
            case EMPTY -> "nobody entered";
            case PRIZES -> "paid out";
            case REFUND_ALONE -> "refunded (one entrant)";
            case REFUND_NO_CONTEST -> "refunded (fewer than 2 Cup times)";
            case VOIDED -> "called off and refunded";
        };
    }
}
