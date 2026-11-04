package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.config.CandyConfig;
import com.dierks.homecraft.util.Keys;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Harvest Basket runs on the candy shower's engine (0.38.6): a second instance of {@link CandyService} with
 * its own profile. Pinned here: the profile's keys, ledger source, config and words are the basket's own and never
 * candy's, and the handlers every instance registers keep candy's priorities. The handlers need a server and are
 * checked live.
 */
class HarvestServiceTest {

    @Test
    void theBasketsProfileIsItsOwn() {
        ShowerProfile h = ShowerProfile.harvest();
        assertEquals("harvest", h.id());
        assertEquals(CandyConfig.HARVEST_PATH, h.configPath());
        assertEquals("arcade.events.harvest", h.configPath());
        assertEquals("the Harvest Basket", h.label());
        assertEquals("harvest", h.counterPrefix());
        assertSame(TokenService.Source.EVENT_HARVEST, h.source(), "its own daily cap");
        assertEquals("hat", h.reward().word());
        assertEquals("Pilgrim Hat", h.reward().title());
        assertEquals("/hcm harvest hat", h.reward().restoreHint(h.id()));
        assertInstanceOf(HarvestShowerItems.class, h.items());
        assertInstanceOf(PilgrimHat.class, h.reward());
    }

    /** The keys are read when used, after the plugin has set them; here they are still unset. */
    @Test
    void theBasketsKeysAreTheHarvestOnes() throws Exception {
        ShowerProfile h = ShowerProfile.harvest();
        ShowerProfile c = ShowerProfile.candy();
        org.bukkit.NamespacedKey drop = new org.bukkit.NamespacedKey("hcm", "harvest_drop");
        org.bukkit.NamespacedKey item = org.bukkit.NamespacedKey.fromString("hcm:harvest_item");
        org.bukkit.NamespacedKey candyDrop = new org.bukkit.NamespacedKey("hcm", "candy_drop");
        org.bukkit.NamespacedKey candyItem = org.bukkit.NamespacedKey.fromString("hcm:candy_item");
        var oldHd = Keys.HARVEST_DROP;
        var oldHi = Keys.HARVEST_ITEM;
        var oldCd = Keys.CANDY_DROP;
        var oldCi = Keys.CANDY_ITEM;
        try {
            Keys.HARVEST_DROP = drop;
            Keys.HARVEST_ITEM = item;
            Keys.CANDY_DROP = candyDrop;
            Keys.CANDY_ITEM = candyItem;
            assertSame(drop, h.dropKey().get());
            assertSame(item, h.itemKey().get());
            assertSame(candyDrop, c.dropKey().get(), "candy keeps candy_drop");
            assertSame(candyItem, c.itemKey().get(), "and hcm:candy_item");
            assertNotEquals(h.dropKey().get(), c.dropKey().get(), "neither counts the other's");
        } finally {
            Keys.HARVEST_DROP = oldHd;
            Keys.HARVEST_ITEM = oldHi;
            Keys.CANDY_DROP = oldCd;
            Keys.CANDY_ITEM = oldCi;
        }
    }

    @Test
    void candysProfileIsWhat0385Ran() {
        ShowerProfile c = ShowerProfile.candy();
        assertEquals("candy", c.id());
        assertEquals(CandyConfig.PATH, c.configPath());
        assertEquals("the Candy Apple shower", c.label());
        assertSame(TokenService.Source.EVENT_CANDY, c.source());
        assertEquals("bag", c.reward().word());
        assertEquals("Trick-or-Treat Bag", c.reward().title());
        assertInstanceOf(CandyShowerItems.class, c.items());
        assertInstanceOf(CandyBag.class, c.reward());
    }

    @Test
    void theWordsAreTheBasketsAndPlain() {
        ShowerText t = ShowerProfile.harvest().text();
        assertEquals("&6Harvest time!", t.title());
        assertEquals("&eTreats are falling!", t.subtitle());
        assertEquals("&eA Golden Pie!", t.goldenLine());
        assertEquals("Golden Pie", t.goldenReason(), "the token history's line");
        assertEquals("The Harvest Basket", t.sentence());
        for (String s : List.of(t.title(), t.subtitle(), t.goldenLine(), t.goldenReason(), t.barMark(), t.joinHead())) {
            assertTrue(s.chars().allMatch(ch -> ch < 0x80), "plain letters: " + s);
        }
    }

    /** Every instance registers the same handlers: the harvest one must keep candy's priorities. */
    @Test
    void theHandlersAreCandysOwn() throws Exception {
        for (var h : List.of(handler("onTap", PlayerInteractEvent.class), handler("onPickup", EntityPickupItemEvent.class),
                handler("onEat", PlayerItemConsumeEvent.class), handler("onMerge", ItemMergeEvent.class),
                handler("onHopper", InventoryPickupItemEvent.class), handler("onEntitiesLoad", EntitiesLoadEvent.class),
                handler("onPortal", EntityPortalEvent.class), handler("onTeleport", EntityTeleportEvent.class),
                handler("onJoin", PlayerJoinEvent.class), handler("onQuit", PlayerQuitEvent.class))) {
            assertNotNull(h);
        }
        assertEquals(org.bukkit.event.EventPriority.HIGH, handler("onTap", PlayerInteractEvent.class).priority());
        assertTrue(!handler("onTap", PlayerInteractEvent.class).ignoreCancelled(), "air taps arrive cancelled");
        assertEquals(org.bukkit.event.EventPriority.MONITOR,
                handler("onPickup", EntityPickupItemEvent.class).priority());
        assertTrue(handler("onEat", PlayerItemConsumeEvent.class).ignoreCancelled(), "no treat in a game");
    }

    @Test
    void theConstructorsTakeAProfile() throws Exception {
        assertNotNull(CandyService.class.getConstructor(com.dierks.homecraft.HomeCraftManagement.class,
                com.dierks.homecraft.storage.AchievementDao.class, ShowerProfile.class));
        assertNotNull(CandyService.class.getConstructor(com.dierks.homecraft.HomeCraftManagement.class,
                ShowerProfile.class));
        assertNotNull(CandyService.class.getConstructor(com.dierks.homecraft.HomeCraftManagement.class,
                com.dierks.homecraft.storage.AchievementDao.class), "candy's own, kept");
    }

    private static EventHandler handler(String name, Class<? extends Event> event) throws Exception {
        EventHandler h = CandyService.class.getMethod(name, event).getAnnotation(EventHandler.class);
        assertNotNull(h, "CandyService." + name + " is a handler");
        return h;
    }
}
