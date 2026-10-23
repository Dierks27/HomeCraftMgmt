package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeSession.Cause;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crouch gesture (SHOP-V2-SPEC §4.2, T6): it asks only when crouching, with the main hand, an empty main hand,
 * at a real player (not an NPC), and when no earlier listener (the Games' LOWEST guard) cancelled the click; and a
 * teleport ends a trade only over one block or to another world.
 */
class GestureTest {

    /** What the listener asked of the trade window. */
    private static final class Recorder implements TradeListener.Trades {
        final List<String> calls = new ArrayList<>();
        boolean on = true;

        @Override
        public boolean on() {
            return on;
        }

        @Override
        public void gesture(Player asker, Player target) {
            calls.add("ask " + target.getName());
        }

        @Override
        public void ended(Player player, Cause cause, String detail) {
            calls.add(cause + (detail == null ? "" : " " + detail));
        }

        @Override
        public void dropRequests(Player player, String why) {
            calls.add("drop");
        }

    }

    /** A player or NPC: crouching or not, holding nothing, with or without the trade permission. */
    private static Player player(String name, boolean sneaking, boolean npc, boolean permitted) {
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(GestureTest.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, m, a) -> null); // an empty hand
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(GestureTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> id;
                    case "isSneaking" -> sneaking;
                    case "getInventory" -> inv;
                    case "hasMetadata" -> npc;
                    case "hasPermission" -> permitted;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == a[0];
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    private static Entity zombie() {
        return (Entity) Proxy.newProxyInstance(GestureTest.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, m, a) -> m.getReturnType() == boolean.class ? false : null);
    }

    private static List<String> click(Player who, Entity target, EquipmentSlot hand, boolean on) {
        Recorder r = new Recorder();
        r.on = on;
        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(who, target, hand);
        new TradeListener(r).onGesture(e);
        if (!r.calls.isEmpty()) {
            assertTrue(e.isCancelled(), "a click that asks is cancelled");
        } else {
            assertFalse(e.isCancelled(), "a click that doesn't ask is left alone");
        }
        return r.calls;
    }

    @Test
    void asksOnlyWhenCrouchingWithTheMainHandAtAPlayer() {
        Player helper = player("Helper", false, false, true);
        assertEquals(List.of("ask Helper"),
                click(player("Owner", true, false, true), helper, EquipmentSlot.HAND, true));
        assertEquals(List.of(), click(player("Owner", false, false, true), helper, EquipmentSlot.HAND, true),
                "not crouching");
        assertEquals(List.of(), click(player("Owner", true, false, true), helper, EquipmentSlot.OFF_HAND, true),
                "the off-hand's copy of the click");
        assertEquals(List.of(), click(player("Owner", true, false, true), player("Shopkeeper", false, true, true),
                EquipmentSlot.HAND, true), "an NPC");
        assertEquals(List.of(), click(player("Owner", true, false, true), zombie(), EquipmentSlot.HAND, true),
                "not a player");
        assertEquals(List.of(), click(player("Owner", true, false, false), helper, EquipmentSlot.HAND, true),
                "no hcm.trade.use");
        assertEquals(List.of(), click(player("Owner", true, false, true), helper, EquipmentSlot.HAND, false),
                "trading switched off");
    }

    @Test
    void theHandMustBeEmpty() {
        assertTrue(TradeRules.gestureAsks(true, true, true, true, false));
        assertFalse(TradeRules.gestureAsks(true, true, false, true, false),
                "a held boost, firework, hat or Mini would fire on the same click (T6)");
    }

    @Test
    void theGestureRunsAtHighAndSkipsACancelledClick() throws Exception {
        Method m = TradeListener.class.getMethod("onGesture", PlayerInteractEntityEvent.class);
        EventHandler h = m.getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGH, h.priority());
        assertTrue(h.ignoreCancelled(), "so the Games' LOWEST guard wins");
    }

    @Test
    void aTeleportEndsATradeOnlyOverOneBlockOrToAnotherWorld() {
        World world = (World) Proxy.newProxyInstance(GestureTest.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "hashCode" -> 1;
                    case "equals" -> proxy == a[0];
                    default -> null;
                });
        World nether = (World) Proxy.newProxyInstance(GestureTest.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "hashCode" -> 2;
                    case "equals" -> proxy == a[0];
                    default -> null;
                });
        Location here = new Location(world, 10, 64, 10);
        assertFalse(TradeListener.movedAway(here, new Location(world, 10.5, 64, 10.5)), "a small correction");
        assertTrue(TradeListener.movedAway(here, new Location(world, 12, 64, 10)), "two blocks");
        assertTrue(TradeListener.movedAway(here, new Location(nether, 10, 64, 10)), "another world");
    }
}
