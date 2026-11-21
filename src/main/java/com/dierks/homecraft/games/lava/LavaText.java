package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.lava.rules.LavaMode;
import com.dierks.homecraft.games.lava.rules.LavaResult;

/**
 * Everything The Floor Is Lava says. The game is played by a four-year-old who can't read yet, so nothing here is
 * needed to play: every moment has its picture, its sound and its sparkle, and these words are the grown-ups' extra.
 * Titles are pictures where they can be (stars, arrows).
 */
public final class LavaText {

    public static final String NAME = "The Floor Is Lava";

    public static final String CLOSED = "&cThe Floor Is Lava isn't open right now.";
    public static final String FIXING = "&7The playroom is being tidied up - try again in a moment!";
    public static final String ALREADY_IN = "&7You're in the playroom already.";
    public static final String CHALLENGE_GOING = "&7A Challenge is going - it won't be long. Try again in a minute!";
    public static final String NO_ROOM = "&cCouldn't take you to the playroom right now.";
    public static final String GUARDED = "&7The playroom builds itself - nothing here can be changed.";
    public static final String MOVED = "&7The playroom is being tidied up, so we moved you somewhere safe.";

    public static final String BACK_IN_CLUB = "&7Back in the Clubhouse!";

    public static final String KIT_LEAVE = "&cLeave game &7- click twice";
    public static final String KIT_START = "&aStart now";
    public static final String KIT_AGAIN = "&a&l▶ Play again";

    public static final String LITTLE_INTRO = "&6Collect every star! &7When the floor rumbles, climb up on the"
            + " furniture. The lava can't hurt you.";
    public static final String GROUP_INTRO = "&6Collect stars together! &7The lava comes a little higher every round,"
            + " and a touch costs the group one star.";
    public static final String CHALLENGE_INTRO = "&6Keep climbing! &7The lava rises every round and never goes down."
            + " Touch it and you're out.";
    public static final String WATCHING = "&7You're watching this one from the top shelf.";

    /** The warning's title: arrows up, no words needed. */
    public static final String CLIMB_BIG = "&6▲ ▲ ▲";
    public static final String CLIMB_SMALL = "&eClimb up!";
    public static final String SAFE_BIG = "&a★";
    public static final String LOST_STAR = "&c-1 ★";
    public static final String WIN_BIG = "&6★ ★ ★";

    private LavaText() {
    }

    /** "The playroom is being used for Group right now." */
    public static String busy(LavaMode mode) {
        return "&7The playroom is being used for " + mode.label() + " right now - join in, or try again soon!";
    }

    public static String full(int max) {
        return "&7The playroom is full (" + max + " players). Try again soon!";
    }

    /** The tile's name: the key fact first, for Bedrock. */
    public static String tile(int playing, LavaMode mode, boolean open) {
        if (!open) {
            return "&6" + NAME + " &7- back soon";
        }
        return "&6" + NAME + " &7- " + (playing == 0 ? "play!" : playing + " playing " + mode.label());
    }

    /** Little's bar: a row of stars, the collected ones gold. */
    public static String starRow(int have, int goal) {
        int n = Math.max(0, Math.min(have, goal));
        return "&6" + "★".repeat(n) + "&8" + "☆".repeat(Math.max(0, goal - n));
    }

    /** Group's bar: the shared count and the rounds as dots. */
    public static String groupBar(int stars, int round, int rounds) {
        int r = Math.max(0, Math.min(round, rounds));
        return "&6★ " + stars + "  &e" + "●".repeat(r) + "&8" + "○".repeat(Math.max(0, rounds - r));
    }

    /** Challenge's bar. */
    public static String challengeBar(int round, int standing) {
        return "&cRound " + round + " &7- " + standing + " standing";
    }

    public static String gatherBar(LavaMode mode, int seconds, int here) {
        return "&e" + mode.label() + " starts in " + seconds + " s &7- " + here + " here";
    }

    public static String gathering(LavaMode mode) {
        return "&6" + NAME + ": " + mode.label() + ". &7Waiting a moment for others - &aStart now &7begins at once.";
    }

    public static String roundTitle(int round, int rounds) {
        return "&eRound " + round + (rounds > 0 ? " of " + rounds : "");
    }

    public static String out(int place, int survived) {
        return "&cThe lava got you! &7You lasted " + rounds(survived) + ".";
    }

    public static String rounds(int n) {
        return n + (n == 1 ? " round" : " rounds");
    }

    public static String stars(int n) {
        return n + (n == 1 ? " star" : " stars");
    }

    /**
     * The result line for one player.
     *
     * @param best their best on this mode's board before this round, or {@code null} for none
     */
    public static String result(LavaResult r, java.util.UUID player, Long best, java.util.function.Function<java.util.UUID,
            String> names) {
        switch (r.mode()) {
            case LITTLE -> {
                return "&6You collected every star! &e" + stars(r.stars()) + "&6.";
            }
            case GROUP -> {
                String beat = best == null ? "" : r.stars() > best ? " &aA new best!" : " &7Best: " + best + ".";
                return "&6Together you collected &e" + stars(r.stars()) + "&6." + beat;
            }
            default -> {
                LavaResult.Standing s = r.standing(player);
                int lasted = s == null ? 0 : s.survived();
                if (r.solo()) {
                    String beat = best == null ? "" : lasted > best ? " &aA new best!" : " &7Best: " + best + ".";
                    return "&6You lasted &e" + rounds(lasted) + "&6." + beat;
                }
                java.util.List<String> winners = new java.util.ArrayList<>();
                for (java.util.UUID w : r.winners()) {
                    String n = names == null ? null : names.apply(w);
                    winners.add(n == null ? "someone" : n);
                }
                boolean won = s != null && s.place() == 1;
                return (won ? "&6You're the last one standing! " : "&6Last one standing: &e" + String.join(", ",
                        winners) + "&6. ") + "&7You lasted " + rounds(lasted) + ".";
            }
        }
    }

    /** The end title's small line. */
    public static String endSmall(LavaResult r, java.util.UUID player) {
        return switch (r.mode()) {
            case LITTLE -> "&eEvery star!";
            case GROUP -> "&e" + stars(r.stars());
            case CHALLENGE -> {
                LavaResult.Standing s = r.standing(player);
                yield s != null && s.place() == 1 && !r.solo() ? "&eLast one standing!"
                        : "&e" + rounds(s == null ? 0 : s.survived());
            }
        };
    }
}
