package com.dierks.homecraft.trade;

import com.dierks.homecraft.trade.TradeSession.Cause;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything that ends a trade does so before anything can move under the window (SHOP-V2-SPEC §4.5, §4.8, red team
 * D-m2): a game-mode change cancels at LOWEST, before Multiverse-Inventories swaps the bag at MONITOR (a per-mode
 * profile under the slot references), and logs both modes; a world change at LOWEST, before its LOW swap; a quit,
 * a kick and a death at LOWEST; a teleport at MONITOR, once it is sure to happen. A join rolls a waiting bag back at
 * LOWEST, before anything else touches it.
 */
class GameModeCancelTest {

    private static Player player(GameMode mode) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(GameModeCancelTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> "Owner";
                    case "getUniqueId" -> id;
                    case "getGameMode" -> mode;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == a[0];
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    private static final class Recorder implements TradeListener.Trades {
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean on() {
            return true;
        }

        @Override
        public void gesture(Player asker, Player target) {
        }

        @Override
        public void ended(Player player, Cause cause, String detail) {
            calls.add(cause + (detail == null ? "" : ": " + detail));
        }

        @Override
        public void dropRequests(Player player, String why) {
        }
    }

    @Test
    void aGameModeChangeCancelsAtLowestAndLogsBothModes() throws Exception {
        Method m = TradeListener.class.getMethod("onGameMode", PlayerGameModeChangeEvent.class);
        EventHandler h = m.getAnnotation(EventHandler.class);
        assertEquals(EventPriority.LOWEST, h.priority(),
                "[X] before Multiverse-Inventories' MONITOR profile swap puts another bag under the references");
        Recorder r = new Recorder();
        new TradeListener(r).onGameMode(new PlayerGameModeChangeEvent(player(GameMode.SURVIVAL), GameMode.CREATIVE,
                PlayerGameModeChangeEvent.Cause.COMMAND, null));
        assertEquals(List.of("GAME_MODE: SURVIVAL -> CREATIVE"), r.calls);
    }

    @Test
    void everyEndRunsBeforeTheBagCanChange() throws Exception {
        Map<String, EventPriority> expected = Map.of(
                "onQuit", EventPriority.LOWEST,
                "onKick", EventPriority.LOWEST,
                "onDeath", EventPriority.LOWEST,
                "onWorldChange", EventPriority.LOWEST,
                "onGameMode", EventPriority.LOWEST,
                "onTeleport", EventPriority.MONITOR);
        Map<String, Class<? extends Event>> events = Map.of(
                "onQuit", PlayerQuitEvent.class,
                "onKick", PlayerKickEvent.class,
                "onDeath", PlayerDeathEvent.class,
                "onWorldChange", PlayerChangedWorldEvent.class,
                "onGameMode", PlayerGameModeChangeEvent.class,
                "onTeleport", PlayerTeleportEvent.class);
        for (var e : expected.entrySet()) {
            EventHandler h = TradeListener.class.getMethod(e.getKey(), events.get(e.getKey()))
                    .getAnnotation(EventHandler.class);
            assertEquals(e.getValue(), h.priority(), e.getKey());
        }
        assertFalse(TradeListener.class.getMethod("onQuit", PlayerQuitEvent.class).getAnnotation(EventHandler.class)
                .ignoreCancelled());
        assertTrue(TradeListener.class.getMethod("onTeleport", PlayerTeleportEvent.class)
                .getAnnotation(EventHandler.class).ignoreCancelled(), "a cancelled teleport went nowhere");
    }

    /**
     * Red-team findings TCR4-WORTH-1, R4FA-5: a join or an arrival in another world used to roll a waiting bag back,
     * and a false read at a start took items from an honest player's bag with no admin step. A trade the server
     * didn't see finish is only reported now: nothing listens for a join, and a world change only cancels.
     */
    @Test
    void noJoinOrArrivalTouchesABag() {
        for (Method m : TradeListener.class.getMethods()) {
            if (m.getAnnotation(EventHandler.class) == null) {
                continue;
            }
            assertFalse(org.bukkit.event.player.PlayerJoinEvent.class.isAssignableFrom(m.getParameterTypes()[0]),
                    "[X] no join handler: " + m.getName());
            if (m.getParameterTypes()[0] == PlayerChangedWorldEvent.class) {
                assertEquals("onWorldChange", m.getName(), "[X] the only world-change handler cancels the trade");
            }
        }
    }

    @Test
    void aQuitAndAWorldChangeCancelTheTrade() {
        Recorder r = new Recorder();
        TradeListener l = new TradeListener(r);
        Player p = player(GameMode.SURVIVAL);
        l.onQuit(new PlayerQuitEvent(p, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        l.onWorldChange(new PlayerChangedWorldEvent(p, null));
        assertEquals(List.of("QUIT", "WORLD"), r.calls);
    }
}
