package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.PrizeService.CapPlacement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.trade.TradeService;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a collector cap goes (HALLOWEEN-SPEC D8, kid-bedrock #6): a present goes on the head, swapping
 * an old helmet into the bag, unless that helmet has Curse of Binding; a buy never takes a helmet off. And
 * the present's moment is never hidden behind one of our menus, though it never closes a game screen.
 */
class CapPlacementTest {

    @Test
    void aPresent() {
        assertEquals(CapPlacement.HEAD, PrizeService.capPlacement(true, false, PrizeService.GIFT));
        assertEquals(CapPlacement.SWAP, PrizeService.capPlacement(false, false, PrizeService.GIFT));
        assertEquals(CapPlacement.BAG, PrizeService.capPlacement(false, true, PrizeService.GIFT));
    }

    @Test
    void aBuy() {
        assertEquals(CapPlacement.HEAD, PrizeService.capPlacement(true, false, PrizeService.BUY));
        assertEquals(CapPlacement.BAG, PrizeService.capPlacement(false, false, PrizeService.BUY));
        assertEquals(CapPlacement.BAG, PrizeService.capPlacement(false, true, PrizeService.BUY));
    }

    /**
     * Review BK-1: the present's moment must not play behind the receiver's open menu (on a Bedrock window a
     * chest screen covers the whole view). One of our menus is closed first; any other screen is left open,
     * and so is a game screen (final audit F2): closing a cabinet game ends a friend match for both players, or
     * today's scored try on a Daily board with no score.
     */
    @Test
    void theMomentClosesOnlyOurOwnMenu() {
        Menu ours = new Menu(null) {
            @Override
            protected void build() {
            }
        };
        assertTrue(PrizeService.hidesTheMoment(ours), "the Prize Counter, or any menu of ours: closed");
        InventoryHolder chest = () -> null;
        assertFalse(PrizeService.hidesTheMoment(chest), "a chest, or another plugin's screen: left open");
        assertFalse(PrizeService.hidesTheMoment(null), "nothing open");
        GameMenu game = new GameMenu(null, null, null, null) {
            @Override
            protected void build() {
            }
        };
        assertFalse(PrizeService.hidesTheMoment(game), "a game screen: left open, the game goes on");
    }

    /**
     * Red-team finding BR-3: a present (or {@code /hcm looks give}) closed an open trade window, which cancelled the
     * trade and told the other player she closed it, and closed her "Trade?" screen, which is shown only once. Both
     * stay open now, and the moment plays over them.
     */
    @Test
    void theMomentLeavesATradeOpen() {
        class Window extends Menu implements TradeService.Window {
            Window() {
                super(null);
            }

            @Override
            protected void build() {
            }

            @Override
            public void open() {
            }

            @Override
            public boolean showing() {
                return true;
            }

            @Override
            public void repaint() {
            }

            @Override
            public void close() {
            }

            @Override
            public void hint(String line) {
            }
        }
        class Ask extends Menu implements TradeService.AskScreen {
            Ask() {
                super(null);
            }

            @Override
            protected void build() {
            }

            @Override
            public long requestId() {
                return 1;
            }
        }
        assertFalse(PrizeService.hidesTheMoment(new Window()), "[X] the trade window: left open, the trade goes on");
        assertFalse(PrizeService.hidesTheMoment(new Ask()), "[X] the Trade? screen: left open, she can still say yes");
    }
}
