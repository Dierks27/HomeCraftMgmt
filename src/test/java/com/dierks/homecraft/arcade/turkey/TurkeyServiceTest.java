package com.dierks.homecraft.arcade.turkey;

import com.dierks.homecraft.arcade.turkey.TurkeyRuns.Offer;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catch the Turkey's service: where each handler runs, that the catch handlers see a tap whatever another plugin
 * did to it, and that the turkey carries none of the tags another service would delete or guard. The handlers
 * themselves need a server and are checked live (with her Bedrock client).
 */
class TurkeyServiceTest {

    private static EventHandler handler(String name, Class<? extends Event> event) throws Exception {
        EventHandler h = TurkeyService.class.getMethod(name, event).getAnnotation(EventHandler.class);
        assertNotNull(h, "TurkeyService." + name + " is a handler");
        return h;
    }

    /** A claimed town or a protection plugin may cancel the hit or the use first: the catch must still see it. */
    @Test
    void theCatchHandlersRunOnTapsThatArriveCancelled() throws Exception {
        EventHandler hit = handler("onHit", EntityDamageByEntityEvent.class);
        assertEquals(EventPriority.LOW, hit.priority());
        assertFalse(hit.ignoreCancelled(), "Bedrock's touch tap is a hit");
        EventHandler use = handler("onUse", PlayerInteractEntityEvent.class);
        assertEquals(EventPriority.LOW, use.priority());
        assertFalse(use.ignoreCancelled());
        EventHandler useAt = handler("onUseAt", PlayerInteractAtEntityEvent.class);
        assertFalse(useAt.ignoreCancelled());
    }

    /** Counted at LOW, then nothing hurts it at HIGHEST, after the count. */
    @Test
    void nothingHurtsItAfterTheCount() throws Exception {
        EventHandler damage = handler("onDamage", EntityDamageEvent.class);
        assertEquals(EventPriority.HIGHEST, damage.priority());
        assertFalse(damage.ignoreCancelled());
        assertTrue(damage.priority().getSlot() > handler("onHit", EntityDamageByEntityEvent.class).priority().getSlot());
    }

    @Test
    void theGuardsAreInPlace() throws Exception {
        assertNotNull(handler("onLeash", PlayerLeashEntityEvent.class));
        assertNotNull(handler("onLove", EntityEnterLoveModeEvent.class));
        assertNotNull(handler("onDrop", EntityDropItemEvent.class));
        assertNotNull(handler("onMount", EntityMountEvent.class));
        assertNotNull(handler("onVehicle", VehicleEnterEvent.class));
        assertNotNull(handler("onTarget", EntityTargetEvent.class));
        assertNotNull(handler("onDeath", EntityDeathEvent.class));
        assertNotNull(handler("onPortal", EntityPortalEvent.class));
        assertNotNull(handler("onTeleport", EntityTeleportEvent.class));
        assertNotNull(handler("onEntitiesLoad", EntitiesLoadEvent.class));
        assertNotNull(handler("onJoin", PlayerJoinEvent.class));
        assertNotNull(handler("onQuit", PlayerQuitEvent.class));
    }

    /**
     * Not invulnerable (that would hide Bedrock's hit before the event), not a game entity (KitGuard cancels
     * every non-owner's tap on one), not an effect entity (deleted on sight).
     */
    @Test
    void theTurkeyCarriesOnlyItsOwnTag() throws Exception {
        String src = Files.readString(Path.of("src", "main", "java", "com", "dierks", "homecraft", "arcade", "turkey",
                "TurkeyService.java"), StandardCharsets.UTF_8);
        assertFalse(src.contains("setInvulnerable(true)"));
        assertFalse(src.contains("GAME_ENTITY"));
        assertFalse(src.contains("EFFECT_ENTITY"));
        assertFalse(src.contains("WorldEntities"));
        assertTrue(src.contains("setPersistent(false)"), "never saved");
        assertTrue(src.contains("Keys.TURKEY"));
    }

    @Test
    void whatAnAdminIsToldForEachOffer() {
        assertNull(TurkeyService.offerText(Offer.SPAWNED));
        assertNull(TurkeyService.offerText(Offer.HAS_ONE), "theirs is out already: fine");
        assertEquals("caught this run already", TurkeyService.offerText(Offer.CAUGHT));
        assertEquals("cap: 6 turkeys out this run", TurkeyService.offerText(Offer.CAP));
        for (Offer o : Offer.values()) {
            String text = TurkeyService.offerText(o);
            assertTrue(text == null || text.chars().allMatch(ch -> ch < 128), o + " reads in plain ASCII");
        }
    }

    @Test
    void itWandersAndNeverRuns() {
        assertTrue(TurkeyService.WALK_SPEED < 0.25, "slower than a chicken");
        assertEquals(20L, TurkeyService.PERIOD_TICKS, "once a second");
    }
}
