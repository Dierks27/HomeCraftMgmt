package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Colour picker for a Prize Counter filament bundle that lets the buyer choose.
 *
 * <p>Filament is the one prize where the colour is the whole point: a Card names the
 * colours its Mini needs, so a bundle of the wrong dye is worth nothing to the player
 * holding it. Letting them pick is what turns this from a consolation prize into the
 * reason to come back — tokens earned by playing become the Mini they were short of.
 */
public final class FilamentColorMenu extends Menu {

    /** Slots for the sixteen colours: the middle three rows, centred six-wide. */
    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15,
            19, 20, 21, 22, 23, 24,
            28, 29, 30, 31,
    };

    private final Player player;
    private final PluginConfig.Prize prize;
    private final Runnable back;

    public FilamentColorMenu(HomeCraftManagement plugin, Player player,
                             PluginConfig.Prize prize, Runnable back) {
        super(plugin);
        this.player = player;
        this.prize = prize;
        this.back = back;
        init(45, Text.of("&5Pick a filament colour"));
    }

    @Override
    protected void build() {
        int tokens = plugin.arcade().balance(player.getUniqueId());
        set(4, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens,
                "&7" + prize.amount() + "x filament for &6" + prize.costTokens() + " tokens",
                "&8Pick the colour you need for your next print."), null);

        DyeColor[] colors = DyeColor.values();
        for (int i = 0; i < SLOTS.length && i < colors.length; i++) {
            DyeColor color = colors[i];
            set(SLOTS[i], Menus.icon(dyeMaterial(color), "&f" + pretty(color.name()),
                    "&7" + prize.amount() + "x " + pretty(color.name()) + " Filament",
                    "&7Cost: &6" + prize.costTokens() + " tokens",
                    "&8—", "&eClick to buy"), e -> buy(color));
        }

        set(40, Menus.icon(Material.ARROW, "&eBack"), e -> back.run());
    }

    private void buy(DyeColor color) {
        var r = plugin.arcade().buyPrize(player, prize.id(), color);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
        } else {
            player.sendMessage(Text.of("&a✔ Bought " + r.label() + "&a for &6"
                    + prize.costTokens() + " tokens&a."));
        }
        back.run();
    }

    private Material dyeMaterial(DyeColor color) {
        Material m = Material.matchMaterial(color.name() + "_DYE");
        return m != null ? m : Material.WHITE_DYE;
    }

    private static String pretty(String enumName) {
        String n = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
