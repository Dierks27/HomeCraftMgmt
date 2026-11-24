package com.dierks.homecraft.gui.games.sportsbook;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.chance.sportsbook.Board;
import com.dierks.homecraft.games.chance.sportsbook.Lines;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * How much to put on the side picked (54 slots), with the Game Parlor's chips.
 *
 * <pre>
 *  row 0   4 the side: the game, its start, its chance
 *  row 1   13 what the chips so far lock in if it wins
 *  row 3   29-31 the chips · 33 the chips so far (a click takes them back)
 *  row 4   40 Next: the confirm screen
 *  row 5   46 how it works · 47 your balance · 48 today · 49 Back
 * </pre>
 *
 * A chip goes on only while the stake stays inside the biggest stake, the day's limit, the balance and the most this
 * side takes under the biggest payout. Nothing here moves money: the confirm screen asks the board.
 */
public final class SportsStakeMenu extends GameMenu {

    static final int SIDE = 4;
    static final int PAYS = 13;
    static final int[] CHIPS = {29, 30, 31};
    static final int STAKE = 33;
    static final int NEXT = 40;

    private final Sportsbook sb;
    private final String event;
    private final String ticker;
    private final String side;
    private int stake;

    public SportsStakeMenu(HomeCraftManagement plugin, Sportsbook game, Player viewer, Runnable back, String event,
                           String ticker, String side) {
        this(plugin, game, viewer, back, event, ticker, side, 0);
    }

    SportsStakeMenu(HomeCraftManagement plugin, Sportsbook game, Player viewer, Runnable back, String event,
                    String ticker, String side, int stake) {
        super(plugin, game, viewer, back);
        this.sb = game;
        this.event = event;
        this.ticker = ticker;
        this.side = side;
        this.stake = Math.max(0, stake);
        init(54, Text.of("&2Sports Board &8- your pick"));
    }

    @Override
    protected void build() {
        fill();
        long now = sb.games().clock().nowMillis();
        Board.Found found = sb.board().find(ticker, side);
        if (found == null || !found.game().event().equals(event)) {
            set(22, Menus.icon(Material.GRAY_DYE, "&7That game has left the board",
                    "&7Its picks are closed. Go back to see the rest."), null);
            exitTile();
            return;
        }
        Board.Game g = found.game();
        Board.Side s = found.side();
        Sportsbook.State state = sb.state(g, s, now);
        String starts = Sportsbook.when(g.startsAt(), now, sb.games().clock().zone());
        List<String> lore = SportsTiles.sideLines(g, s, state, null, starts);
        lore.removeIf(l -> l.startsWith("&8Not offered at these"));
        set(SIDE, Menus.icon(Material.LIME_CONCRETE, "&a" + s.label() + " &7to win", lore.toArray(new String[0])),
                null);
        int most = sb.mostFor(s.price());
        Parlor.Rules rules = sb.games().parlor().rules();
        if (stake > most) {
            stake = 0;
        }
        Sportsbook.Quote q = state == Sportsbook.State.OPEN ? sb.quote(s, stake) : null;
        set(PAYS, paysTile(q, stake, most), null);
        double balance = plugin.economy() == null ? 0 : plugin.economy().balance(viewer);
        int left = sb.games().parlor().leftToday(viewer.getUniqueId());
        for (int i = 0; i < CHIPS.length && i < rules.chips().size(); i++) {
            int chip = rules.chips().get(i);
            String why = chipRefusal(stake, chip, rules, left, balance, most);
            if (why != null) {
                set(CHIPS[i], Menus.icon(Material.LIGHT_GRAY_CONCRETE, "&7+" + Parlor.dollars(chip) + " &8- " + why),
                        null);
            } else {
                set(CHIPS[i], Menus.count(Menus.icon(SportsTiles.CHIP[Math.min(i, SportsTiles.CHIP.length - 1)],
                        "&6+" + Parlor.dollars(chip), "&eClick to add it"), Math.min(64, chip)), e -> {
                            stake += chip;
                            hold(com.dierks.homecraft.gui.games.ClickHold.ACTION_MS);
                            refresh();
                        });
            }
        }
        if (stake <= 0) {
            set(STAKE, Menus.icon(Material.BOWL, "&7No chips yet", "&7Click a chip to add it."), null);
        } else {
            set(STAKE, Menus.glint(Menus.icon(Material.RAW_GOLD, "&6" + Parlor.dollars(stake) + " in",
                    "&eClick to take your chips back"), true), e -> {
                        stake = 0;
                        refresh();
                    });
        }
        if (state != Sportsbook.State.OPEN) {
            set(NEXT, Menus.icon(Material.LIGHT_GRAY_CONCRETE, "&7Picks on this side are closed",
                    SportsTiles.sideLines(g, s, state, null, starts).getLast()), null);
        } else if (q == null) {
            set(NEXT, Menus.icon(Material.LIGHT_GRAY_CONCRETE, "&7Add a chip first"), null);
        } else {
            Sportsbook.Quote locked = q;
            set(NEXT, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aNext: check your pick",
                    "&f" + Parlor.dollars(q.stake()) + " in → &6" + Parlor.money(q.payout()) + " back &7if they win"),
                    true), e -> new SportsConfirmMenu(plugin, sb, viewer, back, event, locked).open(viewer));
        }
        set(46, SportsTiles.howTile(sb.settings().rtp()), null);
        set(47, SportsTiles.balanceTile(plugin, viewer), null);
        set(48, SportsTiles.todayTile(sb, viewer), null);
        exitTile();
    }

    /** What the chips so far give back if the side wins (pure but for the tile). */
    private static org.bukkit.inventory.ItemStack paysTile(Sportsbook.Quote q, int stake, int most) {
        if (q == null) {
            return Menus.icon(Material.PAPER, stake <= 0 ? "&7Add chips to see what it pays" : "&7Not offered",
                    "&7The most this side takes is " + Parlor.dollars(Math.max(0, most)) + ",",
                    "&7so a win never pays over the biggest payout.");
        }
        return Menus.icon(Material.EMERALD, "&f" + Parlor.dollars(q.stake()) + " in → &6" + Parlor.money(q.payout())
                        + " back &7if they win",
                "&7If they don't win, nothing comes back.",
                "&7If the game is called off, your " + Parlor.dollars(q.stake()) + " comes back.",
                "&7Chance now: about " + Lines.inHundred(q.price()) + " in 100.");
    }

    /**
     * Why one more {@code chip} can't go on (pure): the Parlor's own reasons, or the most this side takes under the
     * biggest payout; {@code null} when it can.
     */
    static String chipRefusal(int stake, int chip, Parlor.Rules rules, int leftToday, double balance, int most) {
        String why = Parlor.chipRefusal(stake, chip, rules, leftToday, balance);
        if (why != null) {
            return why;
        }
        return (long) stake + chip > most ? "the most on this side is " + Parlor.dollars(Math.max(0, most)) : null;
    }
}
