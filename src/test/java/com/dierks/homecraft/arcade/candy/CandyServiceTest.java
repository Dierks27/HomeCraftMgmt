package com.dierks.homecraft.arcade.candy;

import com.dierks.homecraft.arcade.candy.CandyRules.BagStep;
import com.dierks.homecraft.games.world.KitGuardListener;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Candy Apple shower's service (HALLOWEEN-SPEC §3.2 row 4, D11-D17, §4.3 E1, E2, E15, E17, E20, E22): the
 * decisions it hands to {@link CandyRules} (the live caps, the taps, the bag's steps, the bar's text), its
 * main-thread budget, and where each handler runs. The handlers themselves need a server and are checked live
 * (§6 Step 2).
 */
class CandyServiceTest {

    // ---- the live caps (D11, D17 #8) --------------------------------------------------------

    @Test
    void aShowerIsCutToWhatTheCapsLeave() {
        assertEquals(6, CandyRules.applesFor(6, 0, 0), "the shipped six");
        assertEquals(6, CandyRules.applesFor(6, 6, 6), "one shower ignored: room for another");
        assertEquals(2, CandyRules.applesFor(6, 10, 10), "12 a player: never more out");
        assertEquals(0, CandyRules.applesFor(6, 12, 12));
        assertEquals(3, CandyRules.applesFor(6, 0, 117), "120 on the server");
        assertEquals(0, CandyRules.applesFor(6, 0, 120));
        assertEquals(0, CandyRules.applesFor(6, 14, 0), "never below 0");
    }

    // ---- the taps (D13, D15; kid-bedrock #1) -----------------------------------------------

    @Test
    void aTapIsTheAirOrAPlainBlock() {
        assertTrue(CandyRules.tap(true, false, false, false, false), "a tap in the air");
        assertTrue(CandyRules.tap(false, true, false, false, false), "a tap on the grass she is looking at");
        assertFalse(CandyRules.tap(false, true, true, false, false), "a chest or a door still opens");
        assertFalse(CandyRules.tap(false, true, false, true, false), "sneaking: the block's own use");
        assertFalse(CandyRules.tap(false, true, false, false, true), "the Arcade Machine, a join sign or a Mini");
        assertFalse(CandyRules.tap(false, false, false, false, false), "neither");
    }

    @Test
    void theEatFallbackIsForAFullBedrockPlayerOnly() {
        assertTrue(CandyRules.eatsOnTap(true, 20, true, true, true, true), "Bedrock, full, a red apple, home");
        assertFalse(CandyRules.eatsOnTap(false, 20, true, true, true, true), "Java eats it itself (can_always_eat)");
        assertFalse(CandyRules.eatsOnTap(true, 19, true, true, true, true), "hungry: Bedrock eats it itself");
        assertFalse(CandyRules.eatsOnTap(true, 20, false, true, true, true), "not a tap");
        assertFalse(CandyRules.eatsOnTap(true, 20, true, false, true, true), "the off hand's repeat");
        assertFalse(CandyRules.eatsOnTap(true, 20, true, true, false, true), "a golden one is always edible");
        assertFalse(CandyRules.eatsOnTap(true, 20, true, true, true, false), "in a game: KitGuard's");
    }

    @Test
    void theBagIsAToyOnBothEditions() {
        assertTrue(CandyRules.toyOnTap(true, true, true, true));
        assertFalse(CandyRules.toyOnTap(false, true, true, true), "a chest still opens with the bag in hand");
        assertFalse(CandyRules.toyOnTap(true, false, true, true), "main hand only");
        assertFalse(CandyRules.toyOnTap(true, true, false, true), "not the bag");
        assertFalse(CandyRules.toyOnTap(true, true, true, false), "in a game");
    }

    @Test
    void theCooldowns() {
        assertTrue(CandyRules.cooledDown(null, 1_000, CandyService.EAT_COOLDOWN_MS), "never ate");
        assertFalse(CandyRules.cooledDown(1_000L, 2_599, CandyService.EAT_COOLDOWN_MS),
                "holding the button repeats the use: one apple per eat, not several a second");
        assertTrue(CandyRules.cooledDown(1_000L, 2_600, CandyService.EAT_COOLDOWN_MS));
        assertEquals(1_600L, CandyService.EAT_COOLDOWN_MS, "the length of one eat");
        assertEquals(3_000L, CandyService.TOY_COOLDOWN_MS);
    }

    // ---- the bag (D15; E17, E22) -----------------------------------------------------------

    @Test
    void theBagsStepsInOrder() {
        assertEquals(BagStep.HAS_IT, CandyRules.bagStep(true, 40, 31, false, false), "the mark first");
        assertEquals(BagStep.NOT_YET, CandyRules.bagStep(false, 30, 31, true, true));
        assertEquals(BagStep.GIVE, CandyRules.bagStep(false, 31, 31, true, true));
        assertEquals(BagStep.WAIT_GAME, CandyRules.bagStep(false, 31, 31, false, true),
                "E22: never into a game, whose restore would wipe it while its mark says it was given");
        assertEquals(BagStep.WAIT_GAME, CandyRules.bagStep(false, 31, 31, false, false), "a game before room");
        assertEquals(BagStep.WAIT_ROOM, CandyRules.bagStep(false, 35, 31, true, false), "never on the ground");
        assertEquals(BagStep.GIVE, CandyRules.bagStep(false, 1, 1, true, true), "the goal as set");
    }

    @Test
    void theJoinTryWaitsForAGamesCrashRestore() {
        assertTrue(CandyService.JOIN_BAG_DELAY_TICKS >= 40,
                "a crash-rejoin's restore runs 1 tick after the join and replaces the whole inventory");
    }

    // ---- the bar (D15) ---------------------------------------------------------------------

    @Test
    void theBarCountsToTheGoalThenSaysWhereTheBagIs() {
        assertEquals("&6Candy apples &f17 &7/ 31", CandyRules.barLine(17, 31, false));
        assertEquals("&6Candy apples: &f35 &7· &aYou got your bag!", CandyRules.barLine(35, 31, false));
        assertEquals("&6Candy apples: &f31 &7· &eYour bag is waiting!", CandyRules.barLine(31, 31, true));
        assertEquals("&6Candy apples: &f17 &7/ 31", CandyRules.actionBar(17, 31));
        assertEquals("&6Candy apples: &f35", CandyRules.actionBar(35, 31), "no 35 / 31");
        assertEquals(1f, CandyRules.progress(35, 31), "full from the goal on, never above 1");
    }

    // ---- the main thread -------------------------------------------------------------------

    @Test
    void theTaskIsBudgeted() {
        assertEquals(5, CandyService.PERIOD_TICKS, "drops due every 5 ticks");
        assertEquals(20, CandyService.PERIOD_TICKS * CandyService.RUNS_PER_SECOND, "the window, slot and sweep once a second");
        assertTrue(CandyService.PLACEMENTS_PER_RUN >= 4 && CandyService.PLACEMENTS_PER_RUN <= 12,
                "enough for 4 players' apples every 10 ticks, never a heap of placements in one tick");
        assertEquals(30_000L, CandyService.WELCOME_DELAY_MS);
    }

    // ---- where the handlers run ------------------------------------------------------------

    /** Red-team m7: air clicks arrive already cancelled, so the taps must not ignore cancelled events. */
    @Test
    void theTapsRunOnClicksThatArriveCancelled() throws Exception {
        EventHandler tap = handler("onTap", PlayerInteractEvent.class);
        assertEquals(EventPriority.HIGH, tap.priority());
        assertFalse(tap.ignoreCancelled(), "with it, a tap in the air would never eat or play");
    }

    /** E1: only a real pickup counts, after every plugin that might refuse it. */
    @Test
    void aPickupCountsAtMonitorWhenItHappens() throws Exception {
        EventHandler pickup = handler("onPickup", EntityPickupItemEvent.class);
        assertEquals(EventPriority.MONITOR, pickup.priority());
        assertTrue(pickup.ignoreCancelled(), "a refused pickup counts nothing");
        EventHandler eat = handler("onEat", PlayerItemConsumeEvent.class);
        assertEquals(EventPriority.MONITOR, eat.priority());
        assertTrue(eat.ignoreCancelled(), "eating in a game is cancelled by KitGuard: no treat");
    }

    /** E2 and E20: merges, hoppers, portals and teleports are refused for shower candy; a loaded chunk's goes. */
    @Test
    void theGuardsAreInPlace() throws Exception {
        assertTrue(handler("onMerge", ItemMergeEvent.class).ignoreCancelled());
        EventHandler hopper = handler("onHopper", InventoryPickupItemEvent.class);
        EventHandler kitDelete = KitGuardListener.class.getMethod("onHopper", InventoryPickupItemEvent.class)
                .getAnnotation(EventHandler.class);
        assertTrue(hopper.priority().getSlot() > kitDelete.priority().getSlot(),
                "after KitGuard deletes a stray kit item (" + kitDelete.priority() + ")");
        assertNotNull(handler("onEntitiesLoad", EntitiesLoadEvent.class));
        assertNotNull(handler("onPortal", EntityPortalEvent.class));
        assertNotNull(handler("onTeleport", EntityTeleportEvent.class));
        assertNotNull(handler("onJoin", PlayerJoinEvent.class));
        assertNotNull(handler("onQuit", PlayerQuitEvent.class));
    }

    private static EventHandler handler(String name, Class<? extends Event> event) throws Exception {
        EventHandler h = CandyService.class.getMethod(name, event).getAnnotation(EventHandler.class);
        assertNotNull(h, "CandyService." + name + " is a handler");
        return h;
    }
}
