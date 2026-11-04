package com.dierks.homecraft.arcade.candy;

/**
 * Every word a shower says (0.38.6), so one engine can speak as the Candy Apple shower or the Harvest Basket.
 * Plain strings: the reward's own words are {@link ShowerReward}'s, the sounds {@link ShowerFx}'s.
 *
 * @param name          the shower's name in logs and admin lines: "Candy Apple shower", "Harvest Basket"
 * @param title         the shower's title, and {@code subtitle} under it
 * @param goldenLine    the subtitle under "GOLDEN!" at a golden pickup
 * @param goldenReason  the token history's reason for a golden pickup
 * @param one           one item, lower case, for logs: "candy apple", "treat"; {@code many} more than one
 * @param leftoverOne   one leftover on the ground, for the start's sweep: "candy", "treat"; {@code leftoverMany}
 * @param joinHead      the start of the first-join line, up to "fall every": "&6✦ Candy apples", "&6Treats"
 * @param barNoun       the bar's and the action bar's noun: "Candy apples", "Harvest Basket"
 * @param barMark       the mark between the count and the reward on the bar: "·", or "-" in plain letters
 */
public record ShowerText(String name, String title, String subtitle, String goldenLine, String goldenReason,
                         String one, String many, String leftoverOne, String leftoverMany, String joinHead,
                         String barNoun, String barMark) {

    /** The Candy Apple shower's words, exactly as 0.38.5 said them. */
    public static ShowerText candy() {
        return new ShowerText("Candy Apple shower", "&6Trick or treat!", "&eCandy apples are falling!",
                "&eA Golden Candy Apple!", "Golden Candy Apple", "candy apple", "candy apples", "candy", "candies",
                "&6✦ Candy apples", "Candy apples", "·");
    }

    /** The Harvest Basket's words: plain letters only, for Bedrock. */
    public static ShowerText harvest() {
        return new ShowerText("Harvest Basket", "&6Harvest time!", "&eTreats are falling!", "&eA Golden Pie!",
                "Golden Pie", "treat", "treats", "treat", "treats", "&6Treats", "Harvest Basket", "-");
    }

    /** "the Harvest Basket": the name in a sentence. */
    public String label() {
        return "the " + name;
    }

    /** "The Harvest Basket": the name starting a sentence. */
    public String sentence() {
        return "The " + name;
    }

    /** The action bar after a counted pickup ({@link CandyRules#actionBar(long, int, String)}). */
    public String actionBar(long found, int goal) {
        return CandyRules.actionBar(found, goal, barNoun);
    }

    /** The boss bar's line, with the reward's word ("bag", "hat"). */
    public String barLine(long found, int goal, boolean waiting, String reward) {
        return CandyRules.barLine(found, goal, waiting, barNoun, reward, barMark);
    }

    /**
     * The line on the first join of a local day while the shower is on: how often and until when, then the
     * player's count ({@code until} is "31 Oct").
     */
    public String joinLine(int every, String until, long found, int goal) {
        return joinHead + " fall every " + every + " minutes until " + until + "! &7You've found &f" + found
                + (found < goal ? " &7of " + goal : "") + "&7.";
    }
}
