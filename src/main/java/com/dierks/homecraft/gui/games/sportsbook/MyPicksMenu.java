package com.dierks.homecraft.gui.games.sportsbook;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Parlor;
import com.dierks.homecraft.games.chance.sportsbook.Lines;
import com.dierks.homecraft.games.chance.sportsbook.PickHistory;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.storage.SportsPicksDao;
import com.dierks.homecraft.storage.SportsPicksDao.Pick;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * My picks (54 slots), 0.60.0 a real history (the owner: "any place we can check our bets after the fact to show how
 * we did?"): every pick the player made, newest first (the open ones, then every settled one with how it went and the
 * game's final score when it was read), {@value PickHistory#PER_PAGE} a page.
 *
 * <pre>
 *  row 0     4 how you did (made, won, lost, money in and back, up or down overall and this week), 5 everyone's
 *  rows 1-4  9-44 the picks of this page
 *  row 5     45/53 page arrows when needed, 48 how it works, 49 Back
 * </pre>
 */
public final class MyPicksMenu extends GameMenu {

    /** The first slot of the picks (row 1). */
    static final int FIRST = 9;
    static final int SUMMARY = 4;
    static final int EVERYONE = 5;

    private final Sportsbook sb;
    private int page;

    public MyPicksMenu(HomeCraftManagement plugin, Sportsbook game, Player viewer, Runnable back) {
        this(plugin, game, viewer, back, 0);
    }

    public MyPicksMenu(HomeCraftManagement plugin, Sportsbook game, Player viewer, Runnable back, int page) {
        super(plugin, game, viewer, back);
        this.sb = game;
        this.page = Math.max(0, page);
        init(54, Text.of(MenuStyle.title("Sports Board", "my picks")));
    }

    @Override
    protected void build() {
        fill();
        long now = sb.games().clock().nowMillis();
        ZoneId zone = sb.games().clock().zone();
        List<Pick> picks = sb.history(viewer.getUniqueId());
        if (picks == null) {
            set(22, Menus.icon(Material.GRAY_DYE, "&7Your picks can't be read right now", "&7Look again in a moment."),
                    null);
            picks = List.of();
        } else if (picks.isEmpty()) {
            set(22, Menus.icon(Material.GRAY_DYE, "&7No picks yet", "&7Pick a side on the board to see it here."),
                    null);
        }
        PickHistory.Summary all = PickHistory.of(picks, 0);
        PickHistory.Summary week = PickHistory.of(picks, PickHistory.weekStart(now, zone));
        set(SUMMARY, Menus.icon(all.net() >= 0 ? Material.EMERALD : Material.PAPER, summaryName(all),
                PickHistory.lines(all, week).toArray(new String[0])), null);
        SportsPicksDao.Totals t = sb.everyone();
        if (t != null && t.picks() > 0) {
            long net = t.back() - t.in() * 100L;
            set(EVERYONE, Menus.icon(Material.BOOKSHELF, "&eEveryone &7- " + PickHistory.upDown(net),
                    "&7Every pick on the server, together:",
                    "&7" + t.picks() + " settled, &a" + t.won() + " &7won, " + t.lost() + " didn't win",
                    "&7Money in: &6" + Parlor.dollars((int) t.in()) + " &7- money back: &6" + Parlor.money(t.back())),
                    null);
        }
        int pages = PickHistory.pages(picks.size(), PickHistory.PER_PAGE);
        page = Math.min(page, pages - 1);
        List<Pick> shown = PickHistory.page(picks, page, PickHistory.PER_PAGE);
        for (int i = 0; i < shown.size(); i++) {
            Pick p = shown.get(i);
            set(FIRST + i, Menus.icon(icon(p), title(p), lore(p, now, zone).toArray(new String[0])), null);
        }
        if (page > 0) {
            set(MenuStyle.PREV, Menus.prevPage(page, pages), e -> {
                page--;
                refresh();
            });
        }
        if (page < pages - 1) {
            set(MenuStyle.NEXT, Menus.nextPage(page + 2, pages), e -> {
                page++;
                refresh();
            });
        }
        set(MenuStyle.HELP, SportsTiles.howTile(sb.settings().rtp()), null);
        exitTile();
    }

    /** The summary tile's name (pure): the key fact, so Bedrock shows it without a long press. */
    static String summaryName(PickHistory.Summary all) {
        if (all.made() == 0) {
            return "&6How you did &7- no picks yet";
        }
        if (all.settled() == 0) {
            return "&6How you did &7- " + all.open() + " waiting";
        }
        return "&6How you did &7- " + PickHistory.upDown(all.net());
    }

    private static Material icon(Pick p) {
        return switch (p.status()) {
            case SportsPicksDao.OPEN -> Material.PAPER;
            case SportsPicksDao.WON -> Material.EMERALD;
            case SportsPicksDao.LOST -> Material.GRAY_DYE;
            default -> Material.GOLD_NUGGET;
        };
    }

    /** A pick's name (pure). */
    static String title(Pick p) {
        return switch (p.status()) {
            case SportsPicksDao.OPEN -> "&e" + p.pick() + " &7- waiting for the game";
            case SportsPicksDao.WON -> "&a" + p.pick() + " won: &6" + Parlor.money(p.paid()) + " back";
            case SportsPicksDao.LOST -> "&7" + p.pick() + " didn't win";
            case SportsPicksDao.VOID -> "&7" + p.pick() + ": called off, &6" + Parlor.money(p.paid()) + " &7back";
            default -> "&7" + p.pick() + ": no result, &6" + Parlor.money(p.paid()) + " &7back";
        };
    }

    /** A pick's lines (pure). */
    static List<String> lore(Pick p, long now, ZoneId zone) {
        List<String> out = new ArrayList<>();
        out.add("&7" + p.game() + (p.startsAt() > 0 ? " &7(" + (p.startsAt() > now ? "starts " : "started ")
                + Sportsbook.when(p.startsAt(), now, zone) + ")" : ""));
        if (p.finalScore() != null && !p.finalScore().isBlank()) {
            out.add("&f" + p.finalScore());
        }
        out.add("&7Put in: &6" + Parlor.dollars(p.stake()) + " &7at about " + Lines.inHundred(p.price()) + " in 100");
        if (p.open()) {
            out.add("&7If they win: &6" + Parlor.money(p.payout()) + " &7back");
            out.add("&7Locked in when you picked.");
            out.add("&7Paid when the real game is decided.");
        } else if (SportsPicksDao.VOID.equals(p.status()) || SportsPicksDao.LAPSED.equals(p.status())) {
            out.add("&7Your money came back.");
        } else {
            long net = p.paid() - p.stake() * 100L;
            out.add("&7This pick: " + (net > 0 ? "&a" : "&7") + PickHistory.upDown(net));
        }
        return out;
    }
}
