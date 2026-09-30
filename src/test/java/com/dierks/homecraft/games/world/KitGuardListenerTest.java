package com.dierks.homecraft.games.world;

import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's rules about bystanders, with no server.
 *
 * <p>Pinned here: a player in a game gets an effect only from the game itself (a plugin) or an
 * admin's {@code /effect} — a splash potion, a cloud, an arrow, a beacon, a conduit or anything
 * else in the world is cancelled — while an effect running out or being taken away always goes
 * through; and a fishing rod reeling in what it hooked is the one catch cancelled.
 */
class KitGuardListenerTest {

    @Test
    void anEffectFromOutsideTheGameIsCancelled() {
        for (EntityPotionEffectEvent.Cause cause : EnumSet.of(EntityPotionEffectEvent.Cause.POTION_SPLASH,
                EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD, EntityPotionEffectEvent.Cause.ARROW,
                EntityPotionEffectEvent.Cause.BEACON, EntityPotionEffectEvent.Cause.CONDUIT,
                EntityPotionEffectEvent.Cause.ATTACK, EntityPotionEffectEvent.Cause.WARDEN,
                EntityPotionEffectEvent.Cause.UNKNOWN)) {
            assertTrue(KitGuardListener.fromOutside(cause, EntityPotionEffectEvent.Action.ADDED),
                    cause + " giving an effect is someone or something else: cancelled");
            assertTrue(KitGuardListener.fromOutside(cause, EntityPotionEffectEvent.Action.CHANGED),
                    cause + " making one stronger or longer is too");
        }
    }

    @Test
    void theGamesOwnEffectsAndAnAdminsGoThrough() {
        assertEquals(EnumSet.of(EntityPotionEffectEvent.Cause.PLUGIN, EntityPotionEffectEvent.Cause.COMMAND),
                KitGuardListener.OWN_EFFECTS, "the game itself and an admin's /effect, nothing else");
        for (EntityPotionEffectEvent.Cause cause : KitGuardListener.OWN_EFFECTS) {
            assertFalse(KitGuardListener.fromOutside(cause, EntityPotionEffectEvent.Action.ADDED),
                    cause + " is the game's own (or an admin's, which a game that cares sees for itself)");
        }
    }

    @Test
    void anEffectRunningOutOrTakenAwayAlwaysGoesThrough() {
        for (EntityPotionEffectEvent.Cause cause : EntityPotionEffectEvent.Cause.values()) {
            assertFalse(KitGuardListener.fromOutside(cause, EntityPotionEffectEvent.Action.REMOVED),
                    cause + ": losing an effect never spoils a game");
            assertFalse(KitGuardListener.fromOutside(cause, EntityPotionEffectEvent.Action.CLEARED),
                    cause + ": nor losing every effect");
        }
    }

    @Test
    void onlyARodReelingInWhatItHookedIsCancelled() {
        assertTrue(KitGuardListener.reelsIn(PlayerFishEvent.State.CAUGHT_ENTITY),
                "pulling a hooked player towards the angler");
        for (PlayerFishEvent.State state : PlayerFishEvent.State.values()) {
            if (state != PlayerFishEvent.State.CAUGHT_ENTITY) {
                assertFalse(KitGuardListener.reelsIn(state), state + " moves nobody");
            }
        }
    }
}
