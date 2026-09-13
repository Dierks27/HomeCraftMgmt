package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The Prize Counter (§3.9) — the known-outcome half of the Arcade. A crate is a pull;
 * this is a price. You can see what you are buying before you spend, which is what gives
 * tokens a floor value rather than only an expected one, and it is the one place in the
 * Arcade where a player who dislikes gambling can still spend what they earned.
 *
 * <p>Every row comes from {@code arcade.prizes} and pays out filament, a HomeCraft block,
 * or a sealed Card pack — never money and never a market good, so §11 #9 holds.
 */
public final class PrizeCounterMenu extends Menu {

    /** The grid: rows 1–3 of a 54-slot chest, nine wide. */
    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 27;

    private final Player player;
    private final Runnable back;

    public PrizeCounterMenu(HomeCraftManagement plugin, Player player, Runnable back) {
        super(plugin);
        this.player = player;
        this.back = back;
        init(54, Text.of("&5&lPrize Counter"));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        int tokens = plugin.arcade().balance(player.getUniqueId());
        set(4, Menus.icon(Material.SUNFLOWER, "&eYour Tokens: &6" + tokens,
                "&7Everything here has a fixed price.",
                "&8No pulls, no odds — you see what you get."), null);

        List<PluginConfig.Prize> prizes = plugin.config().arcade().prizes();
        if (prizes.isEmpty()) {
            set(22, Menus.icon(Material.BARRIER, "&7The counter is empty",
                    "&8An admin can stock it under arcade.prizes."), null);
        }

        for (int i = 0; i < GRID_SIZE && i < prizes.size(); i++) {
            PluginConfig.Prize prize = prizes.get(i);
            boolean affordable = tokens >= prize.costTokens();
            set(GRID_START + i, icon(prize, affordable), e -> {
                if (plugin.arcade().balance(player.getUniqueId()) < prize.costTokens()) {
                    player.sendMessage(Text.of("&cYou need &6" + prize.costTokens()
                            + " tokens&c for that."));
                    return;
                }
                // A prize that lets the buyer choose its colour needs one more click; every
                // other prize is already fully specified, so buy it outright.
                if (prize.choosesColor()) {
                    new FilamentColorMenu(plugin, player, prize, this::reopen).open(player);
                } else {
                    buy(prize, null);
                }
            });
        }

        if (back != null) {
            set(49, Menus.icon(Material.ARROW, "&eBack to the Arcade"), e -> back.run());
        } else {
            set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        }
    }

    private void buy(PluginConfig.Prize prize, org.bukkit.DyeColor color) {
        var r = plugin.arcade().buyPrize(player, prize.id(), color);
        if (!r.ok()) {
            player.sendMessage(Text.of("&c" + r.error()));
        } else {
            player.sendMessage(Text.of("&a✔ Bought " + r.label() + "&a for &6"
                    + prize.costTokens() + " tokens&a."));
        }
        refresh();
    }

    /** The tile for one prize: what it is, what it costs, and whether it is within reach. */
    private ItemStack icon(PluginConfig.Prize prize, boolean affordable) {
        String cost = "&7Cost: &6" + prize.costTokens() + " token"
                + (prize.costTokens() == 1 ? "" : "s");
        String what = switch (prize.type()) {
            case FILAMENT -> prize.color() != null
                    ? "&7" + prize.amount() + "x " + pretty(prize.color().name()) + " Filament"
                    : "&7" + prize.amount() + "x filament — &fyou pick the colour";
            case BLOCK -> "&7A placeable HomeCraft block.";
            case PACK -> "&7A sealed Card pack.";
        };
        return Menus.icon(material(prize), prize.display(), what, cost, "&8—",
                affordable ? "&eClick to buy" : "&cNot enough tokens");
    }

    /** A material that looks like the thing being sold, so the grid reads without hovering. */
    private Material material(PluginConfig.Prize prize) {
        return switch (prize.type()) {
            case FILAMENT -> prize.color() != null
                    ? dyeMaterial(prize.color()) : Material.WHITE_DYE;
            case PACK -> Material.PAPER;
            case BLOCK -> blockMaterial(prize);
        };
    }

    private Material dyeMaterial(org.bukkit.DyeColor color) {
        Material m = Material.matchMaterial(color.name() + "_DYE");
        return m != null ? m : Material.WHITE_DYE;
    }

    /** The real block's configured material, so a re-skinned block still looks like itself. */
    private Material blockMaterial(PluginConfig.Prize prize) {
        ItemStack it = null;
        try {
            it = plugin.items().of(com.dierks.homecraft.block.CustomBlockType.valueOf(prize.blockKey()));
        } catch (RuntimeException ignored) {
            // a misconfigured row still gets a tile rather than breaking the whole counter
        }
        return it != null ? it.getType() : Material.CHEST;
    }

    private static String pretty(String enumName) {
        String n = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void reopen() {
        new PrizeCounterMenu(plugin, player, back).open(player);
    }
}
