package com.dierks.homecraft.games.world;

import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.MenuListener;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Card trade-in tray hands its Cards back before Multiverse-Inventories saves (final review, minor).
 * Multiverse-Inventories now disables before this plugin (this plugin loads before Multiverse-Core, for
 * the void world) and saves every player's things as it goes; the menus used to close only in this
 * plugin's own onDisable, after that save, so with its load-on-join setting a child's Cards could be
 * lost. Now the recovery listener closes our menus at Multiverse-Inventories' PluginDisableEvent
 * ({@link SessionRecoveryListener#closeMenusBefore}), just before it saves, as it ends world sessions
 * there; and a menu closes once ({@link Menu#closeNow}): neither the close event that close fires (our
 * listener still hears it then) nor this plugin's own close at its disable hands anything back twice.
 *
 * <p>The tray here is a menu with no inventory of its own (no server: an item can't be made) that
 * counts what it hands back, and hands it back every time its close runs, so a second run would show.
 */
class MenusBeforeInventoriesSaveTest {

    private static final Logger LOG = Logger.getLogger("MenusBeforeInventoriesSaveTest");

    /** A trade-in tray holding {@code cards}: every run of its close hands them all back. */
    private static final class Tray extends Menu {
        final int cards;
        int handedBack;
        int closes;

        Tray(int cards) {
            super(null);
            this.cards = cards;
        }

        @Override
        protected void build() {
        }

        @Override
        protected void onClose(Player player) {
            closes++;
            handedBack += cards;
        }
    }

    /**
     * A player looking at {@code menu} (or nothing, {@code null}). Closing their inventory fires the close
     * event to our {@link MenuListener}, as the server does while this plugin is still enabled, then shows
     * their own inventory; {@code closed} counts the closes.
     */
    private static final class Viewer {
        final UUID id = UUID.randomUUID();
        InventoryHolder top;
        int closed;
        final Player player;

        Viewer(Menu menu) {
            this.top = menu;
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, m, a) -> switch (m.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> "Ava";
                        case "isOnline" -> true;
                        case "getOpenInventory" -> view();
                        case "closeInventory" -> {
                            closed++;
                            if (top instanceof Menu) {
                                new MenuListener().onClose(new InventoryCloseEvent(view()));
                            }
                            top = null;
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == a[0];
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
        }

        private InventoryView view() {
            InventoryHolder holder = top;
            Inventory inv = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                    new Class<?>[]{Inventory.class}, (proxy, m, a) -> switch (m.getName()) {
                        case "getHolder" -> holder;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == a[0];
                        default -> nothing(m.getReturnType());
                    });
            return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                    new Class<?>[]{InventoryView.class}, (proxy, m, a) -> switch (m.getName()) {
                        case "getTopInventory" -> inv;
                        case "getPlayer" -> player;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == a[0];
                        default -> nothing(m.getReturnType());
                    });
        }
    }

    /** What a fake answers a call it doesn't care about. */
    private static Object nothing(Class<?> type) {
        return type == boolean.class ? false : type == int.class ? 0 : null;
    }

    @Test
    void theTrayHandsItsCardsBackWhenMultiverseInventoriesDisablesAndOnlyOnce() {
        Tray tray = new Tray(5);
        Viewer ava = new Viewer(tray);
        Viewer ben = new Viewer(null); // looking at nothing of ours

        assertTrue(SessionRecoveryListener.closeMenusBefore("Multiverse-Inventories", List.of(ava.player, ben.player),
                LOG), "Multiverse-Inventories is about to save everyone: our menus close first");
        assertEquals(5, tray.handedBack, "Ava's five Cards are back in her inventory before it is saved");
        assertEquals(1, tray.closes, "once: the close event that closing fired (our listener still hears it then)"
                + " is the same close, not a second one");
        assertNull(ava.top, "and the tray is shut");
        assertEquals(0, ben.closed, "a player looking at nothing of ours is left alone");

        // this plugin's own disable, later: its close finds nothing of ours open
        Menu.closeAll(List.of(ava.player, ben.player), LOG);
        assertEquals(5, tray.handedBack, "nothing is handed back twice");
        assertEquals(1, tray.closes, "the tray's close ran once in all");
        tray.closeNow(ava.player);
        assertEquals(1, tray.closes, "even when asked again directly");
    }

    @Test
    void otherPluginsDisablingLeaveTheMenusOpen() {
        Tray tray = new Tray(3);
        Viewer ava = new Viewer(tray);
        assertFalse(SessionRecoveryListener.closeMenusBefore("Multiverse-Core", List.of(ava.player), LOG),
                "Multiverse-Core saves nobody's things");
        assertEquals(0, tray.closes, "the tray stays open");
        assertTrue(SessionRecoveryListener.closeMenusBefore("multiverse-inventories", List.of(ava.player), LOG),
                "the name in any case");
        assertEquals(3, tray.handedBack, "then the Cards go back");
    }

    @Test
    void withoutMultiverseInventoriesThePluginsOwnDisableStillHandsTheTrayBackOnce() {
        Tray tray = new Tray(2);
        Viewer ava = new Viewer(tray);
        Menu.closeAll(List.of(ava.player), LOG);
        assertEquals(2, tray.handedBack, "this plugin's own disable hands the Cards back, as before");
        assertEquals(1, tray.closes, "once");
        assertEquals(1, ava.closed, "and shuts the tray");
    }

    @Test
    void aCloseThePlayerMakesStillHandsTheTrayBack() {
        Tray tray = new Tray(4);
        Viewer ava = new Viewer(tray);
        ava.player.closeInventory(); // the player shuts it: the close event, as always
        assertEquals(4, tray.handedBack, "the Cards go back");
        assertEquals(1, tray.closes, "once");
        List<Player> none = new ArrayList<>();
        Menu.closeAll(none, LOG);
        tray.closeNow(ava.player);
        assertEquals(1, tray.closes, "a shutdown afterwards hands nothing back again");
    }
}
