package com.dierks.homecraft.games;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Invites to two-player games: Coin Flip, and Connect Four or Tic-Tac-Toe against a friend (spec
 * R1.8, R3.8).
 *
 * <p>One pending invite per invitee, answered with {@code /hcm play accept|deny} (Java players
 * also get a clickable "[Accept]"; Bedrock players read the command) or the glinting tile on the
 * Games screen. Expiry, a quit or a world change cancels it. Each player chooses per game whether
 * they take invites at all ({@code invites.<gameId>} in {@code game_prefs}): Coin Flip invites are
 * OFF until the player turns them on; friend games are on. A player who can't be invited is
 * simply not offered — the inviter is never told why.
 */
public final class Invites {

    /** The {@code game_prefs} key for a player's choice about a game's invites. */
    public static String prefKey(String gameId) {
        return "invites." + gameId;
    }

    private final GamesService games;

    public Invites(GamesService games) {
        this.games = games;
    }

    /**
     * Invite {@code to} to {@code game}. {@code answer} runs once with the invite and true
     * (accepted) or false (denied, expired, cancelled) — the game re-checks everything at that
     * moment (both online, both allowed, same world...) before any token moves.
     *
     * @param summary what the invitee reads ("Coin Flip for 10 tokens each")
     * @return the invite, or {@code null} when it was not sent (the invitee doesn't take them,
     *         already has one pending, or the pair is on cooldown)
     */
    public Invite send(Player from, Player to, Game game, String summary, int seconds,
                       BiConsumer<Invite, Boolean> answer) {
        // F1b
        return null;
    }

    /** {@code /hcm play accept}: accept the player's latest invite. False if there is none. */
    public boolean accept(Player to) {
        // F1b
        return false;
    }

    /** {@code /hcm play deny}: turn down the player's latest invite. False if there is none. */
    public boolean deny(Player to) {
        // F1b
        return false;
    }

    /** The invite waiting for {@code to}, or {@code null}. */
    public Invite pending(UUID to) {
        // F1b
        return null;
    }

    /** Cancel every invite from or to the player (quit, world change). */
    public void cancel(UUID player) {
        // F1b
    }

    /** Whether the player takes invites to this game (Coin Flip off by default, friend games on). */
    public boolean accepts(UUID player, String gameId) {
        // F1b: dao.pref(player, prefKey(gameId)), default false for coin_flip, true otherwise.
        return false;
    }

    /** The player turns this game's invites on or off. */
    public void setAccepts(UUID player, String gameId, boolean on) {
        // F1b
    }
}
