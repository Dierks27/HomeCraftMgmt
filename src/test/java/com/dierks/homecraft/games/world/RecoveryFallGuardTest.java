package com.dierks.homecraft.games.world;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a trip home's fall is spared (the round-2 audit's G1 #3). The games' own guard
 * ({@link KitGuardListener}) stops at "games off and nobody in a session", and that is just how a server
 * is after a crash once the owner switched the games off, while a trip home is still under way; if the
 * games service didn't start, it isn't registered at all. So the fall is spared by
 * {@link SessionRecoveryListener}, which is registered once at enable, whatever happened, and is never
 * gated by the games' switch. What it asks is {@link SessionCore#sparesFall} ({@link FallHomeTest}).
 */
class RecoveryFallGuardTest {

    /** The damage handlers a listener declares. */
    private static List<Method> damageHandlers(Class<?> listener) {
        return Arrays.stream(listener.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(EventHandler.class))
                .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == EntityDamageEvent.class)
                .toList();
    }

    @Test
    void theRecoveryListenerSparesTheFallWhateverTheGamesSwitchSays() {
        List<Method> handlers = damageHandlers(SessionRecoveryListener.class);
        assertEquals(1, handlers.size(), "the recovery listener, which runs with the games on, off or failed, hears"
                + " every fall: " + handlers);
        EventHandler handler = handlers.get(0).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.LOWEST, handler.priority(), "first, like the games' own damage guard");
        assertTrue(!handler.ignoreCancelled(), "and whatever another plugin did with it before");
    }

    @Test
    void theGamesOwnGuardNoLongerDecidesATripHomesFall() {
        assertTrue(Arrays.stream(KitGuardListener.class.getDeclaredMethods())
                        .noneMatch(m -> m.getName().equals("sparesFall")),
                "the games' guard, which a games-off server with nobody playing never asks, has no say in it");
    }
}
