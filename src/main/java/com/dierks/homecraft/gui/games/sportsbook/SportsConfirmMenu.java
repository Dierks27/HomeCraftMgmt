package com.dierks.homecraft.gui.games.sportsbook;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.chance.sportsbook.Board;
import com.dierks.homecraft.games.chance.sportsbook.Lines;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The last screen before a pick (27 slots): exactly what is locked in, then Confirm.
 *
 * <pre>
 *  4  the pick: the game and its start, who, the chance, the money put in now, what a win gives back, what a game
 *     called off gives back, and that the price is locked
 *  11 Confirm · 15 Change the amount · 22 Back to the games
 * </pre>
 *
 * Confirm asks the board ({@link Sportsbook#place}), which checks everything again and locks this very price; if the
 * price moved since, it says so and this screen shows the new numbers.
 */
public final class SportsConfirmMenu extends GameMenu {

    static final int PICK = 4;
    static final int CONFIRM = 11;
    static final int CHANGE = 15;

    private final Sportsbook sb;
    private final String event;
    private final Sportsbook.Quote quote;

    public SportsConfirmMenu(HomeCraftManagement plugin, Sportsbook game, Player viewer, Runnable back, String event,
                             Sportsbook.Quote quote) {
        super(plugin, game, viewer, back);
        this.sb = game;
        this.event = event;
        this.quote = quote;
        init(27, Text.of(MenuStyle.title("Sports Board", "lock it in?")));
    }

    @Override
    protected void build() {
        fill();
        long now = sb.games().clock().nowMillis();
        Board.Found found = sb.board().find(quote.ticker(), quote.side());
        String game = found == null ? "" : found.game().title();
        String who = found == null ? quote.ticker() : found.side().label();
        String starts = found == null ? "" : Sportsbook.when(found.game().startsAt(), now, sb.games().clock().zone());
        set(PICK, Menus.icon(Material.PAPER, "&6Lock in this pick?",
                lines(game, starts, who, quote).toArray(new String[0])), null);
        set(CONFIRM, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aConfirm: " + Parlor.dollars(quote.stake())
                + " on " + who, "&7The money is taken now.", "&7If they win: &6" + Parlor.money(quote.payout())
                + " &7back."), true), e -> confirm());
        set(CHANGE, Menus.icon(Material.GOLD_NUGGET, "&eChange the amount"),
                e -> new SportsStakeMenu(plugin, sb, viewer, back, event, quote.ticker(), quote.side(),
                        quote.stake()).open(viewer));
        exitTile();
    }

    /** What the pick locks in, line by line (pure). */
    static List<String> lines(String game, String starts, String who, Sportsbook.Quote q) {
        List<String> out = new ArrayList<>();
        if (!game.isEmpty()) {
            out.add("&7Game: &f" + game + (starts.isEmpty() ? "" : " &7(starts " + starts + ")"));
        }
        out.add("&7Your pick: &f" + who + " &7to win");
        out.add("&7Chance: about &f" + Lines.inHundred(q.price()) + " in 100");
        out.add("&7You put in: &6" + Parlor.dollars(q.stake()) + " &7(taken now)");
        out.add("&7If they win: &6" + Parlor.money(q.payout()) + " &7back");
        out.add("&7If they don't: nothing back");
        out.add("&7If the game is called off: your &6" + Parlor.dollars(q.stake()) + " &7back");
        out.add("&7This price is locked when you confirm.");
        return out;
    }

    private void confirm() {
        hold(ClickHold.SETTLE_MS);
        Refusal r = sb.place(viewer, quote);
        if (r == Sportsbook.MOVED) {
            sb.games().tell(viewer, r);
            Board.Found found = sb.board().find(quote.ticker(), quote.side());
            Sportsbook.Quote fresh = found == null ? null : sb.quote(found.side(), quote.stake());
            if (fresh != null) {
                new SportsConfirmMenu(plugin, sb, viewer, back, event, fresh).open(viewer);
            } else if (back != null) {
                back.run();
            }
            return;
        }
        if (r != null) {
            sb.games().tell(viewer, r);
            return;
        }
        Board.Found found = sb.board().find(quote.ticker(), quote.side());
        String who = found == null ? quote.ticker() : found.side().label();
        viewer.sendMessage(Text.of("&aYour pick is in: &6" + Parlor.dollars(quote.stake()) + " &aon " + who
                + ". &7If they win: &6" + Parlor.money(quote.payout()) + "&7. My picks shows it."));
        try {
            Sounds.paid(viewer);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the pick
        }
        new MyPicksMenu(plugin, sb, viewer, back).open(viewer);
    }
}
