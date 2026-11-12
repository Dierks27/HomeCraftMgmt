package com.dierks.homecraft.graves;

import com.dierks.homecraft.arcade.PrizeSafetyListener;
import com.dierks.homecraft.courier.CourierListener;
import com.dierks.homecraft.games.world.KitGuardListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The death's sequence (HCM-GRAVES §1): the grave is built after every HCM filter, and only from a death that went
 * ahead. Pinned here, so moving any of these handlers is a deliberate change.
 */
class GraveDeathOrderTest {

    private static EventHandler handler(Class<?> listener, String method) throws NoSuchMethodException {
        Method m = listener.getMethod(method, PlayerDeathEvent.class);
        EventHandler h = m.getAnnotation(EventHandler.class);
        assertTrue(h != null, listener.getSimpleName() + "." + method + " is a handler");
        return h;
    }

    @Test
    void theGraveIsBuiltLastAndOnlyFromADeathThatWentAhead() throws Exception {
        EventHandler grave = handler(GraveListener.class, "onDeath");
        assertEquals(EventPriority.MONITOR, grave.priority(), "after every HIGHEST, HCM's and other plugins'");
        assertTrue(grave.ignoreCancelled(), "a cancelled death (the Games' revive) makes no grave");
    }

    @Test
    void keepingEverythingIsDecidedFirst() throws Exception {
        EventHandler keep = handler(GraveListener.class, "onDeathKeep");
        assertEquals(EventPriority.LOWEST, keep.priority(),
                "hcm.graves.keep makes a keep-inventory death before prizes, packages or the grave read it");
        assertTrue(keep.ignoreCancelled());
    }

    @Test
    void everyHcmFilterRunsBeforeIt() throws Exception {
        assertEquals(EventPriority.LOWEST, handler(KitGuardListener.class, "onDeath").priority(),
                "the Games' revive cancels a game death first");
        assertEquals(EventPriority.LOW, handler(PrizeSafetyListener.class, "onDeath").priority(),
                "prizes are moved to the items to keep");
        assertEquals(EventPriority.NORMAL, handler(CourierListener.class, "onDeath").priority(),
                "courier packages kept when keep_on_death");
        assertEquals(EventPriority.HIGHEST, handler(PrizeSafetyListener.class, "onDeathGuard").priority(),
                "the prize guard, which must run before the grave");
        assertTrue(EventPriority.HIGHEST.getSlot() < EventPriority.MONITOR.getSlot());
    }
}
