package com.dierks.homecraft.games;

import org.bukkit.entity.Player;

/**
 * The ordered checks before any play (spec §4.1, R1.10, R3.9). One gate for every game, so no
 * game can forget a limit, and the order is the order a player is told things in:
 * <ol start="0">
 *   <li>in a world session, only that session's game ("Finish your game first");</li>
 *   <li>{@code games.enabled}, the game's own switch, not failed;</li>
 *   <li>{@code hcm.games.play}, and {@code hcm.games.chance} for a game of chance;</li>
 *   <li>an economy world, a {@code games.worlds} world or a {@code games.play_worlds} world;</li>
 *   <li>games of chance only: Take a break's pause;</li>
 *   <li>games of chance only: the click cooldown (silent);</li>
 *   <li>games of chance only: the game's {@code daily_limit};</li>
 *   <li>games of chance only: the day's token limit (own, parent/admin, server) with this stake;</li>
 *   <li>the balance covers the stake.</li>
 * </ol>
 * Opening a screen runs steps 0-4 ({@link #open}); putting tokens in runs all of them
 * ({@link #check}); putting MORE in mid-round (a Double, a Coin Flip confirm) re-runs 2, 4, 7 and
 * 8 for the extra amount at the moment of the debit ({@link #extra}).
 */
public final class PlayGate {

    /** The click cooldown never goes below this, whatever {@code games.click_cooldown_ms} says. */
    public static final long COOLDOWN_FLOOR_MS = 250;

    private final GamesService games;

    public PlayGate(GamesService games) {
        this.games = games;
    }

    /** Steps 0-4: may the player open this game's screen? {@code null} = yes. */
    public Refusal open(Player player, Game game) {
        // F1b: steps 0-4 (Breaks.chanceAllowed covers the permission and pause for chance games).
        return Refusal.CLOSED;
    }

    /** Every step, for putting {@code stake} tokens in. {@code null} = go ahead. */
    public Refusal check(Player player, Game game, int stake) {
        // F1b: steps 0-8; remember the click for the cooldown only when allowed.
        return Refusal.CLOSED;
    }

    /** Steps 2, 4, 7 and 8 for {@code extra} more tokens mid-round. {@code null} = go ahead. */
    public Refusal extra(Player player, Game game, int extra) {
        // F1b
        return Refusal.CLOSED;
    }
}
