package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.GameClock;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiles the Arcade's two card games share (Twenty-One, Higher or Lower): a face-up card, the
 * day's position against the player's limits, and the stake buttons' look.
 *
 * <p>A face-up card is paper named for its card ("&amp;cQueen of Hearts"), with the stack count
 * showing what it counts, so the row reads at a glance even on Bedrock where lore needs a long
 * press. Nothing here plays a sound or sends chat: these are painted in {@code build()}.
 */
final class CardGameTiles {

    private CardGameTiles() {
    }

    /** A face-up card: its name in the NAME (red suits red), {@code count} as the stack size. */
    static ItemStack card(String name, boolean red, int count, String... lore) {
        return Menus.count(Menus.icon(Material.PAPER, (red ? "&c" : "&f") + name, lore), count);
    }

    /**
     * "Today: 35 of 100 tokens" (or "Today: 35 tokens" with no limit at all), with the plays left
     * of this game today underneath (spec §4.1).
     *
     * @param plays "hands" or "runs"
     */
    static ItemStack today(GamesService games, Game game, Player viewer, GameClock clock, int dailyLimit,
                           String plays) {
        Breaks breaks = games.breaks();
        int in = breaks == null ? 0 : breaks.tokensInToday(viewer.getUniqueId());
        int limit = breaks == null ? Breaks.NO_LIMIT : breaks.limit(viewer.getUniqueId());
        String name = limit == Breaks.NO_LIMIT ? "&eToday: &6" + in + " tokens"
                : "&eToday: &6" + in + " &eof &6" + limit + " tokens";
        List<String> lore = new ArrayList<>();
        lore.add("&7Tokens put into games of chance today.");
        try {
            int played = games.dao().playsToday(viewer.getUniqueId(), game.id(), clock.dayKey());
            lore.add("&7" + game.name() + " " + plays + " left today: &f" + Math.max(0, dailyLimit - played));
        } catch (SQLException | RuntimeException e) {
            // The count is a courtesy; the gate still enforces the limit when tokens go in.
        }
        lore.add("&7A limit of your own: Wallet → Take a break.");
        return Menus.icon(Material.CLOCK, name, lore.toArray(new String[0]));
    }

    /** A stake button: "5 tokens in", lit when chosen. */
    static ItemStack stake(int stake, boolean chosen, List<String> lore) {
        List<String> lines = new ArrayList<>(lore);
        lines.add(chosen ? "&aChosen" : "&eClick to choose");
        ItemStack icon = Menus.icon(Material.GOLD_NUGGET, (chosen ? "&6&l" : "&6") + stake + " tokens in",
                lines.toArray(new String[0]));
        return Menus.glint(Menus.count(icon, stake), chosen);
    }

    /** The slots stake buttons use, in order: row 5's three, then row 4 for any more. */
    static final int[] STAKE_SLOTS = {46, 47, 48, 36, 37, 38, 39, 40, 41, 42, 43, 44};

    /** A button that can't be used now: grey, with why in its name. */
    static ItemStack unlit(String name, String why) {
        return Menus.icon(Material.LIGHT_GRAY_CONCRETE, "&7" + name + (why == null ? "" : " &8- " + why));
    }
}
