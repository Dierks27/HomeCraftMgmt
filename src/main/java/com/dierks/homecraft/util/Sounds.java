package com.dierks.homecraft.util;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * The plugin's sound vocabulary, named by intent rather than by note.
 *
 * <p>Before this, the store, market, checkout, marketplace, mailbox and museum made no sound at
 * all — so spending money, being refused for lack of funds, and misclicking a decorative pane were
 * indistinguishable. A call site should say what happened, not choose a pitch; if the palette is
 * ever retuned, it is retuned here.
 *
 * <p>Four motifs and no fifth without a reason:
 * <ul>
 *   <li>{@link #paid} — money left your pocket. Two notes rising, so it reads as a transaction
 *       completing rather than a button acknowledging a press.</li>
 *   <li>{@link #received} — money arrived, or a parcel did. One note, deliberately: the pair is
 *       what distinguishes paying from being paid when you cannot see the screen.</li>
 *   <li>{@link #refused} — it did not happen. A low bass note, never {@code ENTITY_VILLAGER_NO},
 *       which reads as mockery by the third hearing and this is a child's server.</li>
 *   <li>{@link #won} — a genuine payoff. Rare on purpose; spend it and it stops meaning anything.</li>
 * </ul>
 *
 * <p>Volumes sit between 0.3 and 0.8. These play while a player is reading a menu, and a sound loud
 * enough to startle is worse than silence. Everything is {@link Player#playSound} rather than the
 * world's — a purchase is nobody else's business.
 *
 * <p>Deliberate silences, which are as much a part of the design as the sounds: a menu never makes
 * noise when it merely rebuilds (a screen left open would tick forever), closing is silent, and
 * decorative tiles are silent so that silence keeps meaning "that was not a button".
 */
public final class Sounds {

    private Sounds() {
    }

    /** Money spent. */
    public static void paid(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.5f);
        play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
    }

    /** Money earned, or goods handed over. */
    public static void received(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.26f);
    }

    /** A parcel collected from the Mailbox. */
    public static void collected(Player player) {
        play(player, Sound.ENTITY_ITEM_PICKUP, 0.9f, 1.0f);
    }

    /** Refused — not enough money, out of stock, the price moved, the inventory is full. */
    public static void refused(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
    }

    /** A real payoff: a crate pull, a scratch win, a pack finishing. */
    public static void won(Player player) {
        play(player, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    /**
     * Play one sound, swallowing anything that goes wrong.
     *
     * <p>A cosmetic sound is never worth an exception reaching the click handler that was in the
     * middle of a purchase. {@code AnnounceService} already guards its broadcasts the same way.
     */
    private static void play(Player player, Sound sound, float volume, float pitch) {
        if (player == null) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Throwable ignored) {
            // never let a sound break a transaction
        }
    }
}
