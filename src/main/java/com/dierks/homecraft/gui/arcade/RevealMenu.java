package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The result screen after opening a crate, running the pity exchange, or scratching a ticket —
 * the prize (or the miss) front and centre, then back to wherever the player came from.
 *
 * <p>A miss is not a win. This screen used to be titled "You won!", play the level-up fanfare and
 * print "Arcade: you won no win — try again!" for a ticket that paid nothing — three ways of
 * telling a child the opposite of what happened. A loss now simply says what happened ("No prize
 * this time"), plays a soft note and prints nothing: no near-miss wording, no nudge to go again
 * (spec §2, R1.18).
 */
public final class RevealMenu extends Menu {

    private final Player player;
    private final ArcadeService.Outcome outcome;
    private final Runnable onBack;

    public RevealMenu(HomeCraftManagement plugin, Player player, ArcadeService.Outcome outcome, Runnable onBack) {
        super(plugin);
        this.player = player;
        this.outcome = outcome;
        this.onBack = onBack;
        init(27, Text.of(outcome.win() ? "&5You won!" : "&8No prize this time"));
    }

    /**
     * The sound and the chat line ride on open, not on build.
     *
     * <p>build() also runs on every refresh, and a menu that plays a sound or prints a line when
     * it merely repaints would chime at a player for as long as they left it open.
     */
    @Override
    public void open(Player viewer) {
        super.open(viewer);
        if (outcome.win()) {
            Sounds.won(player);
            if (outcome.label() != null) {
                player.sendMessage(Text.of("&aYou got " + outcome.label() + "&a!"));
            }
            if (outcome.big()) {
                BigWin.celebrate(player, outcome.label());
            }
        } else {
            Sounds.miss(player);
        }
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        ItemStack icon = outcome.icon() != null ? outcome.icon() : Menus.icon(Material.PAPER, "&7Prize");
        set(13, icon, null);
        set(22, Menus.icon(Material.BARRIER, onBack != null ? "&cBack" : "&cClose"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }
}
