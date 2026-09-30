package com.dierks.homecraft.games.chance.wheel;

import com.dierks.homecraft.games.RtpLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The Wheel at one stake, as solved (spec §5.5, R1.7): the exact prize on each of the 24 spaces
 * and what that gives back.
 *
 * <p>Every space is equally likely, so the published odds are simply counts ("3 of 24 spaces") and
 * the return is exact: the prizes added up over the ring, divided by 24 times the tokens put in.
 * The screen's tiles, the "How it pays" list, {@code /hcm arcade odds}, the website and the spin
 * itself all read this one object, so what a player is shown is what the wheel pays.
 *
 * @param stake  the tokens put in
 * @param prizes the tokens back on each space, in ring order (clockwise from the top left): 0,
 *               exactly {@code stake} ("your N back"), or more than {@code stake} (a win)
 * @param back   the prizes added up over all 24 spaces (the exact return is {@code back / (24 · stake)})
 * @param k      the scale the prizes above one base came from (for admins; {@code 0} when no space
 *               pays more than the tokens put in)
 */
public record WheelOdds(int stake, List<Integer> prizes, long back, double k) {

    /** How a spin that lands on a space reads. */
    public enum Result {
        /** Nothing back: "No win this time." */
        NOTHING,
        /** Exactly the tokens put in: "Your 10 back", neutral, never called a win. */
        BACK,
        /** More back than was put in. */
        WIN
    }

    public WheelOdds {
        prizes = List.copyOf(prizes);
    }

    /** The exact return, as a fraction. */
    public RtpLimits.Ratio ratio() {
        return new RtpLimits.Ratio(back, (long) WheelSettings.SPACES * stake);
    }

    /** The exact return as a double (0.8958...): publish and floor THIS, never the target. */
    public double rtp() {
        return ratio().value();
    }

    /** The prize on one space (0-23, ring order). */
    public int prize(int space) {
        return prizes.get(space);
    }

    /** How many of the 24 spaces pay exactly {@code prize}. */
    public int spaces(int prize) {
        int n = 0;
        for (int p : prizes) {
            if (p == prize) {
                n++;
            }
        }
        return n;
    }

    /** The different prizes on the ring, biggest first (0 included when a space pays nothing). */
    public List<Integer> distinct() {
        return new ArrayList<>(new TreeSet<>(prizes).descendingSet());
    }

    /** The biggest prize on the ring. */
    public int top() {
        int top = 0;
        for (int p : prizes) {
            top = Math.max(top, p);
        }
        return top;
    }

    /** What a prize means for these tokens in: never a win unless more came back than went in. */
    public Result result(int prize) {
        if (prize <= 0) {
            return Result.NOTHING;
        }
        return prize > stake ? Result.WIN : Result.BACK;
    }

    /** A prize as a player reads it on a tile: "&amp;7Nothing", "&amp;fYour 10 back", "&amp;a17 tokens". */
    public String label(int prize) {
        return switch (result(prize)) {
            case NOTHING -> "&7Nothing";
            case BACK -> "&fYour " + prize + " back";
            case WIN -> "&a" + prize + " tokens";
        };
    }

    /** A prize as the website and admins read it: "nothing", "your 10 back", "17 tokens". */
    public String plain(int prize) {
        return switch (result(prize)) {
            case NOTHING -> "nothing";
            case BACK -> "your " + prize + " back";
            case WIN -> prize + " tokens";
        };
    }

    /** "3 of 24 spaces". */
    public String odds(int prize) {
        return spaces(prize) + " of " + WheelSettings.SPACES + " spaces";
    }

    /** The one line players read: "Gives back about 89 of every 100 tokens". */
    public String giveBack() {
        String line = RtpLimits.playerLine(rtp());
        return Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    /**
     * "How it pays", as lore lines: each different prize with how many spaces show it, biggest
     * first, then the give-back line.
     */
    public List<String> howItPays() {
        List<String> out = new ArrayList<>();
        for (int p : distinct()) {
            out.add(label(p) + " &8- &7" + odds(p));
        }
        out.add("&7" + giveBack() + ".");
        out.add("&7Every space is just as likely.");
        return out;
    }
}
