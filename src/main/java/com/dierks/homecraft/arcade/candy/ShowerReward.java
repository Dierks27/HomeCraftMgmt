package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.entity.Player;

/**
 * What a shower gives at its goal (0.38.6): the Trick-or-Treat Bag ({@link CandyBag}) or the Pilgrim Hat
 * ({@link PilgrimHat}). The service keeps the order the bag always had: the mark is written first
 * ({@link CandyRules#handOver}), then {@link #give} runs, only when the player is home from every game and
 * {@link #hasRoom}; a reward that waits is tried again every second, and one marked but lost is restored by an
 * admin with {@code /hcm <shower> <word> <player>}.
 */
public interface ShowerReward {

    /** Its name without the year, for lines and logs: "Trick-or-Treat Bag", "Pilgrim Hat". */
    String title();

    /** Its one word: the end of its mark ({@link CandyRules#rewardKey}), the bar's word and the admin verb. */
    String word();

    /** Whether it can be handed over now without anything going on the ground. */
    boolean hasRoom(Player p);

    /** Hand it over (its mark is already written): true when the player has it. */
    boolean give(HomeCraftManagement plugin, Player p, int year, int goal);

    /** The moment once it is given: a sound, a title and a firework, and a line when it helps. */
    void moment(Player p, int year, int goal);

    /** The mark's last part ({@code candy:2026:bag}). */
    default String markSuffix() {
        return word();
    }

    /** How an admin gives it back, for a shower whose command word is {@code showerId}. */
    default String restoreHint(String showerId) {
        return "/hcm " + showerId + " " + word();
    }
}
