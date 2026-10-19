package com.dierks.homecraft.arcade.candy;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * STUB, so {@code /hcm candy} compiles before the real shower is in (HALLOWEEN-SPEC §3.2 row 4). The real
 * {@code CandyService} replaces this file whole; these four methods are all {@code HcmCommand} calls on it.
 * Nothing builds one yet, so {@code plugin.candy()} is null and the command says the shower is not running.
 */
public final class CandyService {

    /**
     * {@code /hcm candy now}: a shower for this player now, if D17 lets them have one. Null when it started,
     * else the reason (D17's text: "idle 6 min", "in a game", ...). The command has already checked the window.
     */
    public String showerNow(Player player) {
        throw new UnsupportedOperationException("CandyService stub");
    }

    /** {@code /hcm candy clear}: removes every live candy and clears the queue; nothing counts. How many went. */
    public int clear() {
        throw new UnsupportedOperationException("CandyService stub");
    }

    /** The owner's candy still out (D17's per-player cap), for {@code /hcm candy status}. */
    public int live(UUID owner) {
        throw new UnsupportedOperationException("CandyService stub");
    }

    /**
     * Gives the player their Trick-or-Treat Bag (D15): home from every game and a free slot, then the mark
     * ({@code CandyRules.bagKey}) first and the bag after, with the toast, the title and the burst. Null when it
     * was given, else why not (for the admin). {@code restore} ({@code /hcm candy bag}) gives one even when the
     * mark is already there. Without it ({@code /hcm candy} and {@code /hcm candy add}), a player who isn't
     * owed one gets nothing, and one who can't take it now is owed it (the sweep retries) and told once.
     */
    public String giveBag(Player player, boolean restore) {
        throw new UnsupportedOperationException("CandyService stub");
    }
}
