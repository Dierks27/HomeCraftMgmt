package com.dierks.homecraft.gui.games.sportsbook;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.chance.sportsbook.Board;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Sports Board's first screen (54 slots): tonight's games, by league.
 *
 * <pre>
 *  row 0     0-7 a tab for each league with games (a click shows it) · 8 My picks
 *  rows 1-4  three games a row: the game (its teams and start, in the server's zone), then its two sides, each with
 *            its chance ("wins about 62 in 100") and what $10 on it gives back; a click on a side picks it
 *  row 5     45 the page before · 46 how it works · 47 your balance · 48 today · 49 Back/Close · 50 the lines
 *            · 53 the next page
 * </pre>
 *
 * Every number is the board's own ({@link Sportsbook#quote}): the same one a pick locks in.
 */
public final class SportsBoardMenu extends GameMenu {

    static final int MY_PICKS = 8;
    static final int[] ROW_STARTS = {9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42};
    static final int PREV = 45;
    static final int HOW = 46;
    static final int BALANCE = 47;
    static final int TODAY = 48;
    static final int LINES = 50;
    static final int NEXT = 53;

    private final Sportsbook sb;
    /** The league shown ({@code null}: the first with games). */
    private String league;
    private int page;

    public SportsBoardMenu(HomeCraftManagement plugin, Sportsbook game, Player viewer, Runnable back, String league,
                           int page) {
        super(plugin, game, viewer, back);
        this.sb = game;
        this.league = league;
        this.page = Math.max(0, page);
        init(54, Text.of(MenuStyle.title("Sports Board", "tonight's games")));
    }

    @Override
    protected void build() {
        fill();
        long now = sb.games().clock().nowMillis();
        Board board = sb.board();
        List<SportsbookSettings.League> tabs = new ArrayList<>();
        for (SportsbookSettings.League l : sb.settings().boardLeagues()) {
            if (!board.upcoming(now, l.key()).isEmpty()) {
                tabs.add(l);
            }
        }
        if (league == null || tabs.stream().noneMatch(l -> l.key().equals(league))) {
            league = tabs.isEmpty() ? null : tabs.get(0).key();
        }
        for (int i = 0; i < tabs.size() && i < 8; i++) {
            SportsbookSettings.League l = tabs.get(i);
            boolean here = l.key().equals(league);
            int n = board.upcoming(now, l.key()).size();
            set(i, Menus.glint(Menus.icon(here ? Material.LIME_BANNER : Material.WHITE_BANNER,
                    (here ? "&a&l" : "&e") + l.tab() + " &7(" + n + ")",
                    here ? "&7You're here." : "&eClick to see its games"), here),
                    here ? null : e -> {
                        league = l.key();
                        page = 0;
                        refresh();
                    });
        }
        set(MY_PICKS, Menus.icon(Material.WRITABLE_BOOK, "&eMy picks &7- how you did",
                "&7Your open picks and what they pay,", "&7every pick you made and how it went,",
                "&7and whether you're up or down.", "&eClick to see them"),
                e -> new MyPicksMenu(plugin, sb, viewer, this::reopen).open(viewer));

        List<Board.Game> games = league == null ? List.of() : board.upcoming(now, league);
        int pages = Math.max(1, (games.size() + ROW_STARTS.length - 1) / ROW_STARTS.length);
        page = Math.min(page, pages - 1);
        if (games.isEmpty()) {
            set(22, Menus.icon(Material.GRAY_DYE, board.fetchedAt() <= 0 ? "&7Lines are updating"
                            : "&7No games in the next " + sb.settings().hoursAhead() + " hours",
                    "&7The board shows every game of its leagues", "&7that starts soon. Look again later."), null);
        }
        for (int i = 0; i < ROW_STARTS.length; i++) {
            int at = page * ROW_STARTS.length + i;
            if (at >= games.size()) {
                break;
            }
            game(ROW_STARTS[i], games.get(at), now);
        }
        if (page > 0) {
            set(PREV, Menus.icon(Material.ARROW, "&f◀ Page " + page), e -> {
                page--;
                refresh();
            });
        }
        if (page < pages - 1) {
            set(NEXT, Menus.icon(Material.ARROW, "&fPage " + (page + 2) + " ▶"), e -> {
                page++;
                refresh();
            });
        }
        set(HOW, SportsTiles.howTile(sb.settings().rtp()), null);
        set(BALANCE, SportsTiles.balanceTile(plugin, viewer), null);
        set(TODAY, SportsTiles.todayTile(sb, viewer), null);
        set(LINES, linesTile(board, now), null);
        exitTile();
    }

    /** One game: its own tile, then its sides. */
    private void game(int slot, Board.Game g, long now) {
        String starts = Sportsbook.when(g.startsAt(), now, sb.games().clock().zone());
        set(slot, Menus.icon(Material.PAPER, "&f" + g.title(), "&7Starts &f" + starts,
                "&7Picks close when it starts."), null);
        for (int k = 0; k < 2 && k < g.sides().size(); k++) {
            Board.Side s = g.sides().get(k);
            Sportsbook.State state = sb.state(g, s, now);
            Sportsbook.Quote q = shown(s);
            List<String> lore = SportsTiles.sideLines(g, s, state, q, starts);
            if (state == Sportsbook.State.OPEN && q != null) {
                set(slot + 1 + k, Menus.icon(k == 0 ? Material.LIME_CONCRETE : Material.LIGHT_BLUE_CONCRETE,
                        "&a" + s.label(), lore.toArray(new String[0])),
                        e -> new SportsStakeMenu(plugin, sb, viewer, this::reopen, g.event(), s.ticker(), s.side())
                                .open(viewer));
            } else {
                set(slot + 1 + k, SportsTiles.unlit(s.label(), lore), null);
            }
        }
    }

    /** What the board shows a side paying: $10 in, or the most it takes when that is less. */
    private Sportsbook.Quote shown(Board.Side s) {
        Sportsbook.Quote q = sb.quote(s, Sportsbook.WALL_STAKE);
        if (q == null) {
            int most = sb.mostFor(s.price());
            q = most > 0 && most < Sportsbook.WALL_STAKE ? sb.quote(s, most) : null;
        }
        return q;
    }

    private org.bukkit.inventory.ItemStack linesTile(Board board, long now) {
        String read = board.fetchedAt() <= 0 ? "not read yet" : "read " + Menus.duration(now - board.fetchedAt())
                + " ago";
        if (sb.off()) {
            return Menus.icon(Material.RED_CONCRETE, "&cThe Sports Board is closed right now",
                    "&7Your open picks are still paid", "&7when their games are decided.");
        }
        if (!sb.linesOpen(now)) {
            return Menus.icon(Material.YELLOW_CONCRETE, "&eLines are updating", "&7Picks open again in a moment.",
                    "&7Lines " + read + ".");
        }
        return Menus.icon(Material.LIME_CONCRETE, "&aLines are live", "&7Lines " + read + " from Kalshi.",
                "&7They are read again every " + sb.settings().refreshMinutes() + " minutes.");
    }

    /** This screen again, as it was (Back from a screen it opened). */
    private void reopen() {
        new SportsBoardMenu(plugin, sb, viewer, back, league, page).open(viewer);
    }
}
