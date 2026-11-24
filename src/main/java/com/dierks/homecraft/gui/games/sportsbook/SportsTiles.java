package com.dierks.homecraft.gui.games.sportsbook;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.chance.sportsbook.Board;
import com.dierks.homecraft.games.chance.sportsbook.Lines;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.gui.Menus;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Sports Board's screens share: the "How it works" tile, the balance and today's money, a side's lines, and
 * the words for a side that can't be picked. The lines are pure ({@code static}, tested); nothing here moves money.
 */
final class SportsTiles {

    /** The chips' pictures, smallest first (the Parlor's own). */
    static final Material[] CHIP = {Material.COPPER_INGOT, Material.IRON_INGOT, Material.GOLD_INGOT};

    private SportsTiles() {
    }

    /** "How it works": where the prices come from, that they are locked, what comes back, when results come. */
    static List<String> howLines(double rtp) {
        String back = Sportsbook.percent(rtp);
        List<String> out = new ArrayList<>();
        out.add("&7Every game here is a real game tonight.");
        out.add("&7Prices come from Kalshi's markets: a side's");
        out.add("&7chance is what its market says it is.");
        out.add("&7Your price is locked when you pick, whatever");
        out.add("&7the market does after.");
        out.add("&7Every pick gives back about &f" + back + " &7of every");
        out.add("&7100 dollars over time: a long shot pays more");
        out.add("&7when it wins, a favourite less.");
        out.add("&7Results come when the real game is decided.");
        out.add("&7A game called off gives your money back.");
        out.add("&7Picks close when the game starts.");
        return out;
    }

    static ItemStack howTile(double rtp) {
        return Menus.icon(Material.BOOK, "&eHow it works", howLines(rtp).toArray(new String[0]));
    }

    /**
     * A side's lines on the board (pure): its chance, then what {@code quote} locks in, or why it can't be picked.
     *
     * @param quote what {@link Sportsbook#WALL_STAKE} (or the most it takes) gives back, or {@code null}
     */
    static List<String> sideLines(Board.Game g, Board.Side s, Sportsbook.State state, Sportsbook.Quote quote,
                                  String starts) {
        List<String> lore = new ArrayList<>();
        lore.add("&7" + g.title());
        lore.add("&7Starts " + starts);
        if (s.price() > 0) {
            lore.add("&7Wins about &f" + Lines.inHundred(s.price()) + " in 100");
        }
        switch (state) {
            case OPEN -> {
                if (quote != null) {
                    lore.add("&f" + Parlor.dollars(quote.stake()) + " in → &6" + Parlor.money(quote.payout())
                            + " back &7if they win");
                    lore.add("&eClick to pick " + s.label());
                } else {
                    lore.add("&8Not offered at these stakes.");
                }
            }
            case NOT_OFFERED -> lore.add("&8Not offered: " + (s.price() <= 0 ? "no price yet."
                    : "too one-sided to be worth a pick."));
            case STARTED -> lore.add("&8The game has started: picks are closed.");
            case UPDATING -> lore.add("&8Lines are updating.");
            case OFF -> lore.add("&8The Sports Board is closed right now.");
        }
        return lore;
    }

    /** A side that can't be picked now. */
    static ItemStack unlit(String name, List<String> lore) {
        return Menus.icon(Material.LIGHT_GRAY_CONCRETE, "&7" + name, lore.toArray(new String[0]));
    }

    /** "You have $123.45". */
    static ItemStack balanceTile(HomeCraftManagement plugin, Player viewer) {
        double have = plugin.economy() == null ? 0 : plugin.economy().balance(viewer);
        return Menus.icon(Material.GOLD_INGOT, "&eYou have " + Menus.money(plugin, have));
    }

    /** "Today: $35 of $250": money put into the Parlor's games today, against the player's limit. */
    static ItemStack todayTile(Sportsbook sb, Player viewer) {
        Parlor parlor = sb.games().parlor();
        int in = Math.max(0, parlor.inToday(viewer.getUniqueId()));
        int limit = parlor.limit(viewer.getUniqueId());
        String name = limit == Parlor.NO_LIMIT ? "&eToday: &6" + Parlor.dollars(in)
                : "&eToday: &6" + Parlor.dollars(in) + " &eof &6" + Parlor.dollars(limit);
        return Menus.icon(Material.CLOCK, name, "&7Money put into the Parlor's games today,",
                "&7picks included.", "&7A pause of your own: Wallet → Take a break.");
    }
}
