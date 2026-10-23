package com.dierks.homecraft.gui.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.trade.TradeRequests;
import com.dierks.homecraft.trade.TradeService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Bedrock "Trade?" screen (SHOP-V2-SPEC §4.2): she can't click chat or type, so a trade request comes as a
 * screen with the asker in the middle, Yes on the right and No thanks on the left, the same places as every confirm
 * screen. The service opens it only when no container of any kind is open and her cursor is empty, so it never
 * closes a chest or drops a carried stack. Esc is not an answer: the request waits until it runs out. The screen
 * closes on its own when its request runs out, is taken back or is called off.
 */
public final class TradeAskMenu extends Menu implements TradeService.AskScreen {

    static final int NO = 11;
    static final int ASKER = 13;
    static final int YES = 15;

    private final TradeService trades;
    private final Player to;
    private final TradeRequests.Request request;

    public TradeAskMenu(HomeCraftManagement plugin, TradeService trades, Player to, TradeRequests.Request request) {
        super(plugin);
        this.trades = trades;
        this.to = to;
        this.request = request;
        init(27, Text.of("&5Trade?"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        set(NO, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c&l✗ No thanks"), e -> {
            trades.deny(to, request.fromName());
            to.closeInventory();
        });
        set(ASKER, Menus.icon(Material.PAPER, askerName(request.fromName())), null);
        set(YES, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Yes, trade"), e -> {
            // a yes opens the trade window over this screen; one that couldn't start closes it
            if (!trades.accept(to, request.fromName()) && isOpenFor(to)) {
                to.closeInventory();
            }
        });
    }

    @Override
    public long requestId() {
        return request.id();
    }

    /** The middle tile's NAME. */
    static String askerName(String from) {
        return "&b" + from + " wants to trade!";
    }
}
