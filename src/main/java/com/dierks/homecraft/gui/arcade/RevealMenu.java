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
 * The reward reveal after opening a crate, running the pity exchange, or scratching
 * a ticket — shows the won item/prize front-and-centre before returning to the hub.
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
        init(27, Text.of("&5You won!"));
    }

    /**
     * The fanfare rides on open, not on build.
     *
     * <p>build() also runs on every refresh, and a menu that plays a sound when it merely repaints
     * would chime at a player for as long as they left it open. This screen is where a crate pull,
     * a pity redemption and a scratch ticket all land, and it was silent — while PackRevealMenu two
     * files over plays a note per card and a fanfare at the end. The arcade's actual payoff was its
     * quietest moment.
     */
    @Override
    public void open(Player viewer) {
        super.open(viewer);
        Sounds.won(player);
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        ItemStack icon = outcome.icon() != null ? outcome.icon() : Menus.icon(Material.PAPER, "&7Prize");
        set(13, icon, null);
        set(22, Menus.icon(Material.ARROW, "&aBack to Arcade"), e -> {
            if (onBack != null) {
                onBack.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
        if (outcome.label() != null) {
            player.sendMessage(Text.of("&5Arcade: &fyou won " + outcome.label() + "&f!"));
        }
    }
}
