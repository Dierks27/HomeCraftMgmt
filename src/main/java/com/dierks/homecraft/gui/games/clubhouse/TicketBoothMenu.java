package com.dierks.homecraft.gui.games.clubhouse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.arcade.ScratchTicketMenu;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Scratch Ticket's booth in the Game Parlor (27; 0.46.1, the owner: "Scratch ticket is lottery... I would put that
 * in the parlor as well"). A tap on the booth opens this, never a ticket: tokens only ever go in on a button that says
 * what it costs.
 *
 * <pre>
 *  row 0     4 the ticket: what it costs, the top prize, what it gives back
 *  row 1     11-15 Buy one (one big button)
 *  row 2     20 your tokens · 22 Close
 * </pre>
 *
 * Buying is the Arcade's own {@link ArcadeService#scratch}: the same price, the same prizes, the same pot, Take a
 * break and the day's token limit. The ticket's own screen follows, and comes back here.
 */
public final class TicketBoothMenu extends GameMenu {

    static final int TICKET = 4;
    static final int[] BUY = {11, 12, 13, 14, 15};
    static final int TOKENS = 20;

    public TicketBoothMenu(HomeCraftManagement plugin, Game clubhouse, Player viewer) {
        super(plugin, clubhouse, viewer, null);
        init(27, Text.of("&aScratch Ticket"));
    }

    @Override
    protected void build() {
        fill();
        PluginConfig.Lotto lotto = plugin.config().arcade().lotto();
        int cost = lotto.ticketTokens();
        boolean closed = plugin.arcade() == null || lotto.payouts().isEmpty() || ArcadeService.ticketClosed(lotto);
        if (closed) {
            set(TICKET, Menus.icon(Material.FILLED_MAP, "&aScratch Ticket", "&7" + ArcadeService.TICKET_CLOSED), null);
        } else {
            set(TICKET, Menus.glint(Menus.icon(Material.FILLED_MAP, "&aScratch Ticket &7- &6" + cost + " tokens",
                    "&7Top prize right now: &6" + plugin.arcade().pot() + " tokens",
                    "&7It " + RtpLimits.playerLine(ArcadeService.rtp(lotto)) + ",", "&7over lots of tickets.",
                    "&7Scratch three squares to see what you get."), true), null);
            for (int slot : BUY) {
                set(slot, Menus.icon(Material.LIME_CONCRETE, "&a&lBuy one &7- &6" + cost + " tokens",
                        "&7The ticket is decided when you buy it.", "&7Scratching just shows it."), e -> buy());
            }
        }
        set(TOKENS, balanceTile(), null);
        exitTile();
    }

    private void buy() {
        if (plugin.arcade() == null) {
            return;
        }
        ArcadeService.Outcome r = plugin.arcade().scratch(viewer);
        if (r.ok()) {
            new ScratchTicketMenu(plugin, viewer, r,
                    () -> new TicketBoothMenu(plugin, game, viewer).open(viewer)).open(viewer);
        } else {
            viewer.sendMessage(Text.of("&c" + r.error()));
            Sounds.refused(viewer);
            refresh();
        }
    }
}
