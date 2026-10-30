package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invite;
import com.dierks.homecraft.games.Invites;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The "Play?" screen an invite opens on the invitee (0.38.4): the daughter can't click chat or type, so every
 * invite (a party race, golf together, a cabinet game against a friend, Coin Flip, a ride along, a challenge)
 * comes as a screen with the asker in the middle, Yes on the right and Not now on the left, the same places as
 * every confirm screen and the "Trade?" screen ({@code gui/trade/TradeAskMenu}). The asker's tile NAME carries
 * who and what ("Mum invites you: Party race"); its lore the summary and the seconds left, ticking.
 *
 * <p>It opens only when nothing else is open and the cursor is empty ({@link Invites#askScreenOpens}), so it
 * never closes a chest or drops a carried stack; the invites try again each second until it has opened once,
 * and never after that. Esc is not an answer: the invite waits, in chat, until it runs out. The screen closes
 * itself when the invite runs out, is called off or is answered another way (a Java click on [Accept], the
 * command, the Games screen's book). A yes that opens the game's own screen (Coin Flip's Confirm) replaces this
 * one; a yes on an invite that went closes it. Built with the invite's OWNING game, so a throw fails that game,
 * not the framework.
 */
public final class InviteAskMenu extends GameMenu implements Invites.AskScreen {

    static final int NO = 11;
    static final int ASKER = 13;
    static final int YES = 15;

    private final Invite invite;
    private final String from;
    private int seconds;

    public InviteAskMenu(HomeCraftManagement plugin, Game owner, Player to, Invite invite, String from, int seconds) {
        super(plugin, owner, to, null);
        this.invite = invite;
        this.from = from;
        this.seconds = seconds;
        init(27, Text.of("&5Play?"));
    }

    @Override
    protected void build() {
        fill();
        set(NO, Menus.icon(Material.RED_STAINED_GLASS_PANE, "&c&l✗ Not now"), e -> {
            GamesService games = plugin.games();
            if (games != null) {
                games.invites().deny(viewer); // the invite's end closes this screen
            }
            closeNow(viewer);
        });
        set(ASKER, askerTile(), null);
        set(YES, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&l✓ Yes"), e -> {
            GamesService games = plugin.games();
            // a yes opens the game's own screen over this one (or seats them); one on a gone invite closes it
            if ((games == null || !games.invites().accept(viewer)) && showing()) {
                closeNow(viewer);
            }
        });
    }

    /** Open, then the seconds tick on the asker's tile once a second (the ticker stops with the screen). */
    @Override
    public void open(Player player) {
        super.open(player);
        ticker(20, this::tick);
    }

    /** The invite went (answered another way, run out, called off): close; else the seconds left. */
    private void tick() {
        GamesService games = plugin.games();
        Invite now = games == null ? null : games.invites().pending(viewer.getUniqueId());
        if (now == null || now.id() != invite.id()) {
            closeNow(viewer);
            return;
        }
        seconds = (int) Math.max(0, (now.expiresAt() - games.clock().nowMillis() + 999) / 1000);
        set(ASKER, askerTile(), null); // one decorative slot: nothing under a finger moves
    }

    private ItemStack askerTile() {
        return Menus.icon(Material.PAPER, askerName(from, invite.name()), "&7" + invite.summary(),
                "&7" + seconds + " s left");
    }

    @Override
    public long inviteId() {
        return invite.id();
    }

    /** The middle tile's NAME: "&amp;bMum invites you: &amp;fParty race" (the key fact, for Bedrock). */
    static String askerName(String from, String what) {
        return "&b" + from + " invites you: &f" + what;
    }
}
