package com.dierks.homecraft.arcade;

import com.dierks.homecraft.games.world.KitGuardListener;
import com.dierks.homecraft.mini.MiniDestructionListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which prizes are kept safe (HALLOWEEN-SPEC D9, §4.1, E8, E15, E16, E18, E21): a prize that holds
 * nothing but candy is kept on death and safe on the ground (candy itself only on death), a dropped
 * collector is bound to the one who dropped it, and a kept prize leaves the drops before a grave plugin
 * reads them, exactly once.
 */
class PrizeSafetyTest {

    private static final List<Boolean> NOTHING = List.of();

    @Test
    void theContentsRule() {
        assertTrue(PrizeSafety.holdsOnlyCandy(NOTHING), "an empty bag holds nothing but candy");
        assertTrue(PrizeSafety.holdsOnlyCandy(List.of(true, true)));
        assertFalse(PrizeSafety.holdsOnlyCandy(List.of(true, false)), "one thing that isn't candy is enough");
        assertFalse(PrizeSafety.holdsOnlyCandy(List.of(false)));
    }

    /** One item: what it is, and whether it is kept on death and safe on the ground. */
    private record Case(String what, boolean prize, boolean candy, List<Boolean> contents, boolean death,
                        boolean ground) {
    }

    @Test
    void keptOnDeathAndSafeOnTheGround() {
        List<Case> cases = List.of(
                new Case("a plain prize", true, false, NOTHING, true, true),
                new Case("candy (E16: it despawns on the ground)", true, true, NOTHING, true, false),
                new Case("an empty prize bundle", true, false, NOTHING, true, true),
                new Case("a prize bundle holding a diamond (E18, red-team M3: no keep-inventory bag)", true, false,
                        List.of(false), false, false),
                new Case("a prize bundle holding candy and a diamond", true, false, List.of(true, false), false, false),
                new Case("a prize bundle holding only candy (E15)", true, false, List.of(true, true), true, true),
                new Case("a non-prize", false, false, NOTHING, false, false),
                new Case("a plain bundle of candy", false, false, List.of(true), false, false));
        for (Case c : cases) {
            assertEquals(c.death(), PrizeSafety.keepOnDeath(c.prize(), c.contents()), c.what() + ": kept on death");
            assertEquals(c.ground(), PrizeSafety.keepOnGround(c.prize(), c.candy(), c.contents()),
                    c.what() + ": safe on the ground");
        }
    }

    @Test
    void aDroppedCollectorIsBoundToWhoDroppedIt() {
        assertTrue(PrizeSafety.ownerBound("cap_pumpkin_2026"));
        assertTrue(PrizeSafety.ownerBound("CAP_PUMPKIN_2026"), "case-insensitive, as the catalogue is");
        assertTrue(PrizeSafety.ownerBound("trail_leaves_2026"));
        assertTrue(PrizeSafety.ownerBound("bag_trick_or_treat_2026"), "the bag included");
        assertFalse(PrizeSafety.ownerBound("hat_creeper"), "other prizes are handed over by dropping them");
        assertFalse(PrizeSafety.ownerBound("speed"));
        assertFalse(PrizeSafety.ownerBound("candy_apple_2026"), "candy isn't a collector");
        assertFalse(PrizeSafety.ownerBound(null), "not a prize at all");
    }

    /**
     * E21: the owner set on a drop is checked only when a player walks over it, so a hopper (or a hopper
     * minecart) under a dropped collector would take it, and anyone could open the chest. HW1-ECON-2.
     */
    @Test
    void aHopperLeavesADroppedCollectorWhereItIs() {
        assertTrue(PrizeSafety.hopperRefuses("cap_pumpkin_2026", true));
        assertTrue(PrizeSafety.hopperRefuses("trail_leaves_2026", true));
        assertTrue(PrizeSafety.hopperRefuses("bag_trick_or_treat_2026", true), "the bag included");
        assertFalse(PrizeSafety.hopperRefuses("cap_pumpkin_2026", false),
                "nobody owns it (spilled from a broken chest): anyone could pick it up anyway");
        assertFalse(PrizeSafety.hopperRefuses("hat_creeper", true), "other prizes go into hoppers as before");
        assertFalse(PrizeSafety.hopperRefuses(null, true), "not a prize at all");
    }

    @Test
    void moveIfMovesExactlyTheMatchesInOrderAndOnlyOnce() {
        List<String> drops = new ArrayList<>(List.of("dirt", "prize:cap", "stone", "prize:speed", "bundle:diamond"));
        List<String> keep = new ArrayList<>();
        assertEquals(2, PrizeSafety.moveIf(drops, keep, s -> s.startsWith("prize:")));
        assertEquals(List.of("prize:cap", "prize:speed"), keep, "the matches, in their order");
        assertEquals(List.of("dirt", "stone", "bundle:diamond"), drops, "the rest stay, in their order");

        assertEquals(0, PrizeSafety.moveIf(drops, keep, s -> s.startsWith("prize:")),
                "a second call moves nothing: no prize is kept twice (the grave case, X18)");
        assertEquals(List.of("prize:cap", "prize:speed"), keep);
    }

    /**
     * A grave plugin like GravesX listens at MONITOR, skips a cancelled death, and builds its grave from
     * the drops alone. Whatever our LOW handler kept is gone from the drops by then, so the grave never
     * holds a kept prize and nothing is copied.
     */
    @Test
    void aGravePluginAtMonitorNeverSeesAKeptPrize() {
        List<String> drops = new ArrayList<>(List.of("prize:cap", "dirt", "prize:speed", "bundle:diamond"));
        List<String> keep = new ArrayList<>();
        PrizeSafety.moveIf(drops, keep, s -> s.startsWith("prize:"));       // our LOW handler
        List<String> grave = new ArrayList<>(drops);                         // GravesX at MONITOR
        assertTrue(grave.stream().noneMatch(s -> s.startsWith("prize:")), "no prize in the grave: " + grave);
        assertEquals(List.of("dirt", "bundle:diamond"), grave, "everything else goes in the grave as before");
        List<String> all = new ArrayList<>(grave);
        all.addAll(keep);
        assertEquals(4, all.size(), "every item ends up exactly once: kept or in the grave");
    }

    @Test
    void theDeathHandlerRunsAfterTheGamesReviveAndBeforeAGravePlugin() throws Exception {
        EventHandler ours = handler(PrizeSafetyListener.class, "onDeath", PlayerDeathEvent.class);
        assertEquals(EventPriority.LOW, ours.priority());
        assertTrue(ours.ignoreCancelled(), "a death the Games cancelled (the revive) is left alone");
        EventHandler revive = handler(KitGuardListener.class, "onDeath", PlayerDeathEvent.class);
        assertTrue(ours.priority().getSlot() > revive.priority().getSlot(),
                "after KitGuard's revive (" + revive.priority() + "), which cancels the death");
        assertTrue(ours.priority().getSlot() < EventPriority.MONITOR.getSlot(),
                "before a grave plugin's MONITOR, which builds the grave from the drops");
    }

    /**
     * The HIGHEST guard (§3.1 #22): another plugin puts a kept prize back in the drops after LOW. The
     * guard takes out one drop for each kept copy, so the grave at MONITOR holds no prize and nothing
     * is both kept and dropped; anything else in the drops, and a prize kept only once, is untouched.
     */
    @Test
    void theGuardTakesAPrizeThatIsKeptAndDroppedOutOfTheDrops() {
        List<String> drops = new ArrayList<>(List.of("prize:cap", "dirt", "prize:speed", "bundle:diamond"));
        List<String> keep = new ArrayList<>();
        PrizeSafety.moveIf(drops, keep, s -> s.startsWith("prize:"));       // our LOW handler
        drops.add("prize:cap");                                              // another plugin, at NORMAL
        drops.add("dirt");
        assertEquals(1, PrizeSafety.dropKeptTwice(drops, keep, s -> s.startsWith("prize:")));
        assertEquals(List.of("dirt", "bundle:diamond", "dirt"), drops, "only the prize copy goes, not other items");
        assertEquals(List.of("prize:cap", "prize:speed"), keep, "the kept list is never changed");

        assertEquals(0, PrizeSafety.dropKeptTwice(drops, keep, s -> s.startsWith("prize:")), "nothing left to take");
        List<String> twice = new ArrayList<>(List.of("prize:speed", "prize:speed"));
        assertEquals(1, PrizeSafety.dropKeptTwice(twice, List.of("prize:speed"), s -> s.startsWith("prize:")),
                "one drop for each kept copy: a second, real stack still drops");
        assertEquals(List.of("prize:speed"), twice);
        List<String> notGuarded = new ArrayList<>(List.of("dirt"));
        assertEquals(0, PrizeSafety.dropKeptTwice(notGuarded, List.of("dirt"), s -> s.startsWith("prize:")),
                "a stack that isn't a kept prize is another plugin's business");
    }

    @Test
    void theGuardRunsAfterOurMoveAndBeforeAGravePlugin() throws Exception {
        EventHandler guard = handler(PrizeSafetyListener.class, "onDeathGuard", PlayerDeathEvent.class);
        assertEquals(EventPriority.HIGHEST, guard.priority());
        assertTrue(guard.ignoreCancelled(), "a death the Games cancelled is left alone");
        assertTrue(guard.priority().getSlot() < EventPriority.MONITOR.getSlot(),
                "before a grave plugin's MONITOR, which builds the grave from the drops");
    }

    @Test
    void theGroundHandlersRunWhereTheyMust() throws Exception {
        EventHandler spawn = handler(PrizeSafetyListener.class, "onItemSpawn", ItemSpawnEvent.class);
        assertEquals(EventPriority.MONITOR, spawn.priority(), "after KitGuard's kit cancel");
        assertTrue(spawn.ignoreCancelled(), "a cancelled spawn is no item");

        EventHandler drop = handler(PrizeSafetyListener.class, "onDrop", PlayerDropItemEvent.class);
        assertEquals(EventPriority.MONITOR, drop.priority());
        assertTrue(drop.ignoreCancelled(), "a cancelled drop stays in the bag");

        EventHandler hopper = handler(PrizeSafetyListener.class, "onHopper", InventoryPickupItemEvent.class);
        EventHandler kitDelete = handler(KitGuardListener.class, "onHopper", InventoryPickupItemEvent.class);
        assertTrue(hopper.priority().getSlot() > kitDelete.priority().getSlot(),
                "after KitGuard deletes a stray kit item (" + kitDelete.priority() + ")");
        assertTrue(hopper.ignoreCancelled());

        EventHandler damage = handler(PrizeSafetyListener.class, "onItemDamage", EntityDamageEvent.class);
        EventHandler retire = handler(MiniDestructionListener.class, "onItemDamaged", EntityDamageEvent.class);
        assertTrue(damage.priority().getSlot() < retire.priority().getSlot() && retire.ignoreCancelled(),
                "cancelled before the Mini retirement, which then skips it");
    }

    private static EventHandler handler(Class<?> listener, String name, Class<?> event) throws Exception {
        EventHandler h = listener.getMethod(name, event).getAnnotation(EventHandler.class);
        assertNotNull(h, listener.getSimpleName() + "." + name + " is a handler");
        return h;
    }
}
